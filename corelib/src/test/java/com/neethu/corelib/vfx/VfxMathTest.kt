package com.neethu.corelib.vfx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * VfxMath 纯函数锁（docs/vfx-feasibility.md §4.7/§六 P0 单测清单）：
 * 基向量正交性 / Billboard 四角 / 自转 / 拉伸轴 / 曲线采样。
 */
class VfxMathTest {

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-4f) {
        if (abs(expected - actual) > eps) throw AssertionError("expected $expected got $actual")
    }

    private fun assertVec(ex: Float, ey: Float, ez: Float, v: FloatArray, eps: Float = 1e-4f) {
        assertClose(ex, v[0], eps); assertClose(ey, v[1], eps); assertClose(ez, v[2], eps)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    @Test
    fun `basis from look-at is orthonormal and right-handed`() {
        val right = FloatArray(3)
        val camUp = FloatArray(3)
        val eye = floatArrayOf(0f, 1.5f, 5f)
        val target = floatArrayOf(0f, 1f, 0f)
        val up = floatArrayOf(0f, 1f, 0f)
        VfxMath.basisFromLookAt(eye, target, up, right, camUp)
        val forward = floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2])
        assertClose(1f, kotlin.math.sqrt(dot(right, right)))
        assertClose(1f, kotlin.math.sqrt(dot(camUp, camUp)))
        assertClose(0f, dot(right, camUp))
        assertClose(0f, dot(right, forward))
        assertClose(0f, dot(camUp, forward))
        // 俯视相机：right=+X；camUp = right×forward = (0, −fz, fy)，随俯角前倾
        assertVec(1f, 0f, 0f, right)
        val fl = kotlin.math.sqrt(0.5f * 0.5f + 25f)
        assertVec(0f, 5f / fl, -0.5f / fl, camUp, eps = 1e-3f)
    }

    @Test
    fun `basis for a level camera is exactly x-y`() {
        val right = FloatArray(3)
        val camUp = FloatArray(3)
        VfxMath.basisFromLookAt(
            floatArrayOf(0f, 1f, 5f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 1f, 0f),
            right, camUp,
        )
        assertVec(1f, 0f, 0f, right)
        assertVec(0f, 1f, 0f, camUp)
    }

    @Test
    fun `basis degenerates without NaN when eye equals target`() {
        val right = FloatArray(3)
        val camUp = FloatArray(3)
        val same = floatArrayOf(1f, 2f, 3f)
        VfxMath.basisFromLookAt(same, same, floatArrayOf(0f, 1f, 0f), right, camUp)
        assertFalse(right[0].isNaN()); assertFalse(camUp[0].isNaN())
    }

    @Test
    fun `billboard corners span the quad with fixed uv`() {
        val out = FloatArray(VfxMath.FLOATS_PER_PARTICLE)
        VfxMath.writeParticle(
            out, 0,
            px = 10f, py = 20f, pz = 30f,
            r = floatArrayOf(1f, 0f, 0f),
            u = floatArrayOf(0f, 1f, 0f),
            halfW = 0.5f, halfH = 0.25f,
        )
        // V0 左下
        assertVec(9.5f, 19.75f, 30f, out, eps = 0f)
        // V1 右下
        assertVec(out[9], out[10], out[11], floatArrayOf(10.5f, 19.75f, 30f), 0f)
        // V2 右上
        assertVec(out[18], out[19], out[20], floatArrayOf(10.5f, 20.25f, 30f), 0f)
        // V3 左上
        assertVec(out[27], out[28], out[29], floatArrayOf(9.5f, 20.25f, 30f), 0f)
        // UV 四角
        assertClose(0f, out[3]); assertClose(0f, out[4])
        assertClose(1f, out[12]); assertClose(0f, out[13])
        assertClose(1f, out[21]); assertClose(1f, out[22])
        assertClose(0f, out[30]); assertClose(1f, out[31])
    }

    @Test
    fun `rotate basis spins the quad in screen plane`() {
        val r2 = FloatArray(3)
        val u2 = FloatArray(3)
        val halfPi = (Math.PI / 2).toFloat()
        VfxMath.rotateBasis(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), halfPi, r2, u2)
        assertVec(0f, 1f, 0f, r2)
        assertVec(-1f, 0f, 0f, u2)
        // 0 弧度 = 恒等
        VfxMath.rotateBasis(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), 0f, r2, u2)
        assertVec(1f, 0f, 0f, r2)
        assertVec(0f, 1f, 0f, u2)
    }

    @Test
    fun `stretch axes align long axis with velocity`() {
        val long = FloatArray(3)
        val width = FloatArray(3)
        VfxMath.stretchAxes(
            vx = 0f, vy = -2f, vz = 0f,
            forward = floatArrayOf(0f, 0f, -1f),
            right = floatArrayOf(1f, 0f, 0f),
            up = floatArrayOf(0f, 1f, 0f),
            outLong = long, outWidth = width,
        )
        assertVec(0f, -1f, 0f, long)
        assertVec(1f, 0f, 0f, width)
    }

    @Test
    fun `stretch axes fall back to billboard basis at zero velocity`() {
        val long = FloatArray(3)
        val width = FloatArray(3)
        VfxMath.stretchAxes(
            vx = 0f, vy = 0f, vz = 0f,
            forward = floatArrayOf(0f, 0f, -1f),
            right = floatArrayOf(1f, 0f, 0f),
            up = floatArrayOf(0f, 1f, 0f),
            outLong = long, outWidth = width,
        )
        assertVec(0f, 1f, 0f, long)
        assertVec(1f, 0f, 0f, width)
    }

    @Test
    fun `scalar curve clamps and interpolates segments`() {
        val flat = VfxMath.flattenScalarKeys(
            listOf(ScalarKey(0f, 1f), ScalarKey(0.5f, 3f), ScalarKey(1f, 0f)),
        )
        assertClose(1f, VfxMath.sampleCurve(flat, 0f))
        assertClose(1f, VfxMath.sampleCurve(flat, -1f))
        assertClose(0f, VfxMath.sampleCurve(flat, 2f))
        assertClose(2f, VfxMath.sampleCurve(flat, 0.25f))
        assertClose(3f, VfxMath.sampleCurve(flat, 0.5f))
        assertClose(1.5f, VfxMath.sampleCurve(flat, 0.75f))
    }

    @Test
    fun `color curve interpolates all four channels`() {
        val out = FloatArray(4)
        val flat = VfxMath.flattenColorKeys(
            listOf(
                ColorKey(0f, VfxColor(1f, 0f, 0f, 1f)),
                ColorKey(1f, VfxColor(0f, 1f, 0f, 0f)),
            ),
        )
        VfxMath.sampleColorCurve(flat, 0.25f, out)
        assertClose(0.75f, out[0]); assertClose(0.25f, out[1])
        assertClose(0f, out[2]); assertClose(0.75f, out[3])
        VfxMath.sampleColorCurve(flat, 0f, out)
        assertClose(1f, out[0]); assertClose(1f, out[3])
        VfxMath.sampleColorCurve(flat, 1f, out)
        assertClose(0f, out[0]); assertClose(0f, out[3])
    }

    @Test
    fun `stride constants match the interleaved layout`() {
        assertEquals(9, VfxMath.FLOATS_PER_VERTEX)
        assertEquals(36, VfxMath.FLOATS_PER_PARTICLE)
        assertEquals(36, VfxMath.STRIDE_BYTES)
        assertEquals(3, VfxMath.UV_OFFSET)
        assertEquals(5, VfxMath.COLOR_OFFSET)
        // 一个粒子的 4 顶点在 out 中紧密排列
        assertEquals(VfxMath.FLOATS_PER_VERTEX * 4, VfxMath.FLOATS_PER_PARTICLE)
        // 三角形索引模式 (0,1,2, 2,3,0) 的自检：每粒子 6 索引
        assertEquals(6, VfxMath.VERTICES_PER_PARTICLE * 6 / 4)
        assertTrue(cos(0f) == 1f && sin(0f) == 0f)
    }
}
