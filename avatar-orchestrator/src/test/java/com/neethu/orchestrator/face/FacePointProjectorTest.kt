package com.neethu.orchestrator.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频模式的注视点投影（FacePointProjector + OneEuroFilter）：
 * 锁定**角度语义**（脸贴画面边缘 = 22°水平/15°垂直转角上限，转角不随
 * 机位距离漂移）、符号约定（世界对齐 nx：+1=屏幕右；ny：+1=画面下缘）、
 * 深度前伸钳制、平滑收敛与退化安全。
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

    /** 人物头部(target=z0 平面)到注视点的水平转角(度,取绝对值)。 */
    private fun yawAtTarget(point: FloatArray): Float =
        FacePointProjector.offsetAngleDegrees(kotlin.math.abs(point[0]), kotlin.math.abs(point[2]))

    // ── OneEuro ───────────────────────────────────────────────────────────

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
        val afterFew = List(8) { f.filter(1f, dt) }.last()
        assertTrue("step response too slow: $afterFew", afterFew > 0.5f)
        repeat(200) { f.filter(1f, dt) }
        assertEquals(1f, f.filter(1f, dt), 1e-3f)
    }

    // ── 符号约定 ──────────────────────────────────────────────────────────

    @Test
    fun `nx positive means world-right of camera eye`() {
        val p = FacePointProjector()
        val point = steadyPoint(
            p, nx = 1f, ny = 0f, area = 0.1f,
            eye = floatArrayOf(0f, 0f, 3f), target = floatArrayOf(0f, 0f, 0f), up = floatArrayOf(0f, 1f, 0f),
        )
        // 相机距人物 3:边缘脸 = 22° → 偏移 tan(22°)*3 ≈ 1.21(原线性版是 1.4→54°,特写直接打到限幅)
        assertEquals(kotlin.math.tan(Math.toRadians(22.0)).toFloat() * 3f, point[0], 0.05f)
        assertEquals(0f, point[1], 1e-4f)
    }

    @Test
    fun `ny positive means below camera axis`() {
        val p = FacePointProjector()
        val point = steadyPoint(
            p, nx = 0f, ny = 1f, area = 0.1f,
            eye = floatArrayOf(0f, 0f, 3f), target = floatArrayOf(0f, 0f, 0f), up = floatArrayOf(0f, 1f, 0f),
        )
        assertEquals(-kotlin.math.tan(Math.toRadians(15.0)).toFloat() * 3f, point[1], 0.05f)
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
        assertTrue("expected z<-0.8, got ${point[2]}", point[2] < -0.8f)
    }

    // ── 角度语义(二轮调参核心):转角不随机位漂移 ─────────────────────────

    @Test
    fun `edge face yields the same turn angle at close-up and full-shot distance`() {
        val p = FacePointProjector()
        val up = floatArrayOf(0f, 1f, 0f)
        val target = floatArrayOf(0f, 0f, 0f)
        // 特写 d=1 vs 全景 d=4:头部处量到的转角都必须 ≈22°
        val closeUp = steadyPoint(
            p, nx = 1f, ny = 0f, area = 0.1f,
            eye = floatArrayOf(0f, 0f, 1f), target = target, up = up,
        )
        val fullShot = steadyPoint(
            p, nx = 1f, ny = 0f, area = 0.1f,
            eye = floatArrayOf(0f, 0f, 4f), target = target, up = up,
        )
        assertEquals(22f, yawAtTarget(closeUp), 1f)
        assertEquals(22f, yawAtTarget(fullShot), 1f)
    }

    @Test
    fun `half-way face yields about half the max angle`() {
        val p = FacePointProjector()
        val point = steadyPoint(
            p, nx = 0.5f, ny = 0f, area = 0.1f,
            eye = floatArrayOf(0f, 0f, 3f), target = floatArrayOf(0f, 0f, 0f), up = floatArrayOf(0f, 1f, 0f),
        )
        assertEquals(11f, yawAtTarget(point), 1f)
    }

    // ── 深度项与退化安全 ──────────────────────────────────────────────────

    @Test
    fun `large face area pulls the gaze point toward the avatar but capped`() {
        val p = FacePointProjector()
        val eye = floatArrayOf(0f, 0f, 3f)
        val target = floatArrayOf(0f, 0f, 0f)
        val up = floatArrayOf(0f, 1f, 0f)
        val close = steadyPoint(p, 0f, 0f, area = 0.4f, eye = eye, target = target, up = up)
        val far = steadyPoint(p, 0f, 0f, area = 0.02f, eye = eye, target = target, up = up)
        // 近:前伸被钳在距离的 20%(0.6)→ z=2.4;远:后退钳在 25%(0.75)→ z=3.75
        assertEquals(2.4f, close[2], 0.05f)
        assertEquals(3.75f, far[2], 0.05f)
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
        val weird = steadyPoint(p, 0.3f, 0.3f, 0f, eye, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 0f))
        assertTrue(weird.all { it.isFinite() })
    }
}
