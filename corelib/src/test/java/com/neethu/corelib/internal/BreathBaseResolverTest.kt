package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 呼吸层基座判定双信号的回归测试。核心场景=真机锁死陷阱（Python 仿真
 * tools/breath_sim 复现）：待机动画每帧把骨骼重置回基座 B，strip 单信号把 B
 * 误判为「我的写入」（小偏移时恒成立），base 被算成 inv(LO_prev)·B，渲染永远
 * 只剩单帧增量 → 99% 静止 + 偶发微抽动。修复=动画分支前快照，重写必现则绝不 strip。
 */
class BreathBaseResolverTest {

    // 工具：绕 x 轴转 deg 的四元数（呼吸俯仰轴）
    private fun pitch(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble()).toFloat()
        return floatArrayOf(sin(r / 2), 0f, 0f, cos(r / 2))
    }

    // 「待机动画写出的基座姿势」——任意非平凡单位四元数
    private val bPose = normalize(floatArrayOf(0.0923f, 0.0061f, -0.0283f, 0.9954f))

    private fun normalize(q: FloatArray): FloatArray {
        val n = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
    }

    private fun mul(a: FloatArray, b: FloatArray) = GazeMath.quatMultiply(a, b)
    private fun inv(q: FloatArray) = GazeMath.quatInverse(q)
    private fun angleDeg(a: FloatArray, b: FloatArray) =
        Math.toDegrees(GazeMath.quatAngle(a, b).toDouble())

    @Test
    fun `动画重写过时绝不 strip——锁死陷阱回归`() {
        val b = bPose
        val lo = pitch(5f)                       // 上一帧的偏移
        val myWrite = mul(lo, b)                 // 我上一帧写出的局部
        val preAnim = myWrite                    // 动画分支前的快照=我的写入
        val current = b                          // 动画本帧重写回基座
        val lastOffset = lo

        val (base, stripped) = BreathBaseResolver.resolve(
            current, preAnim, myWrite, lastOffset,
        )
        assertFalse("动画重写过，绝不能 strip", stripped)
        // base 必须等于动画新姿势（12° 级偏移场景：旧算法会得到 inv(lo)·b，
        // 与 base 差 5°——此处逐位断言相等）
        assertEquals(0.0, angleDeg(base, current), 1e-6)
    }

    @Test
    fun `小偏移锁死场景——旧算法会误 strip 而新算法不会`() {
        val b = bPose
        val lo = pitch(0.2f)                     // 吸气初期：偏移小于 strip 阈值
        val myWrite = mul(lo, b)
        val preAnim = myWrite
        val current = b                          // 动画重置回基座

        // 旧单信号（无快照）：strip 误触发 → base=inv(lo)·b（污染，与 current
        // 差一个偏移角）→ 写分支用它叠出 lo_new·inv(lo_prev)·b ≈ b + 增量 = 锁死
        val (oldBase, _) = VrmLookAtEngine.stripPreviousWrite(current, myWrite, lo)
        assertTrue(angleDeg(oldBase, current) > 0.1)

        // 新双信号：base == current，偏移从动画基座完整叠加
        val (base, stripped) = BreathBaseResolver.resolve(current, preAnim, myWrite, lo)
        assertFalse(stripped)
        assertEquals(0.0, angleDeg(base, current), 1e-6)
    }

    @Test
    fun `未重写且骨骼保持我的写入时正确 strip 旧偏移`() {
        val b = bPose
        val lo = pitch(6f)
        val base = b                             // 干净基座（rest pose 定格）
        val myWrite = mul(lo, b)
        val preAnim = myWrite                    // 没有动画：快照=我的写入（未重写）
        val current = myWrite                    // 骨骼一直保持我上次的写入

        val (resolved, stripped) = BreathBaseResolver.resolve(
            current, preAnim, myWrite, lo,
        )
        assertTrue(stripped)
        assertEquals(0.0, angleDeg(resolved, base), 1e-4)
    }

    @Test
    fun `未重写且无历史时不写`() {
        val cur = pitch(3f)
        val (base, stripped) = BreathBaseResolver.resolve(cur, cur, null, null)
        assertFalse(stripped)
        assertEquals(0.0, angleDeg(base, cur), 1e-6)
    }

    @Test
    fun `快照为 null 时退回单信号 strip（兼容首帧前）`() {
        val b = bPose
        val lo = pitch(5f)
        val myWrite = mul(lo, b)
        val (base, stripped) = BreathBaseResolver.resolve(myWrite, null, myWrite, lo)
        assertTrue(stripped)
        assertEquals(0.0, angleDeg(base, bPose), 1e-4)
    }

    @Test
    fun `重写判定阈值以下视为未重写（浮点噪声容忍）`() {
        val b = bPose
        // 快照与 current 差 ~0.005°（< REWRITE_EPS_RAD=1e-4 rad≈0.0057°）
        val tiny = pitch(0.005f)
        val current = mul(tiny, b)
        val (base, stripped) = BreathBaseResolver.resolve(current, b, null, null)
        assertFalse(stripped)
        assertEquals(0.0, angleDeg(base, current), 1e-6)
    }

    @Test
    fun `完整帧序列仿真——修复后偏移能爬升到全幅（锁死不复现）`() {
        val b = bPose
        var bone = b
        var lastWritten: FloatArray? = null
        var lastOffset: FloatArray? = null
        var preAnim: FloatArray? = null
        val degToRad = (Math.PI / 180.0).toFloat()
        var maxRendered = 0f
        // 60fps × 8s：待机每帧重写 B + 正弦呼吸（BreathWave 简化内联）
        for (i in 0 until 480) {
            preAnim = bone                       // 动画前快照
            bone = b                             // 动画重写
            val u = (i * (1f / 60f) * 0.25f) % 1f
            val a = if (u < 0.4f) sin(u / 0.4f * (Math.PI / 2).toFloat())
                    else (1f + cos(Math.PI.toFloat() * (u - 0.4f) / 0.6f)) / 2f
            val mat = FloatArray(16)
            GazeMath.quatToMat(bone, mat)
            val cur = GazeMath.matToQuat(mat)
            val (base, _) = BreathBaseResolver.resolve(cur, preAnim, lastWritten, lastOffset)
            val lo = pitch(12f * a)
            val lnew = mul(lo, base)
            bone = lnew
            lastWritten = lnew
            lastOffset = lo
            maxRendered = maxOf(maxRendered, angleDeg(bone, b).toFloat())
        }
        // 修复前 max≈0.2°（锁死）；修复后必须到达全幅 12°
        assertTrue("偏移未爬升到全幅（max=$maxRendered°），锁死未修复", maxRendered > 11f)
    }
}
