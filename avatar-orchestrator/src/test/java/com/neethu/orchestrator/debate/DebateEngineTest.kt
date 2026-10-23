package com.neethu.orchestrator.debate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 赛制引擎全流程虚拟时钟验证（doc §13.1）：阶段顺序、攻辩子轮、非法 grant、
 * 双方队伍余额、到时截止、暂停恢复、不重入、旧回调忽略。
 */
private fun quickConfig(
    standWhenSpeaking: Boolean = false,
    scripts: Map<String, Map<String, String>> = emptyMap(),
    speech: DebateConfigDto.SpeechDto = DebateConfigDto.SpeechDto(mode = "fixed"),
) = DebateConfigDto(
    matchId = "quick-001",
    topic = "人工智能的发展是否更有利于人类创造力",
    sides = DebateConfigDto.SidesDto(PRO = "更有利", CON = "更不利"),
    rules = DebateConfigDto.RulesDto(
        openingMs = 1_000L,
        crossRoundMs = 2_000L,
        questionMs = 500L,
        answerMs = 800L,
        crossSummaryMs = 1_000L,
        freeTeamMs = 300L,
        freeTurnMs = 300L,
        closingMs = 1_000L,
        hostSegmentMs = 400L,
    ),
    speech = speech,
    posture = DebateConfigDto.PostureDto(standWhenSpeaking = standWhenSpeaking),
    participants = testParticipants(),
    scripts = scripts,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DebateEngineTest {

    @Test fun `turn started observers see current speaker before posture or playback`() {
        val h = Harness(standWhenSpeaking = true)
        h.build()
        val observed = mutableListOf<Pair<String, String?>>()
        h.eventScope.launch {
            h.engine.events.collect { event ->
                if (event is DebateEvent.TurnStarted) {
                    observed += event.turn.speakerId to h.engine.state.value.currentTurn?.speakerId
                }
            }
        }
        h.engine.start()
        h.engine.endTurn()
        assertEquals(listOf("HOST" to "HOST", "PRO1" to "PRO1"), observed)
    }

    @Test fun `AI preparation allows generation time and transcript stores actual speech`() {
        val h = Harness()
        h.build(quickConfig(speech = DebateConfigDto.SpeechDto(
            mode = "ai", generationTimeoutMs = 700L, firstAudioTimeoutMs = 300L)))
        h.engine.start()
        assertEquals(1000L, h.timer.remaining(DebateEngine.TIMER_PREP))
        val turn = h.engine.state.value.currentTurn!!
        val epoch = h.engine.state.value.epoch
        h.engine.notifySpeechTextPrepared(epoch - 1, turn.turnId, "过期的台词")
        h.engine.notifySpeechTextPrepared(epoch, turn.turnId + 1, "错误回合的台词")
        h.engine.notifySpeechTextPrepared(epoch, turn.turnId, "我是豆包，这次给灵感搭把手！")
        h.engine.notifySpeechAudioStarted(epoch, turn.turnId)
        h.engine.notifySpeechCompleted(epoch, turn.turnId, false, 100L)
        assertEquals("我是豆包，这次给灵感搭把手！", h.engine.transcriptEntries.single().text)
    }

    // ── 测试替身 ────────────────────────────────────────────────────────

    class TestClock(var now: Long = 10_000L) : DebateClock {
        override fun nowMs(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    class TestTimer : DebateTimer {
        class Pending(val key: String, val fireAt: Long, val action: () -> Unit)
        val pending = linkedMapOf<String, Pending>()
        var clock: TestClock? = null

        override fun schedule(key: String, delayMs: Long, onFire: () -> Unit) {
            pending[key] = Pending(key, (clock?.now ?: 0L) + delayMs, onFire)
        }

        override fun cancel(key: String) { pending.remove(key) }
        override fun cancelAll() { pending.clear() }

        fun fire(key: String) {
            val p = pending.remove(key) ?: error("timer $key not scheduled")
            p.action()
        }

        fun armed(key: String): Boolean = pending.containsKey(key)
        fun remaining(key: String): Long? = pending[key]?.let { it.fireAt - (clock?.now ?: 0L) }
    }

    /** 可编程发言播放层：auto 模式在 start 时自动报「音频已起播 + 播完」。 */
    class FakeSpeechPlayer : DebateSpeechPlayer {
        val startedTurns = mutableListOf<ActiveTurn>()
        val startedTexts = mutableListOf<String>()
        val cancelReasons = mutableListOf<String>()
        var autoMode: AutoMode = AutoMode.NONE
        var consumeMsPerTurn: Long = 100L
        var clock: TestClock? = null
        var released = false

        enum class AutoMode { NONE, COMPLETE, FAIL }

        lateinit var engine: DebateEngine

        override fun start(turn: ActiveTurn, text: String) {
            startedTurns += turn
            startedTexts += text
            when (autoMode) {
                AutoMode.COMPLETE -> {
                    engine.notifySpeechAudioStarted(turn.epoch, turn.turnId)
                    // 逐回合上报实测发言时长：模拟真实发言耗时（自由辩论扣余额靠它）
                    clock?.advance(consumeMsPerTurn)
                    engine.notifySpeechCompleted(
                        turn.epoch, turn.turnId, interrupted = false, consumedOverrideMs = consumeMsPerTurn,
                    )
                }
                AutoMode.FAIL -> engine.notifySpeechFailed(turn.epoch, turn.turnId, RuntimeException("fake"))
                AutoMode.NONE -> Unit
            }
        }

        override fun cancelActive(reason: String) { cancelReasons += reason }
        override fun release() { released = true }
    }

    class RecordingPosture : DebatePostureDirector {
        val commands = mutableListOf<PostureCommand>()
        override fun onPostureCommand(command: PostureCommand) { commands += command }
    }

    // ── 装配 ────────────────────────────────────────────────────────────

    private class Harness(val standWhenSpeaking: Boolean = false) {
        val clock = TestClock()
        val timer = TestTimer().apply { this.clock = clock }
        val player = FakeSpeechPlayer()
        val posture = RecordingPosture()
        val events = mutableListOf<DebateEvent>()
        lateinit var engine: DebateEngine

        val eventScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        fun build(dto: DebateConfigDto = quickConfig(standWhenSpeaking)) {
            engine = DebateEngine(clock, timer, player, posture)
            player.engine = engine
            player.clock = clock
            eventScope.launch { engine.events.collect { events += it } }
            engine.load(dto)
        }

        /** 姿态门放行：把引擎当前等待集合全报就绪（循环到不再等待）。 */
        fun releasePosture() {
            var guard = 0
            while (guard++ < 10) {
                val state = engine.state.value
                val waiting = state.postureWaiting
                if (waiting.isEmpty()) return
                val turnId = state.currentTurn?.turnId ?: return
                for (id in waiting) engine.notifyPostureReady(state.epoch, turnId, id)
            }
        }

        fun runToFinish() {
            player.autoMode = FakeSpeechPlayer.AutoMode.COMPLETE
            engine.start()
            releasePosture()
            var guard = 0
            while (engine.state.value.matchState == MatchState.RUNNING && guard++ < 300) {
                val before = engine.state.value.currentTurn?.turnId
                releasePosture()
                if (engine.state.value.currentTurn?.turnId == before &&
                    engine.state.value.postureWaiting.isEmpty()
                ) break
            }
        }

        fun turnEnds() = events.filterIsInstance<DebateEvent.TurnEnded>()
        fun rejections() = events.filterIsInstance<DebateEvent.Rejected>()
        fun phaseEvents() = events.filterIsInstance<DebateEvent.PhaseChanged>()
        fun speakersInOrder(): List<String> = turnEnds().map { it.speakerId }
    }

    // ── 全赛程 ──────────────────────────────────────────────────────────

    @Test
    fun `固定稿跑完整赛程_阶段顺序与发言名单符合默认赛制`() {
        val h = Harness()
        h.build()
        assertEquals(MatchState.READY, h.engine.state.value.matchState)

        h.runToFinish()

        assertEquals(MatchState.FINISHED, h.engine.state.value.matchState)
        assertEquals(
            listOf(
                "HOST_OPENING", "OPENING_STATEMENT", "CROSS_EXAM", "CROSS_SUMMARY",
                "FREE_DEBATE", "CLOSING", "HOST_CLOSING",
            ),
            h.phaseEvents().map { it.phase.name },
        )
        // 全场发言顺序：HOST；PRO1 CON1；攻辩每轮问答各一（4 轮 8 次）；PRO1 CON1；
        // 自由 6 次（每队 300ms÷100ms）；CON4 PRO4（反方先结）；HOST
        assertEquals(
            listOf(
                "HOST", "PRO1", "CON1",
                "PRO2", "CON2", "CON2", "PRO2", "PRO3", "CON3", "CON3", "PRO3",
                "PRO1", "CON1",
                "PRO1", "CON1", "PRO2", "CON2", "PRO3", "CON3",
                "CON4", "PRO4",
                "HOST",
            ),
            h.speakersInOrder(),
        )
        assertEquals(22, h.turnEnds().size)
    }

    @Test
    fun `自由辩论双方余额扣减_耗尽后段位结束`() {
        val h = Harness()
        h.build()
        h.runToFinish()
        val balances = h.events.filterIsInstance<DebateEvent.TeamBalanceChanged>()
        assertEquals(0L, balances.last { it.team == DebateTeam.PRO }.remainingMs)
        assertEquals(0L, balances.last { it.team == DebateTeam.CON }.remainingMs)
        // 自由段结束才进入 CLOSING
        assertTrue(h.speakersInOrder().indexOf("PRO3") < h.speakersInOrder().indexOf("CON4"))
    }

    @Test
    fun `配置稿优先于内置兜底稿`() {
        val h = Harness()
        h.build(quickConfig(scripts = mapOf("PRO1" to mapOf("OPENING_STATEMENT" to "固定稿内容甲"))))
        h.runToFinish()
        val idx = h.player.startedTexts.indexOf("固定稿内容甲")
        assertTrue(idx >= 0)
        assertEquals("PRO1", h.player.startedTurns[idx].speakerId)
    }

    @Test
    fun `主持人稿由辩题立场确定模板填入`() {
        val h = Harness()
        h.build()
        h.runToFinish()
        val hostOpening = h.player.startedTexts.first()
        assertTrue(hostOpening.contains("人工智能的发展是否更有利于人类创造力"))
        assertTrue(hostOpening.contains("更有利"))
    }

    // ── 命令面 ──────────────────────────────────────────────────────────

    @Test
    fun `未load时start被拒绝_ready态可重复load`() {
        val h = Harness()
        h.engine = DebateEngine(h.clock, h.timer, h.player, h.posture)
        h.player.engine = h.engine
        h.player.clock = h.clock
        h.eventScope.launch { h.engine.events.collect { h.events += it } }
        h.engine.start()
        assertTrue(h.rejections().any { it.command == "start" })

        h.engine.load(quickConfig())
        assertEquals(MatchState.READY, h.engine.state.value.matchState)
        h.engine.load(quickConfig())
        assertEquals(MatchState.READY, h.engine.state.value.matchState)
    }

    @Test
    fun `比赛中load被拒绝`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, h.engine.state.value.currentTurn!!.turnId)

        h.engine.load(quickConfig())
        assertTrue(h.rejections().any { it.command == "load" })
        assertEquals(MatchState.RUNNING, h.engine.state.value.matchState)
    }

    @Test
    fun `非法grant被拒_合法grant不产生拒绝`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        h.engine.grant("CON1")
        assertTrue(h.rejections().any { it.command == "grant" && it.reason.contains("CON1") })
        val before = h.rejections().size
        h.engine.grant("HOST")
        assertEquals(before, h.rejections().size)
    }

    @Test
    fun `end_turn主动交棒_下一发言者立即接续`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, h.engine.state.value.currentTurn!!.turnId)
        h.clock.advance(300)
        h.engine.endTurn()

        assertEquals(MatchState.RUNNING, h.engine.state.value.matchState)
        val end = h.turnEnds().last()
        assertEquals("HOST", end.speakerId)
        assertEquals(TurnEndReason.MANUAL_END, end.reason)
        assertEquals(300L, end.consumedMs)
        assertEquals("PRO1", h.engine.state.value.currentTurn!!.speakerId)
    }

    @Test
    fun `next_phase中止当前段并记录人工跳过`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        h.engine.nextPhase()

        assertEquals(DebatePhase.OPENING_STATEMENT, h.engine.state.value.phase)
        val skip = h.events.filterIsInstance<DebateEvent.ManualSkip>().single()
        assertEquals(DebatePhase.HOST_OPENING, skip.fromPhase)
        assertEquals(TurnEndReason.SKIPPED, h.turnEnds().last().reason)
        assertEquals(TranscriptStatus.SKIPPED, h.engine.transcriptEntries.last().status)
    }

    // ── 计时与打断 ──────────────────────────────────────────────────────

    @Test
    fun `个人到时_取消播放并按宽限收尾_记录为打断`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        val hostTurn = h.engine.state.value.currentTurn!!
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, hostTurn.turnId)
        assertTrue(h.timer.armed(DebateEngine.TIMER_TURN))
        assertEquals(400L, h.timer.remaining(DebateEngine.TIMER_TURN))

        h.timer.fire(DebateEngine.TIMER_TURN)
        assertTrue(h.player.cancelReasons.contains("deadline"))
        assertTrue(h.timer.armed(DebateEngine.TIMER_GRACE))
        h.timer.fire(DebateEngine.TIMER_GRACE)

        val end = h.turnEnds().last()
        assertEquals(TurnEndReason.TIMEOUT, end.reason)
        assertEquals(400L, end.consumedMs)
        assertEquals("PRO1", h.engine.state.value.currentTurn!!.speakerId)
        assertEquals(TranscriptStatus.INTERRUPTED, h.engine.transcriptEntries.last().status)
    }

    @Test
    fun `宽限内收到完成确认则不重复收尾_宽限定时器无副作用`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        val hostTurn = h.engine.state.value.currentTurn!!
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, hostTurn.turnId)

        h.timer.fire(DebateEngine.TIMER_TURN)
        h.engine.notifySpeechCompleted(h.engine.state.value.epoch, hostTurn.turnId, interrupted = true)

        assertEquals("PRO1", h.engine.state.value.currentTurn!!.speakerId)
        assertEquals(1, h.turnEnds().size)
        assertEquals(TurnEndReason.TIMEOUT, h.turnEnds()[0].reason)
        // 完成确认已收尾：宽限定时器被撤销，迟到触发不可能发生
        assertFalse(h.timer.armed(DebateEngine.TIMER_GRACE))
    }

    @Test
    fun `首音频超时走失败策略_重试一次后跳过`() {
        val h = Harness()
        h.build(
            quickConfig(
                speech = DebateConfigDto.SpeechDto(mode = "fixed", firstAudioTimeoutMs = 100L, failureRetries = 1),
            )
        )
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        assertTrue(h.timer.armed(DebateEngine.TIMER_PREP))
        h.timer.fire(DebateEngine.TIMER_PREP)
        assertTrue(h.timer.armed(DebateEngine.TIMER_PREP)) // 重试
        h.timer.fire(DebateEngine.TIMER_PREP)

        val end = h.turnEnds().last()
        assertEquals(TurnEndReason.FAILED, end.reason)
        assertEquals(TranscriptStatus.FAILED, h.engine.transcriptEntries.last().status)
        assertEquals("PRO1", h.engine.state.value.currentTurn!!.speakerId)
    }

    @Test
    fun `播放层报错走失败策略`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        val hostTurn = h.engine.state.value.currentTurn!!
        // 第一次失败 → 重试；第二次失败 → 重试耗尽 → FAILED → PRO1 接续
        h.engine.notifySpeechFailed(h.engine.state.value.epoch, hostTurn.turnId, RuntimeException("x1"))
        assertEquals(hostTurn.turnId, h.engine.state.value.currentTurn!!.turnId)
        assertEquals(1, h.engine.state.value.currentTurn!!.attempt)
        h.engine.notifySpeechFailed(h.engine.state.value.epoch, hostTurn.turnId, RuntimeException("x2"))
        val end = h.turnEnds().last()
        assertEquals(TurnEndReason.FAILED, end.reason)
        assertEquals("PRO1", h.engine.state.value.currentTurn!!.speakerId)
        assertEquals(TranscriptStatus.FAILED, h.engine.transcriptEntries.last().status)
    }

    @Test
    fun `连续失败触发段级熔断_跳过整个阶段`() {
        val h = Harness()
        h.build(
            quickConfig(speech = DebateConfigDto.SpeechDto(mode = "fixed", failureRetries = 0)),
        )
        // 全场所有发言都失败：自由辩论零消耗不会死循环，逐段熔断直至 FINISHED
        h.player.autoMode = FakeSpeechPlayer.AutoMode.FAIL
        h.engine.start()
        h.releasePosture()
        assertEquals(MatchState.FINISHED, h.engine.state.value.matchState)
        // 每段都以 FAILED 收尾（失败次数 = 段容量）
        assertTrue(h.turnEnds().all { it.reason == TurnEndReason.FAILED })
    }

    // ── 攻辩子轮与墙钟 ──────────────────────────────────────────────────

    @Test
    fun `攻辩墙钟耗尽强制掐断问答并进入下一轮`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        h.engine.nextPhase() // → 立论
        h.engine.nextPhase() // → 攻辩

        val qTurn = h.engine.state.value.currentTurn!!
        assertEquals(TurnKind.CROSS_QUESTION, qTurn.kind)
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, qTurn.turnId)
        assertTrue(h.timer.armed(DebateEngine.TIMER_WALL))
        assertEquals(2_000L, h.timer.remaining(DebateEngine.TIMER_WALL))

        h.timer.fire(DebateEngine.TIMER_WALL)
        h.timer.fire(DebateEngine.TIMER_GRACE)

        val roundEvent = h.events.filterIsInstance<DebateEvent.RoundEnded>().single()
        assertEquals("wall-deadline", roundEvent.reason)
        val q2 = h.engine.state.value.currentTurn!!
        assertEquals("CON2", q2.speakerId)
        assertEquals(TurnKind.CROSS_QUESTION, q2.kind)
    }

    @Test
    fun `攻辩正常换轮清除旧墙钟避免误杀下一轮起立`() {
        val h = Harness(standWhenSpeaking = true)
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.engine.nextPhase()
        h.engine.nextPhase()
        h.releasePosture()
        val q = h.engine.state.value.currentTurn!!
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, q.turnId)
        assertTrue(h.timer.armed(DebateEngine.TIMER_WALL))
        h.engine.notifySpeechCompleted(h.engine.state.value.epoch, q.turnId, false)
        val a = h.engine.state.value.currentTurn!!
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, a.turnId)
        h.engine.notifySpeechCompleted(h.engine.state.value.epoch, a.turnId, false)
        assertFalse(h.timer.armed(DebateEngine.TIMER_WALL))
        assertEquals(TurnStage.AWAITING_POSTURE, h.engine.state.value.currentTurn!!.stage)
    }

    @Test
    fun `起立中暂停恢复仍等待站稳才播放`() {
        val h = Harness(standWhenSpeaking = true)
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.engine.pause()
        h.engine.resume()
        assertTrue(h.player.startedTurns.isEmpty())
        assertEquals(TurnStage.AWAITING_POSTURE, h.engine.state.value.currentTurn!!.stage)
        h.releasePosture()
        assertEquals(1, h.player.startedTurns.size)
    }

    @Test
    fun `手动结束发言先取消旧音频再交接`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.engine.endTurn()
        assertTrue(h.player.cancelReasons.contains("manual-end"))
        assertEquals("PRO1", h.engine.state.value.currentTurn!!.speakerId)
    }

    @Test
    fun `起立超时不能绕过姿态门控直接重试音频`() {
        val h = Harness(standWhenSpeaking = true)
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        val old = h.engine.state.value.currentTurn!!
        assertEquals(TurnStage.AWAITING_POSTURE, old.stage)
        h.timer.fire(DebateEngine.TIMER_POSTURE)
        assertTrue(h.player.startedTurns.isEmpty())
        assertTrue(h.turnEnds().any { it.turnId == old.turnId && it.reason == TurnEndReason.FAILED })
        h.engine.notifyPostureReady(h.engine.state.value.epoch, old.turnId, "HOST")
        assertTrue(h.player.startedTurns.isEmpty())
    }

    @Test
    fun `攻辩轮内提问结束接应答_轮末双方一起坐下`() {
        val h = Harness(standWhenSpeaking = true)
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture() // HOST 起立
        h.engine.nextPhase() // 立论
        h.releasePosture() // PRO1 起立
        h.engine.nextPhase() // 攻辩

        // 轮开始：PRO2+CON2 一起请求起立
        val standCmd = h.posture.commands.last()
        assertEquals(PostureAction.STAND, standCmd.action)
        assertEquals(setOf("PRO2", "CON2"), standCmd.participantIds.toSet())
        h.posture.commands.clear() // 只核查本轮内的姿态；跳段会收起上一段的站姿。
        h.releasePosture()

        val qTurn = h.engine.state.value.currentTurn!!
        assertEquals("PRO2", qTurn.speakerId)
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, qTurn.turnId)
        h.engine.notifySpeechCompleted(h.engine.state.value.epoch, qTurn.turnId, false)

        // 提问完 → 应答；轮内没有新的姿态指令
        val aTurn = h.engine.state.value.currentTurn!!
        assertEquals("CON2", aTurn.speakerId)
        assertEquals(TurnKind.CROSS_ANSWER, aTurn.kind)
        assertTrue(h.posture.commands.none { it.action == PostureAction.SIT })
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, aTurn.turnId)
        h.engine.notifySpeechCompleted(h.engine.state.value.epoch, aTurn.turnId, false)

        // 轮末双方一起坐下；随后第二轮双方再次起立
        val sitCmd = h.posture.commands.last { it.action == PostureAction.SIT }
        assertEquals(setOf("PRO2", "CON2"), sitCmd.participantIds.toSet())
        val stand2 = h.posture.commands.last()
        assertEquals(setOf("CON2", "PRO2"), stand2.participantIds.toSet())
    }

    @Test
    fun `攻辩墙钟在首个问题音频起播时才启动`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        h.engine.nextPhase()
        h.engine.nextPhase()

        // 尚未起播：墙钟未挂
        assertFalse(h.timer.armed(DebateEngine.TIMER_WALL))
        assertNull(h.engine.state.value.wallRemainingMs)
    }

    // ── 暂停恢复与旧回调 ────────────────────────────────────────────────

    @Test
    fun `暂停冻结计时_恢复按剩余量续讲`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        val hostTurn = h.engine.state.value.currentTurn!!
        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, hostTurn.turnId)
        h.clock.advance(150)

        h.engine.pause("test")
        assertEquals(MatchState.PAUSED, h.engine.state.value.matchState)
        assertEquals(1, h.turnEnds().size)
        assertEquals(TurnEndReason.PAUSED, h.turnEnds()[0].reason)
        assertEquals(150L, h.turnEnds()[0].consumedMs)
        assertTrue(h.engine.state.value.currentTurn!!.frozen)

        // 暂停中迟到完成回调：吞掉不推进
        h.engine.notifySpeechCompleted(h.engine.state.value.epoch, hostTurn.turnId, true)
        assertEquals(MatchState.PAUSED, h.engine.state.value.matchState)

        h.engine.resume()
        assertEquals(MatchState.RUNNING, h.engine.state.value.matchState)
        val resumed = h.engine.state.value.currentTurn!!
        assertEquals(hostTurn.turnId, resumed.turnId)
        assertEquals(250L, resumed.remainingMs) // 400 全额 - 已播 150
        assertTrue(h.timer.armed(DebateEngine.TIMER_PREP))

        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, hostTurn.turnId)
        assertEquals(250L, h.timer.remaining(DebateEngine.TIMER_TURN)) // 剩余量而非全额
        h.clock.advance(250)
        h.engine.notifySpeechCompleted(h.engine.state.value.epoch, hostTurn.turnId, false)
        assertEquals("PRO1", h.engine.state.value.currentTurn!!.speakerId)
        assertEquals(2, h.turnEnds().size)
    }

    @Test
    fun `迟到旧回合回调被忽略_不会推进新回合`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        val oldTurnId = h.engine.state.value.currentTurn!!.turnId
        h.engine.endTurn()
        val newTurnId = h.engine.state.value.currentTurn!!.turnId
        assertTrue(newTurnId != oldTurnId)

        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, oldTurnId)
        h.engine.notifySpeechCompleted(h.engine.state.value.epoch, oldTurnId, false)
        assertEquals(newTurnId, h.engine.state.value.currentTurn!!.turnId)
        assertEquals(1, h.turnEnds().size)

        h.engine.notifySpeechCompleted(h.engine.state.value.epoch + 1, newTurnId, false)
        assertEquals(newTurnId, h.engine.state.value.currentTurn!!.turnId)
    }

    @Test
    fun `stop终止比赛_reset回到READY并递增epoch`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        val epoch0 = h.engine.state.value.epoch

        h.engine.stop("test-stop")
        assertEquals(MatchState.STOPPED, h.engine.state.value.matchState)
        h.engine.start()
        assertTrue(h.rejections().any { it.command == "start" })

        h.engine.reset()
        assertEquals(MatchState.READY, h.engine.state.value.matchState)
        assertEquals(epoch0 + 1, h.engine.state.value.epoch)

        h.player.autoMode = FakeSpeechPlayer.AutoMode.COMPLETE
        h.engine.start()
        h.releasePosture()
        var guard = 0
        while (h.engine.state.value.matchState == MatchState.RUNNING && guard++ < 300) {
            val before = h.engine.state.value.currentTurn?.turnId
            h.releasePosture()
            if (h.engine.state.value.currentTurn?.turnId == before &&
                h.engine.state.value.postureWaiting.isEmpty()
            ) break
        }
        assertEquals(MatchState.FINISHED, h.engine.state.value.matchState)
    }

    @Test
    fun `状态快照携带合法发言人_余额与墙钟`() {
        val h = Harness()
        h.build()
        h.player.autoMode = FakeSpeechPlayer.AutoMode.NONE
        h.engine.start()
        h.releasePosture()
        assertEquals(listOf("HOST"), h.engine.state.value.legalSpeakers)
        assertNull(h.engine.state.value.teamBalancesMs)

        h.engine.nextPhase()
        h.engine.nextPhase()
        h.releasePosture()
        assertEquals(listOf("PRO2"), h.engine.state.value.legalSpeakers)

        h.engine.notifySpeechAudioStarted(h.engine.state.value.epoch, h.engine.state.value.currentTurn!!.turnId)
        assertEquals(2_000L, h.engine.state.value.wallRemainingMs)
    }
}
