package com.neethu.aiadapter.edge

import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.text.EdgeTtsTexts
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 微软 Edge「大声朗读」免费 TTS 适配器（开源友好，无需 baseUrl/Key）。
 *
 * 走 `speech.platform.bing.com` 的 WSS 端点，对外保持 [TtsAdapter] 的句级整段
 * 语义：每次 [synthesize] 开一条连接、发 SSML、收齐音频分片后关闭。
 * SpeechPipeline 的句级并发（≤4）原样适用。
 *
 * 协议逐字对齐开源实现 edge-tts（python，rany2/edge-tts）——特别是 2024 年起
 * 必需的 `Sec-MS-GEC` 鉴权参数（SHA-256 DRM token，见 [EdgeTtsDrm]，**勿凭记忆
 * 改算法，对齐 drm.py**）：
 * - 握手 URL：`{endpoint}?TrustedClientToken=…&ConnectionId=…&Sec-MS-GEC=…&Sec-MS-GEC-Version=…`
 *   （token 5 分钟一档：Windows file time 向下取整到 300s ×10⁷ 后拼 TrustedClientToken
 *   求 SHA-256 大写十六进制；设备时钟偏移时按服务端 Date 头校偏并重试一次）
 * - 文本消息 1：`Path:speech.config`（JSON，声明 outputFormat）
 * - 文本消息 2：`Path:ssml`（X-RequestId + SSML；XML 转义、控制字符清洗、
 *   超过 4096 字节按词界/UTF-8 边界/实体边界切分，每段独立连接）
 * - 服务端 TEXT：`Path:turn.start/response/audio.metadata` 忽略，`Path:turn.end` 结束
 * - 服务端 BINARY：前 2 字节大端头长 + 头（`Path:audio`）+ MP3 负载，顺序拼接
 *
 * 输出恒为 MP3 24kHz 单声道（`audio-24khz-48kbitrate-mono-mp3`——2026-10-05
 * 实测该端点**只认**这一种 outputFormat，riff wav/其它 mp3 档一律不出声），
 * orchestrator 的 PcmDecoder MediaCodec 路径直接可解，无需新解码器。
 * 注意 24kHz 解码后走 wLipSync 口型管线的分数降采样路径（16k 标定），口型
 * 匹配质量略降——免费引擎的可接受取舍（见 docs/ai-layer-handoff.md 附录 A）。
 *
 * 该接口无 SLA 且随时可能变：所有失败（握手 403/网络/协议不符/空音频/超时）
 * 都抛带上下文的 [IOException]，由调用方给明确的错误事件，不做静默兜底。
 */
