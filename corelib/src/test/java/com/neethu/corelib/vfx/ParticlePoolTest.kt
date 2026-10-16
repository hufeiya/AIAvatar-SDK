package com.neethu.corelib.vfx

import com.neethu.corelib.vfx.sim.ParticlePool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ParticlePool 纯数学锁（docs/vfx-feasibility.md §六 P0 单测清单）：
 * 追加/swap-with-last 回收/RECYCLE_OLDEST 槽位判据/lifeFraction/清空。
 */
class ParticlePoolTest {

    private fun append(pool: ParticlePool, x: Float, life: Float): Int =
        pool.append(
            x = x, y = 0f, z = 0f, velX = 0f, velY = 0f, velZ = 0f,
            lifeSec = life, size = 0.1f, angleRad = 0f, angVelRad = 0f,
            r = 1f, g = 1f, b = 1f, a = 1f,
        )

    @Test
    fun `append fills slots sequentially and reports full`() {
        val pool = ParticlePool(3)
        assertEquals(0, append(pool, 1f, 1f))
        assertEquals(1, append(pool, 2f, 1f))
        assertEquals(2, append(pool, 3f, 1f))
        assertEquals(3, pool.activeCount)
        assertTrue(pool.isFull)
        assertEquals(-1, append(pool, 4f, 1f))
        assertEquals(3, pool.activeCount)
    }

    @Test
    fun `killAt swaps last into the hole`() {
        val pool = ParticlePool(4)
        append(pool, 1f, 4f)
        append(pool, 2f, 3f)
        append(pool, 3f, 2f)
        append(pool, 4f, 1f)
        pool.killAt(0)
        assertEquals(3, pool.activeCount)
        // 尾部粒子 4（剩余寿命 1）换位到 0 号槽
        assertEquals(4f, pool.px[0], 1e-6f)
        assertEquals(1f, pool.life[0], 1e-6f)
        // 其余保持
        assertEquals(2f, pool.px[1], 1e-6f)
        assertEquals(3f, pool.px[2], 1e-6f)
    }

    @Test
    fun `killAt out of range is a no-op`() {
        val pool = ParticlePool(2)
        append(pool, 1f, 1f)
        pool.killAt(1)
        pool.killAt(-1)
        assertEquals(1, pool.activeCount)
    }

    @Test
    fun `findShortestLife returns the minimum remaining life`() {
        val pool = ParticlePool(4)
        append(pool, 1f, 5f)
        append(pool, 2f, 2f)
        append(pool, 3f, 9f)
        assertEquals(1, pool.findShortestLife())
        pool.life[1] = 0.5f
        assertEquals(1, pool.findShortestLife())
        // 平局取更小下标
        pool.life[2] = 0.5f
        assertEquals(1, pool.findShortestLife())
    }

    @Test
    fun `findShortestLife on empty pool returns minus one`() {
        assertEquals(-1, ParticlePool(4).findShortestLife())
    }

    @Test
    fun `overwrite replaces fields without changing count`() {
        val pool = ParticlePool(2)
        append(pool, 1f, 5f)
        append(pool, 2f, 5f)
        pool.overwrite(
            i = 0,
            x = 9f, y = 8f, z = 7f, velX = 1f, velY = 2f, velZ = 3f,
            lifeSec = 0.4f, size = 0.2f, angleRad = 0.1f, angVelRad = 0.2f,
            r = 0f, g = 0.5f, b = 1f, a = 0.25f,
        )
        assertEquals(2, pool.activeCount)
        assertEquals(9f, pool.px[0], 1e-6f)
        assertEquals(0.4f, pool.life[0], 1e-6f)
        assertEquals(0.4f, pool.maxLife[0], 1e-6f)
        assertEquals(0.2f, pool.curSize[0], 1e-6f)
        assertEquals(0.25f, pool.ca[0], 1e-6f)
    }

    @Test
    fun `lifeFraction spans birth to death`() {
        val pool = ParticlePool(1)
        append(pool, 0f, life = 2f)
        assertEquals(0f, pool.lifeFraction(0), 1e-6f)
        pool.life[0] = 1f
        assertEquals(0.5f, pool.lifeFraction(0), 1e-6f)
        pool.life[0] = 0f
        assertEquals(1f, pool.lifeFraction(0), 1e-6f)
    }

    @Test
    fun `clear empties the pool for reuse`() {
        val pool = ParticlePool(2)
        append(pool, 1f, 1f)
        append(pool, 2f, 1f)
        pool.clear()
        assertEquals(0, pool.activeCount)
        assertFalse(pool.isFull)
        assertEquals(0, append(pool, 3f, 1f))
    }
}
