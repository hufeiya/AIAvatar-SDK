package com.neethu.aiadapter.openai

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.LlmUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
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
    // 读超时给足 60s：SSE 首字节前的排队+预填充（14K+ 字符的钉住协议）可能
    // 超过 OkHttp 默认的 10s（真机 TurnFailed: timeout），首字节之后逐 delta
    // 读取远低于超时；connectTimeout 保持默认。
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) : LlmAdapter {

    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> =
        channelFlow {
            val body = buildJsonObject {
                put("model", config.model)
                put("messages", buildJsonMessages(messages))
                put("stream", true)
                // Ask for the final usage chunk (prompt/cached/completion tokens) —
                // the prefix-cache hit-rate probe for the pinned-protocol prompt.
                put("stream_options", buildJsonObject { put("include_usage", true) })
                put("temperature", config.temperature)
                put("top_p", config.topP)
                put("max_tokens", config.maxTokens)
                // Tool calling (P0): empty registry → both keys omitted (zero
                // behavior change; strict endpoints 400 on an empty tools array).
                if (config.tools.isNotEmpty()) {
                    put("tools", kotlinx.serialization.json.buildJsonArray {
                        config.tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("type", "function")
                                    put(
                                        "function",
                                        buildJsonObject {
                                            put("name", tool.name)
                                            put("description", tool.description)
                                            put("parameters", tool.parametersSchema)
                                        },
                                    )
                                },
                            )
                        }
                    })
                    config.toolChoice?.let { put("tool_choice", it) }
                }
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

            val currentCall = java.util.concurrent.atomic.AtomicReference<Call?>(null)
            val writer = launch(Dispatchers.IO) {
                // 瞬态故障静默重试（airi llm-service transientRetry 同思路，2026-10-11
                // 真机实测驱动）：HTTP 5xx / 快速 IO 失败（连接拒绝、DNS、秒级超时）在
                // **还没吐出任何事件**时重发一次——服务商间歇性 50507/抖动不再杀死回合。
                // 已有可见输出后绝不重试（会复读）；慢超时（读超时 60s 级）也不重试
                // （再等 60s 的挂起比失败更伤语音体验）。
                var attempts = 0
                var emittedAny = false
                while (true) {
                    val call = client.newCall(request)
                    currentCall.set(call)
                    var retry = false
                    val attemptStartMs = System.nanoTime()
                    try {
                        call.execute().use { response ->
                            if (!response.isSuccessful) {
                                val errBody = response.body?.string()?.take(500).orEmpty()
                                if (response.code in 500..599 && !emittedAny && attempts < TRANSIENT_RETRIES) {
                                    attempts++
                                    retry = true
                                    return@use
                                }
                                send(LlmStreamEvent.Error(IllegalStateException("LLM HTTP ${response.code}: $errBody")))
                                return@use
                            }
                            val source = response.body?.source() ?: run {
                                send(LlmStreamEvent.Error(IllegalStateException("LLM response has no body")))
                                return@use
                            }
                            var finished = false
                            var finishReason: String? = null
                            var usage: LlmUsage? = null
                            var contentEmitted = false
                            val reasoningSpare = StringBuilder()
                            val toolAccumulator = ToolCallAccumulator()
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                if (!line.startsWith("data:")) continue
                                val payload = line.removePrefix("data:").trim()
                                if (payload == "[DONE]") break
                                val root = try {
                                    json.parseToJsonElement(payload).jsonObject
                                } catch (_: Exception) {
                                    continue
                                }
                                // include_usage providers append a trailing chunk with
                                // empty choices and the usage object — hoard it for Finish.
                                (root["usage"] as? JsonObject)?.let { usage = parseUsage(it) ?: usage }
                                val delta = parseDelta(root) ?: continue
                                delta.first?.let { text ->
                                    if (text.isNotEmpty()) {
                                        contentEmitted = true
                                        emittedAny = true
                                        send(LlmStreamEvent.TextDelta(text))
                                    }
                                }
                                // 思考通道缓存（reasoning_content）：SiliconFlow 的
                                // Qwen3-VL 系在「流式 + tools」组合下实测无视
                                // enable_thinking=false,全部输出进 reasoning_content、
                                // content 恒空（2026-10-25 真机/curl 双实锤）。这里只
                                // 缓存不转发——流末 content 全空时才兜底转正,有
                                // content 的正常路径零行为变化。
                                delta.reasoning?.takeIf { it.isNotEmpty() }?.let { reasoningSpare.append(it) }
                                // Streaming tool calls: accumulate per index slot and
                                // surface Started/Arguments events in fragment order.
                                delta.third?.let { deltaTools ->
                                    toolAccumulator.feed(deltaTools).forEach { event ->
                                        emittedAny = true
                                        send(event)
                                    }
                                }
                                if (!finished && delta.second != null) {
                                    finished = true
                                    finishReason = delta.second
                                }
                            }
                            // 兜底转正：content 全空 + 无工具调用 + 思考通道有货
                            // → 思考文本当正文（模型把它当回答输出,只是放错了通道;
                            // 口吻可能带思考味,好过整回合零输出）。
                            val tools = toolAccumulator.complete()
                            if (!contentEmitted && tools.isEmpty() && reasoningSpare.isNotBlank()) {
                                emittedAny = true
                                send(LlmStreamEvent.TextDelta(reasoningSpare.toString().trim()))
                            }
                            emittedAny = true
                            send(
                                LlmStreamEvent.Finish(
                                    finishReason,
                                    usage,
                                    tools,
                                ),
                            )
                        }
                    } catch (t: Throwable) {
                        val elapsedMs = (System.nanoTime() - attemptStartMs) / 1_000_000
                        val fastIoFailure = t is java.io.IOException &&
                            elapsedMs < TRANSIENT_FAST_FAILURE_MS &&
                            attempts < TRANSIENT_RETRIES
                        if (fastIoFailure && !emittedAny && !call.isCanceled()) {
                            attempts++
                            retry = true
                        } else if (!call.isCanceled()) {
                            send(LlmStreamEvent.Error(t))
                        }
                    }
                    if (retry) {
                        delay(TRANSIENT_RETRY_BACKOFF_MS)
                        continue
                    }
                    break
                }
                close()
            }
            awaitClose {
                currentCall.getAndSet(null)?.cancel()
                writer.cancel()
            }
        }

    private fun buildJsonMessages(messages: List<ChatMessage>) = kotlinx.serialization.json.buildJsonArray {
        messages.forEach { msg ->
            add(
                buildJsonObject {
                    put("role", msg.role.name.lowercase())
                    // Tool results are always plain text (no images branch).
                    if (msg.role == ChatRole.TOOL) {
                        msg.toolCallId?.let { put("tool_call_id", it) }
                        put("content", msg.content)
                        return@buildJsonObject
                    }
                    // Assistant messages that initiated tool calls replay as
                    // `assistant.tool_calls`; content stays a plain string
                    // (empty string on a tool-only round — DeepSeek-family
                    // gateways 400 on non-string content, measured).
                    if (msg.role == ChatRole.ASSISTANT && msg.toolCalls.isNotEmpty()) {
                        put("content", msg.content)
                        put("tool_calls", buildJsonArray {
                            msg.toolCalls.forEach { call ->
                                add(
                                    buildJsonObject {
                                        put("id", call.id)
                                        put("type", "function")
                                        put(
                                            "function",
                                            buildJsonObject {
                                                put("name", call.name)
                                                put("arguments", call.argumentsJson)
                                            },
                                        )
                                    },
                                )
                            }
                        })
                        return@buildJsonObject
                    }
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

    /** Returns `(deltaText, finishReason, deltaToolCalls, reasoningText)` — any may be null. */
    private fun parseDelta(root: JsonObject): Quadruple = try {
        val choice = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
            ?: return Quadruple(null, null, null, null)
        val choiceObj = choice.jsonObject
        val deltaObj = choiceObj["delta"]?.jsonObject
        val content = (deltaObj?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf {
            it !is kotlinx.serialization.json.JsonNull
        }?.jsonPrimitive
        val reasoning = (deltaObj?.get("reasoning_content") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf {
            it !is kotlinx.serialization.json.JsonNull
        }?.jsonPrimitive?.content
        val finish = choiceObj["finish_reason"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
        }
        val deltaTools = deltaObj?.get("tool_calls") as? kotlinx.serialization.json.JsonArray
        Quadruple(content?.contentOrNullSafe(), finish, deltaTools, reasoning)
    } catch (_: Exception) {
        Quadruple(null, null, null, null)
    }

    /** [parseDelta] 的四元组（OpenAI content/finish/tool_calls + 思考通道）。 */
    private data class Quadruple(
        val first: String?,
        val second: String?,
        val third: kotlinx.serialization.json.JsonArray?,
        val reasoning: String?,
    )

    /** OpenAI usage chunk → [LlmUsage]; `cached_tokens` stays null when unreported. */
    private fun parseUsage(u: JsonObject): LlmUsage? = try {
        val prompt = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: return null
        val completion = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cached = (u["prompt_tokens_details"] as? JsonObject)
            ?.get("cached_tokens")?.jsonPrimitive?.intOrNull
        LlmUsage(promptTokens = prompt, completionTokens = completion, cachedTokens = cached)
    } catch (_: Exception) {
        null
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        try { if (this is kotlinx.serialization.json.JsonNull) null else content } catch (_: Exception) { null }

    private companion object {
        /** 瞬态故障（5xx/快速 IO）的静默重试次数与节奏（仅未吐字前，见 streamChat 注释）。 */
        const val TRANSIENT_RETRIES = 1
        const val TRANSIENT_RETRY_BACKOFF_MS = 1_500L

        /** 「快速失败」阈值：超过它的 IO 失败多半是读超时级挂起，重试会让回合挂更久。 */
        const val TRANSIENT_FAST_FAILURE_MS = 15_000L
    }
}
