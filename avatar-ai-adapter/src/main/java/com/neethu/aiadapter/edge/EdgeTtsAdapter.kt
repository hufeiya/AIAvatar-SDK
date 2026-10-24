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
 * 协议逐字对齐开源实现 edge-tts（python，rany2/edge-tts）——特别是 `Sec-MS-GEC`
 * 鉴权参数（SHA-256 DRM token，见 [EdgeTtsDrm]，**勿凭记忆改算法，对齐 drm.py**）：
 * - 握手 URL：`{endpoint}?TrustedClientToken=…&ConnectionId=…&Sec-MS-GEC=…&Sec-MS-GEC-Version=…`
 *   （**v2 token**：2026-10 微软强制升级，v1 纯 SHA-256 一律 403——格式
 *   `<16hex CS64>-<64hex SHA256>`，5 分钟一档：Windows file time 向下取整到 300s
 *   ×10⁷ 后拼 TrustedClientToken 过自定义 SHA-256+CS64 双摘要；上游 2026-10-13
 *   `ba61d944` 落地 v2；设备时钟偏移时按服务端 Date 头校偏并重试一次）
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
 * （2026-10-24 抓取的 master 源码，v2 token，上游提交 ba61d944），勿凭记忆"优化"。
 *
 * 历史：v1（2024-11 起）= ticks+token 过**标准 SHA-256** 取大写 hex；2026-10 微软
 * 服务端强制升级 v2 并全面拒绝 v1（一律 403，PyPI edge-tts 7.2.8 亦中招），上游
 * 同月适配。v2 = 自定义 SHA-256 变体（前 7 字节过自定义调度表揉出初始状态）+ 自定义
 * CS64 校验和，token 格式 `<16hex CS64>-<64hex SHA256>`。
 *
 * token 时间档位与 v1 相同（drm.py generate_sec_ms_gec_v2）：
 * 1. 当前 Unix 时间（+ 时钟偏移）
 * 2. 换到 Windows file time 纪元（+11644473600 秒，1601-01-01）
 * 3. 向下取整到 5 分钟（300 秒）
 * 4. ×10⁷（换 100 纳秒 tick）
 * 5. 拼接 TrustedClientToken 过 [sha256Digest]+[cs64Digest] 双摘要
 */
internal object EdgeTtsDrm {
    /** Edge 浏览器公开的 TrustedClientToken（constants.py，全网通用非机密）。 */
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** Windows file time 纪元偏移（秒），drm.py WIN_EPOCH。 */
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L

    /** 与 edge-tts 同步的 Chromium 版本（constants.py CHROMIUM_FULL_VERSION）；
     *  官方实现会随版本更新，403 长期出现时先回来对齐这里。 */
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"

    /** v2 起版本串前缀为 2-（constants.py SEC_MS_GEC_VERSION）。 */
    const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"

    private const val TICKS_PER_SECOND = 10_000_000L

    /** 生成 v2 Sec-MS-GEC token；[unixSecondsWithSkew] = 当前 Unix 秒 + 时钟偏移。 */
    fun generateSecMsGec(unixSecondsWithSkew: Long): String {
        var ticks = unixSecondsWithSkew + WIN_EPOCH_SECONDS
        ticks -= ticks % 300
        ticks *= TICKS_PER_SECOND
        val toHash = "$ticks$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(toHash)
        val (cs64Lo, cs64Hi) = cs64Digest(toHash, digest)
        val cs64 = hexWordLe(cs64Lo) + hexWordLe(cs64Hi)
        val sha = digest.joinToString("") { "%02X".format(it) }
        return "$cs64-$sha"
    }

