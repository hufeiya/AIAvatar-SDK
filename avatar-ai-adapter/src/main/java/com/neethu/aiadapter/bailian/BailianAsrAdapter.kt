package com.neethu.aiadapter.bailian

import com.neethu.aiadapter.api.AsrAdapter
import com.neethu.aiadapter.model.AsrConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/**
 * 阿里云百炼语音识别（qwen3-asr-flash）适配器：**OpenAI 兼容 chat 形态的音频
 * 输入**，不是 /audio/transcriptions——该 multipart 端点在 compatible-mode
 * （专属端点与公共 dashscope 同查）实测 404（2026-10-18 真 key 核验）：
 *
 * ```
 * POST {base}/chat/completions
 * {"model":"qwen3-asr-flash","messages":[{"role":"user","content":[
 *   {"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,…","format":"wav"}}]}]}
 * → {"choices":[{"message":{"content":"转写文本","annotations":[{"emotion":…,"language":…}]}}]}
 * ```
 *
 * wav 与 m4a（demo 录音两种来源）都用真语音实测转写正确；语言/情绪信息在
 * annotations 里，本适配器只取 content 文本。请求走非流式。
 */
class BailianAsrAdapter(
    baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : AsrAdapter {

    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

    override suspend fun transcribe(audio: ByteArray, mime: String, config: AsrConfig): String =
        withContext(Dispatchers.IO) {
            val format = containerFormatFor(mime)
            val dataUri = "data:$mime;base64," + Base64.getEncoder().encodeToString(audio)
            // content 必须是**纯 input_audio 单项**：附带 text 段（哪怕只是转写
            // 指令）实测 400「The dedicated task `asr` … does not support this
            // input」（2026-10-18 真机+host 双侧 A/B 实锤）——模型对纯音频默认转写
            val body = buildJsonObject {
                put("model", config.model.ifBlank { DEFAULT_ASR_MODEL })
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "input_audio")
                                put("input_audio", buildJsonObject {
                                    put("data", dataUri)
                                    put("format", format)
                                })
                            })
                        })
                    })
                })
            }.toString()

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("ASR HTTP ${response.code}: ${raw.take(500)}")
                }
                val content = runCatching {
                    Json.parseToJsonElement(raw).jsonObject["choices"]
                        ?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
                }.getOrNull()
                content ?: throw IOException(
                    "ASR returned unexpected body (HTTP ${response.code}): ${raw.take(200)}"
                )
            }
        }

    companion object {
        /** 百炼 ASR 默认模型（/models 在册，chat 形态音频输入实测转写正确）。 */
        const val DEFAULT_ASR_MODEL = "qwen3-asr-flash"

        /** MIME → input_audio.format 容器名（demo 录音 wav / 导入 m4a 都实测过）。 */
        internal fun containerFormatFor(mime: String): String {
            val subtype = mime.substringAfter('/').substringBefore(';').lowercase()
            return when (subtype) {
                "wav", "x-wav", "wave", "vnd.wave" -> "wav"
                "mp4", "aac", "m4a" -> "m4a"
                "mpeg", "mp3" -> "mp3"
                "amr" -> "amr"
                "ogg", "opus" -> "opus"
                "webm" -> "webm"
                "flac" -> "flac"
                else -> "wav"
            }
        }
    }
}
