package com.neethu.orchestrator.history

import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST 聊天记录读写（酒馆 P1-4）：jsonl 导出可回灌、jsonl/数组双形态导入、
 * 行内标签双向清洗（不污染新请求、ST 侧不显示字面标签）。
 */
class StChatTranscriptTest {

    private val history = listOf(
        ChatMessage(ChatRole.USER, "你好，介绍一下自己"),
        ChatMessage(ChatRole.ASSISTANT, "<emo:happy>你好！我是艾莉娅。（微笑着挥手）"),
    )

    @Test
    fun `jsonl export is line-per-object and parseable back`() {
        val jsonl = StChatTranscript.toJsonl("艾莉娅", "User", history)
        val lines = jsonl.trim().split("\n")
        assertEquals(2, lines.size)
        val first = lines[0]
        assertTrue(first.contains("\"name\":\"User\""))
        assertTrue(first.contains("\"is_user\":true"))
        val second = lines[1]
        assertTrue(second.contains("\"name\":\"艾莉娅\""))
        assertTrue(second.contains("\"is_user\":false"))
        // 回灌可读
        val parsed = StChatTranscript.parseJsonl(jsonl)
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].isUser)
        assertEquals("艾莉娅", parsed[1].name)
    }

    @Test
    fun `export sanitizes inline tags`() {
        val jsonl = StChatTranscript.toJsonl("C", "User", history)
        assertFalse(jsonl.contains("<emo:happy>"))
        assertTrue(jsonl.contains("你好！我是艾莉娅。（微笑着挥手）"))
    }

    @Test
    fun `newlines escaped in jsonl`() {
        val msgs = listOf(ChatMessage(ChatRole.USER, "第一行\n第二行"))
        val jsonl = StChatTranscript.toJsonl("C", "User", msgs)
        val line = jsonl.trim()
        assertFalse(line.split("\n").size > 1) // 必须单行
        val back = StChatTranscript.parseJsonl(line)
        assertEquals("第一行\n第二行", back.single().mes)
    }

    @Test
    fun `import strips tags and maps roles`() {
        val jsonl = """
            {"name":"C","is_user":false,"mes":"<act:wave>欢迎回来！"}
            {"name":"User","is_user":true,"mes":"我回来了"}
        """.trimIndent()
        val msgs = StChatTranscript.toMessages(StChatTranscript.parseJsonl(jsonl))
        assertEquals(2, msgs.size)
        // jsonl 行序保持：首行是角色发言
        assertEquals(ChatRole.ASSISTANT, msgs[0].role)
        assertEquals("欢迎回来！", msgs[0].content)
        assertEquals(ChatRole.USER, msgs[1].role)
        assertEquals("我回来了", msgs[1].content)
    }

    @Test
    fun `tolerates blank lines and object-array form`() {
        val jsonl = "\n{\"name\":\"C\",\"is_user\":false,\"mes\":\"hi\"}\n\nnot json\n"
        assertEquals(1, StChatTranscript.parseJsonl(jsonl).size)
        val arr = """[{"name":"C","is_user":false,"mes":"a"},{"name":"U","is_user":true,"mes":"b"}]"""
        assertEquals(2, StChatTranscript.parseJsonl(arr).size)
    }

    @Test
    fun `markdown export is human-readable`() {
        val md = StChatTranscript.toMarkdown("艾莉娅", "User", history)
        assertTrue(md.contains("**User**"))
        assertTrue(md.contains("**艾莉娅**"))
        assertFalse(md.contains("<emo:"))
    }

    @Test
    fun `sanitize collapses blank runs and trims`() {
        assertEquals("a\n\nb", StChatTranscript.sanitize("  a\n\n\n\nb  "))
    }

    @Test
    fun `sanitize strips closing tags the extractor misses`() {
        // 真机导出回环踩过：开标签被剥、闭标签漏进正文；
        // （微笑）类括号舞台指示同被滤除（提取器的滤而不显语义）
        assertEquals("你好！我是艾莉娅。", StChatTranscript.sanitize("<emo:happy>你好！我是艾莉娅。（微笑）</emo:happy>"))
        assertEquals("这行有标签", StChatTranscript.sanitize("<emo:joy>这行有标签</emo:joy>"))
        // 非协议命名空间的闭合形态是普通文本，保留
        assertEquals("</div>", StChatTranscript.sanitize("</div>"))
    }
}
