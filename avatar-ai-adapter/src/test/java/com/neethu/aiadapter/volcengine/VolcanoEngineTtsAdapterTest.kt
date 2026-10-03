package com.neethu.aiadapter.volcengine

import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import kotlinx.coroutines.runBlocking
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * 用 MockWebServer 的 WebSocket 升级扮演火山 v3 双向流式服务端，逐帧校验
 * 客户端协议序（StartConnection → StartSession → TaskRequest → FinishSession）
 * 与音频/错误路径。服务端帧构造与客户端 [VolcanoEngineTtsAdapter.parseFrame]
 * 共用同一套编解码约定（消息类型位换成服务端值）。
 */
private fun i32(v: Int) =
    byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

private const val FULL_SERVER = 0b0011
private const val AUDIO_ONLY_SERVER = 0b0100
private const val FLAG_WITH_EVENT = 0b0100
private const val SER_JSON = 0b0001
private const val SER_NONE = 0b0000

/** 服务端帧：4 字节头 + 可选 event + 可选 session id + payload。 */
private fun serverFrame(
    msgType: Int,
    event: Int?,
    sessionId: String? = null,
    payload: ByteArray = "{}".toByteArray(),
    serialization: Int = SER_JSON,
): ByteString {
    val flags = if (event != null) FLAG_WITH_EVENT else 0
    val out = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf(0x11, (msgType shl 4 or flags).toByte(), (serialization shl 4).toByte(), 0))
    event?.let { out.write(i32(it)) }
    sessionId?.let {
        val b = it.toByteArray(Charsets.UTF_8)
        out.write(i32(b.size)); out.write(b)
    }
    out.write(i32(payload.size)); out.write(payload)
    return out.toByteArray().toByteString()
}

/** 从客户端帧里解析 (event, 完整payload文本)——只解 FULL_CLIENT 帧所需字段。 */
private fun parseClientFrame(bytes: ByteArray): Pair<Int, String> {
    var ptr = (bytes[0].toInt() and 0x0F) * 4
    fun readI32(): Int {
        val v = ((bytes[ptr].toInt() and 0xFF) shl 24) or ((bytes[ptr + 1].toInt() and 0xFF) shl 16) or
            ((bytes[ptr + 2].toInt() and 0xFF) shl 8) or (bytes[ptr + 3].toInt() and 0xFF)
        ptr += 4
        return v
    }
    val event = readI32()
    if (event !in setOf(1, 2, 50, 51, 52)) { // 会话级事件带 session id，跳过
        val idLen = readI32(); ptr += idLen
    }
    val payloadLen = readI32()
    return event to String(bytes, ptr, payloadLen, Charsets.UTF_8)
}

class VolcanoEngineTtsAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: VolcanoEngineTtsAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = VolcanoEngineTtsAdapter("volc-key", server.url("/api/v3/tts/bidirection").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── 测试用帧编解码 ───────────────────────────────────────────────────

    /**
     * 剧本服务端：记录客户端每帧 (event, payload)，并按事件回包——
     * StartConnection→ConnectionStarted、StartSession→SessionStarted(sess-1)、
     * TaskRequest→音频分片+SessionFinished（或 SessionFailed 报错剧本）。
     */
    private class ScriptedServer(
        private val audioChunks: List<ByteArray>,
        private val failWith: String? = null,
    ) : WebSocketListener() {
        val clientEvents = mutableListOf<Int>()
        val clientPayloads = mutableListOf<Pair<Int, String>>()
        private val lock = Object()

        override fun onMessage(webSocket: WebSocket, text: String) = Unit

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val (event, payload) = parseClientFrame(bytes.toByteArray())
            synchronized(lock) {
                clientEvents += event
                clientPayloads += event to payload
            }
            when (event) {
                VolcanoEngineTtsAdapter.EVENT_START_CONNECTION ->
                    webSocket.send(
                        serverFrame(FULL_SERVER, VolcanoEngineTtsAdapter.EVENT_CONNECTION_STARTED)
                    )
                VolcanoEngineTtsAdapter.EVENT_START_SESSION ->
                    webSocket.send(
                        serverFrame(FULL_SERVER, VolcanoEngineTtsAdapter.EVENT_SESSION_STARTED, sessionId = "sess-1")
                    )
                VolcanoEngineTtsAdapter.EVENT_TASK_REQUEST -> {
                    if (failWith != null) {
                        webSocket.send(
                            serverFrame(
                                FULL_SERVER, VolcanoEngineTtsAdapter.EVENT_SESSION_FAILED, sessionId = "sess-1",
                                payload = """{"code":45000001,"message":"$failWith"}""".toByteArray(),
                            )
                        )
                    } else {
                        audioChunks.forEach { chunk ->
                            webSocket.send(
                                serverFrame(
                                    AUDIO_ONLY_SERVER, 352, sessionId = "sess-1",
                                    payload = chunk, serialization = SER_NONE,
                                )
                            )
                        }
                        webSocket.send(
                            serverFrame(FULL_SERVER, VolcanoEngineTtsAdapter.EVENT_SESSION_FINISHED, sessionId = "sess-1")
                        )
                    }
                    // 真实服务端在终态事件后即断连（host 探测实测）；不主动关会让
                    // MockWebServer.shutdown 等队列排空超时
                    webSocket.close(1000, null)
                }
            }
        }

    }

    private val config = TtsConfig(model = "ignored", voice = "zh_female_vv_uranus_bigtts", sampleRate = 16_000)

    @Test
    fun `happy path completes protocol handshake and concatenates audio chunks`() = runBlocking {
        val script = ScriptedServer(audioChunks = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5)))
        server.enqueue(MockResponse().withWebSocketUpgrade(script))

        val result = adapter.synthesize("今天天气真不错。", config)

        assertEquals(TtsAudioFormat.RAW_PCM_16LE, result.format)
        assertEquals(16_000, result.rawPcmSampleRate)
        assertTrue(result.audio.contentEquals(byteArrayOf(1, 2, 3, 4, 5)))

        // 鉴权头与资源号：API Key 鉴权域只认 seed-tts-2.0
        val recorded = server.takeRequest()
        assertEquals("volc-key", recorded.getHeader("X-Api-Key"))
        assertEquals("seed-tts-2.0", recorded.getHeader("X-Api-Resource-Id"))
        assertNotNull(recorded.getHeader("X-Api-Connect-Id"))

        // 协议序：StartConnection → StartSession → TaskRequest → FinishSession
        assertEquals(
            listOf(
                VolcanoEngineTtsAdapter.EVENT_START_CONNECTION,
                VolcanoEngineTtsAdapter.EVENT_START_SESSION,
                VolcanoEngineTtsAdapter.EVENT_TASK_REQUEST,
                VolcanoEngineTtsAdapter.EVENT_FINISH_SESSION,
            ),
            script.clientEvents,
        )
    }

    @Test
    fun `start session payload carries speaker and pcm16k audio params`() = runBlocking {
        val script = ScriptedServer(audioChunks = listOf(byteArrayOf(9)))
        server.enqueue(MockResponse().withWebSocketUpgrade(script))

        adapter.synthesize("测试", config)

        val startSessionPayload = script.clientPayloads
            .first { it.first == VolcanoEngineTtsAdapter.EVENT_START_SESSION }.second
        assertTrue("speaker expected", startSessionPayload.contains("zh_female_vv_uranus_bigtts"))
        assertTrue("pcm format expected", startSessionPayload.contains("\"format\":\"pcm\""))
        assertTrue("16k expected", startSessionPayload.contains("\"sample_rate\":16000"))
        assertTrue("namespace expected", startSessionPayload.contains("BidirectionalTTS"))

        val taskPayload = script.clientPayloads
            .first { it.first == VolcanoEngineTtsAdapter.EVENT_TASK_REQUEST }.second
        assertTrue("text expected", taskPayload.contains("测试"))
    }

    @Test
    fun `session failed event surfaces as IOException with server message`() = runBlocking {
        val script = ScriptedServer(audioChunks = emptyList(), failWith = "voice quota exceeded")
        server.enqueue(MockResponse().withWebSocketUpgrade(script))

        try {
            adapter.synthesize("测试", config)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(
                "server message expected in: ${e.message}",
                e.message!!.contains("voice quota exceeded"),
            )
        }
    }

    @Test
    fun `blank voice falls back to default speaker and still completes`() = runBlocking {
        val script = ScriptedServer(audioChunks = listOf(byteArrayOf(7)))
        server.enqueue(MockResponse().withWebSocketUpgrade(script))

        val result = adapter.synthesize("测试", config.copy(voice = " "))
        assertEquals(1, result.audio.size)
        val startSessionPayload = script.clientPayloads
            .first { it.first == VolcanoEngineTtsAdapter.EVENT_START_SESSION }.second
        assertTrue(startSessionPayload.contains(VolcanoEngineTtsAdapter.DEFAULT_SPEAKER))
    }
}
