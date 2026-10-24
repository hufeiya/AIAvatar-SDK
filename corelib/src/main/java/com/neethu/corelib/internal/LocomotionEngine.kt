package com.neethu.corelib.internal

import com.neethu.corelib.WalkBounds
import com.neethu.corelib.WalkPhase
import com.neethu.corelib.WalkSnapshot
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-math locomotion state machine for free walking (P0 of
 * docs/locomotion-feasibility.md) — no Filament/engine dependencies, fully
 * JVM-testable（纯数学类不带 android.util.Log，JVM 单测无 mock；日志在
 * SoulLinkRenderer 层）. The renderer feeds it the current root position
 * each frame and applies the returned [FrameMotion] to the asset root
 * transform.
 *
 * Movement model (方案 B, §4.2): the walk VRMA keeps playing **in place**
 * while this engine moves the root — synthetic root motion. Targets are
 * world-space XZ points; heading 0 faces +Z (toward the camera, the load
 * convention for both VRM 0.x and 1.0 — the 0.x Y-180 flip is already baked
 * into the root matrix, and world-space rotations compose on top of it).
 *
 * State machine: IDLE → TURNING (align to target in place; also the start-clip
 * windup window when the renderer plays one) → WALKING (advance along the
 * facing direction with continuous heading correction, pull-up braking near
 * the target) → IDLE (arrival within [ARRIVE_EPS]). Optional start/stop
 * transition clips are orchestrated by the renderer (§5.2 档 1), not here.
 */
internal class LocomotionEngine {

    companion object {
        /** Arrival radius (meters) — P0 stops hard inside it. */
        const val ARRIVE_EPS = 0.06f

        /** Advance gate: turn in place while the heading error exceeds this (~20°). */
        private const val ALIGN_RAD = 0.35f

        /** Turn-rate cap (rad/s, ~150°/s) for both in-place turns and walk corrections. */
        private const val TURN_RATE_RAD_PER_S = 2.6f

        /** Start-up speed ramp (seconds) so the first steps don't lurch. */
        private const val EASE_IN_SECONDS = 0.35f

        /**
         * Braking deceleration (m/s²) for the P1 pull-up stop (§5.1: 制动距离
         * = v²/2a): speed is capped to sqrt(2·a·dist) on approach so the walk
         * eases into the stop instead of hard-stopping at full pace.
         */
        private const val BRAKE_DECEL = 2.2f
    }

    /** Per-frame root motion to apply: world translation + world-Y rotation. */
    data class FrameMotion(
        val dx: Float,
        val dy: Float,
        val dz: Float,
        /** Left-multiply the root's 3×3 block by Ry(this) — turn about the body axis. */
        val dHeadingRad: Float,
        /**
         * 本帧指令速度（m/s，= min(speed, brake)×ease）。externalGait 模式下
         * 渲染层拿它设步态 playbackSpeed（提取位移随播放头走，自身已含变速）；
         * 程序位移模式下仅诊断用。
         */
        val commandedSpeedMps: Float = 0f,
    )

    private var phase = WalkPhase.IDLE
    private var headingRad = 0f
    private var targetX = 0f
    private var targetZ = 0f
    private var speed = 1.0f
    private var bounds: WalkBounds? = null
    private var easeElapsed = 0f
    /**
     * Start-clip windup (seconds): translation is suppressed (turn-in-place
     * behavior only) while a configured start transition clip plays — the
     * program motion must not double the clip's own steps (§5.2 档 1 标定项).
     * Fresh only when starting from IDLE.
     */
    private var startHoldRemaining = 0f
    /**
     * 侧移模式（P2 跑步/侧移）：heading 全程锁死不修正（不转身），位移直指
     * 目标（投影点）——身体朝向不变、横步挪过去，步态用侧移剪辑。
     */
    private var headingLocked = false
    private var arrivedFlag = false
    private var lastX = 0f
    private var lastZ = 0f

    /**
     * 原地转向模式（多虚拟人 P3 对谈位互相对着）：只转不挪，朝向对齐目标
     * 即到达（照常发 arrived 事件）。startWalk/stopWalk 都会退出本模式。
     */
    private var turnOnly = false

    /** 原地转向的到达容差（rad，~3°）——比行进对齐门更紧，站定就正对。 */
    private val TURN_ARRIVE_RAD = 0.06f

