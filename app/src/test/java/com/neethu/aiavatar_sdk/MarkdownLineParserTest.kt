package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字幕气泡 markdown 解析（P2-1）的纯逻辑锁：样式 span 划分、词边界保护、
 * 转义、未闭合回退字面。标记本身是语法不进内容——span 文本拼合 = 剥掉
 * 标记后的正文（渲染端只加样式）。
 */
class MarkdownLineParserTest {

    private fun stylesOf(text: String): String =
        MarkdownLineParser.parse(text).joinToString("|") { span ->
            val flags = buildString {
                if (span.bold) append('B')
                if (span.italic) append('I')
                if (span.quote) append('Q')
                if (span.code) append('C')
            }
            "${if (flags.isEmpty()) "_" else flags}:${span.text}"
        }

    private fun joined(text: String): String =
        MarkdownLineParser.parse(text).joinToString("") { it.text }

    @Test
    fun `plain text is single span`() {
        assertEquals("_:你好", stylesOf("你好"))
    }

    @Test
    fun `bold and italic basic`() {
        assertEquals("B:重要|I:挥手", stylesOf("**重要***挥手*"))
    }

    @Test
    fun `underscore italic`() {
        assertEquals("I:action", stylesOf("_action_"))
    }

    @Test
    fun `spaced asterisks still italicize - device model habit`() {
        // 真机实测：模型常写 `* 她双手叉腰 *`（星号内侧带空格），照样斜体
        assertEquals("I:她双手叉腰，一脸不服气。", stylesOf("* 她双手叉腰，一脸不服气。*"))
        // 算术空白乘法不误伤（闭标记守卫拒收前空白）
        assertEquals("_:3 * 4 * 5", stylesOf("3 * 4 * 5"))
    }

    @Test
    fun `snake_case is not italic`() {
        assertEquals("_:hello_world_ok", stylesOf("hello_world_ok"))
    }

    @Test
    fun `nested italic inside bold composes`() {
        val spans = MarkdownLineParser.parse("**粗体 *斜体* 尾**")
        assertEquals(
            listOf(
                MarkdownLineParser.Span("粗体 ", bold = true),
                MarkdownLineParser.Span("斜体", bold = true, italic = true),
                MarkdownLineParser.Span(" 尾", bold = true),
            ),
            spans,
        )
    }

    @Test
    fun `ascii and cjk quotes ride quote style`() {
        assertEquals("Q:\"|Q:你好|Q:\"", stylesOf("\"你好\""))
        assertEquals("Q:「|Q:你好|Q:」", stylesOf("「你好」"))
        assertEquals("Q:「|IQ:加油|Q:」", stylesOf("「*加油*」"))
    }

    @Test
    fun `code span`() {
        assertEquals("C:json", stylesOf("`json`"))
    }

    @Test
    fun `unclosed markers fall back to literal`() {
        // 流式半个标记：先原样显示，闭合到达后自然成型
        assertEquals("_:*waves", stylesOf("*waves"))
        assertEquals("_:**粗体", stylesOf("**粗体"))
        assertEquals("_:「未闭合", stylesOf("「未闭合"))
        assertEquals("_:`code", stylesOf("`code"))
    }

    @Test
    fun `escapes produce literal chars`() {
        assertEquals("_:*_\"", stylesOf("\\*\\_\\\""))
    }

    @Test
    fun `joined text equals marker-stripped content`() {
        // 标记剥除语义：拼合 = 去掉语法标记（引号保留成对）
        assertEquals("waves 你好呀！", joined("*waves* 你好呀！"))
        assertEquals("重要提醒：json 与「对话」", joined("**重要**提醒：`json` 与「对话」"))
        assertEquals("345 与 snake_case_name", joined("3*4*5 与 snake_case_name"))
        assertEquals("「嵌套引号」结尾", joined("「*嵌套*引号」结尾"))
        // 字面回退/转义场景：转义消费反斜杠、未闭合按字面保留
        assertEquals("未闭合 *斜体 与 **粗体", joined("未闭合 *斜体 与 **粗体"))
        assertEquals("*转义*", joined("\\*转义*"))
    }

    @Test
    fun `multiline keeps newlines verbatim`() {
        val text = "第一行\n\n*第二段动作*"
        val spans = MarkdownLineParser.parse(text)
        assertTrue(spans.first().text.contains("\n\n"))
        assertFalse(spans.first().italic)
        assertTrue(spans.last().italic)
        assertEquals("第一行\n\n第二段动作", joined(text))
    }
}
