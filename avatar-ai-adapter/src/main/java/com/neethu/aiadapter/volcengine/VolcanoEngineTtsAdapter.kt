package com.neethu.aiadapter.volcengine

import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 火山引擎「豆包语音合成大模型 2.0」（seed-tts-2.0）适配器。
 *
 * 走 V3 双向流式 WebSocket 协议（wss://openspeech.bytedance.com/api/v3/tts/bidirection），
 * 但对外保持 [TtsAdapter] 的句级整段语义：每次 [synthesize] 开一条连接、整句文本
 * 一次下发、收齐全部音频后关闭。SpeechPipeline 的句级并发（≤4）原样适用。
 *
 * 鉴权用豆包语音控制台签发的 API Key（`X-Api-Key` 头）+ 资源号 `seed-tts-2.0`——
 * 注意这**不是**方舟（Ark）大模型的 API Key，两个控制台各发各的。
 *
 * 二进制协议（对齐火山官方 arkitect Python SDK，帧结构一字不差）：
 * - 4 字节头：`[version<<4|headerSize, msgType<<4|flags, ser<<4|compression, 0x00]`
 * - 帧体：`event(int32 BE)` + 可选 `connectionId/sessionId(u32 len + utf8)` + `payload(u32 len + bytes)`
 * - 交互：StartConnection → ConnectionStarted → StartSession(连接级) → SessionStarted
 *   → TaskRequest(会话级, text) → FinishSession → 音频分片(无序列化) → SessionFinished
 * - 输出恒为 16-bit LE 单声道 PCM（`audio_params.format="pcm"`），采样率取
 *   [TtsConfig.sampleRate]（默认 16000，与 wLipSync 口型管线标定输入一致）。
 *
 * `TtsConfig.model` 被忽略（模型由资源号选定）；`voice` 即 speaker 音色 id，
 * 留空时省略 speaker 字段走服务端默认音色。
 */
