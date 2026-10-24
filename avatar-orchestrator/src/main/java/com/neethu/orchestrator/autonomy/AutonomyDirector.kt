package com.neethu.orchestrator.autonomy

import java.util.Calendar
import kotlin.random.Random

/**
 * 自主决策状态机（docs/autonomy-feasibility.md §4）：默认零 LLM 的确定性
 * 大脑——作息先验表（[AutonomySchedule]）+ 加权随机 + 禁忌规则决定下一状态，
 * 冲突仲裁（§4.3 优先级：用户会话 > 技能 > LLM 工具指令 > 自主）经每拍
 * 注入的 [AutonomyWorld] 机票。动作词汇表（[spots]）由集成方投影注入，
 * 换场景 = 换注入，FSM 零改动。
 *
 * 决策节拍：集成方每 ≈1s 调 [tick]，返回至多一组 [AutonomyDirective] 由
 * executor 顺序执行（视线复位+就位这类成对指令）；执行结果经世界快照回读
 * （autonomyInFlight/walking/contactKind），FSM 不需要显式回执。指令被拒
 * （就位链忙/锚点失效）时集成方调 [markDirectiveAborted]，FSM 退避重选。
 *
 * 纯 JVM 可测：时间与随机全部注入。
 */
class AutonomyDirector(
    var spots: List<AutonomySpot>,
    val schedule: AutonomySchedule = AutonomySchedule.DEFAULT,
    private val limits: AutonomyLimits = AutonomyLimits(),
    private val rng: Random = Random.Default,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val hourProvider: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
) {

    /** 当前状态（含外部就位的被动续跑）。 */
    var state: AutonomyStateKind = AutonomyStateKind.IDLE_STAND
        private set

    /** 当前所在锚点（坐/躺/看就位点；走动/站桩中为 null 或上一锚点）。 */
    var currentAnchorId: String? = null
        private set

    /** FSM 是否挂载运行（app 设置开关的镜像；关 = tick 直接 no-op）。 */
    var enabled: Boolean = true

    /**
     * 挂载（冷启动）：先站桩一个 IDLE dwell 再开始决策——一上来就起身乱走
     * 没有生命感，先「看到你」安静几秒。会话就绪/场景注入后调用。
     */
    fun mount() {
        nextDecisionAtMs = nowMs() + dwellFor(AutonomyStateKind.IDLE_STAND)
    }

    private val _transitions = ArrayDeque<AutonomyTransition>()

    /** 最近 20 条转移记录（ai_cmd autonomy_log）。 */
    val transitions: List<AutonomyTransition> get() = _transitions.toList()

    /** 下一次决策时间（ai_cmd autonomy_state 的倒计时正本）。 */
    var nextDecisionAtMs: Long = 0
        private set

    /** LOOKING 续盯节拍（就位链 8s 回退 CAMERA 之后补发 GazePoint）。 */
    private var nextGazeSustainAtMs: Long = 0

    /** 已发出 GoTo 且尚未观察到就位/失败。 */
    private var goToPending: AutonomyDirective.GoTo? = null

    /** 最近一次执行失败的锚点（退避期内避让，防同点反复撞墙）。 */
    private var abortedSpotId: String? = null

    /** 冻结期标记：冻结解除后立刻做一次决策检查（dwell 不再等满）。 */
    private var wasFrozen = false

    /** 观望外部走动（LLM move_to 在跑）：走完以新位置续跑，不回弹。 */
    private var observingExternalWalk = false

    /** 每小时动作滚动窗口（GoTo 计数，防「抽搐感」）。 */
    private val actionTimes = ArrayDeque<Long>()

    /** 会话冻结期间的停步指令只发一次（复用 stop 语义，不连发）。 */
    private var stopDirectiveSent = false

    // ── 每拍决策 ─────────────────────────────────────────────────────────

    fun tick(world: AutonomyWorld): List<AutonomyDirective> {
        if (!enabled || spots.isEmpty()) return emptyList()
        val now = nowMs()

        // 会话冻结（用户会话/VAD 优先级最高）：走动让路（停步一次），坐/躺
        // 不强制起身；dwell 顺延，解冻后立刻重查。
        if (world.frozen) {
            wasFrozen = true
            if (goToPending != null && world.walking && !stopDirectiveSent) {
                stopDirectiveSent = true
                goToPending = null
                setState(AutonomyStateKind.IDLE_STAND, null, "frozen-stop")
                return listOf(AutonomyDirective.StopWalking)
            }
            shiftDwell(1_000)
            return emptyList()
        }
        stopDirectiveSent = false
        val unfroze = wasFrozen
        wasFrozen = false

        // 技能/表演占用：让位，不决策，dwell 顺延。
        if (world.skillBusy) {
            shiftDwell(1_000)
            return emptyList()
        }

        // 外部走动（LLM move_to / 标签通道）：观望到走完，以新位置续跑。
        if (world.walking && !world.autonomyInFlight && goToPending == null) {
            observingExternalWalk = true
            shiftDwell(1_000)
            return emptyList()
        }
        if (observingExternalWalk && !world.walking) {
            observingExternalWalk = false
            adoptExternalState(world, "external")
            return emptyList()
        }

        // 外部刚动过（工具/技能 5s 内）：观望并就地认领结果。
        if (world.recentExternalAction && !world.autonomyInFlight) {
            shiftDwell(1_000)
            if (goToPending == null) adoptExternalState(world, "external")
            return emptyList()
        }

        // 自主 GoTo 在执行：等待就位（就位/失败的判定在世界快照里）。
        // inFlight=true 而 pending=null（执行器视角还占用着，FSM 视角已清）
        // 视为失配，同样不发新决策——执行器互斥是硬约束。
        val pending = goToPending
        if (pending != null || world.autonomyInFlight) {
            if (pending != null && !world.autonomyInFlight && !world.walking) {
                goToPending = null
                onArrived(pending, now)
            }
            return emptyList()
        }

        // LOOKING 续盯：就位链 8s 后把视线交回镜头，状态期间按节拍补发。
        if (state == AutonomyStateKind.LOOKING && now >= nextGazeSustainAtMs) {
            val look = spots.firstOrNull { it.id == currentAnchorId }?.look
            if (look != null && look.size >= 3) {
                nextGazeSustainAtMs = now + GAZE_SUSTAIN_PERIOD_MS
                return listOf(AutonomyDirective.GazePoint(look[0], look[1], look[2]))
            }
        }

        // 冻结刚解除：不等 dwell（解冻即活），本轮直接落决策检查。
        if (now < nextDecisionAtMs && !unfroze) return emptyList()

        // 决策：每小时动作上限（超限纯站桩，宁可安静）。
        if (overActionCap(now)) {
            val out = mutableListOf<AutonomyDirective>()
            if (state == AutonomyStateKind.LYING || state == AutonomyStateKind.SITTING) {
                setState(AutonomyStateKind.IDLE_STAND, null, "cap")
                out += AutonomyDirective.StandUp
            } else if (state != AutonomyStateKind.IDLE_STAND) {
                // 站/看被 cap 归位：人还站在原地，保留锚点（禁忌继续生效）
                setState(AutonomyStateKind.IDLE_STAND, currentAnchorId, "cap")
            }
            nextDecisionAtMs = now + IDLE_CAP_RETRY_MS
            return out
        }

        return decideNext(world, now, reason = if (unfroze) "unfroze" else "dwell")
    }

    /** 指令被 executor 拒绝：退避 + 退避期内避让该锚点。 */
    fun markDirectiveAborted() {
        goToPending?.let { abortedSpotId = it.spot.id }
        goToPending = null
        setState(AutonomyStateKind.IDLE_STAND, null, "abort")
        nextDecisionAtMs = nowMs() + ABORT_BACKOFF_MS
    }

    /** 测试钩子：强行置状态与锚点（分布对拍用，不走日志）。 */
    internal fun forceStateForTest(state: AutonomyStateKind, anchorId: String?) {
        this.state = state
        this.currentAnchorId = anchorId
    }

    // ── 决策与状态转移 ───────────────────────────────────────────────────

    private fun decideNext(world: AutonomyWorld, now: Long, reason: String): List<AutonomyDirective> {
        nextDecisionAtMs = now + DECISION_RETRY_MS
        val (kind, spot) = chooseNext(hourProvider())
        val previous = state
        if (kind == AutonomyStateKind.IDLE_STAND) {
            if (previous != AutonomyStateKind.IDLE_STAND || currentAnchorId != null) {
                setState(kind, null, reason)
            }
            nextDecisionAtMs = now + dwellFor(kind)
            // 从坐/躺离开需起身；视线若黏在 LOOKING 目标上交回镜头。
            return when (previous) {
                AutonomyStateKind.SITTING, AutonomyStateKind.LYING ->
                    listOf(AutonomyDirective.StandUp, AutonomyDirective.GazeCamera)
                AutonomyStateKind.LOOKING -> listOf(AutonomyDirective.GazeCamera)
                else -> emptyList()
            }
        }
        val target = spot ?: return emptyList() // 退避重试（DECISION_RETRY_MS 已设）
        setState(kind, target.id, reason)
        actionTimes += now
        goToPending = AutonomyDirective.GoTo(target, kind)
        nextDecisionAtMs = now + DWELL_FALLBACK_MS // 就位观察转正时重设
        val out = mutableListOf<AutonomyDirective>()
        if (previous == AutonomyStateKind.LOOKING) out += AutonomyDirective.GazeCamera
        out += AutonomyDirective.GoTo(target, kind)
        return out
    }

    /**
     * 加权随机选下一状态与锚点（§4.2）：作息权重 → LYING 后偏 WANDER →
     * 状态可用锚点过滤（禁忌：不连去同一点/退避期内刚失败点）→ 距离衰减选点。
     */
    internal fun chooseNext(hour: Int): Pair<AutonomyStateKind, AutonomySpot?> {
        val weights = schedule.weightsFor(hour).toMutableMap()
        // 醒一醒：躺完偏好走动。
        if (state == AutonomyStateKind.LYING) {
            weights[AutonomyStateKind.WANDER] = (weights[AutonomyStateKind.WANDER] ?: 1f) * 3f
        }
        val available = AutonomyStateKind.entries.filter { kind ->
            kind == AutonomyStateKind.IDLE_STAND || spotsFor(kind).isNotEmpty()
        }
        val kind = rng.pickWeighted(available) { weights[it] ?: 1f }
            ?: return AutonomyStateKind.IDLE_STAND to null
        if (kind == AutonomyStateKind.IDLE_STAND) return kind to null
        val spot = rng.pickWeighted(spotsFor(kind)) { spot ->
            distanceDecay(spot.distanceTo(positionX(), positionZ()))
        }
        return kind to spot
    }

    /** 状态候选锚点：禁忌过滤（不连去同一点 + 退避期内刚失败的点）。 */
    private fun spotsFor(kind: AutonomyStateKind): List<AutonomySpot> =
        spots.filter { it.kind == kind }
            .filter { it.id != currentAnchorId }
            .filter { it.id != abortedSpotId }

    /** 距离衰减权重：横穿房间越远越不想动（1/(1+d/4)，4m 半衰）。 */
    private fun distanceDecay(distanceMeters: Float): Float = 1f / (1f + distanceMeters / 4f)

    // 决策用的当前位置：接触锚点坐标由 app 层回喂（reportPosition），缺省原点。
    // 距离衰减是软偏好，误差只影响选点倾向，不影响正确性——FSM 保持无 controller 依赖。
    private var lastKnownX = 0f
    private var lastKnownZ = 0f

    private fun positionX(): Float = lastKnownX
    private fun positionZ(): Float = lastKnownZ

    /** 集成方回喂当前位置（可选，纯选点偏好精度用）。 */
    fun reportPosition(x: Float, z: Float) {
        lastKnownX = x
        lastKnownZ = z
    }

    private fun onArrived(goTo: AutonomyDirective.GoTo, now: Long) {
        abortedSpotId = null
        nextDecisionAtMs = now + dwellFor(goTo.state)
        if (goTo.state == AutonomyStateKind.LOOKING) {
            nextGazeSustainAtMs = now + GAZE_SUSTAIN_PERIOD_MS
        }
    }

    /** 外部工具把虚拟人放到新位置：状态就地续跑，不回弹（§4.3 优先级 3）。 */
    private fun adoptExternalState(world: AutonomyWorld, reason: String) {
        val kind = when (world.contactKind) {
            "sit" -> AutonomyStateKind.SITTING
            "lie" -> AutonomyStateKind.LYING
            else -> if (state == AutonomyStateKind.SITTING || state == AutonomyStateKind.LYING) {
                AutonomyStateKind.IDLE_STAND
            } else {
                null
            }
        } ?: return
        if (kind != state || world.contactAnchorId != currentAnchorId) {
            setState(kind, world.contactAnchorId, reason)
            nextDecisionAtMs = nowMs() + dwellFor(kind)
        }
    }

    private fun setState(to: AutonomyStateKind, anchorId: String?, reason: String) {
        if (to == state && anchorId == currentAnchorId) return
        _transitions.addLast(AutonomyTransition(nowMs(), state, to, anchorId, reason))
        while (_transitions.size > TRANSITION_LOG_MAX) _transitions.removeFirst()
        state = to
        currentAnchorId = anchorId
    }

    private fun shiftDwell(ms: Long) {
        nextDecisionAtMs += ms
    }

    private fun overActionCap(now: Long): Boolean {
        while (actionTimes.isNotEmpty() && now - actionTimes.first() > 3_600_000) {
            actionTimes.removeFirst()
        }
        return actionTimes.size >= limits.maxActionsPerHour
    }

    // ── 停留时长（§4.1 状态表；躺卧深夜加长）───────────────────────────

    internal fun dwellFor(kind: AutonomyStateKind): Long {
        val base = when (kind) {
            AutonomyStateKind.IDLE_STAND -> rng.nextLong(20_000, 60_001)
            AutonomyStateKind.WANDER -> rng.nextLong(6_000, 12_001)
            AutonomyStateKind.LOOKING -> rng.nextLong(30_000, 90_001)
            AutonomyStateKind.SITTING -> rng.nextLong(180_000, 600_001)
            AutonomyStateKind.LYING -> rng.nextLong(480_000, 1_200_001)
        }
        if (kind == AutonomyStateKind.LYING && schedule.inQuietHours(hourProvider())) {
            return (base * 3 / 2).coerceAtMost(1_800_000)
        }
        return base.coerceAtLeast(limits.minDwellMs)
    }

    // ── 观测（ai_cmd autonomy_state / autonomy_log 的正本）──────────────

    /** 一行快照：状态/锚点/下次决策倒计时/动作计数/转移数。 */
    fun stateLine(): String {
        val now = nowMs()
        val left = (nextDecisionAtMs - now).coerceAtLeast(0) / 1000
        return buildString {
            append("state=$state anchor=${currentAnchorId ?: "none"}")
            append(" nextIn=${left}s")
            append(" actionsLastHour=${actionTimes.size}/${limits.maxActionsPerHour}")
            append(" pending=${goToPending != null}")
            append(" transitions=${_transitions.size}")
        }
    }

    /** 最近转移记录多行转储（空 = 尚无）。 */
    fun logDump(): String = if (_transitions.isEmpty()) {
        "no transitions yet"
    } else {
        _transitions.joinToString("\n") { t ->
            val hh = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(t.atMs))
            "$hh ${t.from} → ${t.to}${t.anchorId?.let { " @$it" } ?: ""} (${t.reason})"
        }
    }

    companion object {
        private const val TRANSITION_LOG_MAX = 20

        /** 决策重试节拍（dwell 到点没选出目标的兜底重试间隔）。 */
        private const val DECISION_RETRY_MS = 5_000L

        /** GoTo 未观察到就位前的占位 dwell（onArrived 会重设）。 */
        private const val DWELL_FALLBACK_MS = 45_000L

        /** 指令被拒后的退避。 */
        private const val ABORT_BACKOFF_MS = 30_000L

        /** 动作超限后的纯站桩重查间隔。 */
        private const val IDLE_CAP_RETRY_MS = 300_000L

        /** LOOKING 续盯节拍（就位链 8s 回退之后）。 */
        private const val GAZE_SUSTAIN_PERIOD_MS = 7_000L
    }
}

/** 加权随机抽取；权重和为 0/列表空返回 null。 */
internal fun <T> Random.pickWeighted(items: List<T>, weight: (T) -> Float): T? {
    if (items.isEmpty()) return null
    val weights = items.map(weight)
    val total = weights.sum()
    if (total <= 0f) return null
    var roll = nextFloat() * total
    for ((index, w) in weights.withIndex()) {
        roll -= w
        if (roll <= 0f) return items[index]
    }
    return items.last()
}
