package com.neethu.aiadapter.openai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * /models 目录拉取的防御性：各家返回形态不一（火山带 domain/status、
 * OpenRouter 带 architecture modalities、其余只有 id），解析取「给什么收
 * 什么」，任何畸形响应都返回空列表由调用方回退静态清单。
 */
class OpenAiCompatibleModelCatalogTest {

    private lateinit var server: MockWebServer
    private lateinit var catalog: OpenAiCompatibleModelCatalog

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        catalog = OpenAiCompatibleModelCatalog()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses plain openai style list with ids only`() {
        val body = """
            {"object":"list","data":[
                {"id":"qwen3.8-flash","object":"model","created":1791882726,"owned_by":"system"},
                {"id":"qwen3-vl-plus","object":"model","created":1791022057,"owned_by":"system"}
            ]}
        """.trimIndent()
        val entries = catalog.parse(body)
        assertEquals(listOf("qwen3.8-flash", "qwen3-vl-plus"), entries.map { it.id })
        assertNull(entries[0].domain)
        assertNull(entries[0].status)
        assertNull(entries[0].inputModalities)
    }

    @Test
    fun `parses volcano domain and status metadata`() {
        val body = """
            {"data":[
                {"created":1714982195,"domain":"","features":{},"id":"doubao-lite-128k-240428",
                 "name":"doubao-lite-128k","object":"model","status":"Shutdown","version":"240428"},
                {"id":"doubao-seed-2-1-pro-260915","object":"model","domain":"VLM","status":null},
                {"id":"deepseek-v4-flash-ga-260731","object":"model","domain":"LLM"}
            ]}
        """.trimIndent()
        val entries = catalog.parse(body)
        assertEquals("Shutdown", entries[0].status)
        assertEquals("VLM", entries[1].domain)
        assertEquals("LLM", entries[2].domain)
        assertNull(entries[1].status)
    }

    @Test
    fun `parses openrouter architecture modalities`() {
        val body = """
            {"data":[
                {"id":"google/gemini-3.8-flash",
                 "architecture":{"input_modalities":["text","file","image"],"output_modalities":["text"]}},
                {"id":"some/text-only",
                 "architecture":{"input_modalities":["text"],"output_modalities":["text"]}},
                {"id":"openrouter/free"}
            ]}
        """.trimIndent()
        val entries = catalog.parse(body)
        assertEquals(listOf("text", "file", "image"), entries[0].inputModalities)
        assertEquals(listOf("text"), entries[0].outputModalities)
        assertEquals(listOf("text"), entries[1].inputModalities)
        assertNull(entries[2].inputModalities)
    }

    @Test
    fun `dedupes ids and drops entries without id`() {
        val body = """
            {"data":[
                {"id":"m1"},{"id":"m1"},{"name":"no id"},{"id":""},
                "surprise",{"id":"m2"}
            ]}
        """.trimIndent()
        assertEquals(listOf("m1", "m2"), catalog.parse(body).map { it.id })
    }

    @Test
    fun `malformed bodies degrade to empty list instead of throwing`() {
        assertTrue(catalog.parse("not json at all").isEmpty())
        assertTrue(catalog.parse("""{"data":"surprise"}""").isEmpty())
        assertTrue(catalog.parse("""{"object":"list"}""").isEmpty())
    }

    @Test
    fun `fetch hits models endpoint with bearer and tolerates failures`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))
        assertEquals(0, catalog.fetch(server.url("/v1").toString(), "sk-x").size)

        server.enqueue(MockResponse().setBody("""{"data":[{"id":"m1"}]}"""))
        assertEquals(listOf("m1"), catalog.fetch(server.url("/v1").toString(), "sk-x").map { it.id })

        val recorded = server.takeRequest()
        assertEquals("Bearer sk-x", recorded.getHeader("Authorization"))
        assertEquals("/v1/models", recorded.path)
    }

    @Test
    fun `io errors never escape fetch`() = runBlocking {
        val offline = OpenAiCompatibleModelCatalog()
        assertTrue(offline.fetch("http://127.0.0.1:1/v1", "sk-x").isEmpty())
    }
}
