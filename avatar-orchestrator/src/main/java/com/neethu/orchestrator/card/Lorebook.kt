package com.neethu.orchestrator.card

import java.text.Normalizer

/**
 * 世界书/lorebook（SillyTavern World Info，docs/tavern-companion-feasibility.md
 * P1-1）：关键词触发的设定注入条目集合，长篇 RP 的设定来源。数据模型同时
 * 兼容两种来源（[LorebookParser] 统一归一化）：
 * - 角色卡内嵌 `data.character_book`（CCv2/v3 规范字段，entries 为数组）；
 * - 独立世界书 JSON（ST World Info 导出格式，entries 为 `{id: {...}}` 映射）。
 */
data class Lorebook(
    val name: String = "",
    val entries: List<LorebookEntry> = emptyList(),
)

/**
 * 单个世界书条目。字段名对齐 CCv2/v3 规范（`keys`/`insertion_order`/…），
 * ST 独立导出格式的同义字段（`key`/`order`/`disable`/…）在解析层归一。
 *
 * @property keys 触发关键词；`/pattern/flags` 形态按正则处理（ST 惯例）。
 * @property secondaryKeys 选择性触发（[selective]=true 时须主键命中 **且**
 *   任一次键命中）。
 * @property content 命中后注入的正文。
 * @property insertionOrder 注入排序（小者在前，对齐 ST 的 order 语义）。
 * @property constant 常驻条目：不做关键词匹配，每轮恒注入。
 * @property selective 是否启用次键联合触发。
 * @property caseSensitive 关键词大小写敏感（默认不敏感）。
 * @property enabled 禁用条目跳过（ST 独立格式的 `disable:true` 归一为 false）。
 * @property position 原始位置字段（before_char/after_char 或 0/1），本端 v1
 *   统一按 before_char 语义注入 system（见 [LorebookInjector]），字段保留
 *   供查看与前瞻兼容。
 */
data class LorebookEntry(
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val content: String = "",
    val insertionOrder: Int = 100,
    val constant: Boolean = false,
    val selective: Boolean = false,
    val caseSensitive: Boolean = false,
    val enabled: Boolean = true,
    val position: String = "before_char",
    val name: String = "",
    val id: String? = null,
)

/**
 * 世界书关键词匹配（纯函数，JVM 可测）。扫描语义对齐 ST World Info 的
 * v1 子集：
 * - 大小写不敏感（条目 [LorebookEntry.caseSensitive] 可开）；
 * - 全半角归一（`ＡＢＣ`≡`abc`、「：」≡":"，中文卡常见全角标点关键词）；
 * - `/pattern/flags` 形态的 key 按正则处理（flags 支持 i）；
 * - selective 条目要求次键至少命中一个；
 * - constant 条目跳过匹配恒命中；keys 为空的非 constant 条目永不命中。
 */
object LorebookMatcher {

    private val WHITESPACE_RUN = Regex("\\s+")

    /** NFKC 归一（全角→半角等）+ 可选小写化；空白串收敛为单空格。 */
    fun normalize(text: String, caseSensitive: Boolean): String {
        val nkfc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val lowered = if (caseSensitive) nkfc else nkfc.lowercase()
        return lowered.replace(WHITESPACE_RUN, " ").trim()
    }

    private val REGEX_KEY = Regex("^/(.+)/([a-z]*)$", RegexOption.DOT_MATCHES_ALL)

    /**
     * 在扫描窗口中判定一个条目是否触发。[texts] 为原文（大小写敏感条目用），
     * [normalizedTexts] 为归一小写文本（默认条目用，由注入器复用归一结果）。
     */
    fun matches(entry: LorebookEntry, texts: List<String>, normalizedTexts: List<String>): Boolean {
        if (!entry.enabled) return false
        if (entry.constant) return true
        val keys = entry.keys.filter { it.isNotBlank() }
        if (keys.isEmpty()) return false
        val scan = if (entry.caseSensitive) texts else normalizedTexts
        val primaryHit = keys.any { keyHit(it, entry, texts, scan) }
        if (!primaryHit) return false
        if (entry.selective) {
            val secondary = entry.secondaryKeys.filter { it.isNotBlank() }
            if (secondary.isEmpty()) return false
            return secondary.any { keyHit(it, entry, texts, scan) }
        }
        return true
    }

    private fun keyHit(
        rawKey: String,
        entry: LorebookEntry,
        texts: List<String>,
        normalizedTexts: List<String>,
    ): Boolean {
        REGEX_KEY.matchEntire(rawKey.trim())?.let { m ->
            val pattern = m.groupValues[1]
            val flags = mutableSetOf<RegexOption>()
            if ('i' in m.groupValues[2] || !entry.caseSensitive) flags += RegexOption.IGNORE_CASE
            val regex = try {
                Regex(pattern, flags)
            } catch (_: Exception) {
                return false
            }
            return texts.any { regex.containsMatchIn(it) }
        }
        val key = normalize(rawKey, entry.caseSensitive)
        if (key.isEmpty()) return false
        return normalizedTexts.any { it.contains(key) }
    }
}

/**
 * 世界书注入器（纯函数，JVM 可测）：对扫描窗口命中的条目按
 * [LorebookEntry.insertionOrder] 排序，在字符预算内拼成一段可并入 system
 * 的文本块。注入位置 v1 统一 before_char 语义——落在 AvatarSession 单条
 * system 消息的**末尾**（人设/协议之后）：保持 [人设][协议] 前缀逐字节稳定
 * （服务商前缀缓存，protocol pin 语义），逐轮变化的世界书段落放其后不破坏。
 *
 * 预算语义：常驻（constant）条目恒注入、不占关键词预算；命中条目按序
 * 装入，装不下的整条跳过（对齐 ST 的 token budget 行为——截半条比丢整条
 * 更伤设定）。
 */
object LorebookInjector {

    data class Config(
        /** 注入字符预算（docs/tavern-companion-feasibility.md P1-1：先给 1500）。 */
        val budgetChars: Int = 1500,
        /** 扫描窗口：最近 N 条 user 消息（含当前输入，ST 的 scan depth 同语义）。 */
        val scanDepth: Int = 2,
        /** 注入块的标题行（随界面语言）。 */
        val header: String = "【世界设定】",
    )

    /**
     * @return 注入文本（含标题行）；无书/无命中/无内容返回 null（零开销路径：
     *   不注入时请求体与既有形态逐字节一致）。
     */
    fun inject(
        book: Lorebook?,
        recentUserTexts: List<String>,
        config: Config = Config(),
    ): String? {
        val lore = book?.takeIf { it.entries.isNotEmpty() } ?: return null
        val window = recentUserTexts.takeLast(config.scanDepth)
        if (window.isEmpty()) return null
        val normalized = window.map { LorebookMatcher.normalize(it, caseSensitive = false) }
        val matched = lore.entries.filter { LorebookMatcher.matches(it, window, normalized) }
        if (matched.isEmpty()) return null
        val constants = matched.filter { it.constant }.sortedBy { it.insertionOrder }
        val triggered = matched.filterNot { it.constant }.sortedBy { it.insertionOrder }
        var used = 0
        val kept = buildList {
            for (entry in constants + triggered) {
                val cost = entry.content.length
                if (entry.constant) {
                    add(entry); used += cost
                } else if (used + cost <= config.budgetChars) {
                    add(entry); used += cost
                }
            }
        }
        val body = kept.map { it.content.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n\n")
        if (body.isBlank()) return null
        return "${config.header}\n$body"
    }
}
