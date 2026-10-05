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
    private var minBlinkIntervalS: Float = 1f,
    private var maxBlinkIntervalS: Float = 6f,
    private val blinkDurationS: Float = 0.2f,
) {
    private var nextBlinkIn = nextInterval()
    private var blinkProgress = -1f // negative = not blinking
    private var blinkEnabled = true

    /** 眨眼开关：关闭立即结束当前眨眼并停住（权重恒 0）。 */
    fun setBlinkEnabled(enabled: Boolean) {
        blinkEnabled = enabled
        if (!enabled) blinkProgress = -1f
    }

    /**
     * 眨眼平均间隔（秒）：以均值为中心的均匀分布，宽度 ±0.71×均值
     * （默认 3.5 → 1.05~5.95，与出厂 U(1,6) 同量级）。运行时可调。
     */
    fun setBlinkIntervalMean(meanS: Float) {
        minBlinkIntervalS = (meanS * 0.3f).coerceAtLeast(0.3f)
        maxBlinkIntervalS = (meanS * 1.7f).coerceAtLeast(minBlinkIntervalS + 0.2f)
    }

    /** Advance time; returns the current blink weight (0 when idle). */
    fun tickBlink(deltaSeconds: Float): Float {
        if (!blinkEnabled) {
            blinkProgress = -1f
            return 0f
        }
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
