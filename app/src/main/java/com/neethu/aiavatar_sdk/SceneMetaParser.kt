package com.neethu.aiavatar_sdk

import android.content.Context
import com.neethu.corelib.ScenePlacement
import com.neethu.corelib.WalkBounds
import com.neethu.orchestrator.locomotion.MoveAnchor
import com.neethu.orchestrator.locomotion.parseMoveAnchors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 场景对位 meta（自由走动 P0，docs/locomotion-feasibility.md §7.1）：
 * `assets/scene/<场景名>.meta.json`（可选，缺省 = 原样加载，与历史行为一致）。
 *
 * 标定流程：`load_scene` 后用 ai_cmd `scene_offset` / `scene_rot` 微调到
 * 角色双脚踩在房间地板上，把命令回显的 placement JSON 抄进本文件——
 * 随 APK 重打包后 MainActivity 的场景加载自动套用。
 * `bounds` 是可走区域（walk_to 的 clamp 依据），踩点满意后一并填入。
 *
 * 用 kotlinx.serialization 而非 org.json——android.jar 的 org.json 是抛
 * "not mocked" 的桩，进不了 JVM 单测（与预置卡导入的纯 JSON 逻辑同惯例）。
 */
internal fun sceneMetaAssetName(sceneFile: String): String =
    "scene/" + sceneFile.substringBeforeLast('.') + ".meta.json"

internal fun parseSceneMeta(json: String): ScenePlacement? = runCatching {
    val o = Json.parseToJsonElement(json).jsonObject
    fun num(key: String): Float = o[key]?.jsonPrimitive?.floatOrNull ?: 0f
    val bounds = o["bounds"]?.jsonObject?.let { b ->
        fun bnum(key: String, def: Float): Float = b[key]?.jsonPrimitive?.floatOrNull ?: def
        WalkBounds(
            minX = bnum("minX", -10f),
            maxX = bnum("maxX", 10f),
            minZ = bnum("minZ", -10f),
            maxZ = bnum("maxZ", 10f),
        )
    }
    ScenePlacement(
        offsetX = num("offsetX"),
        offsetY = num("offsetY"),
        offsetZ = num("offsetZ"),
        yawDeg = num("yawDeg"),
        bounds = bounds,
    )
}.getOrNull()

// ── 自由走动 AI 通道（P1，docs/locomotion-feasibility.md §7.2/§7.3）────────

/** 默认步态（tools/walk-calib 实测：Female Normal Walk 步长 0.417m/周期 0.683s）。
 *  ⚠P2 实测该素材带烘焙位移 1.49m/循环（净速 1.27 m/s，与 walk-calib 脚步速度
 *  1.22 一致——「原地」是误认），方案 C 提取开启后位移由素材驱动，更精确。 */
const val DEFAULT_WALK_CLIP = "animations/Female Normal Walk.vrma"
const val DEFAULT_WALK_BASE_SPEED = 1.22f

/** P2 跑步/侧移步态（hips 净位移实测：run 1.68 / strafeL 0.62 / strafeR 1.72 m/s，
 *  baseSpeed=0 由提取器自动标定）。 */
const val DEFAULT_RUN_CLIP = "animations/Running Forward.vrma"
const val DEFAULT_STRAFE_LEFT_CLIP = "animations/Walking Strafe To The Left.vrma"
const val DEFAULT_STRAFE_RIGHT_CLIP = "animations/Walking Strafe To The Right.vrma"

/** 锚点表资产路径（与场景对应；当前内置场景只有 home.glb）。 */
private const val ANCHORS_ASSET = "scene/home.anchors.json"

/**
 * 读锚点表（`<move:>` 词表与 move_to 工具共用）；文件缺失/坏 JSON = 空表
 * （协议块无移动段，功能静默缺席——集成方没有摆锚点就不宣传走动）。
 * 坐标是真机 `walk_to` 踩点后写死的（§7.2），换场景/重新对位后要重标。
 */
fun loadWalkAnchors(context: Context): List<MoveAnchor> = runCatching {
    context.assets.open(ANCHORS_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
}.getOrNull()?.let { parseMoveAnchors(it) } ?: emptyList()
