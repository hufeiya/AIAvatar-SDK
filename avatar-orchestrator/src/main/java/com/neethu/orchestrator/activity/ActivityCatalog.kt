package com.neethu.orchestrator.activity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 活动目录（P0 道具系统，docs/prop-system-feasibility.md §2.1）：每条活动 =
 * 道具×动画×场景锚点×可选 VFX×可选台词的「行为配方」。两个自主来源共用：
 * LLM `start_activity` 工具与 FSM 作息（`DoActivity` directive）。
 *
 * 边界铁律同交互点表（interact_with 惯例）：**orchestrator 不直接碰 assets**，
 * JSON 由 app 层读出字符串喂 [parse]；解析失败整表回落 [EMPTY]（脏配置不炸
 * 决策）。道具显隐不经这里——bindings.json 绑定表（animation→accessory）
 * 是道具显隐的唯一通道，[ActivityEntry.props] 仅声明语义归属与校验；
 * 唯一例外是 `mount:"world"` 世界摆件（§6.2，[PropRef.isWorld]）：它没有
 * 动画可绑，由 app 编排层按锚点挂/收（orchestrator 只出结构化引用）。
 */
data class ActivityEntry(
    val id: String,
    val name: String,
    val nameEn: String,
    val desc: String,
    val category: String,
    /** 交互点 id（场景 *.interactions.json 内）；null = 原地做（拉伸/打电话）。 */
    val anchor: String? = null,
    /** VRMA 类别相对路径（绑定表同款键，如 `07_日常互动/X.vrma`）。 */
    val animation: String,
    /** 道具引用列表（语义归属；骨骼道具显隐仍走 bindings.json）。 */
    val props: List<PropRef> = emptyList(),
    /** 本动画下的绑定偏移覆盖（§2.1 可选；P0 仅解析，P1 偏移模板层统一消费）。 */
    val bindingOffset: BindingOffset? = null,
    /** VFX preset id，活动开始时播放。 */
    val vfx: String? = null,
    /** true=循环持续态（直到 stop/被打断）；false=一次性。 */
    val loop: Boolean = true,
    /** FSM 进入该活动的最短停留（毫秒）。 */
    val minDwellMs: Long? = null,
    /** 作息窗口（from 含 to 舍，同 AutonomySchedule.hourIn 惯例）。 */
    val hourFrom: Int? = null,
    val hourTo: Int? = null,
    /** 台词池：活动开始时 speak() 随机说一句（可带 `<emo:>/<act:>` 标签）。 */
    val says: List<String> = emptyList(),
) {
    /** 就绪的作息窗口（hourTo 舍）；任一端缺失 = 不限。 */
    val hourRange: IntRange? get() = if (hourFrom != null && hourTo != null) hourFrom until hourTo else null

    /** §2.1 `bindingOffset`：位置偏移 + 备注（P0 不落渲染层）。 */
    data class BindingOffset(val position: List<Float>, val note: String? = null)
}

/**
 * 活动配方里的道具引用（§2.1 `props` 项）。两种写法：
 * - 字符串 `"book"` = 骨骼道具（语义归属；显隐走 bindings.json 绑定表）；
 * - 对象 `{"id":"yoga_mat","mount":"world","anchor":"yoga_spot"}` = 世界摆件
 *   （§6.2 地面/场景道具）：不挂骨骼、不经绑定表，由编排层按 [anchor] 交互点
 *   坐标挂到世界（活动开始出现、结束收起）。
 */
data class PropRef(
    val id: String,
    /** 挂载类别；`"world"` = 世界摆件（大小写不敏感），其余/null = 骨骼道具。 */
    val mount: String? = null,
    /** 世界摆件的交互点 id（§6.2；骨骼道具忽略）。 */
    val anchor: String? = null,
) {
    val isWorld: Boolean get() = mount.equals("world", ignoreCase = true)
}

/**
 * 解析后的活动目录（不可变快照）。id 查询大小写不敏感（对齐 wearAccessory
 * 白名单比对口径）；重复 id 首条胜出（目录是策展资产，先到先得可预期）。
 */
class ActivityCatalog(val entries: List<ActivityEntry>) {

    val ids: List<String> get() = entries.map { it.id }

    fun find(id: String): ActivityEntry? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }

    operator fun get(id: String): ActivityEntry? = find(id)

    companion object {
        val EMPTY = ActivityCatalog(emptyList())

        /**
         * 解析 activities.json（§2.1 schema）。根缺 `activities` 数组 / JSON 脏 /
         * 必填字段缺失的条目跳过；整表解析失败回落 [EMPTY]。
         */
        fun parse(json: String): ActivityCatalog = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val arr = root["activities"] as? JsonArray ?: return EMPTY
            val seen = mutableSetOf<String>()
            val out = mutableListOf<ActivityEntry>()
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                fun str(key: String): String? =
                    (o[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                        ?.takeIf { it.content.isNotBlank() }?.content
                val id = str("id")?.lowercase() ?: continue
                val name = str("name") ?: continue
                val animation = str("animation") ?: continue
                if (!seen.add(id)) continue
                out += ActivityEntry(
                    id = id,
                    name = name,
                    nameEn = str("nameEn") ?: name,
                    desc = str("desc") ?: name,
                    category = str("category") ?: "daily",
                    anchor = str("anchor")?.lowercase(),
                    animation = animation,
                    props = (o["props"] as? JsonArray)?.mapNotNull { el ->
                        // 字符串 = 骨骼道具；对象 = {id, mount, anchor}（§6.2 世界摆件）。
                        // 只认字符串基元（数字/布尔/JsonNull 不当道具 id）
                        when (el) {
                            is JsonPrimitive -> el.takeIf { it.isString }?.contentOrNullSafe()?.let { PropRef(it) }
                            is JsonObject -> el.getString("id")?.let {
                                PropRef(
                                    id = it,
                                    mount = el.getString("mount"),
                                    anchor = el.getString("anchor")?.lowercase(),
                                )
                            }
                            else -> null
                        }
                    } ?: emptyList(),
                    bindingOffset = (o["bindingOffset"] as? JsonObject)?.let { bo ->
                        ActivityEntry.BindingOffset(
                            position = (bo["position"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.floatOrNull } ?: emptyList(),
                            note = (bo["note"] as? JsonPrimitive)?.contentOrNullSafe(),
                        )
                    },
                    vfx = str("vfx"),
                    loop = o["loop"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: true,
                    minDwellMs = o["minDwellMs"]?.let { (it as? JsonPrimitive)?.longOrNullSafe() },
                    hourFrom = o["hourRange"]?.let { hr ->
                        (hr as? JsonArray)?.getOrNull(0)?.let { (it as? JsonPrimitive)?.intOrNull }
                    },
                    hourTo = o["hourRange"]?.let { hr ->
                        (hr as? JsonArray)?.getOrNull(1)?.let { (it as? JsonPrimitive)?.intOrNull }
                    },
                    says = (o["says"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() } ?: emptyList(),
                )
            }
            ActivityCatalog(out)
        }.getOrDefault(EMPTY)

        private fun JsonPrimitive.contentOrNullSafe(): String? =
            runCatching { content }.getOrNull()?.takeIf { it.isNotBlank() }

        /** 非空字符串字段（null/空白/非原始类型 = null）。 */
        private fun JsonObject.getString(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNullSafe()

        private fun JsonPrimitive.longOrNullSafe(): Long? = runCatching {
            (content.toDoubleOrNull()?.toLong())
        }.getOrNull()
    }
}
