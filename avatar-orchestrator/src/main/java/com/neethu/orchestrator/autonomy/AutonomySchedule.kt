package com.neethu.orchestrator.autonomy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 作息先验表（docs/autonomy-feasibility.md §4.2）：现实时钟 hour → 各状态
 * 权重乘子。可调可关——`assets/autonomy/autonomy.schedule.json` 解析产物；
 * 缺省表（[DEFAULT]）已是「深夜爱躺、白天爱坐、傍晚爱看」的保守曲线。
 *
 * JSON 形态：
 * ```json
 * {
 *   "weights": {"SITTING": 2.0},
 *   "activityWeights": {"work": 2.0},
 *   "hours": [{"from": 0, "to": 6, "weights": {"LYING": 5.0}, "activityWeights": {"food": 0.2}}],
 *   "quietHours": {"from": 23, "to": 8, "enabled": true}
 * }
 * ```
 * `from` 含、`to` 舍；quietHours 支持 from>to 的跨午夜区间。解析失败整表
 * 回落默认（脏配置不炸决策）。`activityWeights`（P0 道具系统 §3.1）是
 * 活动 category→倍率：基线表与小时规则叠乘，只影响 FSM 选点层（LLM/用户
 * 指令不受限），缺省空表 = 全 1（保守，不动既有分布）。
 */
data class AutonomySchedule(
    val baseWeights: Map<AutonomyStateKind, Float>,
    val hourRules: List<HourRule>,
    val quietFrom: Int = 23,
    val quietTo: Int = 8,
    val quietEnabled: Boolean = true,
    /** 活动 category→倍率基线（选点层加权用）。 */
    val activityWeights: Map<String, Float> = emptyMap(),
) {

    data class HourRule(
        val from: Int,
        val to: Int,
        val weights: Map<AutonomyStateKind, Float>,
        val activityWeights: Map<String, Float> = emptyMap(),
    )

    /** 某小时的状态权重：默认值 × 命中规则乘子（多重命中按表序叠乘）。 */
    fun weightsFor(hour: Int): Map<AutonomyStateKind, Float> {
        val out = baseWeights.toMutableMap()
        for (rule in hourRules) {
            if (hourIn(hour, rule.from, rule.to)) {
                for ((k, v) in rule.weights) {
                    out[k] = (out[k] ?: 1f) * v
                }
            }
        }
        return out
    }

    /** 某小时的活动类别倍率：基线 × 命中规则乘子（缺省类别 = 1）。 */
    fun activityWeightsFor(hour: Int): Map<String, Float> {
        val out = activityWeights.toMutableMap()
        for (rule in hourRules) {
            if (hourIn(hour, rule.from, rule.to)) {
                for ((k, v) in rule.activityWeights) {
                    out[k] = (out[k] ?: 1f) * v
                }
            }
        }
        return out
    }

    /** 静默时段判定（问候硬门 + 深夜判定共用）。 */
    fun inQuietHours(hour: Int): Boolean =
        quietEnabled && hourIn(hour, quietFrom, quietTo)

    /** 深夜时段（躺卧时长加长判定用）：静默时段即深夜。 */
    val isNightDefined: Boolean get() = quietEnabled

    companion object {
        /** 缺省作息：0-6 躺×5 坐×0.5；9-17 坐×3；18-23 看×2。 */
        val DEFAULT = AutonomySchedule(
            baseWeights = mapOf(
                AutonomyStateKind.IDLE_STAND to 1f,
                AutonomyStateKind.WANDER to 1.2f,
                AutonomyStateKind.LOOKING to 1f,
                AutonomyStateKind.SITTING to 1.6f,
                AutonomyStateKind.LYING to 1f,
            ),
            hourRules = listOf(
                HourRule(0, 6, mapOf(AutonomyStateKind.LYING to 5f, AutonomyStateKind.SITTING to 0.5f)),
                HourRule(9, 17, mapOf(AutonomyStateKind.SITTING to 3f)),
                HourRule(18, 23, mapOf(AutonomyStateKind.LOOKING to 2f)),
            ),
        )

        /** from 含 to 舍的区间判定（支持 from>to 跨午夜）。 */
        fun hourIn(hour: Int, from: Int, to: Int): Boolean =
            if (from <= to) hour in from until to else hour >= from || hour < to

        fun parse(json: String): AutonomySchedule = runCatching {
            val root = Json.parseToJsonElement(json).jsonObject
            val base = parseWeights(root["weights"]) ?: DEFAULT.baseWeights
            val rules = (root["hours"] as? JsonArray)?.mapNotNull { el ->
                val o = (el as? JsonObject) ?: return@mapNotNull null
                val from = o["from"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val to = o["to"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                HourRule(
                    from,
                    to,
                    parseWeights(o["weights"]) ?: return@mapNotNull null,
                    parseActivityWeights(o["activityWeights"]),
                )
            }.orEmpty()
            val quiet = root["quietHours"] as? JsonObject
            AutonomySchedule(
                baseWeights = base,
                hourRules = rules,
                quietFrom = quiet?.get("from")?.jsonPrimitive?.intOrNull ?: DEFAULT.quietFrom,
                quietTo = quiet?.get("to")?.jsonPrimitive?.intOrNull ?: DEFAULT.quietTo,
                quietEnabled = quiet?.get("enabled")?.jsonPrimitive?.booleanOrNull ?: DEFAULT.quietEnabled,
                activityWeights = parseActivityWeights(root["activityWeights"]),
            )
        }.getOrDefault(DEFAULT)

        private fun parseWeights(el: kotlinx.serialization.json.JsonElement?): Map<AutonomyStateKind, Float>? {
            val o = (el as? JsonObject) ?: return null
            val out = mutableMapOf<AutonomyStateKind, Float>()
            for ((key, v) in o) {
                val kind = runCatching { AutonomyStateKind.valueOf(key.uppercase()) }.getOrNull() ?: continue
                val w = v.jsonPrimitive.floatOrNull ?: continue
                if (w >= 0f) out[kind] = w
            }
            return out.ifEmpty { null }
        }

        /** 活动 category→倍率（键为任意小写类别串；负值跳过）。空/缺 = 空表。 */
        private fun parseActivityWeights(el: kotlinx.serialization.json.JsonElement?): Map<String, Float> {
            val o = (el as? JsonObject) ?: return emptyMap()
            val out = mutableMapOf<String, Float>()
            for ((key, v) in o) {
                val w = v.jsonPrimitive.floatOrNull ?: continue
                if (w >= 0f) out[key.lowercase()] = w
            }
            return out
        }
    }
}