    /**
     * Start (or retarget) a walk. Retargeting mid-walk keeps the heading and
     * the ease ramp; the align gate re-evaluates on the next update, so a
     * target behind the back turns in place first. Targets are clamped into
     * [bounds] when present (P0 room geometry = rectangle, no obstacles).
     * [startHoldSeconds] freezes translation for that long (start transition
     * clip windup); heading corrections still apply during the hold.
     * [headingLocked]（侧移）跳过转身、全程不修正朝向。
     */
    fun startWalk(
        targetX: Float,
        targetZ: Float,
        speed: Float,
        bounds: WalkBounds?,
        startHoldSeconds: Float = 0f,
        headingLocked: Boolean = false,
    ) {
        this.bounds = bounds
        if (bounds != null) {
            this.targetX = targetX.coerceIn(bounds.minX, bounds.maxX)
            this.targetZ = targetZ.coerceIn(bounds.minZ, bounds.maxZ)
        } else {
            this.targetX = targetX
            this.targetZ = targetZ
        }
        this.speed = speed.coerceIn(0.2f, 3.0f)
        this.headingLocked = headingLocked
        turnOnly = false
        if (phase == WalkPhase.IDLE) {
            easeElapsed = 0f
            startHoldRemaining = startHoldSeconds.coerceAtLeast(0f)
        }
        // 侧移不进 TURNING（无朝向修正可言），直接走
        phase = if (headingLocked) WalkPhase.WALKING else WalkPhase.TURNING
        arrivedFlag = false
    }

    /** Stop immediately (hard stop — transition clips are optional, renderer-side). */
    fun stopWalk() {
        phase = WalkPhase.IDLE
        startHoldRemaining = 0f
        headingLocked = false
        turnOnly = false
        arrivedFlag = false
    }

    /**
     * Re-derive the internal heading after an external root rotation (scene
     * contact pose / spawn placement rotate the root matrix directly, which
     * this engine can't see — position stays authoritative per-frame, heading
     * is internal state and would go stale). [rad] is the belly heading read
     * back from the actual root matrix.
     */
    fun resyncHeadingRad(rad: Float) {
        headingRad = rad
    }

    /**
     * 原地转向（多虚拟人 P3 对谈位互相对着）：目标是一个世界点，engine 只转
     * 朝向不产生平移，|朝向误差| ≤ 容差即到达。bounds clamp 与 startWalk 同款。
     * 走动中调用被忽略（调用方先停步——防抖纪律同坑 9，这里不静默改目标）。
     */
    fun startTurnTo(targetX: Float, targetZ: Float, bounds: WalkBounds?): Boolean {
        if (phase != WalkPhase.IDLE) return false
        if (bounds != null) {
            this.targetX = targetX.coerceIn(bounds.minX, bounds.maxX)
            this.targetZ = targetZ.coerceIn(bounds.minZ, bounds.maxZ)
        } else {
            this.targetX = targetX
            this.targetZ = targetZ
        }
        turnOnly = true
        phase = WalkPhase.TURNING
        arrivedFlag = false
        return true
    }

