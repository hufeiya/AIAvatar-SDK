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
}
