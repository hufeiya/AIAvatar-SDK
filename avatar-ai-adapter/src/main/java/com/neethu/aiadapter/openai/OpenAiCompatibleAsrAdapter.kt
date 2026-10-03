package com.neethu.aiadapter.openai

import com.neethu.aiadapter.api.AsrAdapter
import com.neethu.aiadapter.model.AsrConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * OpenAI-compatible `POST {baseUrl}/audio/transcriptions` adapter
 * (whisper-1 wire schema): `model` + `file` multipart form, response
 * `{"text": "..."}`. Works with OpenAI and compatible gateways such as
 * SiliconFlow (e.g. `Qwen/Qwen3-ASR-1.7B`).
 */
class OpenAiCompatibleAsrAdapter(
    baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : AsrAdapter {

    private val endpoint = baseUrl.trimEnd('/') + "/audio/transcriptions"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun transcribe(audio: ByteArray, mime: String, config: AsrConfig): String =
        withContext(Dispatchers.IO) {
            val (fileName, contentType) = mediaTypeFor(mime)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", config.model)
                .addFormDataPart("file", fileName, audio.toRequestBody(contentType))
                .apply {
                    config.language?.let { addFormDataPart("language", it) }
                    config.prompt?.let { addFormDataPart("prompt", it) }
                }
                .build()

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // 网关失败也可能是 200 的 HTML/JSON 页面（附录 A.1 第 11 条的同源教训）：
                    // 两类都要带响应预览报错，真实原因直接进堆栈
                    throw IOException(
                        "ASR HTTP ${response.code}: ${text.take(500)}"
                    )
                }
                val parsed = runCatching { json.parseToJsonElement(text).jsonObject }
                    .getOrElse {
                        throw IOException(
                            "ASR returned non-JSON body (HTTP ${response.code}, " +
                                "content-type=${response.header("Content-Type").orEmpty()}): " +
                                text.take(120)
                        )
                    }
                parsed["text"]?.jsonPrimitive?.content
                    ?: throw IOException("ASR response has no 'text' field: ${text.take(200)}")
            }
        }

    /** Map an input MIME type to a (filename, MediaType) pair servers accept. */
    private fun mediaTypeFor(mime: String): Pair<String, okhttp3.MediaType> {
        val subtype = mime.substringAfter('/').substringBefore(';').lowercase()
        val fileName = when (subtype) {
            "mp4", "aac", "m4a" -> "speech.m4a"
            "wav", "x-wav", "wave", "vnd.wave" -> "speech.wav"
            "mpeg", "mp3" -> "speech.mp3"
            "amr" -> "speech.amr"
            "ogg", "opus" -> "speech.ogg"
            "webm" -> "speech.webm"
            "flac" -> "speech.flac"
            else -> "speech.bin"
        }
        val type = when (fileName) {
            // Android records AAC into an MP4 container; servers expect audio/mp4 there
            "speech.m4a" -> "audio/mp4"
            else -> if (mime.isNotBlank()) mime.substringBefore(';') else "application/octet-stream"
        }
        return fileName to type.toMediaType()
    }
}
