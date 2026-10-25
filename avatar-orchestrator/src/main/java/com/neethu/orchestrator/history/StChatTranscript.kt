package com.neethu.orchestrator.history

import com.neethu.aiadapter.emotion.InlineTagExtractor
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole

/**
 * SillyTavern 聊天记录读写（docs/tavern-companion-feasibility.md P1-4）：
 * ST 的聊天记录是逐行一个 JSON 对象的 jsonl（`{"name":…,"is_user":…,"mes":…}`，
 * 另有 send_date/swipes 等本端用不到的字段）。导出 = Room 历史 → jsonl/人读
 * markdown；导入 = jsonl → 清洗 → [ConversationStore] 历史上下文。
 *
 * 导入/导出双向都过 [sanitize]：行内标签协议（`<emo:…>/<act:…>` 等）是本端
 * 专属，旧标签回灌请求会被投影器/提取器再处理，ST 侧则显示为字面文本——
 * 存储边界上一律剥掉（正文语义不受影响）。
 */
object StChatTranscript {

    /** jsonl 的一行（仅本端关心的三字段）。 */
    data class Line(val name: String, val isUser: Boolean, val mes: String)

    /**
     * 闭合标签（`</emo:happy>` 形态）：提取器只识别开标签，闭标签会漏进
     * clean 文本（真机导出回环踩过）——清洗前先剥掉四个协议命名空间的
     * 闭标签（其余 `</...>` 形态是普通文本，保留）。
     */
    private val CLOSING_TAG = Regex(
        """</\s*(?:emo|act|cam|move)\s*(?::[^>]*)?>""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 行内标签/舞台指示清洗（复用 [InlineTagExtractor] 的流式提取逻辑）：
     * 标签段与（动作）括号剥离，剩余正文按流拼接。连续换行收敛为两换行。
     */
    fun sanitize(text: String): String {
        if (text.isBlank()) return ""
        val extractor = InlineTagExtractor()
        val clean = try {
            val prepared = CLOSING_TAG.replace(text, "")
            extractor.feed(prepared).cleanText + extractor.flush().cleanText
        } catch (_: Exception) {
            text
        }
        return clean.replace(THREE_PLUS_NEWLINES, "\n\n").trim()
    }

    private val THREE_PLUS_NEWLINES = Regex("\n{3,}")

    /** 导出 ST jsonl（逐行一个 JSON 对象；日期字段留空字符串，ST 可读）。 */
    fun toJsonl(charName: String, userName: String, messages: List<ChatMessage>): String =
        messages.joinToString("\n") { msg ->
            val name = if (msg.role == ChatRole.USER) userName else charName
            """{"name":${jsonStr(name)},"is_user":${msg.role == ChatRole.USER},""" +
                """"send_date":"","mes":${jsonStr(sanitize(msg.content))}}"""
        }

    /** 导出人读 markdown（对话体，说话人加粗）。 */
    fun toMarkdown(charName: String, userName: String, messages: List<ChatMessage>): String =
        messages.joinToString("\n\n") { msg ->
            val speaker = if (msg.role == ChatRole.USER) userName else charName
            "**$speaker**\n\n${sanitize(msg.content)}"
        }

    /**
     * 解析 ST jsonl 文本；坏行（空行/非 JSON/缺 mes）跳过。容忍对象数组
     * 形态（部分工具导出整段 JSON 数组而非逐行）。
     */
    fun parseJsonl(text: String): List<Line> {
        val lines = ArrayList<Line>()
        val objects = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("{")) objects += t
        }
        // 单对象/对象数组形态兜底（jsonl 老玩家一般不会有，但导入别挑食）
        if (objects.isEmpty() && text.trim().startsWith("[")) {
            runCatching {
                val arr = kotlinx.serialization.json.Json.parseToJsonElement(text)
                (arr as? kotlinx.serialization.json.JsonArray)?.forEach {
                    objects += it.toString()
                }
            }
        }
        for (obj in objects) {
            readLine(obj)?.let { lines += it }
        }
        return lines
    }

    private fun readLine(json: String): Line? = runCatching {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
            ?: return null
        val mes = (obj["mes"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        val isUser = (obj["is_user"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        val name = (obj["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: if (isUser) "User" else "Char"
        Line(name, isUser, mes)
    }.getOrNull()

    /** 行文本 → messages（清洗后），按 is_user 定角色；未知角色的行按 name 兜底。 */
    fun toMessages(lines: List<Line>): List<ChatMessage> = lines.mapNotNull { line ->
        val role = if (line.isUser) ChatRole.USER else ChatRole.ASSISTANT
        val cleaned = sanitize(line.mes)
        if (cleaned.isEmpty()) null else ChatMessage(role, cleaned)
    }

    private val ESCAPES = mapOf("\\" to "\\\\", "\"" to "\\\"", "\n" to "\\n", "\r" to "\\r", "\t" to "\\t")

    private fun jsonStr(raw: String): String {
        val sb = StringBuilder("\"")
        for (ch in raw) sb.append(ESCAPES[ch.toString()] ?: ch.toString())
        return sb.append('"').toString()
    }
}
