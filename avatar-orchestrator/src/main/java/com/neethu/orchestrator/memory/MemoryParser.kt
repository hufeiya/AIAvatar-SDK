package com.neethu.orchestrator.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 提炼输出的宽松解析（docs/memory-feasibility.md §4.3）：截首个 `{...}` →
 * kotlinx lenient + ignoreUnknownKeys；逐条清洗——未知 category→other、
 * importance clamp 1..10、content ≤120 字、ops ≤5 条；解析失败返回空列表
 * （调用方按「本轮不记」静默结束，永不抛）。
 */
object MemoryParser {

    /** 单条变更操作。 */
    data class Op(
        val action: String,
        val id: String? = null,
        val content: String = "",
        val category: String = MemoryCategories.OTHER,
        val importance: Int = MemoryLimits.IMPORTANCE_AUTO_DEFAULT,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 一次提炼最多应用的操作数（prompt 层同样有硬顶，这里兜机械层）。 */
    const val MAX_OPS = 5

    /**
     * @return 合法 ops（含空列表=模型判定无可记，是**成功**结论）；null =
     *   输出无法解析为 ops JSON（出格回复）——调用方保留窗口下次重试。
     */
    fun parse(raw: String): List<Op>? {
        val fragment = firstJsonObject(raw) ?: return null
        val root = try {
            json.parseToJsonElement(fragment).jsonObject
        } catch (_: Exception) {
            return null
        }
        val ops = root["ops"] as? JsonArray ?: return null
        return ops.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull(::parseOp)
            .take(MAX_OPS)
            .toList()
    }

    private fun parseOp(obj: JsonObject): Op? {
        val action = obj.str("action")?.lowercase() ?: return null
        when (action) {
            "add" -> {
                val content = MemoryLimits.clampContent(obj.str("content"))
                if (content.isEmpty()) return null
                return Op(
                    action = "add",
                    content = content,
                    category = MemoryCategories.normalize(obj.str("category")),
                    importance = MemoryLimits.clampImportance(obj.str("importance")?.toIntOrNull()),
                )
            }
            "update" -> {
                val content = MemoryLimits.clampContent(obj.str("content"))
                val id = obj.str("id")?.trim().orEmpty()
                if (content.isEmpty() || id.isEmpty()) return null
                return Op(action = "update", id = id, content = content)
            }
            "delete" -> {
                val id = obj.str("id")?.trim().orEmpty()
                if (id.isEmpty()) return null
                return Op(action = "delete", id = id)
            }
            else -> return null // 未知 action 整条丢弃
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.let { runCatching { it.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    /**
     * 截首个平衡的 `{...}`（模型偶尔在 JSON 前后带客套话或 markdown 围栏）。
     * 嵌套花括号按深度配对；字符串里的花括号不计数（提炼输出是机器形态，
     * 粗糙处理足够——解析失败也只是本轮不记，零风险）。
     */
    internal fun firstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
