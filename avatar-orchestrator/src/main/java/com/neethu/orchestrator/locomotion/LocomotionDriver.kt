package com.neethu.orchestrator.locomotion

import android.util.Log
import com.neethu.corelib.AvatarController
import com.neethu.corelib.WalkPhase
import com.neethu.corelib.WalkStartResult

/**
 * LLM 请求的行走驱动（`<move:锚点>` 标签与 `move_to` 工具共用，与
 * [com.neethu.orchestrator.gesture.GestureDriver] 完全对称，
 * docs/locomotion-feasibility.md §7.3）：锚点目录由集成方注入（app 读
 * `assets/scene/<场景>.anchors.json` 后赋给 [AvatarSession.locomotionAnchors]，
 * 同 LlmActionCatalog 注入 ActCatalog 的模式），决策层只发指令不逐帧参与。
 *
 * P2 waypoint 图（§7.2 V2）：锚点表带 links 时 [walkTo] 经
 * [WaypointRouter] BFS 求途经锚点序列，逐段起步——到达一段由
 * [onWalkFinished]（AvatarSession 从 controller.walkFinished 转交）接着走
 * 下一段，中断/停步清空队列。全表无 links 时维持两点直线（V1 行为）。
 *
 * 防抖（坑 9）：走动中收到新指令一律忽略（不排队不替换目标）——"边走边改
 * 目标"的语义不进 V1，提示词与协议块同步约束模型不要连发。结果枚举
 * [Outcome] 让标签通道与工具通道各自决定「要不要说出去」（工具要回灌文案，
 * 标签通道只发事件+日志）。[open] 供测试 fakes。
 */
open class LocomotionDriver(private val controller: AvatarController) {

    enum class Outcome {
        /** 已起步（步态播放中，状态机接管）。 */
        STARTED,

        /** 锚点表里没有这个名字。 */
        UNKNOWN_ANCHOR,

        /** 走动中忽略新指令（防抖，坑 9）。 */
        ALREADY_WALKING,

        /** 没加载 3D 模型。 */
        NO_MODEL,

        /** 没加载场景（虚空行走守卫，坑 4）。 */
        NO_SCENE,

        /** 步态未配置 / 步态剪辑加载失败。 */
        NO_GAIT,

        /** 「模仿我」引擎占用骨骼，走动被拒。 */
        MIMIC_ACTIVE,

        /** 渲染层缺失（无视图挂载）。 */
        NO_RENDERER,

        /** 步态剪辑加载失败（同名后果不同归因的缝：NO_GAIT=没配置）。 */
        LOAD_FAILED,
    }

    /** 当前锚点表（协议块词表与解析共用同一份，会话内不变）。 */
    var anchors: List<MoveAnchor> = emptyList()

    /** 行走速度（m/s），`move_to` 的可选 speed 参数与标签通道共用。 */
    var speed: Float = 1.0f

    /** waypoint 路由的剩余途经锚点（含最终目标；空 = 单段直线走动中）。 */
    private val pendingLegs = ArrayDeque<MoveAnchor>()

    /** 名字解析对大小写不敏感（extractor 已归一小写；锚点表手写大小写兜底）。 */
    open fun find(name: String): MoveAnchor? =
        anchors.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** 解析名字并起步；防抖与失败归因全在 [walkTo]。 */
    open fun walkTo(name: String): Outcome {
        val anchor = find(name) ?: return Outcome.UNKNOWN_ANCHOR
        return walkTo(anchor)
    }

    open fun walkTo(anchor: MoveAnchor): Outcome {
        controller.getWalkState()?.let { state ->
            if (state.phase != WalkPhase.IDLE) {
                Log.i(TAG, "move cue ignored (already walking): target=${anchor.name}")
                return Outcome.ALREADY_WALKING
            }
        }
        // waypoint 路由（P2 §7.2 V2）：图拓扑（任一锚点带 links）时起点取
        // **全局最近锚点**（不设半径——保证从图节点出发走verified连线，比
        // 直线穿越未知几何安全）；无图 = 直线（V1 行为，bounds clamp 兜底）。
        val state = controller.getWalkState()
        val graphed = anchors.any { it.links.isNotEmpty() }
        val from = if (state != null && graphed) {
            WaypointRouter.nearestAnchor(anchors, state.x, state.z, maxDistMeters = Float.MAX_VALUE)
        } else {
            null
        }
        val route = WaypointRouter.route(anchors, from, anchor)
        pendingLegs.clear()
        pendingLegs.addAll(route.drop(1)) // 第一段立即起步，剩余逐段续
        val first = route.first()
        val outcome = startLeg(first)
        if (outcome != Outcome.STARTED) pendingLegs.clear()
        if (pendingLegs.isNotEmpty()) {
            Log.i(TAG, "waypoint route to '${anchor.name}': ${route.joinToString("→") { it.name }}")
        }
        return outcome
    }

    open fun startLeg(anchor: MoveAnchor): Outcome {
        val result = controller.startWalk(anchor.x, anchor.z, speed)
        val outcome = when (result) {
            WalkStartResult.STARTED -> Outcome.STARTED
            WalkStartResult.NO_MODEL -> Outcome.NO_MODEL
            WalkStartResult.NO_SCENE -> Outcome.NO_SCENE
            WalkStartResult.NO_GAIT -> Outcome.NO_GAIT
            WalkStartResult.MIMIC_ACTIVE -> Outcome.MIMIC_ACTIVE
            WalkStartResult.LOAD_FAILED -> Outcome.LOAD_FAILED
            WalkStartResult.NO_RENDERER -> Outcome.NO_RENDERER
        }
        if (outcome != Outcome.STARTED) {
            Log.w(TAG, "walk to '${anchor.name}' rejected: $result")
        }
        return outcome
    }

    /**
     * 走动结束转交（AvatarSession 从 controller.walkFinished 收集后调用）：
     * [arrived]=true 续走下一段；false（停步/中断）清空队列。
     */
    open fun onWalkFinished(arrived: Boolean) {
        if (!arrived) {
            if (pendingLegs.isNotEmpty()) Log.i(TAG, "walk interrupted — ${pendingLegs.size} legs dropped")
            pendingLegs.clear()
            return
        }
        val next = pendingLegs.removeFirstOrNull() ?: return
        Log.i(TAG, "arrival → next leg '${next.name}'")
        val outcome = startLeg(next)
        if (outcome != Outcome.STARTED) pendingLegs.clear()
    }

    /** 剩余途经锚点名（诊断/ai_cmd；空 = 无多段腿在排队）。 */
    fun pendingLegsSnapshot(): List<String> = pendingLegs.map { it.name }

    /** 停步并回到 idle（`stop_moving` 工具 / 中断语义）。 */
    open fun stopWalking() {
        pendingLegs.clear()
        controller.stopWalk()
    }

    companion object {
        private const val TAG = "LocomotionDriver"
    }
}