class EdgeTtsAdapter(
    /** 端点可注入：单测里换成 MockWebServer 的 ws:// 地址。 */
    private val endpoint: String = ENDPOINT,
    client: OkHttpClient = OkHttpClient(),
    /** 时钟可注入：Sec-MS-GEC token 是时间衍生的，单测用固定时钟锁已知答案。 */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** 用户可读错误文案的语言（多语言支持）；缺省中文保持既有行为。 */
    private val texts: EdgeTtsTexts = EdgeTtsTexts.ZH,
) : TtsAdapter {

    /** 独立配置：WebSocket 建连/读超时对长句要留余量，且不得污染调用方的 client。 */
    private val wsClient = client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 设备时钟相对微软服务器的偏移（秒），403 时按响应 Date 头修正（对齐 drm.py 的 clock skew）。 */
    @Volatile
    private var clockSkewSeconds: Long = 0

    override suspend fun synthesize(text: String, config: TtsConfig): TtsResult =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) throw IOException(texts.emptyText())
            val chunks = splitTextByByteLength(
                escapeXml(removeIncompatibleCharacters(text)),
                MAX_TEXT_BYTES,
                texts = texts,
            )
            val audio = java.io.ByteArrayOutputStream()
            for (chunk in chunks) {
                try {
                    runConnection(chunk, config).let { audio.write(it) }
                } catch (e: ClockSkewRetry) {
                    // 403 + 服务端 Date：校偏后整段重试一次（对齐 edge-tts 的
                    // handle_client_response_error → 重试语义）
                    audio.write(runConnection(chunk, config))
                }
            }
            val bytes = audio.toByteArray()
            if (bytes.isEmpty()) throw IOException(texts.noAudio())
            TtsResult(bytes, TtsAudioFormat.MP3)
        }

    /** 开一条连接合成一段文本，返回 MP3 字节。403 带服务端日期时抛 [ClockSkewRetry]。 */
    private suspend fun runConnection(ssmlText: String, config: TtsConfig): ByteArray {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val gec = EdgeTtsDrm.generateSecMsGec(nowMs() / 1000 + clockSkewSeconds)
        val request = Request.Builder()
            .url(
                "$endpoint?TrustedClientToken=${EdgeTtsDrm.TRUSTED_CLIENT_TOKEN}" +
                    "&ConnectionId=$connectionId" +
                    "&Sec-MS-GEC=$gec" +
                    "&Sec-MS-GEC-Version=${EdgeTtsDrm.SEC_MS_GEC_VERSION}"
            )
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("Accept-Encoding", "gzip, deflate, br, zstd")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", EdgeTtsDrm.USER_AGENT)
            .header("Cookie", "muid=${EdgeTtsDrm.generateMuid()};")
            .build()

        // 服务器 → 协程 的消息通道；onFailure/onClosed 也折算成事件入队，
        // 保证接收循环总能前进（不会既等不到消息又没人唤醒）。
        val inbox = Channel<InboxEvent>(Channel.UNLIMITED)
        var webSocket: WebSocket? = null

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // 握手完成即连发两条文本消息：speech.config → ssml
                ws.send(speechConfigMessage())
                ws.send(ssmlMessage(ssmlText, config))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                inbox.trySend(parseTextMessage(text))
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                inbox.trySend(parseBinaryMessage(bytes.toByteArray()))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                if (code == 403) {
                    val date = response?.headers?.getDate("Date")
                    if (date != null) {
                        // 按服务端时间校偏（edge-tts DRM.handle_client_response_error）
                        clockSkewSeconds = date.time / 1000 - nowMs() / 1000
                        inbox.trySend(InboxEvent(failure = ClockSkewRetry(texts.auth403())))
                        return
                    }
                }
                val hint = response?.let { " HTTP ${it.code}" } ?: ""
                inbox.trySend(InboxEvent(failure = IOException(texts.connectFailed(hint, t.message), t)))
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                inbox.trySend(InboxEvent(closed = true))
            }

            /**
             * ⚠ OkHttp 语义（RealWebSocket.onReadClose）：服务端先关闭时只回调
             * [onClosing]，[onClosed] 仅在客户端自己已 enqueue close 后才触发——
             * 只覆盖 onClosed 的话"无 turn.end 直接断连"会挂到看门狗。这里按
             * OkHttp 文档语义回 close 完成握手并立即折算成 closed 事件。
             */
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                inbox.trySend(InboxEvent(closed = true))
                ws.close(1000, null)
            }
        }

        var finished = false
        try {
            webSocket = wsClient.newWebSocket(request, listener)
            val out = java.io.ByteArrayOutputStream()
            var sawTurnEnd = false
            while (true) {
                val ev = receiveEvent(inbox)
                ev.failure?.let { throw it }
                ev.audio?.let { out.write(it) }
                if (ev.turnEnd) {
                    sawTurnEnd = true
                    break
                }
                if (ev.closed) {
                    throw IOException(texts.closedBeforeTurnEnd())
                }
            }
            finished = true
            if (out.size() == 0) {
                throw IOException(texts.noAudio())
            }
            return out.toByteArray()
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
        val audio: ByteArray? = null,
        val turnEnd: Boolean = false,
        val closed: Boolean = false,
        val failure: IOException? = null,
    )

    /** 403 校偏重试的内部信号（由 [synthesize] 捕获后重连一次）。 */
    private class ClockSkewRetry(message: String) : IOException(message)

    private suspend fun receiveEvent(inbox: Channel<InboxEvent>): InboxEvent {
        // 每条消息 30s 看门狗：服务器停摆时不无限占用 SpeechPipeline 的合成槽
        val ev = withTimeoutOrNull(MESSAGE_TIMEOUT_MS) { inbox.receive() }
        return ev ?: InboxEvent(failure = IOException(texts.messageTimeout(MESSAGE_TIMEOUT_MS)))
    }

    private fun speechConfigMessage(): String {
        val timestamp = dateString(nowMs())
        return "X-Timestamp:$timestamp\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
    }

    private fun ssmlMessage(escapedText: String, config: TtsConfig): String {
        val voice = config.voice.trim().ifBlank { DEFAULT_VOICE }
        val ratePct = Math.round((config.speed - 1f) * 100f)
        val rate = "%+d%%".format(ratePct)
        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'>" +
            "<prosody pitch='+0Hz' rate='$rate' volume='+0%'>" +
            escapedText +
            "</prosody></voice></speak>"
        val timestamp = dateString(nowMs())
        // 注意 X-Timestamp 末尾的 Z 是对齐 edge-tts 的（微软自家 bug，注释原文
        // "This is not a mistake, Microsoft Edge bug"），勿“修正”。
        return "X-RequestId:${UUID.randomUUID().toString().replace("-", "")}\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    internal fun parseTextMessage(text: String): InboxEvent {
        val sep = text.indexOf("\r\n\r\n")
        if (sep < 0) return InboxEvent(failure = IOException(texts.missingMessageHeader(text.take(120))))
        val headers = text.substring(0, sep)
            .split("\r\n")
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()
        return when (headers["Path"]) {
            "turn.end" -> InboxEvent(turnEnd = true)
            "turn.start", "response", "audio.metadata" -> InboxEvent()
            null -> InboxEvent(failure = IOException(texts.missingPathHeader(text.take(120))))
            else -> InboxEvent(failure = IOException(texts.unknownPath(headers["Path"])))
        }
    }

    internal fun parseBinaryMessage(bytes: ByteArray): InboxEvent {
        if (bytes.size < 2) {
            return InboxEvent(failure = IOException(texts.binaryFrameTooShort()))
        }
        val headerLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        if (headerLength > bytes.size) {
            return InboxEvent(failure = IOException(texts.headerLengthOutOfRange(headerLength, bytes.size)))
        }
        val headerText = String(bytes, 2, headerLength, Charsets.UTF_8)
        val headers = headerText.split("\r\n")
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }
            .toMap()
        if (headers["Path"] != "audio") {
            return InboxEvent(failure = IOException(texts.binaryNotAudio(headers["Path"])))
        }
        val payload = bytes.copyOfRange(2 + headerLength, bytes.size)
        val contentType = headers["Content-Type"]
        if (contentType == null) {
            // 结束前的空音频帧（无 Content-Type 且无数据）按 edge-tts 语义跳过
            if (payload.isEmpty()) return InboxEvent()
            return InboxEvent(failure = IOException(texts.binaryDataWithoutContentType()))
        }
        if (contentType != "audio/mpeg") {
            return InboxEvent(failure = IOException(texts.unexpectedAudioContentType(contentType)))
        }
        if (payload.isEmpty()) {
            return InboxEvent(failure = IOException(texts.audioFrameEmpty()))
        }
        return InboxEvent(audio = payload)
    }

    companion object {
        const val ENDPOINT =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"

        /** 端点唯一接受的输出格式（2026-10-05 实测，见类注释）。 */
        const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"

        /** 中文女声·晓晓（_demo 默认；SDK 侧 [TtsConfig.voice] 留空时兜底）。 */
        const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

        private const val MESSAGE_TIMEOUT_MS = 30_000L
        /** 服务端 SSML 文本上限（edge-tts split_text_by_byte_length 的切分长度）。 */
        internal const val MAX_TEXT_BYTES = 4096

        /**
         * JS 风格日期串（对齐 edge-tts date_to_string；GMT 名称不可本地化，
         * Locale.US 固定英文月份/星期，否则非英文设备上握手必挂）。
         */
        internal fun dateString(nowMs: Long): String {
            val fmt = java.text.SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                Locale.US,
            )
            fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return fmt.format(java.util.Date(nowMs))
        }
    }
}

