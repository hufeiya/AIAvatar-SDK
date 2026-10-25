package com.neethu.companion

import android.os.SystemClock
import android.util.Log
import com.neethu.corelib.AvatarController
import com.neethu.corelib.ContactKind
import com.neethu.corelib.Lang
import com.neethu.corelib.WalkPhase
import com.neethu.orchestrator.autonomy.AutonomyDirector
import com.neethu.orchestrator.autonomy.AutonomyDirective
import com.neethu.orchestrator.autonomy.AutonomySchedule
import com.neethu.orchestrator.autonomy.AutonomySpot
import com.neethu.orchestrator.autonomy.AutonomyStateKind
import com.neethu.orchestrator.autonomy.AutonomyWorld
import com.neethu.orchestrator.autonomy.GreetingScheduler
import com.neethu.orchestrator.autonomy.ThoughtComposer
import com.neethu.orchestrator.face.GazeMode
import com.neethu.orchestrator.memory.MemoryCategories
import com.neethu.orchestrator.memory.MemoryStore
import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.session.ConversationPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 自主生命体装配器（docs/autonomy-feasibility.md §3）：orchestrator 的大脑
 * （[AutonomyDirector] 决策 + [GreetingScheduler] 问候 + [ThoughtComposer]
 * 文本）经本类接到 app 层的身体——就位链（[InteractionCoordinator]）、视线
 * （FaceDriver）、开口（session.speak）、长期记忆（memoryStore 回执）。
 *
 * 边界延续既有铁律：
 * - orchestrator 不感知场景表——动作词汇表由 [setVocabulary] 从场景交互点表
 *   投影注入，换场景 = 换注入，FSM 零改动；
 * - 执行互斥——自主动作走 [InteractionCoordinator.interact] 同一把串行锁，
 *   忙时拒绝 → FSM 退避重选；冻结让路走 [InteractionCoordinator.cancelChain]。
 *
 * 每拍（≈1s）采集世界快照（会话相位/VAD/技能占用/走动/接触定姿/外部动作
 * 时间戳）进仲裁，执行至多一组指令；问候走 speak（不进对话历史）+ 记忆回执。
 */
