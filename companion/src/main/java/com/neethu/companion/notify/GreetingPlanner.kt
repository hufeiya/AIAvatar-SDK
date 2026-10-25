package com.neethu.companion.notify

/**
 * G10 问候后台推送的纯判定逻辑（JVM 单测直测）：安静时段硬门 / 每日上限 /
 * 最小间隔 / 前台宽限。与前台问候调度（GreetingScheduler）独立——后台通知
 * 更打扰，各项阈值都更保守（docs/tavern-companion-feasibility.md §5 P3）。
 */
object GreetingPlanner {

    /** 判定输入快照（worker 组装；默认值即产品默认策略）。 */
    data class Input(
        val nowMs: Long,
        val hourOfDay: Int,
        val enabled: Boolean,
        val quietFrom: Int = 22,
        val quietTo: Int = 9,
        val quietEnabled: Boolean = true,
        val dailyCount: Int = 0,
        val dailyCap: Int = 1,
        val lastNotifyMs: Long? = null,
        val minIntervalMs: Long = 4 * 60 * 60 * 1000L,
        val lastForegroundMs: Long? = null,
        val foregroundGraceMs: Long = 10 * 60 * 1000L,
    ) {
        fun inQuietHours(hour: Int): Boolean = hourIn(hour, quietFrom, quietTo)
    }

    sealed interface Decision {
        /** 推送（[reason] 记日志）。 */
        data class Fire(val reason: String) : Decision

        /** 静默跳过（[reason] 记日志，防误判时排查）。 */
        data class Skip(val reason: String) : Decision
    }

    fun decide(input: Input): Decision {
        if (!input.enabled) return Decision.Skip("disabled")
        if (input.quietEnabled && input.inQuietHours(input.hourOfDay)) return Decision.Skip("quiet-hours")
        if (input.dailyCount >= input.dailyCap) return Decision.Skip("daily-cap")
        input.lastNotifyMs?.takeIf { input.nowMs - it < input.minIntervalMs }
            ?.let { return Decision.Skip("min-interval(${input.nowMs - it}ms)") }
        input.lastForegroundMs?.takeIf { input.nowMs - it < input.foregroundGraceMs }
            ?.let { return Decision.Skip("foreground-recent(${input.nowMs - it}ms)") }
        return Decision.Fire("all-gates-passed")
    }

    /** 跨午夜时段判断（autonomy 同款语义）。 */
    private fun hourIn(hour: Int, from: Int, to: Int): Boolean =
        if (from <= to) hour in from until to else hour >= from || hour < to
}
