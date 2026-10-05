package com.neethu.aiavatar_sdk

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 自由说话（连续聆听）的三件套：
 *  - [WavEncoder] PCM16LE → WAV（纯函数，喂 ASR 的 multipart）
 *  - [SpeechVad] 软件语音端点检测（纯函数：自适应噪声地板 + 起音确认 + 静默
 *    悬停判句尾 + 说话期高门限 barge-in）
 *  - [FreeSpeechController] AudioRecord 16k 单声道采音线程：VAD 判定句尾后
 *    把该句 PCM（含 ~280ms 前滚）编码成 WAV 交给 [FreeSpeechController.onUtterance]
 *
 * 与按住说话共享同一条 ASR/发送链路（DemoScreen 接线）；自由态自动发送。
 * 回声策略：视频模式用 VOICE_COMMUNICATION 音源（硬件 AEC），虚拟人说话期
 * VAD 抬高门限 + 600ms 宽限 + 350ms 持续才发 BargeIn（打断当前回复），避免
 * 扬声器残留触发"自己打断自己"。
 */

/** PCM16LE 单声道 → 44 字节标准 WAV 头。纯 JVM 可测。 */
object WavEncoder {
    fun encode(pcm16Le: ByteArray, sampleRateHz: Int, channels: Int = 1): ByteArray {
        val out = ByteArray(44 + pcm16Le.size)
        val ascii = { dst: ByteArray, off: Int, s: String ->
            s.toByteArray(Charsets.US_ASCII).copyInto(dst, off)
        }
        val le32 = { dst: ByteArray, off: Int, v: Int ->
            dst[off] = v.toByte(); dst[off + 1] = (v shr 8).toByte()
            dst[off + 2] = (v shr 16).toByte(); dst[off + 3] = (v shr 24).toByte()
        }
        val le16 = { dst: ByteArray, off: Int, v: Int ->
            dst[off] = v.toByte(); dst[off + 1] = (v shr 8).toByte()
        }
        ascii(out, 0, "RIFF"); le32(out, 4, 36 + pcm16Le.size); ascii(out, 8, "WAVE")
        ascii(out, 12, "fmt "); le32(out, 16, 16)
        le16(out, 20, 1) // PCM
        le16(out, 22, channels)
        le32(out, 24, sampleRateHz)
        le32(out, 28, sampleRateHz * channels * 2) // byte rate
        le16(out, 32, channels * 2) // block align
        le16(out, 34, 16) // bits
        ascii(out, 36, "data"); le32(out, 40, pcm16Le.size)
        pcm16Le.copyInto(out, 44)
        return out
    }
}

/**
 * 标准 WAV（16bit PCM，[WavEncoder] 的输出）字节数 → 时长毫秒。
 * 技能快路径用：VAD 判完句尾、ASR 之前，猜拳技能靠它判「短句=出拳信号」。
 */
fun wavDurationMs(wav: ByteArray, sampleRateHz: Int = 16_000): Long =
    (wav.size - 44).coerceAtLeast(0) * 1_000L / (2 * sampleRateHz)

/**
 * 软件语音端点检测（纯 JVM）。按 ~20ms 帧喂 [SpeechVad.feed]（RMS 归一化
 * 0..1 + 墙钟 + 虚拟人是否在说话），返回判定事件：
 *  - [SpeechVad.Event.SpeechStarted]：确认起音（调用方 flush 前滚、高亮"听到"）
 *  - [SpeechVad.Event.SpeechEnded]：句尾（静默超悬停且时长达标）→ 调用方切片发 ASR
 *  - [SpeechVad.Event.TooShort]：短促噪声（咳嗽/碰撞）→ 丢弃
 *  - [SpeechVad.Event.BargeIn]：虚拟人说话期间检测到用户大声开口 → 调用方
 *    interrupt 当前回复，并按 SpeechStarted 一样开始捕获这句话
 *
 * 门限全部相对自适应噪声地板（静默期慢速跟随），外加绝对兜底——安静房间
 * 低噪地板不会误触，嘈杂环境地板抬高后轻声说话仍可触发（ floor*4 兜在
 * 绝对值之上）。
 */