internal class AutonomyRig(
    private val scope: CoroutineScope,
    private val controller: AvatarController,
    private val sessionProvider: () -> AvatarSession?,
    private val coordinator: InteractionCoordinator,
    /** 记忆库直取缝（问候素材/思考记忆槽；null = 无记忆功能）。 */
    private val memoryStoreProvider: () -> MemoryStore? = { null },
    /** 关系快照缝（P3-1:纪念日问候/专属称呼）；null = 无关系数据。 */
    private val relationshipProvider: () -> com.neethu.orchestrator.memory.RelationshipSnapshot? = { null },
) {

    /** 思考气泡显示状态（overlay 收集）；null = 不显示。 */
    data class ThoughtBubble(val text: String, val startedAtMs: Long, val durationMs: Long)

    private val _bubble = MutableStateFlow<ThoughtBubble?>(null)
    val bubble: StateFlow<ThoughtBubble?> = _bubble.asStateFlow()

    /** 用户正在说话的探针（FreeSpeech/VAD/系统 ASR 活跃；MainActivity 回填）。 */
    var userSpeakingProbe: () -> Boolean = { false }

    private var director: AutonomyDirector? = null
    private var scheduler: GreetingScheduler = GreetingScheduler()
    private var composer: ThoughtComposer = ThoughtComposer(Lang.ZH)

    private var autonomyEnabled = false
    private var greetingEnabled = true
    private var bubbleEnabled = true

    private var loop: Job? = null
    private var eventsJob: Job? = null

    /** 自主指令在执行中（就位链 busy 或自主起步的走动未站定）。 */
    private var autonomyBusy = false

    /** 最近一次外部身体动作（LLM 工具/技能/标签分派）的时间戳。 */
    private var lastExternalActionMs = 0L

    /** 长停留尾段气泡已弹过标记（每次进入接触状态重置）。 */
    private var dwellBubbleShown = false

    // ── 装配（MainActivity 注入）────────────────────────────────────────

    /**
     * 注入动作词汇表与作息表（场景切换/语言变化时重跑）。词汇表从交互点表
     * 投影：sit→SITTING、lie→LYING、带 look 的 stand→LOOKING、其余→WANDER。
     */
    fun setVocabulary(anchors: List<InteractionAnchor>, schedule: AutonomySchedule, lang: Lang) {
        val spots = anchors.map { anchor ->
            AutonomySpot(
                id = anchor.id,
                kind = when {
                    anchor.kind == "sit" -> AutonomyStateKind.SITTING
                    anchor.kind == "lie" -> AutonomyStateKind.LYING
                    anchor.kind == "stand" && anchor.look != null -> AutonomyStateKind.LOOKING
                    else -> AutonomyStateKind.WANDER
                },
                x = anchor.x,
                z = anchor.z,
                look = anchor.look,
            )
        }
        val old = director
        director = AutonomyDirector(spots, schedule).also {
            it.enabled = old?.enabled ?: false
        }
        composer = ThoughtComposer(lang)
        Log.i(TAG, "vocabulary injected: ${spots.size} spots (${spots.groupingBy { it.kind }.eachCount()})")
    }

    /** 挂载/卸载（app 可见性 × 自主开关 × 会话就绪）。 */
    fun setMounted(enabled: Boolean, greeting: Boolean, bubble: Boolean) {
        autonomyEnabled = enabled
        greetingEnabled = greeting
        bubbleEnabled = bubble
        val d = director
        if (!enabled || d == null) {
            d?.enabled = false
            scheduler.unmount()
            loop?.cancel()
            loop = null
            eventsJob?.cancel()
            eventsJob = null
            _bubble.value = null
            return
        }
        if (d.enabled && loop?.isActive == true) return // 已挂载，只同步了子开关
        d.enabled = true
        d.mount()
        if (greeting) scheduler.mount()
        watchExternalActions()
        loop?.cancel()
        loop = scope.launch {
            while (isActive) {
                delay(TICK_PERIOD_MS)
                runCatching { tickOnce() }
                    .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "tick failed", it) }
            }
        }
        Log.i(TAG, "mounted (greeting=$greeting bubble=$bubble)")
    }

    /** 订阅会话事件 → 外部身体动作时间戳（仲裁「外部刚动过」的输入）。 */
    private fun watchExternalActions() {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            sessionProvider()?.events?.collect { event ->
                when (event) {
                    is AvatarEvent.ToolCallStarted,
                    is AvatarEvent.ActionStarted,
                    is AvatarEvent.WalkStarted,
                    is AvatarEvent.SkillEvent,
                    -> lastExternalActionMs = SystemClock.elapsedRealtime()

                    else -> Unit
                }
            }
        }
    }

    /** 用户刚说过话（问候冷却重置；所有发送入口经 pushUserLine 调用）。 */
    fun onUserSpoke() {
        scheduler.onUserActivity()
    }

    // ── 每拍 ────────────────────────────────────────────────────────────

    private fun tickOnce() {
        val d = director ?: return
        val session = sessionProvider()
        val now = SystemClock.elapsedRealtime()
        controller.getWalkState()?.let { d.reportPosition(it.x, it.z) }
        val world = buildWorld(session, now)
        val directives = d.tick(world)
        execute(directives, session)
        // 长停留尾段气泡（SITTING/LYING 剩余 <60s 时随机弹一次）
        maybeDwellTailThought(d, now)
        // 问候调度（挂载 + 开关 + 非 frozen 由 scheduler 的推迟门管）
        if (greetingEnabled) {
            val decision = scheduler.poll(world.phaseBusy, world.userSpeaking)
            if (decision == GreetingScheduler.Decision.Fire) fireGreeting(session)
        }
    }

    private fun buildWorld(session: AvatarSession?, now: Long): AutonomyWorld {
        val phase = session?.phase?.value ?: ConversationPhase.IDLE
        val walk = controller.getWalkState()
        val walking = walk != null && walk.phase != WalkPhase.IDLE
        if (autonomyBusy && !coordinator.isBusy && !walking) autonomyBusy = false
        val pose = controller.getContactPoseState()
        return AutonomyWorld(
            phaseBusy = phase != ConversationPhase.IDLE,
            userSpeaking = runCatching { userSpeakingProbe() }.getOrDefault(false),
            skillBusy = session?.skills?.bodyOccupied == true,
            walking = walking,
            autonomyInFlight = autonomyBusy,
            contactKind = when (pose?.kind) {
                ContactKind.SIT -> "sit"
                ContactKind.LIE -> "lie"
                else -> null
            },
            contactAnchorId = coordinator.lastAnchorId,
            recentExternalAction = now - lastExternalActionMs < EXTERNAL_ACTION_FRESH_MS,
        )
    }

    private fun execute(directives: List<AutonomyDirective>, session: AvatarSession?) {
        for (directive in directives) {
            when (directive) {
                is AutonomyDirective.GoTo -> {
                    autonomyBusy = true
                    val result = coordinator.interact(directive.spot.id)
                    if (!result.startsWith("interaction")) {
                        // 拒绝（就位链忙/锚点失效）→ FSM 退避重选
                        Log.i(TAG, "goTo '${directive.spot.id}' refused: $result")
                        autonomyBusy = false
                        director?.markDirectiveAborted()
                    }
                    // 状态转移气泡（走向书桌→「先把昨天那章看完…」）
                    if (bubbleEnabled) showThoughtForState(directive.state)
                }

                is AutonomyDirective.StandUp -> {
                    val result = coordinator.clearPose()
                    Log.i(TAG, "standUp: $result")
                }

                is AutonomyDirective.GazePoint -> session?.faceDriver?.let { fd ->
                    fd.setGazePoint(directive.x, directive.y, directive.z)
                    fd.setGazeMode(GazeMode.POINT)
                }

                is AutonomyDirective.GazeCamera -> session?.faceDriver?.setGazeMode(GazeMode.CAMERA)

                is AutonomyDirective.StopWalking -> {
                    // 复用就位链的让路语义：取消自主链 + 停步
                    coordinator.cancelChain()
                    autonomyBusy = false
                }
            }
        }
    }

    // ── 思考气泡 ────────────────────────────────────────────────────────

    /** 状态转移的内心独白（记忆槽概率由 composer 内部决定；记忆查询异步）。 */
    private fun showThoughtForState(state: AutonomyStateKind) {
        if (!bubbleEnabled) return
        dwellBubbleShown = false
        scope.launch {
            val text = composer.thoughtFor(state, currentHour(), recentMemoryFact()) ?: return@launch
            showBubble(text, THOUGHT_DURATION_MS)
        }
    }

    /** 长停留尾段（接触状态剩余 <60s）随机补一条，同状态只弹一次。 */
    private fun maybeDwellTailThought(d: AutonomyDirector, now: Long) {
        if (!bubbleEnabled || dwellBubbleShown) return
        if (d.state != AutonomyStateKind.SITTING && d.state != AutonomyStateKind.LYING) return
        val remaining = d.nextDecisionAtMs - nowMs()
        if (remaining in 1..DWELL_TAIL_WINDOW_MS && (now / 1000) % 3 == 0L) {
            dwellBubbleShown = true
            composer.thoughtFor(d.state, currentHour(), null)?.let {
                showBubble(it, THOUGHT_DURATION_MS)
            }
        }
    }


    /** 问候前奏的内心版（「要不要问问ta今天怎么样」），开口前 1.5s 弹。
     *  纪念日当天走纪念池（P3-1：纪念日触发主动问候）。 */
    private fun showGreetingThought(anniversaryYears: Int = 0) {
        if (!bubbleEnabled) return
        showBubble(composer.greetingThought(anniversaryYears = anniversaryYears), GREETING_THOUGHT_MS)
    }

    private fun showBubble(text: String, durationMs: Long) {
        _bubble.value = ThoughtBubble(text, nowMs(), durationMs)
        scope.launch {
            delay(durationMs)
            // 只清理自己这一条（后续气泡可能已顶替）
            _bubble.value?.let { if (it.text == text && it.startedAtMs + it.durationMs <= nowMs()) {
                _bubble.value = null
            } }
        }
        // 轻微 think 表情联动（内心独白的设定；不出声）
        sessionProvider()?.faceDriver?.applyEmotion(
            com.neethu.aiadapter.api.EmotionCue("think", 0.5f),
            holdMs = (durationMs + 1_000).coerceAtMost(6_000),
        )
    }

    // ── 问候 ────────────────────────────────────────────────────────────

    private fun fireGreeting(session: AvatarSession?) {
        if (session == null) return
        Log.i(TAG, "greeting fired")
        scope.launch {
            // ① 前奏思考气泡（「想你了才开口」）；纪念日带纪念语气
            val relationship = relationshipProvider()
            val anniversaryYears = relationship?.takeIf { it.isAnniversaryToday }
                ?.let { it.daysTogether / 365 } ?: 0
            showGreetingThought(anniversaryYears)
            delay(1_500)
            // ② 内容：纪念日 > 记忆事实 > 称呼 > 通用模板（≤2 句）
            val fact = if (anniversaryYears == 0) recentMemoryFact() else null
            val text = composer.greetingText(
                currentHour(), fact,
                nickname = relationship?.record?.nickname,
                anniversaryYears = anniversaryYears,
            ) ?: return@launch
            // ③ 播报：免 LLM 直说，带挥手微笑标签；不写对话历史（speak 语义）
            runCatching { session.speak("$GREETING_TAGS$text") }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "greeting speak failed", it) }
            // ④ 回执写入长期记忆（airi 教训：主动发言要有回执，下次对话可被引用）
            session.writeAutonomyReceipt("我主动问候了用户：「${text.take(60)}」")
            Log.i(TAG, "greeting spoken: $text")
        }
    }

    /**
     * 问候/思考的记忆素材：最近一条像样的事实（event/commitment/preference
     * 优先），截短防模板爆长。无记忆返回 null（模板走无记忆分支）。
     * Room 查询挂起调用——调用方都在协程里。
     */
    private suspend fun recentMemoryFact(): String? = runCatching {
        val store = memoryStoreProvider() ?: return null
        store.all()
            .filter { it.content.length >= 6 }
            .sortedByDescending { it.updatedAt }
            .let { list ->
                list.firstOrNull { it.category in MEMORY_FACT_CATEGORIES } ?: list.firstOrNull()
            }
            ?.content
            ?.take(MEMORY_FACT_MAX_CHARS)
    }.getOrNull()

    // ── 观测（ai_cmd autonomy_state / autonomy_log / autonomy）──────────

    fun stateLine(): String {
        val d = director
        if (d == null) return "autonomy=no vocabulary (no scene interactions)"
        return "${d.stateLine()} ${scheduler.stateLine()} mounted=$autonomyEnabled"
    }

    fun logDump(): String = director?.logDump() ?: "autonomy not mounted"

    /** ai_cmd autonomy on|off（无参 = 翻转）：运行时开关（不重置状态机）。 */
    fun toggle(arg: String?): String {
        val target = when (arg?.lowercase()) {
            "on", "true" -> true
            "off", "false" -> false
            null -> !autonomyEnabled
            else -> return "autonomy: unknown arg '$arg' (on|off)"
        }
        setMounted(target, greetingEnabled, bubbleEnabled)
        return "autonomy ${if (target) "on" else "off"} — ${stateLine()}"
    }

    /** ai_cmd autonomy_greet：立刻问候（测试入口，绕开间隔/安静门，日上限仍守）。 */
    fun forceGreet(): String {
        if (!autonomyEnabled) return "autonomy is off — run `autonomy on` first"
        scheduler.forceDue()
        return "greeting scheduled now — ${scheduler.stateLine()}"
    }

    /** ai_cmd autonomy_think：立刻弹一条思考气泡（渲染验收入口）。 */
    suspend fun forceThink(): String {
        val d = director ?: return "autonomy not mounted"
        val text = composer.thoughtFor(d.state, currentHour(), recentMemoryFact())
            ?: composer.greetingThought()
        showBubble(text, THOUGHT_DURATION_MS)
        return "bubble: $text"
    }

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "AutonomyRig"

        /** 决策/问候轮询节拍。 */
        private const val TICK_PERIOD_MS = 1_000L

        /** 外部动作「刚发生」窗口。 */
        private const val EXTERNAL_ACTION_FRESH_MS = 5_000L

        /** 思考气泡显示时长。 */
        private const val THOUGHT_DURATION_MS = 6_000L

        /** 问候前奏气泡时长（开口前 1.5s + 余量）。 */
        private const val GREETING_THOUGHT_MS = 3_000L

        /** 长停留尾段气泡的剩余窗口。 */
        private const val DWELL_TAIL_WINDOW_MS = 60_000L

        /** 问候正文的表情/动作标签（挥手微笑说出来）。 */
        private const val GREETING_TAGS = "<emo:joy><act:wave>"

        /** 记忆素材的优先分类（问候/思考提「最近的事」最自然）。 */
        private val MEMORY_FACT_CATEGORIES = setOf(
            MemoryCategories.EVENT,
            MemoryCategories.COMMITMENT,
            MemoryCategories.PREFERENCE,
        )

        /** 记忆素材截短（模板 ≤2 句的总预算）。 */
        private const val MEMORY_FACT_MAX_CHARS = 40
    }
}
