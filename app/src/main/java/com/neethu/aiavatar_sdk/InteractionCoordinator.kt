package com.neethu.aiavatar_sdk

import android.util.Log
import com.neethu.corelib.AvatarController
import com.neethu.corelib.ContactKind
import com.neethu.corelib.ContactPoseRequest
import com.neethu.corelib.ContactPoseResult
import com.neethu.corelib.WalkPhase
import com.neethu.orchestrator.face.GazeMode
import com.neethu.orchestrator.locomotion.LocomotionDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 场景交互就位链（dream_bedroom）：`interact_with` 工具与 ai_cmd `interact`
 * 共用的编排器——查交互点表 → 走到接近点（waypoint 图路由，走 driver 与
 * LLM move_to 同路）→ 原地转向交互点朝向 → sit/lie 由 corelib 接触定姿层
 * 对齐（hips 吸附接触面）→ 可选视线目标。
 *
 * 异步起步语义（坑 24 同源）：[interact] 立即返回状态文案，链路在本类
 * coroutine 里跑完——ToolRegistry 10s 超时下阻塞等待走位必炸。
 *
 * 到达判定不用 WalkArrived 事件（多段腿每段都发，事件序不定），轮询
 * 「walkState IDLE + driver 队列空」连续两拍（120ms×2）才算站定。
 */
