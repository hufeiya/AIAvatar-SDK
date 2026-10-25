package com.neethu.orchestrator.session

import android.os.SystemClock
import android.util.Log
import com.neethu.aiadapter.api.LipSyncProcessor
import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.api.EmotionCue
import com.neethu.aiadapter.api.TagCue
import com.neethu.aiadapter.api.TagExtractor
import com.neethu.orchestrator.tag.ActionCandidateSelector
import com.neethu.orchestrator.tag.TagCapabilityRegistry
import com.neethu.aiadapter.emotion.InlineTagExtractor
import com.neethu.aiadapter.lipsync.WlipsyncLipSyncProcessor
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.LlmUsage
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.ToolCallRequest
import com.neethu.aiadapter.model.ToolSpec
import com.neethu.aiadapter.openai.isToolRelatedError
import com.neethu.corelib.AvatarActor
import com.neethu.corelib.Lang
import com.neethu.orchestrator.agent.AgentToolCatalog
import com.neethu.orchestrator.agent.ToolExecutor
import com.neethu.orchestrator.agent.ToolRegistry
import com.neethu.orchestrator.agent.isActionCommitmentReply
import com.neethu.orchestrator.agent.isDanglingBridgeReply
import com.neethu.orchestrator.agent.isPseudoToolCallText
import com.neethu.orchestrator.agent.parseTemplateToolCalls
import com.neethu.orchestrator.i18n.PromptTexts
import com.neethu.orchestrator.i18n.promptTextsOf
import com.neethu.aiadapter.api.EmbeddingAdapter
import com.neethu.aiadapter.api.EmbeddingConfig
import com.neethu.orchestrator.memory.MemoryCoordinator
import com.neethu.orchestrator.memory.MemoryRetriever
import com.neethu.orchestrator.memory.MemoryStore
import com.neethu.orchestrator.memory.MemoryTools
import com.neethu.orchestrator.memory.RelationshipStore
import com.neethu.orchestrator.memory.RelationshipTracker
import com.neethu.orchestrator.memory.memoryTextsOf
import com.neethu.orchestrator.locomotion.LocomotionDriver
import com.neethu.orchestrator.locomotion.MoveAnchor
import com.neethu.orchestrator.audio.AudioTrackPlaybackQueue
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.card.CardPromptExtras
import com.neethu.orchestrator.card.CharacterCard
import com.neethu.orchestrator.card.Lorebook
import com.neethu.orchestrator.card.LorebookInjector
import com.neethu.orchestrator.card.SystemPromptAssembler
import com.neethu.orchestrator.chunker.SentenceChunker
import com.neethu.orchestrator.face.EmotionAliases
import com.neethu.orchestrator.face.FaceDriver
import com.neethu.orchestrator.gesture.ActionEntry
import com.neethu.orchestrator.gesture.GestureDriver
import com.neethu.orchestrator.history.ConversationStore
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.pipeline.SpeechPipeline
import com.neethu.orchestrator.skill.SkillHost
import com.neethu.orchestrator.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * High-level facade of the avatar conversational pipeline — the single entry
 * point integrators use. One instance drives: LLM streaming → sentence
 * chunking → concurrent TTS → ordered playback → face driving (visemes,
 * emotions, blinking) on the given [AvatarController].
 *
 * Typical wiring:
 * ```kotlin
 * val session = AvatarSession(
 *     scope = lifecycleScope,
 *     llm = OpenAiCompatibleLlmAdapter(baseUrl, apiKey),
 *     tts = OpenAiCompatibleTtsAdapter(baseUrl, apiKey),
 *     controller = avatarController,
 * )
 * session.llmConfig = LlmConfig(baseUrl, apiKey, model = "gpt-4o-mini")
 * session.ttsConfig = TtsConfig(model = "tts-1", voice = "alloy")
 * session.setCharacterCard(card)
 *
 * session.send("你好！")
 * session.interrupt()          // cut LLM + TTS + audio instantly
 * ```
 *
 * If no [AvatarController] is provided the session still works (audio-only,
 * headless) — useful for tests and voice-only deployments. Likewise, `llm = null`
 * builds a speak-only session (free TTS, no LLM) — [send] then emits
 * [AvatarEvent.TurnFailed] instead of streaming.
 */
