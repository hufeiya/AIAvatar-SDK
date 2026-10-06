package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模仿我」骨骼解算纯函数层的已知答案单测：锁死核心公式
 * `L' = inv(P)·D·P·L`（世界系最短弧→局部写入）与退场缓动的 nlerp。
 * 断言一律「旋转后探一根探针方向」而非逐分量比四元数（等价类 ±q + float32
 * 归一化噪声，逐分量精确比对会假失败）。
 */
class PoseMimicSolverTest {

    private val identityQ = floatArrayOf(0f, 0f, 0f, 1f)

    private fun rad(deg: Float): Float = Math.toRadians(deg.toDouble()).toFloat()
    private fun deg(r: Float): Float = Math.toDegrees(r.toDouble()).toFloat()

    /** 把旋转 [q] 作用在探针 [probe] 上返回单位方向。 */
    private fun spin(q: FloatArray, probe: FloatArray): FloatArray =
        GazeMath.normalize3(GazeMath.rotateVector(q, probe))

    private fun assertVecNear(expected: FloatArray, actual: FloatArray, eps: Float = 1e-3f) {
        for (i in 0..2) assertEquals("axis[$i]", expected[i], actual[i], eps)
    }

    @Test
    fun `driveRotation builds the shortest-arc rotation in local space when parent is identity`() {
        val result = PoseMimicSolver.driveRotation(
            dCurrent = floatArrayOf(1f, 0f, 0f),
            dTarget = floatArrayOf(0f, 1f, 0f),
            parentQ = identityQ,
            currentLocal = identityQ,
            writeEps = 0.002f,
        )
        assertNotNull(result)
        // 局部 +X 方向被驱动后精确到达目标（父系无旋转，局部=世界）
        assertVecNear(floatArrayOf(0f, 1f, 0f), spin(result!!, floatArrayOf(1f, 0f, 0f)))
        // 最短弧：绕 +Z（不绕远路）
        val axis = GazeMath.normalize3(
            floatArrayOf(
                result[1] * 0f - result[2] * 0f, // 从四元数读轴
                0f, 0f,
            ).let { floatArrayOf(result[0], result[1], result[2]) },
        )
        assertEquals(0f, axis[0], 1e-3f)
        assertEquals(0f, axis[1], 1e-3f)
        assertTrue(axis[2] > 0f)
    }

