package com.neethu.corelib.accessory

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 配饰局部 TRS 的纯数学锁（docs/accessory-feasibility.md §5.2 第 6 步：
 * 「写一次局部 TRS」的矩阵语义）。
 *
 * 列主序 4×4（TransformManager 约定）：[0..2]=第 0 列，[12..14]=平移列。
 */
class AccessoryMathTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-4f) {
        if (abs(expected - actual) > eps) throw AssertionError("expected $expected got $actual")
    }

    /** 列主序 4×4 变换一个点（w=1）。 */
    private fun apply(mat: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        mat[0] * x + mat[4] * y + mat[8] * z + mat[12],
        mat[1] * x + mat[5] * y + mat[9] * z + mat[13],
        mat[2] * x + mat[6] * y + mat[10] * z + mat[14],
    )

    @Test
    fun `identity rotation passes position through`() {
        val mat = AccessoryMath.localTransform(
            position = floatArrayOf(0.01f, 0.02f, 0.03f),
            rotationEulerYXZ = floatArrayOf(0f, 0f, 0f),
            scale = 1f,
        )
        assertClose(1f, mat[0]); assertClose(1f, mat[5]); assertClose(1f, mat[10])
        assertClose(0.01f, mat[12]); assertClose(0.02f, mat[13]); assertClose(0.03f, mat[14])
    }

    @Test
    fun `scale multiplies the basis but not the translation`() {
        val mat = AccessoryMath.localTransform(
            position = floatArrayOf(0.1f, 0f, 0f),
            rotationEulerYXZ = floatArrayOf(0f, 0f, 0f),
            scale = 2.5f,
        )
        assertClose(2.5f, mat[0]); assertClose(2.5f, mat[5]); assertClose(2.5f, mat[10])
        assertClose(0.1f, mat[12])
    }

    @Test
    fun `yaw 90 maps z axis to x axis`() {
        val mat = AccessoryMath.localTransform(
            position = FloatArray(3),
            rotationEulerYXZ = floatArrayOf(0f, 90f, 0f),
            scale = 1f,
        )
        val v = apply(mat, 0f, 0f, 1f)
        assertClose(1f, v[0]); assertClose(0f, v[1]); assertClose(0f, v[2])
    }

    @Test
    fun `yxz order applies z then x then y`() {
        // v=(0,1,0)：Rx(90°) → (0,0,1)，再 Ry(90°) → (1,0,0)。
        // 若顺序错成 ZYX（先 Ry 后 Rx）会得到 (0,0,1)，以此锁死 YXZ 语义。
        val mat = AccessoryMath.localTransform(
            position = FloatArray(3),
            rotationEulerYXZ = floatArrayOf(90f, 90f, 0f),
            scale = 1f,
        )
        val v = apply(mat, 0f, 1f, 0f)
        assertClose(1f, v[0]); assertClose(0f, v[1]); assertClose(0f, v[2])
    }

    @Test
    fun `full trs composes scale into rotated offset`() {
        // 局部点 (0,0,0.1)：先 scale 2 → (0,0,0.2)，再 Ry(90°) → (0.2,0,0)，加平移
        val mat = AccessoryMath.localTransform(
            position = floatArrayOf(0.01f, 0f, 0f),
            rotationEulerYXZ = floatArrayOf(0f, 90f, 0f),
            scale = 2f,
        )
        val v = apply(mat, 0f, 0f, 0.1f)
        assertClose(0.21f, v[0]); assertClose(0f, v[1]); assertClose(0f, v[2])
    }

    @Test
    fun `rotation preserves vector length`() {
        val mat = AccessoryMath.localTransform(
            position = FloatArray(3),
            rotationEulerYXZ = floatArrayOf(30f, -45f, 60f),
            scale = 1f,
        )
        val v = apply(mat, 0.3f, 0.4f, 0f)
        assertClose(0.5f, kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]))
    }

    @Test
    fun `rotationYXZ matches component formulas`() {
        // 与手推导出的分量式对拍（独立路径防转置/行主序混淆）
        val rx = Math.toRadians(20.0).toFloat()
        val ry = Math.toRadians(-35.0).toFloat()
        val rz = Math.toRadians(70.0).toFloat()
        val r = AccessoryMath.rotationYXZ(rx, ry, rz)
        val cx = cos(rx); val sx = sin(rx)
        val cy = cos(ry); val sy = sin(ry)
        val cz = cos(rz); val sz = sin(rz)
        assertEquals(cy * cz + sy * sx * sz, r[0], 1e-5f)
        assertEquals(cx * sz, r[3], 1e-5f)
        assertEquals(-sy * cz + cy * sx * sz, r[6], 1e-5f)
        assertEquals(sy * cx, r[2], 1e-5f)
        assertEquals(-sx, r[5], 1e-5f)
        assertEquals(cy * cx, r[8], 1e-5f)
    }

    // ── P1 §6.2 世界挂载 TRS：world = T(锚点)·Ry(yaw)·local ────────────────

    @Test
    fun `world mount identity - anchor lands at its own coordinates`() {
        val mat = AccessoryMath.worldTransform(
            mount = AccessoryWorldMount(1.9f, -0.932f, -2.5f),
            position = FloatArray(3),
            rotationEulerYXZ = FloatArray(3),
            scale = 1f,
        )
        assertClose(1.9f, mat[12]); assertClose(-0.932f, mat[13]); assertClose(-2.5f, mat[14])
        assertClose(1f, mat[0]); assertClose(1f, mat[5]); assertClose(1f, mat[10])
    }

    @Test
    fun `world mount yaw rotates the prop basis - tea tray turns with the anchor`() {
        val mat = AccessoryMath.worldTransform(
            mount = AccessoryWorldMount(1.5f, -0.164f, -6.43f, yawDeg = 180f),
            position = FloatArray(3),
            rotationEulerYXZ = FloatArray(3),
            scale = 1f,
        )
        // 托盘自身 +X 端（局部 (1,0,0)）转 180° 后指向世界 -X（锚点分量不动）
        val v = apply(mat, 1f, 0f, 0f)
        assertClose(1.5f - 1f, v[0]); assertClose(-0.164f, v[1]); assertClose(-6.43f, v[2])
    }

    @Test
    fun `world mount local offset rotates with yaw - not dropped or axis-fixed`() {
        // 局部微调 (0,0,0.1)（校准链的抬升/前移量）：yaw 0 = 原样前移;yaw 90 =
        // 转到 +X——锁死「局部偏移列随 Ry 一同旋转」而非只旋基向量
        val m0 = AccessoryMath.worldTransform(
            AccessoryWorldMount(0f, 0f, 0f), floatArrayOf(0f, 0f, 0.1f), FloatArray(3), 1f,
        )
        assertClose(0.1f, m0[14])
        val m90 = AccessoryMath.worldTransform(
            AccessoryWorldMount(0f, 0f, 0f, yawDeg = 90f), floatArrayOf(0f, 0f, 0.1f), FloatArray(3), 1f,
        )
        assertClose(0.1f, m90[12]); assertClose(0f, m90[14])
    }

    @Test
    fun `world mount scale composes - per-asset calibration still applies`() {
        val mat = AccessoryMath.worldTransform(
            AccessoryWorldMount(-1f, -1f, -1f), FloatArray(3), FloatArray(3), scale = 2f,
        )
        assertClose(2f, mat[0]); assertClose(2f, mat[5]); assertClose(2f, mat[10])
        assertClose(-1f, mat[12]); assertClose(-1f, mat[13]); assertClose(-1f, mat[14])
    }
}