class AvatarSession(
    private val scope: CoroutineScope,
    /**
     * null = 纯 TTS 会话（只允许 [speak]，[send] 发 [AvatarEvent.TurnFailed]）——
     * 免 LLM 的「只显示+说话」接入不必再塞占位 adapter。有默认值但后面的
     * [tts] 没有，纯 TTS 接入请用命名参数（见类 KDoc 示例）。
     */
    private val llm: LlmAdapter? = null,
    private val tts: TtsAdapter,
    private val controller: AvatarActor? = null,
    private val options: Options = Options(),
    playbackQueue: PlaybackQueue? = null,
    lipSyncProcessor: LipSyncProcessor? = WlipsyncLipSyncProcessor(),
    gestureDriver: GestureDriver? = null,
    /**
     * 行走决策层注入位（测试 fake / 集成方定制）；默认按 [Options.enableLlmMove]
     * 自动构建。见 [locomotionDriver]。
     */
    locomotionDriver: LocomotionDriver? = null,
    /**
     * 对话历史存储（任务 3）：默认内存环形（杀进程即失），集成方注入
     * [com.neethu.orchestrator.history.RoomConversationStore] 即获得跨进程
     * 持久化与多上下文。
     */
    store: ConversationStore = InMemoryConversationStore(),
    /**
     * 长期记忆存储（docs/memory-feasibility.md §4.6）：null = SDK 默认无记忆
     * （与 [store] 同性质的缺省语义）；demo 注入
     * [com.neethu.orchestrator.memory.RoomMemoryStore]。记忆跨上下文全局共享。
     */
    private val memoryStore: MemoryStore? = null,
    /**
     * 关系状态存储（酒馆 P3-1，docs/tavern-companion-feasibility.md §5）：
     * null = 不做关系追踪；集成方注入
     * [com.neethu.orchestrator.memory.RoomRelationshipStore]（与记忆同库）。
     * 卡级分片经 [relationshipSubjectId]（激活卡时赋值，免重建会话）。
     */
    private val relationshipStore: RelationshipStore? = null,
    /**
     * 文本嵌入适配器（记忆检索主路）：null 或 [embeddingConfig] 返回 null 时
     * 检索走关键词降级路。配置（Key/模型）经 [embeddingConfig] 每次现取，
     * 运行时换 Key 不需要重建会话。
     */
    private val embeddingAdapter: EmbeddingAdapter? = null,
    private val embeddingConfig: (() -> EmbeddingConfig?)? = null,
    /**
     * 技能框架的能力缝（docs/rps-skill-feasibility.md §4）：null = 未接线，
     * 技能状态机照常运转但能力调用（出动作/抓拍/VAD 调参）全部落空。
     */
    skillHost: SkillHost? = null,
    /**
     * TTS 句子的解码 + 口型分析所在线程（默认共享后台池）。生产注入留默认；
     * JVM 单测传与 scope 同款的 unconfined dispatcher 保持同步时序。
     */
    heavyWorkDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    data class Options(
        /** Concurrent TTS requests (AIRI default: 4). */
        val ttsMaxConcurrent: Int = 4,
        val chunkerOptions: SentenceChunker.Options = SentenceChunker.Options(),
        val enableFaceDriving: Boolean = true,
        /**
         * 提示词语言（多语言支持）：身份前言/协议块/视角行全部随之切换。
         * 默认 [Lang.ZH] 保持既有行为；集成方按应用语言显式传入（demo 见
         * AiChatController.ensure）。语言变化 = 重建会话（上下文不变，每轮身份块
         * 与协议块使用新语言）。
         */
        val lang: Lang = Lang.ZH,
        /**
         * 协议块拼装器；默认随 [lang] 取对应语言的文案目录。显式注入的
         * assembler 自带语言（集成方自行负责）。
         */
        val assembler: SystemPromptAssembler = SystemPromptAssembler(promptTextsOf(lang)),
        /**
         * 每轮携带的身份/任务/表达纪律前言，与当前人设和完整协议合为单条
         * system。null 使用当前语言默认文案；空字符串关闭前言。
         */
        val identityPreamble: String? = null,
        /**
         * 是否在每轮注入多模态协议块（全量表情/动作/镜头目录）；关闭时身份
         * 前言里的「协议遵循」段一并省略。
         */
        val protocolInstructions: Boolean = true,
        /** Let `<act:…>` tags play gestures from the action catalog. */
        val enableLlmGestures: Boolean = true,
        /** Let `<cam:…>` tags switch the preset camera framing. */
        val enableLlmCamera: Boolean = true,
        /**
         * Let `<move:锚点>` tags (and the move_to/stop_moving tools) walk the
         * avatar to scene anchors (docs/locomotion-feasibility.md §7.3). The
         * anchor table itself is injected via [locomotionAnchors] — an empty
         * table means the move section never appears in the protocol block
         * and move cues are reported as unknown tags.
         */
        val enableLlmMove: Boolean = true,
        /**
         * LLM 请求只带最近 N 条 user/assistant 历史（system 消息恒置顶、不受
         * 裁剪影响）；null = 全量发送。持久化存储下上下文会无限增长，长会话
         * 建议设置。
         */
        val recentTurnLimit: Int? = null,
        /**
         * 历史摘要压缩（酒馆 P2-4，docs/tavern-companion-feasibility.md §5）：
         * 超出 [recentTurnLimit] 的早期历史滚动摘要成一段 system 摘要块——
         * 摘要管「剧情」，长期记忆管「事实」，两者并存。触发=新增溢出消息数
         * 达 [historySummaryEveryMessages]；LLM 复用当前 provider（无工具无
         * 标签），失败保留旧摘要下轮重试。默认开。
         */
        val historySummaryEnabled: Boolean = true,
        /** 摘要滚动更新阈值：新增溢出消息数达到该值才再摘要一次。 */
        val historySummaryEveryMessages: Int = 20,
        /** 摘要文本长度上限（字符），超出截断。 */
        val historySummaryMaxChars: Int = 700,
        /**
         * 智能体工具调用总开关（docs/agent-feasibility.md §4.5）：关闭后请求
         * 永不带 tools 键、工具循环短路，行为与非 agent 会话逐字节一致。
         * 注册表为空时开启也是天然 no-op。
         */
        val agentToolsEnabled: Boolean = true,
        /** 工具轮数上限：模型连续请求工具的最大轮数（语音场景每轮都是真金白银的延迟）。 */
        val agentMaxToolRounds: Int = 3,
        /** 单工具执行超时（超时转 isError 结果回灌模型，不炸回合）。 */
        val toolTimeoutMs: Long = 10_000L,
        /**
         * SDK 集成者的内置工具注入位：构造时注册进 [tools]。demo 的
         * observe_camera 走注册表手动 register/unregister（隐私开关控制）。
         */
        val tools: List<Pair<ToolSpec, ToolExecutor>> = emptyList(),
        /**
         * 长期记忆总开关（docs/memory-feasibility.md §4.6）：与 [memoryStore]
         * 非空同时成立才生效（注入+提炼+三工具）。运行时切换走
         * [setMemoryEnabled]（不重建会话，observeCamera 先例）。
         */
        val memoryEnabled: Boolean = true,
        /** 自动提炼子开关（通道 B）；关 = 只保留 remember 工具写入与检索注入。 */
        val memoryAutoExtract: Boolean = true,
        /**
         * 攒批触发阈值：累计轮次（与 memoryExtractMinChars **或**关系，§2.1）。
         * 调大批次更省 token、调小批次更及时；真机调试用 ai_cmd memory_config。
         */
        val memoryExtractEveryTurns: Int = 5,
        /** 攒批触发阈值：窗口累计字符（内容密集的少轮次对话也能及时提炼）。 */
        val memoryExtractMinChars: Int = 600,
        /** 每轮检索注入的命中条数上限（注入 token 恒定，与库大小无关）。 */
        val memoryTopK: Int = 3,
        /** 常驻槽上限：身份/关系类且重要度 ≥8 的事实恒注入 ≤N 条。 */
        val memoryPinnedMax: Int = 3,
        /**
         * 关系追踪（酒馆 P3-1）默认开关（仅注入 relationshipStore 时生效）；
         * 运行时切换走 [setRelationshipEnabled]。
         */
        val relationshipTrackingEnabled: Boolean = true,
    )

    /** LLM request parameters; must be set before [send]. */
    var llmConfig: LlmConfig? = null

    /** TTS request parameters; must be set before [send]. */
    var ttsConfig: TtsConfig? = null

    /** Base system prompt. [setCharacterCard] fills this from a card. */
    var systemPrompt: String = ""

    /**
     * 圆桌逐轮发言权提示（一屏多虚拟人 P2，docs/multi-avatar-feasibility.md
     * §4.4）：挂在末尾 user 前缀槽（viewLine→memoryLine→skillLine 之后的
     * roundtableLine——离生成位置最近，逐轮变化、不进 store、不碰钉住协议块）。
     * null = 不注入（单虚拟人路径零变化）。舞台层在每次授予发言权时写、
     * 回合收尾/停题时清。
     */
    @Volatile
    var roundtableLine: String? = null

    private val _phase = MutableStateFlow(ConversationPhase.IDLE)
    val phase: StateFlow<ConversationPhase> = _phase.asStateFlow()

    private val _events = MutableSharedFlow<AvatarEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AvatarEvent> = _events.asSharedFlow()

    /** Face mixer; null when no controller or face driving disabled. */
    val faceDriver: FaceDriver? =
        if (controller != null && options.enableFaceDriving) FaceDriver(controller, scope) else null

    /**
     * LLM-driven gesture player; null when no controller or gestures
     * disabled. Injectable for tests (pass a fake via the constructor).
     */
    val gestureDriver: GestureDriver? =
        if (controller != null && options.enableLlmGestures) {
            gestureDriver ?: GestureDriver(controller)
        } else {
            null
        }

    /**
     * LLM-driven walk driver (`<move:锚点>` 标签 + move_to/stop_moving 工具
     * 共用的决策层，docs/locomotion-feasibility.md §7.3)；null = 无 controller
     * 或 [Options.enableLlmMove] 关闭。可注入（测试传 fake）。锚点表经
     * [locomotionAnchors] 注入（会话内不变——协议块指纹承诺）。
     */
    val locomotionDriver: LocomotionDriver? =
        if (controller != null && options.enableLlmMove) {
            locomotionDriver ?: LocomotionDriver(controller)
        } else {
            null
        }

    /**
     * 场景锚点表（`assets/scene/<场景>.anchors.json` 解析产物）：`<move:>`
     * 词表、协议块移动段与 move_to 工具的 enum 白名单共用。空表 = 功能未
     * 配置（协议块无移动段、Move cue 走 UnknownTag 上报）。
     */
    var locomotionAnchors: List<MoveAnchor> = emptyList()
        set(value) {
            field = value
            locomotionDriver?.anchors = value
            ensureWalkLegRelay()
        }

    /**
     * Full animation asset catalog. The registry exposes a small semantic vocabulary
     * plus per-turn candidates; manual/skill playback retains the full catalog.
     * Unknown or unadvertised actions are rejected with observable reasons.
     */
    var actionCatalog: List<ActionEntry> = emptyList()
        set(value) {
            field = value.toList()
            gestureDriver?.setCatalog(field)
            if (turnJob?.isActive != true) selectedActions = null
        }

    /**
     * The looping idle the model returns to after one-shot gestures and
     * manual stops (§7.10). Null = rest pose fallback. Applied through
     * [GestureDriver.setIdle] whenever the model is (re)loaded.
     */
    var idleAction: ActionEntry? = null
        set(value) {
            field = value
            if (value != null) gestureDriver?.setIdle(value) else gestureDriver?.clearIdle()
        }

    /**
     * LLM 可调用的工具注册表（docs/agent-feasibility.md §4.4）：P0 内置
     * get_device_time / get_current_view（默认注册，纯本地无隐私面）+ 集成者
     * 经 [Options.tools] 注入的 + app/技能运行时 register 的。开启
     * [Options.agentToolsEnabled] 且注册表非空时，请求携带 tools 并进入工具
     * 循环；注册表为空 = 请求体与既有形态逐字节一致（零行为变化）。
     */
    val tools: ToolRegistry = ToolRegistry(options.toolTimeoutMs)

    /** 长期记忆提示词目录（随 [Options.lang]，与 [texts] 同惯例）。 */
    private val memoryTexts = memoryTextsOf(options.lang)

    /**
     * 长期记忆协调器（docs/memory-feasibility.md §4.3）：null = 未注入
     * memoryStore 或纯 TTS 会话（llm=null，§9.7）。检索注入 / 攒批提炼 /
     * 运行时开关都经它；三记忆工具独立注册（只依赖 store，llm 缺失时读写
     * 本地仍可用，提炼自然停摆）。
     */
    val memoryCoordinator: MemoryCoordinator? = run {
        val store = memoryStore ?: return@run null
        if (llm == null) return@run null
        MemoryCoordinator(
            store = store,
            scope = scope,
            texts = memoryTexts,
            retriever = MemoryRetriever(
                store,
                embeddingAdapter,
                { embeddingConfig?.invoke() },
                MemoryRetriever.Config(topK = options.memoryTopK, pinnedMax = options.memoryPinnedMax),
            ),
            llmProvider = { llm },
            llmConfigProvider = { llmConfig },
            charName = { characterName.ifBlank { memoryTexts.defaultCharName } },
            embedder = embeddingAdapter,
            embeddingConfig = { embeddingConfig?.invoke() },
            everyTurns = options.memoryExtractEveryTurns,
            minChars = options.memoryExtractMinChars,
        ).also { it.autoExtract = options.memoryAutoExtract }
    }

    /** 检索缝独立持有：llm=null 的纯 TTS 会话里三工具仍可本地读写记忆。 */
    private val memoryRetriever: MemoryRetriever? = memoryStore?.let {
        MemoryRetriever(
            it,
            embeddingAdapter,
            { embeddingConfig?.invoke() },
            MemoryRetriever.Config(topK = options.memoryTopK, pinnedMax = options.memoryPinnedMax),
        )
    }

    /** 关系分片键（当前卡标识）；激活卡时赋值，配合 [refreshRelationship] 生效。
     *  声明必须先于 [relationship]——其初始化器经 subjectIdProvider 读本字段，
     *  unconfined scope 下 launch 急执行，晚声明会读到未初始化的 null。 */
    @Volatile
    var relationshipSubjectId: String = ""

    /**
     * 关系状态协调器（P3-1）：null = 未注入 store。快照流供 UI 低调状态行，
     * promptLine 逐轮进 system（[buildRequestMessages] 末段动态区）；卡切换
     * 后调 [refreshRelationship] 即换分片，无需重建会话。
     */
    val relationship: RelationshipTracker? = relationshipStore?.let {
        RelationshipTracker(
            store = it,
            scope = scope,
            llmProvider = { llm },
            llmConfigProvider = { llmConfig },
            subjectIdProvider = { relationshipSubjectId },
            lang = options.lang,
        ).also { tracker ->
            tracker.enabled = options.relationshipTrackingEnabled
            // 预热加载（快照流 + 提示行就绪）：失败不致命（判定与 UI 重拉兜底）
            scope.launch { runCatching { tracker.refresh() } }
        }
    }

    /** 手动关系提示行（无 store 的接入方直接喂；有 tracker 时以其 promptLine 优先）。 */
    @Volatile
    var relationshipLine: String? = null

    /** 卡切换后重拉关系分片（快照流 + 提示行）；无 tracker 时 no-op。 */
    suspend fun refreshRelationship() {
        relationship?.refresh()
    }

    /** 关系追踪运行时开关（§8.3 可关闭）：关 = 停判定，已有提示行冻结。 */
    fun setRelationshipEnabled(enabled: Boolean) {
        relationship?.enabled = enabled
    }

    /** 当前人物卡名（提炼 prompt 里助手行前缀；无卡用 MemoryTexts 缺省）。 */
    private var characterName: String = ""

    /**
     * 技能注册表（docs/rps-skill-feasibility.md §4.2）：app 喂事件
     * （语音文本/VAD 时机/相机手势），session 喂回合结果
     * （[onTurnCompleted 钩子在 send]/interrupt），每轮组请求时读
     * [SkillRegistry.activeDirective]。技能经能力缝反向调用本会话
     * （[playGestureFile]/[speak]/[send]）——host 实现持有 session 引用即可。
     * 技能的工具缝（[AvatarSkill.tools]）也经它挂进上面的注册表。
     */
    val skills: SkillRegistry = SkillRegistry(skillHost, tools) { emit(it) }

    private val queue: PlaybackQueue = playbackQueue ?: AudioTrackPlaybackQueue()
    private val pipeline =
        SpeechPipeline(scope, tts, queue, lipSyncProcessor, options.ttsMaxConcurrent, heavyWorkDispatcher)
    private val extractor: TagExtractor = InlineTagExtractor()
    private val chunker = SentenceChunker(options.chunkerOptions)
    private val store: ConversationStore = store
    private val replyBuffer = StringBuilder()
    /** 标签剥离后的正文（与 [replyBuffer] 同步累积，LlmPrompt 日志的"解析后"一路）。 */
    private val cleanBuffer = StringBuilder()
    /** 本地复用的协议文本；每个无状态请求仍须携带全文。 */
    private var pinnedProtocol: String? = null
    private var pinnedCapabilityVersion: String? = null
    /** 每轮请求恒带的前言（身份/任务/纪律 + 协议遵循提醒），实例内逐字节稳定。 */
    private val texts: PromptTexts = promptTextsOf(options.lang)
    private val missionText: String =
        listOf(
            (options.identityPreamble ?: texts.identityPreamble).trim(),
            if (options.protocolInstructions) texts.protocolReminder else "",
        )
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    private var turnJob: Job? = null

    // ── 历史摘要压缩（P2-4）───────────────────────────────────────────────
    /** 当前摘要正文；null = 尚无（早期溢出消息在摘要生成前的请求里照旧丢弃）。 */
    @Volatile
    private var historySummary: String? = null

    /** 摘要已覆盖的历史消息数（store.messages() 的前缀长度）。 */
    @Volatile
    private var historySummaryCovers: Int = 0

    /** 后台摘要任务（独立于 [turnJob]——用户打断回合不打断摘要）。 */
    private var historySummaryJob: Job? = null
    /**
     * 等待挂接的表情 cue 列表（§7.4）：标签语义上修饰"它后面的那句话"，而
     * TTS 合成+排队常把开播推迟数秒——若在 LLM 吐标签瞬间就驱动面部，3 秒
     * 自动归零会在句子出声前就把表情吃掉（真机：整段回复的表情全部提前衰减
     * 完，脸全程无变化）。cue 先存这里，[submitSentence] 挂到句序号上，开播
     * 瞬间才应用并保持"clip 时长 + 余量"。同名连续序列（协议教的眨眼模式
     * `<emo:x:1><emo:x:0>`）开播后按步进补发后续 cue；句尾残余在回合收尾时
     * 立即应用。
     */
    private val pendingEmotions = ArrayList<EmotionCue>()
    private val emotionsBySequence = java.util.concurrent.ConcurrentHashMap<Int, List<EmotionCue>>()

    init {
        lipSyncProcessor?.let { lsp ->
            if (lsp is WlipsyncLipSyncProcessor) faceDriver?.setPhonemeLayout(lsp.vowelLayout)
        }
        // P0 内置工具（纯本地无隐私面，默认开）：时钟与当前机位；集成者注入的
        // options.tools 随后（同名覆盖内置）。agentToolsEnabled=false 时注册表
        // 保持为空——请求体与非 agent 会话逐字节一致。
        if (options.agentToolsEnabled) {
            AgentToolCatalog.getDeviceTime(texts).let { (spec, exec) -> tools.register(spec, exec) }
            AgentToolCatalog.getCurrentView(texts) { controller?.getActiveCameraShot() }
                .let { (spec, exec) -> tools.register(spec, exec) }
            options.tools.forEach { (spec, exec) -> tools.register(spec, exec) }
        }
        // 记忆三工具（docs/memory-feasibility.md §4.4）：memoryStore 非空 +
        // 总开关开 + agentTools 开才注册。运行时切换走 [setMemoryEnabled]
        // （register/unregister + 协调器 flag，revision 重钉自动发生）。
        if (memoryStore != null && options.agentToolsEnabled && options.memoryEnabled) {
            registerMemoryTools()
        }
        pipeline.listener = object : SpeechPipeline.Listener {
        override fun onSentenceFailed(sequence: Int, text: String, error: Throwable) {
            Log.e("AvatarSession", "${STREAM_PREFIX}sentence #$sequence failed", error)
            emit(AvatarEvent.SentenceFailed(sequence, text, error.message ?: "TTS failed"))
        }
        }
        // 走动完成事件（自由走动 P1，docs/locomotion-feasibility.md §7.4）：corelib
        // 渲染层到达/停步 → AvatarEvent，UI 信息行与对话逻辑共用同一出口。
        // P2 waypoint 的**续腿订阅**走 [ensureWalkLegRelay]（下方，惰性幂等）：
        // 不能在 init 里一次性注册——demo 的 session 会在配置变化时重建且旧
        // 实例 scope 不取消（共享 scope，close() 不取消 job），旧实例的僵尸
        // collector 收走事件只会转给它自己的空队列 driver（真机实测多段腿断
        // 在第二段即此因）；锚点注入（[locomotionAnchors] setter）时再注册，
        // 读的是当下确定的 driver。
        // P3 修正：两条订阅都记名，[close] 时撤销——facade 重建走 close 旧
        // 实例，僵尸 relay 从「无害但常驻」变成结构性不可能（多个旧实例同时
        // 收事件，站位编排等按事件归属的消费方不能再踩重建窗口）。
        controller?.walkFinished?.let { walkFlow ->
            walkEventJob = scope.launch {
                walkFlow.collect { finished ->
                    if (finished.arrived) {
                        emit(AvatarEvent.WalkArrived(finished.x, finished.z))
                    } else {
                        emit(AvatarEvent.WalkInterrupted(finished.x, finished.z))
                    }
                }
            }
        }
        ensureWalkLegRelay()
        queue.listener = object : PlaybackQueue.Listener {
            override fun onPlaybackStarted(item: PlaybackItem) {
                queue.active()?.let { faceDriver?.onPlaybackStarted(item, it) }
                scope.launch(Dispatchers.Main.immediate) {
                    // 表情在开播瞬间应用（§7.4）：hold = clip 时长 + 余量，长句
                    // 不再中途被默认 3 秒归零切断。
                    emotionsBySequence.remove(item.sequence)?.let { cues ->
                        val holdMs = item.pcm.size * 1_000L / item.sampleRateHz.coerceAtLeast(1) +
                            EMOTION_HOLD_MARGIN_MS
                        applyEmotionCues(faceDriver, cues, holdMs)
                            ?.let { emit(AvatarEvent.EmotionChanged(it)) }
                    }
                    // Normal turns arrive here via THINKING; speak()'s greeting
                    // turns start from IDLE — either way playback means speaking.
                    if (_phase.value != ConversationPhase.SPEAKING) {
                        _phase.value = ConversationPhase.SPEAKING
                    }
                    emit(AvatarEvent.SentenceStarted(item.sequence, item.text))
                }
            }

            override fun onPlaybackEnded(item: PlaybackItem) {
                pipeline.onPlaybackSettled()
                faceDriver?.onPlaybackEnded()
                // 观测点：Ended 应发生在整段 PCM 播完之后（墙钟差 ≥ pcm 时长），
                // 若明显小于说明句尾被截断（对照 logcat 的 SentenceStarted 时间戳）。
                Log.i(
                    "AvatarSession",
                    "${STREAM_PREFIX}clip #${item.sequence} pcm=${"%.2f".format(item.pcm.size.toFloat() / item.sampleRateHz)}s",
                )
                scope.launch(Dispatchers.Main.immediate) {
                    emit(AvatarEvent.SentenceEnded(item.sequence, item.text))
                }
            }

            override fun onPlaybackInterrupted(item: PlaybackItem?) {
                pipeline.onPlaybackSettled()
                faceDriver?.onPlaybackInterrupted()
                scope.launch(Dispatchers.Main.immediate) {
                    if (_phase.value == ConversationPhase.SPEAKING) _phase.value = ConversationPhase.IDLE
                    emit(AvatarEvent.PlaybackInterrupted)
                }
            }
        }
    }

    // ── Configuration ─────────────────────────────────────────────────────

    /**
     * 世界书（酒馆 P1-1，docs/tavern-companion-feasibility.md）：每轮组请求时
     * 对最近 [LorebookInjector.Config.scanDepth] 条用户消息做关键词扫描，命中
     * 条目按序/预算注入 system 末尾；constant 条目恒注入。运行时可换（app 的
     * 世界书绑定/全局书直写本属性，请求时生效，不重建会话）。
     */
    @Volatile
    var lorebook: Lorebook? = null

    /** 世界书总开关（运行时切换；关 = 不扫描不注入）。 */
    @Volatile
    var lorebookEnabled: Boolean = true

    /**
     * 当前人物卡（P1-3 卡字段补全的取数源）：mes_example few-shot 与
     * post_history_instructions 逐轮从卡读取，卡片热替换（app 的人工提示词
     * 覆盖走 systemPrompt 直写，不影响本字段）。
     */
    private var activeCard: CharacterCard? = null

    /** Load persona from a character card and rebuild the system prompt. */
    fun setCharacterCard(card: CharacterCard) {
        systemPrompt = options.assembler.assemble(card)
        // 提炼 prompt 的助手行前缀用卡名（无卡回 MemoryTexts 缺省「虚拟人」）
        characterName = card.name
        activeCard = card
        // 卡内嵌世界书默认生效（app 绑定了独立书时随后覆盖 [lorebook]）；
        // 换卡即重置——上一张卡的书不允许泄漏进新角色
        lorebook = card.characterBook
    }

    /** Drop the card persona (system prompt reverts to empty). */
    fun clearCharacterCard() {
        systemPrompt = ""
        characterName = ""
        activeCard = null
        lorebook = null
    }

    /**
     * Start the face driver. Call once the avatar model is loaded so the
     * expression name list is available. Safe to call again after model reload.
     */
    fun startFaceDriving() {
        faceDriver?.start()
    }

    fun stopFaceDriving() {
        faceDriver?.stop()
    }

    /**
     * 应用拟人感设置（呼吸/视线/眨眼的开关与参数）。全部实时生效、不重建
     * 会话；会话（重）建后由调用方重放，使设置跨会话存续。
     */
    fun applyLivenessSettings(
        breathEnabled: Boolean,
        breathAmplitude: Float,
        breathRateBpm: Float,
        saccadeEnabled: Boolean,
        saccadeJitter: Float,
        blinkEnabled: Boolean,
        blinkIntervalS: Float,
    ) {
        controller?.setBreathEnabled(breathEnabled)
        controller?.setBreathAmplitudeScale(breathAmplitude)
        controller?.setBreathRateBpm(breathRateBpm)
        faceDriver?.setSaccadeEnabled(saccadeEnabled)
        faceDriver?.setSaccadeJitter(saccadeJitter)
        faceDriver?.setBlinkEnabled(blinkEnabled)
        faceDriver?.setBlinkIntervalMean(blinkIntervalS)
    }

    /** Clear dialog history (a fresh conversation). */
    fun clearHistory() {
        historySummaryJob?.cancel()
        historySummary = null
        historySummaryCovers = 0
        store.clear()
        previousAction = null
        turnPreviousAction = null
        candidateInput = ""
        selectedActions = null
    }

    /**
     * 文本进历史（user 角色）但**不发起生成**（圆桌 P2 的听众 fanout 缝）：
     * 话题与别人的发言以此形态广播进倾听者的上下文（§4.4「其他 avatar 的
     * 发言：role=user，前缀【B】:…」），等它被授予发言权时上下文已经齐了。
     * 单虚拟人接入方不会用到；与 [send] 的区别仅在于不跑 LLM/TTS 流水。
     */
    fun injectUserMessage(text: String) {
        if (text.isNotBlank()) store.appendUser(text)
    }

    /** 当前历史全量（聊天记录导出用，P1-4）。只读快照，不含 system/协议。 */
    fun historyMessages(): List<ChatMessage> = store.messages()

    /**
     * 整体替换历史（聊天记录导入用，P1-4）：清空当前上下文后逐条灌入
     * （文本应已过 com.neethu.orchestrator.history.StChatTranscript.sanitize
     * 清洗——旧行内标签不回灌请求）。与 [clearHistory] 同样重置动作择优的
     * 跨回合状态。进行中的回合先打断。
     */
    fun importHistory(messages: List<ChatMessage>) {
        if (turnJob?.isActive == true) interrupt("import-history")
        historySummaryJob?.cancel()
        historySummary = null
        historySummaryCovers = 0
        store.clear()
        previousAction = null
        turnPreviousAction = null
        candidateInput = ""
        selectedActions = null
        for (m in messages) {
            when (m.role) {
                ChatRole.USER -> if (m.content.isNotBlank()) store.appendUser(m.content)
                ChatRole.ASSISTANT -> if (m.content.isNotBlank()) store.appendAssistant(m.content)
                else -> Unit // system/tool 不入对话历史
            }
        }
    }

    // ── Long-term memory (docs/memory-feasibility.md §4.6) ───────────────

    /**
     * 长期记忆总开关（运行时切换，不重建会话——observeCamera 先例）：开 =
     * 三工具注册 + 检索注入 + 自动提炼；关 = 全部退场 + 待提炼窗口清空。
     * 工具集合变化 → 协议块按指纹自动重钉。
     */
    fun setMemoryEnabled(enabled: Boolean) {
        if (memoryStore == null) return
        memoryCoordinator?.setEnabled(enabled)
        if (enabled && options.agentToolsEnabled) {
            registerMemoryTools()
        } else {
            unregisterMemoryTools()
        }
    }

    /** 自动提炼子开关（运行时）：只关通道 B，注入与工具写入照常。 */
    fun setMemoryAutoExtract(enabled: Boolean) {
        memoryCoordinator?.autoExtract = enabled
    }

    /** 当前记忆配置快照（ai_cmd memory_config 无参查看用）。 */
    fun memoryConfigLine(): String? = memoryCoordinator?.windowLine()

    private fun registerMemoryTools() {
        val store = memoryStore ?: return
        val retriever = memoryRetriever ?: return
        if (tools.find(MemoryTools.NAME_REMEMBER) != null) return // 幂等
        MemoryTools.remember(store, embeddingAdapter, { embeddingConfig?.invoke() }, memoryTexts)
            .let { (spec, exec) -> tools.register(spec, exec) }
        MemoryTools.recall(store, retriever, memoryTexts)
            .let { (spec, exec) -> tools.register(spec, exec) }
        MemoryTools.forget(store, memoryTexts)
            .let { (spec, exec) -> tools.register(spec, exec) }
    }

    private fun unregisterMemoryTools() {
        MemoryTools.ALL_NAMES.forEach { tools.unregister(it) }
    }

    // ── Conversation control ──────────────────────────────────────────────

    /**
     * Fire-and-forget user turn. Emits [AvatarEvent]s on [events].
     *
     * [images] (data URLs) ride the CURRENT request only — the multimodal
     * camera snapshot of a video-call turn. They are attached to the trailing
     * user message but never persisted into history: keeping every historical
     * turn's frame in context would multiply vision-token cost per turn for
     * no conversational gain (the model reads "刚才那张图" without pixels).
     */
    fun send(text: String, images: List<String> = emptyList()) {
        if (turnJob?.isActive == true) interrupt("superseded")
        val llmCfg = llmConfig ?: run {
            emit(AvatarEvent.TurnFailed(IllegalStateException("llmConfig not set"))); return
        }
        val ttsCfg = ttsConfig ?: run {
            emit(AvatarEvent.TurnFailed(IllegalStateException("ttsConfig not set"))); return
        }
        val llmAdapter = llm ?: run {
            emit(AvatarEvent.TurnFailed(IllegalStateException("llm adapter not set (speak-only session)"))); return
        }
        if (images.isNotEmpty()) {
            Log.i("AvatarSession", "${STREAM_PREFIX}multimodal turn: ${images.size} image(s), text=${text.take(40)}")
        }
        turnJob = scope.launch {
            _phase.value = ConversationPhase.THINKING
            extractor.reset()
            chunker.reset()
            replyBuffer.setLength(0)
            cleanBuffer.setLength(0)
            pendingEmotions.clear()
            emotionsBySequence.clear()
            pipeline.beginTurn()
            prepareCapabilities(text)
            store.appendUser(text)
            // 首句延迟三段法（grep InfoStreamDectect）：REQUEST→首个 TextDelta 的
            // ttfb + SentenceQueued→SentenceStarted 的 TTS 合成，都在本行/事件流里
            val turnStartMs = SystemClock.elapsedRealtime()
            var ttfbMs = -1L
            var usage: LlmUsage? = null
            try {
                // 记忆检索（docs/memory-feasibility.md §4.5）：在本轮用户输入上
                // 检索（空库短路 0ms；embed ≤800ms 超时→关键词降级）。挂末尾
                // user 前缀槽，与视角行/技能行同缝——逐轮变化、不进 store、
                // 不碰钉住协议块。
                val memoryLine = memoryCoordinator?.lineFor(text)
                val messages = buildRequestMessages(images, memoryLine).toMutableList()
                // 工具循环（docs/agent-feasibility.md §4.5）：文本增量照旧走既有
                // 断句/TTS/口型流水（跨轮句序号连续，语音无感）；模型请求工具时
                // 在「流结束」与「回合完成」之间插入「执行→结果回灌→再请求」，
                // 最多 agentMaxToolRounds 轮。工具消息只活在当前回合的请求里，
                // 回合结束 store 仍只存 user+assistant 全量 raw 文本。
                var round = 0
                var emptyRetries = 0
                while (true) {
                    val roundTextStart = replyBuffer.length
                    val roundCleanStart = cleanBuffer.length
                    embodimentDispatchedThisRound = false
                    val useTools = options.agentToolsEnabled &&
                        !toolsDisabledModels.contains(modelKey(llmCfg)) &&
                        !tools.isEmpty()
                    val roundCfg = if (useTools) llmCfg.copy(tools = tools.list()) else llmCfg
                    var sawText = false
                    var sawToolCall = false
                    var finish: LlmStreamEvent.Finish? = null
                    try {
                        finish = streamRound(
                            llmAdapter, messages, roundCfg, ttsCfg, round,
                            onFirstDelta = {
                                sawText = true
                                if (ttfbMs < 0) ttfbMs = SystemClock.elapsedRealtime() - turnStartMs
                            },
                            onToolCall = { sawToolCall = true },
                        )
                    } catch (t: Throwable) {
                        // 工具相关 400 自动降级（抄 airi llm.ts:63-84）：立即以无
                        // tools 配置重发一次（文本照常），并按 modelKey 缓存禁用，
                        // 本会话不再尝试。只在「本轮还没吐过文本」时降级重发——
                        // 已有可见输出重发会复读。
                        if (useTools && !sawText && !sawToolCall && isToolRelatedError(t)) {
                            toolsDisabledModels += modelKey(llmCfg)
                            Log.w(
                                "AvatarSession",
                                "${STREAM_PREFIX}tool-related error → tools disabled for ${llmCfg.model}, retrying without tools: ${t.message}",
                            )
                            extractor.reset()
                            chunker.reset()
                            finish = streamRound(
                                llmAdapter, messages, llmCfg, ttsCfg, round,
                                onFirstDelta = { if (ttfbMs < 0) ttfbMs = SystemClock.elapsedRealtime() - turnStartMs },
                                onToolCall = {},
                            )
                        } else {
                            throw t
                        }
                    }
                    usage = finish.usage
                    var toolCalls = finish.toolCalls
                    // 悬空衔接句补救（Qwen3.8 关思考实测 ~50% 只说「让我看看……」不发
                    // 调用，docs/agent-provider-matrix.md）：用 tool_choice=required
                    // 静默重试一轮（textSink 静默——required 下服务端可能把 Qwen 模板
                    // 的 <tool_call> 文本当 delta 吐出，绝不能进 TTS），原生调用与
                    // 模板文本解析双路兜底；重试仍无调用则按原回复正常收尾。
                    // 2026-10-24 扩展三类命中：动作承诺句（「我这就去坐」——坐/躺只有
                    // 工具能执行；⚠本轮标签通道已分派身体动作则不算悬空——「<move:x>
                    // 这就过去」是正常搭配，B2 教训）、伪工具调用文本（「<interact_with>
                    // …」写进正文，⚠必须查 raw——MALFORMED 伪标签已被提取器从 clean
                    // 文本剥掉；任何轮都可能发生——E1 实测出现在工具轮之后的续答轮）。
                    embodimentDispatchedThisRound = false
                    val roundRaw = replyBuffer.substring(roundTextStart)
                    if (toolCalls.isEmpty() && useTools &&
                        ((round == 0 && isDanglingBridgeReply(cleanBuffer.substring(roundCleanStart))) ||
                            (!embodimentDispatchedThisRound &&
                                isActionCommitmentReply(cleanBuffer.substring(roundCleanStart))) ||
                            isPseudoToolCallText(roundRaw, tools.list().map { it.name }))
                    ) {
                        Log.i(
                            PROMPT_TAG,
                            "$STREAM_PREFIX dangling bridge reply, retrying with tool_choice=required",
                        )
                        val retryText = StringBuilder()
                        val retryFinish = try {
                            streamRound(
                                llmAdapter, messages, roundCfg.copy(toolChoice = "required"), ttsCfg, round,
                                onFirstDelta = {}, onToolCall = {}, textSink = retryText,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            // required 并非家家支持（DeepSeek 思考模式实测 400
                            // 「Thinking mode does not support this tool_choice」）：
                            // 降级为**不带 tool_choice 的静默普通重试**再给模型一次
                            // 原生调用的机会（同一上下文再采样，宣布不调用的形态
                            // 基线实测约 2/3 能在重试中补上调）。仍失败才按原回复收尾。
                            Log.w(
                                "AvatarSession",
                                "$STREAM_PREFIX required retry rejected (${t.message}), retrying plain",
                            )
                            try {
                                streamRound(
                                    llmAdapter, messages, roundCfg, ttsCfg, round,
                                    onFirstDelta = {}, onToolCall = {}, textSink = retryText,
                                )
                            } catch (e2: CancellationException) {
                                throw e2
                            } catch (t2: Throwable) {
                                Log.w(
                                    "AvatarSession",
                                    "$STREAM_PREFIX plain retry also failed (${t2.message}), keeping original reply",
                                )
                                null
                            }
                        }
                        val retryCalls = retryFinish?.toolCalls.orEmpty().ifEmpty {
                            parseTemplateToolCalls(retryText.toString())
                        }
                        if (retryCalls.isNotEmpty() && retryFinish != null) {
                            finish = retryFinish
                            usage = retryFinish.usage
                            toolCalls = retryCalls
                            logChunked(PROMPT_TAG, "TOOL retry", "recovered ${retryCalls.map { it.name }} from required round")
                        }
                    }
                    if (toolCalls.isEmpty() || !useTools) {
                        // 空回复补救（2026-10-11 真机实测：服务商偶发 200 但 0 字——
                        // completion 全进思考通道，用户听到死寂；工具轮之后的续答轮
                        // 同样会中招）：整回合零可见输出且本轮没请求工具时，原样重发
                        // 一次（messages 已含历史与工具结果，重发即续答重试）。
                        if (replyBuffer.isEmpty() && toolCalls.isEmpty() && emptyRetries < 1) {
                            emptyRetries++
                            Log.w(
                                "AvatarSession",
                                "$STREAM_PREFIX empty reply (completion=${usage?.completionTokens ?: -1}), retrying once",
                            )
                            continue
                        }
                        break
                    }
                    round++
                    if (round > options.agentMaxToolRounds) {
                        // 轮数用尽：直接 break，已有文本照常收尾（上限写进了协议
                        // 块的工具说明里，正常情况下模型自己不会越线）
                        Log.w("AvatarSession", "${STREAM_PREFIX}tool round limit (${options.agentMaxToolRounds}) reached, breaking")
                        break
                    }
                    emit(AvatarEvent.ToolCallsIssued(toolCalls.map { it.name }))
                    lastToolCalls = toolCalls.map { it.name }
                    // 原始回复仅用于审计；回灌模型前按当前能力规范化标签。
                    messages += ChatMessage(
                        ChatRole.ASSISTANT,
                        projectAssistantHistory(replyBuffer.substring(roundTextStart)),
                        toolCalls = toolCalls,
                    )
                    logChunked(
                        PROMPT_TAG,
                        "TOOL round=$round",
                        "calls=[${toolCalls.joinToString(", ") { it.name }}] args=[${toolCalls.joinToString(", ") { it.argumentsJson }}]",
                    )
                    // 顺序执行（不并行）：多工具并发会有竞态观感，且保证 tool_call_id 对位
                    for (call in toolCalls) {
                        val result = tools.invoke(call.name, call.argumentsJson)
                        Log.i(
                            PROMPT_TAG,
                            "$STREAM_PREFIX TOOL round=$round ${call.name}(${call.argumentsJson.take(200)})" +
                                " → isError=${result.isError} ${result.content.take(200)}",
                        )
                        emit(AvatarEvent.ToolCallFinished(call.name, result.isError))
                        messages += ChatMessage(
                            ChatRole.TOOL,
                            result.content.take(MAX_TOOL_RESULT_CHARS),
                            toolCallId = call.id,
                        )
                    }
                }
                val streamMs = SystemClock.elapsedRealtime() - turnStartMs
                // Drain the extractor tail, then any chunker remainder.
                val tail = extractor.flush()
                dispatchCues(tail.cues)
                cleanBuffer.append(tail.cleanText)
                if (tail.cleanText.isNotEmpty()) {
                    chunker.feed(tail.cleanText).forEach { submitSentence(it, ttsCfg) }
                }
                chunker.flush().forEach { submitSentence(it, ttsCfg) }
                // 句尾残余标签（其后没有新句子了）：立即应用
                flushTrailingEmotion()
                pipeline.endTurn()
                pipeline.awaitTurnComplete()
                // 观测点：原始回复（含标签）——排查"模型没发标签/标签被丢弃"先看这行
                Log.i("AvatarSession", "${STREAM_PREFIX}raw reply: ${replyBuffer}")
                // 需求 5：原始与解析后的完整回复落到专用 tag（分段+单行化，方便排查）。
                // ttfb=首 token 延迟（首句等待的大头）；tok 一段=prompt/cached/completion
                // token（cached>0=服务商前缀缓存命中，钉住协议的省钱验证位）
                Log.i(
                    PROMPT_TAG,
                    "${STREAM_PREFIX}=== RESPONSE model=${llmCfg.model} raw=${replyBuffer.length}ch clean=${cleanBuffer.length}ch " +
                        "ttfb=${ttfbMs}ms stream=${streamMs}ms toolRounds=$round${usagePart(usage)} ===",
                )
                logChunked(PROMPT_TAG, "RESPONSE raw", replyBuffer.toString())
                logChunked(PROMPT_TAG, "RESPONSE clean", cleanBuffer.toString())
                store.appendAssistant(replyBuffer.toString())
                // 记忆窗口追加（通道 B，docs/memory-feasibility.md §4.3）：放在
                // try 块内 TurnCompleted 之前——被打断/失败的回合走取消或异常
                // 路径天然跳过（半截对话提炼出来的事实不可信）；内部达阈值才
                // 后台提炼，fire-and-forget 永不在此处抛。
                memoryCoordinator?.onTurnCompleted(text, cleanBuffer.toString())
                // 关系状态判定（P3-1）：同上 fire-and-forget；够长才调 LLM，
                // 增量落库后快照流与提示行自动更新
                relationship?.onTurnCompleted(text, cleanBuffer.toString())
                // 历史摘要滚动触发（P2-4）：整轮落库后检查，后台执行不阻塞下一回合
                maybeSummarizeHistory()
                _phase.value = ConversationPhase.IDLE
                emit(AvatarEvent.TurnCompleted(interrupted = false))
                // 技能缝：回合结果广播给技能（猜拳 INVITED→ARMED / JUDGING→ARMED）
                skills.onTurnCompleted(cleanBuffer.toString())
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                pipeline.cancelTurn("turn-error")
                _phase.value = ConversationPhase.IDLE
                emit(AvatarEvent.TurnFailed(t))
                skills.onTurnFailed()
            }
        }
    }

    /** Send and suspend until the whole turn (LLM + TTS + playback) settles. */
    suspend fun sendAndAwait(text: String, images: List<String> = emptyList()) {
        send(text, images)
        turnJob?.join()
    }

    /**
     * 技能专用动作通道：不经 LLM 动作目录直接按路径播一个动作 clip（非循环，
     * 播完引擎自动回待机）——猜拳出拳手势走这里，`<act:>` 协议块里永远看不到
     * 这些 tag（防 LLM 对话中随机刷出）。[assetPath] 是 assets 相对路径
     * （"animations/…"）或绝对文件路径。
     */
    fun playGestureFile(assetPath: String): Boolean {
        val driver = gestureDriver ?: return false
        val name = assetPath.substringAfterLast('/').removeSuffix(".vrma")
        return driver.playFile(ActionEntry(tag = "skill:$name", label = name, assetPath = assetPath))
    }

    /**
     * 自主问候的回执写入长期记忆（docs/autonomy-feasibility.md §5，airi 教训
     * 「主动发言要有回执」）：问候不进对话历史，但落一条 event 类记忆——下次
     * 对话的记忆提炼/检索能引用「上次主动问候过什么」。无记忆库/no-op，失败
     * 静默（回执是增强，永不影响问候主链）。
     */
    fun writeAutonomyReceipt(content: String) {
        val store = memoryStore ?: return
        scope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                store.add(
                    com.neethu.orchestrator.memory.AgentMemory(
                        id = java.util.UUID.randomUUID().toString().replace("-", "").take(12),
                        content = content.take(com.neethu.orchestrator.memory.MemoryLimits.CONTENT_MAX_CHARS),
                        category = com.neethu.orchestrator.memory.MemoryCategories.EVENT,
                        importance = 5,
                        source = com.neethu.orchestrator.memory.MemorySources.GREETING,
                        createdAt = now,
                        updatedAt = now,
                        lastAccessedAt = now,
                        embedding = com.neethu.orchestrator.memory.MemoryTools.embedSafely(
                            content, embeddingAdapter, { embeddingConfig?.invoke() },
                        ),
                    ),
                )
            }.onFailure { Log.w("AvatarSession", "autonomy receipt failed: ${it.message}") }
        }
    }

    /**
     * Speak [text] directly, without an LLM turn — character-card greetings,
     * announcements. The text goes through the same tag extractor → chunker
     * → TTS → ordered playback pipeline as LLM turns (inline tags — emotion,
     * action, camera — ARE interpreted, so a greeting can wave and smile);
     * phase skips THINKING and the playback callback drives IDLE→SPEAKING→
     * IDLE. The text is not appended to conversation history. Suspending
     * like [sendAndAwait]; [interrupt] cuts it mid-playback.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (turnJob?.isActive == true) interrupt("superseded")
        val ttsCfg = ttsConfig ?: run {
            emit(AvatarEvent.TurnFailed(IllegalStateException("ttsConfig not set")))
            skills.onSpeakCompleted(text)
            return
        }
        val job = scope.launch {
            prepareCapabilities(text)
            extractor.reset()
            chunker.reset()
            pendingEmotions.clear()
            emotionsBySequence.clear()
            pipeline.beginTurn()
            try {
                val tagged = extractor.feed(text)
                val tail = extractor.flush()
                dispatchCues(tagged.cues + tail.cues)
                (chunker.feed(tagged.cleanText + tail.cleanText) + chunker.flush())
                    .forEach { submitSentence(it, ttsCfg) }
                flushTrailingEmotion()
                pipeline.endTurn()
                pipeline.awaitTurnComplete()
                _phase.value = ConversationPhase.IDLE
                emit(AvatarEvent.TurnCompleted(interrupted = false))
                // speak 的完成事件单独广播（回合结果广播刻意不含 speak）：
                // 「看这边」用它做回合节拍；其余技能默认空实现无感
                skills.onSpeakCompleted(text)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                pipeline.cancelTurn("speak-error")
                _phase.value = ConversationPhase.IDLE
                emit(AvatarEvent.TurnFailed(t))
                skills.onSpeakCompleted(text)
            }
        }
        turnJob = job
        job.join()
    }

    /**
     * Three-layer interrupt (AIRI parity): cancels the LLM stream, aborts
     * in-flight TTS, and silences playback immediately. Any LLM-started
     * gesture is cut back to the rest pose; the camera keeps its current
     * framing (a shot is a mode, not a transient). Phase returns to IDLE.
     */
    fun interrupt(reason: String = "user-interrupt") {
        turnJob?.cancel()
        turnJob = null
        pendingEmotions.clear()
        emotionsBySequence.clear()
        gestureDriver?.stop()
        pipeline.cancelTurn(reason)
        skills.onInterrupted()
        scope.launch(Dispatchers.Main.immediate) {
            if (_phase.value != ConversationPhase.IDLE) _phase.value = ConversationPhase.IDLE
        }
    }

    /** Interrupt + stop face driving + release audio resources. */
    fun close() {
        interrupt("close")
        faceDriver?.stop()
        queue.release()
        // P3：撤销走动订阅（事件转出 + 续腿 relay）——本实例的共享 scope 还
        // 活着（rig 的槽 scope 跨重建共享），不撤就是常驻僵尸 collector
        walkEventJob?.cancel()
        walkEventJob = null
        walkLegRelayJob?.cancel()
        walkLegRelayJob = null
    }

    /**
     * P2 waypoint 多段腿：把 controller.walkFinished 转交给
     * [locomotionDriver]（到达=续走下一段，停步=清队列）。惰性幂等——
     * init 时 driver 可能尚未确定（demo 里 enableLlmMove 随 prefs 异步
     * 到位），[locomotionAnchors] 注入时（会话接线 effect）再调一次；
     * 重复调用不重复订阅。
     */
    private var walkEventJob: kotlinx.coroutines.Job? = null
    private var walkLegRelayJob: kotlinx.coroutines.Job? = null
    private fun ensureWalkLegRelay() {
        if (walkLegRelayJob != null) return
        val driver = locomotionDriver ?: return
        val flow = controller?.walkFinished ?: return
        walkLegRelayJob = scope.launch {
            flow.collect { driver.onWalkFinished(it.arrived) }
        }
    }

    // ── Turn internals ────────────────────────────────────────────────────

    /**
     * 采集一轮 LLM 流：文本增量走既有 handleDelta 流水（标签→断句→TTS），
     * 工具调用增量只发观测事件；返回该轮的 Finish 载荷（含累积完成的
     * tool_calls）。正常情况下 flow 以 Finish 事件收尾，理论上不进入兜底。
     *
     * [textSink] 非空 = 静默轮：文本增量只进该缓冲，**不进** extractor/断句/TTS
     * （required 重试轮的服务端可能把 Qwen 模板的 `<tool_call>` 文本当 delta 吐
     * 出来，绝不能朗读，见 [parseTemplateToolCalls]）。
     */
    private suspend fun streamRound(
        adapter: LlmAdapter,
        messages: List<ChatMessage>,
        cfg: LlmConfig,
        ttsCfg: TtsConfig,
        round: Int,
        onFirstDelta: () -> Unit,
        onToolCall: () -> Unit,
        textSink: StringBuilder? = null,
    ): LlmStreamEvent.Finish {
        var finish: LlmStreamEvent.Finish? = null
        adapter.streamChat(messages, cfg).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> {
                    val sink = textSink
                    if (sink != null) {
                        sink.append(event.text)
                    } else {
                        onFirstDelta()
                        handleDelta(event.text, ttsCfg)
                    }
                }
                is LlmStreamEvent.ToolCallStarted -> {
                    onToolCall()
                    emit(AvatarEvent.ToolCallStarted(event.name))
                }
                // 参数增量：进度观测用，回合层无需逐片处理
                is LlmStreamEvent.ToolCallArguments -> Unit
                is LlmStreamEvent.Finish -> finish = event
                is LlmStreamEvent.Error -> throw event.throwable
            }
        }
        return finish ?: LlmStreamEvent.Finish(null, null, emptyList()).also {
            Log.w("AvatarSession", "${STREAM_PREFIX}round $round stream ended without Finish")
        }
    }

    /** 工具相关 400 后的禁用缓存（provider+model；本会话内不再尝试工具）。 */
    private val toolsDisabledModels = mutableSetOf<String>()

    /** 最近一轮工具调用（观测快照用）。 */
    @Volatile private var lastToolCalls: List<String> = emptyList()

    /** 调试快照：智能体工具状态（ai_cmd agent_state 的正本）。单行。 */
    fun agentSnapshot(): String = buildString {
        append("agentTools=${options.agentToolsEnabled}")
        append(" maxToolRounds=${options.agentMaxToolRounds}")
        append(" toolTimeoutMs=${options.toolTimeoutMs}")
        append(" tools=[${tools.list().joinToString(", ") { it.name }}]")
        append(" lastToolCalls=[${lastToolCalls.joinToString(", ")}]")
        append(" disabledModels=[${toolsDisabledModels.joinToString(", ")}]")
        memoryCoordinator?.let {
            append(" memoryCoordinator=yes")
        } ?: run { if (memoryStore != null) append(" memoryCoordinator=no(llm null)") }
    }

    private fun modelKey(cfg: LlmConfig): String = "${cfg.baseUrl}|${cfg.model}"

    private fun handleDelta(delta: String, ttsCfg: TtsConfig) {
        replyBuffer.append(delta)
        val result = extractor.feed(delta)
        cleanBuffer.append(result.cleanText)
        dispatchCues(result.cues)
        if (result.cleanText.isNotEmpty()) {
            chunker.feed(result.cleanText).forEach { submitSentence(it, ttsCfg) }
        }
    }

    /**
     * Multimodal dispatch (docs/ai-layer-handoff.md §7.4)：camera/action cue
     * 在 LLM 吐出的瞬间执行（反应快，且动作/镜头是持续态，早几秒无妨）；
     * emotion cue 改为挂到其后第一个句子上、开播瞬间才驱动面部——TTS 排队
     * 延迟下"即发即执行"会让表情在出声前就被 3 秒归零吃掉（真机踩过）。
     * Unknown names are filtered out (never spoken) and reported via
     * [AvatarEvent.UnknownTag]. 模型自造词表外名字是常态：先过
     * [EmotionAliases]（中文表回 canonical），落空的英文情绪词再兜底（模型
     * 原生 morph 优先、表只补空缺），动作槽里的情绪词跨类改写成表情
     * （`<act:laugh>` → happy，真机踩过），仍无处可去的发 UnknownTag 供
     * 面板/日志定位模型实际写了什么。
     */
    private fun dispatchCues(cues: List<TagCue>) {
        val registry = capabilityRegistry()
        for (rawCue in cues) {
            val resolution = registry.resolve(rawCue)
            val cue = resolution.cue
            if (cue == null) {
                val reason = resolution.reason ?: TagCapabilityRegistry.Reason.UNKNOWN_NAME
                rejectTag(rawCue, reason)
                if (reason == TagCapabilityRegistry.Reason.UNKNOWN_NAME || reason == TagCapabilityRegistry.Reason.MALFORMED) {
                    reportUnknownTag(tagKind(rawCue), tagRaw(rawCue))
                }
                continue
            }
            if (resolution.aliased) {
                emit(AvatarEvent.TagAliasResolved(tagKind(rawCue), tagRaw(rawCue), registry.canonicalTag(rawCue).orEmpty()))
            }
            when (cue) {
                is TagCue.Emotion -> {
                    if (faceDriver == null) rejectTag(rawCue, if (options.enableFaceDriving) TagCapabilityRegistry.Reason.UNAVAILABLE else TagCapabilityRegistry.Reason.DISABLED)
                    else pendingEmotions += EmotionCue(cue.name, cue.intensity)
                }
                is TagCue.Action -> {
                    if (actionStartedThisTurn || skills.bodyOccupied) {
                        rejectTag(rawCue, TagCapabilityRegistry.Reason.BUSY)
                        continue
                    }
                    val entry = registry.actions.getValue(cue.name)
                    if (gestureDriver?.play(entry.tag) == true) {
                        actionStartedThisTurn = true
                        embodimentDispatchedThisRound = true
                        previousAction = entry.tag
                        emit(AvatarEvent.ActionStarted(cue.name, entry.label))
                    } else rejectTag(rawCue, TagCapabilityRegistry.Reason.RESOURCE_LOAD_FAILED)
                }
                is TagCue.Camera -> {
                    val shot = TagCapabilityRegistry.cameraShots.getValue(cue.name)
                    if (controller == null) rejectTag(rawCue, TagCapabilityRegistry.Reason.UNAVAILABLE)
                    else {
                        controller.setCameraShot(shot)
                        emit(AvatarEvent.CameraChanged(shot))
                    }
                }
                is TagCue.Move -> {
                    val driver = locomotionDriver
                    val anchor = driver?.find(cue.name)
                    if (driver == null || anchor == null) rejectTag(rawCue, TagCapabilityRegistry.Reason.UNAVAILABLE)
                    else when (driver.walkTo(anchor)) {
                        LocomotionDriver.Outcome.STARTED -> {
                            embodimentDispatchedThisRound = true
                            emit(AvatarEvent.WalkStarted(anchor.name, anchor.x, anchor.z))
                        }
                        LocomotionDriver.Outcome.ALREADY_WALKING, LocomotionDriver.Outcome.MIMIC_ACTIVE ->
                            rejectTag(rawCue, TagCapabilityRegistry.Reason.BUSY)
                        LocomotionDriver.Outcome.LOAD_FAILED -> rejectTag(rawCue, TagCapabilityRegistry.Reason.RESOURCE_LOAD_FAILED)
                        else -> rejectTag(rawCue, TagCapabilityRegistry.Reason.UNAVAILABLE)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun tagKind(cue: TagCue): String = when (cue) {
        is TagCue.Emotion -> "emo"
        is TagCue.Action -> "act"
        is TagCue.Camera -> "cam"
        is TagCue.Move -> "move"
        is TagCue.ToolCallText -> "tool"
        is TagCue.InvalidControl -> "protocol"
    }

    private fun tagRaw(cue: TagCue): String = when (cue) {
        is TagCue.Emotion -> cue.raw
        is TagCue.Action -> cue.raw
        is TagCue.Camera -> cue.raw
        is TagCue.Move -> cue.raw
        is TagCue.ToolCallText -> cue.raw
        is TagCue.InvalidControl -> cue.raw
    }

    private fun rejectTag(cue: TagCue, reason: TagCapabilityRegistry.Reason) {
        Log.w("AvatarSession", "${STREAM_PREFIX}tag rejected: ${tagRaw(cue)} reason=$reason")
        emit(AvatarEvent.TagRejected(tagKind(cue), tagRaw(cue), reason))
    }

    private fun reportUnknownTag(kind: String, raw: String) {
        Log.w("AvatarSession", "${STREAM_PREFIX}unknown $kind tag filtered (not spoken): $raw")
        emit(AvatarEvent.UnknownTag(kind, raw))
    }

    private fun submitSentence(sentence: String, ttsCfg: TtsConfig) {
        if (sentence.isBlank()) return
        val sequence = pipeline.submit(sentence, ttsCfg)
        // 标签修饰其后的句子：挂到这句的序号上，开播时应用（见 [pendingEmotions]）。
        pendingEmotions.takeIf { it.isNotEmpty() }?.let {
            emotionsBySequence[sequence] = it.toList()
            it.clear()
        }
        emit(AvatarEvent.SentenceQueued(sequence, sentence))
    }

    /** 回合收尾：仍没等到句子的表情 cue（句尾标签）立即应用。 */
    private fun flushTrailingEmotion() {
        if (pendingEmotions.isEmpty()) return
        val cues = ArrayList(pendingEmotions)
        pendingEmotions.clear()
        applyEmotionCues(faceDriver, cues, FaceDriver.DEFAULT_EMOTION_HOLD_MS)
            ?.let { emit(AvatarEvent.EmotionChanged(it)) }
    }

    /**
     * 开播时应用一句话的表情 cue 序列。全部同名（协议教的眨眼/开合模式
     * `<emo:x:1>` 紧跟 `<emo:x:0>`）→ 首个立即应用（hold 覆盖整句），后续
     * 按 [EMOTION_STEP_MS] 步进补发（不重置归零计时）；混有不同名 → 句尾
     * 独立情绪只保留最后一个，但同名开始/复位序列始终按顺序保留。返回 t=0 应用
     * 的 cue（供发 EmotionChanged 事件），无 cue/无驱动器返回 null。
     */
    private fun applyEmotionCues(
        faceDriver: FaceDriver?,
        cues: List<EmotionCue>,
        holdMs: Long,
    ): EmotionCue? {
        val fd = faceDriver ?: return null
        if (cues.isEmpty()) return null
        val (first, deferred) = splitEmotionRun(cues)
        fd.applyEmotion(first, holdMs)
        deferred.forEachIndexed { index, cue ->
            scope.launch(Dispatchers.Main.immediate) {
                delay(EMOTION_STEP_MS * (index + 1))
                fd.applyEmotion(cue, -1)
                emit(AvatarEvent.EmotionChanged(cue))
            }
        }
        return first
    }

    /**
     * 每轮请求注入当前镜头视角（需求 2：任何模式的提示词都告知大模型所在
     * 视角，让 <cam:> 建议与描述符合用户实际看到的取景）。headless（无
     * controller）不注入。视角行是请求里唯一逐轮变化的指令，挂在末尾 user
     * 消息上（只改本轮请求的副本，store 始终存干净文本）：离生成位置最近，
     * 且不破坏 [人设][协议][历史] 前缀的逐字节稳定（前缀缓存友好）。
     */
    private fun currentViewLine(): String? {
        val c = controller ?: return null
        val shot = c.getActiveCameraShot()
        return if (shot != null) texts.viewLine(shot.label(options.lang), shot.name)
        else texts.viewLineFree
    }

    /** Revalidate old tags against today's model, catalog and capability switches. */
    private fun projectAssistantHistory(text: String): String {
        val registry = capabilityRegistry()
        return InlineTagHistoryProjector.project(text, registry::canonicalTag)
    }

    private var actionStartedThisTurn = false

    /**
     * 本工具轮内已通过标签通道分派了身体动作（move 标签起步 / act 标签开播）。
     * 悬空「动作承诺句」判定用它做互斥：模型用 `<move:x>` 起步再说「这就过去」
     * 是正常搭配（2026-10-24 真机 B2 实测，配套承诺句再触发 required 重试反而
     * 把好回合炸成 400），承诺没兑现才算悬空。每轮开头重置。
     */
    private var embodimentDispatchedThisRound = false
    private var candidateInput = ""
    private var previousAction: String? = null
    private var turnPreviousAction: String? = null

    private fun prepareCapabilities(input: String) {
        actionStartedThisTurn = false
        candidateInput = input
        // Only successfully executed actions can seed repeat; raw audit history is not authorization.
        turnPreviousAction = previousAction
        selectedActions = ActionCandidateSelector.select(actionCatalog, input, turnPreviousAction)
    }

    private var selectedActions: ActionCandidateSelector.Selection? = null

    private fun liveActionSelection(): ActionCandidateSelector.Selection {
        val advertised = selectedActions ?: ActionCandidateSelector.select(actionCatalog, candidateInput, turnPreviousAction)
        // Freeze additions until the next request, but revoke removed/replaced assets immediately.
        return ActionCandidateSelector.Selection(
            advertised.common.filterValues { it in actionCatalog },
            advertised.candidates.filterValues { it in actionCatalog },
        )
    }

    /** 测试观测缝（P2-4）：等挂在飞行的后台摘要任务收尾（无任务立即返回）。 */
    internal suspend fun awaitHistorySummaryForTest() {
        historySummaryJob?.join()
    }

    /** 摘要注入块（system 段）：无摘要或未溢出返回 null。 */
    private fun historySummaryBlock(historySize: Int): String? {
        val summary = historySummary ?: return null
        val limit = options.recentTurnLimit ?: return null
        if (historySize <= limit) return null
        return "${texts.historySummaryHeader}\n${summary.trim()}"
    }

    /**
     * 滚动摘要触发（P2-4）：新增溢出消息（超出 recentTurnLimit 且未被当前
     * 摘要覆盖的部分）达 [Options.historySummaryEveryMessages] 时，后台调一次
     * 当前 provider 的 LLM 把「旧摘要 + 新溢出段」合并成新摘要。fire-and-forget：
     * 失败只打日志并保留旧摘要（下轮溢出再触发重试）；进行中则跳过。
     */
    private fun maybeSummarizeHistory() {
        if (!options.historySummaryEnabled) return
        val limit = options.recentTurnLimit ?: return
        if (historySummaryJob?.isActive == true) return
        val history = store.messages()
        val overflow = history.size - limit
        if (overflow <= 0 || overflow - historySummaryCovers < options.historySummaryEveryMessages) return
        val previous = historySummary
        val slice = history.subList(historySummaryCovers.coerceAtMost(overflow), overflow)
        val transcript = slice.joinToString("\n") { m ->
            // 语言中立前缀（摘要指令本身随语言，转写不掺中文）
            val who = if (m.role == ChatRole.ASSISTANT) "[assistant]" else "[user]"
            "$who ${m.content.take(600)}"
        }.take(12_000)
        val cfg = llmConfig ?: return
        val adapter = llm ?: return
        Log.i(
            PROMPT_TAG,
            "$STREAM_PREFIX history summary: covering ${slice.size} new overflow message(s) " +
                "(overflow=$overflow covers=$historySummaryCovers prev=${previous?.length ?: 0}ch)",
        )
        historySummaryJob = scope.launch {
            try {
                val sb = StringBuilder()
                adapter.streamChat(
                    listOf(
                        ChatMessage(ChatRole.SYSTEM, texts.historySummaryInstruction(previous, transcript)),
                        ChatMessage(ChatRole.USER, "请输出合并后的摘要。"),
                    ),
                    cfg.copy(tools = emptyList(), toolChoice = null, maxTokens = 1024),
                ).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> sb.append(event.text)
                        is LlmStreamEvent.Error -> throw event.throwable
                        else -> Unit
                    }
                }
                val merged = sb.toString().trim()
                if (merged.isEmpty()) throw IllegalStateException("empty summary reply")
                historySummary = merged.take(options.historySummaryMaxChars)
                historySummaryCovers = overflow
                Log.i(PROMPT_TAG, "$STREAM_PREFIX history summary updated: ${historySummary!!.length}ch covers=$overflow")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 失败保留旧摘要（covers 不动）：下轮新增溢出再触发重试
                Log.w("AvatarSession", "$STREAM_PREFIX history summary failed, keeping old: ${t.message}")
            }
        }
    }

    /** Rebuild from live assets so a removed model/anchor cannot survive in a cached allowlist. */
    private fun capabilityRegistry(): TagCapabilityRegistry = TagCapabilityRegistry(
        emotions = texts.emotionNames.map { it.substringBefore('(') }.toSet(),
        directExpressions = faceDriver?.availableExpressions?.filter { it.isNotBlank() }?.sorted().orEmpty(),
        selection = liveActionSelection(),
        allActions = actionCatalog,
        cameras = if (options.enableLlmCamera && controller != null) texts.cameraTags.filter { it.first in TagCapabilityRegistry.cameraShots } else emptyList(),
        moves = (locomotionDriver?.anchors ?: locomotionAnchors).map { it.name to it.labelFor(options.lang) },
        gesturesEnabled = options.enableLlmGestures,
        gesturesAvailable = gestureDriver != null,
        cameraEnabled = options.enableLlmCamera,
        cameraAvailable = controller != null,
        movesEnabled = options.enableLlmMove,
        movesAvailable = locomotionDriver != null,
    )

    private fun buildRequestMessages(
        turnImages: List<String>,
        memoryLine: String? = null,
    ): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        // Chat Completions 无状态：每次请求都带当前完整身份、目录和工具说明。
        // 保持单条 system 及稳定前缀，动态信息挂在末尾 user 副本上。
        val history = store.messages()
        val persona = systemPrompt.trim()
        val protocol = pinnedProtocolText()
        // 世界书（酒馆 P1-1）：扫描最近 N 条用户消息（历史里末条 user 即当前
        // 输入），命中条目注入 system **末尾**——mission/人设/协议是逐字节稳定
        // 的缓存前缀（protocol pin），逐轮变化的世界书段放其后不破坏。
        val loreBlock = if (lorebookEnabled) LorebookInjector.inject(
            lorebook,
            history.filter { it.role == ChatRole.USER }.map { it.content },
            LorebookInjector.Config(header = texts.lorebookHeader),
        ) else null
        // 历史摘要（P2-4）：超出 recentTurnLimit 的早期剧情滚动摘要，拼进
        // system 末尾（世界书段之前）——与 lore 同属逐轮可能变化的动态段，
        // 不进稳定前缀（protocol pin）；单条 system 消息保持多家端点兼容。
        val summaryBlock = historySummaryBlock(history.size)
        // 关系状态行（P3-1）：tracker 的判定结果优先，手动喂的
        // [relationshipLine] 兜底（无 store 接入方）。
        val relationshipBlock = relationship?.promptLine ?: relationshipLine
        val systemText = listOf(
            missionText,
            persona,
            protocol.orEmpty(),
            summaryBlock.orEmpty(),
            relationshipBlock.orEmpty(),
            loreBlock.orEmpty(),
        )
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        if (systemText.isNotEmpty()) list += ChatMessage(ChatRole.SYSTEM, systemText)
        // mes_example few-shot（P1-3）：插在 system 之后、历史之前
        //（SystemPromptAssembler 预留的位置——示例对话对位置敏感）。
        val fewshot = if (systemText.isNotEmpty()) {
            CardPromptExtras.parse(activeCard ?: CharacterCard(name = ""), texts.userAlias)
        } else {
            emptyList()
        }
        list += fewshot
        // 历史裁剪（任务 3）：store 里只有 user/assistant；人设/协议两段
        // 都不进 store，天然不受 recentTurnLimit 影响。
        list += (options.recentTurnLimit?.let { history.takeLast(it) } ?: history).map {
            if (it.role == ChatRole.ASSISTANT) it.copy(content = projectAssistantHistory(it.content)) else it
        }
        // post_history_instructions（P1-3，ST 的 UJB 位置）+ 视角行 + 记忆参考块
        // + 技能指令行 + 圆桌发言权行 + 本轮抓拍（视频模式）：都挂到刚 append 的
        // 末尾 user 消息上——请求里带，store 里的历史始终只有干净文字（见 send
        // 的注释）。排序 viewLine→memoryLine→skillLine→phiLine→roundtableLine：
        // 越靠后离生成位置越近（§7.10 近因效应），圆桌的「轮到你发言」是本轮
        // 最硬的指令，放最末。
        val viewLine = currentViewLine()
        val skillLine = skills.activeDirective()
        val roundtableLine = this.roundtableLine
        val phiLine = activeCard?.let { CardPromptExtras.postHistoryInstructions(it, texts.userAlias) }
        val directivePrefix =
            listOfNotNull(viewLine, memoryLine, skillLine, phiLine, roundtableLine).joinToString("\n\n")
        if ((directivePrefix.isNotEmpty() || turnImages.isNotEmpty()) && list.lastOrNull()?.role == ChatRole.USER) {
            val last = list.last()
            list[list.size - 1] = last.copy(
                content = if (directivePrefix.isNotEmpty()) "$directivePrefix\n\n${last.content}" else last.content,
                images = turnImages,
            )
        }
        // 需求 4：提示词落到专用 tag（LlmPrompt），分段绕开 logcat 单条上限。
        // thinking= 是否显式关闭（关思考的参数各家不同：火山 thinking.type /
        // 硅基流动 enable_thinking，见 AiProviders.llmExtraBody）
        val eb = llmConfig?.extraBody
        val thinkingOff = eb?.containsKey("thinking") == true || eb?.containsKey("enable_thinking") == true
        val sysLog = if (systemText.isNotEmpty()) "full" else "off"
        val personaLog = "${persona.length}ch"
        val protocolLog = protocol?.let { "${it.length}ch" } ?: "off"
        Log.i(
            PROMPT_TAG,
            "${STREAM_PREFIX}=== REQUEST model=${llmConfig?.model} thinking=${if (thinkingOff) "off" else "default"} " +
                "view=${viewLine ?: "n/a"} mem=${memoryLine?.length ?: 0}ch skill=${skillLine?.length ?: 0}ch " +
                "rt=${roundtableLine?.length ?: 0}ch lore=${loreBlock?.length ?: 0}ch sum=${summaryBlock?.length ?: 0}ch rel=${relationshipBlock?.length ?: 0}ch phi=${phiLine?.length ?: 0}ch " +
                "fewshot=${fewshot.size} " +
                "tools=${requestToolCount()} " +
                "sys=$sysLog persona=$personaLog protocol=$protocolLog " +
                "history=${history.size} sent=${trimmedSent(history)} images=${turnImages.size} ===",
        )
        list.lastOrNull()
            ?.takeIf { it.role == ChatRole.USER }
            ?.let { Log.i(PROMPT_TAG, "${STREAM_PREFIX}REQUEST user: ${it.content}${if (it.images.isEmpty()) "" else " (+${it.images.size} image)"}") }
        // 完整请求日志用于核对当轮模型实际收到的目录。
        if (systemText.isNotEmpty()) logChunked(PROMPT_TAG, "REQUEST system", systemText)
        Log.i(
            "AvatarSession",
            "${STREAM_PREFIX}prompt: sys=$sysLog persona=$personaLog protocol=$protocolLog, " +
                "cameras=${currentCameraTags().size}, actions=${currentActionGroups().sumOf { it.second.size }}, " +
                "directExpr=${currentDirectExpressions().size}, history=${history.size} " +
                "sent=${trimmedSent(history)} images=${turnImages.size}",
        )
        return list
    }

    /**
     * 协议文本缓存：常用能力和工具说明保持稳定前缀，本轮候选追加在末尾。
     * 目录、资产映射或候选变化时更新版本日志；内容相同时复用缓存文本。
     * 本地缓存只负责文本复用，不代表服务端记住了目录；每次请求都发送全文。
     * LLM 模型切换的「新开上下文」由集成方轮换上下文 id 实现（demo 见
     * MainActivity）。
     *
     * 返回协议文本；null = 协议关闭或目录为空。
     */
    private fun pinnedProtocolText(): String? {
        val full = composeProtocolText() ?: return null
        val version = capabilityRegistry().version
        if (full != pinnedProtocol || version != pinnedCapabilityVersion) {
            pinnedProtocol = full
            pinnedCapabilityVersion = version
            Log.i(
                "AvatarSession",
                "$STREAM_PREFIX protocol pinned: ${full.length} chars, capabilities=$version, " +
                    "cameras=${currentCameraTags().size}, " +
                    "actions=${currentActionGroups().sumOf { it.second.size }}, " +
                    "directExpr=${currentDirectExpressions().size}" +
                    (if (currentMoveTags().isNotEmpty()) ", moves=${currentMoveTags().size}" else "") +
                    (if (options.agentToolsEnabled && !tools.isEmpty()) ", tools=${tools.list().size}" else ""),
            )
        }
        return pinnedProtocol
    }

    /**
     * 协议块全文合成（钉住层与设置页只读展示共用）：多模态标签目录 + 工具使用
     * 说明段（§4.8，同一条 system——部分服务商拒绝多条 system）。工具集合或
     * hint 变化 → 文本变化 → 原地重钉（历史保留，前缀缓存语义不破坏）。
     * null = 协议关闭或两段皆空。
     */
    private fun composeProtocolText(): String? {
        if (!options.protocolInstructions) return null
        val registry = capabilityRegistry()
        val block = options.assembler.multimodalProtocolBlock(
            cameras = currentCameraTags(),
            actionGroups = if (registry.gesturesEnabled && registry.gesturesAvailable) registry.selection.common.entries.groupBy { it.value.category }
                .map { (category, entries) -> category to entries.map { it.key } } else emptyList(),
            directExpressions = currentDirectExpressions(),
            moves = currentMoveTags(),
            allowedEmotionNames = texts.emotionNames.filter { it.substringBefore('(') in registry.emotions },
        )
        val toolset = toolsetBlockText()
        val candidates = registry.selection.candidates.keys.takeIf { registry.gesturesEnabled && registry.gesturesAvailable && it.isNotEmpty() }?.let {
            texts.actionCandidates(it.toList())
        }
        return listOfNotNull(block.takeIf { it.isNotBlank() }, toolset, candidates)
            .joinToString("\n\n")
            .ifBlank { null }
    }

    /**
     * 工具使用说明块（§4.8）：非空 systemHint 聚合渲染；空注册表/总开关关闭
     * 时为 null（协议块不含工具段，与既有形态一致）。
     */
    private fun toolsetBlockText(): String? {
        if (!options.agentToolsEnabled) return null
        val hints = tools.systemHints()
        if (hints.isEmpty()) return null
        return texts.toolsetBlock(hints, options.agentMaxToolRounds)
    }

    /** 实际发送的历史条数（观测点用）：null 上限 = 全量。 */
    private fun trimmedSent(history: List<ChatMessage>): Int =
        options.recentTurnLimit?.let { minOf(it, history.size) } ?: history.size

    /** REQUEST 头行的 tools 段：本轮将携带的工具数；总开关关闭恒 0。 */
    private fun requestToolCount(): Int =
        if (options.agentToolsEnabled) tools.list().size else 0

    /** RESPONSE 头行的 token 用量段：`tok: prompt=A cached=B completion=C`，未上报时缺省。 */
    private fun usagePart(usage: LlmUsage?): String = when (usage) {
        null -> ""
        else -> " tok: prompt=${usage.promptTokens} cached=${usage.cachedTokens ?: -1} completion=${usage.completionTokens}"
    }

    /** 设置页与每轮请求共用当前完整协议（含工具说明）。 */
    fun protocolBlock(): String = composeProtocolText().orEmpty()

    private fun currentCameraTags(): List<Pair<String, String>> = capabilityRegistry().cameras

    private fun currentMoveTags(): List<Pair<String, String>> = capabilityRegistry().let {
        if (it.movesEnabled && it.movesAvailable) it.moves else emptyList()
    }

    private fun currentActionGroups(): List<Pair<String, List<String>>> = capabilityRegistry().actions.entries
        .groupBy { it.value.category }.map { (category, entries) -> category to entries.map { it.key } }

    private fun currentDirectExpressions(): List<String> = capabilityRegistry().directExpressions

    private fun emit(event: AvatarEvent) {
        _events.tryEmit(event)
    }

    /**
     * 长文本按 ~3.2K 字符分段打日志（logcat 单条有上限），换行换成字面 `\n`
     * 让每段保持单行——`adb logcat -s LlmPrompt` 可整段读回。
     */
    private fun logChunked(tag: String, header: String, body: String) {
        if (body.isEmpty()) {
            Log.i(tag, "$STREAM_PREFIX$header <empty>")
            return
        }
        val chunkSize = 3_200
        val parts = (body.length + chunkSize - 1) / chunkSize
        var start = 0
        var part = 1
        while (start < body.length) {
            val end = minOf(start + chunkSize, body.length)
            Log.i(tag, "$STREAM_PREFIX$header [$part/$parts] ${body.substring(start, end).replace("\n", "\\n")}")
            start = end
            part++
        }
    }

    companion object {
        /** 提示词/回复的专用排查日志 tag（需求 4/5）：`adb logcat -s LlmPrompt`。 */
        private const val PROMPT_TAG = "LlmPrompt"

        /** 问答链路日志的统一前缀（用户 grep 用，覆盖提示词/回复/实时播放事件）。 */
        private const val STREAM_PREFIX = "[InfoStreamDectect] "

        /** 工具结果回灌的最大字符数：VLM 描述/检索类结果可能爆上下文（截断不炸轮）。 */
        internal const val MAX_TOOL_RESULT_CHARS = 4_000

        /**
         * 每轮请求恒带的身份/任务/表达纪律前言（[Options.identityPreamble]
         * 缺省值；用户需求 3/4：模型曾把人设里的旁白/心理描写当台词念出来，
         * 且不清楚自己是虚拟人）。两段都极短——它们每轮都在请求里。
         * 多语言后正本在 com.neethu.orchestrator.i18n.PromptTexts（这里保留
         * 兼容引用，恒为中文版）。
         */
        val DEFAULT_IDENTITY_PREAMBLE: String
            get() = com.neethu.orchestrator.i18n.PromptTextsZh.identityPreamble

        /**
         * 协议遵循提醒：以本次请求的允许清单为准。兼容引用，正本见 PromptTexts。
         */
        val PROTOCOL_REMINDER: String
            get() = com.neethu.orchestrator.i18n.PromptTextsZh.protocolReminder

        /** 句子挂接表情的保持余量（clip 时长之上）：句尾留一段表情余韵再归零。 */
        private const val EMOTION_HOLD_MARGIN_MS = 1_200L

        /** 同名表情序列（眨眼模式）补发步长：闭→开的间隔≈一次眨眼。 */
        private const val EMOTION_STEP_MS = 200L

        /** Keep explicit same-name runs (e.g. blink on/reset), plus the last standalone mood. */
        internal fun splitEmotionRun(cues: List<EmotionCue>): Pair<EmotionCue, List<EmotionCue>> {
            require(cues.isNotEmpty())
            val keep = mutableSetOf<Int>()
            var lastStandalone: Int? = null
            var start = 0
            while (start < cues.size) {
                var end = start + 1
                while (end < cues.size && cues[end].name == cues[start].name) end++
                if (end - start > 1) keep.addAll(start until end) else lastStandalone = start
                start = end
            }
            lastStandalone?.let { keep += it }
            val sequence = keep.sorted().map { cues[it] }
            return sequence.first() to sequence.drop(1)
        }

    }
}
