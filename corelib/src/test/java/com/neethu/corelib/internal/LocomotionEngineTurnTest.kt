package com.neethu.corelib.internal

import com.neethu.corelib.WalkBounds
import com.neethu.corelib.WalkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 原地转向模式（多虚拟人 P3 对谈位互相对着）的纯数学回归：只转不挪、
 * 对齐即到达、走动中拒绝、startWalk/stopWalk 退出纯转模式。
 */
class LocomotionEngineTurnTest {

    @Test
    fun `原地转向只转不挪 对齐后到达`() {
        val engine = LocomotionEngine()
        // 面朝 +Z（heading 0），目标在 -Z：要转 180°
        assertTrue(engine.startTurnTo(0f, -4f, null))
        assertEquals(WalkPhase.TURNING, engine.snapshot().phase)

        var heading = 0f
        var x = 0f
        var z = 0f
        var arrived = false
        val dt = 1f / 60f
        var t = 0f
        while (t < 30f) {
            val m = engine.update(dt, x, 0f, z)
            // 纯转帧：零平移
            assertEquals(0f, m?.dx ?: 0f, 1e-6f)
            assertEquals(0f, m?.dz ?: 0f, 1e-6f)
            heading += m?.dHeadingRad ?: 0f
            t += dt
            if (engine.consumeArrived()) {
                arrived = true
                break
            }
        }
        assertTrue("turn did not arrive", arrived)
        assertEquals(Math.PI.toFloat(), abs(heading), 0.05f)
        assertEquals(WalkPhase.IDLE, engine.snapshot().phase)
    }

    @Test
    fun `目标在脚下立即到达不空转`() {
        val engine = LocomotionEngine()
        assertTrue(engine.startTurnTo(0f, 0f, null))
        assertNull(engine.update(1f / 60f, 0f, 0f, 0f))
        assertTrue(engine.consumeArrived())
        assertEquals(WalkPhase.IDLE, engine.snapshot().phase)
    }

    @Test
    fun `走动中拒绝纯转 stopWalk 后可转`() {
        val engine = LocomotionEngine()
        engine.startWalk(2f, 0f, 1f, null)
        assertFalse(engine.startTurnTo(0f, -4f, null))
        engine.stopWalk()
        assertTrue(engine.startTurnTo(0f, -4f, null))
    }

    @Test
    fun `startWalk 取代纯转模式`() {
        val engine = LocomotionEngine()
        assertTrue(engine.startTurnTo(0f, -4f, null))
        engine.startWalk(2f, 0f, 1f, null)
        // walk 帧会产生位移（对齐后），不再是纯转的零平移语义
        var x = 0f
        var z = 0f
        val dt = 1f / 60f
        var walked = false
        var t = 0f
        while (t < 30f) {
            val m = engine.update(dt, x, 0f, z)
            x += m?.dx ?: 0f
            z += m?.dz ?: 0f
            t += dt
            if ((m?.dx ?: 0f) != 0f || (m?.dz ?: 0f) != 0f) {
                walked = true
                break
            }
        }
        assertTrue(walked)
    }

    @Test
    fun `bounds clamp 对纯转目标生效`() {
        val engine = LocomotionEngine()
        val bounds = WalkBounds(minX = -1f, maxX = 1f, minZ = -10f, maxZ = 10f)
        assertTrue(engine.startTurnTo(50f, -4f, bounds))
        assertEquals(1f, engine.snapshot().targetX, 1e-4f)
    }
}