    /** 随机 MUID（drm.py generate_muid：32 个大写十六进制字符）。 */
    fun generateMuid(): String = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)

    // ── 自定义 SHA-256 变体（drm.py _sha256_digest/_sha256_init/_sha256_rounds）──
    // 前 7 字节过自定义调度表算出块压缩的初始状态（见 [sha256Init]），之后每个 64
    // 字节块的轮函数是标准 FIPS 180-4；但填充的长度字段按 57+len(data) 计（非标准）。

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val zeroCount = ((55 - length) % 64 + 64) % 64 // Python % 语义：结果非负
        val padded = ByteArray(data.size - 7 + 1 + zeroCount + 8)
        data.copyInto(padded, 0, 7)
        padded[data.size - 7] = 0x80.toByte()
        val bits = length.toLong() * 8
        for (i in 0 until 8) padded[padded.size - 8 + i] = (bits ushr ((7 - i) * 8)).toByte()

        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = IntArray(64)
            for (j in 0 until 16) {
                val at = offset + j * 4
                words[j] = ((padded[at].toInt() and 0xFF) shl 24) or
                    ((padded[at + 1].toInt() and 0xFF) shl 16) or
                    ((padded[at + 2].toInt() and 0xFF) shl 8) or
                    (padded[at + 3].toInt() and 0xFF)
            }
            expandSha256Schedule(words, 16)
            val working = sha256Rounds(state, words, firstRound = 0)
            // 前馈加法（drm.py：state = (x + y) & mask for zip(state, working)）
            state = IntArray(8) { state[it] + working[it] }
            offset += 64
        }
        return ByteArray(32) { i -> (state[i / 4] ushr (24 - (i % 4) * 8)).toByte() }
    }

    /** drm.py _sha256_init：用前 7 字节 + 自定义调度表算出块压缩的自定义初始状态。 */
    private fun sha256Init(seven: ByteArray): IntArray {
        val words = IntArray(64)
        words[14] = ((seven[0].toInt() and 0xFF) shl 16) or
            ((seven[1].toInt() and 0xFF) shl 8) or
            (seven[2].toInt() and 0xFF)
        words[15] = ((seven[3].toInt() and 0xFF) shl 24) or
            ((seven[4].toInt() and 0xFF) shl 16) or
            ((seven[5].toInt() and 0xFF) shl 8) or
            (seven[6].toInt() and 0xFF)
        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                value += if (index == 16) sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT
                else sigma1(words[index - 2])
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                value += if (index == 29) sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT
                else sigma0(words[index - 15])
            }
            words[index] = value
        }
        words[30] = sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
            SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
        expandSha256Schedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words, firstRound = 14)
        return IntArray(8) { SHA256_INITIAL[it] + working[it] }
    }

    /** 标准 FIPS 180-4 轮函数；firstRound=14 时轮常量 K[14] 换成 [SHA256_ADJUSTMENT]。 */
    private fun sha256Rounds(state: IntArray, words: IntArray, firstRound: Int): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (index in firstRound until 64) {
            val k = if (index == 14 && firstRound == 14) SHA256_ADJUSTMENT else SHA256_ROUND_CONSTANTS[index]
            val t1 = h + (e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)) +
                (g xor (e and (f xor g))) + k + words[index]
            val t2 = (a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)) +
                ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    /** 标准 SHA-256 消息扩展（drm.py _expand_sha256_schedule）。 */
    private fun expandSha256Schedule(words: IntArray, firstWord: Int) {
        for (index in firstWord until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            words[index] = sigma1(x) + words[index - 7] + sigma0(y) + words[index - 16]
        }
    }

    /**
     * SHA-256 小 σ 函数——⚠ drm.py 的实现**不是**标准 ROTR 组合：左移项是 Python
     * 无界大整数直接参与异或、最后才截断 32 位（DRM 故意混淆），必须用 Long 复刻
     * "无界左移→异或→截断"语义，rotateRight 组合会得出不同值。
     */
    private fun sigma0(value: Int): Int {
        val v = value.toLong() and 0xFFFFFFFFL
        return ((v shr 7) xor (v shl 25) xor (v shr 18) xor (v shl 14) xor (v shr 3)).toInt()
    }

    private fun sigma1(value: Int): Int {
        val v = value.toLong() and 0xFFFFFFFFL
        return ((v shr 17) xor (v shl 15) xor (v shr 19) xor (v shl 13) xor (v shr 10)).toInt()
    }

    // ── CS64 校验和（drm.py _cs64_digest/_rol16/_words_le4_even）──────────────

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Int, Int> {
        val keyA = leIntAt(digest, 0) or 1
        val keyB = leIntAt(digest, 4) or 1
        val factorA = keyA * CS64_FACTOR
        val factorB = keyB * CS64_FACTOR

        val baseTerms = IntArray(CS64_COEFFICIENTS.size / 2)
        val baseOffsets = IntArray(CS64_COEFFICIENTS.size / 2)
        for (i in baseTerms.indices) {
            baseTerms[i] = CS64_COEFFICIENTS[2 * i] * (if (i % 2 == 0) factorA else factorB)
            baseOffsets[i] = CS64_COEFFICIENTS[2 * i + 1]
        }

        // b"\x00" + data → 小端 u32 词表，词数补齐到偶数（_words_le4_even）
        val prefixed = ByteArray(data.size + 1)
        data.copyInto(prefixed, 1)
        val wordCount = (prefixed.size + 3) / 4
        val evenWords = (wordCount + 1) / 2 * 2
        val terms = baseTerms + IntArray(evenWords)
        val offsets = baseOffsets + IntArray(evenWords)
        for (index in 0 until evenWords) {
            var word = 0
            for (j in 0 until 4) {
                val at = index * 4 + j
                if (at < prefixed.size) word = word or ((prefixed[at].toInt() and 0xFF) shl (8 * j))
            }
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rotl16(word) *
                (if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factorA
                offset += CS64_ADJUSTMENT1
            }
            terms[baseTerms.size + index] = term
            offsets[baseOffsets.size + index] = offset
        }

        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rotl16(offsets[index] + x)
            var q = t * 0x76F50F34.toInt()
            swapAcc += rotl16(q) * 0xC3EFEA97.toInt() + q
            t = rotl16(offsets[index + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rotl16(q) * 0x35BD1EC9.toInt() + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rotl16(state) * 0xCF98B111.toInt()
            state = rotl16(state) * 0x87085B9F.toInt()
            state = rotl16(state) * 0x12CEB96D
            state = rotl16(state) * 0x257E1D83
            acc += state
            t = state * keyB + y
            t = rotl16(t) * 0xA27416F5.toInt()
            t = rotl16(t) * 0xD38396FF.toInt()
            state = rotl16(rotl16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state
            index += 2
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }

    /** 32 位左旋 16 位（= 高低半字交换，drm.py _rol16）。 */
    private fun rotl16(value: Int): Int = (value shl 16) or (value ushr 16)

    /** 小端读 u32（struct.unpack("<I")）。 */
    private fun leIntAt(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /** u32 的小端十六进制（struct.pack("<I").hex().upper()）。 */
    private fun hexWordLe(word: Int): String = "%02X%02X%02X%02X".format(
        word and 0xFF,
        (word shr 8) and 0xFF,
        (word shr 16) and 0xFF,
        (word shr 24) and 0xFF,
    )

    // ── 常量表（drm.py 逐值抄录；≥0x80000000 的字面量需显式 .toInt() 保位模式）──

    private val SHA256_INITIAL = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(),
        0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
    )
    private val SHA256_STATE = intArrayOf(
        0xFD9FBEA2.toInt(), 0x91243CE7.toInt(), 0xBE499094.toInt(), 0xCD2E1E0A.toInt(),
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4.toInt(), 0x03EFFBD2,
    )
    private val SHA256_SCHEDULE = intArrayOf(
        0xEB24BF9E.toInt(), 0x1558691F, 0xA0C09EE2.toInt(), 0x0CFA32BA,
        0xA622EE8C.toInt(), 0xD1DD9491.toInt(), 0xC0DD25A0.toInt(), 0xF9E70AB6.toInt(),
        0xF0C31573.toInt(), 0x7058CE4E, 0x7188E606, 0x00E5AB33,
        0xD258B060.toInt(), 0x72766564,
    )
    private val SHA256_ROUND_CONSTANTS = intArrayOf(
        0x428A2F98.toInt(), 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3,
        0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3, 0x748F82EE,
        0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(), 0x90BEFFFA.toInt(),
        0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0

    /** (coefficient, offset) 扁平交替存放，drm.py _CS64_COEFFICIENTS。 */
    private val CS64_COEFFICIENTS = intArrayOf(
        0xE82E91B1.toInt(), 0xB32700D0.toInt(),
        0x44DE24C6.toInt(), 0x88DFF51D.toInt(),
        0x3BEB65BC, 0x29514038,
        0xC89C5933.toInt(), 0xB328CBDF.toInt(),
        0x8D1EC211.toInt(), 0xD405B4D5.toInt(),
        0x314EF5D5, 0x26E55F3C,
        0xE92491B1.toInt(), 0x607801C6,
        0xF0A5B9D5.toInt(), 0x82B2C1FB.toInt(),
        0x06064CF3, 0xFA51A3DA.toInt(),
        0xDA79FA60.toInt(), 0x207CC464,
        0xBED20560.toInt(), 0x3C3C74F3,
        0x89F9C9F8.toInt(), 0xD2D14B61.toInt(),
        0x35FA38C6, 0xBB04A2C1.toInt(),
        0xCB49E3C6.toInt(), 0xC5626167.toInt(),
    )
    private const val CS64_FACTOR = 0x82893933.toInt()
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000
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
