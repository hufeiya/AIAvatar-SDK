package com.neethu.aiadapter.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 错误文案双语目录的不变量（多语言支持）：EN 目录全部成员不得含中文字符
 * （错误条直接展示 IOException.message）；ZH 目录保留既有文案锚点（适配器单测
 * 与真机排障依赖）。
 */
class TtsTextsI18nTest {

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    @Test
    fun `en edge texts have no chinese`() {
        val t = EdgeTtsTexts.EN
        val samples = listOf(
            t.emptyText(), t.noAudio(), t.auth403(),
            t.connectFailed(" HTTP 403", "refused"),
            t.closedBeforeTurnEnd(), t.messageTimeout(30_000L),
            t.missingMessageHeader("xx"), t.missingPathHeader("xx"),
            t.unknownPath("nope"), t.binaryFrameTooShort(),
            t.headerLengthOutOfRange(9, 4), t.binaryNotAudio("Path:meta"),
            t.binaryDataWithoutContentType(), t.unexpectedAudioContentType("a/b"),
            t.audioFrameEmpty(), t.splitFailed(4096),
        )
        for (m in samples) assertFalse("含中文: $m", cjk.containsMatchIn(m))
    }

    @Test
    fun `en volcano texts have no chinese`() {
        val t = VolcanoTtsTexts.EN
        val samples = listOf(
            t.stepConnect(), t.stepSessionStart(),
            t.connectFailed("", "x"), t.sessionStartMissingSessionId(),
            t.closedBeforeSessionFinished(), t.emptyAudio(),
            t.stepFailed("connect", 1, 2, "preview"), t.messageTimeout(30_000L),
            t.serverError("boom"),
        )
        for (m in samples) assertFalse("含中文: $m", cjk.containsMatchIn(m))
    }

    @Test
    fun `zh anchors keep legacy wording`() {
        val e = EdgeTtsTexts.ZH
        assertEquals("EdgeTTS：合成文本为空", e.emptyText())
        assertEquals("EdgeTTS鉴权403（已按服务端时间校偏）", e.auth403())
        assertEquals("EdgeTTS连接失败 HTTP 500: down", e.connectFailed(" HTTP 500", "down"))
        val v = VolcanoTtsTexts.ZH
        assertEquals("建连", v.stepConnect())
        assertEquals("火山TTS服务端错误: quota", v.serverError("quota"))
        assertEquals("火山TTS建连失败：期望事件 1 实得 2 preview", v.stepFailed("建连", 1, 2, "preview"))
    }
}
