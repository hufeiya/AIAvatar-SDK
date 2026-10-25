package com.neethu.orchestrator.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 世界书解析器（docs/tavern-companion-feasibility.md P1-1）：把两种来源的
 * JSON 归一成 [Lorebook]——
 * - 角色卡内嵌 `data.character_book`（CCv2/v3：entries 为数组，字段
 *   `keys`/`secondary_keys`/`content`/`insertion_order`/`position`/
 *   `priority`/`constant`/`selective`/`case_sensitive`/`enabled`）；
 * - ST World Info 独立导出（entries 为 `{id: {...}}` 映射，字段
 *   `key`/`keysecondary`/`order`/`disable`/`comment`/…）。
 *
 * 容错语义与 [CharacterCardParser] 一致：整文件解析失败返回 null，单条目
 * 字段缺失/类型不符跳过该条目不炸整本。
 */
object LorebookParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 独立世界书 JSON 文本 → [Lorebook]；非 JSON/无 entries 返回 null。 */
    fun parse(text: String): Lorebook? = try {
        val root = json.parseToJsonElement(text).jsonObject
        if (root["entries"] == null) null else fromRoot(root)
    } catch (_: Exception) {
        null
    }

    /** 卡内嵌 `character_book` 对象（CCv2/v3）→ [Lorebook]。 */
    fun fromEmbedded(book: JsonObject): Lorebook = fromRoot(book)

    private fun fromRoot(root: JsonObject): Lorebook = Lorebook(
        name = root.str("name").orEmpty(),
        entries = when (val raw = root["entries"]) {
            is JsonArray -> raw.mapNotNull { (it as? JsonObject)?.let(::entry) }
            is JsonObject -> raw.mapNotNull { (id, value) ->
                (value as? JsonObject)?.let { entry(it, id = id) }
            }
            else -> emptyList()
        },
    ).let { book ->
        // 死条目剔除：空正文、或无关键词又非常驻（永不触发的占位）
        book.copy(
            entries = book.entries.filter { e ->
                e.content.isNotBlank() && (e.constant || e.keys.any { it.isNotBlank() })
            },
        )
    }

    /** 单条目归一：CC 规范字段优先，ST 独立格式同义字段兜底。 */
    private fun entry(obj: JsonObject, id: String? = null): LorebookEntry {
        val disable = obj.bool("disable") ?: false
        return LorebookEntry(
            keys = obj.strList("keys") ?: obj.strList("key") ?: emptyList(),
            secondaryKeys = obj.strList("secondary_keys") ?: obj.strList("keysecondary") ?: emptyList(),
            content = obj.str("content").orEmpty(),
            insertionOrder = obj.int("insertion_order") ?: obj.int("order") ?: 100,
            constant = obj.bool("constant") ?: false,
            selective = obj.bool("selective") ?: false,
            caseSensitive = obj.bool("case_sensitive") ?: false,
            enabled = (obj.bool("enabled") ?: true) && !disable,
            position = obj.str("position") ?: obj.int("position")?.let { if (it == 1) "after_char" else "before_char" }
                ?: "before_char",
            name = obj.str("name") ?: obj.str("comment").orEmpty(),
            id = obj.str("uid") ?: id,
        )
    }

    // ── tolerant JSON helpers（对齐 CharacterCardParser 同名私有件）────────

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.toIntOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.let {
            when (it.content.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    /** 字符串数组字段；容忍单字符串写法（作者侧手写 JSON 常见 `"keys": "王冠"`）。 */
    private fun JsonObject.strList(key: String): List<String>? = when (val v = this[key]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        is JsonPrimitive -> if (v.isString) listOf(v.content) else null
        else -> null
    }
}
