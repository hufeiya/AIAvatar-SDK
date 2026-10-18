package com.neethu.corelib.vfx

import com.neethu.corelib.vfx.sim.Behavior
import com.neethu.corelib.vfx.sim.ParticlePool
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 纯数学单测（docs/vfx-feasibility.md §六 P2）：curl 旋度湍流的散度恒零
 * 与帧率无关性（回归锁）、DecalGeometry 地面圆盘/穹顶网格形态、Fresnel 明度
 * 场、cameraFrontPoint。
 */
class VfxDecalCurlTest {

    // ── curl 旋度湍流 ──────────────────────────────────────────────────

    private fun curlBehavior(strength: Float = 1.0f, scale: Float = 1.0f, evolution: Float = 1.0f) =
        Behavior.from(listOf(BehaviorParams.Turbulence(strength, scale, evolution, curl = true)))
            .first() as Behavior.Turbulence

    @Test
    fun `curl force field is divergence free`() {
        val f = curlBehavior(strength = 1.6f, scale = 1.4f, evolution = 0.9f)
        val out = FloatArray(3)
        val h = 1e-3f
        // 中央差分数值散度，采样点覆盖多个波长
        for (k in 0 until 24) {
            val x = k * 0.37f - 2.1f
            val y = k * 0.11f + 0.9f
            val z = -k * 0.23f + 1.3f
            val t = k * 0.53
            f.forceCurl(x + h, y, z, t, out); val fx1 = out[0]
            f.forceCurl(x - h, y, z, t, out); val fx0 = out[0]
            f.forceCurl(x, y + h, z, t, out); val fy1 = out[1]
            f.forceCurl(x, y - h, z, t, out); val fy0 = out[1]
            f.forceCurl(x, y, z + h, t, out); val fz1 = out[2]
            f.forceCurl(x, y, z - h, t, out); val fz0 = out[2]
            val div = (fx1 - fx0) / (2 * h) + (fy1 - fy0) / (2 * h) + (fz1 - fz0) / (2 * h)
            assertTrue("div($x,$y,$z)=$div not ~0", abs(div) < 1e-2f)
        }
    }

    @Test
    fun `curl integration is frame rate independent`() {
        // 同一模拟时长用 60Hz 与 30Hz 步长各自积分（同一粒子状态推进），
        // 旧行为（漏乘 dt）会让累计速度差 ~2 倍；加速度 ×dt 语义下近似相等
        fun integrate(dt: Float): Pair<FloatArray, FloatArray> {
            val f = curlBehavior(strength = 2.0f, scale = 1.2f, evolution = 0.8f)
            val pool = ParticlePool(1)
            pool.append(
                x = 0.3f, y = 0.7f, z = -0.5f,
                velX = 0f, velY = 0f, velZ = 0f,
                lifeSec = 10f, size = 0.05f, angleRad = 0f, angVelRad = 0f,
                r = 1f, g = 1f, b = 1f, a = 1f,
            )
            val steps = (1.0f / dt).toInt()
            var px = 0.3f; var py = 0.7f; var pz = -0.5f
            repeat(steps) {
                // 手动搬位置进池（Behavior 只改速度；位置由调用方积分）
                pool.px[0] = px; pool.py[0] = py; pool.pz[0] = pz
                f.apply(pool, 0, dt)
                px += pool.vx[0] * dt
                py += pool.vy[0] * dt
                pz += pool.vz[0] * dt
            }
            return floatArrayOf(pool.vx[0], pool.vy[0], pool.vz[0]) to floatArrayOf(px, py, pz)
        }
        val (v60, p60) = integrate(1f / 60f)
        val (v30, p30) = integrate(1f / 30f)
        for (i in 0..2) {
            assertEquals("vel[$i] frame-rate drift", v60[i], v30[i], 0.06f * (abs(v60[i]) + 0.5f))
            assertEquals("pos[$i] frame-rate drift", p60[i], p30[i], 0.06f * (abs(p60[i]) + 0.2f))
        }
    }

    @Test
    fun `curl strength scales the field amplitude`() {
        val weak = curlBehavior(strength = 0.5f)
        val strong = curlBehavior(strength = 4.0f)
        val out = FloatArray(3)
        var magWeak = 0f
        var magStrong = 0f
        for (k in 0 until 32) {
            val x = k * 0.41f; val y = k * 0.13f; val z = k * 0.29f; val t = k * 0.37
            weak.forceCurl(x, y, z, t, out); magWeak += sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
            strong.forceCurl(x, y, z, t, out); magStrong += sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        }
        // 强度 8 倍 → 场幅值 ~8 倍（线性标度，除 k0 不破坏比例）
        assertTrue(magStrong / magWeak in 7.0f..9.0f)
    }

    // ── DecalGeometry ──────────────────────────────────────────────────

