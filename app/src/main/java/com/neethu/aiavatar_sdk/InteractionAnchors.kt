package com.neethu.aiavatar_sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 场景交互点表（dream_bedroom 接入，资产包 interactions.json 的 app 侧投影
 * ——生成脚本 tools/gen_dream_interactions.py，坐标已是 SDK 世界米）：
 *
 * - `stand/approach/waypoint` 纯走位点：走过去 + 原地转向 [InteractionAnchor.yawDeg]；
 * - `sit/lie` 接触点：先走 [InteractionAnchor.approach]（导航图里的接近点），
 *   再由 corelib 接触定姿层把 hips 吸附到 (x, y, z) 接触面（坐姿播
 *   sitInClip→sitLoopClip，躺姿为根旋转方案——内置库没有躺卧 VRMA）。
 *
 * [InteractionAnchor.look] 是就位后的视线目标（看电视/看窗外），可选。
 * 解析用 kotlinx.serialization（org.json 是 not-mocked 桩，进不了 JVM 单测
 * ——与 SceneMetaParser 同惯例）。
 */
internal data class InteractionAnchor(
    val id: String,
    val label: String,
    val labelEn: String,
    val kind: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val yawDeg: Float,
    /** sit/lie 的先行走位目标（导航图接近点 id）；stand 类为 null。 */
    val approach: String? = null,
    /** 起步→就位链路上的过渡剪辑（SIT）。 */
    val sitInClip: String? = null,
    /** 就位后循环的坐姿 idle（SIT）。 */
    val sitLoopClip: String? = null,
    /** 起身剪辑（SIT，clearContactPose 播放）。 */
    val standUpClip: String? = null,
    /** 接触面→hips 骨的中心距（臀厚/背厚）；缺省走 corelib 默认 0.09。 */
    val hipsLift: Float? = null,
    /** 就位后的视线目标（SDK 世界坐标）；null = 不干预视线。 */
    val look: List<Float>? = null,
) {
    val isContactPose: Boolean get() = kind == "sit" || kind == "lie"
}

internal fun parseInteractionAnchors(json: String): List<InteractionAnchor> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    val arr = root["anchors"]?.jsonArray ?: return@runCatching emptyList()
    arr.mapNotNull { el ->
        val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
        fun str(key: String): String? = o[key]?.jsonPrimitive?.content
        fun num(key: String): Float? = o[key]?.jsonPrimitive?.floatOrNull
        val id = str("id")?.lowercase() ?: return@mapNotNull null
        InteractionAnchor(
            id = id,
            label = str("label") ?: id,
            labelEn = str("labelEn") ?: id.replace('_', ' '),
            kind = str("kind") ?: "stand",
            x = num("x") ?: 0f,
            y = num("y") ?: 0f,
            z = num("z") ?: 0f,
            yawDeg = num("yawDeg") ?: 0f,
            approach = str("approach")?.lowercase(),
            sitInClip = str("sitInClip"),
            sitLoopClip = str("sitLoopClip"),
            standUpClip = str("standUpClip"),
            hipsLift = num("hipsLift"),
            look = o["look"]?.jsonArray?.mapNotNull { it.jsonPrimitive.floatOrNull },
        )
    }
}.getOrNull() ?: emptyList()
