package com.neethu.aiadapter.openai

import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.ToolCallRequest
import com.neethu.aiadapter.model.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Multimodal request wiring (视频模式): messages carrying [ChatMessage.images]
 * must serialize to the OpenAI content-array form (`text` + `image_url`
 * parts), while text-only messages keep the plain-string `content` that
 * text-only models (DeepSeek…) require — array-form content makes them 400
 * (measured: SiliconFlow answers "The model is not a VLM").
 */
class OpenAiCompatibleLlmAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: OpenAiCompatibleLlmAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = OpenAiCompatibleLlmAdapter(server.url("/v1").toString(), "sk-test")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val config = LlmConfig(baseUrl = "unused", apiKey = "unused", model = "m")

    private fun sseBody(): String =
        "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
            "data: [DONE]\n\n"

    /** Runs one turn and returns the parsed request body's messages array. */
    private fun capturedMessages(messages: List<ChatMessage>) = runBlocking {
        server.enqueue(MockResponse().setBody(sseBody()).setHeader("Content-Type", "text/event-stream"))
        adapter.streamChat(messages, config).toList()
        val body = server.takeRequest().body.readUtf8()
        Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
    }

    @Test
    fun `text-only messages keep plain-string content`() = runBlocking {
        val msgs = capturedMessages(
            listOf(
                ChatMessage(ChatRole.SYSTEM, "be nice"),
                ChatMessage(ChatRole.USER, "你好"),
            )
        )
        assertEquals(2, msgs.size)
        assertEquals("你好", msgs[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", msgs[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `image messages serialize to text + image_url content array`() = runBlocking {
        val dataUrl = "data:image/jpeg;base64,QUJD"
        val msgs = capturedMessages(
            listOf(
                ChatMessage(ChatRole.USER, "你看我身后有什么?", images = listOf(dataUrl)),
            )
        )
        val content = msgs[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        val textPart = content[0].jsonObject
        assertEquals("text", textPart["type"]!!.jsonPrimitive.content)
        assertEquals("你看我身后有什么?", textPart["text"]!!.jsonPrimitive.content)
        val imagePart = content[1].jsonObject
        assertEquals("image_url", imagePart["type"]!!.jsonPrimitive.content)
        assertEquals(
            dataUrl,
            imagePart["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `assistant history stays string even when the current turn has images`() = runBlocking {
        val msgs = capturedMessages(
            listOf(
                ChatMessage(ChatRole.ASSISTANT, "之前的回复"),
                ChatMessage(ChatRole.USER, "看这个", images = listOf("data:image/jpeg;base64,QUJD")),
            )
        )
        // history message must remain a plain string (text-only-safe)
        assertTrue(msgs[0].jsonObject["content"]!!.jsonPrimitive.content == "之前的回复")
    }

    /** Reads the raw request body of the most recent (or only) enqueued call. */
    private suspend fun lastRequestBody(): kotlinx.serialization.json.JsonObject = runBlocking {
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    }

    @Test
    fun `request asks for stream_options include_usage`() = runBlocking {
        server.enqueue(MockResponse().setBody(sseBody()).setHeader("Content-Type", "text/event-stream"))
        adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        val body = lastRequestBody()
        assertEquals(
            true,
            body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content.toBooleanStrict(),
        )
    }

    @Test
    fun `extraBody is merged verbatim into the request`() = runBlocking {
        server.enqueue(MockResponse().setBody(sseBody()).setHeader("Content-Type", "text/event-stream"))
        val cfg = config.copy(
            extraBody = kotlinx.serialization.json.buildJsonObject {
                put("thinking", kotlinx.serialization.json.buildJsonObject { put("type", "disabled") })
            },
        )
        adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), cfg).toList()
        val body = lastRequestBody()
        assertEquals(
            "disabled",
            body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `usage chunk is parsed and carried on Finish`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1234," +
                        "\"completion_tokens\":56,\"prompt_tokens_details\":{\"cached_tokens\":1024}}}\n\n" +
                        "data: [DONE]\n\n",
                )
                .setHeader("Content-Type", "text/event-stream"),
        )
        val events = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        val finish = events.filterIsInstance<LlmStreamEvent.Finish>().single()
        // no finish_reason chunk in this stream — usage is the point of the test
        assertEquals(null, finish.reason)
        val usage = finish.usage!!
        assertEquals(1234, usage.promptTokens)
        assertEquals(56, usage.completionTokens)
        assertEquals(1024, usage.cachedTokens)
    }

    @Test
    fun `usage without cached breakdown keeps cachedTokens null`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}\n\n" +
                        "data: [DONE]\n\n",
                )
                .setHeader("Content-Type", "text/event-stream"),
        )
        val events = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        val finish = events.filterIsInstance<LlmStreamEvent.Finish>().single()
        assertEquals("stop", finish.reason)
        assertEquals(10, finish.usage!!.promptTokens)
        assertEquals(null, finish.usage!!.cachedTokens)
    }

    // ── P0 tool calling ──────────────────────────────────────────────────────

    private val toolSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("tz", buildJsonObject { put("type", "string") }) })
        put("required", kotlinx.serialization.json.buildJsonArray { })
    }
    private val toolSpec = ToolSpec("get_time", "获取设备当前时间", toolSchema, "需要时间时调用")

    /** SSE script with streamed tool_calls (SiliconFlow wire shape, measured 2026-10-11). */
    private fun toolCallSseBody(finishReason: String = "tool_calls"): String =
        "data: {\"choices\":[{\"delta\":{\"content\":\"\",\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":null,\"tool_calls\":[" +
            "{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_time\",\"arguments\":\"\"}}]},\"finish_reason\":null}]}\n\n" +
            // follow-ups: id:null + name:"" must be treated as absent (measured)
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
            "{\"index\":0,\"id\":null,\"type\":null,\"function\":{\"name\":\"\",\"arguments\":\"{\\\"tz\\\"\"}}]},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
            "{\"index\":0,\"id\":null,\"function\":{\"name\":\"\",\"arguments\":\":\\\"UTC+8\\\"}\"}}]},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$finishReason\"}]}\n\n" +
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":96,\"completion_tokens\":14}}\n\n" +
            "data: [DONE]\n\n"

    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "text/event-stream"))
    }

    @Test
    fun `reasoning-only stream is promoted to text when content empty`() = runBlocking {
        // SiliconFlow Qwen3-VL 流式+tools 实锤(2026-10-25):enable_thinking=false
        // 被无视,全部输出进 reasoning_content,content 恒空——兜底转正防整回合零输出。
        enqueue(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考一下\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"答案是42\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}}]}\n\n" +
                "data: [DONE]\n\n",
        )
        val events = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("思考一下\n答案是42".let { "思考一下答案是42" }, text)
        assertTrue(events.last() is LlmStreamEvent.Finish)
    }

    @Test
    fun `reasoning content never leaks when content present`() = runBlocking {
        enqueue(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"正文\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}}]}\n\n" +
                "data: [DONE]\n\n",
        )
        val events = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        val text = events.filterIsInstance<LlmStreamEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("正文", text)
    }

    @Test
    fun `empty tools config omits tools and tool_choice keys entirely`() = runBlocking {
        enqueue(sseBody())
        adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        val body = lastRequestBody()
        assertNull(body["tools"])
        assertNull(body["tool_choice"])
    }

    @Test
    fun `tools assemble as type-function wrappers with schema`() = runBlocking {
        enqueue(sseBody())
        val cfg = config.copy(tools = listOf(toolSpec))
        adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), cfg).toList()
        val tools = lastRequestBody()["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val fn = tools[0].jsonObject
        assertEquals("function", fn["type"]!!.jsonPrimitive.content)
        val spec = fn["function"]!!.jsonObject
        assertEquals("get_time", spec["name"]!!.jsonPrimitive.content)
        assertEquals("获取设备当前时间", spec["description"]!!.jsonPrimitive.content)
        assertEquals("object", spec["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toolChoice string forms pass through verbatim`() = runBlocking {
        for (choice in listOf("auto", "none", "required")) {
            server = MockWebServer(); server.start()
            adapter = OpenAiCompatibleLlmAdapter(server.url("/v1").toString(), "sk-test")
            enqueue(sseBody())
            adapter.streamChat(
                listOf(ChatMessage(ChatRole.USER, "hi")),
                config.copy(tools = listOf(toolSpec), toolChoice = choice),
            ).toList()
            assertEquals(choice, lastRequestBody()["tool_choice"]!!.jsonPrimitive.content)
            server.shutdown()
        }
    }

    @Test
    fun `streamed tool_calls accumulate into Finish with id name and joined args`() = runBlocking {
        enqueue(toolCallSseBody())
        val events = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "现在几点")), config).toList()
        val finish = events.filterIsInstance<LlmStreamEvent.Finish>().single()
        assertEquals(1, finish.toolCalls.size)
        val call = finish.toolCalls[0]
        assertEquals("call_1", call.id)
        assertEquals("get_time", call.name)
        assertEquals("{\"tz\":\"UTC+8\"}", call.argumentsJson)
        assertEquals("tool_calls", finish.reason)
        assertEquals(96, finish.usage!!.promptTokens)
        // Started fired once, before its argument fragments
        val started = events.filterIsInstance<LlmStreamEvent.ToolCallStarted>()
        assertEquals(1, started.size)
        assertEquals(0, started[0].index)
        val args = events.filterIsInstance<LlmStreamEvent.ToolCallArguments>()
        assertEquals(listOf("{\"tz\"", ":\"UTC+8\"}"), args.map { it.fragment })
    }

    @Test
    fun `parallel tool calls in separate index slots accumulate independently`() = runBlocking {
        enqueue(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                "{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"get_time\",\"arguments\":\"{}\"}}," +
                "{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"observe\",\"arguments\":\"{\\\"d\\\":\"}}]},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                "{\"index\":1,\"function\":{\"arguments\":\"1}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
                "data: [DONE]\n\n",
        )
        val events = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        val finish = events.filterIsInstance<LlmStreamEvent.Finish>().single()
        assertEquals(2, finish.toolCalls.size)
        assertEquals("call_a", finish.toolCalls[0].id)
        assertEquals("{}", finish.toolCalls[0].argumentsJson)
        assertEquals("call_b", finish.toolCalls[1].id)
        assertEquals("{\"d\":1}", finish.toolCalls[1].argumentsJson)
        assertEquals(listOf("get_time", "observe"), finish.toolCalls.map { it.name })
    }

    @Test
    fun `finish_reason stop with accumulated tool calls still carries them`() = runBlocking {
        // 防御分支(实测有的 provider 收尾用 stop):Finish.toolCalls 非空即工具轮
        enqueue(toolCallSseBody(finishReason = "stop"))
        val events = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        val finish = events.filterIsInstance<LlmStreamEvent.Finish>().single()
        assertEquals("stop", finish.reason)
        assertEquals(1, finish.toolCalls.size)
        assertEquals("call_1", finish.toolCalls[0].id)
    }

    @Test
    fun `plain text stream completes with zero tool calls`() = runBlocking {
        enqueue(sseBody())
        val finish = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
            .filterIsInstance<LlmStreamEvent.Finish>().single()
        assertTrue(finish.toolCalls.isEmpty())
    }

    @Test
    fun `assistant with toolCalls serializes content string plus tool_calls array`() = runBlocking {
        enqueue(sseBody())
        val msgs = capturedMessages(
            listOf(
                ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(ToolCallRequest("call_1", "get_time", "{\"tz\":\"UTC+8\"}"))),
                ChatMessage(ChatRole.TOOL, "2026-10-11 21:34 周日 (UTC+8)", toolCallId = "call_1"),
            ),
        )
        val assistant = msgs[0].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        // content 恒为字符串(空轮传 "",非 null)——DeepSeek 系对非字符串 400
        assertEquals("", assistant["content"]!!.jsonPrimitive.content)
        val calls = assistant["tool_calls"]!!.jsonArray
        assertEquals(1, calls.size)
        val call = calls[0].jsonObject
        assertEquals("call_1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        assertEquals("get_time", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("{\"tz\":\"UTC+8\"}", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val tool = msgs[1].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("2026-10-11 21:34 周日 (UTC+8)", tool["content"]!!.jsonPrimitive.content)
        assertNull(tool["tool_calls"])
    }

    @Test
    fun `assistant history without toolCalls keeps legacy serialization`() = runBlocking {
        enqueue(sseBody())
        val msgs = capturedMessages(listOf(ChatMessage(ChatRole.ASSISTANT, "普通回复")))
        assertNull(msgs[0].jsonObject["tool_calls"])
        assertEquals("普通回复", msgs[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `isToolRelatedError matches provider tool-support failures only`() {
        fun http400(msg: String) = IllegalStateException("LLM HTTP 400: $msg")
        // 命中:各家的「不支持工具」报错(airi 正则表逐条)
        assertTrue(isToolRelatedError(http400("does not support tools")))
        assertTrue(isToolRelatedError(http400("No endpoints found that support tool use")))
        assertTrue(isToolRelatedError(http400("Unrecognized request argument supplied: tools")))
        assertTrue(isToolRelatedError(http400("tools are not supported for this model")))
        assertTrue(isToolRelatedError(IllegalStateException("wrap", http400("does not support function calling"))))
        // 不命中:单工具参数错误/普通 400(airi 同款裁剪——一次工具报错≠没有工具能力)
        assertFalse(isToolRelatedError(http400("invalid_function_parameters")))
        assertFalse(isToolRelatedError(http400("tool_use_failed")))
        assertFalse(isToolRelatedError(http400("The model is not a VLM")))
    }

    // ── 瞬态故障静默重试（2026-10-11 服务商间歇 50507 实测驱动）──────────────

    @Test
    fun `http 5xx before any delta retries once and succeeds`() = runBlocking {
        // 第 1 次 500（秒级失败）、第 2 次正常 → 回合成功，共发出 2 个请求
        server.enqueue(MockResponse().setResponseCode(505).setBody("boom"))
        server.enqueue(MockResponse().setBody(sseBody()).setHeader("Content-Type", "text/event-stream"))
        val events = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        assertTrue(events.any { it is LlmStreamEvent.TextDelta })
        assertTrue(events.any { it is LlmStreamEvent.Finish })
        assertTrue(events.none { it is LlmStreamEvent.Error })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `http 5xx twice surfaces the error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("a"))
        server.enqueue(MockResponse().setResponseCode(503).setBody("b"))
        val events = adapter.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        val error = events.filterIsInstance<LlmStreamEvent.Error>().single()
        assertTrue(error.throwable.message!!.contains("503"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `fast io timeout retries and recovers`() = runBlocking {
        // 短读超时客户端 + 首响应头延迟 2s → SocketTimeout(快速失败) → 重试 → 成功
        val flaky = OpenAiCompatibleLlmAdapter(
            server.url("/v1").toString(), "sk-test",
            client = okhttp3.OkHttpClient.Builder().readTimeout(500, java.util.concurrent.TimeUnit.MILLISECONDS).build(),
        )
        server.enqueue(MockResponse().setHeadersDelay(2, java.util.concurrent.TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody(sseBody()).setHeader("Content-Type", "text/event-stream"))
        val events = flaky.streamChat(listOf(ChatMessage(ChatRole.USER, "hi")), config).toList()
        assertTrue(events.any { it is LlmStreamEvent.Finish })
        assertTrue(events.none { it is LlmStreamEvent.Error })
        assertEquals(2, server.requestCount)
    }

    // 「已吐 delta 后 IO 失败不重试」的守卫（emittedAny）无法用 MockWebServer 确定性地
    // 模拟（DISCONNECT_DURING_RESPONSE_BODY 会挂到读超时且吞 delta）——该分支由代码
    // 审读覆盖：send 任意事件即 emittedAny=true，catch 中不再进入重试分支。
}