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
                TtsResult(bytes, format, config.rawPcmSampleRate)
            }
        }
}
