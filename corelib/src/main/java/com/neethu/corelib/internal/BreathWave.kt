package com.neethu.corelib.internal

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

/**
 * Pure breath waveform — the JVM-testable numeric core of [VrmBreathEngine]
 * （方案一呼吸，docs/avatar-realism-feasibility.md §2.1）.
 *
 * 周期归一 u ∈ [0,1)：吸气段占 [INHALE_FRACTION]（40%）、呼气段占 60%——
 * 「吸快呼慢」的不对称由时长分配实现，两段波形均为 easeInOutSine（两端零速，
 * 速度处处连续，无顿挫）。静息 0.25 Hz（15 次/分）；说话时频率 ×1.15、幅度
 * ×0.6（引擎侧调制），幅度切换经 [ampFactor] 一阶趋近平滑，置位瞬间不突跳。
 */
internal object BreathWave {

    /** 吸气段占整周期的比例（0.4 = 吸:呼 = 40:60）。 */
    const val INHALE_FRACTION = 0.4f

    /** 说话⇄静息幅度系数的一阶趋近速率（1/s）：≈0.5s 过渡。 */
    const val AMP_SMOOTH_RATE = 2f

    private val PI_F = Math.PI.toFloat()

    /**
     * 归一化波形：u（先回卷进 [0,1)，容忍越界输入）→ 归一化幅度 a ∈ [0,1]。
     * 两段都用 easeInOutSine（两端零速）——速度在谷底/波峰/周期缝全部连续
     * （C¹）。曾用 easeOutSine 做吸气段（起步全速），周期缝处速度从 0 突跳到
     * 峰值产生每周期一次的顿挫（真机可见），改现版后消除；「吸快呼慢」由
     * 40/60 的时长分配保留（同距离用时短 → 峰值速度仍为呼气 1.5 倍）。
     */
    fun amplitude(u: Float): Float {
        val x = u - floor(u)
        return if (x < INHALE_FRACTION) {
            val p = x / INHALE_FRACTION
            (1f - cos(PI_F * p)) / 2f // easeInOutSine：慢起慢收
        } else {
            val p = (x - INHALE_FRACTION) / (1f - INHALE_FRACTION)
            (1f + cos(PI_F * p)) / 2f // 1 − easeInOutSine
        }
    }

    /**
     * 相位推进并回卷进 [0,1)。只做单圈回卷：调用方（渲染循环）已把 dt 钳到
     * ≤0.05s，dt·hz 恒 ≪ 1；超大 dt 不做多圈处理。
     */
    fun advance(phase: Float, dtSeconds: Float, hz: Float): Float {
        var p = phase + dtSeconds * hz
        if (p >= 1f) p -= 1f
        return p
    }

    /**
     * 幅度调制系数从 [current] 向 [target] 的一阶（指数）趋近：单调、不过冲，
     * 恒落在 min/max(current, target) 之间。dt=0 时原值返回。
     */
    fun ampFactor(current: Float, target: Float, dtSeconds: Float): Float {
        if (dtSeconds <= 0f) return current
        val k = 1f - exp(-dtSeconds * AMP_SMOOTH_RATE)
        return current + (target - current) * k
    }
}
