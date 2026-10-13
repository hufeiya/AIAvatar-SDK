package com.neethu.aiadapter.openai

import com.neethu.aiadapter.api.EmbeddingConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Embedding 适配器的错误面契约（docs/memory-feasibility.md §4.2）：批量一次
 * 往返、按 index 归位、HTTP/非 JSON 失败都带响应预览抛 IOException（A.1
 * 第 11 条同源教训）——嵌入失败 ≠ 记忆丢失，调用方据异常降级存储。
 */
class OpenAiCompatibleEmbeddingAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: OpenAiCompatibleEmbeddingAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = OpenAiCompatibleEmbeddingAdapter()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val config get() = EmbeddingConfig(server.url("/v1").toString(), "sk-test", "BAAI/bge-m3")

    @Test
    fun `happy path posts model and inputs and reorders by index`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                // 故意乱序：index=1 的在前
                """{"data":[
                    {"index":1,"embedding":[0.4,0.5,0.6]},
                    {"index":0,"embedding":[0.1,0.2,0.3]}
                ]}""",
            ),
        )

        val vectors = adapter.embed(listOf("第一条", "第二条"), config)

        assertEquals(2, vectors.size)
        assertEquals(listOf(0.1f, 0.2f, 0.3f), vectors[0].toList())
        assertEquals(listOf(0.4f, 0.5f, 0.6f), vectors[1].toList())
        val recorded = server.takeRequest()
        assertEquals("/v1/embeddings", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"model\":\"BAAI/bge-m3\""))
        assertTrue(body.contains("第一条"))
        assertTrue(body.contains("第二条"))
    }

    @Test
    fun `http errors surface a response preview as IOException`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"bad key"}"""))

        try {
            adapter.embed(listOf("x"), config)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("401"))
            assertTrue(e.message!!.contains("bad key"))
        }
    }

    @Test
    fun `non-json 200 body surfaces a preview too`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>gateway page</html>"))

        try {
            adapter.embed(listOf("x"), config)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("non-JSON"))
            assertTrue(e.message!!.contains("gateway page"))
        }
    }
}
