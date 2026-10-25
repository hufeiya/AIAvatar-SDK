package com.neethu.aiavatar_sdk

import com.neethu.corelib.vfx.VfxBlending
import com.neethu.corelib.vfx.VfxInfo
import com.neethu.orchestrator.activity.HolidayCalendar.HolidayDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动执行器收场逻辑的纯函数锁（P1 真机验收第 1 轮两项修复）：
 * - ⑤入座竞态：配方动画与锚点 sitLoopClip 同款时不重播（重播会把定姿过渡
 *   one-shot 顶出 base 槽,`isOneShotActive()` 永真,ENTERING 卡死不对齐）；
 * - ①特效残留：活动收场的 VFX 停止目标——句柄精确停、无句柄按 preset 兜底。
 */
class ActivityExecutorLogicTest {

    // ── clipMatchesAnchorLoop（不重播判据）───────────────────────────────

    @Test
    fun `reading reuses the reading seat sit loop - strip animations prefix`() {
        val reading = "06_坐姿/Elbows On Legs Leaning Forward.vrma"
        val sitLoop = "animations/06_坐姿/Elbows On Legs Leaning Forward.vrma"
        assertTrue(clipMatchesAnchorLoop(reading, sitLoop))
        // 反向写法（配方带前缀）也命中——两侧归一同 binder
        assertTrue(clipMatchesAnchorLoop(sitLoop, reading))
    }

    @Test
    fun `activities with own clips do not match the anchor loop`() {
        // 坐床边喝饮料:床沿循环是抖脚,活动要播喝罐装——必须自己播
        assertFalse(
            clipMatchesAnchorLoop(
                "07_日常互动/Having A Seat And Drinking A Canned Beverage.vrma",
                "animations/06_坐姿/Sit With Fidgeting Feet.vrma",
            ),
        )
        // 躺床刷手机:lie 锚没有循环剪辑(null)——必须自己播躺姿
        assertFalse(clipMatchesAnchorLoop("06_坐姿/Laying On A Bed Idle.vrma", null))
    }

    // ── activityVfxStopTargets（收场停止目标）────────────────────────────

    private fun info(id: String, preset: String) = VfxInfo(
        id = id, presetId = preset, blending = VfxBlending.ADDITIVE,
        activeParticles = 31, ageSeconds = 155.7f,
    )

    @Test
    fun `handle present stops only the handle - no collateral on same preset`() {
        val active = listOf(info("fx1", "magic_circle"), info("fx2", "magic_circle"))
        assertEquals(listOf("fx1"), activityVfxStopTargets("magic_circle", "fx1", active))
    }

    @Test
    fun `missing handle falls back to preset scan`() {
        val active = listOf(info("fx1", "magic_circle"), info("fx9", "rain"))
        assertEquals(listOf("fx1"), activityVfxStopTargets("magic_circle", null, active))
    }

    @Test
    fun `nothing played or nothing in scene yields empty targets`() {
        assertTrue(activityVfxStopTargets(null, null, listOf(info("fx1", "rain"))).isEmpty())
        assertTrue(activityVfxStopTargets("magic_circle", null, emptyList()).isEmpty())
    }

    // ── parseHolidayDate（P2 activity forceDate 参数解析）──────────────────

    @Test
    fun `forceDate parses yyyy-MM-dd and rejects malformed input`() {
        assertEquals(HolidayDate(2026, 12, 25), parseHolidayDate("2026-12-25"))
        assertEquals(HolidayDate(2027, 2, 6), parseHolidayDate("2027-2-6"))
        // 非法:格式错/月日出界/null 语义(空串)
        assertEquals(null, parseHolidayDate("2026/12/25"))
        assertEquals(null, parseHolidayDate("2026-13-01"))
        assertEquals(null, parseHolidayDate("2026-12-32"))
        assertEquals(null, parseHolidayDate("yesterday"))
        assertEquals(null, parseHolidayDate(""))
    }
}
