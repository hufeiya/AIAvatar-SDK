package com.neethu.aiadapter.text

/**
 * TTS 适配器用户可读错误文案的双语目录（多语言支持，2026-10）。
 *
 * 适配器是纯 JVM 模块、不依赖 corelib——语言由调用方注入文本目录（demo 在
 * AiChatController.ensure 按应用语言传 [ZH]/[EN]），缺省 [ZH] 保持既有行为
 * 与既有单测不变。接口无 SLA 的端点（Edge-TTS/火山）失败必须抛带上下文的
 * IOException，文案语言跟着应用走。
 *
 * 维护约定同 orchestrator 的 PromptTexts：中英按成员顺序对齐，改一处同步
 * 两语；`TtsTextsI18nTest` 断言 EN 版无中文字符。
 */
class EdgeTtsTexts private constructor(private val en: Boolean) {
    fun emptyText(): String = if (en) "EdgeTTS: synthesis text is empty" else "EdgeTTS：合成文本为空"
    fun noAudio(): String =
        if (en) "EdgeTTS: no audio received (voice/params may not be supported)"
        else "EdgeTTS：未收到任何音频（音色/参数可能不被支持）"
    fun auth403(): String =
        if (en) "EdgeTTS auth 403 (clock re-aligned to the server date)" else "EdgeTTS鉴权403（已按服务端时间校偏）"
    fun connectFailed(hint: String, message: String?): String =
        if (en) "EdgeTTS connect failed$hint: $message" else "EdgeTTS连接失败$hint: $message"
    fun closedBeforeTurnEnd(): String =
        if (en) "EdgeTTS connection closed before synthesis finished (no turn.end)"
        else "EdgeTTS连接在合成完成前被关闭（未收到 turn.end）"
    fun messageTimeout(ms: Long): String =
        if (en) "EdgeTTS timed out waiting for a server message (${ms}ms)"
        else "EdgeTTS等待服务器消息超时(${ms}ms)"
    fun missingMessageHeader(preview: String): String =
        if (en) "EdgeTTS response missing message header: $preview"
        else "EdgeTTS响应缺消息头: $preview"
    fun missingPathHeader(preview: String): String =
        if (en) "EdgeTTS response missing Path header: $preview"
        else "EdgeTTS响应缺 Path 头: $preview"
    fun unknownPath(path: String?): String =
        if (en) "EdgeTTS unknown response type: $path" else "EdgeTTS未知响应类型: $path"
    fun binaryFrameTooShort(): String =
        if (en) "EdgeTTS binary frame too short (missing header-length field)"
        else "EdgeTTS二进制帧过短（缺头长字段）"
    fun headerLengthOutOfRange(headerLength: Int, size: Int): String =
        if (en) "EdgeTTS binary header length out of range: $headerLength > $size"
        else "EdgeTTS二进制帧头长越界: $headerLength > $size"
    fun binaryNotAudio(path: String?): String =
        if (en) "EdgeTTS binary frame is not audio: $path" else "EdgeTTS二进制帧不是音频: $path"
    fun binaryDataWithoutContentType(): String =
        if (en) "EdgeTTS binary frame has data but no Content-Type"
        else "EdgeTTS二进制帧缺 Content-Type 但带数据"
    fun unexpectedAudioContentType(contentType: String): String =
        if (en) "EdgeTTS audio frame Content-Type mismatch: $contentType"
        else "EdgeTTS音频帧 Content-Type 异常: $contentType"
    fun audioFrameEmpty(): String =
        if (en) "EdgeTTS audio frame has no data" else "EdgeTTS音频帧无数据"
    fun splitFailed(byteLength: Int): String =
        if (en) "EdgeTTS: cannot split text at entity/UTF-8 boundary within $byteLength bytes"
        else "EdgeTTS：文本在实体/UTF-8 边界处无法按 $byteLength 字节切分"

    companion object {
        /** 简体中文（默认）。 */
        val ZH = EdgeTtsTexts(false)

        /** English。 */
        val EN = EdgeTtsTexts(true)
    }
}

/** 火山 seed-tts-2.0 适配器的用户可读错误文案（双语），约定同 [EdgeTtsTexts]。 */
class VolcanoTtsTexts private constructor(private val en: Boolean) {
    /** awaitFirstFrame 的步骤名（"建连"/"开会话"）。 */
    fun stepConnect(): String = if (en) "connect" else "建连"
    fun stepSessionStart(): String = if (en) "session start" else "开会话"

    fun connectFailed(hint: String, message: String?): String =
        if (en) "VolcanoTTS connect failed$hint: $message" else "火山TTS连接失败$hint: $message"
    fun sessionStartMissingSessionId(): String =
        if (en) "VolcanoTTS session-started response has no sessionId"
        else "火山TTS会话启动响应缺少 sessionId"
    fun closedBeforeSessionFinished(): String =
        if (en) "VolcanoTTS connection closed before synthesis finished (no SessionFinished)"
        else "火山TTS连接在合成完成前被关闭（未收到 SessionFinished）"
    fun emptyAudio(): String =
        if (en) "VolcanoTTS returned empty audio (voice/params may not be supported)"
        else "火山TTS返回了空音频（音色/参数可能不被支持）"
    fun stepFailed(label: String, expectEvent: Int, actualEvent: Int?, preview: String?): String =
        if (en) "VolcanoTTS $label failed: expected event $expectEvent got $actualEvent${preview?.let { " $it" } ?: ""}"
        else "火山TTS${label}失败：期望事件 $expectEvent 实得 $actualEvent${preview?.let { " $it" } ?: ""}"
    fun messageTimeout(ms: Long): String =
        if (en) "VolcanoTTS timed out waiting for a server message (${ms}ms)"
        else "火山TTS等待服务器消息超时(${ms}ms)"
    fun serverError(preview: String?): String =
        if (en) "VolcanoTTS server error: $preview" else "火山TTS服务端错误: $preview"

    companion object {
        /** 简体中文（默认）。 */
        val ZH = VolcanoTtsTexts(false)

        /** English。 */
        val EN = VolcanoTtsTexts(true)
    }
}