class SpeechVad(
    private val frameMs: Long = 20,
    /** 起音绝对兜底门限(≈-32dBFS,一臂距离正常音量;比它更轻的靠噪声地板相对项兜误触)。 */
    private var startAbsolute: Float = 0.025f,
    /** 起音相对门限 = max(startAbsolute, floor*[floorMultiplier], floor+[floorOffset])。 */
    private val floorMultiplier: Float = 4f,
    private val floorOffset: Float = 0.012f,
    /** 句尾静默门限 = 起音门限 × [endThresholdRatio]。 */
    private val endThresholdRatio: Float = 0.6f,
    /** barge-in 绝对门限（虚拟人说话期用户开口要比正常起音响一截；硬 AEC 残留远低于此）。 */
    private var bargeAbsolute: Float = 0.10f,
    private val bargeMultiplier: Float = 7f,
    /** barge-in 需要持续超过该时长才触发（滤扬声器尾音/一声咳嗽）。 */
    private val bargeSustainMs: Long = 350,
    /** 虚拟人开始说话后的宽限期（回声建立期），期间不判 barge-in。 */
    private val bargeGraceMs: Long = 600,
    /** 起音确认时长（连续超门限才算开口）。 */
    private val startConfirmMs: Long = 60,
    /** 句尾静默悬停。 */
    private var hangoverMs: Long = 800,
    /** 短于该时长的发声按噪声丢弃。 */
    private val minUtteranceMs: Long = 280,
    /** 超长强制断句（防持续背景声无限占用）。 */
    private val maxUtteranceMs: Long = 15_000,
) {
    sealed interface Event {
        data object SpeechStarted : Event
        data object SpeechEnded : Event
        data object BargeIn : Event
        data object TooShort : Event
    }

    internal enum class Phase { SILENCE, CAPTURING }

    private var phase = Phase.SILENCE
    private var noiseFloor = 0.01f
    private var loudRunMs = 0L
    private var bargeLoudRunMs = 0L
    private var silentRunMs = 0L
    private var voicedMs = 0L
    private var speechStartAt = 0L
    private var speakingSince = -1L
    private var bargeFired = false
    private var lastAvatarSpeaking = false

    /** 当前是否在捕获一句话（调用方据此决定 PCM 进段缓冲还是前滚环）。 */
    val capturing: Boolean get() = phase == Phase.CAPTURING

    /**
     * 运行中调参（设置页「自由说话」区实时生效，采音中调用也只影响下一次
     * 判定帧）；噪声地板等运行状态不动，仅覆盖用户可调的三项。
     */
    fun applyTuning(startAbsolute: Float, bargeAbsolute: Float, hangoverMs: Long) {
        this.startAbsolute = startAbsolute
        this.bargeAbsolute = bargeAbsolute
        this.hangoverMs = hangoverMs
    }

    /** 当前生效的起音门限与噪声地板（真机调参用：比对 level 日志里的 rms 与门限差距）。 */
    val debugStartThreshold: Float
        get() = maxOf(startAbsolute, noiseFloor * floorMultiplier, noiseFloor + floorOffset)
    val debugNoiseFloor: Float get() = noiseFloor

    fun reset() {
        phase = Phase.SILENCE
        noiseFloor = 0.01f
        loudRunMs = 0; bargeLoudRunMs = 0; silentRunMs = 0; voicedMs = 0
        speakingSince = -1; bargeFired = false; lastAvatarSpeaking = false
    }

    fun feed(frameRms: Float, nowMs: Long, avatarSpeaking: Boolean): List<Event> {
        // 虚拟人说话沿：上升沿记起点（宽限期用），期间不打扰噪声地板
        if (avatarSpeaking && !lastAvatarSpeaking) {
            speakingSince = nowMs
            bargeFired = false
        } else if (!avatarSpeaking && lastAvatarSpeaking) {
            speakingSince = -1L
            bargeLoudRunMs = 0
        }
        lastAvatarSpeaking = avatarSpeaking

        val startThreshold = maxOf(startAbsolute, noiseFloor * floorMultiplier, noiseFloor + floorOffset)

        return when (phase) {
            Phase.SILENCE -> {
                val events = mutableListOf<Event>()
                if (avatarSpeaking) {
                    // barge-in 判定：宽限期后，连续 bargeLoud 持续达标才触发一次
                    val bargeThreshold = maxOf(bargeAbsolute, noiseFloor * bargeMultiplier)
                    bargeLoudRunMs = if (frameRms >= bargeThreshold) bargeLoudRunMs + frameMs else 0
                    val pastGrace = speakingSince in 0..nowMs && nowMs - speakingSince >= bargeGraceMs
                    if (!bargeFired && pastGrace && bargeLoudRunMs >= bargeSustainMs) {
                        bargeFired = true
                        enterCapture(nowMs)
                        events.add(Event.BargeIn)
                    }
                    // 说话期间不做普通起音、不更新地板（防 TTS 残留抬地板/误起音）
                    return events
                }
                if (frameRms < startThreshold) {
                    loudRunMs = 0
                    noiseFloor = (noiseFloor * 0.95f + frameRms * 0.05f).coerceAtLeast(0.003f)
                } else {
                    loudRunMs += frameMs
                    if (loudRunMs >= startConfirmMs) {
                        enterCapture(nowMs)
                        events.add(Event.SpeechStarted)
                    }
                }
                events
            }

            Phase.CAPTURING -> {
                val endThreshold = startThreshold * endThresholdRatio
                if (frameRms >= endThreshold) {
                    silentRunMs = 0
                    voicedMs += frameMs
                } else {
                    silentRunMs += frameMs
                }
                val wallMs = nowMs - speechStartAt
                when {
                    wallMs >= maxUtteranceMs -> listOf(endCapture(Event.SpeechEnded))
                    // 句长按"有声时长"算:悬停静默不计入,短促咳嗽不至于凑时长
                    silentRunMs >= hangoverMs && voicedMs >= minUtteranceMs ->
                        listOf(endCapture(Event.SpeechEnded))
                    silentRunMs >= hangoverMs -> listOf(endCapture(Event.TooShort))
                    else -> emptyList()
                }
            }
        }
    }

    private fun enterCapture(nowMs: Long) {
        phase = Phase.CAPTURING
        speechStartAt = nowMs - loudRunMs
        silentRunMs = 0
        voicedMs = 0
        loudRunMs = 0
        bargeLoudRunMs = 0
    }

    private fun endCapture(event: Event): Event {
        phase = Phase.SILENCE
        silentRunMs = 0
        loudRunMs = 0
        return event
    }
}