    @Test
    fun `driveRotation respects the parent frame via the similarity transform`() {
        // 父骨骼绕 +Y 转 90°；骨骼局部 limb 方向 = 局部 +Z（则世界 limb 方向
        // = P·+Z = −Z...验证相似变换端到端：驱动后世界 limb 方向精确到达目标）
        val parent = GazeMath.quatFromTo(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f))
        val localLimb = floatArrayOf(0f, 0f, 1f)
        val dCurrent = spin(parent, localLimb) // 本帧动画刚写完的世界 limb 方向
        val dTarget = floatArrayOf(0f, 1f, 0f)
        val result = PoseMimicSolver.driveRotation(
            dCurrent = dCurrent,
            dTarget = dTarget,
            parentQ = parent,
            currentLocal = identityQ,
            writeEps = 0.002f,
        )!!
        // 新世界旋转 Q' = P·L'：局部 limb 方向应精确到达世界目标
        val newWorld = GazeMath.quatMultiply(parent, result)
        assertVecNear(dTarget, spin(newWorld, localLimb))
    }

    @Test
    fun `driveRotation composes onto the current local rotation`() {
        // 骨骼当前局部已把 +X 摆到 +Y（世界 limb=+Y），目标是 +X：驱动后世界=+X
        val currentLocal = GazeMath.quatFromTo(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        val result = PoseMimicSolver.driveRotation(
            dCurrent = floatArrayOf(0f, 1f, 0f),
            dTarget = floatArrayOf(1f, 0f, 0f),
            parentQ = identityQ,
            currentLocal = currentLocal,
            writeEps = 0.002f,
        )!!
        assertVecNear(floatArrayOf(1f, 0f, 0f), spin(result, floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun `driveRotation skips writes below the deadzone`() {
        // 0.1° 变化 < 0.002 rad 死区 → 跳写
        val tiny = GazeMath.dirFromYawPitch(floatArrayOf(0f, 0f, 1f), rad(0.1f), 0f)
        assertNull(
            PoseMimicSolver.driveRotation(
                floatArrayOf(0f, 0f, 1f), tiny, identityQ, identityQ, 0.002f,
            ),
        )
        // 5° 变化照常驱动
        val big = GazeMath.dirFromYawPitch(floatArrayOf(0f, 0f, 1f), rad(5f), 0f)
        assertNotNull(
            PoseMimicSolver.driveRotation(
                floatArrayOf(0f, 0f, 1f), big, identityQ, identityQ, 0.002f,
            ),
        )
    }

    @Test
    fun `headYawPitch clamps to the natural range`() {
        val forward = floatArrayOf(0f, 0f, 1f)
        val maxYaw = rad(55f)
        val maxPitch = rad(35f)
        // 正前方 → 零偏移
        val (y0, p0) = PoseMimicSolver.headYawPitch(forward, forward, maxYaw, maxPitch)
        assertEquals(0f, y0, 1e-5f)
        assertEquals(0f, p0, 1e-5f)
        // 正右方目标 → yaw 拉满 55°
        val (y1, _) = PoseMimicSolver.headYawPitch(forward, floatArrayOf(1f, 0f, 0f), maxYaw, maxPitch)
        assertEquals(55f, deg(y1), 0.1f)
        // 正上方目标 → pitch 拉满 35°
        val (_, p2) = PoseMimicSolver.headYawPitch(forward, floatArrayOf(0f, 1f, 0f), maxYaw, maxPitch)
        assertEquals(35f, deg(p2), 0.1f)
        // 小偏移原样透传
        val small = GazeMath.dirFromYawPitch(forward, rad(10f), rad(5f))
        val (y3, p3) = PoseMimicSolver.headYawPitch(forward, small, maxYaw, maxPitch)
        assertEquals(10f, deg(y3), 0.1f)
        assertEquals(5f, deg(p3), 0.1f)
    }

    @Test
    fun `quatNlerp endpoints, shortest arc and normalization`() {
        val a = floatArrayOf(0f, 0f, 0f, 1f)
        val b = GazeMath.quatFromTo(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        // 端点：t=0 精确等于 a；t=1 旋转语义等于 b（float32 归一化噪声用探针断言）
        assertTrue(GazeMath.quatNlerp(a, b, 0f).contentEquals(a))
        assertVecNear(
            floatArrayOf(0f, 1f, 0f),
            spin(GazeMath.quatNlerp(a, b, 1f), floatArrayOf(1f, 0f, 0f)),
        )
        // 中点：+X 旋转一半（45°）
        val mid = GazeMath.quatNlerp(a, b, 0.5f)
        assertEquals(
            kotlin.math.sin(Math.toRadians(45.0)).toFloat(),
            spin(mid, floatArrayOf(1f, 0f, 0f))[1],
            1e-3f,
        )
        // 单位长度
        val len = kotlin.math.sqrt(
            (mid[0] * mid[0] + mid[1] * mid[1] + mid[2] * mid[2] + mid[3] * mid[3]).toDouble(),
        ).toFloat()
        assertEquals(1f, len, 1e-5f)
        // 最短弧：−b 与 b 同一旋转，nlerp 不应绕远路
        val nb = floatArrayOf(-b[0], -b[1], -b[2], -b[3])
        val mid2 = GazeMath.quatNlerp(a, nb, 0.5f)
        assertTrue(spin(mid2, floatArrayOf(1f, 0f, 0f))[1] > 0f) // 仍走 +45° 而不是 −135°
    }
}

// ── P2：躯干欧拉分解 / 肩线倾滚 / 轴角四元数 ─────────────────────────────

class PoseMimicTorsoSolverTest {

    private fun rad(deg: Float): Float = Math.toRadians(deg.toDouble()).toFloat()
    private fun deg(r: Float): Float = Math.toDegrees(r.toDouble()).toFloat()

    private fun assertVecNear(expected: FloatArray, actual: FloatArray, eps: Float = 1e-3f) {
        for (i in 0..2) assertEquals("axis[$i]", expected[i], actual[i], eps)
    }

    @Test
    fun `upright axis and level line yield zero angles`() {
        val (p, r, y) = PoseMimicSolver.torsoAngles(
            floatArrayOf(0f, 1f, 0f), floatArrayOf(1f, 0f, 0f),
        )
        assertEquals(0f, p, 1e-4f)
        assertEquals(0f, r, 1e-4f)
        assertEquals(0f, y, 1e-4f)
    }

    @Test
    fun `forward-leaning axis yields positive pitch`() {
        // 躯干轴朝观察者倾 30°（top toward +Z_A）
        val axis = GazeMath.normalize3(floatArrayOf(0f, kotlin.math.cos(rad(30f)), kotlin.math.sin(rad(30f))))
        val (pitch, roll, yaw) = PoseMimicSolver.torsoAngles(axis, floatArrayOf(1f, 0f, 0f))
        assertEquals(30f, deg(pitch), 0.1f)
        assertEquals(0f, roll, 1e-4f)
        assertEquals(0f, yaw, 1e-4f)
    }

    @Test
    fun `sideways-leaning axis yields roll and tilted line yields tilt`() {
        val axis = GazeMath.normalize3(floatArrayOf(kotlin.math.sin(rad(20f)), kotlin.math.cos(rad(20f)), 0f))
        val (_, roll, _) = PoseMimicSolver.torsoAngles(axis, floatArrayOf(1f, 0f, 0f))
        assertEquals(20f, deg(roll), 0.1f)
        // 肩线左端抬起 10°
        val line = GazeMath.normalize3(floatArrayOf(kotlin.math.cos(rad(10f)), kotlin.math.sin(rad(10f)), 0f))
        assertEquals(10f, deg(PoseMimicSolver.shoulderLineTilt(line)), 0.1f)
    }

    @Test
    fun `rotated shoulder line yields yaw`() {
        // 肩线绕 +Y 转 −30°（左肩端朝观察者）
        val line = GazeMath.normalize3(
            GazeMath.rotateVector(GazeMath.axisAngleQuat(floatArrayOf(0f, 1f, 0f), rad(-30f)), floatArrayOf(1f, 0f, 0f)),
        )
        val (_, _, yaw) = PoseMimicSolver.torsoAngles(floatArrayOf(0f, 1f, 0f), line)
        assertEquals(-30f, deg(yaw), 0.1f)
    }

    @Test
    fun `axisAngleQuat round-trips a probe vector`() {
        val q = GazeMath.axisAngleQuat(floatArrayOf(0f, 0f, 1f), rad(90f))
        assertVecNear(floatArrayOf(0f, 1f, 0f), GazeMath.normalize3(GazeMath.rotateVector(q, floatArrayOf(1f, 0f, 0f))))
        // 零角 → 单位四元数
        val identity = GazeMath.axisAngleQuat(floatArrayOf(0f, 1f, 0f), 0f)
        assertEquals(0f, identity[0], 1e-6f)
        assertEquals(0f, identity[1], 1e-6f)
        assertEquals(0f, identity[2], 1e-6f)
        assertEquals(1f, identity[3], 1e-6f)
    }

    @Test
    fun `applyWorldRotation composes with parent like driveRotation`() {
        val parent = GazeMath.quatFromTo(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 0f, -1f))
        val world = GazeMath.axisAngleQuat(floatArrayOf(0f, 0f, 1f), rad(90f))
        val l = PoseMimicSolver.applyWorldRotation(world, parent, floatArrayOf(0f, 0f, 0f, 1f))
        val newWorld = GazeMath.quatMultiply(parent, l)
        // 探针 +Y：P（绕 +Y）不动它，随后世界 Rz(90°) 把它带到 −X
        assertVecNear(
            floatArrayOf(-1f, 0f, 0f),
            GazeMath.normalize3(GazeMath.rotateVector(newWorld, floatArrayOf(0f, 1f, 0f))),
        )
    }
}