/**
 * Sec-MS-GEC DRM token 与请求常量——逐行对齐 edge-tts 的 drm.py + constants.py
 * （2026-10-05 抓取的 master 源码），勿凭记忆"优化"。
 *
 * token 算法（drm.py generate_sec_ms_gec）：
 * 1. 当前 Unix 时间（+ 时钟偏移）
 * 2. 换到 Windows file time 纪元（+11644473600 秒，1601-01-01）
 * 3. 向下取整到 5 分钟（300 秒）
 * 4. ×10⁷（换 100 纳秒 tick）
 * 5. 拼接 TrustedClientToken 求 SHA-256，大写十六进制
 */
internal object EdgeTtsDrm {
    /** Edge 浏览器公开的 TrustedClientToken（constants.py，全网通用非机密）。 */
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** Windows file time 纪元偏移（秒），drm.py WIN_EPOCH。 */
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L

    /** 与 edge-tts 同步的 Chromium 版本（constants.py CHROMIUM_FULL_VERSION）；
     *  官方实现会随版本更新，403 长期出现时先回来对齐这里。 */
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    private const val TICKS_PER_SECOND_NS_DIV_100 = 10_000_000L // 1e9 / 100

    /** 生成 Sec-MS-GEC token；[unixSecondsWithSkew] = 当前 Unix 秒 + 时钟偏移。 */
    fun generateSecMsGec(unixSecondsWithSkew: Long): String {
        var ticks = unixSecondsWithSkew + WIN_EPOCH_SECONDS
        ticks -= ticks % 300
        ticks *= TICKS_PER_SECOND_NS_DIV_100
        val toHash = "$ticks$TRUSTED_CLIENT_TOKEN"
        val digest = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /** 随机 MUID（drm.py generate_muid：32 个大写十六进制字符）。 */
    fun generateMuid(): String = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
}

// ── 文本预处理（对齐 edge-tts communicate.py 的清洗/转义/切分三步）────────

/** 服务端不支持的字符区间（含垂直制表）替换为空格（communicate.py remove_incompatible_characters）。 */
internal fun removeIncompatibleCharacters(text: String): String = buildString(text.length) {
    for (c in text) {
        val code = c.code
        append(if ((code in 0..8) || (code in 11..12) || (code in 14..31)) ' ' else c)
    }
}

/** XML 转义（xml.sax.saxutils.escape：& < > 三个）。 */
internal fun escapeXml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * 按 UTF-8 字节数上限切分转义后的文本（communicate.py split_text_by_byte_length）：
 * 优先换行、其次空格，不得切断 UTF-8 多字节字符，不得切断 XML 实体（&amp; 等）。
 */
internal fun splitTextByByteLength(
    text: String,
    byteLength: Int,
    texts: EdgeTtsTexts = EdgeTtsTexts.ZH,
): List<String> {
    require(byteLength > 0) { "byteLength must be positive" }
    var rest = text.toByteArray(Charsets.UTF_8)
    val out = mutableListOf<String>()
    while (rest.size > byteLength) {
        var splitAt = lastNewlineOrSpaceWithinLimit(rest, byteLength)
        if (splitAt < 0) splitAt = safeUtf8SplitPoint(rest)
        splitAt = adjustSplitPointForXmlEntity(rest, splitAt)
        if (splitAt < 0) {
            throw IllegalArgumentException(texts.splitFailed(byteLength))
        }
        val advance = if (splitAt > 0) splitAt else 1
        rest.copyOfRange(0, splitAt).toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        rest = rest.copyOfRange(advance, rest.size)
    }
    rest.toString(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }?.let { out += it }
    return out
}

/** [0, limit) 内最靠后的换行（优先）或空格，找不到 -1。 */
private fun lastNewlineOrSpaceWithinLimit(bytes: ByteArray, limit: Int): Int {
    for (i in limit - 1 downTo 0) {
        if (bytes[i] == '\n'.code.toByte()) return i
    }
    for (i in limit - 1 downTo 0) {
        if (bytes[i] == ' '.code.toByte()) return i
    }
    return -1
}

/** 最靠后的完整 UTF-8 切点（严格解码器，残缺多字节序列回退）。 */
private fun safeUtf8SplitPoint(bytes: ByteArray): Int {
    var at = bytes.size
    while (at > 0) {
        try {
            Charsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes, 0, at))
            return at
        } catch (_: java.nio.charset.CharacterCodingException) {
            at--
        }
    }
    return at
}

/** 切点落在未闭合实体（如 &amp 中间）时回退到 & 处。 */
private fun adjustSplitPointForXmlEntity(bytes: ByteArray, splitAtIn: Int): Int {
    var at = splitAtIn
    while (at > 0) {
        var amp = -1
        for (i in at - 1 downTo 0) {
            if (bytes[i] == '&'.code.toByte()) { amp = i; break }
        }
        if (amp < 0) break
        var closed = false
        for (i in amp until at) {
            if (bytes[i] == ';'.code.toByte()) { closed = true; break }
        }
        if (closed) break
        at = amp
    }
    return at
}
