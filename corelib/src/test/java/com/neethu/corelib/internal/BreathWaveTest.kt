package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 呼吸波形纯函数的不变量（方案一，docs/avatar-realism-feasibility.md §2.1）：
 *  1. 吸呼时长比 40/60（吸快呼慢，缝在 u=0.4）；
 *  2. 相位连续无跳变（段缝 + 周期回卷缝）；
 *  3. 幅度有界 [0,1]；
 *  4. 说话调制边界（一阶趋近单调不过冲、收敛到目标）；
 *  5. 长时间推进（模拟 10 万帧）无漂移。
 */
class BreathWaveTest {

    // ── 1. 吸呼 40/60 与波形形状 ─────────────────────────────────────────

    @Test
    fun `inhale takes 40 percent and peaks exactly at the seam`() {
        assertEquals(0.4f, BreathWave.INHALE_FRACTION)
        assertEquals(0f, BreathWave.amplitude(0f), 1e-6f)
        // 吸气段终点 = 波峰（easeInOutSine 收在 1）
        assertEquals(1f, BreathWave.amplitude(BreathWave.INHALE_FRACTION - 1e-6f), 1e-3f)
        assertEquals(1f, BreathWave.amplitude(BreathWave.INHALE_FRACTION), 1e-6f)
        // 半幅点在吸气段正中（easeInOutSine 对称）
        assertEquals(0.5f, BreathWave.amplitude(0.2f), 1e-3f)
    }

    @Test
    fun `waveform velocity is continuous at seams and troughs (no stutter corner)`() {
        // 数值微分：接缝（0/0.4/1）两侧的斜率差必须远小于周期内峰值斜率。
        // 旧版吸气 easeOutSine 在 u=0 缝处速度从 0 突跳到峰值（每周期一次顿挫）。
        fun slope(u: Float): Float {
            val h = 1e-3f
            return (BreathWave.amplitude(u + h) - BreathWave.amplitude(u - h)) / (2 * h)
        }
        val peakSlope = slope(0.2f) // 吸气段中点=峰值速度
        for (seam in listOf(0.0005f, 0.4f, 0.9995f)) {
            val jump = abs(slope(seam + 1e-3f) - slope(seam - 1e-3f))
            assertTrue("seam u=$seam velocity jump $jump too large", jump < peakSlope * 0.15f)
        }
    }

    @Test
    fun `waveform is monotone rising in inhale and falling in exhale`() {
        var prev = 0f
        var u = 0f
        while (u < 0.4f) {
            val a = BreathWave.amplitude(u)
            assertTrue("rising at u=$u: $a < $prev", a >= prev)
            prev = a
            u += 0.01f
        }
        prev = 1f
        u = 0.4f
        while (u < 1f) {
            val a = BreathWave.amplitude(u)
            assertTrue("falling at u=$u: $a > $prev", a <= prev)
            prev = a
            u += 0.01f
        }
    }

    // ── 2. 相位连续无跳变 ────────────────────────────────────────────────

    @Test
    fun `waveform is continuous at both seams`() {
        // 吸⇄呼段缝（u=0.4）
        val beforeSeam = BreathWave.amplitude(0.4f - 1e-5f)
        val afterSeam = BreathWave.amplitude(0.4f + 1e-5f)
        assertTrue("seam jump: $beforeSeam vs $afterSeam", abs(beforeSeam - afterSeam) < 1e-3f)
        // 周期回卷缝（u=1 → 0）
        val beforeWrap = BreathWave.amplitude(1f - 1e-5f)
        assertTrue("wrap jump: $beforeWrap vs 0", abs(beforeWrap - BreathWave.amplitude(0f)) < 1e-3f)
    }

    @Test
    fun `advance wraps back into the unit interval`() {
        assertEquals(0.009f, BreathWave.advance(0.999f, 0.01f, 1f), 1e-6f)
        assertEquals(0f, BreathWave.advance(0.75f, 0.25f, 1f), 1e-6f)
        // dt=0：相位原样
        assertEquals(0.5f, BreathWave.advance(0.5f, 0f, 1f), 1e-6f)
    }

