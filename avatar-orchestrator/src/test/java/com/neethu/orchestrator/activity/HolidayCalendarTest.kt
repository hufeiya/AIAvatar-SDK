package com.neethu.orchestrator.activity

import com.neethu.orchestrator.activity.HolidayCalendar.HolidayDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节日日历（P2 节日活动包，docs/prop-system-feasibility.md §5-H）单测：
 * 公历整月加权（12 月圣诞/10 月万圣）、农历已知年份表窗口（春节除夕~初六/
 * 中秋当天~次日）、表外年份优雅降级、生日命中（含 2/29 平年顺延）、
 * 长期记忆生日事实提取（三种写法 + 关键词门 + 非法值拒绝）。纯 JVM。
 */
class HolidayCalendarTest {

    // ── 公历整月加权 ────────────────────────────────────────────────────────

    @Test
    fun `12月整月命中圣诞——10月整月命中万圣`() {
        assertEquals(setOf(HolidayCalendar.CHRISTMAS), HolidayCalendar.tagsFor(HolidayDate(2026, 12, 1)))
        assertEquals(setOf(HolidayCalendar.CHRISTMAS), HolidayCalendar.tagsFor(HolidayDate(2026, 12, 25)))
        assertEquals(setOf(HolidayCalendar.CHRISTMAS), HolidayCalendar.tagsFor(HolidayDate(2030, 12, 31)))
        assertEquals(setOf(HolidayCalendar.HALLOWEEN), HolidayCalendar.tagsFor(HolidayDate(2026, 10, 3)))
        assertEquals(setOf(HolidayCalendar.HALLOWEEN), HolidayCalendar.tagsFor(HolidayDate(2027, 10, 31)))
    }

    @Test
    fun `平日无标签——11月与3月为空集`() {
        assertTrue(HolidayCalendar.tagsFor(HolidayDate(2026, 11, 1)).isEmpty())
        assertTrue(HolidayCalendar.tagsFor(HolidayDate(2026, 3, 8)).isEmpty())
    }

    // ── 农历已知年份表窗口 ──────────────────────────────────────────────────

    @Test
    fun `春节窗口=除夕到初六——2026年2月16日到23日`() {
        // 2026 春节 2/17：窗口 2/16（除夕）..2/23（初六）
        assertTrue(HolidayCalendar.SPRING_FESTIVAL in HolidayCalendar.tagsFor(HolidayDate(2026, 2, 16)))
        assertTrue(HolidayCalendar.SPRING_FESTIVAL in HolidayCalendar.tagsFor(HolidayDate(2026, 2, 17)))
        assertTrue(HolidayCalendar.SPRING_FESTIVAL in HolidayCalendar.tagsFor(HolidayDate(2026, 2, 23)))
        assertFalse(HolidayCalendar.tagsFor(HolidayDate(2026, 2, 15)).contains(HolidayCalendar.SPRING_FESTIVAL))
        assertFalse(HolidayCalendar.tagsFor(HolidayDate(2026, 2, 24)).contains(HolidayCalendar.SPRING_FESTIVAL))
    }

    @Test
    fun `春节跨月窗口——2028年1月26日`() {
        assertTrue(HolidayCalendar.SPRING_FESTIVAL in HolidayCalendar.tagsFor(HolidayDate(2028, 1, 26)))
        // 1/25 = 除夕仍在窗口（day-1）;2/2 = 初七出窗
        assertTrue(HolidayCalendar.SPRING_FESTIVAL in HolidayCalendar.tagsFor(HolidayDate(2028, 1, 25)))
        assertFalse(HolidayCalendar.tagsFor(HolidayDate(2028, 2, 2)).contains(HolidayCalendar.SPRING_FESTIVAL))
    }

