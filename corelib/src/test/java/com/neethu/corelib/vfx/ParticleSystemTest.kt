package com.neethu.corelib.vfx

import com.neethu.corelib.vfx.sim.ParticleSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * ParticleSystem 确定性积分锁（docs/vfx-feasibility.md §六 P0 单测清单）：
 * 同种子同 dt 序列逐帧断言——发射预算/溢出策略/dt 钳制/行为积分/顶点布局。
 */
class ParticleSystemTest {

    private val right = floatArrayOf(1f, 0f, 0f)
    private val up = floatArrayOf(0f, 1f, 0f)
    private val forward = floatArrayOf(0f, 0f, -1f)

    private fun assertClose(expected: Float, actual: Float, eps: Float = 1e-4f) {
        if (abs(expected - actual) > eps) throw AssertionError("expected $expected got $actual")
    }

    private fun burstSpec(maxParticles: Int = 16, burst: Int = 16, behaviors: List<BehaviorParams> = emptyList()) =
        VfxSpec(
            id = "t",
            maxParticles = maxParticles,
            burst = burst,
            rate = 0f,
            lifetimeMin = 1f,
            lifetimeMax = 1f,
            sizeMin = 0.1f,
            sizeMax = 0.1f,
            emitter = EmitterParams(
                shape = EmitterShape.POINT,
                direction = VfxVec3.DOWN,
                speedMin = 0f,
                speedMax = 0f,
            ),
            behaviors = behaviors,
        )

    private fun update(
        system: ParticleSystem,
        dt: Float,
        anchor: VfxVec3 = VfxVec3.ZERO,
        out: FloatArray = FloatArray(64 * 8 * VfxMath.FLOATS_PER_VERTEX),
    ): Int = system.update(
        dt, 0.0, anchor.x, anchor.y, anchor.z, right, up, forward, out, 0,
    )

    @Test
    fun `burst emits once then finishes when the pool drains`() {
        val system = ParticleSystem(burstSpec(burst = 10, maxParticles = 10), Random(42))
        val written = update(system, 0.016f)
        assertEquals(10, written)
        assertEquals(10, system.pool.activeCount)
        assertFalse(system.isFinished)
        // 寿命 1s：连推 21 帧 × 0.05 全部耗尽
        repeat(21) { update(system, 0.05f) }
        assertEquals(0, system.pool.activeCount)
        assertTrue(system.isFinished)
        // 收尾后再 update 保持空转
        assertEquals(0, update(system, 0.05f))
    }

    @Test
    fun `continuous rate accumulates a fractional budget`() {
        val spec = VfxSpec(
            id = "t", maxParticles = 16, burst = 0, rate = 100f,
            lifetimeMin = 10f, lifetimeMax = 10f,
            sizeMin = 0.1f, sizeMax = 0.1f,
            emitter = EmitterParams(shape = EmitterShape.POINT, speedMin = 0f, speedMax = 0f),
        )
        val system = ParticleSystem(spec, Random(7))
        update(system, 0.016f) // 预算 1.6 → 发射 1，余 0.6
        assertEquals(1, system.pool.activeCount)
        update(system, 0.016f) // 0.6+1.6=2.2 → 发射 2
        assertEquals(3, system.pool.activeCount)
        update(system, 0.016f) // 0.2+1.6=1.8 → 发射 1
        assertEquals(4, system.pool.activeCount)
        assertFalse(system.isFinished)
    }

    @Test
    fun `dt is clamped into the safe band`() {
        val bigDt = ParticleSystem(burstSpec(burst = 0, maxParticles = 16).copy(rate = 100f), Random(9))
        val clamped = ParticleSystem(burstSpec(burst = 0, maxParticles = 16).copy(rate = 100f), Random(9))
        update(bigDt, 10f) // 野 dt 直接钳到 0.05：预算 5.0 → 5 粒
        update(clamped, ParticleSystem.DT_MAX)
        assertEquals(clamped.pool.activeCount, bigDt.pool.activeCount)
        assertEquals(5, bigDt.pool.activeCount)
    }

    @Test
    fun `gravity accelerates and integrates position`() {
        val system = ParticleSystem(
            burstSpec(
                burst = 1, maxParticles = 1,
                behaviors = listOf(BehaviorParams.Gravity(VfxVec3(0f, -10f, 0f))),
            ),
            Random(1),
        )
        update(system, 0.05f, anchor = VfxVec3(0f, 10f, 0f))
        assertClose(-0.5f, system.pool.vy[0])            // v = −10×0.05
        assertClose(10f - 0.5f * 0.05f, system.pool.py[0]) // y = y0 + v·dt
    }

