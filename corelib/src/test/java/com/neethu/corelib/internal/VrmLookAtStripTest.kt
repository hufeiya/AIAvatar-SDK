package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视线叠加层的"先剥再叠"记账纯函数（眼骨逐帧累积→只剩眼白的真机元凶，
 * 见 docs/ai-layer-handoff.md A.1 第 24/25 条）。
 */
class VrmLookAtStripTest {

    private val identity = floatArrayOf(0f, 0f, 0f, 1f)

    private fun offsetQuat(): FloatArray =
        GazeMath.quatFromTo(
            floatArrayOf(0f, 0f, 1f),
            GazeMath.normalize3(floatArrayOf(0.3f, 0.2f, 0.9f)),
        )

    /** 任一非平凡单位四元数（测试基座姿态用）。 */
    private fun someQuat(x: Float, y: Float, z: Float): FloatArray =
        GazeMath.quatFromTo(floatArrayOf(0f, 0f, 1f), GazeMath.normalize3(floatArrayOf(x, y, z)))

    @Test
    fun `baked offset is stripped back to the clean base`() {
        val animPose = someQuat(0.1f, 0.9f, 0.4f)
        val k = offsetQuat()
        val written = GazeMath.quatMultiply(k, animPose)

        val (base, stripped) = VrmLookAtEngine.stripPreviousWrite(written, written, k)
        assertTrue(stripped)
        // 剥回的是动画基座（带符号的旋转等价：作用在探测向量上逐分量比对）
        val probe = floatArrayOf(0.4f, -0.2f, 0.9f)
        val got = GazeMath.rotateVector(base, probe)
        val want = GazeMath.rotateVector(animPose, probe)
        for (i in want.indices) assertEquals(want[i], got[i], 1e-4f)
    }

    @Test
    fun `animation-rewritten pose is not stripped`() {
        val k = offsetQuat()
        val written = GazeMath.quatMultiply(k, identity)
        // 动画本帧写了一个明显不同的姿态（差 ≥ 偏移量，远超剥离阈值）
        val rewritten = someQuat(0.8f, 0.1f, 0.6f)
        val (base, stripped) = VrmLookAtEngine.stripPreviousWrite(rewritten, written, k)
        assertFalse(stripped)
        assertTrue(base.contentEquals(rewritten))
    }

    @Test
    fun `no previous write is a no-op`() {
        val cur = someQuat(0.2f, 0.8f, 0.5f)
        val (base, stripped) = VrmLookAtEngine.stripPreviousWrite(cur, null, null)
        assertFalse(stripped)
        assertTrue(base.contentEquals(cur))
    }

    @Test
    fun `identity offset strips without marking`() {
        val pose = someQuat(0.1f, 0.7f, 0.7f)
        val (base, stripped) = VrmLookAtEngine.stripPreviousWrite(pose, pose, identity)
        assertFalse("identity 偏移没有可剥的东西", stripped)
        assertTrue(GazeMath.quatAngle(base, pose) < 1e-5f)
    }

    @Test
    fun `quatAngle is sign agnostic and bounded`() {
        val q = offsetQuat()
        assertEquals(0f, GazeMath.quatAngle(q, q), 1e-4f)
        val neg = FloatArray(4) { -q[it] }
        assertEquals(0f, GazeMath.quatAngle(q, neg), 1e-4f)
        // 90° 旋转对
        val q90 = GazeMath.quatFromTo(floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 0f))
        assertEquals((Math.PI / 2).toFloat(), GazeMath.quatAngle(q90, identity), 1e-4f)
    }
}
