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
        out: FloatArray = FloatArray(64 * VfxMath.FLOATS_PER_PARTICLE),
    ): Int = system.update(
        dt, anchor.x, anchor.y, anchor.z, right, up, forward, out, 0,
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
}
