package com.neethu.orchestrator.debate

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 辩论赛引擎（docs/debate-feasibility.md §5）：唯一的比赛事实源。
 *
 * 核心约束（全部在本类内强制）：
 * 1. 同一时刻最多一个可播放音频的角色（含主持人）——单令牌即 [activeTurn]。
 * 2. 赛程推进只由引擎游标决定；发言文本、姿态回调都不能推进赛程。
 * 3. 起立完成（姿态门通过）后才允许 [DebateSpeechPlayer.start]。
 * 4. 不以单句结束换人；以完整回合回调、失败、到时或显式 end_turn 收尾。
 * 5. 每条异步回调绑定 epoch+turnId；迟到的 TTS/姿态回调被丢弃。
 * 6. 到时先取消播放，等宽限内的收尾确认，再交还令牌并坐下；下一位不与尾音重叠。
 *
 * 计时用 [DebateClock] 单调时钟 + [DebateTimer]；暂停 = cancelAll + 引擎侧
 * fireAt 账目冻结，恢复 = 按剩余重排。全部命令/回调经同步蹦床串行（见 [step]），
 * 所有方法须在拥有 [DebateTimer] 的同一线程上调用。
 */
class DebateEngine(
    private val clock: DebateClock,
    private val timer: DebateTimer,
    private val speechPlayer: DebateSpeechPlayer,
    private val postureDirector: DebatePostureDirector? = null,
    /** P1 AI 发言缝：null = 用配置 scripts + 内置固定稿。 */
    private val scriptOverride: DebateScriptProvider? = null,
) {

    // ── 公共可观测 ──────────────────────────────────────────────────────

    val state: StateFlow<DebatePublicState> get() = _state.asStateFlow()
    val events: SharedFlow<DebateEvent> get() = _events.asSharedFlow()
    val transcriptEntries: List<TranscriptEntry> get() = synchronized(transcript) { transcript.toList() }

    private val _state = MutableStateFlow(DebatePublicState.idle())
    private val _events = MutableSharedFlow<DebateEvent>(
        replay = 0, extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    // ── 内部账目 ────────────────────────────────────────────────────────

    private var config: DebateConfigDto? = null
    private var plan: DebatePlan? = null
    private var scriptProvider: DebateScriptProvider? = null

    private var epoch = 0L
    private var matchState = MatchState.IDLE
    private var segIdx = 0
    private var stmtIdx = 0
    private var roundIdx = 0
    /** 攻辩轮内推进：false = 该提问者说，true = 该应答者说。 */
    private var roundNextIsAnswer = false
    private var freeRotationIdx = mutableMapOf<DebateTeam, Int>()
    private var freeActiveTeam: DebateTeam? = null
    private var balances = mutableMapOf<DebateTeam, Long>()

    private var activeTurn: ActiveTurn? = null
    private val preparedTexts = mutableMapOf<Long, String>()
    /** 姿态门：等待 STAND 就绪的参与者（空集 = 门已通过）。 */
    private var posturePending = linkedSetOf<String>()
    private var audioStartedAtMs: Long? = null
    private var wallExpiresAtMs: Long? = null
    private var frozenTurn: FrozenTurn? = null
    private var pendingReasonOnComplete: TurnEndReason? = null
    private var lastError: String? = null
    private var matchStartedAtMs: Long? = null
    private var turnSeq = 0L
    /** 当前段内连续失败/跳过计数（达段容量上限即熔断跳段，防零消耗死循环）。 */
    private var segmentFailures = 0

    private val transcript = mutableListOf<TranscriptEntry>()

    /** 冻结的定时器账目：暂停时保留，恢复时按剩余重排。 */
    private class PendingTimer(val fireAtMs: Long, val action: () -> Unit)
    private val pendingTimers = LinkedHashMap<String, PendingTimer>()

    /** 暂停时挂起的回合（恢复续讲）。 */
    private class FrozenTurn(
        val turn: ActiveTurn,
        val text: String,
        val remainingMs: Long,
        val remainingWallMs: Long?,
    )

    // ── 命令（全部经 step 串行）────────────────────────────────────────

    fun load(dto: DebateConfigDto) = step {
        if (matchState == MatchState.RUNNING) {
            reject("load", "比赛中禁止重载（先 pause/stop）")
            return@step
        }
        teardownForReset(sitEveryone = false)
        val issues = DebateConfigValidator.validate(dto)
        if (issues.isNotEmpty()) {
            matchState = MatchState.ERROR
            lastError = "配置校验失败: " + issues.joinToString("; ") { it.toString() }
            publish()
            return@step
        }
        config = dto
        plan = RuleCompiler.compile(dto)
        scriptProvider = scriptOverride ?: ConfigScriptProvider(dto)
        epoch++
        matchState = MatchState.READY
        lastError = null
        emit(DebateEvent.MatchStateChanged(MatchState.READY, "load ${dto.matchId}"))
        publish()
    }

    fun start() = step {
        val p = plan
        if (p == null) {
            reject("start", "尚未加载任何比赛配置（先 load）")
            return@step
        }
        if (matchState != MatchState.READY) {
            reject("start", "状态 $matchState 不允许开赛（需 READY）")
            return@step
        }
        matchState = MatchState.RUNNING
        matchStartedAtMs = clock.nowMs()
        emit(DebateEvent.MatchStateChanged(MatchState.RUNNING, "start"))
        enterSegment(0)
        publish()
    }

    fun pause(reason: String = "pause") = step {
        if (matchState != MatchState.RUNNING) {
            reject("pause", "状态 $matchState 不在比赛中"); return@step
        }
        freezeTimers()
        val turn = activeTurn
        if (turn != null && frozenTurn == null) {
            speechPlayer.cancelActive("pause")
            val consumed = consumedSoFar(turn)
            val remaining = (turn.budgetMs - consumed).coerceAtLeast(0L)
            val remainingWall = wallExpiresAtMs?.let { (it - clock.nowMs()).coerceAtLeast(0L) }
            frozenTurn = FrozenTurn(turn, currentScript(turn), remaining, remainingWall)
            recordTranscript(turn, TurnEndReason.PAUSED, consumed, TranscriptStatus.INTERRUPTED)
            emit(DebateEvent.TurnEnded(turn.turnId, turn.speakerId, TurnEndReason.PAUSED, consumed, null))
        }
        matchState = MatchState.PAUSED
        emit(DebateEvent.MatchStateChanged(MatchState.PAUSED, reason))
        publish()
    }

    fun resume() = step {
        if (matchState != MatchState.PAUSED) {
            reject("resume", "状态 $matchState 未暂停"); return@step
        }
        matchState = MatchState.RUNNING
        emit(DebateEvent.MatchStateChanged(MatchState.RUNNING, "resume"))
        val frozen = frozenTurn
        frozenTurn = null
        if (frozen != null) {
            // 恢复续讲：姿态不重复请求（站姿在 app 侧保持），预算用剩余量；
            // 定时器账目全数清空——prep/turn/wall 在下面显式重排
            audioStartedAtMs = null
            pendingTimers.clear()
            activeTurn = frozen.turn
            emit(DebateEvent.TurnResumed(frozen.turn.turnId, frozen.turn.speakerId, frozen.remainingMs))
            if (frozen.turn.stage == TurnStage.AWAITING_POSTURE && posturePending.isNotEmpty()) {
                scheduleTimer(TIMER_POSTURE, configSpeech().postureTimeoutMs)
                postureDirector?.onPostureCommand(PostureCommand(
                    frozen.turn.turnId, posturePending.toList(), PostureAction.STAND,
                ))
            } else {
                scheduleTimer(TIMER_PREP, preparationTimeoutMs())
                beginAudio(frozen.turn, frozen.text, frozen.remainingMs)
            }
            frozen.remainingWallMs?.let { scheduleTimer(TIMER_WALL, it) }
        } else {
            unfreezeTimers()
        }
        publish()
    }

    fun stop(reason: String = "stop") = step {
        if (matchState == MatchState.IDLE) {
            reject("stop", "尚未加载任何比赛"); return@step
        }
        if (matchState == MatchState.STOPPED) return@step
        speechPlayer.cancelActive("stop")
        timer.cancelAll()
        pendingTimers.clear()
        activeTurn?.let { turn ->
            recordTranscript(turn, TurnEndReason.MANUAL_END, consumedSoFar(turn), TranscriptStatus.INTERRUPTED)
        }
        activeTurn = null
        preparedTexts.clear()
        frozenTurn = null
        posturePending.clear()
        if (matchState == MatchState.RUNNING || matchState == MatchState.PAUSED) {
            postureDirector?.onPostureCommand(PostureCommand(-1L, DebateIds.ALL, PostureAction.SIT))
        }
        matchState = MatchState.STOPPED
        emit(DebateEvent.MatchStateChanged(MatchState.STOPPED, reason))
        publish()
    }

    fun reset() = step {
        if (matchState == MatchState.IDLE) {
            reject("reset", "尚未加载任何比赛"); return@step
        }
        speechPlayer.cancelActive("reset")
        teardownForReset(sitEveryone = matchState == MatchState.RUNNING || matchState == MatchState.PAUSED)
        epoch++
        matchState = MatchState.READY
        emit(DebateEvent.MatchStateChanged(MatchState.READY, "reset"))
        publish()
    }

    /** 主动结束当前合法发言，按正常收尾交接（doc §10 end_turn）。 */
    fun endTurn() = step {
        if (matchState != MatchState.RUNNING) {
            reject("end_turn", "状态 $matchState 不在比赛中"); return@step
        }
        val turn = activeTurn ?: run { reject("end_turn", "当前没有在途发言"); return@step }
        if (frozenTurn != null) { reject("end_turn", "回合已冻结（暂停中）"); return@step }
        speechPlayer.cancelActive("manual-end")
        finishTurn(turn, TurnEndReason.MANUAL_END)
    }

    /** 调试裁定：中止当前阶段并跳到下一阶段（日志注明人工跳过）。 */
    fun nextPhase() = step {
        if (matchState != MatchState.RUNNING && matchState != MatchState.PAUSED) {
            reject("next_phase", "状态 $matchState 不在比赛中"); return@step
        }
        val currentPhase = plan?.segments?.getOrNull(segIdx)?.phase
        abandonCurrentTurn("next_phase")
        emit(DebateEvent.ManualSkip(fromPhase = currentPhase, toSegmentIndex = segIdx + 1))
        endSegment()
    }

    /** 授权发言：只接受当前合法集合内的结果（doc §3），其余拒绝。 */
    fun grant(speakerId: String) = step {
        val expected = expectedSpeakerId()
        if (matchState != MatchState.RUNNING) {
            reject("grant", "状态 $matchState 不在比赛中"); return@step
        }
        if (expected == null || speakerId != expected) {
            reject("grant", "「$speakerId」不在当前合法发言集合 [${expected ?: "-"}] 内")
            return@step
        }
        // 合法且即期望者：自动推进已覆盖，幂等确认即可
        publish()
    }

    // ── app → 引擎回调（epoch/turnId 绑定，迟到即弃）──────────────────

    fun notifyPostureReady(epochAt: Long, turnId: Long, participantId: String) = step {
        if (epochAt != epoch) return@step
        val turn = activeTurn ?: return@step
        if (turn.turnId != turnId || frozenTurn != null) return@step
        if (!posturePending.remove(participantId)) return@step
        if (posturePending.isEmpty() && turn.stage == TurnStage.AWAITING_POSTURE) {
            beginAudioGate(turn)
        }
    }

    fun notifySpeechAudioStarted(epochAt: Long, turnId: Long) = step {
        if (epochAt != epoch) return@step
        val turn = activeTurn ?: return@step
        if (turn.turnId != turnId || frozenTurn != null) return@step
        if (turn.stage != TurnStage.SPEAKING) {
            turn.stage = TurnStage.SPEAKING
            cancelTimer(TIMER_PREP)
            audioStartedAtMs = clock.nowMs()
            // 个人 deadline：首段音频实际开始时起表（doc §5）
            scheduleTimer(TIMER_TURN, turn.budgetMs)
            emit(DebateEvent.SpeechAudioStarted(turn.turnId, turn.speakerId, audioStartedAtMs!!))
            // 攻辩整轮墙钟：首个问题音频起播时启动
            if (turn.kind == TurnKind.CROSS_QUESTION && wallExpiresAtMs == null) {
                wallExpiresAtMs = clock.nowMs() + currentRoundWallMs()
                scheduleTimer(TIMER_WALL, currentRoundWallMs())
            }
        }
        publish()
    }

    /**
     * 完整回合播完/被打断。[consumedOverrideMs] = 播放层实测的发言时长（起播
     * 到交还令牌）；null 时回退引擎侧时钟采样。时长由播放层上报而非引擎采样：
     * 回调经过串行队列后引擎时钟可能已不在采样点上。
     */
    fun notifySpeechCompleted(
        epochAt: Long,
        turnId: Long,
        interrupted: Boolean,
        consumedOverrideMs: Long? = null,
    ) = step {
        if (epochAt != epoch) return@step
        val turn = activeTurn ?: return@step
        if (turn.turnId != turnId) return@step
        if (frozenTurn != null || matchState == MatchState.PAUSED) return@step // 暂停路径已记账
        val reason = pendingReasonOnComplete ?: if (interrupted) TurnEndReason.INTERRUPTED else null
        if (reason == null && turn.stage != TurnStage.SPEAKING && turn.stage != TurnStage.DRAINING) {
            return@step // 未起播就回调完成：忽略（异常播放层行为）
        }
        finishTurn(turn, reason ?: TurnEndReason.COMPLETED, consumedOverrideMs)
    }

    fun notifySpeechFailed(epochAt: Long, turnId: Long, error: Throwable) = step {
        if (epochAt != epoch) return@step
        val turn = activeTurn ?: return@step
        if (turn.turnId != turnId || frozenTurn != null) return@step
        if (matchState != MatchState.RUNNING) return@step
        val retries = configSpeech().failureRetries
        if (turn.attempt < retries) {
            turn.attempt++
            lastError = "发言失败重试(${turn.attempt}/$retries): ${error.message}"
            audioStartedAtMs = null
            cancelTimer(TIMER_TURN)
            cancelTimer(TIMER_PREP)
            emit(DebateEvent.TurnResumed(turn.turnId, turn.speakerId, turn.budgetMs))
            beginAudioGate(turn)
        } else {
            lastError = "发言失败跳过: ${error.message}"
            finishTurn(turn, TurnEndReason.FAILED)
        }
    }

    // ── 回合推进核心 ────────────────────────────────────────────────────

    private fun beginAudioGate(turn: ActiveTurn) {
        turn.stage = TurnStage.PREPARING_AUDIO
        val script = currentScript(turn)
        scheduleTimer(TIMER_PREP, preparationTimeoutMs())
        beginAudio(turn, script, turn.budgetMs)
    }

    private fun beginAudio(turn: ActiveTurn, text: String, budgetMs: Long) {
        turn.stage = TurnStage.PREPARING_AUDIO
        turn.budgetMs = budgetMs
        speechPlayer.start(turn, text)
        publish()
    }

    private fun finishTurn(turn: ActiveTurn, reason: TurnEndReason, consumedOverrideMs: Long? = null) {
        cancelTimer(TIMER_POSTURE)
        cancelTimer(TIMER_PREP)
        cancelTimer(TIMER_TURN)
        cancelTimer(TIMER_GRACE)
        pendingReasonOnComplete = null
        val consumed = (consumedOverrideMs ?: consumedSoFar(turn)).coerceAtMost(turn.budgetMs)
        val status = when (reason) {
            TurnEndReason.COMPLETED -> TranscriptStatus.PLAYED
            TurnEndReason.TIMEOUT -> TranscriptStatus.INTERRUPTED
            TurnEndReason.FAILED -> TranscriptStatus.FAILED
            TurnEndReason.SKIPPED -> TranscriptStatus.SKIPPED
            else -> TranscriptStatus.INTERRUPTED
        }
        recordTranscript(turn, reason, consumed, status)
        var balanceAfter: Long? = null
        val turnTeam = turn.team
        if (turn.kind == TurnKind.FREE && turnTeam != null) {
            balances[turnTeam] = ((balances[turnTeam] ?: 0L) - consumed).coerceAtLeast(0L)
            balanceAfter = balances[turnTeam]
            emit(DebateEvent.TeamBalanceChanged(turnTeam, balanceAfter ?: 0L))
        }
        emit(DebateEvent.TurnEnded(turn.turnId, turn.speakerId, reason, consumed, balanceAfter))
        activeTurn = null
        audioStartedAtMs = null
        posturePending.clear()
        when (reason) {
            TurnEndReason.FAILED, TurnEndReason.SKIPPED -> segmentFailures++
            else -> segmentFailures = 0
        }

        // 熔断：一段内失败/跳过达段容量（人人失败一轮）即放弃该段，防死循环
        val segNow = currentSegment()
        if (segNow != null && segmentFailures >= segmentFailureLimit(segNow)) {
            lastError = "阶段 ${segNow.phase} 连续失败 $segmentFailures 次，人工或自动跳过该段"
            android.util.Log.w(TAG, lastError!!)
            emit(DebateEvent.RoundEnded(-1, "segment-fail-circuit-breaker"))
            endSegment()
            publish()
            return
        }

        // 姿态收尾：按段类型决定谁坐下（攻辩轮内不坐，轮末一起坐）
        val seg = currentSegment()
        val sits: List<String> = when {
            seg is DebateSegment.CrossExam && roundContinues() -> emptyList()
            turn.kind == TurnKind.CROSS_QUESTION || turn.kind == TurnKind.CROSS_ANSWER -> {
                val round = currentCrossRound()
                listOfNotNull(round?.questionerId, round?.answererId)
            }
            turn.kind == TurnKind.FREE -> listOf(turn.speakerId)
            turn.kind == TurnKind.HOST -> listOf(turn.speakerId)
            else -> listOf(turn.speakerId)
        }
        if (sits.isNotEmpty() && configPosture().standWhenSpeaking) {
            postureDirector?.onPostureCommand(PostureCommand(turn.turnId, sits, PostureAction.SIT))
        }
        advanceAfterTurn(turn)
        publish()
    }

    private fun advanceAfterTurn(turn: ActiveTurn) {
        when (val seg = currentSegment()) {
            is DebateSegment.Statements -> {
                stmtIdx++
                val turns = seg.turns
                if (stmtIdx < turns.size) startTurn(turns[stmtIdx], stmtIdx)
                else endSegment()
            }
            is DebateSegment.CrossExam -> {
                if (!roundNextIsAnswer && !wallEndedForce) {
                    // 提问完 → 应答（个人上限换回答额度；墙钟继续）
                    roundNextIsAnswer = true
                    val round = seg.rounds[roundIdx]
                    startTurn(
                        StatementTurn(round.answererId, DebateIds.teamOf(round.answererId), round.answerBudgetMs),
                        turnIndexInPhase = roundIdx * 2 + 1,
                        kind = TurnKind.CROSS_ANSWER,
                    )
                } else {
                    // 回合收尾：正常交替完成，或墙钟耗尽强制掐断
                    cancelTimer(TIMER_WALL)
                    roundNextIsAnswer = false
                    emit(DebateEvent.RoundEnded(roundIdx, if (wallEndedForce) "wall-deadline" else "completed"))
                    wallEndedForce = false
                    wallExpiresAtMs = null
                    roundIdx++
                    if (roundIdx < seg.rounds.size) enterRound(seg.rounds[roundIdx])
                    else endSegment()
                }
            }
            is DebateSegment.FreeDebate -> {
                val team = turn.team ?: return endSegment()
                val other = team.opposing
                val nextTeam = when {
                    (balances[other] ?: 0L) > 0L -> other
                    (balances[team] ?: 0L) > 0L -> team
                    else -> return endSegment()
                }
                freeActiveTeam = nextTeam
                startFreeTurn(seg, nextTeam)
            }
            null, is DebateSegment.HostSpeech -> endSegment()
        }
    }

    private fun startFreeTurn(seg: DebateSegment.FreeDebate, team: DebateTeam) {
        val rotation = seg.rotation.getValue(team)
        val idx = freeRotationIdx.getOrDefault(team, 0)
        freeRotationIdx[team] = idx + 1
        val speaker = rotation[idx % rotation.size]
        val budget = minOf(seg.turnBudgetMs, balances[team] ?: 0L)
        if (budget <= 0L) return endSegment()
        startTurn(StatementTurn(speaker, team, budget), turnIndexInPhase = countFreeTurns(), kind = TurnKind.FREE)
    }

    private fun countFreeTurns(): Int = freeRotationIdx.values.sum()

    private fun segmentFailureLimit(seg: DebateSegment): Int = when (seg) {
        is DebateSegment.HostSpeech -> 1
        is DebateSegment.Statements -> seg.turns.size
        is DebateSegment.CrossExam -> seg.rounds.size * 2
        is DebateSegment.FreeDebate -> seg.rotation.values.sumOf { it.size }
    }

    private fun enterSegment(index: Int) {
        val p = plan ?: return
        if (index >= p.segments.size) return finishMatch()
        segIdx = index
        stmtIdx = 0
        segmentFailures = 0
        val seg = p.segments[index]
        emit(DebateEvent.PhaseChanged(seg.phase, index, p.segments.size, segmentLabel(seg)))
        when (seg) {
            is DebateSegment.HostSpeech ->
                startTurn(StatementTurn(DebateIds.HOST, null, seg.budgetMs), 0, TurnKind.HOST)
            is DebateSegment.Statements -> startTurn(seg.turns[0], 0)
            is DebateSegment.CrossExam -> enterRound(seg.rounds[0])
            is DebateSegment.FreeDebate -> {
                balances[DebateTeam.PRO] = seg.teamBudgetMs.getValue(DebateTeam.PRO)
                balances[DebateTeam.CON] = seg.teamBudgetMs.getValue(DebateTeam.CON)
                freeRotationIdx.clear()
                emit(DebateEvent.TeamBalanceChanged(DebateTeam.PRO, balances.getValue(DebateTeam.PRO)))
                emit(DebateEvent.TeamBalanceChanged(DebateTeam.CON, balances.getValue(DebateTeam.CON)))
                freeActiveTeam = seg.firstTeam
                startFreeTurn(seg, seg.firstTeam)
            }
        }
    }

    private fun enterRound(round: CrossRound) {
        roundNextIsAnswer = false
        wallExpiresAtMs = null
        wallEndedForce = false
        emit(DebateEvent.RoundStarted(round.roundIndex, round.questionerId, round.answererId))
        startTurn(
            StatementTurn(round.questionerId, DebateIds.teamOf(round.questionerId), round.questionBudgetMs),
            turnIndexInPhase = round.roundIndex * 2,
            kind = TurnKind.CROSS_QUESTION,
            standIds = if (configPosture().standWhenSpeaking) {
                listOf(round.questionerId, round.answererId)
            } else {
                null
            },
        )
    }

    /**
     * 开一个新回合：发 TurnStarted、请求起立（[standIds] 可含攻辩应答者——
     * 轮开始时两人一起起立、轮末一起坐下）、等姿态门。应答者回合不重复请求。
     */
    private fun startTurn(
        turn: StatementTurn,
        turnIndexInPhase: Int,
        kind: TurnKind = TurnKind.STATEMENT,
        standIds: List<String>? = null,
    ) {
        turnSeq++
        val at = ActiveTurn(
            turnId = turnSeq,
            epoch = epoch,
            phase = currentSegment()?.phase ?: DebatePhase.HOST_OPENING,
            speakerId = turn.speakerId,
            team = turn.team,
            kind = kind,
            budgetMs = turn.budgetMs,
            turnIndexInPhase = turnIndexInPhase,
        )
        activeTurn = at
        audioStartedAtMs = null
        pendingReasonOnComplete = null
        val text = currentScript(at)
        // 事件收集者可能立即执行（Main.immediate）；先发布新回合再通知镜头。
        publish()
        emit(DebateEvent.TurnStarted(at, text))

        val needsStand = configPosture().standWhenSpeaking
        val requestStand = when {
            !needsStand -> null
            kind == TurnKind.CROSS_ANSWER -> null // 轮开始时已与提问者一起请求
            standIds != null -> standIds
            else -> listOf(at.speakerId)
        }
        if (requestStand != null) {
            posturePending.clear()
            posturePending.addAll(requestStand)
            postureDirector?.onPostureCommand(PostureCommand(at.turnId, requestStand, PostureAction.STAND))
            scheduleTimer(TIMER_POSTURE, configSpeech().postureTimeoutMs)
        }
        if (posturePending.isEmpty()) beginAudioGate(at) else publish()
    }

    private fun endSegment() {
        cancelTimer(TIMER_WALL)
        wallExpiresAtMs = null
        enterSegment(segIdx + 1)
    }

    private fun finishMatch() {
        matchState = MatchState.FINISHED
        val startedAt = matchStartedAtMs
        val elapsed = if (startedAt != null) clock.nowMs() - startedAt else 0L
        emit(
            DebateEvent.MatchFinished(
                matchId = plan?.matchId ?: "",
                totalTurns = synchronized(transcript) { transcript.size },
                elapsedMs = elapsed,
            )
        )
        emit(DebateEvent.MatchStateChanged(MatchState.FINISHED, "match complete"))
        publish()
    }

    /** next_phase 的丢弃路径：作废当前回合（迟到回调按 turnId 弃）。 */
    private fun abandonCurrentTurn(why: String) {
        val turn = activeTurn ?: return
        cancelTimer(TIMER_WALL)
        cancelTimer(TIMER_PREP); cancelTimer(TIMER_TURN); cancelTimer(TIMER_GRACE); cancelTimer(TIMER_POSTURE)
        if (configPosture().standWhenSpeaking) {
            postureDirector?.onPostureCommand(PostureCommand(turn.turnId, DebateIds.ALL, PostureAction.SIT))
        }
        speechPlayer.cancelActive(why)
        recordTranscript(turn, TurnEndReason.SKIPPED, consumedSoFar(turn), TranscriptStatus.SKIPPED)
        emit(DebateEvent.TurnEnded(turn.turnId, turn.speakerId, TurnEndReason.SKIPPED, 0L, null))
        activeTurn = null
        preparedTexts.clear()
        frozenTurn = null
        posturePending.clear()
        wallExpiresAtMs = null
    }

    // ── 定时器（引擎侧 fireAt 账目 + 暂停冻结）─────────────────────────

    private fun cancelTimer(key: String) {
        synchronized(pendingTimers) { pendingTimers.remove(key) }
        timer.cancel(key)
    }

    private fun scheduleTimer(key: String, delayMs: Long, actionOverride: (() -> Unit)? = null) {
        if (delayMs <= 0L) {
            // 0 预算/已到期：直接在当前串行区内触发，避免真实定时器跨线程抢锁
            runTimerAction(key, actionOverride)
            return
        }
        pendingTimers[key] = PendingTimer(clock.nowMs() + delayMs, actionOverride ?: defaultTimerAction(key))
        timer.schedule(key, delayMs) { onTimerFired(key) }
    }

    private fun defaultTimerAction(key: String): () -> Unit = { step { handleTimer(key) } }

    private fun runTimerAction(key: String, action: (() -> Unit)?) {
        (action ?: defaultTimerAction(key)).invoke()
    }

    private fun onTimerFired(key: String) {
        val action = synchronized(pendingTimers) { pendingTimers.remove(key)?.action } ?: return
        action()
    }

    private fun handleTimer(key: String) {
        if (matchState != MatchState.RUNNING) return
        val turn = activeTurn ?: return
        when (key) {
            TIMER_POSTURE -> if (posturePending.isNotEmpty() && turn.stage == TurnStage.AWAITING_POSTURE) {
                lastError = "姿态过渡超时（等 ${posturePending.joinToString()}），回合按失败处理"
                // 姿态门控失败不能走音频重试，否则角色尚未站稳也会开始说话。
                finishTurn(turn, TurnEndReason.FAILED)
            }
            TIMER_PREP -> if (turn.stage == TurnStage.PREPARING_AUDIO) {
                lastError = "首音频超时，回合按失败处理"
                speechPlayer.cancelActive("prep-timeout")
                failTurn(turn)
            }
            TIMER_TURN -> if (turn.stage == TurnStage.SPEAKING) {
                pendingReasonOnComplete = TurnEndReason.TIMEOUT
                turn.stage = TurnStage.DRAINING
                speechPlayer.cancelActive("deadline")
                scheduleTimer(TIMER_GRACE, configSpeech().drainGraceMs)
            }
            TIMER_GRACE -> if (turn.stage == TurnStage.DRAINING) {
                // 宽限内播放层没回完成确认：强制收尾（防卡死）；到时即视为全额消耗
                pendingReasonOnComplete = null
                finishTurn(turn, TurnEndReason.TIMEOUT, turn.budgetMs)
            }
            TIMER_WALL -> if (isInCrossExam()) {
                // 轮时耗尽：取消当前问答并整轮结束
                android.util.Log.i(TAG, "攻辩轮墙钟耗尽，按正常时限收尾")
                pendingReasonOnComplete = TurnEndReason.TIMEOUT
                turn.stage = TurnStage.DRAINING
                speechPlayer.cancelActive("wall-deadline")
                scheduleTimer(TIMER_GRACE, configSpeech().drainGraceMs)
                wallEndedForce = true
            }
        }
        publish()
    }

    private var wallEndedForce = false

    private fun failTurn(turn: ActiveTurn) {
        val retries = configSpeech().failureRetries
        if (turn.attempt < retries) {
            turn.attempt++
            emit(DebateEvent.TurnResumed(turn.turnId, turn.speakerId, turn.budgetMs))
            beginAudioGate(turn)
        } else {
            finishTurn(turn, TurnEndReason.FAILED)
        }
    }

    private fun freezeTimers() {
        timer.cancelAll()
        // pendingTimers 保留：恢复时按 fireAt - now 重排
    }

    private fun unfreezeTimers() {
        val now = clock.nowMs()
        val entries = synchronized(pendingTimers) { pendingTimers.toList() }
        for ((key, pending) in entries) {
            val remaining = pending.fireAtMs - now
            if (remaining <= 0L) {
                synchronized(pendingTimers) { pendingTimers.remove(key) }
                step { handleTimer(key) }
            } else {
                timer.schedule(key, remaining) { onTimerFired(key) }
            }
        }
    }

    // ── 查询辅助 ────────────────────────────────────────────────────────

    /**
     * 串行执行（同步蹦床）：引擎所有状态迁移都不含挂起点，重入（播放层在
     * start 内同步回调 notify*）时入队、外层收尾后迭代排空——单线程事实源，
     * 不需要锁，也天然免疫异步事件循环不排空的问题。
     */
    private var stepping = false
    private val stepQueue = ArrayDeque<() -> Unit>()

    private fun step(block: () -> Unit) {
        if (stepping) {
            stepQueue += block
            return
        }
        stepping = true
        try {
            block()
            while (stepQueue.isNotEmpty()) {
                stepQueue.removeFirst().invoke()
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "engine step crashed", t)
            lastError = "engine step: ${t.javaClass.simpleName}: ${t.message}"
            matchState = MatchState.ERROR
            publish()
        } finally {
            stepping = false
            stepQueue.clear()
        }
    }

    private fun emit(event: DebateEvent) {
        _events.tryEmit(event)
    }

    private fun reject(command: String, reason: String) {
        emit(DebateEvent.Rejected(command, reason))
        publish()
    }

    private fun publish() {
        val p = plan
        val turn = activeTurn
        _state.value = DebatePublicState(
            matchState = matchState,
            epoch = epoch,
            matchId = p?.matchId,
            phase = p?.segments?.getOrNull(segIdx)?.phase,
            segmentIndex = segIdx,
            segmentCount = p?.segments?.size ?: 0,
            currentTurn = turn?.let {
                ActiveTurnSnapshot(
                    turnId = it.turnId,
                    speakerId = it.speakerId,
                    team = it.team,
                    kind = it.kind,
                    stage = it.stage,
                    attempt = it.attempt,
                    remainingMs = it.remainingMs(clock.nowMs(), audioStartedAtMs),
                    frozen = frozenTurn != null,
                )
            },
            legalSpeakers = listOfNotNull(expectedSpeakerId()),
            postureWaiting = posturePending.toList().takeIf { turn != null } ?: emptyList(),
            teamBalancesMs = balances.toMap().takeIf { it.isNotEmpty() },
            wallRemainingMs = wallExpiresAtMs?.let { (it - clock.nowMs()).coerceAtLeast(0L) },
            lastError = lastError,
        )
    }

    private fun expectedSpeakerId(): String? {
        if (matchState != MatchState.RUNNING) return null
        return activeTurn?.speakerId
    }

    private fun currentSegment(): DebateSegment? = plan?.segments?.getOrNull(segIdx)

    private fun currentCrossRound(): CrossRound? =
        (currentSegment() as? DebateSegment.CrossExam)?.rounds?.getOrNull(roundIdx)

    private fun currentRoundWallMs(): Long = currentCrossRound()?.wallBudgetMs ?: 0L

    private fun isInCrossExam(): Boolean = currentSegment() is DebateSegment.CrossExam

    /** 攻辩当前轮是否还要继续（提问完应答没说 / 墙钟未强制结束）。 */
    private fun roundContinues(): Boolean = !roundNextIsAnswer && !wallEndedForce

    private fun consumedSoFar(turn: ActiveTurn): Long {
        val started = audioStartedAtMs ?: return 0L
        if (turn.stage != TurnStage.SPEAKING && turn.stage != TurnStage.DRAINING) return 0L
        return (clock.nowMs() - started).coerceAtLeast(0L)
    }

    /** AI 播放层确认实际台词；忽略取消/重载后的旧回调，导出记录与声音一致。 */
    fun notifySpeechTextPrepared(epoch: Long, turnId: Long, text: String) = step {
        val turn = activeTurn ?: return@step
        if (epoch != this.epoch || turnId != turn.turnId || text.isBlank()) return@step
        if (matchState != MatchState.RUNNING || frozenTurn != null || turn.stage != TurnStage.PREPARING_AUDIO) return@step
        preparedTexts[turnId] = text
    }

    private fun preparationTimeoutMs(): Long = configSpeech().let {
        it.firstAudioTimeoutMs + if (it.mode == "ai") it.generationTimeoutMs else 0L
    }

    private fun currentScript(turn: ActiveTurn): String {
        preparedTexts[turn.turnId]?.let { return it }
        val provider = scriptProvider ?: return ""
        return provider.scriptFor(turn.phase, turn.speakerId, turn.turnIndexInPhase)
    }

    private fun configSpeech() = config?.speech ?: DebateConfigDto.SpeechDto()
    private fun configPosture() = config?.posture ?: DebateConfigDto.PostureDto()

    private fun segmentLabel(seg: DebateSegment): String = when (seg.phase) {
        DebatePhase.HOST_OPENING -> "主持开场"
        DebatePhase.OPENING_STATEMENT -> "开篇立论"
        DebatePhase.CROSS_EXAM -> "攻辩"
        DebatePhase.CROSS_SUMMARY -> "攻辩小结"
        DebatePhase.FREE_DEBATE -> "自由辩论"
        DebatePhase.CLOSING -> "总结陈词"
        DebatePhase.HOST_CLOSING -> "主持结束"
    }

    private fun recordTranscript(
        turn: ActiveTurn,
        reason: TurnEndReason,
        consumedMs: Long,
        status: TranscriptStatus,
    ) {
        val text = currentScript(turn)
        val entry = TranscriptEntry(
            turnId = turn.turnId,
            epoch = epoch,
            phase = turn.phase,
            speakerId = turn.speakerId,
            team = turn.team,
            kind = turn.kind,
            text = text,
            status = status,
            endReason = reason,
            audioStartedElapsedMs = audioStartedAtMs,
            endedElapsedMs = clock.nowMs(),
            consumedMs = consumedMs,
        )
        synchronized(transcript) { transcript += entry }
        emit(DebateEvent.TranscriptAppended(entry))
    }

    private fun teardownForReset(sitEveryone: Boolean) {
        timer.cancelAll()
        pendingTimers.clear()
        if (sitEveryone) {
            postureDirector?.onPostureCommand(PostureCommand(-1L, DebateIds.ALL, PostureAction.SIT))
        }
        activeTurn = null
        preparedTexts.clear()
        frozenTurn = null
        posturePending.clear()
        audioStartedAtMs = null
        wallExpiresAtMs = null
        wallEndedForce = false
        pendingReasonOnComplete = null
        segIdx = 0
        stmtIdx = 0
        segmentFailures = 0
        roundIdx = 0
        roundNextIsAnswer = false
        freeRotationIdx.clear()
        freeActiveTeam = null
        balances.clear()
        matchStartedAtMs = null
        turnSeq = 0L
        synchronized(transcript) { transcript.clear() }
        lastError = null
    }

    companion object {
        const val TAG = "DebateEngine"
        const val TIMER_POSTURE = "posture"
        const val TIMER_PREP = "prep"
        const val TIMER_TURN = "turn"
        const val TIMER_GRACE = "grace"
        const val TIMER_WALL = "wall"
    }
}

// ── 公共状态与事件 ──────────────────────────────────────────────────────

enum class MatchState { IDLE, READY, RUNNING, PAUSED, FINISHED, STOPPED, ERROR }

/** 回合内的阶段（doc §5 的单次发言状态机，P0 子集）。 */
enum class TurnStage { AWAITING_POSTURE, PREPARING_AUDIO, SPEAKING, DRAINING }

data class ActiveTurn(
    val turnId: Long,
    val epoch: Long,
    val phase: DebatePhase,
    val speakerId: String,
    val team: DebateTeam?,
    val kind: TurnKind,
    /** 个人发言预算（首段音频起表）。暂停恢复/续讲时按剩余量改写。 */
    var budgetMs: Long,
    val turnIndexInPhase: Int,
    var stage: TurnStage = TurnStage.AWAITING_POSTURE,
    var attempt: Int = 0,
) {
    fun remainingMs(nowMs: Long, audioStartedAtMs: Long?): Long {
        val started = audioStartedAtMs ?: return budgetMs
        return (budgetMs - (nowMs - started)).coerceAtLeast(0L)
    }
}

data class ActiveTurnSnapshot(
    val turnId: Long,
    val speakerId: String,
    val team: DebateTeam?,
    val kind: TurnKind,
    val stage: TurnStage,
    val attempt: Int,
    val remainingMs: Long,
    val frozen: Boolean,
)

data class DebatePublicState(
    val matchState: MatchState,
    val epoch: Long,
    val matchId: String?,
    val phase: DebatePhase?,
    val segmentIndex: Int,
    val segmentCount: Int,
    val currentTurn: ActiveTurnSnapshot?,
    val legalSpeakers: List<String>,
    /** 姿态门等待中的参与者（「起立中」UI 显示用）。 */
    val postureWaiting: List<String>,
    val teamBalancesMs: Map<DebateTeam, Long>?,
    val wallRemainingMs: Long?,
    val lastError: String?,
) {
    companion object {
        fun idle() = DebatePublicState(
            matchState = MatchState.IDLE, epoch = 0L, matchId = null, phase = null,
            segmentIndex = 0, segmentCount = 0, currentTurn = null, legalSpeakers = emptyList(),
            postureWaiting = emptyList(), teamBalancesMs = null, wallRemainingMs = null, lastError = null,
        )
    }
}

sealed class DebateEvent {
    data class MatchStateChanged(val state: MatchState, val reason: String) : DebateEvent()
    data class PhaseChanged(val phase: DebatePhase, val segmentIndex: Int, val segmentCount: Int, val label: String) : DebateEvent()
    data class RoundStarted(val roundIndex: Int, val questionerId: String, val answererId: String) : DebateEvent()
    data class RoundEnded(val roundIndex: Int, val reason: String) : DebateEvent()
    data class TurnStarted(val turn: ActiveTurn, val text: String) : DebateEvent()
    data class TurnResumed(val turnId: Long, val speakerId: String, val remainingMs: Long) : DebateEvent()
    data class SpeechAudioStarted(val turnId: Long, val speakerId: String, val atElapsedMs: Long) : DebateEvent()
    data class TurnEnded(
        val turnId: Long,
        val speakerId: String,
        val reason: TurnEndReason,
        val consumedMs: Long,
        val teamBalanceAfterMs: Long?,
    ) : DebateEvent()
    data class TeamBalanceChanged(val team: DebateTeam, val remainingMs: Long) : DebateEvent()
    data class TranscriptAppended(val entry: TranscriptEntry) : DebateEvent()
    data class MatchFinished(val matchId: String, val totalTurns: Int, val elapsedMs: Long) : DebateEvent()
    data class ManualSkip(val fromPhase: DebatePhase?, val toSegmentIndex: Int) : DebateEvent()
    data class Rejected(val command: String, val reason: String) : DebateEvent()
}