internal class InteractionCoordinator(
    private val controller: AvatarController,
    private val sessionProvider: () -> com.neethu.orchestrator.session.AvatarSession?,
    private val scope: CoroutineScope,
) {

    /** 当前场景的交互点表（MainActivity 随场景切换重注入）。 */
    var anchors: List<InteractionAnchor> = emptyList()

    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    /** 最近一次就位的锚点（ai_cmd pose_state 显示）。 */
    var lastAnchorId: String? = null
        private set

    /**
     * 启动就位链（异步）。返回给调用方（工具回灌/ai_cmd 回显）的状态文案。
     */
    fun interact(anchorId: String, speed: Float? = null): String {
        val anchor = anchors.firstOrNull { it.id == anchorId.lowercase() }
            ?: return "unknown interaction anchor '$anchorId' — run `interactions` for the table"
        if (isBusy) return "another interaction is still in progress (pose_clear/stand_up to cancel)"
        job = scope.launch {
            try {
                runInteraction(anchor, speed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "interaction '${anchor.id}' failed", e)
            }
        }
        return "interaction '${anchor.id}' started (${anchor.kind}${anchor.approach?.let { ", via $it" } ?: ""})"
    }

    /** 起身（结束坐/躺姿）。 */
    fun clearPose(): String {
        val state = controller.getContactPoseState()
        if (state == null) {
            lastAnchorId = null
            return "no contact pose active"
        }
        controller.clearContactPose()
        restoreGazeDelayed()
        return "standing up (pose was ${state.kind})"
    }

    /** ai_cmd pose_state 用的一行快照。 */
    fun stateText(): String {
        val pose = controller.getContactPoseState()
        val poseLine = if (pose != null) {
            "pose=${pose.kind} phase=${pose.phase} aligned=${pose.aligned}" +
                (lastAnchorId?.let { " anchor=$it" } ?: "")
        } else {
            "pose=none"
        }
        return poseLine + " chain=" + (if (isBusy) "running" else "idle")
    }

    private suspend fun runInteraction(anchor: InteractionAnchor, speed: Float?) {
        val session = sessionProvider()
        val driver = session?.locomotionDriver
        lastAnchorId = anchor.id

        // 1. 坐/躺中先起身（起身动画 ≈1s，走位车道让路）
        if (controller.getContactPoseState() != null) {
            controller.clearContactPose()
            restoreGazeDelayed()
            delay(1_200)
        }

        // 2. 走到接近点：sit/lie 用 approach；stand 类直接走锚点本身。
        //    driver（waypoint 图）优先；无会话兜底直线 startWalk。
        val walkTargetId = if (anchor.isContactPose) anchor.approach ?: anchor.id else anchor.id
        val walkTarget = anchors.firstOrNull { it.id == walkTargetId } ?: anchor
        val started = when {
            driver != null && driver.find(walkTargetId) != null -> {
                if (speed != null) driver.speed = speed
                driver.walkTo(walkTargetId) == LocomotionDriver.Outcome.STARTED
            }
            else -> {
                ensureWalkGaits(controller)
                controller.startWalk(walkTarget.x, walkTarget.z, speed ?: 1.0f) ==
                    com.neethu.corelib.WalkStartResult.STARTED
            }
        }
        if (!started) {
            Log.i(TAG, "walk to '${walkTarget.id}' did not start (already there / refused)")
        }
        if (!awaitStandstill(driver)) {
            Log.w(TAG, "walk to '${walkTarget.id}' timed out — interaction aborted")
            return
        }

        // 3. 站位点转向交互点朝向；接触点朝向由定姿层转动。
        if (!anchor.isContactPose && anchor.kind != "waypoint") {
            controller.turnTo(anchor.yawDeg)
            awaitStandstill(null)
        }

        // 4. 接触定姿（corelib：转身→过渡剪辑→hips 吸附接触面→循环剪辑）
        if (anchor.isContactPose) {
            val kind = if (anchor.kind == "lie") ContactKind.LIE else ContactKind.SIT
            val result = controller.applyContactPose(
                ContactPoseRequest(
                    kind = kind,
                    x = anchor.x,
                    y = anchor.y,
                    z = anchor.z,
                    yawDeg = anchor.yawDeg,
                    sitInClipAssetPath = anchor.sitInClip,
                    sitLoopClipAssetPath = anchor.sitLoopClip,
                    standUpClipAssetPath = anchor.standUpClip,
                    hipsLiftMeters = anchor.hipsLift ?: 0.09f,
                ),
            )
            if (result != ContactPoseResult.STARTED) {
                Log.w(TAG, "contact pose '${anchor.id}' refused: $result")
                return
            }
        }

        // 5. 视线目标（看电视/看窗外）：POINT 8s 后回 CAMERA（不黏死视线；
        //    视频模式下人脸追踪循环 33ms 内会覆盖本设置，天然无害）
        anchor.look?.let { look ->
            val fd = sessionProvider()?.faceDriver
            if (fd != null && look.size >= 3) {
                fd.setGazePoint(look[0], look[1], look[2])
                fd.setGazeMode(GazeMode.POINT)
                scope.launch {
                    delay(8_000)
                    fd.setGazeMode(GazeMode.CAMERA)
                }
            }
        }
        Log.i(TAG, "interaction '${anchor.id}' done (kind=${anchor.kind})")
    }

    /**
     * 站定判定：walkState IDLE 且 driver 无排队腿，连续两拍（事件序不定，
     * 采样去抖）。[driver] 为 null（直线走位/转身）只看 walkState。
     */
    private suspend fun awaitStandstill(driver: LocomotionDriver?, timeoutMs: Long = 30_000): Boolean {
        delay(250) // startWalk 起步帧之前 phase 仍是 IDLE，先让起步发生
        val deadline = System.currentTimeMillis() + timeoutMs
        var stable = 0
        while (System.currentTimeMillis() < deadline && coroutineContextSafe()) {
            val idle = controller.getWalkState()?.phase == WalkPhase.IDLE
            val noLegs = driver?.pendingLegsSnapshot().isNullOrEmpty()
            stable = if (idle && noLegs) stable + 1 else 0
            if (stable >= 2) return true
            delay(120)
        }
        return false
    }

    private fun coroutineContextSafe(): Boolean = job?.isActive != false

    /** 起身/换位后把视线交回默认（延迟恢复，避免 POINT 黏死）。 */
    private fun restoreGazeDelayed() {
        scope.launch {
            delay(1_500)
            sessionProvider()?.faceDriver?.setGazeMode(GazeMode.CAMERA)
        }
    }

    companion object {
        private const val TAG = "InteractionCoordinator"
    }
}
