package com.neethu.companion

import com.neethu.companion.notify.GreetingPlanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GreetingPlanner(G10)门控矩阵:安静时段/每日上限/最小间隔/前台宽限/开关。
 * 纯 JVM;小时边界跨午夜(22→9)单测锁死。
 */
class GreetingPlannerTest {

    private fun input(
        hour: Int = 14,
        enabled: Boolean = true,
        dailyCount: Int = 0,
        lastNotifyMs: Long? = null,
        lastForegroundMs: Long? = null,
    ) = GreetingPlanner.Input(
        nowMs = 1_000_000L,
        hourOfDay = hour,
        enabled = enabled,
        dailyCount = dailyCount,
        lastNotifyMs = lastNotifyMs,
        lastForegroundMs = lastForegroundMs,
    )

    @Test
    fun `fires when all gates pass`() {
        val d = GreetingPlanner.decide(input())
        assertTrue(d is GreetingPlanner.Decision.Fire)
    }

    @Test
    fun `disabled never fires`() {
        val d = GreetingPlanner.decide(input(enabled = false))
        assertEquals("disabled", (d as GreetingPlanner.Decision.Skip).reason)
    }

    @Test
    fun `quiet hours hard gate including cross-midnight edges`() {
        // quietFrom=22, quietTo=9 → 22,23,0..8 安静;9..21 可推
        for (hour in listOf(22, 23, 0, 5, 8)) {
            val d = GreetingPlanner.decide(input(hour = hour))
            assertTrue("hour=$hour 应安静跳过", d is GreetingPlanner.Decision.Skip)
        }
        for (hour in listOf(9, 12, 21)) {
            val d = GreetingPlanner.decide(input(hour = hour))
            assertTrue("hour=$hour 应可推送", d is GreetingPlanner.Decision.Fire)
        }
    }

    @Test
    fun `daily cap stops repeats`() {
        val d = GreetingPlanner.decide(input(dailyCount = 1))
        assertTrue(d is GreetingPlanner.Decision.Skip)
    }

    @Test
    fun `min interval blocks recent notifications`() {
        val d = GreetingPlanner.decide(input(lastNotifyMs = 900_000L)) // 100s 前
        assertTrue((d as GreetingPlanner.Decision.Skip).reason.startsWith("min-interval"))
    }

    @Test
    fun `foreground grace blocks push right after app use`() {
        val d = GreetingPlanner.decide(input(lastForegroundMs = 999_000L)) // 1s 前
        assertTrue((d as GreetingPlanner.Decision.Skip).reason.startsWith("foreground-recent"))
    }

    @Test
    fun `stale foreground and old notify do not block`() {
        val d = GreetingPlanner.decide(
            input(lastNotifyMs = -1_000_000_000L, lastForegroundMs = -1_000_000_000L), // 远古时间戳
        )
        assertTrue(d is GreetingPlanner.Decision.Fire)
    }
}
