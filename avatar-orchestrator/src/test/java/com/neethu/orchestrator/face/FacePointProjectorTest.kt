package com.neethu.orchestrator.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频模式的注视点投影（FacePointProjector + OneEuroFilter）：
 * 锁定符号约定（世界对齐 nx：+1=屏幕右；ny：+1=画面下缘）、深度方向
 * （越靠近用户相机，注视点越向模型前伸）、平滑收敛与退化安全。
 */
class FacePointProjectorTest {

    private val dt = 1f / 30f

    /** 预热平滑器：同一输入喂 N 帧，返回稳态投影点。 */
    private fun steadyPoint(
        p: FacePointProjector,
        nx: Float, ny: Float, area: Float,
        eye: FloatArray, target: FloatArray, up: FloatArray,
        frames: Int = 240,
    ): FloatArray {
        var out = FloatArray(3)
        repeat(frames) { out = p.project(nx, ny, area, eye, target, up, dt) }
        return out
    }

    @Test
    fun `one euro converges to constant input`() {
        val f = OneEuroFilter()
        var out = 0f
        repeat(200) { out = f.filter(0.42f, dt) }
        assertEquals(0.42f, out, 1e-3f)
    }

    @Test
    fun `one euro passes fast steps through quickly but damps them`() {
        val f = OneEuroFilter()
        repeat(120) { f.filter(0f, dt) }
        // 快速阶跃：低速重平滑，但数帧内必须明显跟上（不能永远粘在旧值）
        val afterFew = List(8) { f.filter(1f, dt) }.last()
        assertTrue("step response too slow: $afterFew", afterFew > 0.5f)
        // 稳态收敛到阶跃终点
        repeat(200) { f.filter(1f, dt) }
        assertEquals(1f, f.filter(1f, dt), 1e-3f)
    }

    @Test
    fun `nx positive means world-right of camera eye`() {
        val p = FacePointProjector()
        val eye = floatArrayOf(0f, 0f, 3f)
        val target = floatArrayOf(0f, 0f, 0f)
        val up = floatArrayOf(0f, 1f, 0f)
        val point = steadyPoint(p, nx = 1f, ny = 0f, area = 0.1f, eye = eye, target = target, up = up)
        // 用户在屏幕右 → 注视点在世界 +X（相机看向 -Z 时 right=+X）
        assertTrue("expected x>0, got ${point[0]}", point[0] > 0.5f)
        assertEquals(0f, point[1], 1e-4f)
    }

    @Test
    fun `ny positive means below camera axis`() {
        val p = FacePointProjector()
        val point = steadyPoint(
            p, nx = 0f, ny = 1f, area = 0.1f,
            eye = floatArrayOf(0f, 0f, 3f), target = floatArrayOf(0f, 0f, 0f), up = floatArrayOf(0f, 1f, 0f),
        )
        assertTrue("expected y<0, got ${point[1]}", point[1] < -0.3f)
    }

    @Test
    fun `large face area pulls the gaze point toward the avatar`() {
        val p = FacePointProjector()
        val eye = floatArrayOf(0f, 0f, 3f)
        val target = floatArrayOf(0f, 0f, 0f)
        val up = floatArrayOf(0f, 1f, 0f)
        val close = steadyPoint(p, 0f, 0f, area = 0.4f, eye = eye, target = target, up = up)
        val far = steadyPoint(p, 0f, 0f, area = 0.02f, eye = eye, target = target, up = up)
        // 近：前伸分量>0 → z < eye.z（朝模型）；远：深度被钳制后 z > eye.z（退到用户身后）
        assertTrue("close z=${close[2]}", close[2] < eye[2] && close[2] > target[2])
        assertTrue("far z=${far[2]}", far[2] > eye[2])
    }

    @Test
    fun `right-handed basis follows rotated camera`() {
        // 相机转到 +X 侧（yaw 90°）看向原点：right = forward×up = (0,0,-1)，
        // nx=+1（用户在屏幕右）必须落在世界 -Z。
        val p = FacePointProjector()
        val point = steadyPoint(
            p, nx = 1f, ny = 0f, area = 0.1f,
            eye = floatArrayOf(3f, 0f, 0f), target = floatArrayOf(0f, 0f, 0f), up = floatArrayOf(0f, 1f, 0f),
        )
        assertTrue("expected z<0, got ${point[2]}", point[2] < -0.5f)
    }

    @Test
    fun `degenerate camera pose falls back instead of crashing`() {
        val p = FacePointProjector()
        val point = steadyPoint(
            p, nx = 0.5f, ny = -0.5f, area = 0.1f,
            eye = floatArrayOf(1f, 2f, 3f), target = floatArrayOf(1f, 2f, 3f), up = floatArrayOf(0f, 0f, 0f),
        )
        assertTrue(point.all { it.isFinite() })
    }

    @Test
    fun `repeated identical input is stable and keeps lastPoint`() {
        val p = FacePointProjector()
        val eye = floatArrayOf(0f, 0f, 3f)
        val a = steadyPoint(p, 0.3f, 0.3f, 0.1f, eye, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        val b = steadyPoint(p, 0.3f, 0.3f, 0.1f, eye, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        assertTrue(a.contentEquals(b))
        assertTrue(p.lastPoint.contentEquals(b))
        // 面积异常值不产生发散结果
        val weird = steadyPoint(p, 0.3f, 0.3f, 0f, eye, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        assertTrue(weird.all { it.isFinite() })
    }
}
