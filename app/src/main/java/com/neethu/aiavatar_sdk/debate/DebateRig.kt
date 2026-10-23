package com.neethu.aiavatar_sdk.debate

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.neethu.aiadapter.openai.OpenAiCompatibleLlmAdapter
import com.neethu.corelib.Lang
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.neethu.aiavatar_sdk.AiChatPrefs
import com.neethu.corelib.AvatarState
import com.neethu.corelib.VrmaFinished
import com.neethu.corelib.accessory.AccessorySource
import com.neethu.corelib.accessory.AccessorySpec
import com.neethu.corelib.stage.AvatarHandle
import com.neethu.corelib.stage.AvatarSpec
import com.neethu.corelib.stage.AvatarSpawn
import com.neethu.corelib.stage.AvatarStage
import com.neethu.corelib.stage.StageProp
import com.neethu.corelib.stage.StagePropSpec
import com.neethu.orchestrator.debate.ActiveTurn
import com.neethu.orchestrator.debate.CoroutineDebateTimer
import com.neethu.orchestrator.debate.DebateClock
import com.neethu.orchestrator.debate.DebateConfigDto
import com.neethu.orchestrator.debate.DebateEngine
import com.neethu.orchestrator.debate.DebateEvent
import com.neethu.orchestrator.debate.DebateIds
import com.neethu.orchestrator.debate.DebatePhase
import com.neethu.orchestrator.debate.DebatePostureDirector
import com.neethu.orchestrator.debate.DebateSpeechPlayer
import com.neethu.orchestrator.debate.DebateTeam
import com.neethu.orchestrator.debate.PostureAction
import com.neethu.orchestrator.debate.PostureCommand
import com.neethu.orchestrator.debate.TurnKind
import com.neethu.orchestrator.facade.AIAvatarSdk
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.session.AvatarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 辩论舞台装配（docs/debate-feasibility.md §4 DebateRig 槽位）：九个业务 ID
 * 显式映射到 [AvatarHandle]（不依赖舞台自动 A/B/C id）、辩论布局的座位/椅子/
 * 持麦、每参与者独立 TTS 会话（fixed 固定稿 / ai 实时短句）、[DebateEngine]
 * 的姿态/播放两缝、镜头导演。
 *
 * 纪律：不调 RoundtableSession、不启用圆桌走位；辩论模式下 LLM 相机/走动
 * 标签在配置层禁用（doc §6：执行入口过滤，不靠提示词自觉）。
 */
