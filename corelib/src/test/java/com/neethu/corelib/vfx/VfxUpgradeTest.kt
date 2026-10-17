package com.neethu.corelib.vfx

import com.neethu.corelib.vfx.sim.ParticleSystem
import com.neethu.corelib.vfx.sim.WindField
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画质升级（2026-10-17，docs/vfx-quality-upgrade.md）纯数学层单测：
 * WindField 界/确定性、GroundCollision 落地语义、eulerBasis 正交性、
 * CROSS 八顶点写出、Turbulence ×dt 的帧率无关性（回归锁：漏乘 dt 的
 * 旧 bug 会让 v 随帧率线性漂移）。
 */
class VfxUpgradeTest {

    private val right = floatArrayOf(1f, 0f, 0f)
    private val up = floatArrayOf(0f, 1f, 0f)
    private val forward = floatArrayOf(0f, 0f, -1f)

    // ── WindField ──────────────────────────────────────────────────────

    @Test
    fun `wind sample is deterministic and bounded`() {
        val field = WindField(
            WindParams(
                direction = VfxVec3(1f, 0f, 0f),
                strength = 0.8f, gust = 0.5f, gustFrequency = 0.2f,
                swirl = 0.3f, swirlScale = 1.2f,
            ),
        )
        val a = FloatArray(2)
        val b = FloatArray(2)
        field.sample(1.3f, -0.7f, 12.4, a)
        field.sample(1.3f, -0.7f, 12.4, b)
        assertEquals(a[0], b[0], 1e-6f)
        assertEquals(a[1], b[1], 1e-6f)

        val bound = 0.8f * (1f + 0.5f) + 0.3f + 1e-3f
        for (i in 0 until 200) {
            val t = i * 0.137
            field.sample(i * 0.31f, -i * 0.17f, t, a)
            assertTrue(sqrt(a[0] * a[0] + a[1] * a[1]) <= bound)
        }
    }

    @Test
    fun `wind mean flow aligns with direction and is horizontal`() {
        val field = WindField(
            WindParams(direction = VfxVec3(0f, 0.3f, 1f), strength = 1.0f, gust = 0f, swirl = 0f),
        )
        var mx = 0f
        var mz = 0f
        val out = FloatArray(2)
        val n = 64
        for (i in 0 until n) {
            field.sample(i * 0.5f, i * 0.25f, i * 0.4, out)
            mx += out[0]; mz += out[1]
        }
        mx /= n; mz /= n
        // 方向投影到水平面 = +Z；平均流应为纯 +Z（y 分量不进风）
        assertTrue(abs(mx) < 0.05f)
        assertTrue(mz > 0.9f)
    }

    // ── GroundCollision ────────────────────────────────────────────────

    private fun groundSpec(behaviors: List<BehaviorParams>) = VfxSpec(
        id = "t",
        maxParticles = 8,
        burst = 8,
        rate = 0f,
        lifetimeMin = 10f,
        lifetimeMax = 10f,
        sizeMin = 0.1f,
        sizeMax = 0.1f,
        emitter = EmitterParams(
            shape = EmitterShape.POINT,
            center = VfxVec3(0f, 2f, 0f),
            direction = VfxVec3.DOWN,
            speedMin = 0f,
            speedMax = 0f,
        ),
        behaviors = behaviors,
    )

    @Test
    fun `ground collision pins falling particles and clamps life to fade`() {
        val system = ParticleSystem(
            groundSpec(
                listOf(
                    BehaviorParams.Gravity(VfxVec3(0f, -10f, 0f)),
                    BehaviorParams.GroundCollision(groundY = 0f, fadeSeconds = 0.4f, friction = 3f),
                ),
            ),
            Random(7),
        )
        val out = FloatArray(8 * 8 * VfxMath.FLOATS_PER_VERTEX)
        // 从 y=2 以 -10 m/s² 落地约 0.63s：推 0.8s 必已触地
        var t = 0f
        while (t < 0.8f) {
            system.update(0.02f, 0.0, 0f, 0f, 0f, right, up, forward, out, 0)
            t += 0.02f
        }
        for (i in 0 until system.pool.activeCount) {
            assertEquals("钉在地面", 0f, system.pool.py[i], 1e-4f)
            assertEquals("竖向速度清零", 0f, system.pool.vy[i], 1e-5f)
            assertTrue("剩余寿命钳到 fade", system.pool.life[i] <= 0.4f + 1e-4f)
        }
    }

