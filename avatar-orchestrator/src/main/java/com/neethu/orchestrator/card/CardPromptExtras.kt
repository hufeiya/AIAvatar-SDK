package com.neethu.orchestrator.card

import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole

/**
 * 卡字段补全（docs/tavern-companion-feasibility.md P1-3）：
 * `mes_example` → few-shot 示例消息（插在 system 之后、历史之前——
 * SystemPromptAssembler 注释里预留的位置）；`post_history_instructions`
 * → 请求末条 user 消息前的指令块（ST 的 UJB 位置）。
 */
object CardPromptExtras {

    /** mes_example 单块字符预算（总预算）：示例是逐轮成本，超预算整块丢弃。 */
    private const val MAX_TOTAL_CHARS = 4_000

    /** `<START>` 块分隔（ST 惯例，容忍大小写与行内空白）。 */
    private val START = Regex("(?im)^\\s*<\\s*START\\s*>\\s*$")

    /**
     * 一条 "Name: text" 发言行。多行发言按行解析，未知说话人归属：
     * 角色名（大小写不敏感）→ assistant；user/{{user}}/你 → user；
     * 其余 → assistant（卡作者的变体拼写默认归角色）。
     */
    fun parse(
        card: CharacterCard,
        userAlias: String,
    ): List<ChatMessage> {
        val raw = card.messageExample
        if (raw.isBlank()) return emptyList()
        val charName = card.name
        val out = ArrayList<ChatMessage>()
        var used = 0
        for (block in START.split(raw)) {
            val messages = ArrayList<ChatMessage>()
            var speaker: ChatRole? = null
            val buf = StringBuilder()
            val flush: () -> Unit = {
                if (speaker != null && buf.isNotBlank()) {
                    messages += ChatMessage(speaker!!, expandCardMacros(buf.toString().trim(), charName, userAlias))
                }
                buf.setLength(0)
            }
            for (line in block.lines()) {
                if (line.isBlank()) continue
                val m = SPEAKER_LINE.matchEntire(line)
                if (m == null) {
                    // 无说话人前缀的行属于当前发言人（跨行发言的续行/旁白）
                    if (speaker != null) buf.append('\n').append(line.trim())
                    continue
                }
                val role = resolveRole(m.groupValues[1], charName, userAlias) ?: continue
                if (role != speaker) {
                    flush()
                    speaker = role
                }
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(m.groupValues[2].trim())
            }
            flush()
            if (messages.isEmpty()) continue
            val cost = messages.sumOf { it.content.length }
            if (used + cost > MAX_TOTAL_CHARS) break
            used += cost
            out += messages
        }
        return out
    }

    private val SPEAKER_LINE = Regex("""^\s*(\{\{[^}]{1,32}\}\}|[^:]{1,48}?)\s*:\s*(.*)$""")

    /** 说话人 → 角色；system 旁白行返回 null（整行丢弃，不进 few-shot）。 */
    private fun resolveRole(name: String, charName: String, userAlias: String): ChatRole? {
        val n = name.trim()
        if (n.equals(charName, ignoreCase = true)) return ChatRole.ASSISTANT
        val lowered = n.lowercase().trim('{', '}', ' ', '<', '>')
        if (lowered == "user" || lowered == "you" || n == userAlias) return ChatRole.USER
        if (lowered == "system" || lowered == "note") return null
        // 未知说话人默认归角色（卡作者的角色名变体拼写占绝大多数）
        return ChatRole.ASSISTANT
    }

    /**
     * `post_history_instructions` 宏展开（{{char}}/{{user}}）；空白返回 null。
     * 注入位置：请求末条 user 消息前缀槽的末段（近因效应最强的指令位），
     * 单条 system 约束下等效 ST 的 UJB。
     */
    fun postHistoryInstructions(card: CharacterCard, userAlias: String): String? =
        card.postHistoryInstructions
            .takeIf { it.isNotBlank() }
            ?.let { expandCardMacros(it, card.name, userAlias).trim() }
            ?.takeIf { it.isNotEmpty() }
}
