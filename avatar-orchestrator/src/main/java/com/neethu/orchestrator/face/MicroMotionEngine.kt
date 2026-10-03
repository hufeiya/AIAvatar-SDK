package com.neethu.orchestrator.face

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Physiological micro-motions. V1 ships the AIRI-parity auto-blink:
 *
 *  - interval uniformly random in [1, 6] s (animation.ts MIN/MAX_BLINK_INTERVAL)
 *  - blink cycle 0.2 s with a `sin(π·t)` half-wave curve
 *
 * Suppression is handled by the caller ([FaceDriver]): when an emotion owns
 * the eye area the weight is clamped to 0 while the internal timer keeps
 * advancing — exactly AIRI's `blink.update(vrm, delta, { suppress })`.
 */
class MicroMotionEngine(
    private val minBlinkIntervalS: Float = 1f,
    private val maxBlinkIntervalS: Float = 6f,
    private val blinkDurationS: Float = 0.2f,
) {
    private var nextBlinkIn = nextInterval()
    private var blinkProgress = -1f // negative = not blinking

    /** Advance time; returns the current blink weight (0 when idle). */
    fun tickBlink(deltaSeconds: Float): Float {
        if (blinkProgress < 0f) {
            nextBlinkIn -= deltaSeconds
            if (nextBlinkIn <= 0f) blinkProgress = 0f
        } else {
            blinkProgress += deltaSeconds / blinkDurationS
            if (blinkProgress >= 1f) {
                blinkProgress = -1f
                nextBlinkIn = nextInterval()
            }
        }
        return if (blinkProgress in 0f..1f) {
            sin(PI.toFloat() * min(blinkProgress, 1f))
        } else {
            0f
        }
    }

    private fun nextInterval(): Float =
        minBlinkIntervalS + Random.nextFloat() * (maxBlinkIntervalS - minBlinkIntervalS)
}