    @Test
    fun `drag decays velocity exponentially`() {
        val system = ParticleSystem(
            VfxSpec(
                id = "t", maxParticles = 1, burst = 1,
                lifetimeMin = 1f, lifetimeMax = 1f, sizeMin = 0.1f, sizeMax = 0.1f,
                emitter = EmitterParams(
                    shape = EmitterShape.POINT,
                    direction = VfxVec3(0f, 1f, 0f),
                    speedMin = 10f, speedMax = 10f,
                ),
                behaviors = listOf(BehaviorParams.Drag(coefficient = 10f)),
            ),
            Random(3),
        )
        update(system, 0.05f)
        assertClose(10f * Math.exp(-10.0 * 0.05).toFloat(), system.pool.vy[0], 1e-3f)
    }

    @Test
    fun `color and size curves sample by life fraction`() {
        val system = ParticleSystem(
            burstSpec(
                burst = 1, maxParticles = 1,
                behaviors = listOf(
                    BehaviorParams.ColorOverLife(
                        listOf(
                            ColorKey(0f, VfxColor(1f, 0f, 0f, 1f)),
                            ColorKey(1f, VfxColor(0f, 0f, 1f, 0f)),
                        ),
                    ),
                    BehaviorParams.SizeOverLife(listOf(ScalarKey(0f, 1f), ScalarKey(1f, 2f))),
                ),
            ),
            Random(5),
        )
        update(system, 0.05f) // t = 0.05
        assertClose(0.95f, system.pool.cr[0])
        assertClose(0.05f, system.pool.cb[0])
        assertClose(0.95f, system.pool.ca[0])
        assertClose(0.1f * 1.05f, system.pool.curSize[0])
    }

    @Test
    fun `overflow reject drops excess spawns`() {
        val system = ParticleSystem(burstSpec(burst = 8, maxParticles = 3), Random(11))
        update(system, 0.016f)
        assertEquals(3, system.pool.activeCount)
    }

    @Test
    fun `overflow recycle oldest overwrites the shortest life slot`() {
        val spec = burstSpec(burst = 3, maxParticles = 2).copy(overflow = Overflow.RECYCLE_OLDEST)
        val system = ParticleSystem(spec, Random(13))
        update(system, 0.016f)
        assertEquals(2, system.pool.activeCount) // 第 3 个覆盖最老槽，不超上限
        // 等寿命下「最老」= 0 号槽：寿命 = 新采样 1s − 本帧老化 0.016
        assertEquals(1f - 0.016f, system.pool.life[0], 1e-4f)
    }

    @Test
    fun `vertex layout writes billboard corners uv and color`() {
        val spec = burstSpec(burst = 1, maxParticles = 1).copy(startColor = VfxColor(0.3f, 0.5f, 0.7f, 0.9f))
        val system = ParticleSystem(spec, Random(21))
        val out = FloatArray(VfxMath.FLOATS_PER_PARTICLE)
        update(system, 0.016f, anchor = VfxVec3(5f, 6f, 7f), out = out)
        val s = 0.1f
        val p = system.pool
        val x = p.px[0]; val y = p.py[0]; val z = p.pz[0]
        // V0 左下 = P − r·s − u·s（r=+X, u=+Y）
        assertClose(x - s, out[0]); assertClose(y - s, out[1]); assertClose(z, out[2])
        // V1 右下
        assertClose(x + s, out[9]); assertClose(y - s, out[10]); assertClose(z, out[11])
        // V2 右上
        assertClose(x + s, out[18]); assertClose(y + s, out[19]); assertClose(z, out[20])
        // V3 左上
        assertClose(x - s, out[27]); assertClose(y + s, out[28]); assertClose(z, out[29])
        // UV 固定四角
        assertClose(0f, out[3]); assertClose(0f, out[4])
        assertClose(1f, out[9 + 3]); assertClose(0f, out[9 + 4])
        assertClose(1f, out[18 + 3]); assertClose(1f, out[18 + 4])
        assertClose(0f, out[27 + 3]); assertClose(1f, out[27 + 4])
        // 颜色 ×4 顶点
        for (v in 0 until 4) {
            val o = v * 9 + VfxMath.COLOR_OFFSET
            assertClose(0.3f, out[o]); assertClose(0.5f, out[o + 1])
            assertClose(0.7f, out[o + 2]); assertClose(0.9f, out[o + 3])
        }
    }

