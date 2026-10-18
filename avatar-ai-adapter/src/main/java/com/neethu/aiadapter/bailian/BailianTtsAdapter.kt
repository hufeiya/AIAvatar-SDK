package com.neethu.aiadapter.bailian

import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import kotlin.math.floor

/**
 * 阿里云百炼「通义千问 TTS」（qwen3-tts-flash）适配器：**DashScope 原生协议**，
 * 不是 OpenAI 兼容 /audio/speech——后者在 compatible-mode（专属端点与公共
 * dashscope 同查）实测全 404（2026-10-18 真 key 核验），语音合成只走这里：
 *
 * ```
 * POST https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation
 * {"model":"qwen3-tts-flash","input":{"text":"…","voice":"Cherry"}}
 * → {"output":{"audio":{"url":"https://…oss…/xxx.wav",…}},…}
 * ```
 *
 * 响应给 OSS 临时下载链（audio.data 恒空串，音频全在 url），适配器负责下载。
 * 实测输出恒 WAV PCM 16bit 单声道 24kHz，且 RIFF/data 块 size 字段是流式占位
 * 值（0x7FFFFF…，硅基流动同款病理）——解析按「chunk 头迭代 + data 读到文件尾」
 * 宽式处理（[WavPcm16Reader]）。
 *
 * 输出重采样到 16kHz 后按 RAW_PCM_16LE 返回（火山 seed-tts 同款「免解码直喂」
 * 形态）：qwen3-tts 无采样率请求参数，而 wLipSync 的分数降采样路径在非整数
 * 倍率下 MFCC 得分塌缩（CosyVoice2 24kHz 实测口型死，修法档案见
 * docs/ai-layer-handoff.md 附录A 第 2 条），3:2 线性插值重采样让口型管线走
 * 整数 2:1 好路。音色 Cherry/Ethan/Serena/Chelsie/Jennifer/Ryan 逐一实测可合成。
 *
 * 限流（2026-10-18 真机实锤）：SpeechPipeline 一轮并发 4 句合成会撞
 * qwen3-tts 的并发上限（HTTP 429 Throttling.RateQuota，curl 顺序连发则全 200）。
 * 生成请求在本适配器内全局串行，把并发摊成自然间隔的序列；仍被限流时
 * （跨轮突发/同 key 他端在用）退避重试兜底，见 [postGeneration]。
 */
class BailianTtsAdapter(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    /** 可注入换端点（MockWebServer 单测）；生产用公共 dashscope 原生端点。 */
    private val endpoint: String = DEFAULT_ENDPOINT,
    /** 429 退避基值（毫秒）；单测注入 1ms 避免真实等待。 */
    private val throttleBackoffMs: Long = 1_000,
) : TtsAdapter {

    /** 生成请求串行锁：见类注释「限流」段。 */
    private val generationMutex = Mutex()

    override suspend fun synthesize(text: String, config: TtsConfig): TtsResult =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) throw IOException("Bailian TTS: empty text")
            val body = buildJsonObject {
                put("model", config.model.ifBlank { DEFAULT_MODEL })
                put("input", buildJsonObject {
                    put("text", text)
                    put("voice", config.voice.ifBlank { DEFAULT_VOICE })
                })
            }.toString()

            val audioRef = postGeneration(body)

            val bytes = if (audioRef.startsWith("data:")) {
                Base64.getDecoder().decode(audioRef.removePrefix("data:base64,"))
            } else {
                runCatching {
                    client.newCall(Request.Builder().url(audioRef).get().build()).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("Bailian TTS audio download HTTP ${r.code}")
                        r.body?.bytes() ?: throw IOException("Bailian TTS audio download: empty body")
                    }
                }.getOrElse { e -> throw IOException("Bailian TTS audio download failed: ${e.message}") }
            }

            val wav = WavPcm16Reader.read(bytes)
                ?: throw IOException("Bailian TTS: not a 16-bit PCM WAV (${bytes.size}B)")
            val resampled = resamplePcm16(wav.samples, wav.sampleRate, TARGET_SAMPLE_RATE)
            // Short → 16bit LE 字节（火山 PCM 直喂同款形态）
            val pcm = ByteArray(resampled.size * 2)
            for (i in resampled.indices) {
                val v = resampled[i].toInt()
                pcm[2 * i] = (v and 0xFF).toByte()
                pcm[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            TtsResult(pcm, TtsAudioFormat.RAW_PCM_16LE, TARGET_SAMPLE_RATE)
        }

    /**
     * 生成请求（串行 + 429 退避重试）：互斥锁把 SpeechPipeline 的并发合成摊成
     * 自然间隔的序列（每趟生成本身 1~3s，天然拉开间隔）；仍撞限流就按
     * [throttleBackoffMs] 基值指数退避重试 [THROTTLE_RETRIES] 次。退避用
     * 协程 [delay]——cancelTurn 打断时原样传播取消语义。
     */
    private suspend fun postGeneration(body: String): String = generationMutex.withLock {
        repeat(THROTTLE_RETRIES) { attempt ->
            try {
                return@withLock postGenerationOnce(body)
            } catch (t: Throttled) {
                delay(throttleBackoffMs shl attempt)
            }
        }
        postGenerationOnce(body) // 最后一趟：再 429 就把错误抛给上层（SentenceFailed）
    }

    /**
     * 单趟生成请求：200 → 解析 output.audio（url 先升 https 再下载，见
     * [httpsForAliyun]；data 内联 base64 兼容形态）；429 → [Throttled]（可重试）；
     * 其他非 2xx / 无音频字段 → IOException 直抛。
     */
    private fun postGenerationOnce(body: String): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = "Bailian TTS HTTP ${response.code}: ${raw.take(500)}"
                throw if (response.code == 429) Throttled(msg) else IOException(msg)
            }
            val audio = runCatching {
                Json.parseToJsonElement(raw).jsonObject["output"]?.jsonObject?.get("audio")?.jsonObject
            }.getOrNull()
            val url = audio?.get("url")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?.let { httpsForAliyun(it) }
            val data = audio?.get("data")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            return when {
                data != null -> "data:base64,$data"
                url != null -> url
                else -> throw IOException("Bailian TTS: no audio url/data: ${raw.take(200)}")
            }
        }
    }

    companion object {
        /** DashScope 原生多模态生成端点（compatible-mode 无语音合成路由，见类注释）。 */
        const val DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        const val DEFAULT_MODEL = "qwen3-tts-flash"
        const val DEFAULT_VOICE = "Cherry"

        /** 429 限流重试次数（不含首发；每次前退避 1s/2s 指数递增）。 */
        const val THROTTLE_RETRIES = 2

        /** wLipSync 标定输入率：重采样目标（见类注释）。 */
        const val TARGET_SAMPLE_RATE = 16_000

        /**
         * DashScope 下发的 OSS 下载链是 http:// 明文，而 app（targetSdk≥28）默认
         * 禁明文流量，直接下载会被 Android 网络安全策略拦成 CLEARTEXT IOException
         * （2026-10-18 真机实锤）。Aliyun OSS 全端点支持 https，且 OSS 签名不覆盖
         * 协议头、换 https 不影响签名——所以 aliyuncs.com 域一律升 https；其他域
         * （单测 MockWebServer 的 localhost）原样返回。
         */
        fun httpsForAliyun(url: String): String {
            val host = url.substringAfter("://", "").substringBefore('/')
            val isAliyun = host == "aliyuncs.com" || host.endsWith(".aliyuncs.com")
            return if (url.startsWith("http://") && isAliyun) "https://${url.substringAfter("://")}" else url
        }

        /** 16bit LE 采样数组线性插值重采样（from==to 原样返回；纯 JVM 可测）。 */
        fun resamplePcm16(samples: ShortArray, from: Int, to: Int): ShortArray {
            if (from == to || samples.isEmpty() || from <= 0 || to <= 0) return samples
            val out = ShortArray(((samples.size.toLong() * to) / from).toInt())
            val step = from.toDouble() / to
            for (i in out.indices) {
                val pos = i * step
                val i0 = floor(pos).toInt().coerceIn(0, samples.size - 1)
                val i1 = (i0 + 1).coerceAtMost(samples.size - 1)
                val frac = pos - i0
                out[i] = (samples[i0] + (samples[i1] - samples[i0]) * frac).toInt().toShort()
            }
            return out
        }
    }
}