class VolcanoEngineTtsAdapter(
    private val apiKey: String,
    /** 端点可注入：单测里换成 MockWebServer 的 ws:// 地址。 */
    private val endpoint: String = ENDPOINT,
    client: OkHttpClient = OkHttpClient(),
) : TtsAdapter {

    /** 独立配置：WebSocket 建连/读超时对长句要留余量，且不得污染调用方的 client。 */
    private val wsClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun synthesize(text: String, config: TtsConfig): TtsResult =
        withContext(Dispatchers.IO) {
            val sampleRate = config.sampleRate ?: DEFAULT_SAMPLE_RATE
            val speaker = config.voice.trim()

            val request = Request.Builder()
                .url(endpoint)
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", RESOURCE_ID)
                .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                .header("X-Tt-Logid", UUID.randomUUID().toString().replace("-", ""))
                .build()

            // 服务器 → 协程 的消息通道；onFailure/onClosed 也折算成事件入队，
            // 保证接收循环总能前进（不会既等不到消息又没人唤醒）。
            val inbox = Channel<InboxEvent>(Channel.UNLIMITED)
            val connectionId = UUID.randomUUID().toString()
            var webSocket: WebSocket? = null

            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    // 握手完成即发 StartConnection；服务端回 ConnectionStarted 后才可开会话
                    ws.send(frame(EVENT_START_CONNECTION).toByteString())
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    inbox.trySend(parseFrame(bytes.toByteArray()))
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val hint = response?.let { " HTTP ${it.code}" }.orEmpty()
                    inbox.trySend(InboxEvent(failure = IOException("火山TTS连接失败$hint: ${t.message}", t)))
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    // 服务器在 SessionFinished 后主动断连属正常路径；此前的
                    // 意外关闭由接收循环的完成标志兜底报错
                    inbox.trySend(InboxEvent(closed = true))
                }
            }

            var finished = false
            try {
                webSocket = wsClient.newWebSocket(request, listener)

                // onOpen 里已发 StartConnection，这里等服务端确认
                awaitFirstFrame(inbox, expectEvent = EVENT_CONNECTION_STARTED, label = "建连")

                val connIdBytes = connectionId.toByteArray(Charsets.UTF_8)
                webSocket.send(
                    frame(
                        event = EVENT_START_SESSION,
                        tagBytes = connIdBytes,
                        payload = startSessionPayload(speaker, sampleRate),
                    ).toByteString()
                )
                val sessionStarted = awaitFirstFrame(inbox, expectEvent = EVENT_SESSION_STARTED, label = "开会话")
                val sessionId = sessionStarted.sessionId
                    ?: throw IOException("火山TTS会话启动响应缺少 sessionId")

                webSocket.send(
                    frame(
                        event = EVENT_TASK_REQUEST,
                        tagBytes = sessionId.toByteArray(Charsets.UTF_8),
                        payload = taskRequestPayload(speaker, sampleRate, text),
                    ).toByteString()
                )
                // 整句文本一次下发后立即通知服务器输入结束
                webSocket.send(frame(EVENT_FINISH_SESSION, sessionId.toByteArray(Charsets.UTF_8)).toByteString())

                val audio = java.io.ByteArrayOutputStream()
                while (true) {
                    val ev = receiveFrame(inbox)
                    ev.audio?.let { audio.write(it) }
                    if (ev.failure != null) throw ev.failure
                    if (ev.sessionFinished) {
                        finished = true
                        break
                    }
                    if (ev.closed) {
                        throw IOException("火山TTS连接在合成完成前被关闭（未收到 SessionFinished）")
                    }
                }
                val bytes = audio.toByteArray()
                if (bytes.isEmpty()) throw IOException("火山TTS返回了空音频（音色/参数可能不被支持）")
                TtsResult(bytes, TtsAudioFormat.RAW_PCM_16LE, sampleRate)
            } finally {
                // 正常完成走优雅关闭；异常/取消直接 cancel 防止连接悬挂
                val ws = webSocket
                if (ws != null) {
                    if (finished) ws.close(1000, null) else ws.cancel()
                }
            }
        }

    /** 一次服务端消息的归一化表示。 */
    internal class InboxEvent(
        val event: Int? = null,
        val sessionId: String? = null,
        val audio: ByteArray? = null,
        val sessionFinished: Boolean = false,
        val closed: Boolean = false,
        val failure: IOException? = null,
        val payloadPreview: String? = null,
    )

    private suspend fun awaitFirstFrame(inbox: Channel<InboxEvent>, expectEvent: Int, label: String): InboxEvent =
        receiveFrame(inbox).also { ev ->
            if (ev.failure != null) throw ev.failure
            if (ev.event != expectEvent) {
                throw IOException(
                    "火山TTS${label}失败：期望事件 $expectEvent 实得 ${ev.event}" +
                        (ev.payloadPreview?.let { " $it" } ?: "")
                )
            }
        }

    private suspend fun receiveFrame(inbox: Channel<InboxEvent>): InboxEvent {
        // 每条消息 30s 看门狗：服务器停摆时不无限占用 SpeechPipeline 的合成槽
        val ev = withTimeoutOrNull(MESSAGE_TIMEOUT_MS) { inbox.receive() }
        return ev ?: InboxEvent(failure = IOException("火山TTS等待服务器消息超时(${MESSAGE_TIMEOUT_MS}ms)"))
    }

    private fun startSessionPayload(speaker: String, sampleRate: Int): ByteArray {
        val params = buildJsonObject {
            put("speaker", speaker.ifBlank { DEFAULT_SPEAKER })
            put("audio_params", buildJsonObject {
                put("format", "pcm")
                put("sample_rate", sampleRate)
            })
        }
        return buildJsonObject {
            put("event", EVENT_START_SESSION)
            put("namespace", NAMESPACE)
            put("req_params", params)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    private fun taskRequestPayload(speaker: String, sampleRate: Int, text: String): ByteArray {
        val params = buildJsonObject {
            put("speaker", speaker.ifBlank { DEFAULT_SPEAKER })
            put("audio_params", buildJsonObject {
                put("format", "pcm")
                put("sample_rate", sampleRate)
            })
            put("text", text)
        }
        return buildJsonObject {
            put("event", EVENT_TASK_REQUEST)
            put("namespace", NAMESPACE)
            put("req_params", params)
        }.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val ENDPOINT = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
        /** 豆包语音合成大模型 2.0（seed-tts-2.0）的资源号；API Key 鉴权域只认这个值。 */
        const val RESOURCE_ID = "seed-tts-2.0"
        const val NAMESPACE = "BidirectionalTTS"
        const val DEFAULT_SAMPLE_RATE = 16_000

        // 官方默认音色（speaker 留空时兜底，正常路径设置页恒会解析出一个音色）
        const val DEFAULT_SPEAKER = "zh_female_vv_uranus_bigtts"

        private const val MESSAGE_TIMEOUT_MS = 30_000L

        // 事件号（与官方 SDK constants.py 一致）
        const val EVENT_START_CONNECTION = 1
        const val EVENT_CONNECTION_STARTED = 50
        const val EVENT_CONNECTION_FAILED = 51
        const val EVENT_START_SESSION = 100
        const val EVENT_FINISH_SESSION = 102
        const val EVENT_SESSION_STARTED = 150
        const val EVENT_SESSION_FINISHED = 152
        const val EVENT_SESSION_FAILED = 153
        const val EVENT_TASK_REQUEST = 200

        // 消息类型 / 标志 / 序列化（协议常量）
        private const val MSG_FULL_CLIENT = 0b0001
        private const val MSG_ERROR_SERVER = 0b0110
        private const val FLAG_WITH_EVENT = 0b0100
        private const val SER_JSON = 0b0001
        private const val SER_NONE = 0b0000
        private const val COMP_NONE = 0b0000
        private const val COMP_GZIP = 0b0001

        /**
         * 组一帧 FullClientRequest（JSON、不压缩、带事件号 + 连接/会话 id）。
         * 与官方 `_write_header` + `_write_message` 的字节序逐字段一致。
         */
        internal fun frame(event: Int, tagBytes: ByteArray? = null, payload: ByteArray = "{}".toByteArray()): ByteArray {
            val header = byteArrayOf(
                (0b0001 shl 4 or 0b0001).toByte(),   // version=1, headerSize=1 (4 bytes)
                (MSG_FULL_CLIENT shl 4 or FLAG_WITH_EVENT).toByte(),
                (SER_JSON shl 4 or COMP_NONE).toByte(),
                0x00,
            )
            val out = java.io.ByteArrayOutputStream()
            out.write(header)
            out.write(writeI32(event))
            tagBytes?.let {
                out.write(writeU32(it.size))
                out.write(it)
            }
            out.write(writeU32(payload.size))
            out.write(payload)
            return out.toByteArray()
        }

        /** 解析一帧服务端消息（对齐官方 parse_response）。 */
        internal fun parseFrame(bytes: ByteArray): InboxEvent {
            val headerSize = bytes[0].toInt() and 0x0F
            val msgType = (bytes[1].toInt() and 0xFF) shr 4
            val flags = bytes[1].toInt() and 0x0F
            val serialization = (bytes[2].toInt() and 0xFF) shr 4
            val compression = bytes[2].toInt() and 0x0F
            // 头部定长 headerSize*4（官方 parse_response 语义；逐字节步进易错一位）
            var ptr = headerSize * 4

            var event: Int? = null
            var sessionId: String? = null
            val withEvent = flags == FLAG_WITH_EVENT
            if (withEvent) {
                event = readI32(bytes, ptr); ptr += 4
                if (event !in setOf(1, 2, EVENT_CONNECTION_STARTED, EVENT_CONNECTION_FAILED, EVENT_CONNECTION_FINISHED_CONN)) {
                    val len = readU32(bytes, ptr); ptr += 4
                    sessionId = String(bytes, ptr, len, Charsets.UTF_8); ptr += len
                }
            }
            val payloadSize = readI32(bytes, ptr); ptr += 4
            var payload = if (payloadSize >= 0) bytes.copyOfRange(ptr, ptr + payloadSize) else bytes.copyOfRange(ptr, bytes.size)
            if (compression == COMP_GZIP) {
                payload = java.util.zip.GZIPInputStream(payload.inputStream()).readBytes()
            }
            return when {
                msgType == MSG_ERROR_SERVER || event == EVENT_CONNECTION_FAILED || event == EVENT_SESSION_FAILED -> {
                    val preview = payload.toString(Charsets.UTF_8).take(300)
                    InboxEvent(event = event, sessionId = sessionId, failure = IOException("火山TTS服务端错误: $preview"))
                }
                event == EVENT_SESSION_FINISHED -> InboxEvent(event = event, sessionId = sessionId, sessionFinished = true)
                serialization == SER_NONE ->
                    InboxEvent(event = event, sessionId = sessionId, audio = payload)
                serialization == SER_JSON ->
                    InboxEvent(event = event, sessionId = sessionId, payloadPreview = payload.toString(Charsets.UTF_8).take(300))
                else -> InboxEvent(event = event, sessionId = sessionId)
            }
        }

        /** ConnectionStarted 的事件号（与连接生命周期三兄弟共用解析分支）。 */
        const val EVENT_CONNECTION_FINISHED_CONN = 52

        private fun writeI32(v: Int): ByteArray =
            byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

        private fun writeU32(v: Int): ByteArray = writeI32(v)

        private fun readI32(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
                ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

        private fun readU32(b: ByteArray, off: Int): Int = readI32(b, off)
    }
}
