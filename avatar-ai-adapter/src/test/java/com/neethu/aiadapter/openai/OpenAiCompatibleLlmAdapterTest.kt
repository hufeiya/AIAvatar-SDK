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
}
