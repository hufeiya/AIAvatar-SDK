package com.neethu.orchestrator.autonomy

import java.util.Calendar
import kotlin.random.Random

/**
 * 不定时问候调度器（docs/autonomy-feasibility.md §5）：前台版——app 可见且
 * FSM 挂载时，泊松随机（指数间隔，均值 [Config.meanIntervalMs]）+ 最小间隔
 * 硬底 + 每日上限 + 安静时段硬门（默认 23:00-08:00 静默，Replika 骚扰感教训）。
 * phase≠IDLE / 用户正在说话 → 推迟（调用方每拍 poll，条件满足才放行）。
 *
 * 纯逻辑零依赖：时钟/小时/随机全部注入，JVM 可测。
 */
class GreetingScheduler(
    val config: Config = Config(),
    private val rng: Random = Random.Default,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val hourProvider: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
) {

    data class Config(
        /** 两次问候最小间隔（硬底，泊松随机也压不过它）。 */
        val minIntervalMs: Long = 25 * 60_000L,
        /** 泊松均值间隔（等效 20~60min 一次的观感）。 */
        val meanIntervalMs: Long = 40 * 60_000L,
        /** 每日上限。 */
        val dailyCap: Int = 3,
        /** 安静时段 from 含 to 舍（支持跨午夜）。 */
        val quietFrom: Int = 23,
        val quietTo: Int = 8,
        val quietEnabled: Boolean = true,
        /** 问候触发后若被推迟，重查节拍。 */
        val deferRetryMs: Long = 20_000L,
    ) {
        val quiet: AutonomySchedule
            get() = AutonomySchedule(DEFAULT_BASE, emptyList(), quietFrom, quietTo, quietEnabled)

        private companion object {
            private val DEFAULT_BASE = mapOf(
                AutonomyStateKind.IDLE_STAND to 1f,
                AutonomyStateKind.WANDER to 1f,
                AutonomyStateKind.LOOKING to 1f,
                AutonomyStateKind.SITTING to 1f,
                AutonomyStateKind.LYING to 1f,
            )
        }
    }

    /** 每拍 poll 的结果。 */
    sealed interface Decision {
        /** 不问候。 */
        data object Wait : Decision

        /** 现在问候（调用方负责内容生成与 speak；本调度器记录回执计数）。 */
        data object Fire : Decision
    }

    private var nextDueAtMs: Long = Long.MAX_VALUE
    private var lastFiredAtMs: Long = -1L
    private var forcePending = false
    private var firedToday = 0
    private var dayKey = -1

    /** 挂载（app 可见/FSM 挂载）：排出下一次问候时刻。重复调用幂等。 */
    fun mount() {
        if (nextDueAtMs != Long.MAX_VALUE) return
        scheduleNext(nowMs())
    }

    /** 卸载（app 退后台/FSM 关）：取消待发的问候（重挂重新随机）。 */
    fun unmount() {
        nextDueAtMs = Long.MAX_VALUE
    }

    /** 用户刚说过话（30s 内说过 → 问候冷却重置，airi 教训「沉默是显式出口」）。 */
    fun onUserActivity() {
        if (nextDueAtMs == Long.MAX_VALUE) return
        scheduleNext(nowMs())
    }

    /** 强制立刻问候（ai_cmd autonomy_greet 测试入口；显式人令豁免最小间隔与安静时段——自然问候的硬门不动），日上限仍守。 */
    fun forceDue() {
        forcePending = true
        nextDueAtMs = nowMs()
    }

    /**
     * 每拍轮询。四道门顺序：挂载 → 安静时段硬门 → 日上限 → 推迟条件
     * （[phaseBusy]/[userSpeaking]）→ 到点放行。forceDue 的一次性豁免只跳
     * 安静门与最小间隔（真机验收深夜也能测问候链）。
     */
    fun poll(phaseBusy: Boolean, userSpeaking: Boolean): Decision {
        val now = nowMs()
        rollDayIfNeeded(now)
        if (nextDueAtMs == Long.MAX_VALUE) return Decision.Wait
        if (now < nextDueAtMs) return Decision.Wait
        val bypass = forcePending
        if (!bypass && config.quietEnabled && config.quiet.inQuietHours(hourProvider())) {
            // 安静时段硬门（绝对的）：重排到 minInterval 之后重查，静默期
            // 内每次到点都被这道门拦回——poll 节拍便宜，宁可多查不冒险。
            scheduleNext(now)
            return Decision.Wait
        }
        if (firedToday >= config.dailyCap) {
            nextDueAtMs = Long.MAX_VALUE // 今日额度用尽，明天 rollDay 重排
            return Decision.Wait
        }
        if (phaseBusy || userSpeaking) {
            nextDueAtMs = now + config.deferRetryMs
            return Decision.Wait
        }
        forcePending = false
        if (!bypass && lastFiredAtMs >= 0 && now - lastFiredAtMs < config.minIntervalMs) {
            scheduleNext(now)
            return Decision.Wait
        }
        firedToday++
        lastFiredAtMs = now
        scheduleNext(now)
        return Decision.Fire
    }

    /** 观测行（ai_cmd）。 */
    fun stateLine(): String {
        val now = nowMs()
        val due = if (nextDueAtMs == Long.MAX_VALUE) "off" else {
            "${((nextDueAtMs - now).coerceAtLeast(0)) / 1000}s"
        }
        return "greeting dueIn=$due firedToday=$firedToday/${config.dailyCap} " +
            "last=${if (lastFiredAtMs < 0) "never" else "${(now - lastFiredAtMs) / 1000}s ago"}"
    }

    private fun scheduleNext(fromMs: Long) {
        var delay = -kotlin.math.ln(1.0 - rng.nextDouble()) * config.meanIntervalMs
        if (delay < config.minIntervalMs) delay = config.minIntervalMs.toDouble()
        nextDueAtMs = fromMs + delay.toLong()
    }

    private fun rollDayIfNeeded(now: Long) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val key = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        if (key != dayKey) {
            dayKey = key
            firedToday = 0
            if (nextDueAtMs == Long.MAX_VALUE && lastFiredAtMs != Long.MIN_VALUE) {
                scheduleNext(now)
            }
        }
    }
}
