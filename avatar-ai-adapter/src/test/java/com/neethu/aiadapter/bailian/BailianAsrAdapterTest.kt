package com.neethu.aiadapter.bailian

import com.neethu.aiadapter.model.AsrConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 百炼 ASR 适配器：/audio/transcriptions 在 compatible-mode 实测 404，语音
 * 识别走 OpenAI 兼容 chat 形态的 input_audio（数据 URL base64 + 容器名）。
 */
class BailianAsrAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: BailianAsrAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = BailianAsrAdapter(server.url("/compatible-mode/v1").toString(), apiKey = "sk-bl")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts chat-shaped input_audio with data url and reads content`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"finish_reason":"stop","index":0,"message":{
                    "annotations":[{"emotion":"happy","language":"zh","type":"audio_info"}],
                    "content":"你好，我是你的虚拟伙伴。","role":"assistant"}}],
                    "model":"qwen3-asr-flash","object":"chat.completion"}""",
            ),
        )
        val text = adapter.transcribe(
            audio = byteArrayOf(1, 2, 3, 4),
            mime = "audio/wav",
            config = AsrConfig(model = "qwen3-asr-flash"),
        )
        assertEquals("你好，我是你的虚拟伙伴。", text)

        val recorded = server.takeRequest()
        assertEquals("/compatible-mode/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-bl", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        // 数据 URL 形态 + 容器名（wav）；annotations 只在响应侧，不影响取文本
        assertTrue(body.contains("\"model\":\"qwen3-asr-flash\""))
        assertTrue(body.contains("\"type\":\"input_audio\""))
        assertTrue(body.contains("\"data\":\"data:audio/wav;base64,AQIDBA==\""))
        assertTrue(body.contains("\"format\":\"wav\""))
        // content 必须是纯 input_audio 单项：附带 text 段（哪怕转写指令）
        // 实测 400「does not support this input」（真机+host 双侧 A/B）
        assertFalse(body.contains("\"type\":\"text\""))
    }

    @Test
    fun `mime maps to the container name qwen3-asr accepts`() {
        assertEquals("wav", BailianAsrAdapter.containerFormatFor("audio/wav"))
        assertEquals("wav", BailianAsrAdapter.containerFormatFor("audio/x-wav"))
        assertEquals("m4a", BailianAsrAdapter.containerFormatFor("audio/mp4"))
        assertEquals("mp3", BailianAsrAdapter.containerFormatFor("audio/mpeg"))
        assertEquals("amr", BailianAsrAdapter.containerFormatFor("audio/amr"))
        // 未知类型兜底 wav（不因 mime 生僻而发非法 format）
        assertEquals("wav", BailianAsrAdapter.containerFormatFor("audio/weird"))
    }

    @Test
    fun `blank model falls back to qwen3-asr-flash`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"choices":[{"message":{"content":"hi"}}]}"""),
        )
        adapter.transcribe(byteArrayOf(1), "audio/wav", AsrConfig(model = ""))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"model\":\"qwen3-asr-flash\""))
    }

    @Test
    fun `http and non-json failures carry the response preview`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":{"message":"does not support this input","code":"invalid_parameter_error"}}""",
        ))
        val e = assertThrows(IOException::class.java) {
            runBlocking { adapter.transcribe(byteArrayOf(1), "audio/wav", AsrConfig(model = "qwen3-asr-flash")) }
        }
        assertTrue(e.message!!.contains("HTTP 400"))
        assertTrue(e.message!!.contains("does not support this input"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>gateway</html>"))
        val e2 = assertThrows(IOException::class.java) {
            runBlocking { adapter.transcribe(byteArrayOf(1), "audio/wav", AsrConfig(model = "qwen3-asr-flash")) }
        }
        assertTrue(e2.message!!.contains("unexpected body"))
    }
}