    @Test
    fun `ground circle mesh is a flat textured quad`() {
        val color = VfxColor(0.3f, 0.6f, 0.9f, 1f)
        val mesh = DecalGeometry.groundCircle(1.2f, color)
        assertEquals(4, mesh.vertexCount)
        assertEquals(6, mesh.indexCount)
        val stride = DecalGeometry.FLOATS_PER_VERTEX
        for (v in 0 until 4) {
            val o = v * stride
            assertEquals("y=0 plane", 0f, mesh.vertices[o + 1], 1e-6f)
            assertEquals("radius on XZ", 1.2f, abs(mesh.vertices[o]), 1e-6f)
            assertEquals(color.r, mesh.vertices[o + 5], 1e-6f)
            assertEquals(color.a, mesh.vertices[o + 8], 1e-6f)
            assertTrue("uv in 0..1", mesh.vertices[o + 3] in 0f..1f && mesh.vertices[o + 4] in 0f..1f)
        }
        // 索引第一三角 (0,2,1)：+Y 俯视为正面（BACK 剔除保留朝上面）
        assertEquals(0, mesh.indices[0].toInt()); assertEquals(2, mesh.indices[1].toInt()); assertEquals(1, mesh.indices[2].toInt())
    }

    @Test
    fun `dome mesh sits on unit sphere with wrapped uv`() {
        val mesh = DecalGeometry.dome(0.85f, rings = 6, segments = 32, maxElevationDeg = 78f, color = VfxColor.WHITE)
        assertEquals(7 * 33, mesh.vertexCount)
        assertEquals(6 * 32 * 6, mesh.indexCount)
        val stride = DecalGeometry.FLOATS_PER_VERTEX
        for (v in 0 until mesh.vertexCount) {
            val o = v * stride
            val x = mesh.vertices[o]; val y = mesh.vertices[o + 1]; val z = mesh.vertices[o + 2]
            assertEquals("on sphere r=0.85", 0.85f, sqrt(x * x + y * y + z * z), 1e-3f)
            assertTrue("y>=0 hemisphere", y >= -1e-5f)
            assertTrue("u wraps 0..1", mesh.vertices[o + 3] in 0f..1f)
            assertTrue("v in 0..1", mesh.vertices[o + 4] in 0f..1f)
        }
        // 首环 i=0：仰角 0（贴地圈）
        assertEquals(0f, mesh.vertices[1], 1e-6f)
    }

    @Test
    fun `fresnel shades brightest at silhouette and clamped`() {
        val r = 1.0f
        val mesh = DecalGeometry.dome(r, rings = 6, segments = 32, maxElevationDeg = 78f, color = VfxColor.WHITE)
        val stride = DecalGeometry.FLOATS_PER_VERTEX
        // 相机放在远处 +X：赤道上 +X 点正对相机（base 亮度），+Z 点在轮廓缘（rim→1）
        val eye = floatArrayOf(6f, 0.5f, 0f)
        val cx = 0f; val cy = 0f; val cz = 0f
        DecalGeometry.fresnelShades(mesh.vertices, cx, cy, cz, eye[0], eye[1], eye[2], base = 0.35f, rimStrength = 1.6f, rimPower = 2.2f)
        var shadeFacing = 0f
        var shadeRim = 0f
        for (v in 0 until mesh.vertexCount) {
            val o = v * stride
            val x = mesh.vertices[o]; val y = mesh.vertices[o + 1]; val z = mesh.vertices[o + 2]
            val shade = mesh.vertices[o + 5]
            assertEquals("grayscale rgb", mesh.vertices[o + 6], shade, 1e-6f)
            assertEquals("alpha untouched", 1f, mesh.vertices[o + 8], 1e-6f)
            assertTrue("shade clamped 0..1", shade in 0f..1f)
            if (abs(y) < 0.2f && x > 0.9f) shadeFacing = shade          // 赤道近 +X
            if (abs(y) < 0.2f && abs(z) > 0.9f) shadeRim = shade        // 赤道 ±Z（视线切向）
        }
        assertTrue("rim($shadeRim) should exceed facing($shadeFacing)", shadeRim > shadeFacing + 0.2f)
        assertEquals("facing ≈ base", 0.35f, shadeFacing, 0.05f)
    }

    // ── cameraFrontPoint ───────────────────────────────────────────────

    @Test
    fun `camera front point is eye plus forward times distance`() {
        val eye = floatArrayOf(1f, 2f, 3f)
        val forward = floatArrayOf(0f, 0f, -1f)
        val out = FloatArray(3)
        VfxMath.cameraFrontPoint(eye, forward, 1.5f, out)
        assertEquals(1f, out[0], 1e-6f)
        assertEquals(2f, out[1], 1e-6f)
        assertEquals(1.5f, out[2], 1e-6f)
    }
}