    @Test
    fun `ground collision ignores particles born below ground`() {
        val system = ParticleSystem(
            groundSpec(listOf(BehaviorParams.GroundCollision(groundY = 0f, fadeSeconds = 0.4f))),
            Random(7),
        )
        // 出生点 y=2 在地面上；换个出生在地下的场景须用 emitter center ——
        // 这里反向验证：向上速度(vy>0)不触发钉地
        system.pool.append(
            x = 0f, y = -1f, z = 0f, velX = 0f, velY = 1f, velZ = 0f,
            lifeSec = 5f, size = 0.1f, angleRad = 0f, angVelRad = 0f,
            r = 1f, g = 1f, b = 1f, a = 1f,
        )
        val out = FloatArray(8 * 8 * VfxMath.FLOATS_PER_VERTEX)
        system.update(0.016f, 0.0, 0f, 0f, 0f, right, up, forward, out, 0)
        assertEquals(-1f + 1f * 0.016f, system.pool.py[0], 1e-4f)
        assertEquals(1f, system.pool.vy[0], 1e-5f)
    }

    // ── Turbulence dt 修复回归 ─────────────────────────────────────────

    @Test
    fun `turbulence velocity gain is frame-rate independent`() {
        fun run(dt: Float): Float {
            val system = ParticleSystem(
                groundSpec(listOf(BehaviorParams.Turbulence(strength = 2f, scale = 3f, evolution = 0.8f))),
                Random(11),
            )
            val out = FloatArray(8 * 8 * VfxMath.FLOATS_PER_VERTEX)
            var t = 0f
            while (t < 1f - 1e-5f) {
                val step = minOf(dt, 1f - t)
                system.update(step, 0.0, 0f, 0f, 0f, right, up, forward, out, 0)
                t += step
            }
            return system.pool.vx[0]
        }
        val v60 = run(1f / 60f)
        val v30 = run(1f / 30f)
        // 旧 bug：kick 不乘 dt → 60fps 的累积速度是 30fps 的 2 倍。
        // 修复后同物理时长结果应接近（Euler 离散差 < 20%）。
        assertTrue("v60=$v60 v30=$v30 应同量级", abs(v60 - v30) / 2f.coerceAtLeast(abs(v60)) < 0.2f)
    }

    // ── eulerBasis / CROSS 写出 ────────────────────────────────────────

    @Test
    fun `eulerBasis is orthonormal with known axis mapping`() {
        val bx = FloatArray(3)
        val by = FloatArray(3)
        val bz = FloatArray(3)
        // yaw=90°：X → (0,0,-1)
        VfxMath.eulerBasis(kotlin.math.PI.toFloat() / 2f, 0f, 0f, bx, by, bz)
        assertEquals(0f, bx[0], 1e-5f)
        assertEquals(0f, bx[1], 1e-5f)
        assertEquals(-1f, bx[2], 1e-5f)
        // 一般角：正交 + 单位长
        VfxMath.eulerBasis(0.7f, -1.1f, 2.3f, bx, by, bz)
        fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun len(a: FloatArray) = sqrt(dot(a, a))
        assertEquals(1f, len(bx), 1e-5f)
        assertEquals(1f, len(by), 1e-5f)
        assertEquals(1f, len(bz), 1e-5f)
        assertEquals(0f, dot(bx, by), 1e-5f)
        assertEquals(0f, dot(bx, bz), 1e-5f)
        assertEquals(0f, dot(by, bz), 1e-5f)
    }

