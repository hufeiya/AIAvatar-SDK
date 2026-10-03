package com.neethu.aiadapter.openai

import com.neethu.aiadapter.model.TtsConfig
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
 * The TTS adapter must surface provider/gateway failures as readable errors —
 * never hand a non-audio body to the PCM decoder (which would only say
 * "Not a RIFF file" and hide the real cause, e.g. an HTML page from a proxy).
 */
class OpenAiCompatibleTtsAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: OpenAiCompatibleTtsAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = OpenAiCompatibleTtsAdapter(server.url("/v1").toString(), "sk-test")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val config = TtsConfig(model = "tts", voice = "alloy", responseFormat = "wav", sampleRate = 16_000)

    private fun validWav(): ByteArray {
        val riff = "RIFF".toByteArray(Charsets.US_ASCII)
        val rest = ByteArray(60) { 0x55 }
        return riff + rest
    }

    @Test
    fun `non-2xx surfaces as IOException with body preview`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"code":20047,"message":"Invalid voice."}"""))
        try {
            adapter.synthesize("你好", config)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith("TTS HTTP 400"))
            assertTrue(e.message!!.contains("Invalid voice"))
        }
    }

    @Test
    fun `http 200 with non-wav body is rejected with preview instead of reaching the decoder`() = runBlocking {
        // e.g. an interception proxy answering 200 with an HTML page
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/html")
            .setBody("<html><body>blocked</body></html>"))
        try {
            adapter.synthesize("你好", config)
            fail("expected IOException")
        } catch (e: IOException) {
            val msg = e.message!!
            assertTrue(msg.contains("non-wav body"))
            assertTrue(msg.contains("HTTP 200"))
            assertTrue(msg.contains("text/html"))
            assertTrue(msg.contains("<html><body>blocked"))  // preview shows the real cause
        }
    }

    @Test
    fun `valid wav body passes through unchanged`() = runBlocking {
        val wav = validWav()
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "audio/wav")
            .setBody(okio.Buffer().write(wav)))
        val result = adapter.synthesize("你好", config)
        assertTrue(result.format == com.neethu.aiadapter.model.TtsAudioFormat.WAV)
        assertTrue(wav.contentEquals(result.audio))
        val recorded = server.takeRequest()
        assertEquals("/v1/audio/speech", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"sample_rate\":16000"))
    }

    @Test
    fun `mp3 body with frame sync is accepted for mp3 format`() = runBlocking {
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(16)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(mp3)))
        val result = adapter.synthesize("你好", config.copy(responseFormat = "mp3"))
        assertTrue(result.format == com.neethu.aiadapter.model.TtsAudioFormat.MP3)
    }
}
