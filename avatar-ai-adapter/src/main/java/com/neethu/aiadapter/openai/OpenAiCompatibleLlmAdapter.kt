package com.neethu.aiadapter.openai

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI-compatible chat-completions streaming adapter.
 *
 * Works with any endpoint that implements `POST {baseUrl}/chat/completions`
 * with Server-Sent Events — OpenAI, Groq, DeepSeek, Ollama, LM Studio,
 * OpenRouter, vLLM, etc. One instance is bound to one base URL; use one
 * adapter per configured provider (mirrors AIRI's `createOpenAI(apiKey, baseUrl)`).
 */
class OpenAiCompatibleLlmAdapter(
    baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
) : LlmAdapter {

    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> =
        channelFlow {
            val body = buildJsonObject {
                put("model", config.model)
                put("messages", buildJsonMessages(messages))
                put("stream", true)
                put("temperature", config.temperature)
                put("top_p", config.topP)
                put("max_tokens", config.maxTokens)
                config.extraBody?.forEach { (k, v) -> put(k, v) }
            }.toString()

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .apply { config.extraHeaders.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            val writer = launch(Dispatchers.IO) {
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            val errBody = response.body?.string()?.take(500).orEmpty()
                            send(LlmStreamEvent.Error(IllegalStateException("LLM HTTP ${response.code}: $errBody")))
                            return@use
                        }
                        val source = response.body?.source() ?: run {
                            send(LlmStreamEvent.Error(IllegalStateException("LLM response has no body")))
                            return@use
                        }
                        var finished = false
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            val delta = parseDelta(payload) ?: continue
                            delta.first?.let { text ->
                                if (text.isNotEmpty()) send(LlmStreamEvent.TextDelta(text))
                            }
                            if (!finished && delta.second != null) {
                                finished = true
                                send(LlmStreamEvent.Finish(delta.second))
                            }
                        }
                        if (!finished) send(LlmStreamEvent.Finish(null))
                    }
                } catch (t: Throwable) {
                    if (!call.isCanceled()) {
                        send(LlmStreamEvent.Error(t))
                    }
                }
                close()
            }
            awaitClose {
                call.cancel()
                writer.cancel()
            }
        }

    private fun buildJsonMessages(messages: List<ChatMessage>) = kotlinx.serialization.json.buildJsonArray {
        messages.forEach { msg ->
            add(
                buildJsonObject {
                    put("role", msg.role.name.lowercase())
                    // Text-only messages stay a plain string — array-form content
                    // makes text-only models (DeepSeek …) answer 400. Images ride
                    // OpenAI multimodal parts: text first, then image_url data URLs.
                    put(
                        "content",
                        if (msg.images.isEmpty()) kotlinx.serialization.json.JsonPrimitive(msg.content)
                        else kotlinx.serialization.json.buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", msg.content)
                                }
                            )
                            msg.images.forEach { url ->
                                add(
                                    buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject { put("url", url) })
                                    }
                                )
                            }
                        },
                    )
                }
            )
        }
    }

    /** Returns `(deltaText, finishReason)` — either may be null. */
    private fun parseDelta(payload: String): Pair<String?, String?>? = try {
        val root = json.parseToJsonElement(payload).jsonObject
        val choice = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() ?: return null
        val choiceObj = choice.jsonObject
        val content = (choiceObj["delta"]?.jsonObject?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf {
            it !is kotlinx.serialization.json.JsonNull
        }?.jsonPrimitive
        val finish = choiceObj["finish_reason"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
        }
        Pair(content?.contentOrNullSafe(), finish)
    } catch (_: Exception) {
        null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        try { if (this is kotlinx.serialization.json.JsonNull) null else content } catch (_: Exception) { null }
}
