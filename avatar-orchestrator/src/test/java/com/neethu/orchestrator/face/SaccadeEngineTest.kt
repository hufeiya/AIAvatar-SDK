package com.neethu.orchestrator.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * SaccadeEngine（AIRI eye-motions.ts 精确移植）的分布与抖动语义锁。
 * 随机源全部注入 fake，行为逐项可断言。
 */
class SaccadeEngineTest {

    /** 恒 0 的随机源：jitter 恒 = -0.25（rand 区间起点），间隔恒 = 首档 800ms。 */
    private val zeroRandom = object : Random() {
        override fun nextBits(bitCount: Int) = 0
    }

    @Test
    fun `first tick fixates immediately (AIRI nextSaccadeAfter = -1)`() {
        val e = SaccadeEngine(random = zeroRandom)
        // 全 0 随机：jitter = -0.25（rand(-0.25,0.25) 起点），间隔 = 首档 800ms
        val refreshed = e.tick(0.016f, 10f, 20f, 30f)
        assertTrue(refreshed)
        assertEquals(10f - 0.25f, e.fixation[0], 1e-4f)
        assertEquals(20f - 0.25f, e.fixation[1], 1e-4f)
        assertEquals(30f, e.fixation[2], 1e-4f) // z 恒等于基准（上游 updateFixationTarget）
    }

    @Test
    fun `fixation does not follow the base between saccades`() {
        val e = SaccadeEngine(random = zeroRandom)
        e.tick(0.016f, 0f, 0f, 0f)
        val f0 = e.fixation.copyOf()
        // 基准移动但不 snap：注视点保持（跟随是 snap 的职责，上游同语义）
        e.tick(0.016f, 5f, 5f, 5f)
        assertTrue(f0.contentEquals(e.fixation))
    }

    @Test
    fun `snap re-fixates exactly and does not touch the timer`() {
        val e = SaccadeEngine(random = zeroRandom)
        e.tick(0.016f, 0f, 0f, 0f)
        e.snap(1f, 2f, 3f)
        assertEquals(1f, e.fixation[0], 0f) // 精确贴住，无抖动
        assertEquals(2f, e.fixation[1], 0f)
        assertEquals(3f, e.fixation[2], 0f)
        // snap 不重置计时：再 tick 一小步不应换点（间隔 ≥ 0.8s）
        assertFalse(e.tick(0.016f, 1f, 2f, 3f))
        // 上游语义是"先检查后累加"：0.032s < 0.8 不触发；累计过阈后的下一帧换点
        assertFalse(e.tick(0.9f, 1f, 2f, 3f))
        assertTrue(e.tick(0.016f, 1f, 2f, 3f))
    }

    @Test
    fun `saccade interval falls in the 0_8-4_8s table range`() {
        val e = SaccadeEngine(random = Random(42))
        val intervals = mutableListOf<Float>()
        var time = 0f
        var lastRefresh = 0f
        var firstSeen = false
        while (time < 400f) {
            // 换点比"累计时长过阈"滞后一帧（先检查后累加），测量值 ≤ 表值+16ms
            if (e.tick(0.016f, 0f, 0f, 0f)) {
                if (firstSeen) intervals.add(time - lastRefresh)
                firstSeen = true
                lastRefresh = time
            }
            time += 0.016f
        }
        assertTrue("expected many saccades, got ${intervals.size}", intervals.size in 80..500)
        for (iv in intervals) {
            assertTrue("interval $iv out of range", iv in 0.78f..4.85f)
        }
        // 首档（0.8-1.2s，概率 0.075）应少量出现；最长档只到 4.8s
        val shortOnes = intervals.count { it < 1.22f }
        assertTrue("first bucket should appear sometimes", shortOnes > 0)
    }

    @Test
    fun `jitter stays within the configured amplitude`() {
        val e = SaccadeEngine(random = Random(7))
        var time = 0f
        val seen = mutableListOf<Pair<Float, Float>>()
        while (time < 200f) {
            if (e.tick(0.016f, 100f, 50f, 25f)) seen.add(e.fixation[0] - 100f to e.fixation[1] - 50f)
            time += 0.016f
        }
        assertTrue(seen.isNotEmpty())
        for ((dx, dy) in seen) {
            assertTrue(abs(dx) <= 0.25f + 1e-4f)
            assertTrue(abs(dy) <= 0.25f + 1e-4f)
        }
    }
}
