package com.neethu.corelib.stage

import com.neethu.corelib.stage.internal.SpawnMath
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * spawn 矩阵合成的纯数学锁（docs/multi-avatar-feasibility.md P0 单测项：
 * 「spawn 矩阵/双 runtime 状态隔离」）。
 *
 * 列主序 4×4（TransformManager 约定）：[0..2]=第 0 列，[12..14]=平移列。
 */
class SpawnMathTest {

    private fun identity() = floatArrayOf(
        1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
    )

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-4f) {
        if (abs(expected - actual) > eps) throw AssertionError("expected $expected got $actual")
    }

    @Test
    fun `zero spawn is a no-op`() {
        val mat = identity()
        SpawnMath.applySpawn(mat, 0f, 0f, 0f, 0f)
        assertEquals(1f, mat[0], 1e-6f)
        assertEquals(0f, mat[12], 1e-6f)
        assertEquals(0f, mat[13], 1e-6f)
        assertEquals(0f, mat[14], 1e-6f)
    }

    @Test
    fun `translation offsets the pivot column only`() {
        val mat = identity()
        SpawnMath.applySpawn(mat, x = -0.8f, y = 0.1f, z = -3.2f, yawDeg = 0f)
        assertClose(-0.8f, mat[12])
        assertClose(0.1f, mat[13])
        assertClose(-3.2f, mat[14])
        // 3×3 不动
        assertEquals(1f, mat[0], 1e-6f)
        assertEquals(0f, mat[2], 1e-6f)
    }

    @Test
    fun `yaw rotates the basis and preserves scale`() {
        // unit-cube 后的典型根矩阵：均匀缩放 2（对角），无旋转
        val s = 2f
        val mat = floatArrayOf(
            s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, s, 0f, 1f, 0f, -4f, 1f,
        )
        val yaw = 90f
        SpawnMath.applySpawn(mat, 0f, 0f, 0f, yaw)
        val rad = Math.toRadians(90.0)
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        // 第 0 列 (s,0,0) → (c·s, 0, −s·s)
        assertClose(c * s, mat[0])
        assertClose(0f, mat[1])
        assertClose(-sn * s, mat[2])
        // 第 2 列 (0,0,s) → (sn·s, 0, c·s)
        assertClose(sn * s, mat[8])
        assertClose(c * s, mat[10])
        // 平移列不动
        assertClose(1f, mat[12])
        assertClose(-4f, mat[14])
        // 正交性：列长度保持
        assertClose(s, kotlin.math.sqrt(mat[0] * mat[0] + mat[2] * mat[2]), 1e-3f)
        assertClose(s, kotlin.math.sqrt(mat[8] * mat[8] + mat[10] * mat[10]), 1e-3f)
    }

    @Test
    fun `yaw 360 is identity rotation`() {
        val mat = identity()
        SpawnMath.applySpawn(mat, 0f, 0f, 0f, 360f)
        assertEquals(1f, mat[0], 1e-3f)
        assertEquals(0f, mat[2], 1e-3f)
    }

    @Test
    fun `spawn composes additively on repeated calls`() {
        val mat = identity()
        SpawnMath.applySpawn(mat, 1f, 0f, 0f, 0f)
        SpawnMath.applySpawn(mat, 0f, 0f, -2f, 0f)
        assertClose(1f, mat[12])
        assertClose(-2f, mat[14])
    }

    @Test
    fun `unit cube transform maps bbox center to the stage center point`() {
        // 包围盒中心 (0, 1, 0)，半 extents (0.5, 1, 0.5) → 最长边 2 → s = 1
        val mat = SpawnMath.unitCubeTransform(
            centerX = 0f, centerY = 1f, centerZ = 0f,
            halfExtentX = 0.5f, halfExtentY = 1f, halfExtentZ = 0.5f,
            centerPointX = 0f, centerPointY = 0f, centerPointZ = -4f,
        )
        // 缩放 = 1/maxHalf = 1
        assertClose(1f, mat[0])
        assertClose(1f, mat[5])
        // 平移 = centerPoint − center·s = (0, −1, −4)
        assertClose(0f, mat[12])
        assertClose(-1f, mat[13])
        assertClose(-4f, mat[14])
        // 语义验证：中心点变换结果 = s·center + t = (0,0,−4) ✓
        val cx = mat[0] * 0f + mat[12]
        val cy = mat[5] * 1f + mat[13]
        val cz = mat[10] * 0f + mat[14]
        assertClose(0f, cx)
        assertClose(0f, cy)
        assertClose(-4f, cz)
    }

    @Test
    fun `unit cube scales small models up to fill the cube`() {
        // 0.1 米的小模型：halfExtent 全 0.05 → s = 1/0.05 = 20
        val mat = SpawnMath.unitCubeTransform(
            centerX = 0f, centerY = 0.05f, centerZ = 0f,
            halfExtentX = 0.05f, halfExtentY = 0.05f, halfExtentZ = 0.05f,
        )
        assertClose(20f, mat[0])
        assertClose(20f, mat[5])
        assertClose(20f, mat[10])
        // 中心 (0,0.05,0) 映到 (0,0,−4)：t_y = 0 − 0.05·20 = −1
        assertClose(-1f, mat[13])
        assertClose(-4f, mat[14])
    }

    @Test
    fun `degenerate bbox is clamped and does not blow up`() {
        val mat = SpawnMath.unitCubeTransform(0f, 0f, 0f, 0f, 0f, 0f)
        // maxHalf clamp 到 1e-6 → s = 1e6（有限值，不 NaN/Inf）
        assert(!mat[0].isInfinite() && !mat[0].isNaN())
    }
}
