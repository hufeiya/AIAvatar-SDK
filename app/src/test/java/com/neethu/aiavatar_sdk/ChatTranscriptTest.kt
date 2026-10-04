package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话字幕面板纯逻辑：用户消息独立成行、虚拟人流式逐句并入、容量裁剪。
 * 对应 MainActivity.AiChatBar 的聊天记录面板（用户文字在上、可滚动、自动贴底）。
 */
class ChatTranscriptTest {

    private val user = ChatLine(ChatRole.USER, "你好")
    private val bot = ChatLine(ChatRole.AVATAR, "你好呀")

    @Test
    fun `avatar streaming merges into last avatar line`() {
        // 同一回合逐句开播：SentenceStarted 的句文本并入最后一条 AVATAR 行
        val lines = ChatTranscript.append(emptyList(), ChatRole.AVATAR, "今天天气")
        val merged = ChatTranscript.append(lines, ChatRole.AVATAR, "不错哦")
        assertEquals(1, merged.size)
        assertEquals("今天天气不错哦", merged[0].text)
    }

    @Test
    fun `user message always starts a new line even after user line`() {
        // 连续两次用户发言（自由说话连发）是两次独立发言，不能拼成一条
        val lines = ChatTranscript.append(emptyList(), ChatRole.USER, "第一句")
        val merged = ChatTranscript.append(lines, ChatRole.USER, "第二句")
        assertEquals(2, merged.size)
        assertEquals("第一句", merged[0].text)
        assertEquals("第二句", merged[1].text)
    }

    @Test
    fun `user line does not merge into streaming avatar line`() {
        val lines = ChatTranscript.append(listOf(bot), ChatRole.USER, "问题")
        assertEquals(2, lines.size)
        assertEquals(ChatRole.USER, lines[1].role)
        // 之后虚拟人回复从新行起播
        val replied = ChatTranscript.append(lines, ChatRole.AVATAR, "回答")
        assertEquals(3, replied.size)
        assertEquals("回答", replied[2].text)
    }

    @Test
    fun `greeting lands as first avatar line`() {
        // 开场白 speak() 不经用户消息，直接成为第一条 AVATAR 行
        val lines = ChatTranscript.append(emptyList(), ChatRole.AVATAR, "嗨，我是阿枣")
        assertEquals(listOf(ChatLine(ChatRole.AVATAR, "嗨，我是阿枣")), lines)
    }

    @Test
    fun `empty text is a no-op returning same instance`() {
        assertSame(user, ChatTranscript.append(listOf(user), ChatRole.AVATAR, "").let { it[0] })
        assertTrue(ChatTranscript.append(emptyList(), ChatRole.USER, "").isEmpty())
    }

    @Test
    fun `cap keeps only the newest MAX_LINES`() {
        val overflow = (0 until ChatTranscript.MAX_LINES + 5).map {
            ChatLine(ChatRole.USER, "msg$it")
        }
        val capped = ChatTranscript.cap(overflow)
        assertEquals(ChatTranscript.MAX_LINES, capped.size)
        assertEquals("msg5", capped.first().text)
        assertEquals("msg${ChatTranscript.MAX_LINES + 4}", capped.last().text)
    }

    @Test
    fun `cap keeps lists at or under the limit untouched`() {
        val under = (0 until ChatTranscript.MAX_LINES).map { ChatLine(ChatRole.USER, "m$it") }
        assertEquals(under, ChatTranscript.cap(under))
    }
}
