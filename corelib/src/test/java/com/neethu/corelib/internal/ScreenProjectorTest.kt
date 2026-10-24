package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * 投影对拍单测（docs/autonomy-feasibility.md §7 corelib 行）：手搭
 * lookAt + perspective 矩阵（列主序，OpenGL 惯例），验证中心/偏轴/
 * 背后/出锥四象限——与 Filament camera 读出的矩阵同布局同语义。
 */
class ScreenProjectorTest {

    /** 相机 eye=(0,0,5) 注视原点、up=+Y 的 view 矩阵（列主序）。 */
    private fun lookAtView(eye: FloatArray, target: FloatArray, up: FloatArray): FloatArray {
        fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
        fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
            a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
        )
        fun norm(a: FloatArray): FloatArray {
            val l = kotlin.math.sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble()).toFloat()
            return floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
        }
        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val z = norm(sub(eye, target)) // 相机 z 轴指向背后
        val x = norm(cross(up, z))
        val y = cross(z, x)
        return floatArrayOf(
            x[0], y[0], z[0], 0f,
            x[1], y[1], z[1], 0f,
            x[2], y[2], z[2], 0f,
            -dot(x, eye), -dot(y, eye), -dot(z, eye), 1f,
        )
    }

    /** 垂直 fov 的透视投影（列主序，OpenGL 惯例）。 */
    private fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = (1.0 / tan(Math.toRadians(fovYDeg / 2.0))).toFloat()
        return floatArrayOf(
            f / aspect, 0f, 0f, 0f,
            0f, f, 0f, 0f,
            0f, 0f, (far + near) / (near - far), -1f,
            0f, 0f, (2f * far * near) / (near - far), 0f,
        )
    }

    private val view = lookAtView(floatArrayOf(0f, 0f, 5f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
    private val proj = perspective(90f, 1f, 0.1f, 100f)

    @Test
    fun `画面正中点投影为屏幕中心`() {
        val uv = ScreenProjector.project(0f, 0f, 0f, view, proj)
        assertNotNull(uv)
        assertEquals(0.5f, uv!![0], 1e-4f)
        assertEquals(0.5f, uv[1], 1e-4f)
    }

    @Test
    fun `偏轴点按 fov 比例投影`() {
        // fovY=90°、距离 5 → 半视场 5m：x=+2.5 → ndc 0.5 → u=0.75；y=+2.5 → v=0.25
        val uv = ScreenProjector.project(2.5f, 2.5f, 0f, view, proj)
        assertNotNull(uv)
        assertEquals(0.75f, uv!![0], 1e-4f)
        assertEquals(0.25f, uv[1], 1e-4f)
    }

    @Test
    fun `相机背后的点返回 null`() {
        assertNull(ScreenProjector.project(0f, 0f, 10f, view, proj)) // 眼后 +Z
        assertNull(ScreenProjector.project(0f, 0f, 5.05f, view, proj))
    }

    @Test
    fun `视锥外的点返回 null`() {
        assertNull(ScreenProjector.project(6f, 0f, 0f, view, proj)) // x 超半视场
        assertNull(ScreenProjector.project(0f, -6f, 0f, view, proj))
        // 恰好贴边不裁
        assertNotNull(ScreenProjector.project(4.9f, 0f, 0f, view, proj))
    }

    @Test
    fun `旋转视角后前方点仍居中`() {
        // 相机从 +Z 绕到 +X：eye=(5,0,0) 注视原点
        val v = lookAtView(floatArrayOf(5f, 0f, 0f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        val uv = ScreenProjector.project(0f, 0f, 0f, v, proj)
        assertNotNull(uv)
        assertEquals(0.5f, uv!![0], 1e-4f)
        assertEquals(0.5f, uv[1], 1e-4f)
        // 原世界 +Z 方向（相机 forward=(-1,0,0)、right=(0,0,-1) → +Z 在画面左侧）
        val side = ScreenProjector.project(0f, 0f, 2.5f, v, proj)
        assertEquals(0.25f, side!![0], 1e-3f)
    }

    @Test
    fun `不同纵横比按宽度归一`() {
        val p = perspective(60f, 2f, 0.1f, 100f)
        // 距离 5、fovY 60° → 半高 5·tan30°≈2.89；垂直 ndc=1 恰好贴边
        val edge = 5f * tan(Math.toRadians(30.0)).toFloat()
        val uv = ScreenProjector.project(0f, edge * 0.999f, 0f, lookAtView(
            floatArrayOf(0f, 0f, 5f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f)), p)
        assertNotNull(uv)
        assertEquals(0.001f, uv!![1], 2e-3f)
    }
}
