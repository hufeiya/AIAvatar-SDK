package com.neethu.aiadapter.openai

import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
}