    /**
     * Advance one frame. [rootX/rootY/rootZ] is the current asset-root
     * translation (authoritative — the engine never integrates position
     * itself, so drags and programmatic moves can't desync it). Returns the
     * motion to apply, or `null` when idle/arrived this frame.
     *
     * [externalGait]（方案 C，P2）：步态素材自带位移（提取器逐帧回灌），本引擎
     * 只管朝向/到达/速度策略——平移恒 0，[FrameMotion.commandedSpeedMps] 交给
     * 渲染层设步态 playbackSpeed（提取位移随播放头走，制动/ease 自动生效）。
     */
    fun update(
        dtSeconds: Float,
        rootX: Float,
        rootY: Float,
        rootZ: Float,
        externalGait: Boolean = false,
    ): FrameMotion? {
        lastX = rootX
        lastZ = rootZ
        if (phase == WalkPhase.IDLE || dtSeconds <= 0f) return null

        if (turnOnly) {
            val dx = targetX - rootX
            val dz = targetZ - rootZ
            // 目标就在脚下：方向未定义，直接算到位（调用方给的是「另一个人的
            // 位置」，正常不落这里；重叠站位兜底不空转）
            if (dx * dx + dz * dz <= ARRIVE_EPS * ARRIVE_EPS) {
                turnOnly = false
                phase = WalkPhase.IDLE
                arrivedFlag = true
                return null
            }
            val desired = atan2(dx, dz)
            val err = wrapAngle(desired - headingRad)
            val d = err.coerceIn(-TURN_RATE_RAD_PER_S * dtSeconds, TURN_RATE_RAD_PER_S * dtSeconds)
            headingRad += d
            if (abs(err) <= TURN_ARRIVE_RAD) {
                turnOnly = false
                phase = WalkPhase.IDLE
                arrivedFlag = true
            }
            return FrameMotion(0f, 0f, 0f, d)
        }

        val dx = targetX - rootX
        val dz = targetZ - rootZ
        val dist = sqrt(dx * dx + dz * dz)
        if (dist <= ARRIVE_EPS) {
            phase = WalkPhase.IDLE
            arrivedFlag = true
            return null
        }

        val dHeading: Float
        if (headingLocked) {
            dHeading = 0f
        } else {
            // Forward = (sin h, 0, cos h) → desired heading = atan2(dx, dz).
            val desired = atan2(dx, dz)
            val err = wrapAngle(desired - headingRad)
            dHeading = err.coerceIn(-TURN_RATE_RAD_PER_S * dtSeconds, TURN_RATE_RAD_PER_S * dtSeconds)
            headingRad += dHeading
        }

        // Start windup: turn-in-place behavior only, no translation. On the
        // frame the hold expires, restart the ease ramp so the first program
        // step still eases in after the transition clip.
        if (startHoldRemaining > 0f) {
            startHoldRemaining = (startHoldRemaining - dtSeconds).coerceAtLeast(0f)
            if (startHoldRemaining > 0f) {
                phase = WalkPhase.TURNING
                return FrameMotion(0f, 0f, 0f, dHeading)
            }
            easeElapsed = 0f
        }

        // 对齐门：程序位移模式原地转到位再走；externalGait 不设门——步态自带
        // 位移跟随朝向，转身体同时走出弧线（自然起步），设门反而冻结步态。
        if (!headingLocked && !externalGait &&
            abs(wrapAngle(atan2(dx, dz) - headingRad)) > ALIGN_RAD
        ) {
            phase = WalkPhase.TURNING
            return FrameMotion(0f, 0f, 0f, dHeading)
        }

        phase = WalkPhase.WALKING
        easeElapsed += dtSeconds
        val ease = if (easeElapsed >= EASE_IN_SECONDS) 1f else easeElapsed / EASE_IN_SECONDS
        // Pull-up braking (P1 §5.1): cap the speed to sqrt(2·a·dist) on
        // approach so the last strides decelerate into the stop instead of
        // hard-stopping at full pace.
        val brakeSpeed = sqrt(2f * BRAKE_DECEL * dist)
        val commanded = minOf(speed, brakeSpeed) * ease
        if (externalGait) {
            // 方案 C：渲染层以 commanded 设步态变速，位移来自素材提取
            return if (commanded >= dist / dtSeconds) {
                // 提取步长可能越过目标：归零速停（下一帧 dist≤ARRIVE_EPS 到达）
                FrameMotion(0f, 0f, 0f, dHeading, 0f)
            } else {
                FrameMotion(0f, 0f, 0f, dHeading, commanded)
            }
        }
        val step = commanded * dtSeconds
        return if (step >= dist) {
            // Final partial step walks straight onto the target (≤ ALIGN_RAD off
            // the facing direction, invisible), then hard-stop.
            arrivedFlag = true
            phase = WalkPhase.IDLE
            FrameMotion(dx, 0f, dz, dHeading, commanded)
        } else if (headingLocked) {
            // 侧移：朝向不变，位移直指目标（不沿 heading 前向）
            FrameMotion(dx / dist * step, 0f, dz / dist * step, dHeading, commanded)
        } else {
            FrameMotion(sin(headingRad) * step, 0f, cos(headingRad) * step, dHeading, commanded)
        }
    }

    fun snapshot(): WalkSnapshot = WalkSnapshot(
        phase = phase,
        x = lastX,
        z = lastZ,
        headingDeg = Math.toDegrees(wrapAngle(headingRad).toDouble()).toFloat(),
        targetX = targetX,
        targetZ = targetZ,
        speed = speed,
    )

    /** Arrival happened since the last call — the renderer switches back to idle. */
    fun consumeArrived(): Boolean {
        val arrived = arrivedFlag
        arrivedFlag = false
        return arrived
    }

    /** Wrap [x] into (−π, π]. */
    private fun wrapAngle(x: Float): Float {
        val piF = Math.PI.toFloat()
        val twoPi = 2f * piF
        return ((x + piF) % twoPi + twoPi) % twoPi - piF
    }
}

/** Gait family (P2 跑步/侧移): pick by commanded speed / strafe direction. */
internal enum class WalkGaitKind { WALK, RUN, STRAFE_LEFT, STRAFE_RIGHT }

/** The looping walk gait driving [LocomotionEngine] playback (app-configured). */
internal data class WalkGait(
    val clipAssetPath: String,
    val durationSeconds: Float,
    /** Ground speed (m/s) of the gait at playback speed 1 — from tools/walk-calib,
     *  or auto-derived from the baked root motion (方案 C extraction). */
    val baseSpeed: Float,
    val kind: WalkGaitKind = WalkGaitKind.WALK,
    /** 方案 C 提取器（非原地素材）；null = 原地素材走程序位移。 */
    val extractor: LocomotionExtractor? = null,
    /**
     * hips 局部 1 单位 → 世界的渲染尺度（root→hips 链路缩放积）。骨骼写入的
     * 位移经它放大，而 root 平移列不在放大链上——提取回灌补回同样的倍数
     * （剥离与回灌才严格抵消）。unit-cube 大缩放模型上 ≈9，普通模型 ≈1。
     */
    val renderScale: Float = 1f,
)