/**
 * 自由说话采音控制器（AudioRecord 16k 单声道 + [SpeechVad]）。单实例由
 * DemoScreen 持有；[start]/[stop] 配对（切模式/关开关/组合销毁时 stop）。
 * 回调都在采音线程触发，UI 侧自行 post 到主线程。
 */
class FreeSpeechController(
    @Suppress("unused") private val context: Context,
    private val vad: SpeechVad = SpeechVad(),
    private val sampleRateHz: Int = 16_000,
) {

    /** 用户可读文案表（多语言支持）：start 的异常消息语言，MainActivity 随语言更新。 */
    var texts: com.neethu.aiavatar_sdk.i18n.Strings =
        com.neethu.aiavatar_sdk.i18n.Strings(com.neethu.corelib.Lang.ZH)

    /** 一句话说完（WAV 16k 单声道）。采音线程回调。 */
    var onUtterance: ((wav: ByteArray) -> Unit)? = null

    /** 虚拟人说话时用户大声开口 → 打断当前回复。采音线程回调。 */
    var onBargeIn: (() -> Unit)? = null

    /** "听到你说话"高亮变化。采音线程回调。 */
    var onHearingChanged: ((hearing: Boolean) -> Unit)? = null

    /** 虚拟人是否在说话（DemoScreen 注入 phase 镜像，采音线程每帧读）。 */
    @Volatile
    var isAvatarSpeaking: () -> Boolean = { false }

    @Volatile
    var running: Boolean = false
        private set

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val isCapturing: Boolean get() = vad.capturing

    /** 设置页实时调参（采音中也可调用，见 [SpeechVad.applyTuning]）。 */
    fun applyTuning(startAbsolute: Float, bargeAbsolute: Float, hangoverMs: Long) =
        vad.applyTuning(startAbsolute, bargeAbsolute, hangoverMs)

    /** 开始连续聆听；麦克风被占用/不可用时抛 [IllegalStateException]。 */
    @SuppressLint("MissingPermission") // 调用方已确保 RECORD_AUDIO 授权
    fun start(echoCancellation: Boolean) {
        check(!running) { texts.freeSpeechAlreadyRunning }
        val source = if (echoCancellation) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val frameShorts = sampleRateHz / 50 // 20ms
        val minBuf = AudioRecord.getMinBufferSize(sampleRateHz, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuf > 0) { texts.freeSpeechMinBufferFailed }
        val record = AudioRecord(
            source, sampleRateHz, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, frameShorts * 16),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException(texts.micUnavailable)
        }
        vad.reset()
        this.record = record
        running = true
        thread = Thread({ loop(record, frameShorts) }, "free-speech").apply { start() }
        Log.i(TAG, "free speech started (aec=$echoCancellation)")
    }

    fun stop() {
        if (!running) return
        running = false
        // stop() 让阻塞中的 read 立即返回，线程收尾 release
        runCatching { record?.stop() }
        thread?.join(500)
        thread = null
        record = null
        Log.i(TAG, "free speech stopped")
    }

    private fun loop(record: AudioRecord, frameShorts: Int) {
        val shorts = ShortArray(frameShorts)
        // 前滚环：起音前 ~280ms 的音频一起入段，首音节不削头
        val preRollFrames = 14
        val preRoll = ArrayDeque<ByteArray>(preRollFrames)
        val segment = ByteArrayOutputStream()
        var hearing = false
        var levelLogAt = 0L
        try {
            record.startRecording()
            while (running) {
                val n = try {
                    record.read(shorts, 0, frameShorts)
                } catch (t: Throwable) {
                    break
                }
                if (n <= 0) continue
                val frameBytes = leBytes(shorts, n)
                val rms = rmsOf(shorts, n)
                val now = SystemClock.elapsedRealtime()
                for (event in vad.feed(rms, now, isAvatarSpeaking())) {
                    when (event) {
                        SpeechVad.Event.SpeechStarted, SpeechVad.Event.BargeIn -> {
                            preRoll.forEach { segment.write(it) }
                            preRoll.clear()
                            segment.write(frameBytes)
                            if (!hearing) {
                                hearing = true
                                onHearingChanged?.invoke(true)
                            }
                            if (event == SpeechVad.Event.BargeIn) {
                                Log.i(TAG, "[InfoStreamDectect] barge-in")
                                onBargeIn?.invoke()
                            }
                        }
                        SpeechVad.Event.SpeechEnded -> {
                            if (segment.size() > 0) {
                                val wav = WavEncoder.encode(segment.toByteArray(), sampleRateHz)
                                Log.i(TAG, "[InfoStreamDectect] utterance ${wav.size}B")
                                onUtterance?.invoke(wav)
                            }
                            segment.reset()
                            if (hearing) {
                                hearing = false
                                onHearingChanged?.invoke(false)
                            }
                        }
                        SpeechVad.Event.TooShort -> {
                            segment.reset()
                            if (hearing) {
                                hearing = false
                                onHearingChanged?.invoke(false)
                            }
                        }
                    }
                }
                if (vad.capturing) {
                    segment.write(frameBytes)
                } else {
                    preRoll.addLast(frameBytes)
                    while (preRoll.size > preRollFrames) preRoll.removeFirst()
                    // 1Hz 电平日志：不触发时先看 rms 与门限的差距再动参数
                    if (now - levelLogAt >= 1000) {
                        levelLogAt = now
                        Log.d(TAG, "level rms=%.3f startTh=%.3f floor=%.4f".format(rms, vad.debugStartThreshold, vad.debugNoiseFloor))
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loop exit: ${t.message}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            if (hearing) onHearingChanged?.invoke(false)
        }
    }

    private fun leBytes(shorts: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = shorts[i].toInt()
            out[i * 2] = v.toByte()
            out[i * 2 + 1] = (v shr 8).toByte()
        }
        return out
    }

    private fun rmsOf(shorts: ShortArray, n: Int): Float {
        var acc = 0.0
        for (i in 0 until n) {
            val v = shorts[i].toDouble()
            acc += v * v
        }
        return (kotlin.math.sqrt(acc / n) / 32768.0).toFloat()
    }

    companion object {
        private const val TAG = "FreeSpeech"
    }
}
