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
    private var arrivedFlag = false
    private var lastX = 0f
    private var lastZ = 0f

    /**
     * Start (or retarget) a walk. Retargeting mid-walk keeps the heading and
     * the ease ramp; the align gate re-evaluates on the next update, so a
     * target behind the back turns in place first. Targets are clamped into
     * [bounds] when present (P0 room geometry = rectangle, no obstacles).
     * [startHoldSeconds] freezes translation for that long (start transition
     * clip windup); heading corrections still apply during the hold.
     */
    fun startWalk(
        targetX: Float,
        targetZ: Float,
        speed: Float,
        bounds: WalkBounds?,
        startHoldSeconds: Float = 0f,
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
        if (phase == WalkPhase.IDLE) {
            easeElapsed = 0f
            startHoldRemaining = startHoldSeconds.coerceAtLeast(0f)
        }
        phase = WalkPhase.TURNING
        arrivedFlag = false
    }

    /** Stop immediately (hard stop — transition clips are optional, renderer-side). */
    fun stopWalk() {
        phase = WalkPhase.IDLE
        startHoldRemaining = 0f
        arrivedFlag = false
    }

    /**
     * Advance one frame. [rootX/rootY/rootZ] is the current asset-root
     * translation (authoritative — the engine never integrates position
     * itself, so drags and programmatic moves can't desync it). Returns the
     * motion to apply, or `null` when idle/arrived this frame.
     */
    fun update(dtSeconds: Float, rootX: Float, rootY: Float, rootZ: Float): FrameMotion? {
        lastX = rootX
        lastZ = rootZ
        if (phase == WalkPhase.IDLE || dtSeconds <= 0f) return null

        val dx = targetX - rootX
        val dz = targetZ - rootZ
        val dist = sqrt(dx * dx + dz * dz)
        if (dist <= ARRIVE_EPS) {
            phase = WalkPhase.IDLE
            arrivedFlag = true
            return null
        }

        // Forward = (sin h, 0, cos h) → desired heading = atan2(dx, dz).
        val desired = atan2(dx, dz)
        val err = wrapAngle(desired - headingRad)
        val dHeading = err.coerceIn(-TURN_RATE_RAD_PER_S * dtSeconds, TURN_RATE_RAD_PER_S * dtSeconds)
        headingRad += dHeading

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

        if (abs(err) > ALIGN_RAD) {
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
        val step = minOf(speed, brakeSpeed) * ease * dtSeconds
        return if (step >= dist) {
            // Final partial step walks straight onto the target (≤ ALIGN_RAD off
            // the facing direction, invisible), then hard-stop.
            arrivedFlag = true
            phase = WalkPhase.IDLE
            FrameMotion(dx, 0f, dz, dHeading)
        } else {
            FrameMotion(sin(headingRad) * step, 0f, cos(headingRad) * step, dHeading)
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

/** The looping walk gait driving [LocomotionEngine] playback (app-configured). */
internal data class WalkGait(
    val clipAssetPath: String,
    val durationSeconds: Float,
    /** Ground speed (m/s) of the gait at playback speed 1 — from tools/walk-calib. */
    val baseSpeed: Float,
)
