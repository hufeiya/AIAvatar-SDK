package com.neethu.aiavatar_sdk

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 对话输入四模式（互斥，不能混用；左上角下拉框切换，持久化）：
 * - [MANUAL] 手动点击：完整 demo UI——全部 FAB 面板按钮可见 + 打字输入；
 * - [TEXT]   打字输入：隐藏所有界面按钮，只留输入条（打字）；
 * - [VOICE]  语音模式：隐藏所有界面按钮，只留按住说话 + 字幕；
 * - [VIDEO]  视频模式：语音模式的一切 + 用户相机 PiP 小窗 + 人脸注视追踪 +
 *   发送时附相机抓拍（多模态）。准入门控：只有多模态（可收图）大模型才
 *   能进入（见 [videoModeBlockReason]）。
 * 任何模式下都可用下拉框旁的显隐开关临时显示/隐藏按钮（进入打字/语音/
 * 视频模式时自动隐藏，切回手动点击自动显示）。
 */
enum class InputMode {
    MANUAL,
    TEXT,
    VOICE,
    VIDEO,
}

/** 从文件扩展名推 ASR multipart 所需的 MIME（[VoiceRecorder] 产物是 m4a）。 */
fun mimeForFileName(name: String): String = when (File(name).extension.lowercase()) {
    "m4a", "mp4" -> "audio/mp4"
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "amr" -> "audio/amr"
    "ogg", "opus" -> "audio/ogg"
    "webm" -> "audio/webm"
    "flac" -> "audio/flac"
    else -> "application/octet-stream"
}

/**
 * 按住说话的 MediaRecorder 封装（任务 4）：AAC 编码、单声道 16 kHz、
 * MPEG_4（.m4a）容器，落 cacheDir，识别完成后即删。
 *
 * 选型依据：m4a 是 OpenAI /audio/transcriptions 的官方格式之一（硅基流动
 * 同协议兼容）；16 kHz 单声道足够语音识别且文件小；AMR 音质差、采样率
 * 只有 8k，MediaRecorder 又没有 mp3 编码器，故 AAC/m4a 最稳。
 */
class VoiceRecorder(private val context: Context) {

    /** 用户可读文案表（多语言支持）：start 的异常消息语言，MainActivity 随语言更新。 */
    var texts: com.neethu.aiavatar_sdk.i18n.Strings =
        com.neethu.aiavatar_sdk.i18n.Strings(com.neethu.corelib.Lang.ZH)

    private val cacheDir = context.cacheDir
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null

    val isRecording: Boolean get() = recorder != null

    /**
     * 开始录音到新临时文件；麦克风被占等失败时抛 [IllegalStateException]。
     *
     * [echoCancellation]（视频模式）：音频源用 VOICE_COMMUNICATION 走平台
     * 通话链路的硬件 AEC/NS——虚拟人扬声器出声时麦克风会把 TTS 混进去，
     * MIC 源无回声抑制。MediaRecorder 拿不到 audio session id，挂不了
     * session 级 [android.media.audiofx.AcousticEchoCanceler]，音源切换是
     * 平台标准做法；半双工（按下先 interrupt）仍是主防线，WebRTC 软件
     * APM 仅在真机实测仍有残留回声时再引入。
     */
    fun start(echoCancellation: Boolean = false): File {
        check(recorder == null) { texts.recorderAlreadyRunning }
        val file = File(cacheDir, "voice_input_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            r.setAudioSource(
                if (echoCancellation) MediaRecorder.AudioSource.VOICE_COMMUNICATION
                else MediaRecorder.AudioSource.MIC
            )
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setAudioChannels(1)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (t: Throwable) {
            r.release()
            file.delete()
            throw IllegalStateException(texts.recorderStartFailed(t.message), t)
        }
        recorder = r
        outFile = file
        return file
    }

    /**
     * 停止并返回录音文件；按住太短/无有效采样（`stop()` 抛 RuntimeException）
     * 返回 null 并清理现场。
     */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val file = outFile
        outFile = null
        return try {
            r.stop()
            file
        } catch (_: RuntimeException) {
            file?.delete()
            null
        } finally {
            r.release()
        }
    }

    /** 停止（如还在录）并删除产物。 */
    fun cancel() {
        stop()?.delete()
    }
}
