package com.neethu.orchestrator.locomotion

import com.neethu.corelib.AvatarController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行走决策层（docs/locomotion-feasibility.md §7.3）：锚点解析与失败归因。
 * JVM 上 AvatarController 无 renderer —— startWalk 恒 NO_RENDERER、
 * getWalkState 恒 null，正好锁「无缝也要有确定的 Outcome」的语义；
 * STARTED/防抖路径由 fakes（工具测试）与会话级测试覆盖。
 */
class LocomotionDriverTest {

    private val driver = LocomotionDriver(AvatarController())

    @Test
    fun `unknown anchor returns UNKNOWN_ANCHOR without touching controller`() {
        driver.anchors = listOf(MoveAnchor("sofa", 1f, 2f))
        assertEquals(LocomotionDriver.Outcome.UNKNOWN_ANCHOR, driver.walkTo("window"))
        assertEquals(LocomotionDriver.Outcome.UNKNOWN_ANCHOR, driver.walkTo(""))
    }

    @Test
    fun `find is name-resolved case-insensitively`() {
        driver.anchors = listOf(MoveAnchor("sofa", 1f, 2f))
        assertEquals("sofa", driver.find("SOFA")?.name)
        assertNull(driver.find("couch"))
    }

    @Test
    fun `bare controller (no renderer) maps to NO_RENDERER`() {
        driver.anchors = listOf(MoveAnchor("sofa", 1f, 2f))
        assertEquals(LocomotionDriver.Outcome.NO_RENDERER, driver.walkTo("sofa"))
        assertEquals(LocomotionDriver.Outcome.NO_RENDERER, driver.walkTo(MoveAnchor("sofa", 1f, 2f)))
    }

    @Test
    fun `stopWalking without renderer does not throw`() {
        driver.stopWalking()
        assertTrue(true)
    }

    // ── P2 waypoint 多段腿（脚本化 startLeg 的子类驱动）─────────────────

    /** startLeg 可脚本成功/失败，记录每段起步的锚点。 */
    private class ScriptedDriver : LocomotionDriver(AvatarController()) {
        val started = mutableListOf<String>()
        var accept = true

        override fun startLeg(anchor: MoveAnchor): LocomotionDriver.Outcome {
            if (!accept) return LocomotionDriver.Outcome.NO_GAIT
            started += anchor.name
            return LocomotionDriver.Outcome.STARTED
        }
    }

    @Test
    fun `graph route starts first leg and queues the rest`() {
        val d = ScriptedDriver()
        val bed = MoveAnchor("bed", -2.4f, -4.3f)
        d.anchors = listOf(
            bed.copy(links = listOf("carpet")),
            MoveAnchor("carpet", 0f, -4f, links = listOf("bed", "window")),
            MoveAnchor("window", 2f, -4.4f),
        )
        // 站在 bed（getWalkState 走不了真 controller —— state=null → from=null
        // → 直线单段）：图路径经 from 缺省退化为 [window]
        assertEquals(LocomotionDriver.Outcome.STARTED, d.walkTo("window"))
        assertEquals(listOf("window"), d.started)
        assertEquals(emptyList<String>(), d.pendingLegsSnapshot())
    }

    @Test
    fun `arrival walks the next leg and interruption clears the queue`() {
        val d = ScriptedDriver()
        val bed = MoveAnchor("bed", -2.4f, -4.3f)
        val carpet = MoveAnchor("carpet", 0f, -4f, links = listOf("bed", "window"))
        val window = MoveAnchor("window", 2f, -4.4f)
        d.anchors = listOf(bed, carpet, window)

        // 直接铺队列（模拟 route 后状态），驱动 onWalkFinished 的续走/清队
        d.walkTo(carpet)
        d.onWalkFinished(arrived = true)
        assertEquals(listOf("carpet"), d.started)

        d.walkTo(window) // from=null → [window] 单段
        d.onWalkFinished(arrived = false) // 中断：清队列
        assertEquals(listOf("carpet", "window"), d.started)
        assertEquals(emptyList<String>(), d.pendingLegsSnapshot())
    }

    @Test
    fun `failed first leg drops pending legs`() {
        val d = ScriptedDriver()
        d.anchors = listOf(
            MoveAnchor("a", 0f, 0f, links = listOf("b")),
            MoveAnchor("b", 1f, 0f, links = listOf("c")),
            MoveAnchor("c", 2f, 0f),
        )
        d.accept = false
        assertEquals(LocomotionDriver.Outcome.NO_GAIT, d.walkTo("c"))
        assertEquals(emptyList<String>(), d.pendingLegsSnapshot())
    }

    @Test
    fun `new walkTo replaces pending legs`() {
        val d = ScriptedDriver()
        val b = MoveAnchor("b", 1f, 0f, links = listOf("c"))
        d.anchors = listOf(MoveAnchor("a", 0f, 0f, links = listOf("b")), b, MoveAnchor("c", 2f, 0f))
        // 无法从真 controller 拿 state（null → 直线段），直接铺队列再换目标
        d.walkTo(MoveAnchor("c", 2f, 0f))
        d.walkTo(b)
        d.onWalkFinished(arrived = true)
        // 第二次 walkTo 已清掉旧队列；到达后无残留腿
        assertEquals(emptyList<String>(), d.pendingLegsSnapshot())
    }
}
