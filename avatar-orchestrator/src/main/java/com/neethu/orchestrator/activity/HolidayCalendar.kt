package com.neethu.orchestrator.activity

import java.util.Calendar

/**
 * 节日日历（P2 节日活动包，docs/prop-system-feasibility.md §5-H/§7-P2）：
 * 「今天是什么日子」→ 活动包标签集。两个消费端共用同一份判定——FSM 选点层
 * （[com.neethu.orchestrator.autonomy.AutonomyDirector] 对带 [ActivityEntry.holiday]
 * 的活动在标签命中日加权）与 LLM 工具投影（app 层注册时给节日活动描述加
 * 「今日」标注）。纯 JVM 可测：日期三元组进出，无时钟依赖（[today] 除外）。
 *
 * 公历节日按月加权（设计文档口径：12 月圣诞、10 月万圣——整月保持节日氛围）；
 * 农历节日（春节/中秋）无本地依赖的换算，用 **已知年份表**（2026~2030），
 * 表外年份优雅降级为不加权（活动仍在目录里，LLM/用户指令不受限）。
 */
object HolidayCalendar {

    /** 日期三元组（决策输入的最小面；避免 java.time 在 JVM/Android 双侧的 API 边界问题）。 */
    data class HolidayDate(val year: Int, val month: Int, val day: Int)

    const val CHRISTMAS = "christmas"
    const val HALLOWEEN = "halloween"
    const val SPRING_FESTIVAL = "spring_festival"
    const val MID_AUTUMN = "mid_autumn"
    const val BIRTHDAY = "birthday"

    /** 圣诞月：12 月整月加权（§5-H「12 月圣诞」）。 */
    private const val CHRISTMAS_MONTH = 12

    /** 万圣月：10 月整月加权（§5-H「10 月万圣」）。 */
    private const val HALLOWEEN_MONTH = 10

    /**
     * 农历节日已知年份表（公历当天；来源为公开万年历，2026~2030 五年覆盖，
     * 过表 = 返回空集降级）。春节窗口 = 除夕前 1 天 ~ 初六（day-1 .. day+6），
     * 中秋窗口 = 当天 ~ 次日（day .. day+1）。
     */
    private val springFestivalDates: Map<Int, HolidayDate> = mapOf(
        2026 to HolidayDate(2026, 2, 17),
        2027 to HolidayDate(2027, 2, 6),
        2028 to HolidayDate(2028, 1, 26),
        2029 to HolidayDate(2029, 2, 13),
        2030 to HolidayDate(2030, 2, 3),
    )

    private val midAutumnDates: Map<Int, HolidayDate> = mapOf(
        2026 to HolidayDate(2026, 9, 25),
        2027 to HolidayDate(2027, 9, 15),
        2028 to HolidayDate(2028, 10, 3),
        2029 to HolidayDate(2029, 9, 22),
        2030 to HolidayDate(2030, 9, 12),
    )

    private const val SPRING_FESTIVAL_BEFORE_DAYS = 1
    private const val SPRING_FESTIVAL_AFTER_DAYS = 6
    private const val MID_AUTUMN_AFTER_DAYS = 1

    /** 系统今天（调试覆盖不走这里——app 层用自己的 override 日期调 [tagsFor]）。 */
    fun today(): HolidayDate {
        val c = Calendar.getInstance()
        return HolidayDate(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** 某天命中的节日标签集（无节日 = 空集；表外年份农历节日缺席）。 */
    fun tagsFor(date: HolidayDate): Set<String> {
        val out = mutableSetOf<String>()
        if (date.month == CHRISTMAS_MONTH) out += CHRISTMAS
        if (date.month == HALLOWEEN_MONTH) out += HALLOWEEN
        springFestivalDates[date.year]?.let {
            if (dayIndex(date) in dayIndex(it) - SPRING_FESTIVAL_BEFORE_DAYS..
                dayIndex(it) + SPRING_FESTIVAL_AFTER_DAYS
            ) {
                out += SPRING_FESTIVAL
            }
        }
        midAutumnDates[date.year]?.let {
            if (dayIndex(date) in dayIndex(it)..dayIndex(it) + MID_AUTUMN_AFTER_DAYS) {
                out += MID_AUTUMN
            }
        }
        return out
    }

    /** 用户生日命中判定（长期记忆提取的月-日）；2/29 生日在平年顺延到 2/28 庆祝。 */
    fun isBirthday(today: HolidayDate, birthdayMonth: Int, birthdayDay: Int): Boolean {
        if (birthdayMonth !in 1..12 || birthdayDay !in 1..31) return false
        if (today.month == birthdayMonth && today.day == birthdayDay) return true
        return birthdayMonth == 2 && birthdayDay == 29 &&
            today.month == 2 && today.day == 28 && !isLeapYear(today.year)
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    /** 天序号（civil days 自 1970-01-01；窗口判定的整数运算）。 */
    private fun dayIndex(date: HolidayDate): Long =
        java.time.LocalDate.of(date.year, date.month, date.day).toEpochDay()

    // ── 生日事实提取（长期记忆 → 月-日）────────────────────────────────

    private val BIRTHDAY_KEYWORD = Regex("生日|birthday", RegexOption.IGNORE_CASE)
    private val PAT_M月D日 = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")
    private val PAT_ISO = Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)")
    private val PAT_COMPACT = Regex("(?<!\\d)(\\d{1,2})[-/.](\\d{1,2})(?!\\d)")

    /**
     * 从长期记忆事实清单里提取用户生日的 (月, 日)；命中含「生日/birthday」的
     * 条目 → 依次尝试 `X月X日` / `YYYY-MM-DD` / `MM-DD`（范围校验 month 1-12、
     * day 1-31）→ 首个合法命中胜出（调用方按新旧排序喂入）。无命中 = null
     * （生日加权静默缺席，其余四个节日不受影响）。
     */
    fun parseBirthdayMonthDay(facts: List<String>): Pair<Int, Int>? {
        for (fact in facts) {
            if (!BIRTHDAY_KEYWORD.containsMatchIn(fact)) continue
            PAT_M月D日.find(fact)?.let { m ->
                val (month, day) = listOf(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                if (valid(month, day)) return month to day
            }
            PAT_ISO.find(fact)?.let { m ->
                val (month, day) = listOf(m.groupValues[2].toInt(), m.groupValues[3].toInt())
                if (valid(month, day)) return month to day
            }
            PAT_COMPACT.find(fact)?.let { m ->
                val (month, day) = listOf(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                if (valid(month, day)) return month to day
            }
        }
        return null
    }

    private fun valid(month: Int, day: Int): Boolean = month in 1..12 && day in 1..31
}