    @Test
    fun `same seed yields identical particle positions`() {
        val a = ParticleSystem(burstSpec(burst = 6, maxParticles = 6), Random(99))
        val b = ParticleSystem(burstSpec(burst = 6, maxParticles = 6), Random(99))
        // 换成球发射器才有多样性（点发射器位置全同，测不出种子）
        val sphere = burstSpec(burst = 6, maxParticles = 6).copy(
            emitter = EmitterParams(
                shape = EmitterShape.SPHERE, radius = 1f,
                speedMin = 1f, speedMax = 3f,
            ),
        )
        val c = ParticleSystem(sphere, Random(99))
        val d = ParticleSystem(sphere, Random(99))
        update(a, 0.05f)
        update(b, 0.05f)
        update(c, 0.05f)
        update(d, 0.05f)
        for (i in 0 until 6) {
            assertEquals(a.pool.px[i], b.pool.px[i], 1e-6f)
            assertEquals(c.pool.px[i], d.pool.px[i], 1e-6f)
            assertEquals(c.pool.vx[i], d.pool.vx[i], 1e-6f)
        }
        // 球采样确实产出了不同位置（采样不是常数）
        val distinct = (1 until 6).any { abs(c.pool.px[it] - c.pool.px[0]) > 1e-3f }
        assertTrue(distinct)
    }

    @Test
    fun `stop clear empties particles in one frame`() {
        val system = ParticleSystem(burstSpec(burst = 8, maxParticles = 8), Random(31))
        update(system, 0.016f)
        assertEquals(8, system.pool.activeCount)
        system.clear()
        assertEquals(0, system.pool.activeCount)
        assertTrue(system.isFinished)
    }

    // ── P1：Cone 发射器 / Sway / Turbulence / flipbook / palette ──────────

    @Test
    fun `cone emits from base disc with axis plus radial velocity`() {
        val spec = VfxSpec(
            id = "t", maxParticles = 24, burst = 24,
            lifetimeMin = 1f, lifetimeMax = 1f, sizeMin = 0.1f, sizeMax = 0.1f,
            emitter = EmitterParams(
                shape = EmitterShape.CONE,
                radius = 0.16f,
                direction = VfxVec3(0f, 1f, 0f),
                speedMin = 1f, speedMax = 1f,
                radialFraction = 0.5f,
            ),
        )
        val system = ParticleSystem(spec, Random(17))
        val out = FloatArray(24 * VfxMath.FLOATS_PER_PARTICLE)
        update(system, 0.016f, out = out)
        val p = system.pool
        var withRadial = 0
        for (i in 0 until p.activeCount) {
            // 位置：底盘圆内（含本帧积分位移 ≤ speed·dt 的余量）
            assertTrue(kotlin.math.sqrt(p.px[i] * p.px[i] + p.pz[i] * p.pz[i]) <= 0.17f)
            // 轴向速度恒 = speed；径向只加在水平两轴
            assertEquals(1f, p.vy[i], 1e-5f)
            val radial = kotlin.math.sqrt(p.vx[i] * p.vx[i] + p.vz[i] * p.vz[i])
            if (radial > 1e-4f) withRadial++
            // 径向速度上限 = speed × fraction（圆心粒子为 0）
            assertTrue(radial <= 0.5f + 1e-4f)
        }
        assertTrue("离圆心粒子应带径向分量", withRadial > 0)
    }

    @Test
    fun `sway rewrites axis velocity sinusoidally by elapsed age`() {
        val system = ParticleSystem(
            burstSpec(
                burst = 1, maxParticles = 1,
                behaviors = listOf(BehaviorParams.Sway(axis = "x", amplitude = 0.5f, frequency = 1f)),
            ),
            Random(23),
        )
        update(system, 0.016f)
        // 已活时长 = maxLife − life = 0.016（寿命固定）；v = amplitude·sin(2π·f·已活)
        val expected = 0.5f * Math.sin(2.0 * Math.PI * 0.016).toFloat()
        assertEquals(expected, system.pool.vx[0], 1e-4f)
        assertEquals(0f, system.pool.vy[0], 1e-6f)
        // 相位推进：下一帧已活 0.032
        update(system, 0.016f)
        assertEquals(0.5f * Math.sin(2.0 * Math.PI * 0.032).toFloat(), system.pool.vx[0], 1e-4f)
    }

    @Test
    fun `turbulence is deterministic bounded and strength-zero is a no-op`() {
        val spec = burstSpec(
            burst = 8, maxParticles = 8,
            behaviors = listOf(BehaviorParams.Turbulence(strength = 0.3f, scale = 2f, evolution = 1f)),
        )
        val a = ParticleSystem(spec, Random(41))
        val b = ParticleSystem(spec, Random(41))
        val none = ParticleSystem(burstSpec(burst = 8, maxParticles = 8), Random(41))
        update(a, 0.016f); update(b, 0.016f); update(none, 0.016f)
        for (i in 0 until 8) {
            assertEquals(a.pool.px[i], b.pool.px[i], 1e-6f)
            assertEquals(a.pool.vy[i], b.pool.vy[i], 1e-6f)
        }
        // 有界：单帧 0.016s 内速度扰动 ≤ strength×dt×常数（量级护栏）
        assertTrue(abs(a.pool.vx[0]) < 0.3f)
        // strength=0 退化为无行为
        val zero = ParticleSystem(
            burstSpec(burst = 1, maxParticles = 1, behaviors = listOf(BehaviorParams.Turbulence(strength = 0f))),
            Random(41),
        )
        update(zero, 0.016f)
        assertEquals(0f, zero.pool.vx[0], 1e-7f)
    }

