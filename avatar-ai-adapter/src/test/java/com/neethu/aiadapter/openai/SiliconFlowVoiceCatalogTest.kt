package com.neethu.aiadapter.openai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test


/**
 * 音色清单是「尽力而为」路径：解析必须防御——任何畸形响应都返回空列表由
 * 调用方落静态兜底，绝不抛异常打断设置页。
 */
class SiliconFlowVoiceCatalogTest {

    private lateinit var server: MockWebServer
    private lateinit var catalog: SiliconFlowVoiceCatalog

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        catalog = SiliconFlowVoiceCatalog()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses result array with uri and name fields`() {
        val body = """
            {"result":[
                {"uri":"speech:my-clone","name":"我的克隆","gender":"female"},
                {"uri":"FunAudioLLM/CosyVoice2-0.5B:anna","name":"anna"}
            ]}
        """.trimIndent()
        val voices = catalog.parse(body)
        assertEquals(2, voices.size)
        assertEquals("speech:my-clone", voices[0].value)
        assertEquals("我的克隆", voices[0].label)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", voices[1].value)
    }

    @Test
    fun `empty result is the normal no-voice case`() {
        assertEquals(0, catalog.parse("""{"result":[]}""").size)
    }

    @Test
    fun `malformed bodies degrade to empty list instead of throwing`() {
        assertTrue(catalog.parse("not json at all").isEmpty())
        assertTrue(catalog.parse("""{"data":[1,2,3]}""").isEmpty())
        assertTrue(catalog.parse("""{"result":[{"name":"no uri field"}]}""").isEmpty())
        assertTrue(catalog.parse("""{"result":"surprise"}""").isEmpty())
    }

    @Test
    fun `http failure and network error return empty list`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"bad key"}"""))
        assertEquals(0, catalog.fetch(server.url("/v1").toString(), "sk-x").size)

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(0, catalog.fetch(server.url("/v1").toString(), "sk-x").size)

        // 请求带 Bearer 鉴权且打在 /audio/voice/list
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-x", recorded.getHeader("Authorization"))
        assertEquals("/v1/audio/voice/list", recorded.path)
    }

    @Test
    fun `io errors never escape fetch`() {
        runBlocking {
            val offline = SiliconFlowVoiceCatalog()
            val voices = offline.fetch("http://127.0.0.1:1/v1", "sk-x")
            assertTrue(voices.isEmpty())
        }
    }
}