    @Test
    fun `中秋窗口=当天到次日——2026年9月25日`() {
        assertTrue(HolidayCalendar.MID_AUTUMN in HolidayCalendar.tagsFor(HolidayDate(2026, 9, 25)))
        assertTrue(HolidayCalendar.MID_AUTUMN in HolidayCalendar.tagsFor(HolidayDate(2026, 9, 26)))
        assertFalse(HolidayCalendar.tagsFor(HolidayDate(2026, 9, 24)).contains(HolidayCalendar.MID_AUTUMN))
        assertFalse(HolidayCalendar.tagsFor(HolidayDate(2026, 9, 27)).contains(HolidayCalendar.MID_AUTUMN))
    }

    @Test
    fun `表外年份优雅降级——2031年春节中秋不加权`() {
        assertTrue(HolidayCalendar.tagsFor(HolidayDate(2031, 2, 10)).isEmpty())
        assertTrue(HolidayCalendar.tagsFor(HolidayDate(2031, 9, 20)).isEmpty())
    }

    @Test
    fun `多标签并存——2028年10月3日中秋撞国庆月不撞万圣`() {
        // 2028 中秋 10/3 落在 10 月——中秋命中,万圣按月命中（双标签并存合法）
        val tags = HolidayCalendar.tagsFor(HolidayDate(2028, 10, 3))
        assertTrue(HolidayCalendar.MID_AUTUMN in tags)
        assertTrue(HolidayCalendar.HALLOWEEN in tags)
    }

    // ── 生日命中 ────────────────────────────────────────────────────────────

    @Test
    fun `生日命中月日——闰年229平年顺延228`() {
        assertTrue(HolidayCalendar.isBirthday(HolidayDate(2026, 3, 8), 3, 8))
        assertFalse(HolidayCalendar.isBirthday(HolidayDate(2026, 3, 9), 3, 8))
        // 2/29 生日:闰年当天命中;平年顺延 2/28
        assertTrue(HolidayCalendar.isBirthday(HolidayDate(2028, 2, 29), 2, 29))
        assertTrue(HolidayCalendar.isBirthday(HolidayDate(2026, 2, 28), 2, 29))
        assertFalse(HolidayCalendar.isBirthday(HolidayDate(2028, 2, 28), 2, 29))
        // 非法月日防御
        assertFalse(HolidayCalendar.isBirthday(HolidayDate(2026, 13, 1), 13, 1))
        assertFalse(HolidayCalendar.isBirthday(HolidayDate(2026, 3, 8), 3, 32))
    }

    // ── 长期记忆生日事实提取 ────────────────────────────────────────────────

    @Test
    fun `生日事实三种写法——X月X日与ISO日期与MM-DD`() {
        assertEquals(3 to 8, HolidayCalendar.parseBirthdayMonthDay(listOf("用户的生日是3月8日")))
        assertEquals(3 to 8, HolidayCalendar.parseBirthdayMonthDay(listOf("用户生日是三月后不用管 3月8号")))
        assertEquals(5 to 20, HolidayCalendar.parseBirthdayMonthDay(listOf("user birthday is 2000-05-20")))
        assertEquals(12 to 31, HolidayCalendar.parseBirthdayMonthDay(listOf("生日 12.31 一起过")))
        assertEquals(9 to 1, HolidayCalendar.parseBirthdayMonthDay(listOf("他记得用户生日 9-1")))
    }

    @Test
    fun `生日提取——无关键词不误报与首条命中胜出与非法值跳过`() {
        // 无「生日/birthday」关键词的日期不当生日
        assertNull(HolidayCalendar.parseBirthdayMonthDay(listOf("2026年10月25日我们第一次见面")))
        // 多条按调用方顺序（新旧排序）首个合法命中胜出
        assertEquals(
            7 to 4,
            HolidayCalendar.parseBirthdayMonthDay(listOf("用户喜欢夏天", "用户生日是7月4日", "birthday 2000-01-02")),
        )
        // 关键词命中但数值非法（13月/32日/纯年份）= 跳过继续找
        assertNull(HolidayCalendar.parseBirthdayMonthDay(listOf("生日是 13月32日")))
        assertEquals(
            1 to 2,
            HolidayCalendar.parseBirthdayMonthDay(listOf("生日 13月32日", "birthday 2000-01-02")),
        )
        assertNull(HolidayCalendar.parseBirthdayMonthDay(emptyList()))
    }
}
