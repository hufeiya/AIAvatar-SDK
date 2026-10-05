package com.neethu.aiadapter.edge

import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** 二进制音频帧：2 字节大端头长 + 头 + MP3 负载。 */
private fun audioFrame(payload: ByteArray): ByteString {
    val header = "Path:audio\r\nContent-Type:audio/mpeg\r\n"
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf((header.length shr 8).toByte(), header.length.toByte()))
    out.write(header.toByteArray(Charsets.UTF_8))
    out.write(payload)
    return out.toByteArray().toByteString()
}

/**
 * MockWebServer 的 WebSocket 升级扮演 Edge 朗读服务端，逐条校验客户端协议序
 * （speech.config → ssml → 收音频分片 → turn.end）与鉴权/文本预处理。
 * Sec-MS-GEC 的已知答案向量由 python hashlib 按 edge-tts drm.py 算法独立算出。
 */
class EdgeTtsAdapterTest {

    private lateinit var server: MockWebServer

    /** 固定时钟：token 是时间衍生的，锁已知答案必须钉死时间。 */
    private val fixedNowMs = 1_700_000_000_000L

    private lateinit var adapter: EdgeTtsAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = EdgeTtsAdapter(
            endpoint = server.url("/edge/v1").toString(),
            client = OkHttpClient(),
            nowMs = { fixedNowMs },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Sec-MS-GEC token（对齐 drm.py 的已知答案向量）────────────────────

    @Test
    fun `sec-ms-gec token matches independently computed known answers`() {
        // unix=0 → ticks=116444736000000000 → sha256("…token") 大写
        assertEquals(
            "7ECB79D14E3AA576D2D79E6D487A1388156D91E614B1BE11C64226A29BC8DD8C",
            EdgeTtsDrm.generateSecMsGec(0),
        )
        // unix=1234567890 → 5 分钟向下取整 → ticks=128790414000000000
        assertEquals(
            "581B1D01526D49A559443E5ACAF8D930550151E2C7028166DAA567921BE87886",
            EdgeTtsDrm.generateSecMsGec(1_234_567_890),
        )
    }

    @Test
    fun `token is stable within the same 5 minute bucket`() {
        // 桶边界算术：ts=1700000000 落在 (…34600, …34900] 桶内（+WIN_EPOCH 后 mod 300 = 200），
        // 同桶上限 1700000099，1700000100 起进下一桶
        val a = EdgeTtsDrm.generateSecMsGec(1_700_000_000)
        val b = EdgeTtsDrm.generateSecMsGec(1_700_000_099) // 同一 300s 桶
        val c = EdgeTtsDrm.generateSecMsGec(1_700_000_100) // 下一桶
        assertEquals(a, b)
        assertTrue(a != c)
    }

    // ── ws 服务端剧本 ────────────────────────────────────────────────────

    /**
     * 剧本服务端：记录客户端两条文本消息，收到 ssml 后回 二进制音频×n → turn.end。
     */
    private class ScriptedServer(
        private val audioChunks: List<ByteArray>,
    ) : WebSocketListener() {
        val clientTexts = mutableListOf<String>()
        private val lock = Object()

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) { clientTexts += text }
            if (text.contains("Path:ssml")) {
                audioChunks.forEach { webSocket.send(audioFrame(it)) }
                webSocket.send("X-RequestId:abc\r\nPath:turn.end\r\n\r\n")
                // 与真实服务端一致：终态后主动断连；不关会让 MockWebServer.shutdown
                // 等连接排空超时（火山测试同款坑）
                webSocket.close(1000, null)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit
    }

    private class TurnEndFirstServer : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("Path:ssml")) {
                webSocket.send("X-RequestId:abc\r\nPath:turn.end\r\n\r\n")
                webSocket.close(1000, null)
            }
        }
    }

    private class CloseEarlyServer : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("Path:ssml")) webSocket.close(1000, "bye")
        }
    }

    private class UnknownPathServer : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.contains("Path:ssml")) {
                webSocket.send("X-RequestId:abc\r\nPath:surprise\r\n\r\n{}")
                webSocket.close(1000, null)
            }
        }
    }

    private val config = TtsConfig(model = "ignored", voice = "zh-CN-XiaoxiaoNeural")

    @Test
    fun `happy path concatenates audio chunks and returns mp3`() = runBlocking {
        val script = ScriptedServer(audioChunks = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)))
        server.enqueue(MockResponse().withWebSocketUpgrade(script))

        val result = adapter.synthesize("今天天气真不错。", config)

        assertEquals(TtsAudioFormat.MP3, result.format)
        assertTrue(result.audio.contentEquals(byteArrayOf(1, 2, 3, 4, 5)))

        // 握手 URL 与鉴权头
        val recorded = server.takeRequest()
        val url = recorded.requestUrl!!.toString()
        assertTrue("TrustedClientToken expected", url.contains(EdgeTtsDrm.TRUSTED_CLIENT_TOKEN))
        assertTrue("Sec-MS-GEC expected", url.contains("Sec-MS-GEC=${EdgeTtsDrm.generateSecMsGec(fixedNowMs / 1000)}"))
        assertTrue(
            "Sec-MS-GEC-Version expected",
            url.contains("Sec-MS-GEC-Version=${EdgeTtsDrm.SEC_MS_GEC_VERSION}"),
        )
        assertTrue("ConnectionId expected", url.contains("ConnectionId="))
        assertEquals(EdgeTtsDrm.USER_AGENT, recorded.getHeader("User-Agent"))
        assertEquals("chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold", recorded.getHeader("Origin"))
        assertTrue(recorded.getHeader("Cookie")!!.startsWith("muid="))

        // 消息序：speech.config（带 outputFormat）→ ssml（带音色与正文）
        assertEquals(2, script.clientTexts.size)
        val speechConfig = script.clientTexts[0]
        assertTrue(speechConfig.contains("Path:speech.config"))
        assertTrue(speechConfig.contains(EdgeTtsAdapter.OUTPUT_FORMAT))
        val ssml = script.clientTexts[1]
        assertTrue(ssml.contains("Path:ssml"))
        assertTrue(ssml.contains("<voice name='zh-CN-XiaoxiaoNeural'>"))
        assertTrue(ssml.contains("今天天气真不错。"))
        assertTrue(ssml.contains("rate='+0%'"))
        // X-Timestamp 末尾 Z 是对齐 edge-tts 的（微软 bug 语义）
        assertTrue(ssml.contains(Regex("X-Timestamp:.+Z\\r\\n")))
    }

    @Test
    fun `ssml escapes xml special characters`() = runBlocking {
        val script = ScriptedServer(audioChunks = listOf(byteArrayOf(9)))
        server.enqueue(MockResponse().withWebSocketUpgrade(script))

        adapter.synthesize("a<b>&c", config)

        val ssml = script.clientTexts[1]
        assertTrue(ssml.contains("a&lt;b&gt;&amp;c"))
        assertTrue(!ssml.contains("a<b>&c"))
    }

    @Test
    fun `speed maps to signed prosody rate`() = runBlocking {
        val script = ScriptedServer(audioChunks = listOf(byteArrayOf(9)))
        server.enqueue(MockResponse().withWebSocketUpgrade(script))

        adapter.synthesize("测试", config.copy(speed = 1.2f))

        assertTrue(script.clientTexts[1].contains("rate='+20%'"))
    }

    @Test
    fun `blank voice falls back to default`() = runBlocking {
        val script = ScriptedServer(audioChunks = listOf(byteArrayOf(9)))
        server.enqueue(MockResponse().withWebSocketUpgrade(script))

        adapter.synthesize("测试", config.copy(voice = " "))

        assertTrue(script.clientTexts[1].contains("<voice name='${EdgeTtsAdapter.DEFAULT_VOICE}'>"))
    }

    @Test
    fun `turn end without audio surfaces as IOException`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(TurnEndFirstServer()))
        try {
            adapter.synthesize("测试", config)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue("audio expected in: ${e.message}", e.message!!.contains("未收到任何音频"))
        }
    }

    @Test
    fun `close before turn end surfaces as IOException`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(CloseEarlyServer()))
        try {
            adapter.synthesize("测试", config)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue("closed expected in: ${e.message}", e.message!!.contains("被关闭"))
        }
    }

    @Test
    fun `unknown response path surfaces as IOException`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(UnknownPathServer()))
        try {
            adapter.synthesize("测试", config)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue("path expected in: ${e.message}", e.message!!.contains("surprise"))
        }
    }

    @Test
    fun `blank text fails fast`() = runBlocking {
        try {
            adapter.synthesize("   ", config)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("为空"))
        }
    }

    @Test
    fun `http 403 with server date adjusts clock skew and retries once`() = runBlocking {
        // 服务端比固定时钟快 300s 整 → 重试请求的 token 应等于按服务端时间算出的值
        val serverTimeMs = fixedNowMs + 300_000L
        val dateHeader = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(serverTimeMs))

        val retryScript = ScriptedServer(audioChunks = listOf(byteArrayOf(7)))
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Date", dateHeader))
        server.enqueue(MockResponse().withWebSocketUpgrade(retryScript))

        val result = adapter.synthesize("测试", config)

        assertTrue(result.audio.contentEquals(byteArrayOf(7)))
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(
            "first attempt uses uncorrected clock",
            first.requestUrl!!.queryParameter("Sec-MS-GEC") ==
                EdgeTtsDrm.generateSecMsGec(fixedNowMs / 1000),
        )
        assertEquals(
            "second attempt uses server-corrected clock",
            EdgeTtsDrm.generateSecMsGec(serverTimeMs / 1000),
            second.requestUrl!!.queryParameter("Sec-MS-GEC"),
        )
    }

    // ── 文本预处理纯函数 ─────────────────────────────────────────────────

    @Test
    fun `incompatible control characters become spaces`() {
        // 垂直制表 0x0B 是 OCR 文本常客，服务端见到就报错
        assertEquals("a c  d", removeIncompatibleCharacters("a\u000Bc\u0001\u0007d"))
        assertEquals("tab\tkept", removeIncompatibleCharacters("tab\tkept"))
    }

    @Test
    fun `xml escape covers ampersand less-than greater-than`() {
        assertEquals("a&amp;b&lt;c&gt;d", escapeXml("a&b<c>d"))
    }

    @Test
    fun `short text yields single chunk`() {
        assertEquals(listOf("今天天气真不错。"), splitTextByByteLength("今天天气真不错。", 4096))
    }

    @Test
    fun `long text splits at spaces without exceeding byte limit`() {
        val words = List(200) { "word$it" }
        val text = words.joinToString(" ")
        val chunks = splitTextByByteLength(text, 512)
        assertTrue("expect multiple chunks", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(chunk.toByteArray(Charsets.UTF_8).size <= 512)
        }
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `whitespace-free scripts are sent whole like upstream edge-tts`() {
        // 上游语义（split_text_by_byte_length）：找不到空格/换行且整段是合法
        // UTF-8 时**不硬截断**，剩余文本整段一块——7200 字节中文整段 SSML 实测
        // 端点接受（2026-10-05），这里锁该行为防将来"顺手修正"。
        assertEquals(listOf("你好世界测试"), splitTextByByteLength("你好世界测试", 7))
    }

    @Test
    fun `split never lands inside an xml entity`() {
        val chunks = splitTextByByteLength("one &amp; two", 7)
        assertEquals(listOf("one", "&amp;", "two"), chunks)
    }
}