    @Test
    fun `flipbook remaps uv subrect by life fraction`() {
        // 选址避开帧边界（frac×totalFrames 恰落整数时 toInt 被浮点噪声翻帧）
        // 且 dt ≤ DT_MAX(0.05)：寿命 0.16s、dt 0.05 → frac = 0.3125（二进制
        // 精确）→ ×4 = 1.25 → 帧 1（col1 row0）
        val spec = burstSpec(burst = 1, maxParticles = 1)
            .copy(flipbook = FlipbookParams(columns = 2, rows = 2), lifetimeMin = 0.16f, lifetimeMax = 0.16f)
        val system = ParticleSystem(spec, Random(51))
        val out = FloatArray(VfxMath.FLOATS_PER_PARTICLE)
        update(system, 0.05f, out = out)
        assertEquals(0.5f, out[3], 1e-5f) // V0 u = u0
        assertEquals(0.0f, out[4], 1e-5f) // V0 v = v0
        assertEquals(1.0f, out[18 + 3], 1e-5f) // V2 u = u0+du
        assertEquals(0.5f, out[18 + 4], 1e-5f) // V2 v = v0+dv（2×2 子矩形 dv=0.5）
        // 末帧：寿命略大于 dt（恰等时粒子被当场回收），frac ≈ 0.83 → 帧 3（col1 row1）
        val fresh = ParticleSystem(
            spec.copy(lifetimeMin = 0.06f, lifetimeMax = 0.06f), Random(51),
        )
        val out2 = FloatArray(VfxMath.FLOATS_PER_PARTICLE)
        update(fresh, 0.05f, out = out2)
        assertEquals(0.5f, out2[3], 1e-5f)
        assertEquals(0.5f, out2[4], 1e-5f)
    }

    @Test
    fun `palette samples per particle colors`() {
        val red = VfxColor(1f, 0f, 0f, 1f)
        val blue = VfxColor(0f, 0f, 1f, 1f)
        val spec = burstSpec(burst = 12, maxParticles = 12).copy(palette = listOf(red, blue))
        val system = ParticleSystem(spec, Random(61))
        update(system, 0.016f)
        val p = system.pool
        var reds = 0
        var blues = 0
        for (i in 0 until p.activeCount) {
            val isRed = p.cr[i] == 1f && p.cb[i] == 0f
            val isBlue = p.cr[i] == 0f && p.cb[i] == 1f
            assertTrue("颜色必须出自调色板", isRed || isBlue)
            if (isRed) reds++ else blues++
        }
        assertTrue("两种颜色都该被采到（12 粒二选一）", reds > 0 && blues > 0)
    }

    @Test
    fun `color curve multiplies base color keeping palette hue`() {
        // 2026-10-17 真机 confetti 教训：ColorOverLife 曾整体覆写粒子色，palette
        // 配色被白色 alpha 包络洗成纯白——语义改为 曲线 × 起始色
        val red = VfxColor(1f, 0f, 0f, 1f)
        val spec = burstSpec(
            burst = 1, maxParticles = 1,
            behaviors = listOf(
                BehaviorParams.ColorOverLife(
                    listOf(
                        ColorKey(0f, VfxColor(1f, 1f, 1f, 0f)),
                        ColorKey(1f, VfxColor(1f, 1f, 1f, 1f)),
                    ),
                ),
            ),
        ).copy(palette = listOf(red))
        val system = ParticleSystem(spec, Random(71))
        update(system, 0.016f)
        val p = system.pool
        assertClose(1f, p.cr[0]) // 红通道保留
        assertClose(0f, p.cb[0]) // 蓝通道保留
        assertClose(0.016f, p.ca[0], 1e-3f) // alpha 走曲线包络（0→1 淡入，t=0.016）
        // 起始白 + 彩色曲线 = 纯曲线渐变（spark_burst 语义不变）
        val curve = ParticleSystem(
            burstSpec(
                burst = 1, maxParticles = 1,
                behaviors = listOf(
                    BehaviorParams.ColorOverLife(
                        listOf(
                            ColorKey(0f, VfxColor(1f, 0f, 0f, 1f)),
                            ColorKey(1f, VfxColor(0f, 0f, 1f, 0f)),
                        ),
                    ),
                ),
            ),
            Random(73),
        )
        update(curve, 0.05f)
        assertClose(0.95f, curve.pool.cr[0])
        assertClose(0.05f, curve.pool.cb[0])
    }
}
