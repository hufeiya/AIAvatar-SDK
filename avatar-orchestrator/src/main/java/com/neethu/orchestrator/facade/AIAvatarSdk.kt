package com.neethu.orchestrator.facade

import android.content.Context
import com.neethu.aiadapter.edge.EdgeTtsAdapter
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.openai.OpenAiCompatibleLlmAdapter
import com.neethu.aiadapter.openai.OpenAiCompatibleTtsAdapter
import com.neethu.aiadapter.volcengine.VolcanoEngineTtsAdapter
import com.neethu.corelib.AvatarController
import com.neethu.corelib.Lang
import com.neethu.orchestrator.card.CharacterCard
import com.neethu.orchestrator.history.ConversationDatabase
import com.neethu.orchestrator.history.ConversationStore
import com.neethu.orchestrator.history.RoomConversationStore
import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.session.ConversationPhase
import com.neethu.orchestrator.skill.SkillHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.asStateFlow

/**
 * 高阶门面：把「adapter 工厂 + 会话装配」收拢成一个入口——集成方不再自己
 * 挑 TTS/LLM adapter、拼 [LlmConfig]/[TtsConfig]，一条 [configure] 完成，
 * 之后 [send]/[speak] 即用：
 *
 * ```kotlin
 * val chat = AIAvatarSdk(lifecycleScope, avatarController, applicationContext)
 *
 * // 一把 Key 开聊：硅基流动目录 + 默认模型 + 默认音色（或 ChatProvider 里
 * // 任选一家；模型/音色留空 = 服务商默认，见 ChatProvider）
 * chat.configure(AIAvatarSdk.ChatConfig(apiKey = "sk-..."))
 *
 * // 免费 TTS（Edge-TTS，无需任何 Key）：只配大模型 Key，TTS 引擎切 EDGE
 * chat.configure(AIAvatarSdk.ChatConfig(apiKey = "sk-...", ttsEngine = TtsEngine.EDGE))
 *
 * chat.send("你好！")                 // LLM 流式 → 断句 → TTS → 播放 → 表情口型
 * ```
 *
 * 任意配置变化（key/模型/音色/语言/上下文 id…）在下一次 [configure] 时重建
 * 会话（AIRI getProviderInstance 的「凭据变化即重建实例」语义）；身份未变时
 * 是 no-op。配置不变、只换对话历史用 [ChatConfig.contextId] 轮换。
 *
 * 渲染侧照常由 [AvatarController] 驱动（Compose 用 `AvatarView`，传统 View
 * 用 `AvatarSurfaceView`）；模型加载完成后传 `modelReady = true` 再调一次
 * [configure]（或直接 `session?.startFaceDriving()`）以启动表情/口型驱动。
 *
 * 进阶定制（注入 TTS 适配器/播放队列/技能注册表/人设卡重载时机）直接操作
 * [session]，门面不拦。
 */
