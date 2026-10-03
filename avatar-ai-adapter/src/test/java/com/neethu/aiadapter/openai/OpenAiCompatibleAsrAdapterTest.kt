package com.neethu.aiadapter.openai

import com.neethu.aiadapter.model.AsrConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Mirrors the TTS adapter's error-surfacing contract: provider/gateway
 * failures must reach the caller as readable IOExceptions with a body
 * preview — never as a riddle thrown later by a downstream parser.
 */
class OpenAiCompatibleAsrAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: OpenAiCompatibleAsrAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = OpenAiCompatibleAsrAdapter(server.url("/v1").toString(), "sk-test")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val config = AsrConfig(model = "Qwen/Qwen3-ASR-1.7B")

    @Test
    fun `happy path posts multipart with model and file and parses text field`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"text":"今天天气怎么样"}"""))

        val text = adapter.transcribe(byteArrayOf(1, 2, 3, 4), "audio/mp4", config)

        assertEquals("今天天气怎么样", text)
        val recorded = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertTrue(
            "multipart content type expected",
            recorded.getHeader("Content-Type")!!.startsWith("multipart/form-data"),
        )
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"model\""))
        assertTrue(body.contains("Qwen/Qwen3-ASR-1.7B"))
        assertTrue("file part name expected", body.contains("name=\"file\""))
        assertTrue("m4a filename expected", body.contains("filename=\"speech.m4a\""))
        assertTrue("audio/mp4 content type expected", body.contains("Content-Type: audio/mp4"))
        // language/prompt 为 null 时不得发送（硅基流动只收 file+model，多余字段可能 400）
        assertFalse("language must be omitted", body.contains("name=\"language\""))
        assertFalse("prompt must be omitted", body.contains("name=\"prompt\""))
    }

    @Test
    fun `non-2xx surfaces as IOException with body preview`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"code":20011,"message":"API key not valid"}"""))
        try {
            adapter.transcribe(ByteArray(8), "audio/mp4", config)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith("ASR HTTP 401"))
            assertTrue(e.message!!.contains("API key not valid"))
        }
    }

    @Test
    fun `http 200 with non-json body is rejected with preview`() = runBlocking {
        // e.g. an interception proxy answering 200 with an HTML page
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body>blocked</body></html>"))
        try {
            adapter.transcribe(ByteArray(8), "audio/mp4", config)
            fail("expected IOException")
        } catch (e: IOException) {
            val msg = e.message!!
            assertTrue(msg.contains("non-JSON body"))
            assertTrue(msg.contains("HTTP 200"))
            assertTrue(msg.contains("text/html"))
            assertTrue(msg.contains("<html><body>blocked"))
        }
    }

    @Test
    fun `empty text field is returned as-is for silence`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":""}"""))
        assertEquals("", adapter.transcribe(ByteArray(8), "audio/mp4", config))
    }

    @Test
    fun `language and prompt are forwarded when set`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"hi"}"""))
        adapter.transcribe(
            ByteArray(8), "audio/wav",
            AsrConfig(model = "whisper-1", language = "en", prompt = "greeting"),
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("name=\"language\""))
        assertTrue(body.contains("en"))
        assertTrue(body.contains("name=\"prompt\""))
    }

    @Test
    fun `wav mime maps to a wav filename and content type`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"text":"ok"}"""))
        adapter.transcribe(ByteArray(8), "audio/wav", config)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("filename=\"speech.wav\""))
        assertTrue(body.contains("Content-Type: audio/wav"))
    }
}