/** 宽式 WAV 解析结果：16bit 单声道采样（立体声下混）+ 采样率。 */
internal class WavPcm16(val samples: ShortArray, val sampleRate: Int)

/** DashScope 429 Throttling.RateQuota 专档：唯一可重试的错误类型（其余直抛）。 */
private class Throttled(message: String) : IOException(message)

/**
 * 16bit PCM WAV 的宽式解析：按 chunk 头迭代（fmt/data），data 块 size 为占位值
 * （0x7FFFFF…）或越界时读到文件尾；立体声下混为单声道。非 RIFF/非 16bit PCM
 * 返回 null。纯 JVM（app 的 PcmDecoder 走 MediaExtractor，这里不能复用）。
 */
internal object WavPcm16Reader {
    fun read(bytes: ByteArray): WavPcm16? {
        if (bytes.size < 44) return null
        if (!(bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte())
        ) return null
        var i = 12
        var sampleRate = -1
        var channels = 0
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4, Charsets.US_ASCII)
            val size = readLe32(bytes, i + 4)
            val dataStart = i + 8
            if (id == "data") {
                if (sampleRate <= 0 || channels <= 0) return null
                // 占位/越界 size → 一律截到文件尾
                val end = if (size >= 0 && dataStart.toLong() + size <= bytes.size) dataStart + size else bytes.size
                val bytesPerFrame = 2 * channels
                val usable = (end - dataStart) / bytesPerFrame * bytesPerFrame
                val frames = usable / bytesPerFrame
                val out = ShortArray(frames)
                var p = dataStart
                for (f in 0 until frames) {
                    if (channels == 1) {
                        out[f] = readLe16(bytes, p).toShort()
                    } else {
                        // readLe16 是无符号值：先按 16bit 符号扩展再求和（65532+4 不扩展会溢出）
                        var acc = 0
                        for (c in 0 until channels) acc += readLe16(bytes, p + 2 * c).toShort().toInt()
                        out[f] = (acc / channels).toShort()
                    }
                    p += bytesPerFrame
                }
                return WavPcm16(out, sampleRate)
            }
            if (id == "fmt ") {
                if (dataStart + 16 > bytes.size) return null
                val audioFormat = readLe16(bytes, dataStart)
                channels = readLe16(bytes, dataStart + 2)
                sampleRate = readLe32(bytes, dataStart + 4)
                val bitsPerSample = readLe16(bytes, dataStart + 14)
                if (audioFormat != 1 || bitsPerSample != 16) return null
            }
            // 非 data chunk 一律按 chunk 头步进（fmt 也要走这里，否则原地死循环）；
            // size 同样可能是占位值：越界就步进到文件尾（终循环自然退出）
            i = if (size >= 0 && dataStart.toLong() + size <= bytes.size) {
                dataStart + size + (size and 1) // RIFF chunk 按偶数对齐
            } else {
                bytes.size
            }
        }
        return null
    }

    private fun readLe16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun readLe32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
