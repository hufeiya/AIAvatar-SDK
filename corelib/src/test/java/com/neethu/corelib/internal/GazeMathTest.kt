package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * GazeMath 的几何不变量测试——VrmLookAtEngine 的全部视线解算都建立在这些
 * 纯函数上，符号约定（yaw 绕世界 +Y、pitch 抬头为正）错了真机上只会表现成
 * "头反着转"，很难归因，所以锁死在单测里。
 */
class GazeMathTest {

    private val FWD_Z = floatArrayOf(0f, 0f, 1f) // 模型静止面朝相机 = 世界 +Z

    private fun assertVec(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
        for (i in expected.indices) {
            assertEquals("component $i", expected[i], actual[i], eps)
        }
    }

    @Test
    fun `yaw is positive toward +X and wraps to half-pi`() {
        val yaw = GazeMath.signedYawPitch(FWD_Z, floatArrayOf(1f, 0f, 0f))
        assertEquals(PI.toFloat() / 2f, yaw[0], 1e-5f)
        assertEquals(0f, yaw[1], 1e-5f)
    }

    @Test
    fun `yaw is negative toward -X`() {
        val yaw = GazeMath.signedYawPitch(FWD_Z, floatArrayOf(-1f, 0f, 0f))
        assertEquals(-PI.toFloat() / 2f, yaw[0], 1e-5f)
    }

    @Test
    fun `pitch is positive when the target is above the forward`() {
        val yawPitch = GazeMath.signedYawPitch(FWD_Z, floatArrayOf(0f, 0.5f, 0.8660254f))
        assertEquals(0f, yawPitch[0], 1e-5f)
        assertEquals((30.0 * PI / 180.0).toFloat(), yawPitch[1], 1e-5f)
    }

    @Test
    fun `yawPitch and dirFromYawPitch round-trip`() {
        // 任意方向（含俯仰与偏航组合）都必须精确往返，否则 clamped 角度
        // 重建出的注视方向会偏到别的位置
        val target = GazeMath.normalize3(floatArrayOf(0.6f, 0.35f, -0.4f))
        val yp = GazeMath.signedYawPitch(FWD_Z, target)
        val back = GazeMath.dirFromYawPitch(FWD_Z, yp[0], yp[1])
        assertVec(target, back, 1e-4f)
    }

    @Test
    fun `quatFromTo identity for equal vectors`() {
        val q = GazeMath.quatFromTo(FWD_Z, FWD_Z)
        assertVec(floatArrayOf(0f, 0f, 0f, 1f), q)
    }

    @Test
    fun `quatFromTo rotates forward onto the target`() {
        val dir = GazeMath.normalize3(floatArrayOf(0.3f, 0.2f, 0.9f))
        val q = GazeMath.quatFromTo(FWD_Z, dir)
        val rotated = GazeMath.normalize3(GazeMath.rotateVector(q, FWD_Z))
        assertVec(dir, rotated, 1e-4f)
    }

    @Test
    fun `quatFromTo handles the antiparallel edge case`() {
        val q = GazeMath.quatFromTo(FWD_Z, floatArrayOf(0f, 0f, -1f))
        val rotated = GazeMath.normalize3(GazeMath.rotateVector(q, FWD_Z))
        assertVec(floatArrayOf(0f, 0f, -1f), rotated, 1e-4f)
    }

    @Test
    fun `world delta converts into a parent frame and back`() {
        // L' = inv(P)·D·P·L 后，P·L' == D·P·L（四元数层面恒等），
        // 且作用在测试向量上时世界系旋转等价——applyOffset 的数学根基
        val parent = axisQuat(0.4f, 0.5f, 0.75f, 1.2f)
        val local = axisQuat(0.1f, -0.3f, 0.2f, 0.7f)
        val dWorld = GazeMath.quatFromTo(FWD_Z, GazeMath.normalize3(floatArrayOf(0.2f, 0.1f, 0.97f)))
        val probe = floatArrayOf(0.3f, -0.5f, 0.8f)

        val offset = GazeMath.quatMultiply(
            GazeMath.quatMultiply(GazeMath.quatInverse(parent), dWorld),
            parent,
        )
        val localNew = GazeMath.quatMultiply(offset, local)

        // q 与 -q 同一个旋转，四元数逐分量比对会被符号翻转干扰；
        // 直接作用在探测向量上比对（真不变量）
        val lhs = GazeMath.rotateVector(
            GazeMath.quatMultiply(parent, localNew),
            probe,
        )
        val base = GazeMath.quatMultiply(parent, local)
        val rhs = GazeMath.rotateVector(
            dWorld,
            GazeMath.rotateVector(base, probe),
        )
        assertVec(rhs, lhs)
    }

    @Test
    fun `signedYawPitch degenerates safely for vertical targets`() {
        // 目标在正上/正下方：水平投影为零，yaw 取 0 而不是 NaN
        val yp = GazeMath.signedYawPitch(FWD_Z, floatArrayOf(0f, 1f, 0f))
        assertEquals(0f, yp[0], 1e-6f)
        assertTrue(abs(yp[1] - PI.toFloat() / 2f) < 1e-5f)
    }

    /** Unit quaternion for a rotation of [angle] rad about the axis (x,y,z). */
    private fun axisQuat(x: Float, y: Float, z: Float, angle: Float): FloatArray {
        val v = GazeMath.normalize3(floatArrayOf(x, y, z))
        val s = kotlin.math.sin(angle / 2f)
        return floatArrayOf(v[0] * s, v[1] * s, v[2] * s, kotlin.math.cos(angle / 2f))
    }
}
