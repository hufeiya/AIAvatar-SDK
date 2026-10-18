package com.neethu.aiadapter.bailian

import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import kotlinx.coroutines.Dispatchers
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
 */
class BailianTtsAdapter(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    /** 可注入换端点（MockWebServer 单测）；生产用公共 dashscope 原生端点。 */
    private val endpoint: String = DEFAULT_ENDPOINT,
) : TtsAdapter {

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

            val audioRef = runCatching {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException("Bailian TTS HTTP ${response.code}: ${raw.take(500)}")
                    }
                    val audio = runCatching {
                        Json.parseToJsonElement(raw).jsonObject["output"]?.jsonObject?.get("audio")?.jsonObject
                    }.getOrNull()
                    val url = audio?.get("url")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    val data = audio?.get("data")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    when {
                        // 兼容两种返回形态：audio.data 内联 base64（现网为空串）或 url 下载链
                        data != null -> "data:base64,$data"
                        url != null -> url
                        else -> throw IOException("Bailian TTS: no audio url/data: ${raw.take(200)}")
                    }
                }
            }.getOrElse { e ->
                if (e is IOException) throw e else throw IOException("Bailian TTS: ${e.message}")
            }

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

    companion object {
        /** DashScope 原生多模态生成端点（compatible-mode 无语音合成路由，见类注释）。 */
        const val DEFAULT_ENDPOINT =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        const val DEFAULT_MODEL = "qwen3-tts-flash"
        const val DEFAULT_VOICE = "Cherry"

        /** wLipSync 标定输入率：重采样目标（见类注释）。 */
        const val TARGET_SAMPLE_RATE = 16_000

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
