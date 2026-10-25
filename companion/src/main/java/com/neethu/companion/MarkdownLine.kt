package com.neethu.companion

/**
 * 字幕气泡的 markdown 行内解析（酒馆 P2-1，docs/tavern-companion-feasibility.md
 * §5 P2-1）：ST 圈的回复惯例是 `**粗体**`、`*斜体*` 与 `_斜体_`（动作描写）、
 * `"…"/「…」引号对话、反引号代码。纯函数产出无平台类型的 span 列表，JVM
 * 可测；Compose 渲染端（AiChatBar）把 span 翻译成 AnnotatedString。
 *
 * 有意不做的：标题/列表/链接/图片（聊天气泡里不合适）、HTML（合规面）、
 * 跨行结构（气泡行是流式逐句追加的，只保行内语义）。
 *
 * 语义约定：
 * - 未闭合的标记按字面输出（流式场景半个 `*动作` 先原样显示，闭合到达后
 *   下一帧自然变成斜体）；
 * - `_` 强调带词边界保护（`snake_case` 不误伤），`*` 无此约束（对齐
 *   CommonMark 的 `3*4*5` 行为）；
 * - `\` 可转义 `*_`"` 与反引号本身；
 * - span 文本拼回原串恒等（渲染端只加样式不改文本）。
 */
object MarkdownLineParser {

    /** 一段同样式文本；[text] 原样拼合等于输入串。 */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val quote: Boolean = false,
        val code: Boolean = false,
    )

    /** 解析一行（可含 `\n`，按普通文本保留，渲染端自行换行）。 */
    fun parse(text: String): List<Span> {
        val spans = ArrayList<Span>()
        val buf = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            // 转义：\* \_ \_ \" \` 输出字面字符
            if (c == '\\' && i + 1 < text.length && text[i + 1] in "*_`\"「") {
                buf.append(text[i + 1])
                i += 2
                continue
            }
            if (text.startsWith("**", i)) {
                val end = findSequence(text, "**", i + 2)
                if (end > 0) {
                    flush(buf, spans)
                    emit(text.substring(i + 2, end), spans) { it.copy(bold = true) }
                    i = end + 2
                    continue
                }
                buf.append("**")
                i += 2
                continue
            }
            when (c) {
                '*' -> {
                    // 斜体：开标记宽松（真机模型爱写 `* 动作 *`，星号内侧带空格——
                    // 闭标记的「前字符非空白」守卫足以挡住 `3 * 4 * 5` 之类误伤）
                    if (i + 1 < text.length) {
                        val end = findClosing(text, '*', i + 1)
                        if (end > 0) {
                            flush(buf, spans)
                            // 内侧空白是标记的习惯性衬垫，不进正文
                            emit(text.substring(i + 1, end).trim(), spans) { it.copy(italic = true) }
                            i = end + 1
                            continue
                        }
                    }
                    buf.append(c)
                    i++
                }
                '_' -> {
                    // 词边界保护：_ 开标记不得紧跟在字母数字后（snake_case 不误伤）；
                    // 闭合侧的边界由 findClosingUnderscore 把关
                    val prevWord = i > 0 && text[i - 1].isLetterOrDigit()
                    if (!prevWord && i + 1 < text.length && !text[i + 1].isWhitespace()) {
                        val end = findClosingUnderscore(text, i + 1)
                        if (end > 0) {
                            flush(buf, spans)
                            emit(text.substring(i + 1, end), spans) { it.copy(italic = true) }
                            i = end + 1
                            continue
                        }
                    }
                    buf.append(c)
                    i++
                }
                '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush(buf, spans)
                        spans += Span(text.substring(i + 1, end), code = true)
                        i = end + 1
                        continue
                    }
                    buf.append(c)
                    i++
                }
                '"', '「', '『' -> {
                    val close = when (c) {
                        '"' -> '"'
                        '「' -> '」'
                        else -> '』'
                    }
                    val end = text.indexOf(close, i + 1)
                    if (end > i) {
                        flush(buf, spans)
                        // 引号对整体（含两个引号）挂 quote 样式；内部再嵌套解析
                        spans += Span(c.toString(), quote = true)
                        for (inner in parse(text.substring(i + 1, end))) {
                            spans += inner.copy(quote = true)
                        }
                        spans += Span(close.toString(), quote = true)
                        i = end + 1
                        continue
                    }
                    buf.append(c)
                    i++
                }
                else -> {
                    buf.append(c)
                    i++
                }
            }
        }
        flush(buf, spans)
        return spans
    }

    /** 把 [text] 递归解析后给每个 span 叠加 [style]（bold/italic 组合语义）。 */
    private inline fun emit(
        text: String,
        spans: MutableList<Span>,
        style: (Span) -> Span,
    ) {
        for (inner in parse(text)) spans += style(inner)
    }

    private fun flush(buf: StringBuilder, spans: MutableList<Span>) {
        if (buf.isNotEmpty()) {
            spans += Span(buf.toString())
            buf.setLength(0)
        }
    }

    /** 找 [seq] 的首次出现（从 [from] 起）；[seq] 前紧邻空白不算闭合（`* waves *`）。 */
    private fun findSequence(text: String, seq: String, from: Int): Int {
        var idx = from
        while (true) {
            idx = text.indexOf(seq, idx)
            if (idx < 0) return -1
            if (idx > from && !text[idx - 1].isWhitespace()) return idx
            idx += seq.length
        }
    }

    /**
     * 找单个闭合 `*`：要求前一字符非空白（`waves *` 的孤立尾星不闭合），
     * 也不得是另一个 `*`（`*斜体 与 **粗体` 的未闭合 `**` 不能被斜体吞掉）。
     */
    private fun findClosing(text: String, close: Char, from: Int): Int {
        var idx = from
        while (true) {
            idx = text.indexOf(close, idx)
            if (idx < 0) return -1
            val prev = text[idx - 1]
            if (!prev.isWhitespace() && prev != close) return idx
            idx++
        }
    }

    /** `_` 的闭合：同词边界保护（后一字符不得是字母数字）。 */
    private fun findClosingUnderscore(text: String, from: Int): Int {
        var idx = from
        while (true) {
            idx = text.indexOf('_', idx)
            if (idx < 0) return -1
            val nextIsWord = idx + 1 < text.length && text[idx + 1].isLetterOrDigit()
            if (!text[idx - 1].isWhitespace() && !nextIsWord) return idx
            idx++
        }
    }
}