class AIAvatarSdk(
    private val scope: CoroutineScope,
    /** 渲染控制器：门面只转发，生命周期归集成方（AvatarView/AvatarSurfaceView）。 */
    val controller: AvatarController,
    /**
     * application context：默认 Room 对话库的持有者。注入 [storeFactory] 时
     * 可空（JVM 单测/纯内存场景）。
     */
    private val appContext: Context? = null,
    /** 技能框架能力缝；null = 无技能能力。 */
    private val skillHost: SkillHost? = null,
    /**
     * 对话存储工厂（contextId, characterId → store）；null = 默认 Room 持久化
     * （多上下文 + 跨进程）。集成方注入（如 [com.neethu.orchestrator.history.InMemoryConversationStore]）
     * 即接管存储。
     */
    private val storeFactory: ((contextId: String, characterId: String?) -> ConversationStore)? = null,
) {

    /**
     * 对话配置：[AIAvatarSdk] 的全部装配输入。data class 全量相等即会话身份
     * ——任何字段变化都触发 [AIAvatarSdk.configure] 重建。
     *
     * @property provider 服务商目录（端点/模型/音色清单与默认值的数据源）。
     * @property baseUrl 自定义 OpenAI 兼容端点；非空时覆盖 [provider] 的
     *   端点（自建/中转服务），模型/音色不再走目录校验、原样透传。
     * @property apiKey 大模型服务的 API Key。
     * @property llmModel 大模型名；留空 = 服务商默认（自定义端点必须显式给）。
     * @property ttsEngine TTS 引擎：[TtsEngine.EDGE] 免费无 Key，其余跟随
     *   下面的 TTS 服务商配置。
     * @property ttsProvider TTS 服务商；null = 跟随 [provider]（且自定义端点
     *   时 TTS 走同一端点）。
     * @property ttsApiKey 语音合成的 API Key；null = 与 [provider] 同商时共用
     *   [apiKey]。火山是两把互不通用的钥匙（大模型=方舟、合成=豆包语音），
     *   选火山时必填这个字段。
     * @property ttsModel TTS 模型名；留空 = 服务商默认。
     * @property voice 音色；留空 = 服务商默认（短名/完整引用/Edge 目录 id）。
     * @property lang 提示词与 TTS 错误文案语言；语言变化 = 重建会话。
     * @property enableLlmCamera 允许模型用 `<cam:…>` 标签切镜头。
     * @property contextId 对话上下文 id：换 id = 换一段历史（默认 Room 持久化
     *   下即换一条会话记录）。
     * @property characterId 信息性字段，随 Room 会话行落库。
     * @property temperature LLM 采样温度；略低于通用默认——行内表情标签协议
     *   对指令遵循敏感（真机实测 0.8 下模型偶尔完全忽略标签）。
     * @property recentTurnLimit 每轮请求携带的最近 user/assistant 历史条数
     *   （持久化上下文可无限增长；40 条 ≈ 20 轮）。
     */
    data class ChatConfig(
        val provider: ChatProvider = ChatProvider.SILICONFLOW,
        val baseUrl: String? = null,
        val apiKey: String = "",
        val llmModel: String = "",
        val ttsEngine: TtsEngine = TtsEngine.OPENAI_COMPATIBLE,
        val ttsProvider: ChatProvider? = null,
        val ttsApiKey: String? = null,
        val ttsModel: String = "",
        val voice: String = "",
        val lang: Lang = Lang.ZH,
        val enableLlmCamera: Boolean = true,
        val contextId: String = DEFAULT_CONTEXT_ID,
        val characterId: String? = null,
        val temperature: Float = 0.6f,
        val recentTurnLimit: Int = 40,
    ) {
        /** 自定义端点模式：模型/音色透传，不走目录校验。 */
        val isCustomEndpoint: Boolean
            get() = !baseUrl.isNullOrBlank()

        /** 大模型实际请求端点。 */
        val llmBaseUrl: String
            get() = baseUrl?.takeIf { it.isNotBlank() } ?: provider.baseUrl

        /** TTS 实际生效的服务商（显式指定优先；自定义端点未指定时跟随 LLM 端点）。 */
        val ttsProviderResolved: ChatProvider
            get() = ttsProvider ?: provider

        /** TTS 实际请求端点：显式 TTS 服务商用其端点，否则跟随大模型端点。 */
        val ttsBaseUrl: String
            get() = if (ttsProvider != null) ttsProvider.baseUrl else llmBaseUrl

        /** 语音合成实际生效的 Key：显式指定优先；同商共用大模型 Key，异商必须显式。
         *  ⚠ 火山是两把互不通用的钥匙（大模型=方舟、合成=豆包语音）——即使同商
         *  也不共用，[ttsApiKey] 必填。 */
        val ttsKeyResolved: String
            get() {
                ttsApiKey?.takeIf { it.isNotBlank() }?.let { return it }
                if (ttsProviderResolved == ChatProvider.VOLCANO) return ""
                return if (ttsProviderResolved == provider) apiKey else ""
            }

        /** 大模型解析后的模型名（目录校验 + 默认；自定义端点透传）。 */
        val resolvedLlmModel: String
            get() = if (isCustomEndpoint) llmModel.trim() else resolveLlmModel(provider, llmModel)

        /** TTS 解析后的模型名（Edge 引擎用占位值——端点不校验 model）。 */
        val resolvedTtsModel: String
            get() = when {
                ttsEngine == TtsEngine.EDGE -> EDGE_TTS_MODEL
                ttsProvider != null -> resolveTtsModel(ttsProvider, ttsModel)
                isCustomEndpoint -> ttsModel.trim()
                else -> resolveTtsModel(provider, ttsModel)
            }

        /** TTS 解析后的音色（校验 + 默认；自定义端点透传）。 */
        val resolvedVoice: String
            get() = when {
                ttsEngine == TtsEngine.EDGE -> resolveEdgeVoice(voice, lang)
                ttsProvider != null -> resolveVoice(ttsProvider, voice)
                isCustomEndpoint -> voice.trim()
                else -> resolveVoice(provider, voice)
            }

        /** TTS 侧就绪：Edge 免费；OpenAI 兼容需要 Key + 非空模型名。 */
        val ttsReady: Boolean
            get() = when (ttsEngine) {
                TtsEngine.EDGE -> true
                TtsEngine.OPENAI_COMPATIBLE ->
                    ttsKeyResolved.isNotBlank() && resolvedTtsModel.isNotBlank()
            }

        /** 配置完整性：大模型 Key + 可解析的模型名 + TTS 就绪。 */
        val isConfigured: Boolean
            get() = apiKey.isNotBlank() && resolvedLlmModel.isNotBlank() && ttsReady

        companion object {
            /** 缺省上下文 id：单一历史的最简接入。 */
            const val DEFAULT_CONTEXT_ID = "default"

            /** Edge-TTS 的 TtsConfig.model 占位（微软朗读接口不校验模型名）。 */
            const val EDGE_TTS_MODEL = "edge-readaloud"
        }
    }

    /** 当前生效的配置；null = 尚未 configure 过（或上次未配置完整）。 */
    var config: ChatConfig? = null
        private set

    private val _session = MutableStateFlow<AvatarSession?>(null)

    /** 当前会话；未配置完整时为 null。进阶定制直接操作它（门面不拦）。 */
    val session: AvatarSession? get() = _session.value

    /**
     * 事件流：当前会话的 [AvatarEvent]（会话重建后自动接到新会话上）。
     * 未配置时为空流。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events: Flow<AvatarEvent> =
        _session.flatMapLatest { it?.events ?: emptyFlow() }

    /** 对话阶段（当前会话的 THINKING/SPEAKING/IDLE），未配置时恒 IDLE。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val phase: Flow<ConversationPhase> =
        _session.flatMapLatest { it?.phase ?: flowOf(ConversationPhase.IDLE) }

    /** 上次成功装配时的配置（身份判据）；未装配过为 null。 */
    private var builtFor: ChatConfig? = null

    private var faceDrivingStarted = false

    /** 门面持有的卡片人设：会话重建后自动重放（否则人设被 rebuild 冲掉）。 */
    private var card: CharacterCard? = null

    /**
     * 确保 [config] 对应的会话存活并返回它。配置变化 → 关旧建新（人设卡经
     * [setCharacterCard] 设过的自动重放）；身份未变且会话存活 → no-op。
     *
     * @param modelReady 渲染侧模型已加载（[AvatarController.state] 变
     *   Ready）：为 true 时启动面部驱动（口型/表情/眨眼）。模型晚于首次
     *   configure 就绪时，再以 true 调一次即可。
     * @return 配置不完整（缺 Key 等，见 [ChatConfig.isConfigured]）时返回
     *   null 且不动现有会话。
     */
    fun configure(config: ChatConfig, modelReady: Boolean = false): AvatarSession? {
        if (!config.isConfigured) return null
        val existing = _session.value
        if (existing != null && builtFor == config) {
            if (modelReady && !faceDrivingStarted) {
                existing.startFaceDriving()
                faceDrivingStarted = true
            }
            this.config = config
            return existing
        }
        existing?.close()

        val store = storeFactory?.invoke(config.contextId, config.characterId) ?: run {
            val ctx = requireNotNull(appContext) {
                "AIAvatarSdk needs an application context for the default Room store; " +
                    "pass one to the constructor or inject storeFactory"
            }
            RoomConversationStore.from(
                ConversationDatabase.getInstance(ctx),
                sessionId = config.contextId,
                characterId = config.characterId,
            )
        }

        val llm = OpenAiCompatibleLlmAdapter(config.llmBaseUrl, config.apiKey)
        val tts = when (config.ttsEngine) {
            // Edge-TTS：微软朗读接口免费无 Key；输出恒 MP3 24kHz，
            // orchestrator 的 PcmDecoder MediaCodec 路径直接可解
            TtsEngine.EDGE -> EdgeTtsAdapter(texts = edgeTtsTexts(config.lang))
            TtsEngine.OPENAI_COMPATIBLE -> when (val ttsProvider = config.ttsProviderResolved) {
                // 硅基流动走 OpenAI 兼容 /audio/speech（wav 16k，两 TTS 模型实测可用）
                ChatProvider.SILICONFLOW ->
                    OpenAiCompatibleTtsAdapter(config.ttsBaseUrl, config.ttsKeyResolved)
                // 火山 seed-tts-2.0 只提供 V3 双向流式 WebSocket；适配器对外仍是
                // 句级整段语义，输出恒为 PCM 16k（口型管线免解码直喂）
                ChatProvider.VOLCANO ->
                    VolcanoEngineTtsAdapter(config.ttsKeyResolved, texts = volcanoTtsTexts(config.lang))
                // OpenRouter 同一 OpenAI 兼容 schema，适配器直接复用
                ChatProvider.OPENROUTER ->
                    OpenAiCompatibleTtsAdapter(config.ttsBaseUrl, config.ttsKeyResolved)
            }
        }
        val session = AvatarSession(
            scope, llm, tts, controller,
            AvatarSession.Options(
                lang = config.lang,
                enableLlmCamera = config.enableLlmCamera,
                // Room 持久化后上下文可无限增长；请求只带最近 N 条 user/assistant。
                // 身份前言每轮恒带；人设全文+协议目录（~12K chars）只随上下文
                // 首轮发送、失败自动重发（AvatarSession 内聚），都不进 store。
                recentTurnLimit = config.recentTurnLimit,
            ),
            store = store,
            skillHost = skillHost,
        )
        session.llmConfig = LlmConfig(
            baseUrl = config.llmBaseUrl,
            apiKey = config.apiKey,
            model = config.resolvedLlmModel,
            temperature = config.temperature,
            // 关深度思考（首句延迟治理）：火山 doubao-seed 系与硅基流动 Qwen3 系
            // 默认都开思考，思考 token 全成首句前的隐形等待；自定义端点不做
            // 服务商假设，不带额外参数
            extraBody = if (config.isCustomEndpoint) null else llmExtraBody(config.provider, config.resolvedLlmModel),
        )
        session.ttsConfig = when (config.ttsEngine) {
            TtsEngine.EDGE ->
                // mp3 容器自带采样率（24kHz），无需指定；voice 不在目录即落默认
                TtsConfig(
                    model = ChatConfig.EDGE_TTS_MODEL,
                    voice = config.resolvedVoice,
                    responseFormat = "mp3",
                )
            TtsEngine.OPENAI_COMPATIBLE -> when (config.ttsProviderResolved) {
                // OpenRouter /audio/speech 只认 mp3/pcm（wav 请求会 400）；
                // mp3 容器自带采样率（Voxtral 实测 22.05kHz），不传 sampleRate，
                // 解码与口型走 Edge-TTS 同款 MP3→MediaCodec 路径
                ChatProvider.OPENROUTER ->
                    TtsConfig(
                        model = config.resolvedTtsModel,
                        voice = config.resolvedVoice,
                        responseFormat = "mp3",
                    )
                else ->
                    TtsConfig(
                        model = config.resolvedTtsModel,
                        voice = config.resolvedVoice,
                        responseFormat = "wav",
                        // wLipSync 标定输入是 16kHz；CosyVoice2 默认 24kHz 会走 MFCC
                        // 前端的分数降采样路径，实测口型得分塌缩（见 docs/ai-layer-handoff.md 附录A）
                        sampleRate = 16_000,
                    )
            }
        }
        card?.let { session.setCharacterCard(it) }
        if (modelReady) {
            session.startFaceDriving()
            faceDrivingStarted = true
        } else {
            faceDrivingStarted = false
        }
        _session.value = session
        builtFor = config
        this.config = config
        return session
    }

    /**
     * 模型渲染就绪时调用一次（[AvatarController.state] 变 Ready 后）：启动
     * 当前会话的面部驱动。等价于 `configure(config, modelReady = true)` 的
     * no-op 便捷路径；无会话时是安全 no-op。
     */
    fun notifyModelReady() {
        val s = _session.value ?: return
        if (!faceDrivingStarted) {
            s.startFaceDriving()
            faceDrivingStarted = true
        }
    }

    /**
     * Load persona from a character card. Kept by the facade: config-change
     * rebuilds re-apply it to the fresh session automatically.
     */
    fun setCharacterCard(card: CharacterCard) {
        this.card = card
        _session.value?.setCharacterCard(card)
    }

    /** Drop the card persona (also forgotten across rebuilds). */
    fun clearCharacterCard() {
        card = null
        _session.value?.clearCharacterCard()
    }

    /**
     * Fire-and-forget user turn — events land on [events]. Requires a
     * configured session ([configure] first).
     * @throws IllegalStateException 未 [configure] 或配置不完整时。
     */
    fun send(text: String, images: List<String> = emptyList()) {
        val s = checkNotNull(_session.value) {
            "AIAvatarSdk not configured — call configure(ChatConfig) with a complete config first"
        }
        s.send(text, images)
    }

    /**
     * Speak [text] directly (no LLM turn) through the configured TTS —
     * greetings, announcements; suspends until playback settles.
     * @throws IllegalStateException 未 [configure] 或配置不完整时。
     */
    suspend fun speak(text: String) {
        val s = checkNotNull(_session.value) {
            "AIAvatarSdk not configured — call configure(ChatConfig) with a complete config first"
        }
        s.speak(text)
    }

    /** 三层打断（LLM 流 + 在途 TTS + 播放），阶段回 IDLE。 */
    fun interrupt() {
        _session.value?.interrupt()
    }

    /** 清空当前上下文的历史（新对话）。 */
    fun clearHistory() {
        _session.value?.clearHistory()
    }

    /**
     * 关闭当前会话并复位门面（配置/卡片/面部驱动标记）。之后可再次
     * [configure] 重开；渲染控制器不受影响。
     */
    fun close() {
        _session.value?.close()
        _session.value = null
        builtFor = null
        config = null
        faceDrivingStarted = false
    }
}
