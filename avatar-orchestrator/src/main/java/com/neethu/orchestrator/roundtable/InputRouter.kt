package com.neethu.orchestrator.roundtable

/**
 * 舞台路由的槽位表一行（[InputRouter] 与 [RoundtableSession] 的公共输入）。
 *
 * @property id 舞台内唯一 id（"A"/"B"/…，与 corelib AvatarHandle.id 对齐）。
 * @property displayName 点名匹配用的人设名（人设卡 name）。
 * @property aliases 别名字表（昵称/称呼，§4.6「点名别名字表」）。
 * @property intro 一句人设简介（圆桌 P2 导演 LLM 档的花名册输入；可空）。
 */
data class StageSlotSpec(
    val id: String,
    val displayName: String,
    val aliases: List<String> = emptyList(),
    val intro: String = "",
) {
    /** 全部可点名的称呼（id 不区分大小写 + 名字 + 别名），按表内声明序。 */
    val names: List<String> =
        listOf(id, displayName) + aliases
}

/**
 * 一句话的路由决策（§4.6 听觉路由；圆桌话题模式下 [Target.explicit] 区分
 * 「用户真的点名了」与「默认路由兜底」——点名直授发言权，兜底交给导演）。
 */
sealed interface RoutingDecision {

    /**
     * 点名/默认命中一个槽位。[matchedBy] 命中的称呼（调试回显）；
     * [cleanedText] 是剥掉「@称呼」前缀后的文本——@token 是 UI 语义，模型
     * 听到只会困惑；自然语言称呼（"小柔你好"）保留原样（被叫名字很自然）。
     * [explicit] true = @token 或自然语言点名命中；false = lastAddressed
     * 默认兜底（无任何称呼出现）。
     */
    data class Target(
        val slotId: String,
        val matchedBy: String,
        val cleanedText: String,
        val explicit: Boolean,
    ) : RoutingDecision

    /** 舞台为空 / lastAddressed 已不在台上——无可路由对象。 */
    data object Unroutable : RoutingDecision
}

/**
 * 人 ↔ 虚拟人的听觉路由（docs/multi-avatar-feasibility.md §4.6 的 P1 子集）：
 *
 * 1. **点名优先**：文本含某槽位的称呼 → 路由给它。`@A`/`@小柔` 形态按
 *    @token 解析（可出现在文本任意位置）；自然语言称呼按 contains 匹配；
 *    多个槽位同时命中时取**文本中最先出现**的（"小柔和小刚各自怎样" 给
 *    小柔，至少是确定性的）。
 * 2. **默认路由**：无点名 → [InputRouter.route] 的 [lastAddressed]（最后一个
 *    与用户交互的虚拟人，UI 高亮提示当前对话对象）。
 *
 * 纯 JVM 可测；[RoundtableSession] 在路由命中后负责真正 send。
 */
class InputRouter(private val slots: List<StageSlotSpec>) {

    fun route(text: String, lastAddressed: String?): RoutingDecision {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || slots.isEmpty()) return RoutingDecision.Unroutable

        // 1a. @token 点名：@ 后连续非空白字符，大小写不敏感地匹配称呼表
        mentionTokens(trimmed).forEach { token ->
            val slot = slots.firstOrNull { spec ->
                spec.names.any { it.equals(token, ignoreCase = true) }
            }
            if (slot != null) {
                return RoutingDecision.Target(
                    slotId = slot.id,
                    matchedBy = "@$token",
                    cleanedText = stripMention(trimmed, token),
                    explicit = true,
                )
            }
        }

        // 1b. 自然语言点名：称呼在文本中最先出现的槽位赢
        var best: Triple<Int, StageSlotSpec, String>? = null // (offset, slot, name)
        for (spec in slots) {
            for (name in spec.names) {
                if (name.isEmpty()) continue
                val offset = trimmed.indexOf(name, ignoreCase = true)
                if (offset < 0) continue
                if (best == null || offset < best!!.first ||
                    (offset == best!!.first && name.length > best!!.third.length)
                ) {
                    best = Triple(offset, spec, name)
                }
            }
        }
        best?.let { (_, slot, _) ->
            return RoutingDecision.Target(slot.id, slot.displayName, trimmed, explicit = true)
        }

        // 2. 默认路由：lastAddressed（还在线的话）
        val last = lastAddressed?.let { id -> slots.firstOrNull { it.id == id } }
            ?: return RoutingDecision.Unroutable
        return RoutingDecision.Target(last.id, last.displayName, trimmed, explicit = false)
    }

    companion object {
        /** 提取文本里的全部 @token（按出现序）。 */
        internal fun mentionTokens(text: String): List<String> =
            Regex("@([^\\s@，。！？!?,.:：；;]+)").findAll(text).map { it.groupValues[1] }.toList()

        /**
         * 剥掉首个 @token（含它本身与紧随的空白/标点）：`@小柔 你好` → `你好`、
         * `@小柔：你好` → `你好`。中段/尾段的 @token 保留（剥了会破坏句法）。
         */
        internal fun stripMention(text: String, token: String): String {
            val idx = text.indexOf("@$token", ignoreCase = true)
            if (idx < 0) return text
            var end = idx + token.length + 1
            while (end < text.length && (text[end].isWhitespace() || text[end] in "：:，,。．.")) end++
            val head = text.take(idx)
            val tail = text.substring(end)
            return when {
                head.isBlank() -> tail.trim()
                tail.isBlank() -> head.trim()
                else -> "$head$tail".trim()
            }.ifEmpty { text }
        }
    }
}
