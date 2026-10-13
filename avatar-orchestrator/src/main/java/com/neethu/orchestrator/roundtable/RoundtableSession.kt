package com.neethu.orchestrator.roundtable

import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.session.ConversationPhase
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 话题状态：无话题 / 圆桌进行中 / 交回用户（等插话再继续）。 */
enum class RoundtableState { IDLE, ACTIVE, WAITING_USER }

/**
 * 舞台合流事件：哪个槽位的会话发生了什么（docs/multi-avatar-feasibility.md
 * P1「AvatarEvent 带 slotId」的落地形态——不侵入既有事件类型，舞台层包一层数据类，
 * 字幕分道/说话人高亮/调试通道都消费这里）。
 */
data class StageEvent(val slotId: String, val event: AvatarEvent)

/**
 * 倾听注视的能力缝（§4.7「倾听感的最大来源」）：发言者的头部世界坐标 +
 * 把某个槽位的视线指过去/指回镜头。app 用舞台 [com.neethu.corelib.stage.AvatarHandle]
 * 实现（lookAt/lookAtCamera/headWorldPosition 都是现成 API）；JVM 单测用 fake。
 */
interface StageGazeRelay {
    /** 槽位头部世界坐标；模型未就绪返回 null（该轮注视降级为不动）。 */
    fun headPosition(slotId: String): FloatArray?

    /** 槽位看向世界点（倾听者看发言者）。 */
    fun lookAtPoint(slotId: String, x: Float, y: Float, z: Float)

    /** 槽位看镜头（发言结束回看用户）。 */
    fun lookAtCamera(slotId: String)
}

/**
 * 多会话编排门面（一屏多虚拟人，docs/multi-avatar-feasibility.md §4.2/§4.6）：
 * N 个各自独立（人设/服务商/音色/上下文/记忆分片）的 [AvatarSession] + 一条
 * 「这句话给谁」的路由 + 一个发言令牌 + 倾听注视接力；P2 在此之上加圆桌对话
 * （§4.3 导演制）：[startRoundtable] 开题后由 [DirectorPolicy]（规则/LLM 两档
 * 热切换）逐轮仲裁发言权，虚拟人互聊的消息以 `【说话人】:文本` 形态走 user
 * 通道进各自历史，防失控三件套（轮数上限/复读检测/END 收场）是两档导演共同
 * 的硬守卫，不依赖模型自觉。
 *
 * 消息进历史的形态（§4.4）：被授予者经 `send()` 追加本轮新发言（前缀行），
 * 其余槽位经 `injectUserMessage` 同步收听——任何人被授予发言权时上下文已经
 * 齐了；自己的话在自己的历史里永远是 assistant 原文，不重复注入。
 *
 * 会话归属与生命周期（§4.2 硬要求）：每个 slot 的 session 由集成方装在
 * **各自的 scope** 上（app 一 slot 一个 SupervisorJob 子 scope），本类不持有
 * 也不取消它们——只挂事件收集；配置变化重建某个 slot 后用 [updateSession]
 * 重挂（先 cancel 旧收集，坑 41 僵尸 collector 的教训）。
 *
 * TTS 全局预算（§4.2）：本类不共享 Semaphore——集成方建 session 时按
 * [perSlotTtsBudget] 均分注入 `Options.ttsMaxConcurrent`，总并发不超预算。
 */
