package com.neethu.corelib

import kotlin.math.hypot

/**
 * Pure math for the analog drive channel (docs/gamepad-control-feasibility.md):
 * a gamepad / virtual joystick — or any analog source — feeds two -1..1 axes;
 * this object owns the axis conditioning and the retarget geometry the
 * renderer consumes each frame. Deliberately Android-free so it is JVM
 * testable; the app-side hub (deadzone for real sticks) and SoulLinkRenderer
 * (camera-relative direction + lookahead) share it.
 */
object DriveMath {

    /**
     * Radial deadzone (stick drift guard): vectors at or under this magnitude
     * read as exactly zero. Applied to hardware axes only — AI pad commands
     * are exact values and skip it.
     */
    const val DEADZONE = 0.12f

    /**
     * Ground speed at full deflection without the run gain. The default gait
     * base is 1.22 m/s, so full stick ≈ the gait's natural walking pace.
     */
    const val WALK_MAX_MPS = 1.2f

    /**
     * Run gain (`pad run on` / gamepad A): 1.2 × 1.8 = 2.16 m/s ≥ the run
     * gait auto-select threshold (SoulLinkRenderer RUN_SPEED_MPS = 1.9), so
     * running engages the run gait with the same startWalk path.
     */
    const val RUN_BOOST = 1.8f

    /**
     * The locomotion engine's pull-up braking deceleration
     * (LocomotionEngine.BRAKE_DECEL) — mirrored here so the lookahead can
     * guarantee the brake never binds during continuous driving.
     */
    const val BRAKE_DECEL_MPS2 = 2.2f

    /**
     * Radial deadzone + rescale: |(x,y)| ≤ [DEADZONE] → (0,0); otherwise the
     * vector magnitude is remapped from [DEADZONE..1] to 0..1 (no jump at the
     * rim, no gain loss at the straight edge) and capped there — a diagonal
     * full deflection has magnitude √2, which reads as full speed, never
     * amplified. Output magnitude is always ≤ 1.
     */
    fun applyDeadzone(x: Float, y: Float): Pair<Float, Float> {
        val m = hypot(x, y)
        if (m <= DEADZONE) return 0f to 0f
        val scaled = ((m - DEADZONE) / (1f - DEADZONE)).coerceAtMost(1f)
        val k = scaled / m
        return (x * k) to (y * k)
    }

    /** Ground speed (m/s) for a deadzoned axis magnitude in 0..1. */
    fun speedMps(magnitude: Float, run: Boolean): Float =
        magnitude.coerceIn(0f, 1f) * WALK_MAX_MPS * (if (run) RUN_BOOST else 1f)

    /**
     * Retarget lookahead (meters) for continuous driving: the walk target is
     * re-pinned this far ahead of the avatar every frame, so the engine's
     * pull-up brake √(2·a·d) — with d ≡ lookahead — never caps below the
     * commanded speed and the pace stays constant (no stutter near the
     * always-moving target). Floor keeps slow crawling on a stable target
     * clear of the arrival epsilon.
     */
    fun lookaheadMeters(speedMps: Float): Float =
        maxOf(0.35f, speedMps * speedMps / (2f * BRAKE_DECEL_MPS2) * 1.3f)

    /**
     * Camera-relative world XZ direction for a drive axis pair (the PC-game
     * convention: push up = walk away from the camera). [gazeX]/[gazeZ] is
     * the camera gaze projected to the XZ plane (target − eye); the forward
     * component is its normalization and right = (−fz, fx) — the cross
     * product with world up, i.e. gaze +Z ⇒ screen-right = −X in this
     * right-handed world. Degenerate vertical gaze (top-down camera) falls
     * back to [fallbackForwardX/Z] (the avatar's own heading); without a
     * fallback returns null.
     *
     * @return unit (dx, dz) or null when the axis pair and the fallback are
     *   both degenerate.
     */
    fun cameraRelativeDir(
        gazeX: Float,
        gazeZ: Float,
        axisForward: Float,
        axisRight: Float,
        fallbackForwardX: Float = Float.NaN,
        fallbackForwardZ: Float = Float.NaN,
    ): Pair<Float, Float>? {
        var fx = gazeX
        var fz = gazeZ
        val len = hypot(fx, fz)
        if (len < 1e-4f) {
            if (fallbackForwardX.isNaN() || fallbackForwardZ.isNaN()) return null
            fx = fallbackForwardX
            fz = fallbackForwardZ
        } else {
            fx /= len
            fz /= len
        }
        val dx = fx * axisForward + (-fz) * axisRight
        val dz = fz * axisForward + fx * axisRight
        val dlen = hypot(dx, dz)
        if (dlen < 1e-6f) return null
        return (dx / dlen) to (dz / dlen)
    }
}
