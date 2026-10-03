package com.neethu.aiadapter.openai

import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * OpenAI-compatible `POST {baseUrl}/audio/speech` adapter.
 *
 * Works with OpenAI and any compatible gateway exposing the same schema
 * (model / input / voice / response_format / speed).
 */
class OpenAiCompatibleTtsAdapter(
    baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : TtsAdapter {

    private val endpoint = baseUrl.trimEnd('/') + "/audio/speech"

    override suspend fun synthesize(text: String, config: TtsConfig): TtsResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", config.model)
                put("input", text)
                put("voice", config.voice)
                put("response_format", config.responseFormat)
                config.sampleRate?.let { put("sample_rate", it) }
                put("speed", config.speed)
                config.extraParams.forEach { (k, v) -> put(k, v) }
            }.toString()

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()?.take(500).orEmpty()
                    throw IOException("TTS HTTP ${response.code}: $err")
                }
                val bytes = response.body?.bytes() ?: throw IOException("TTS response has no body")
                val format = when (config.responseFormat.lowercase()) {
                    "pcm" -> TtsAudioFormat.RAW_PCM_16LE
                    "wav" -> TtsAudioFormat.WAV
                    "mp3" -> TtsAudioFormat.MP3
                    "opus", "ogg" -> TtsAudioFormat.OGG
                    else -> TtsAudioFormat.UNKNOWN
                }
                // 有些网关/代理对失败请求也回 HTTP 200（HTML/JSON 页面），直接喂给
                // 解码器只会得到谜语（如 "Not a RIFF file"）——按声明的格式校验魔数，
                // 不匹配时带上响应预览报错，让真实原因出现在堆栈里。
                if (formatMagicMismatch(format, bytes)) {
                    val preview = bytes.preview()
                    throw IOException(
                        "TTS returned non-${config.responseFormat} body " +
                            "(HTTP ${response.code}, ${bytes.size}B, " +
                            "content-type=${response.header("Content-Type").orEmpty()}): $preview"
                    )
                }
                TtsResult(bytes, format, config.rawPcmSampleRate)
            }
        }

    /** True when the body's magic bytes contradict the declared [TtsAudioFormat]. */
    private fun formatMagicMismatch(format: TtsAudioFormat, bytes: ByteArray): Boolean = when (format) {
        TtsAudioFormat.WAV -> !bytes.startsWithMagic("RIFF")
        TtsAudioFormat.MP3 -> !bytes.startsWithMagic("ID3") && !bytes.hasMp3FrameSync()
        TtsAudioFormat.OGG -> !bytes.startsWithMagic("OggS")
        else -> false
    }

    private fun ByteArray.startsWithMagic(magic: String): Boolean {
        if (size < magic.length) return false
        for (i in magic.indices) if (this[i] != magic[i].code.toByte()) return false
        return true
    }

    /** MPEG frame sync: 0xFF followed by a byte whose top 3 bits are 111. */
    private fun ByteArray.hasMp3FrameSync(): Boolean =
        size >= 2 && this[0] == 0xFF.toByte() && (this[1].toInt() and 0xE0) == 0xE0

    /** First bytes as printable ASCII (non-printables as '.') for error messages. */
    private fun ByteArray.preview(): String {
        val head = take(24).toByteArray()
        val text = head.joinToString("") { if (it.toInt() in 32..126) it.toInt().toChar().toString() else "." }
        return "\"$text\""
    }
}
