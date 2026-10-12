package com.neethu.orchestrator.locomotion

import com.neethu.corelib.Lang
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 场景锚点（自由走动 P1，docs/locomotion-feasibility.md §7.2）：房间里一个
 * 语义点的世界坐标与双语显示名——`<move:名字>` 标签与 `move_to` 工具共用的
 * 目标词表。[name] 是模型写的标签名（ASCII 短词，如 "sofa"）；[x]/[z] 是
 * avatar 世界坐标（米）。锚点表会话内不变（协议块指纹稳定的承诺，A.1 第
 * 34 条：system 恒单条不重钉）。
 */
data class MoveAnchor(
    val name: String,
    val x: Float,
    val z: Float,
    /** 协议块/工具结果显示名（当前语言，默认回落到 name）。 */
    val label: String = "",
    val labelEn: String = "",
) {
    fun labelFor(lang: Lang): String = when (lang) {
        Lang.EN -> labelEn.ifEmpty { label.ifEmpty { name } }
        else -> label.ifEmpty { name }
    }
}

/**
 * 解析锚点表 JSON。支持两种顶层形态：`{"anchors":[…]}`（推荐，与场景
 * meta.json 同风格）或裸数组 `[…]`。单条缺 name/x/z 的丢弃（脏数据不进
 * 词表——协议块只会列出能真实走到的锚点）。用 kotlinx.serialization 而非
 * org.json：orchestrator 是纯 JVM 可测模块（与 SceneMetaParser 同惯例）。
 */
fun parseMoveAnchors(json: String): List<MoveAnchor> = runCatching {
    val root = Json.parseToJsonElement(json)
    val arr: JsonArray = when (root) {
        is JsonArray -> root
        is JsonObject -> root["anchors"]?.jsonArray ?: return@runCatching emptyList()
        else -> return@runCatching emptyList()
    }
    arr.mapNotNull { el ->
        val o = (el as? JsonObject) ?: return@mapNotNull null
        fun str(key: String): String? = o[key]?.jsonPrimitive?.let { runCatching { it.content }.getOrNull() }
        fun num(key: String): Float? = o[key]?.jsonPrimitive?.floatOrNull
        val name = str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val x = num("x") ?: return@mapNotNull null
        val z = num("z") ?: return@mapNotNull null
        MoveAnchor(
            name = name.lowercase(),
            x = x,
            z = z,
            label = str("label").orEmpty(),
            labelEn = str("labelEn").orEmpty(),
        )
    }
}.getOrDefault(emptyList())
