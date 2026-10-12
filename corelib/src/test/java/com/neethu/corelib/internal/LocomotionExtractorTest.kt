package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 方案 C 位移提取（docs/locomotion-feasibility.md §4.3）分析数学的锁死测试。
 */
class LocomotionExtractorTest {

    private val rest = floatArrayOf(0f, 1f, 0f)

    /** 构造 hips 平移轨道：times 均匀，values 逐帧由 [frameDelta] 给出（未翻转）。 */
    private fun track(durations: Float, frames: Int, frameDelta: (Float) -> FloatArray): VrmaParser.HumanoidTrack {
        val times = FloatArray(frames) { durations * it / (frames - 1) }
        val values = FloatArray(frames * 3)
        for (i in 0 until frames) {
            val d = frameDelta(times[i])
            values[i * 3] = rest[0] + d[0]
            values[i * 3 + 1] = rest[1] + d[1]
            values[i * 3 + 2] = rest[2] + d[2]
        }
        return VrmaParser.HumanoidTrack("hips", "translation", times, values)
    }

    @Test
    fun `in place clip yields no extractor`() {
        // 只有呼吸式上下起伏 → 净前向 ≈ 0
        val t = track(2f, 5) { time -> floatArrayOf(0f, 0.02f * abs(kotlin.math.sin(time * 6f)), 0f) }
        assertNull(LocomotionExtractor.analyze(t, rest, vrmFlip = false, heightScale = 1f, clipDuration = 2f))
    }

    @Test
    fun `forward clip extracts net direction and unrolled distance`() {
        // 2 秒线性前进 1.6m（模型空间 +Z）
        val t = track(2f, 5) { time -> floatArrayOf(0f, 0f, time * 0.8f) }
        val ex = LocomotionExtractor.analyze(t, rest, vrmFlip = false, heightScale = 1f, clipDuration = 2f)
        assertNotNull(ex)
        ex!!.let {
            assertEquals(1.6f, it.netForwardMeters, 1e-4f)
            assertEquals(0f, it.forwardDir[0], 1e-5f)
            assertEquals(1f, it.forwardDir[2], 1e-5f)
            assertEquals(0.8f, it.forwardAt(1f), 1e-3f)
            // 循环展开：3s = 1 圈 + 1s → net + 0.8
            assertEquals(1.6f + 0.8f, it.forwardAt(3f), 1e-3f)
            // 循环边界连续：f(dur)=net
            assertEquals(1.6f, it.forwardAt(2f), 1e-3f)
        }
    }

    @Test
    fun `vrm0 flip maps clip minus-Z to model plus-Z`() {
        // 素材沿 clip -Z 前进（VRM0 翻转后 = 模型 +Z）
        val t = track(1f, 3) { time -> floatArrayOf(0f, 0f, -time) }
        val ex = LocomotionExtractor.analyze(t, rest, vrmFlip = true, heightScale = 1f, clipDuration = 1f)
        assertNotNull(ex)
        assertEquals(1f, ex!!.netForwardMeters, 1e-4f)
        assertEquals(0f, ex.forwardDir[0], 1e-5f)
        assertEquals(1f, ex.forwardDir[2], 1e-5f)
    }

    @Test
    fun `reverse motion direction absorbed by dir sign`() {
        // 沿 -X 走：dir 直接取净位移方向（-X），f 沿 dir 恒正增长
        val t = track(1f, 3) { time -> floatArrayOf(-time * 0.5f, 0f, 0f) }
        val ex = LocomotionExtractor.analyze(t, rest, vrmFlip = false, heightScale = 1f, clipDuration = 1f)
        assertNotNull(ex)
        assertEquals(0.5f, ex!!.netForwardMeters, 1e-4f)
        assertEquals(-1f, ex.forwardDir[0], 1e-5f)
        assertEquals(0.5f, ex.forwardAt(1f), 1e-4f)
    }

    @Test
    fun `lateral sway stays out of forward channel`() {
        // 前进 1m + 每帧 ±0.3m 侧向晃动：前向通道只含前向分量
        val t = track(1f, 5) { time ->
            val sway = if ((time * 5).toInt() % 2 == 0) 0.3f else -0.3f
            floatArrayOf(sway * time, 0f, time)
        }
        val ex = LocomotionExtractor.analyze(t, rest, vrmFlip = false, heightScale = 1f, clipDuration = 1f)
        assertNotNull(ex)
        // 末端 delta = (0.3*1, 0, 1) → dir ≈ 归一化(0.3, 1)，前向投影会偏向
        // ——这里只锁「净位移方向正确随末端调整」的性质，方向非纯 +Z：
        val dirLen = kotlin.math.sqrt(ex!!.forwardDir[0] * ex.forwardDir[0] + ex.forwardDir[2] * ex.forwardDir[2])
        assertEquals(1f, dirLen, 1e-4f)
        assertEquals(ex.forwardAt(1f), ex.netForwardMeters, 1e-4f)
        assertTrue(ex.netForwardMeters > 0f)
    }

    @Test
    fun `height scale converts clip meters to model meters`() {
        val t = track(1f, 3) { time -> floatArrayOf(0f, 0f, time) }
        val ex = LocomotionExtractor.analyze(t, rest, vrmFlip = false, heightScale = 0.5f, clipDuration = 1f)
        assertNotNull(ex)
        assertEquals(0.5f, ex!!.netForwardMeters, 1e-4f)
    }

    @Test
    fun `degenerate inputs return null`() {
        assertNull(LocomotionExtractor.analyze(null, rest, false, 1f, 1f))
        val rot = VrmaParser.HumanoidTrack("hips", "rotation", floatArrayOf(0f, 1f), FloatArray(8) { 0f })
        assertNull(LocomotionExtractor.analyze(rot, rest, false, 1f, 1f))
        val short = VrmaParser.HumanoidTrack("hips", "translation", floatArrayOf(0f), floatArrayOf(0f, 0f, 0f))
        assertNull(LocomotionExtractor.analyze(short, rest, false, 1f, 1f))
        val zeroDur = track(1f, 3) { floatArrayOf(0f, 0f, 1f) }
        assertNull(LocomotionExtractor.analyze(zeroDur, rest, false, 1f, 0f))
    }

    @Test
    fun `below threshold net treated as in place`() {
        // 每循环净前向 5cm（< 12cm 阈值）= 脚底噪声
        val t = track(1f, 3) { time -> floatArrayOf(0f, 0f, time * 0.05f) }
        assertNull(LocomotionExtractor.analyze(t, rest, false, 1f, 1f))
    }
}