    @Test
    fun `cross mode writes two orthogonal quads sharing the center`() {
        val system = ParticleSystem(
            VfxSpec(
                id = "cross",
                maxParticles = 2,
                burst = 2,
                rate = 0f,
                lifetimeMin = 5f,
                lifetimeMax = 5f,
                sizeMin = 0.1f,
                sizeMax = 0.1f,
                renderMode = VfxRenderMode.CROSS,
                tumbleMin = 90f,
                tumbleMax = 90f,
                emitter = EmitterParams(
                    shape = EmitterShape.POINT,
                    center = VfxVec3(1f, 2f, 3f),
                    speedMin = 0f,
                    speedMax = 0f,
                ),
            ),
            Random(3),
        )
        val floatsPer = system.floatsPerParticle
        assertEquals("CROSS = 8 顶点 × 9 float", 72, floatsPer)
        val out = FloatArray(2 * floatsPer)
        val n = system.update(0.016f, 0.0, 0f, 0f, 0f, right, up, forward, out, 0)
        assertEquals(2, n)

        for (p in 0 until n) {
            val base = p * floatsPer
            var cx = 0f; var cy = 0f; var cz = 0f
            for (v in 0 until 4) {
                cx += out[base + v * 9]; cy += out[base + v * 9 + 1]; cz += out[base + v * 9 + 2]
            }
            cx /= 4; cy /= 4; cz /= 4
            assertEquals("quad A 中心 = 粒子位置 x", 1f, cx, 1e-4f)
            assertEquals("quad A 中心 = 粒子位置 y", 2f, cy, 1e-4f)
            assertEquals("quad A 中心 = 粒子位置 z", 3f, cz, 1e-4f)
            cx = 0f; cy = 0f; cz = 0f
            for (v in 4 until 8) {
                cx += out[base + v * 9]; cy += out[base + v * 9 + 1]; cz += out[base + v * 9 + 2]
            }
            cx /= 4; cy /= 4; cz /= 4
            assertEquals("quad B 中心 = 粒子位置 x", 1f, cx, 1e-4f)
            assertEquals("quad B 中心 = 粒子位置 y", 2f, cy, 1e-4f)
            assertEquals("quad B 中心 = 粒子位置 z", 3f, cz, 1e-4f)
            // quad A 的长轴 (V1-V0) 与 quad B 的长轴不平行（两片正交布局）
            fun axis(v0: Int, v1: Int): FloatArray {
                val ax = floatArrayOf(
                    out[base + v1 * 9] - out[base + v0 * 9],
                    out[base + v1 * 9 + 1] - out[base + v0 * 9 + 1],
                    out[base + v1 * 9 + 2] - out[base + v0 * 9 + 2],
                )
                val l = sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2])
                return floatArrayOf(ax[0] / l, ax[1] / l, ax[2] / l)
            }
            val aAxis = axis(0, 1)
            val bAxis = axis(4, 5)
            val d = aAxis[0] * bAxis[0] + aAxis[1] * bAxis[1] + aAxis[2] * bAxis[2]
            assertTrue("两片 quad 长轴应非平行 |dot|=$d", abs(d) < 0.99f)
        }
    }

    // ── 风场行为集成（ParticleSystem 速度趋向）─────────────────────────

    @Test
    fun `wind drives horizontal velocity toward the field`() {
        val system = ParticleSystem(
            groundSpec(emptyList()).copy(
                wind = WindParams(direction = VfxVec3(1f, 0f, 0f), strength = 1f, gust = 0f, swirl = 0f, responsiveness = 50f),
            ),
            Random(5),
        )
        val out = FloatArray(8 * 8 * VfxMath.FLOATS_PER_VERTEX)
        repeat(10) { system.update(0.016f, 0.0, 0f, 0f, 0f, right, up, forward, out, 0) }
        // responsiveness=50 → ~0.2s 后水平速度应贴近风速 1.0
        assertTrue("vx 应趋向风速", system.pool.vx[0] > 0.85f)
    }
}