    // ── 3. 幅度有界 ──────────────────────────────────────────────────────

    @Test
    fun `amplitude stays within the unit range across the whole period`() {
        var u = -2f // 越界输入也必须安全回卷
        while (u <= 2f) {
            val a = BreathWave.amplitude(u)
            assertTrue("a=$a at u=$u below 0", a >= -1e-6f)
            assertTrue("a=$a at u=$u above 1", a <= 1f + 1e-6f)
            u += 0.0005f
        }
    }

    // ── 4. 说话调制边界 ──────────────────────────────────────────────────

    @Test
    fun `speaking modulation approaches the target monotonically without overshoot`() {
        // 静息 1 → 说话 0.6：严格单调下降、恒 ≥ 0.6、≈200 帧（e^-6.4≈0.2%）收敛
        var v = 1f
        var prev = 1f
        repeat(200) {
            v = BreathWave.ampFactor(v, 0.6f, 0.016f)
            assertTrue("below target: $v", v >= 0.6f - 1e-6f)
            assertTrue("non-monotone: $v > $prev", v <= prev + 1e-9f)
            prev = v
        }
        assertEquals(0.6f, v, 0.005f)

        // 说话 0.6 → 静息 1：对称，恒 ≤ 1、不过冲
        v = 0.6f
        prev = 0.6f
        repeat(200) {
            v = BreathWave.ampFactor(v, 1f, 0.016f)
            assertTrue("above target: $v", v <= 1f + 1e-6f)
            assertTrue("non-monotone: $v < $prev", v >= prev - 1e-9f)
            prev = v
        }
        assertEquals(1f, v, 0.005f)
    }

    @Test
    fun `ampFactor is a no-op at zero dt`() {
        assertEquals(1f, BreathWave.ampFactor(1f, 0.6f, 0f), 0f)
    }

    // ── 5. 长时间推进无漂移 ──────────────────────────────────────────────

    @Test
    fun `phase returns to start after 100k frames at exact dt`() {
        // 0.25 步长在二进制里精确：10 万帧 = 25000 整圈，相位必须回到 0
        var phase = 0f
        repeat(100_000) {
            phase = BreathWave.advance(phase, 0.25f, 1f)
            assertTrue("phase escaped: $phase", phase >= 0f && phase < 1f)
        }
        assertEquals(0f, phase, 1e-6f)
    }

    @Test
    fun `phase tracks elapsed wall time over 100k frames at real rates`() {
        // 真实配置（0.25Hz、16ms 帧）：1600s × 0.25Hz = 400 整圈，累计浮点
        // 误差不得吃掉整圈对齐（有界、无系统性漂移）
        var phase = 0f
        val dt = 0.016f
        repeat(100_000) {
            phase = BreathWave.advance(phase, dt, 0.25f)
            assertTrue("phase escaped: $phase", phase >= 0f && phase < 1f)
        }
        assertEquals(0f, phase, 0.02f)
    }

    @Test
    fun `amplitude stays bounded over a 100k-frame simulation with speaking toggles`() {
        // 说话⇄静息来回切换的长跑：幅度恒有界（调制不会把波形推出 [0,1]）
        var phase = 0f
        var ampScale = 1f
        var speaking = false
        val dt = 0.016f
        repeat(100_000) {
            if (it % 300 == 0) speaking = !speaking
            ampScale = BreathWave.ampFactor(ampScale, if (speaking) 0.6f else 1f, dt)
            phase = BreathWave.advance(phase, dt, if (speaking) 0.25f * 1.15f else 0.25f)
            val a = BreathWave.amplitude(phase) * ampScale
            assertTrue("a=$a at frame $it", a >= -1e-6f && a <= 1f + 1e-6f)
        }
    }
}