class RoundtableSession(
    private val scope: CoroutineScope,
    slots: List<Pair<StageSlotSpec, AvatarSession>>,
    /** null = 不做注视接力（headless/无舞台形态照常可用）。 */
    private val gaze: StageGazeRelay? = null,
    /** 导演策略（P2 圆桌）：默认规则档；[setDirector] 运行时热切换。 */
    director: DirectorPolicy = DirectorPolicy.Rule(),
) {

    private val specs: List<StageSlotSpec> = slots.map { it.first }
    private val router = InputRouter(specs)
    private val sessionById = LinkedHashMap<String, AvatarSession>()
    private val collectorJobs = HashMap<String, Job>()

    private val _events = MutableSharedFlow<StageEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** 合流事件（按 slot 分道消费）。 */
    val events: SharedFlow<StageEvent> = _events.asSharedFlow()

    init {
        slots.forEach { (spec, session) -> attach(spec.id, session) }
    }

    /**
     * 当前发言令牌持有者（最近一条 [AvatarEvent.SentenceStarted] 的槽位）；
     * 回合完成/播放被打断时释放。打断路由与调试通道的数据源。
     */
    @Volatile
    var currentSpeakerId: String? = null
        private set

    /** 最后一个与用户交互的槽位（默认路由目标，UI 高亮同步用，可写以跨重建保留）。 */
    @Volatile
    var lastAddressedId: String? = null

    // ── P2 圆桌状态 ───────────────────────────────────────────────────────

    private val _state = MutableStateFlow(RoundtableState.IDLE)

    /** 话题状态（StagePanel 高亮/调试通道）。 */
    val state: StateFlow<RoundtableState> = _state.asStateFlow()

    /** 导演策略（[setDirector] 热切换；快照/面板显示用）。 */
    var directorPolicy: DirectorPolicy = director
        private set

    private var topic: String = ""
    /** 已完成的虚拟人发言轮数（含失败轮）。 */
    private var round = 0
    /** [lastSpokenSlotId] 连续发言轮数（规则档连续上限的输入）。 */
    private var consecutiveCount = 0
    /** 连续复读 strikes（≥[MAX_REPEAT_STRIKES] 收场，防互捧死循环）。 */
    private var repeatStrikes = 0
    private var lastSpokenSlotId: String? = null
    /** 收场白说话人（开题时的最后对话对象）。 */
    private var initiatorId: String? = null

    /** 已授予发言权、等待其回合完成的槽位；null = 导演空闲。 */
    private var grantedSlotId: String? = null
    /** 用户在发言进行中插的话，等本轮结束后由导演安排回应。 */
    @Volatile
    private var pendingUserLine: String? = null

    /** 授予回合内逐句累积的干净文本（TurnCompleted 时成为 transcript 一行）。 */
    private val turnText = HashMap<String, StringBuilder>()
    /** 圆桌 transcript（含用户行；LLM 档导演的上下文与复读检测的语料）。 */
    private val transcript = ArrayDeque<RoundtableLine>()
    private var directorJob: Job? = null

    private val _directorLog = MutableStateFlow<List<String>>(emptyList())

    /** 导演决策日志（最近 N 条，StagePanel/STAGE_CMD 消费）。 */
    val directorLog: StateFlow<List<String>> = _directorLog.asStateFlow()

    /**
     * 用户一句话进舞台：无话题=P1 路由语义（点名/默认 → 目标接话）；
     * 话题进行中=圆桌插话（§4.6 规则 3）——点名直授发言权，否则广播进
     * transcript 由导演决定谁接（正在发言时先排队）。
     *
     * @return 受理的槽位 id；null = 舞台为空。话题模式下是「路由受理」的
     *   对象（无点名的广播插话返回 null，但插话已受理——接话人由导演安排，
     *   见 [directorLog]）。
     */
    fun sendFromUser(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (_state.value == RoundtableState.IDLE) {
            val decision = router.route(trimmed, lastAddressedId)
            val target = decision as? RoutingDecision.Target ?: return null
            lastAddressedId = target.slotId
            sessionById[target.slotId]?.send(target.cleanedText)
            return target.slotId
        }
        return roundtableInterject(trimmed)
    }

    /** 直接对某个槽位说话（开场白/舞台指令），不走路由、不进历史。 */
    suspend fun say(slotId: String, text: String): Boolean {
        val session = sessionById[slotId] ?: return false
        lastAddressedId = slotId
        session.speak(text)
        return true
    }

    /**
     * 打断（§4.5）：命中**全部非 IDLE** 的会话——正在说的（发言令牌）与正在
     * 想的（THINKING，还没出声也要掐断）。各自的三层打断链路（LLM 流+在途
     * TTS+播放静音）由 AvatarSession 自理；空闲会话不碰（打断一个 IDLE 会话
     * 无意义且会误伤未来回合）。P2：在途的导演裁决与授予一并同步清空（话题
     * 暂停，等下一次插话重新走导演；迟到的 PlaybackInterrupted 见
     * onDirectorEvent 的 phase 检查）。
     */
    fun interrupt() {
        directorJob?.cancel()
        directorJob = null
        if (grantedSlotId != null) {
            grantedSlotId = null
            turnText.clear()
            if (_state.value == RoundtableState.ACTIVE) directorLogAdd("打断：话题暂停")
        }
        sessionById.forEach { (_, session) ->
            if (session.phase.value != ConversationPhase.IDLE) session.interrupt()
        }
    }

    // ── P2 圆桌话题 ───────────────────────────────────────────────────────

    /**
     * 开题（§4.3）：话题文本广播进全部槽位历史（【用户】前缀），导演授予
     * 首个发言权（规则档：最后对话对象优先）。之后每轮：发言完成 →
     * fanout → 导演裁决下一个开口者 → 前缀槽注入「轮到你/保持倾听」。
     *
     * @return false = 话题为空或台上无会话。
     */
    fun startRoundtable(topicText: String): Boolean {
        val t = topicText.trim()
        if (t.isEmpty() || sessionById.isEmpty()) return false
        // 打断上一话题的在途发言/裁决（旧回合不能盖住新话题；其迟到的
        // PlaybackInterrupted 由 phase 检查忽略——见 onDirectorEvent）
        interrupt()
        topic = t
        round = 0
        consecutiveCount = 0
        repeatStrikes = 0
        lastSpokenSlotId = null
        grantedSlotId = null
        pendingUserLine = null
        initiatorId = lastAddressedId
        turnText.clear()
        transcript.clear()
        _state.value = RoundtableState.ACTIVE
        val line = RoundtableLine(null, USER_NAME, t)
        transcriptAdd(line)
        directorLogAdd("● 开题：$t")
        decideAndGrant(listOf(line))
        return true
    }

    /** 停题（用户主动）：打断全部在途发言/裁决，状态回 IDLE，不播收场白。 */
    fun stopRoundtable() {
        if (_state.value == RoundtableState.IDLE && grantedSlotId == null) return
        interrupt()
        topic = ""
        grantedSlotId = null
        pendingUserLine = null
        turnText.clear()
        setAllRoundtableLines(null)
        _state.value = RoundtableState.IDLE
        directorLogAdd("■ 停题（用户）")
    }

    /** 导演热切换（§4.3 同一接口两档）：在途裁决取消，下一轮生效。 */
    fun setDirector(policy: DirectorPolicy) {
        directorJob?.cancel()
        directorJob = null
        directorPolicy = policy
        directorLogAdd("导演切换 → ${policy.label}")
    }

    /** 槽位 id → 当前会话（调试通道/进阶接线用）。 */
    fun sessionOf(slotId: String): AvatarSession? = sessionById[slotId]

    /** 槽位表（路由词表同源）。 */
    fun slotSpecs(): List<StageSlotSpec> = specs.toList()

    /**
     * 配置变化重建某个槽位的会话后重挂事件收集：先 cancel 旧收集者再挂新的
     * （共享 scope 下不 cancel 就是坑 41 的僵尸 collector——旧实例还在收事件）。
     * 旧会话的 close 由集成方（facade.configure 的重建语义）负责。
     */
    fun updateSession(slotId: String, session: AvatarSession) {
        if (sessionById[slotId] == null) return
        attach(slotId, session)
    }

    /** 单行诊断快照（STAGE_CMD chat_state 的正本）。 */
    fun snapshot(): String = buildString {
        append("speaker=${currentSpeakerId ?: "-"}")
        append(" lastAddressed=${lastAddressedId ?: "-"}")
        if (_state.value != RoundtableState.IDLE) {
            append(" topic[${_state.value.name.lowercase()} r=$round/${directorPolicy.maxRounds}")
            append(" director=${directorPolicy.label} pending=${pendingUserLine != null}]")
            append("=\"${topic.take(12)}\"")
        }
        sessionById.forEach { (id, session) ->
            append(" $id=${session.phase.value.name.lowercase()}")
        }
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun attach(slotId: String, session: AvatarSession) {
        sessionById[slotId] = session
        collectorJobs.remove(slotId)?.cancel()
        collectorJobs[slotId] = scope.launch {
            session.events.collect { onEvent(slotId, it) }
        }
    }

    private fun onEvent(slotId: String, event: AvatarEvent) {
        _events.tryEmit(StageEvent(slotId, event))
        // P2：授予回合的句子累积（TurnCompleted 时拼成 transcript 一行）
        if (event is AvatarEvent.SentenceQueued && slotId == grantedSlotId) {
            turnText.getOrPut(slotId) { StringBuilder() }.append(event.text)
        }
        val relay = gaze ?: run { onDirectorEvent(slotId, event); return }
        when (event) {
            is AvatarEvent.SentenceStarted -> {
                currentSpeakerId = slotId
                // 倾听态：其余人看发言者的头（头坐标拿不到=模型未就绪，本轮跳过）
                val head = relay.headPosition(slotId) ?: run { onDirectorEvent(slotId, event); return }
                specs.forEach { spec ->
                    if (spec.id != slotId) relay.lookAtPoint(spec.id, head[0], head[1], head[2])
                }
            }

            // 回合收尾/播放被打断 → 全员回看镜头。SentenceEnded 不触发回看：
            // 一回合多句之间听众不该来回甩头。
            is AvatarEvent.TurnCompleted,
            is AvatarEvent.TurnFailed,
            is AvatarEvent.PlaybackInterrupted,
            -> {
                currentSpeakerId = null
                specs.forEach { spec -> relay.lookAtCamera(spec.id) }
            }

            else -> Unit
        }
        onDirectorEvent(slotId, event)
    }

    /** 圆桌导演的事件侧（令牌状态机）：授予回合的完成/失败/被打断。 */
    private fun onDirectorEvent(slotId: String, event: AvatarEvent) {
        when (event) {
            is AvatarEvent.TurnCompleted -> {
                if (slotId != grantedSlotId) return
                grantedSlotId = null
                val reply = turnText.remove(slotId)?.toString()?.trim().orEmpty()
                if (_state.value == RoundtableState.ACTIVE) onGrantedTurnCompleted(slotId, reply)
            }

            is AvatarEvent.TurnFailed -> {
                if (slotId != grantedSlotId) return
                grantedSlotId = null
                turnText.remove(slotId)
                if (_state.value != RoundtableState.ACTIVE) return
                // 失败轮计入轮数；发言内容没有进任何历史（granted 的 send 在
                // 请求前已 append，其他槽位本就靠注入同步——本批无新内容），
                // 导演照常接力：下一位以「继续」提示开轮。
                round++
                consecutiveCount = 1
                lastSpokenSlotId = slotId
                directorLogAdd("$slotId 回合失败，接力")
                decideAndGrant(emptyList())
            }

            is AvatarEvent.PlaybackInterrupted -> {
                if (slotId != grantedSlotId) return
                // 迟到的旧回合打断（开题/换题后同槽位重新被授予）：会话已经在
                // 跑新回合（THINKING/SPEAKING）则不是本轮被打断，忽略
                if (sessionById[slotId]?.phase?.value != ConversationPhase.IDLE) return
                grantedSlotId = null
                turnText.remove(slotId)
                if (_state.value == RoundtableState.ACTIVE) directorLogAdd("打断：话题暂停")
            }

            else -> Unit
        }
    }

    /**
     * 圆桌插话（话题进行中的 sendFromUser 分支）。点名（@/称呼）直授被点名
     * 者；无点名 = 广播进 transcript 由导演决定谁接（§4.6 规则 3）——此时
     * 不依赖 lastAddressed（没人被点过名也能插话，导演兜底）。
     */
    private fun roundtableInterject(text: String): String? {
        val target = router.route(text, lastAddressedId) as? RoutingDecision.Target
        if (target != null) lastAddressedId = target.slotId
        val cleaned = target?.cleanedText ?: text
        if (grantedSlotId != null) {
            // 正在发言/思考：排队等本轮结束（上一条未回应的插话被覆盖，日志留痕）
            if (pendingUserLine != null) directorLogAdd("插话覆盖：上一条未及回应")
            pendingUserLine = cleaned
            directorLogAdd("用户插话排队：${cleaned.take(16)}")
            return target?.slotId
        }
        val line = RoundtableLine(null, USER_NAME, cleaned)
        transcriptAdd(line)
        if (target?.explicit == true) {
            // 点名优先（§4.6 规则 1）：直接授予被点名者，导演旁路
            grantTurn(target.slotId, listOf(line), FloorDecision.Grant(target.slotId, "用户点名"))
            return target.slotId
        }
        decideAndGrant(listOf(line))
        return target?.slotId
    }

    /** 导演裁决入口（异步：LLM 档一次轻量网络调用；规则档同协程即刻生效）。 */
    private fun decideAndGrant(batch: List<RoundtableLine>) {
        directorJob?.cancel()
        directorJob = scope.launch {
            val decision = runCatching { directorFor(directorPolicy).decide(context()) }
                .getOrElse { FloorDecision.End("导演异常：${it.message ?: "unknown"}") }
            when (decision) {
                is FloorDecision.Grant -> grantTurn(decision.slotId, batch, decision)
                is FloorDecision.ToUser -> {
                    fanout(batch)
                    pendingUserLine = null
                    grantedSlotId = null
                    setAllRoundtableLines(null)
                    _state.value = RoundtableState.WAITING_USER
                    directorLogAdd("→ 交回用户（等插话）")
                }

                is FloorDecision.End -> endTopic(decision.reason, batch)
            }
        }
    }

    private fun context() = RoundtableContext(
        topic = topic,
        round = round,
        maxRounds = directorPolicy.maxRounds,
        slots = specs,
        lastSpeakerId = lastSpokenSlotId,
        lastReply = transcript.lastOrNull { it.slotId != null }?.text.orEmpty(),
        recentLines = transcript.toList(),
        consecutiveCount = consecutiveCount,
        pendingUserLine = pendingUserLine,
        lastAddressedId = lastAddressedId,
    )

    /**
     * 授予发言权：批内新发言 fanout（受命者经 send 追加并生成，其余槽位注入
     * 收听），逐轮前缀槽写「轮到你/保持倾听」，受命者开轮。
     */
    private fun grantTurn(slotId: String, batch: List<RoundtableLine>, decision: FloorDecision.Grant) {
        val session = sessionById[slotId]
        if (session == null) {
            endTopic("槽位 $slotId 不在线", batch)
            return
        }
        val speakable = batch.filter { it.slotId != slotId }
        if (speakable.isEmpty() && lastSpokenSlotId != slotId) {
            // 授予一个没有任何新输入的新槽位（无话可接）：收场防空转
            endTopic("无新内容可授予", batch)
            return
        }
        fanout(batch, except = slotId)
        grantedSlotId = slotId
        turnText.remove(slotId)
        // 逐轮发言权前缀槽（§4.4）：受命者「轮到你」，其余人「保持倾听」
        val name = nameOf(slotId)
        session.roundtableLine = GRANTED_LINE
        specs.forEach { spec ->
            if (spec.id != slotId) {
                sessionById[spec.id]?.roundtableLine = LISTENING_LINE(name)
            }
        }
        _state.value = RoundtableState.ACTIVE
        session.send(speakable.joinToString("\n") { it.prefixed() }.ifEmpty { CONTINUE_NUDGE })
        directorLogAdd("→ $slotId($name)：${decision.reason}")
    }

    /**
     * 授予回合完成：发言进 transcript → 复读检测（硬守卫）→ 组下一批
     * （发言 + 未回应的插话）→ 轮数上限（硬守卫）/ 导演裁决。
     */
    private fun onGrantedTurnCompleted(slotId: String, reply: String) {
        round++
        consecutiveCount = if (slotId == lastSpokenSlotId) consecutiveCount + 1 else 1
        lastSpokenSlotId = slotId
        if (reply.isNotEmpty()) {
            // 复读判定用「加入本条之前」的 avatar 行
            repeatStrikes =
                if (DialogueGuards.isRepeat(reply, transcript.filter { it.slotId != null }.map { it.text })) {
                    repeatStrikes + 1
                } else {
                    0
                }
            transcriptAdd(RoundtableLine(slotId, nameOf(slotId), reply))
        }
        val batch = buildList {
            if (reply.isNotEmpty()) add(RoundtableLine(slotId, nameOf(slotId), reply))
            pendingUserLine?.takeIf { it.isNotBlank() }?.let { p ->
                add(RoundtableLine(null, USER_NAME, p))
                transcriptAdd(RoundtableLine(null, USER_NAME, p))
            }
        }
        pendingUserLine = null
        when {
            round >= directorPolicy.maxRounds -> endTopic("已达 ${directorPolicy.maxRounds} 轮上限", batch)
            repeatStrikes >= MAX_REPEAT_STRIKES -> endTopic("连续复读 ×$repeatStrikes", batch)
            else -> decideAndGrant(batch)
        }
    }

    /**
     * fanout（§4.4）：批内每条发言以 `【用户】/【名字】` 前缀注入除受命者
     * （send 已追加）与说话者本人（自己的话在自己的历史里已是 assistant）
     * 之外的全部槽位历史。
     */
    private fun fanout(batch: List<RoundtableLine>, except: String? = null) {
        if (batch.isEmpty()) return
        sessionById.forEach { (slotId, session) ->
            if (slotId == except) return@forEach
            val entries = batch.filter { it.slotId != slotId }
            if (entries.isNotEmpty()) {
                session.injectUserMessage(entries.joinToString("\n") { it.prefixed() })
            }
        }
    }

    private fun endTopic(reason: String, batch: List<RoundtableLine>) {
        fanout(batch)
        topic = ""
        pendingUserLine = null
        grantedSlotId = null
        setAllRoundtableLines(null)
        _state.value = RoundtableState.IDLE
        directorLogAdd("■ 收场：$reason")
        // 收场白（§4.3 END→收场白）：话题发起者说，不进历史（speak 语义）
        val closer = initiatorId?.let { sessionById[it] }
            ?: lastSpokenSlotId?.let { sessionById[it] }
            ?: sessionById.values.firstOrNull()
        if (closer == null) {
            Log.w(TAG, "closing skipped: no session available")
            return
        }
        Log.i(TAG, "closing speak via ${initiatorId ?: lastSpokenSlotId} length=${CLOSING_LINE.length}")
        scope.launch {
            runCatching { closer.speak(CLOSING_LINE) }
                .onSuccess { Log.i(TAG, "closing speak done") }
                .onFailure { Log.w(TAG, "closing speak failed: ${it.message}") }
        }
    }

    private fun directorFor(policy: DirectorPolicy): ConversationDirector = when (policy) {
        is DirectorPolicy.Rule -> RuleBasedDirector(policy)
        is DirectorPolicy.Llm -> LlmDirector(policy.llm, policy.config, policy)
    }

    private fun nameOf(slotId: String): String =
        specs.firstOrNull { it.id == slotId }?.displayName ?: slotId

    private fun transcriptAdd(line: RoundtableLine) {
        transcript.addLast(line)
        while (transcript.size > TRANSCRIPT_MAX) transcript.removeFirst()
    }

    private fun setAllRoundtableLines(value: String?) {
        sessionById.forEach { (_, session) -> session.roundtableLine = value }
    }

    private fun directorLogAdd(entry: String) {
        _directorLog.value = (_directorLog.value + entry).takeLast(DIRECTOR_LOG_MAX)
    }

    companion object {
        private const val TAG = "Roundtable"
        private const val USER_NAME = "用户"

        /** transcript 环上限（LLM 档导演输入截窗之前的第一道闸）。 */
        private const val TRANSCRIPT_MAX = 24

        /** 导演日志上限（StagePanel/调试通道展示最近若干条）。 */
        private const val DIRECTOR_LOG_MAX = 8

        /** 连续复读 strikes 上限（第 N 次命中即收场，防互捧死循环）。 */
        private const val MAX_REPEAT_STRIKES = 2

        /** 受命者的逐轮前缀槽（§4.4：逐轮变化、不进 store、不碰钉住协议块）。 */
        private const val GRANTED_LINE =
            "【圆桌】现在轮到你发言了：请接着当前话题用一两句口语回应，不要替别人说话。"

        private fun LISTENING_LINE(speakerName: String) =
            "【圆桌】现在轮到 $speakerName 发言，请保持倾听，不要接话。"

        /** 接力提示（上一轮失败/无新内容时的最小 user 输入）。 */
        private const val CONTINUE_NUDGE = "（请接着当前话题继续）"

        /** 规则档收场白（话题发起者 speak，不进历史）。 */
        private const val CLOSING_LINE = "好啦，这个话题我们先聊到这里，感谢大家～"

        /**
         * 全局 TTS 并发预算的均分（§4.2）：每个 session 各自 Semaphore(N) 的
         * 形态下，slotCount 等分才能保证总在途请求不超预算——2 slot × 2 = 4，
         * 与单虚拟人默认一致。预算小于槽数时每槽保底 1（总预算被 exceeded，
         * 但 2~3 人 × 1 并发仍是可听的体验）。
         */
        fun perSlotTtsBudget(globalBudget: Int, slotCount: Int): Int =
            if (slotCount <= 0) globalBudget else maxOf(1, globalBudget / slotCount)
    }
}