class DebateRig(
    private val appContext: Context,
    val stage: AvatarStage,
    private val globalPrefs: () -> AiChatPrefs,
    externalScope: CoroutineScope? = null,
) {
    private val TAG = "DebateEngine"

    val rigScope: CoroutineScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _matchId = MutableStateFlow<String?>(null)
    val matchId: StateFlow<String?> get() = _matchId

    inner class Part(val id: String, val dto: DebateConfigDto.ParticipantDto) {
        var handle: AvatarHandle? = null
        var chat: ParticipantChat? = null
        var chair: StageProp? = null
        val posture = PartPosture()
    }

    val parts = linkedMapOf<String, Part>()
    var config: DebateConfigDto? = null
        private set
    var layout: DebateLayoutDto? = null
        private set

    // ── 引擎与两缝 ──────────────────────────────────────────────────────

    private val monotonicClock = DebateClock { SystemClock.elapsedRealtime() }
    private val speechPlayer = RigSpeechPlayer()
    private val turnTexts = mutableMapOf<Pair<Long, Long>, String>()
    private var generationConnection: Pair<String, String>? = null
    private var generationAdapter: OpenAiCompatibleLlmAdapter? = null

    private fun generationAdapter(chat: AIAvatarSdk.ChatConfig): OpenAiCompatibleLlmAdapter {
        val connection = chat.llmBaseUrl to chat.apiKey
        if (generationConnection != connection || generationAdapter == null) {
            generationConnection = connection
            generationAdapter = OpenAiCompatibleLlmAdapter(chat.llmBaseUrl, chat.apiKey)
        }
        return checkNotNull(generationAdapter)
    }
    private val postureDirector = RigPostureDirector()

    val engine: DebateEngine = DebateEngine(
        clock = monotonicClock,
        timer = CoroutineDebateTimer(rigScope),
        speechPlayer = speechPlayer,
        postureDirector = postureDirector,
    )

    val camera = DebateCameraDirector(stage, rigScope)

    /** 当前字幕（发言人 id + 正在播的句子）。 */
    private val _subtitle = MutableStateFlow<Pair<String, String>?>(null)
    val subtitle: StateFlow<Pair<String, String>?> get() = _subtitle

    /** 九人装载状态快照（准备页）。 */
    data class PartStatus(val id: String, val name: String, val loaded: Boolean, val ready: Boolean, val failed: Boolean)

    private val _loadStatus = MutableStateFlow<List<PartStatus>>(emptyList())
    val loadStatus: StateFlow<List<PartStatus>> get() = _loadStatus

    // ── 装配与重建 ──────────────────────────────────────────────────────

    /**
     * 按配置整体重建舞台（doc §9：READY/PAUSED 时整体更新）。挂起直到九人
     * Ready / 失败；返回是否全部就绪。
     */
    suspend fun rebuild(dto: DebateConfigDto, layoutDto: DebateLayoutDto): Boolean {
        teardownStage()
        config = dto
        layout = layoutDto
        _matchId.value = dto.matchId

        stage.loadScene(layoutDto.scene.asset.ifBlank { "scene/home.glb" })
        engine.load(dto)

        val seats = layoutDto.seats
        var fallbackIndex = 0
        // loadAvatar 会立即启动加载；在创建下一个演员前等待，限制真正的在途资源。
        for (p in dto.participants) {
            val part = Part(p.id, p)
            parts[p.id] = part
            val seat = seats[p.id] ?: run {
                Log.w(TAG, "布局缺座位 ${p.id}，用兜底排布")
                val i = fallbackIndex++
                DebateLayoutDto.Seat(x = (i - 4) * 0.55f, z = -3.6f)
            }
            val model = resolveModel(p.model)
            // AvatarSpawn 是相对 unit-cube 中心 (0, 0, -4) 的偏移；
            // 布局的 z 是世界坐标，y 沿用 home 地面（归一化脚底在 -1）。
            val spawn = AvatarSpawn(seat.x, seat.y, seat.z + 4f, seat.yawDeg)
            val spec = when {
                model.startsWith("asset:") ->
                    AvatarSpec(modelAssetPath = model.removePrefix("asset:"), spawn = spawn)
                model.startsWith("file:") ->
                    AvatarSpec(modelFilePath = model.removePrefix("file:"), spawn = spawn)
                else -> {
                    Log.e(TAG, "非法模型来源 ${p.model}（${p.id}）")
                    continue
                }
            }
            val handle = stage.loadAvatar(spec)
            part.handle = handle
            publishLoadStatus()
            handle.state.first { it is AvatarState.Ready || it is AvatarState.Error }
            publishLoadStatus()
        }
        publishLoadStatus()

        val failed = parts.values.filter { it.handle?.state?.value !is AvatarState.Ready }
        if (failed.isNotEmpty()) {
            Log.e(TAG, "模型加载失败: ${failed.map { it.id }}")
            publishLoadStatus()
            return false
        }

        placeChairs(layoutDto)
        attachMics(layoutDto)
        seatEveryone(layoutDto)
        buildChats(dto)
        bindCamera()
        camera.lock(DebateCameraDirector.ShotSpec(
            DebateCameraDirector.ShotSpec.Kind.WIDE, targets = DebateIds.ALL,
        ), holdMs = 1L)
        publishLoadStatus()
        Log.i(TAG, "九人装配完成: ${parts.keys.joinToString()}")
        return true
    }

    private fun publishLoadStatus() {
        _loadStatus.value = config?.participants?.map { p ->
            val part = parts[p.id]
            val state = part?.handle?.state?.value
            PartStatus(
                id = p.id,
                name = p.name,
                loaded = part?.handle != null,
                ready = state is AvatarState.Ready,
                failed = state is AvatarState.Error,
            )
        } ?: emptyList()
    }

    /** file: 约定相对 filesDir/debate/（doc §9）；绝对路径原样。 */
    private fun resolveModel(model: String): String {
        if (!model.startsWith("file:")) return model
        val raw = model.removePrefix("file:")
        if (raw.startsWith("/")) return model
        return "file:${DebateFiles.root(appContext).resolve(raw).absolutePath}"
    }

    private fun placeChairs(layoutDto: DebateLayoutDto) {
        if (!layoutDto.chair.enabled) return
        val chair = layoutDto.chair
        for ((id, seat) in layoutDto.seats) {
            val prop = stage.loadProp(
                StagePropSpec(
                    assetPath = chair.asset,
                    x = seat.x,
                    y = seat.y + chair.offsetY,
                    z = seat.z + chair.offsetZ,
                    yawDeg = seat.yawDeg + chair.yawDeg,
                    scale = chair.scale,
                )
            ) ?: continue
            parts[id]?.chair = prop
        }
    }

    private fun attachMics(layoutDto: DebateLayoutDto) {
        val mic = layoutDto.mic
        for (part in parts.values) {
            val handle = part.handle ?: continue
            val override = mic.perModel[part.dto.model]
            val spec = AccessorySpec(
                id = "mic",
                source = AccessorySource.Asset(mic.asset),
                bone = mic.bone,
                position = (override?.position ?: mic.position).toFloatArray(),
                rotationEulerYXZ = (override?.rotationEulerYXZ ?: mic.rotationEulerYXZ).toFloatArray(),
                scale = override?.scale ?: mic.scale,
            )
            // 显式道具所有权：只 attach、永不 bind 动画（doc §7.2）——不被剪辑
            // 切换自动隐藏，旧剪辑的延迟 hide 也命中不了未注册的绑定
            runCatching { handle.attachAccessory(spec) }
                .onFailure { Log.e(TAG, "挂麦失败 ${part.id}: ${it.message}") }
        }
    }

    private fun seatEveryone(layoutDto: DebateLayoutDto) {
        for (part in parts.values) {
            val handle = part.handle ?: continue
            check(handle.setVrmaIdleAnimation(layoutDto.animations.seatedIdle)) { "坐姿加载失败: ${part.id}" }
            part.posture.state = PartPosture.State.SEATED
        }
    }

    private fun bindCamera() {
        camera.handleResolver = { id -> parts[id]?.handle }
        val autoSpec: () -> DebateCameraDirector.ShotSpec? = cameraSpec@{
            val snapshot = engine.state.value
            val turn = snapshot.currentTurn ?: return@cameraSpec DebateCameraDirector.ShotSpec(
                DebateCameraDirector.ShotSpec.Kind.WIDE, targets = DebateIds.ALL,
            )
            when {
                turn.speakerId == DebateIds.HOST -> DebateCameraDirector.ShotSpec(DebateCameraDirector.ShotSpec.Kind.HOST)
                snapshot.phase == DebatePhase.CROSS_EXAM -> {
                    // 攻辩：问答双人同框（跟随实际起播者所在的轮次双方）
                    crossPairSpec()
                }
                else -> DebateCameraDirector.ShotSpec(
                    DebateCameraDirector.ShotSpec.Kind.SPEAKER,
                    targetId = turn.speakerId,
                )
            }
        }
        camera.autoSpecProvider = autoSpec
    }

    /** 当前攻辩轮的问答双方（从最近 RoundStarted 事件推断）。 */
    private var crossQuestioner: String? = null
    private var crossAnswerer: String? = null

    private fun crossPairSpec(): DebateCameraDirector.ShotSpec? {
        val q = crossQuestioner ?: return null
        val a = crossAnswerer ?: return null
        return DebateCameraDirector.ShotSpec(
            DebateCameraDirector.ShotSpec.Kind.DUEL,
            targets = listOf(q, a),
        )
    }

    /** 装配完成后调用：接线引擎事件流与姿态完成事件。 */
    private var eventJob: Job? = null
    private val postureJobs = mutableListOf<Job>()

    fun activate() {
        eventJob?.cancel()
        postureJobs.forEach { it.cancel() }
        postureJobs.clear()
        eventJob = engine.events.onEach { event ->
            when (event) {
                is DebateEvent.TurnStarted -> camera.onMatchEvent()
                is DebateEvent.RoundStarted -> {
                    crossQuestioner = event.questionerId
                    crossAnswerer = event.answererId
                    camera.onMatchEvent()
                }
                is DebateEvent.PhaseChanged -> camera.onMatchEvent()
                is DebateEvent.SpeechAudioStarted -> {
                    camera.onMatchEvent()
                    setTalkGesture(event.speakerId, talking = true)
                }
                is DebateEvent.TurnEnded -> {
                    setTalkGesture(event.speakerId, talking = false)
                    _subtitle.value = null
                }
                else -> Unit
            }
        }.launchIn(rigScope)
        for (part in parts.values) {
            val handle = part.handle ?: continue
            postureJobs += handle.vrmaFinished.onEach { finished: VrmaFinished ->
                when (part.posture.state) {
                    PartPosture.State.TO_STANDING -> {
                        part.posture.state = PartPosture.State.STANDING
                        engine.notifyPostureReady(
                            part.posture.epoch,
                            part.posture.turnId,
                            part.id,
                        )
                    }
                    PartPosture.State.TO_SEATED -> part.posture.state = PartPosture.State.SEATED
                    else -> Unit
                }
            }.launchIn(rigScope)
        }
    }

    private fun setTalkGesture(speakerId: String, talking: Boolean) {
        val part = parts[speakerId] ?: return
        val layoutDto = layout ?: return
        val handle = part.handle ?: return
        val anim = if (talking) part.posture.talkClip(layoutDto) else part.posture.idleClip(layoutDto)
        runCatching { handle.setVrmaIdleAnimation(anim) }
            .onFailure { Log.w(TAG, "手势切换失败 $speakerId: ${it.message}") }
    }

    // ── 会话（TTS + 口型 + 表情；AI 引用引擎内的实际台词记录）────────────

    private val _generationStatus = MutableStateFlow<String?>(null)
    val generationStatus: StateFlow<String?> get() = _generationStatus

    private val _speechError = MutableStateFlow<String?>(null)
    val speechError: StateFlow<String?> get() = _speechError

    private fun buildChats(dto: DebateConfigDto) {
        val prefs = globalPrefs()
        _speechError.value = if (prefs.apiKeyVolcanoTts.isBlank()) {
            "请在主界面 AI 设置中配置火山豆包语音 Key，再重新载入赛程"
        } else null
        if (_speechError.value != null) return
        for (p in dto.participants) {
            val part = parts[p.id] ?: continue
            val handle = part.handle ?: continue
            val chatConfig = DebateSpeech.config(prefs, dto.matchId, p.id, p.name, p.voice, debatePersona(p))
            Log.i(TAG, "辩论语音 ${p.id}: VOLCANO ${chatConfig.resolvedVoice}")
            part.chat = ParticipantChat(p, handle, chatConfig)
            part.chat!!.start()
        }
    }

    private fun debatePersona(p: DebateConfigDto.ParticipantDto): String {
        val role = when {
            p.role == "host" -> "本场辩论赛主持人"
            DebateIds.teamOf(p.id) == DebateTeam.PRO -> "正方${p.position ?: 1}辩「${p.name}」"
            else -> "反方${p.position ?: 1}辩「${p.name}」"
        }
        val stance = when (DebateIds.teamOf(p.id)) {
            DebateTeam.PRO -> "你方立场：${config?.sides?.PRO}，整场比赛绝不动摇"
            DebateTeam.CON -> "你方立场：${config?.sides?.CON}，整场比赛绝不动摇"
            null -> "保持中立，只做程序性串场"
        }
        return "你是$role。$stance。性格：${p.personality}。发言用口语，一次只说给定内容，不使用任何标签或工具。"
    }

    inner class ParticipantChat(
        private val participant: DebateConfigDto.ParticipantDto,
        private val handle: AvatarHandle,
        private val chatConfig: AIAvatarSdk.ChatConfig,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sdk = AIAvatarSdk(
            scope,
            controller = handle,
            appContext = appContext,
            storeFactory = { _, _ -> InMemoryConversationStore() },
        )

        fun start() {
            checkNotNull(sdk.configure(chatConfig, modelReady = handle.isReady)) { "辩论语音配置不完整" }
            scope.launch {
                sdk.events.collect { event ->
                    if (event is com.neethu.orchestrator.session.AvatarEvent.SentenceStarted) {
                        _subtitle.value = participant.id to event.text
                    }
                }
            }
        }

        fun close() {
            runCatching { sdk.close() }
            scope.cancel()
        }
    }

    // ── 发言播放缝（engine ← rig）────────────────────────────────────────

    /**
     * 每回合：speak()（等首句 SentenceStarted → 报音频起播）→ 排空 → 按实测
     * 时长回报完成。取消 = cancel 协程 + interrupt；确认超时由引擎宽限期兜底。
     */
    private inner class RigSpeechPlayer : DebateSpeechPlayer {
        private var activeJob: Job? = null
        private val seq = AtomicLong(0)

        override fun start(turn: ActiveTurn, text: String) {
            val part = parts[turn.speakerId]
            val chat = part?.chat
            if (part == null || chat == null || part.handle?.isReady != true) {
                Log.e(TAG, "发言无会话/模型未就绪: ${turn.speakerId}")
                engine.notifySpeechFailed(turn.epoch, turn.turnId, IllegalStateException("no session"))
                return
            }
            _generationStatus.value = null
            val epoch = turn.epoch
            val turnId = turn.turnId
            val token = seq.incrementAndGet()
            var audioStartedAt = 0L
            val audioStarted = arrayOf(false)
            var playbackFailure: Throwable? = null
            activeJob = rigScope.launch {
                val sdk = chat.sdk
                val watcher = launch {
                    sdk.events.collect { event ->
                        if (event is AvatarEvent.TurnFailed) playbackFailure = event.error
                        if (event is AvatarEvent.SentenceFailed) playbackFailure = IllegalStateException(event.message)
                        if (event is AvatarEvent.SentenceStarted && !audioStarted[0] && token == seq.get()) {
                            audioStarted[0] = true
                            audioStartedAt = SystemClock.elapsedRealtime()
                            engine.notifySpeechAudioStarted(epoch, turnId)
                        }
                    }
                }
                try {
                    val dto = config
                    var actualText = text
                    if (dto?.speech?.mode == "ai" && turn.speakerId != DebateIds.HOST && (epoch to turnId) !in turnTexts) {
                        _generationStatus.value = "${part.dto.name} 正在接招…"
                        val prefs = globalPrefs()
                        val llmConfig = prefs.toChatConfig("debate_generation", part.dto.name, Lang.ZH)
                        actualText = try {
                            check(llmConfig.apiKey.isNotBlank()) { "未配置 AI Key" }
                            DebateAiSpeech.generate(
                                generationAdapter(llmConfig),
                                llmConfig, dto, turn, engine.transcriptEntries,
                            ).also { _generationStatus.value = null }
                        } catch (t: Exception) {
                            // 生成超时使用备用稿；暂停/跳段/重载的取消必须继续传播。
                            if (t is CancellationException && t !is TimeoutCancellationException) throw t
                            currentCoroutineContext().ensureActive()
                            _generationStatus.value = "${part.dto.name} 使用备用台词（AI 台词未就绪）"
                            Log.w(TAG, "AI 台词回退 ${turn.speakerId}: ${t.javaClass.simpleName}")
                            val fallback = if (turn.kind == TurnKind.CROSS_ANSWER) {
                                dto.scripts[turn.speakerId]?.get(DebatePhase.CROSS_SUMMARY.name) ?: text
                            } else text
                            DebateAiSpeech.clean(fallback)
                        }
                        turnTexts[epoch to turnId] = actualText
                    }
                    actualText = turnTexts[epoch to turnId] ?: actualText
                    if (token != seq.get()) return@launch
                    engine.notifySpeechTextPrepared(epoch, turnId, actualText)
                    sdk.speak(actualText)
                    if (token != seq.get()) return@launch
                    playbackFailure?.let { throw it }
                    check(audioStarted[0]) { "TTS 回合结束但没有音频起播" }
                    val consumed = SystemClock.elapsedRealtime() - audioStartedAt
                    Log.i(TAG, "发言播完 ${turn.speakerId} turnId=$turnId consumed=${consumed}ms")
                    engine.notifySpeechCompleted(epoch, turnId, interrupted = false, consumedOverrideMs = consumed)
                } catch (t: Throwable) {
                    if (token == seq.get()) {
                        Log.e(TAG, "发言失败 ${turn.speakerId}: ${t.message}")
                        engine.notifySpeechFailed(epoch, turnId, t)
                    }
                } finally {
                    watcher.cancel()
                }
            }
        }

        override fun cancelActive(reason: String) {
            _generationStatus.value = null
            seq.incrementAndGet() // 在途失败回调按 token 丢弃
            activeJob?.cancel()
            activeJob = null
            for (part in parts.values) {
                runCatching { part.chat?.sdk?.interrupt() }
            }
        }

        override fun release() {
            cancelActive("release")
        }
    }

    // ── 姿态缝（坐站链：一次性过渡剪辑 + idle 接管，doc §7.2 推荐闭环）──

    class PartPosture {
        enum class State { SEATED, STANDING, TO_STANDING, TO_SEATED }

        var state: State = State.SEATED
        var epoch: Long = -1L
        var turnId: Long = -1L

        fun idleClip(l: DebateLayoutDto): String =
            if (state == State.STANDING) l.animations.standingIdle else l.animations.seatedIdle

        fun talkClip(l: DebateLayoutDto): String =
            if (state == State.STANDING) l.animations.standingTalk else l.animations.seatedTalk
    }

    private inner class RigPostureDirector : DebatePostureDirector {
        override fun onPostureCommand(command: PostureCommand) {
            when (command.action) {
                PostureAction.STAND -> for (id in command.participantIds) standUp(id, command.turnId)
                PostureAction.SIT -> for (id in command.participantIds) sitDown(id)
            }
        }
    }

    private fun standUp(participantId: String, turnId: Long) {
        val part = parts[participantId] ?: return
        val handle = part.handle ?: return
        val layoutDto = layout ?: return
        val epoch = engine.state.value.epoch
        part.posture.epoch = epoch
        part.posture.turnId = turnId
        when (part.posture.state) {
            PartPosture.State.STANDING -> engine.notifyPostureReady(epoch, turnId, participantId)
            PartPosture.State.TO_STANDING -> Unit // 起立中，vrmaFinished 回报
            else -> {
                part.posture.state = PartPosture.State.TO_STANDING
                Log.i("DebatePosture", "$participantId 起立 turnId=$turnId")
                runCatching {
                    check(handle.loadVrmaAnimation(layoutDto.animations.standUp)) { "起立动作不可用" }
                    handle.playVrmaAnimation(loop = false, speed = 2f, fadeSeconds = 0.25f)
                    // 引擎语义：一次性剪辑播放中挂新 idle 不打断，播完自动接管
                    handle.setVrmaIdleAnimation(layoutDto.animations.standingIdle)
                }.onFailure { t ->
                    Log.e(TAG, "起立失败 $participantId: ${t.message}")
                    part.posture.state = PartPosture.State.SEATED
                }
            }
        }
    }

    private fun sitDown(participantId: String) {
        val part = parts[participantId] ?: return
        val handle = part.handle ?: return
        val layoutDto = layout ?: return
        when (part.posture.state) {
            PartPosture.State.SEATED, PartPosture.State.TO_SEATED -> Unit
            else -> {
                part.posture.state = PartPosture.State.TO_SEATED
                Log.i("DebatePosture", "$participantId 坐下")
                runCatching {
                    handle.loadVrmaAnimation(layoutDto.animations.sitDown)
                    handle.playVrmaAnimation(loop = false, speed = 2f, fadeSeconds = 0.25f)
                    handle.setVrmaIdleAnimation(layoutDto.animations.seatedIdle)
                }.onFailure { t -> Log.e(TAG, "坐下失败 $participantId: ${t.message}") }
            }
        }
    }

    // ── 生命周期 ────────────────────────────────────────────────────────

    private fun teardownStage() {
        eventJob?.cancel()
        postureJobs.forEach { it.cancel() }
        postureJobs.clear()
        speechPlayer.cancelActive("teardown")
        for (part in parts.values) {
            part.chair?.let { stage.removeProp(it) }
            part.chat?.close()
            part.chat = null
            part.handle?.let { stage.removeAvatar(it) }
            part.handle = null
        }
        parts.clear()
        turnTexts.clear()
        generationConnection = null
        generationAdapter = null
        crossQuestioner = null
        crossAnswerer = null
        camera.reset()
        _subtitle.value = null
    }

    fun close() {
        rigScope.cancel()
        teardownStage()
    }
}
