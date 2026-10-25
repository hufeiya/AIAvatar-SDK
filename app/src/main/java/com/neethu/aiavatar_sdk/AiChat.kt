package com.neethu.aiavatar_sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.neethu.aiadapter.api.EmbeddingConfig
import com.neethu.aiadapter.openai.OpenAiCompatibleEmbeddingAdapter
import com.neethu.corelib.AvatarController
import com.neethu.corelib.Lang
import com.neethu.orchestrator.facade.AIAvatarSdk
import com.neethu.orchestrator.facade.ChatProvider
import com.neethu.orchestrator.memory.MemoryStore
import com.neethu.orchestrator.memory.RoomMemoryStore
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.skill.SkillHost
import kotlinx.coroutines.CoroutineScope

/**
 * AI 对话配置（多服务商版：硅基流动/火山引擎/OpenRouter/DeepSeek 官方）。除
 * API Key 外全部下拉框选择，模型/音色留空即用服务商默认（解析正本在 SDK 目录
 * [ChatProvider]，见 [resolveLlmModel]/[resolveTtsModel]/[resolveVoice]）。
 *
 * Key 按服务分存（硅基流动与 OpenRouter 一把通用；**火山的语音与大模型是两把
 * key**：[apiKeyVolcano]=方舟 Ark 大模型，[apiKeyVolcanoTts]=豆包语音合成，实测
 * 互不通用；DeepSeek 只有 chat 一把 key，TTS 自动回落 Edge 见
 * [AiChatPrefs.ttsEngineResolved]）；TTS 可独立选服务商（[ttsSameProvider]=false
 * 时用 [ttsProvider]）。
 */
data class AiChatPrefs(
    val provider: AiProvider = AiProvider.SILICONFLOW,
    val llmModel: String = "",
    val apiKeySiliconflow: String = "",
    /** 火山方舟（Ark）API Key——火山大模型链路。 */
    val apiKeyVolcano: String = "",
    /** 豆包语音控制台 API Key——火山语音合成（seed-tts-2.0 WebSocket）链路。 */
    val apiKeyVolcanoTts: String = "",
    /** OpenRouter API Key——大模型/TTS/ASR 一把通用（面向海外用户）。 */
    val apiKeyOpenrouter: String = "",
    /** DeepSeek 官方 API Key——只有 chat（TTS/ASR 官方无端点，见上）。 */
    val apiKeyDeepseek: String = "",
    /** 阿里云百炼 API Key——一把通吃大模型/TTS/ASR（chat 专属端点三端通用）。 */
    val apiKeyBailian: String = "",
    /**
     * 自定义端点 baseUrl（酒馆 P1-2，OpenAI 兼容，如中转站/本地 ollama）。
     * 非空 = SDK ChatConfig 的自定义端点模式（模型/音色透传不走目录校验）。
     */
    val customBaseUrl: String = "",
    /** 自定义端点的 API Key（中转站需要；本地模型随意填占位）。 */
    val apiKeyCustom: String = "",
    /** TTS 与大模型同服务商（默认勾选）。 */
    val ttsSameProvider: Boolean = true,
    /** [ttsSameProvider]=false 时生效的 TTS 服务商。 */
    val ttsProvider: AiProvider = AiProvider.SILICONFLOW,
    val ttsModel: String = "",
    /**
     * TTS 引擎（任务 6）：默认 OpenAI 兼容（走上面的服务商配置）；选 Edge-TTS
     * 时免费、无需 Key，模型/服务商/Key 三行配置全部旁路。
     */
    val ttsEngine: TtsEngine = TtsEngine.OPENAI_COMPATIBLE,
    /** 存储值为完整引用或服务商短名（解析见 [resolveVoice]），留空=默认音色。 */
    val voice: String = "",
    /** 允许模型用 <cam:…> 标签切换视角；关闭后镜头主权归用户。 */
    val llmCamera: Boolean = true,
    /**
     * 智能体工具调用总开关（P0，docs/agent-feasibility.md §4.11）：默认开。
     * 关闭后请求不带 tools 键，模型只能靠行内标签协议表达。
     */
    val agentTools: Boolean = true,
    /**
     * 摄像头观察工具（observe_camera）：默认**关**（隐私面：模型可主动看画面）。
     * 生效还需视频模式与相机权限（注册时机见 MainActivity 的接线 effect）。
     */
    val observeCamera: Boolean = false,
    /**
     * 允许读取剪贴板（read_clipboard，P0.5 设备工具）：六个里唯一「读取用户
     * 数据」类，独立开关**默认关**——隐私分级先例：无隐私面跟 agentTools 总
     * 开关，读数据独立定价（docs/agent-tools-expansion.md §2.4）。身份签名
     * 刻意不含本开关（与其他工具开关同列：不改变模型能力面，不轮换上下文）。
     */
    val readClipboard: Boolean = false,
    /**
     * AI 自由走动（P1，docs/locomotion-feasibility.md §7.3）：允许模型用
     * `<move:锚点>` 标签走向房间锚点（move_to/stop_moving 工具同步可用）。
     * 默认开（锚点表随 APK 内置，协议块只在锚点非空时才宣传移动段）。
     * 身份签名刻意不含（与 llmCamera 同列：协议块变化走重钉+重发，不轮换
     * 上下文）。
     */
    val llmMove: Boolean = true,
    /**
     * 长期记忆总开关（docs/memory-feasibility.md）：默认开。关闭 = 三记忆工具
     * 退场 + 检索注入消失 + 待提炼窗口清空（运行时切换，不重建会话）。
     * 身份签名刻意不含（与 agentTools/observeCamera 同列：不改变模型能力面，
     * 不轮换上下文）。
     */
    val memory: Boolean = true,
    /**
     * 自动提炼子开关（通道 B 攒批提炼）：默认开。关 = 只保留 remember 工具
     * 显式写入与检索注入，对话不再自动进记忆。
     */
    val memoryAutoExtract: Boolean = true,
    /**
     * 配饰穿戴工具（P2，docs/accessory-feasibility.md §5.6）：wear_accessory/
     * remove_accessory 两工具（跟随 agentTools 总开关）。默认开（无隐私面）。
     * 身份签名刻意不含（与其他工具开关同列）。
     */
    val accessoryTools: Boolean = true,
    /**
     * 特效工具（P2，docs/vfx-feasibility.md §六 P2）：play_effect/stop_effect
     * 两工具（跟随 agentTools 总开关）。**默认关**——特效是观感面，模型滥调
     * 会刷屏，且部分特效带真灯有帧预算，用户显式开启。身份签名刻意不含。
     */
    val fxTools: Boolean = false,
    /**
     * 自主生命体总开关（docs/autonomy-feasibility.md）：FSM 在没人理的时候
     * 自己走动/坐躺/看窗外（决策零 LLM，确定性作息表+加权随机）。默认开。
     * 身份签名刻意不含（不改对话能力面，不轮换上下文）。
     */
    val autonomy: Boolean = true,
    /**
     * 自主问候子开关（§5）：不定时主动开口（25min 最小间隔+泊松随机、
     * 23:00-08:00 安静时段硬门、每日上限 3 次、phase≠IDLE 推迟）。默认开。
     */
    val greetings: Boolean = true,
    /**
     * 思考气泡子开关（§6）：头顶云朵内心独白（状态转移/长停留尾段/问候
     * 前奏），≤20 字、不出声。默认开。
     */
    val thoughtBubble: Boolean = true,
    /**
     * 历史摘要压缩（酒馆 P2-4，docs/tavern-companion-feasibility.md §5）：
     * 超出上下文窗口的早期对话滚动摘要进请求——记忆管「事实」，摘要管
     * 「剧情」。默认开（每 20 条新溢出消息才多一次后台 LLM 调用）。
     */
    val historySummary: Boolean = true,
) {
    /** TTS 实际生效的服务商。 */
    val ttsProviderResolved: AiProvider
        get() = if (ttsSameProvider) provider else ttsProvider

    /**
     * TTS 实际生效的引擎（与 SDK ChatConfig.ttsEngineResolved 同一规则，设置页
     * 显示/分支判定认这个值，实际装配由 SDK 同款规则兜底）：DeepSeek 官方无
     * 语音合成端点（/audio/speech 实测 404），OpenAI 兼容引擎选到它（同商跟随
     * 或显式指定）时整体回落 Edge-TTS——免费无 Key，模型/音色/Key 全按 Edge 解析。
     * 自定义端点同商跟随且未填 TTS 模型名时同样回落 Edge（第三方中转多数不
     * 代理 /audio/speech，留空=没打算用自端点合成）。
     */
    val ttsEngineResolved: TtsEngine
        get() = if (ttsEngine == TtsEngine.OPENAI_COMPATIBLE && (
                ttsProviderResolved == AiProvider.DEEPSEEK ||
                    (provider == AiProvider.CUSTOM && ttsSameProvider && ttsModel.isBlank())
                )
        ) {
            TtsEngine.EDGE
        } else {
            ttsEngine
        }

    /** 大模型服务的 API Key（硅基流动 / 火山方舟 / OpenRouter / DeepSeek / 百炼 / 自定义端点）。 */
    fun apiKeyFor(p: AiProvider): String = when (p) {
        AiProvider.SILICONFLOW -> apiKeySiliconflow
        AiProvider.VOLCANO -> apiKeyVolcano
        AiProvider.OPENROUTER -> apiKeyOpenrouter
        AiProvider.DEEPSEEK -> apiKeyDeepseek
        AiProvider.BAILIAN -> apiKeyBailian
        AiProvider.CUSTOM -> apiKeyCustom
    }

    /** 语音合成服务的 API Key（硅基流动/OpenRouter/百炼/自定义共用一份；火山语音用豆包语音那把；DeepSeek 无 TTS 走 Edge 不读 Key）。 */
    fun apiKeyForTts(): String = when (ttsProviderResolved) {
        AiProvider.SILICONFLOW -> apiKeySiliconflow
        AiProvider.VOLCANO -> apiKeyVolcanoTts
        AiProvider.OPENROUTER -> apiKeyOpenrouter
        AiProvider.DEEPSEEK -> apiKeyDeepseek
        AiProvider.BAILIAN -> apiKeyBailian
        AiProvider.CUSTOM -> apiKeyCustom
    }

    /** TTS 侧的就绪条件：Edge 免费（无 Key 也算就绪），OpenAI 兼容需 Key。 */
    val ttsReady: Boolean
        get() = when (ttsEngine) {
            TtsEngine.EDGE -> true
            TtsEngine.OPENAI_COMPATIBLE -> apiKeyForTts().isNotBlank()
        }

    val isConfigured: Boolean
        get() = apiKeyFor(provider).isNotBlank() && ttsReady &&
            resolveLlmModel(provider, llmModel).isNotBlank()

    /**
     * 持久化配置 → SDK 门面装配输入（[AiChatController] 的会话装配正本在
     * [AIAvatarSdk]，这里只做字段映射）。
     */
    fun toChatConfig(contextId: String, characterId: String?, lang: Lang): AIAvatarSdk.ChatConfig =
        AIAvatarSdk.ChatConfig(
            provider = provider.sdk,
            // 自定义端点（P1-2）：非空 baseUrl = SDK 的自定义端点模式
            //（模型/音色透传不走目录校验，llmModel 手填）
            baseUrl = if (provider == AiProvider.CUSTOM) customBaseUrl.trim() else null,
            apiKey = apiKeyFor(provider),
            llmModel = llmModel,
            ttsEngine = ttsEngine,
            ttsProvider = if (ttsSameProvider) null else ttsProvider.sdk,
            ttsApiKey = when {
                // 火山「同服务商」也必须显式给豆包语音那把 key（两把钥匙互不通用）
                ttsSameProvider && provider == AiProvider.VOLCANO -> apiKeyVolcanoTts
                !ttsSameProvider -> apiKeyForTts()
                else -> null
            },
            ttsModel = ttsModel,
            voice = voice,
            historySummaryEnabled = historySummary,
            lang = lang,
            enableLlmCamera = llmCamera,
            enableLlmMove = llmMove,
            contextId = contextId,
            characterId = characterId,
        )
}

private const val KEY_AI_PROVIDER = "ai_provider"
private const val KEY_AI_BASE_URL = "ai_baseUrl" // 旧版遗留：仅作迁移推断，不再写入
private const val KEY_AI_API_KEY = "ai_apiKey" // 旧版遗留：等价于硅基流动 key，仅作迁移
private const val KEY_AI_API_KEY_SILICONFLOW = "ai_api_key_siliconflow"
private const val KEY_AI_API_KEY_VOLCANO = "ai_api_key_volcano" // 火山方舟（大模型）
private const val KEY_AI_API_KEY_VOLCANO_TTS = "ai_api_key_volcano_tts" // 豆包语音（合成）
private const val KEY_AI_API_KEY_OPENROUTER = "ai_api_key_openrouter"
private const val KEY_AI_API_KEY_DEEPSEEK = "ai_api_key_deepseek"
private const val KEY_AI_API_KEY_BAILIAN = "ai_api_key_bailian"
private const val KEY_AI_CUSTOM_BASE_URL = "ai_custom_base_url"
private const val KEY_AI_API_KEY_CUSTOM = "ai_api_key_custom"
private const val KEY_AI_LLM_MODEL = "ai_llmModel"
private const val KEY_AI_TTS_SAME_PROVIDER = "ai_tts_same_provider"
private const val KEY_AI_TTS_PROVIDER = "ai_tts_provider"
private const val KEY_AI_TTS_MODEL = "ai_ttsModel"
private const val KEY_AI_TTS_ENGINE = "ai_tts_engine"
private const val KEY_AI_VOICE = "ai_voice"
private const val KEY_AI_LLM_CAMERA = "ai_llm_camera"
private const val KEY_AI_AGENT_TOOLS = "ai_agent_tools"
private const val KEY_AI_OBSERVE_CAMERA = "ai_observe_camera"
private const val KEY_AI_READ_CLIPBOARD = "ai_read_clipboard"
private const val KEY_AI_LLM_MOVE = "ai_llm_move"
private const val KEY_AI_MEMORY = "ai_agent_memory"
private const val KEY_AI_MEMORY_AUTO_EXTRACT = "ai_memory_auto_extract"
private const val KEY_AI_ACCESSORY_TOOLS = "ai_accessory_tools"
private const val KEY_AI_FX_TOOLS = "ai_fx_tools"
private const val KEY_AI_AUTONOMY = "ai_autonomy"
private const val KEY_AI_GREETINGS = "ai_greetings"
private const val KEY_AI_THOUGHT_BUBBLE = "ai_thought_bubble"
private const val KEY_AI_HISTORY_SUMMARY = "ai_history_summary"

/** 读枚举偏好；名字失效（改过枚举名）或未存过返回 null。 */
private inline fun <reified T : Enum<T>> prefsEnum(sp: SharedPreferences, key: String): T? =
    sp.getString(key, null)?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() }

fun SharedPreferences.loadAiPrefs(): AiChatPrefs {
    // 旧版（单服务商时代）只有一份 key + baseUrl：迁移时它就是硅基流动 key，
    // 服务商按旧端点推断（volces.com→火山，其余→硅基流动）
    val legacyKey = getString(KEY_AI_API_KEY, "").orEmpty()
    val legacyBaseUrl = getString(KEY_AI_BASE_URL, "").orEmpty()
    val sfKey = getString(KEY_AI_API_KEY_SILICONFLOW, null) ?: legacyKey
    // 火山单字段时代的遗留值按形态归位：ark- 前缀=方舟 key，否则视作豆包语音 key；
    // 新的豆包语音字段一旦存在就优先（saveAiPrefs 起两字段各存各的）
    val (volcArkKey, volcTtsKey) = splitLegacyVolcanoKey(
        getString(KEY_AI_API_KEY_VOLCANO, "").orEmpty(),
    )
    return AiChatPrefs(
        provider = prefsEnum<AiProvider>(this, KEY_AI_PROVIDER) ?: inferProviderFromBaseUrl(legacyBaseUrl),
        llmModel = getString(KEY_AI_LLM_MODEL, "").orEmpty(),
        apiKeySiliconflow = sfKey,
        apiKeyVolcano = volcArkKey,
        apiKeyVolcanoTts = getString(KEY_AI_API_KEY_VOLCANO_TTS, null) ?: volcTtsKey,
        apiKeyOpenrouter = getString(KEY_AI_API_KEY_OPENROUTER, "").orEmpty(),
        apiKeyDeepseek = getString(KEY_AI_API_KEY_DEEPSEEK, "").orEmpty(),
        apiKeyBailian = getString(KEY_AI_API_KEY_BAILIAN, "").orEmpty(),
        customBaseUrl = getString(KEY_AI_CUSTOM_BASE_URL, "").orEmpty(),
        apiKeyCustom = getString(KEY_AI_API_KEY_CUSTOM, "").orEmpty(),
        ttsSameProvider = getBoolean(KEY_AI_TTS_SAME_PROVIDER, true),
        ttsProvider = prefsEnum<AiProvider>(this, KEY_AI_TTS_PROVIDER) ?: AiProvider.SILICONFLOW,
        ttsModel = getString(KEY_AI_TTS_MODEL, "").orEmpty(),
        ttsEngine = prefsEnum<TtsEngine>(this, KEY_AI_TTS_ENGINE) ?: TtsEngine.OPENAI_COMPATIBLE,
        voice = getString(KEY_AI_VOICE, "").orEmpty(),
        llmCamera = getBoolean(KEY_AI_LLM_CAMERA, true),
        agentTools = getBoolean(KEY_AI_AGENT_TOOLS, true),
        observeCamera = getBoolean(KEY_AI_OBSERVE_CAMERA, false),
        readClipboard = getBoolean(KEY_AI_READ_CLIPBOARD, false),
        llmMove = getBoolean(KEY_AI_LLM_MOVE, true),
        memory = getBoolean(KEY_AI_MEMORY, true),
        memoryAutoExtract = getBoolean(KEY_AI_MEMORY_AUTO_EXTRACT, true),
        accessoryTools = getBoolean(KEY_AI_ACCESSORY_TOOLS, true),
        fxTools = getBoolean(KEY_AI_FX_TOOLS, false),
        autonomy = getBoolean(KEY_AI_AUTONOMY, true),
        greetings = getBoolean(KEY_AI_GREETINGS, true),
        thoughtBubble = getBoolean(KEY_AI_THOUGHT_BUBBLE, true),
        historySummary = getBoolean(KEY_AI_HISTORY_SUMMARY, true),
    )
}

fun SharedPreferences.saveAiPrefs(p: AiChatPrefs) {
    edit()
        .putString(KEY_AI_PROVIDER, p.provider.name)
        .putString(KEY_AI_LLM_MODEL, p.llmModel.trim())
        .putString(KEY_AI_API_KEY_SILICONFLOW, p.apiKeySiliconflow.trim())
        .putString(KEY_AI_API_KEY_VOLCANO, p.apiKeyVolcano.trim())
        .putString(KEY_AI_API_KEY_VOLCANO_TTS, p.apiKeyVolcanoTts.trim())
        .putString(KEY_AI_API_KEY_OPENROUTER, p.apiKeyOpenrouter.trim())
        .putString(KEY_AI_API_KEY_DEEPSEEK, p.apiKeyDeepseek.trim())
        .putString(KEY_AI_API_KEY_BAILIAN, p.apiKeyBailian.trim())
        .putString(KEY_AI_CUSTOM_BASE_URL, p.customBaseUrl.trim())
        .putString(KEY_AI_API_KEY_CUSTOM, p.apiKeyCustom.trim())
        .putBoolean(KEY_AI_TTS_SAME_PROVIDER, p.ttsSameProvider)
        .putString(KEY_AI_TTS_PROVIDER, p.ttsProvider.name)
        .putString(KEY_AI_TTS_MODEL, p.ttsModel.trim())
        .putString(KEY_AI_TTS_ENGINE, p.ttsEngine.name)
        .putString(KEY_AI_VOICE, p.voice.trim())
        .putBoolean(KEY_AI_LLM_CAMERA, p.llmCamera)
        .putBoolean(KEY_AI_AGENT_TOOLS, p.agentTools)
        .putBoolean(KEY_AI_OBSERVE_CAMERA, p.observeCamera)
        .putBoolean(KEY_AI_READ_CLIPBOARD, p.readClipboard)
        .putBoolean(KEY_AI_LLM_MOVE, p.llmMove)
        .putBoolean(KEY_AI_MEMORY, p.memory)
        .putBoolean(KEY_AI_MEMORY_AUTO_EXTRACT, p.memoryAutoExtract)
        .putBoolean(KEY_AI_ACCESSORY_TOOLS, p.accessoryTools)
        .putBoolean(KEY_AI_FX_TOOLS, p.fxTools)
        .putBoolean(KEY_AI_AUTONOMY, p.autonomy)
        .putBoolean(KEY_AI_GREETINGS, p.greetings)
        .putBoolean(KEY_AI_THOUGHT_BUBBLE, p.thoughtBubble)
        .putBoolean(KEY_AI_HISTORY_SUMMARY, p.historySummary)
        .apply()
}

/**
 * 语音识别（ASR）引擎：
 *  - [CLOUD]：OpenAI 兼容云端识别（跟随大模型服务商，key 共用大模型那份，
 *    见 [asrProviderFor]；OpenRouter 音频端点要求账户 ≥$0.50 余额）。
 *  - [SYSTEM]：系统内置 `SpeechRecognizer`（免费无 Key，见 [SystemAsrController]）
 *    ——GMS 设备走 Google 服务（海外用户主场景），国产 ROM 走厂商服务。
 */
enum class AsrEngine {
    CLOUD,
    SYSTEM,
}

/**
 * 语音输入配置（任务 4）：ASR 引擎 + 模型 + 松手行为 + 说话方式。
 * 刻意不放进 [AiChatPrefs]——会话身份 = AiChatPrefs + 上下文，改 ASR 配置
 * 不该触发会话重建（正在播的回复会被杀掉）。
 */
data class VoicePrefs(
    /** ASR 模型名；留空 = 按端点自动推断（见 [resolveAsrModel]）。仅 [AsrEngine.CLOUD]。 */
    val asrModel: String = "",
    /** ASR 引擎（云端/系统内置，见 [AsrEngine]）。 */
    val asrEngine: AsrEngine = AsrEngine.CLOUD,
    /** 松手识别成功后直接发送；关闭则识别文本填入输入框，由用户确认后发送。 */
    val autoSend: Boolean = true,
    /**
     * 自由说话（连续聆听）：VAD 自动断句、说完即发（自由态下 [autoSend]
     * 不生效，发送就是自由说话的意义）；false = 按住说话。聊天条左侧
     * 「按住/自由」按钮切换。
     */
    val freeTalk: Boolean = false,
)

private const val KEY_AI_ASR_MODEL = "ai_asr_model"
private const val KEY_AI_ASR_ENGINE = "ai_asr_engine"
private const val KEY_AI_VOICE_AUTO_SEND = "ai_voice_auto_send"
private const val KEY_AI_VOICE_FREE_TALK = "ai_voice_free_talk"

fun SharedPreferences.loadVoicePrefs(): VoicePrefs = VoicePrefs(
    asrModel = getString(KEY_AI_ASR_MODEL, "").orEmpty(),
    asrEngine = prefsEnum<AsrEngine>(this, KEY_AI_ASR_ENGINE) ?: AsrEngine.CLOUD,
    autoSend = getBoolean(KEY_AI_VOICE_AUTO_SEND, true),
    freeTalk = getBoolean(KEY_AI_VOICE_FREE_TALK, false),
)

fun SharedPreferences.saveVoicePrefs(p: VoicePrefs) {
    edit()
        .putString(KEY_AI_ASR_MODEL, p.asrModel.trim())
        .putString(KEY_AI_ASR_ENGINE, p.asrEngine.name)
        .putBoolean(KEY_AI_VOICE_AUTO_SEND, p.autoSend)
        .putBoolean(KEY_AI_VOICE_FREE_TALK, p.freeTalk)
        .apply()
}

/**
 * 大模型身份签名：只含影响对话能力的字段（服务商 + 解析后的模型名）。
 * 协议里的表情/动作目录由所配模型实现决定，切换签名即同步轮换对话上下文
 * （历史清空、新上下文首请求带新目录，见 MainActivity.updateAiPrefs）。
 * 语音/镜头开关与 API Key 不在此列——它们不改变模型能力，历史可以延续。
 * 自定义端点的 baseUrl 同列（换中转/换本地模型 = 换模型能力面）。
 */
fun llmIdentitySignature(p: AiChatPrefs): String =
    if (p.provider == AiProvider.CUSTOM) {
        "CUSTOM|${p.customBaseUrl.trim()}|${resolveLlmModel(p.provider, p.llmModel)}"
    } else {
        "${p.provider.name}|${resolveLlmModel(p.provider, p.llmModel)}"
    }

/**
 * Demo 的会话装配器：把 [AiChatPrefs] + 上下文 id 变成一条 [AvatarSession]。
 * 装配正本在 SDK 门面 [AIAvatarSdk]（adapter 工厂/配置解析/身份重建全在那边），
 * 这个类只剩 prefs 映射 + demo 的就绪标志转发——配置或上下文变化时用
 * [rebuild] 语义（[ensure] 内部按 [AIAvatarSdk.ChatConfig] 全量相等判身份）。
 */
class AiChatController(
    private val scope: CoroutineScope,
    private val avatarController: AvatarController,
    /** application context：Room 库单例的持有者。 */
    private val appContext: Context,
    /** 技能框架能力缝（docs/rps-skill-feasibility.md §4.3）；null = 无技能能力。 */
    private val skillHost: SkillHost? = null,
) {
    private val sdk = AIAvatarSdk(
        scope = scope,
        controller = avatarController,
        appContext = appContext,
        skillHost = skillHost,
        // 长期记忆（docs/memory-feasibility.md）：应用级 Room 库 + 硅基流动
        // 向量化（Key 每次现取，走「语音识别与向量化」同一位）。memoryStore
        // 跨会话/跨上下文共享；不配硅基流动 Key 时检索自动走关键词降级。
        memoryStore = RoomMemoryStore.from(appContext),
        embeddingAdapter = OpenAiCompatibleEmbeddingAdapter(),
    )

    /** 当前会话（[ensure] 装配成功后非空）；进阶定制直接操作它。 */
    val session: AvatarSession? get() = sdk.session

    /**
     * 长期记忆库直取缝（管理面板/ai_cmd 用）：与门面同一实例，会话未配置时
     * 管理面板照常可查/可删。
     */
    val memoryStore: MemoryStore = checkNotNull(sdk.memoryStore)

    /**
     * 向量化配置缝：由 demo 注入（每次现取当前硅基流动 Key——Key 变化立即
     * 生效，无需重建会话；Key 空 = 关键词降级检索）。
     */
    var embeddingConfigProvider: (() -> EmbeddingConfig?)?
        get() = sdk.embeddingConfigProvider
        set(value) {
            sdk.embeddingConfigProvider = value
        }

    /**
     * 确保存在一个与 [prefs]/[contextId]/[lang] 匹配、且在模型 [ready] 后启动
     * 了面部驱动的会话。身份未变且会话存活时是 no-op；配置不完整返回 null
     * 且不动现有会话。
     *
     * @param characterId 信息性字段，随 Room 会话行落库（上下文列表展示用）。
     */
    fun ensure(
        prefs: AiChatPrefs,
        ready: Boolean,
        contextId: String,
        characterId: String?,
        /** 提示词语言（多语言支持）：语言变化即重建会话，身份块自动按新语言重发。 */
        lang: Lang = Lang.ZH,
    ): AvatarSession? {
        if (!prefs.isConfigured) return null
        val config = prefs.toChatConfig(contextId, characterId, lang)
        // /models 实时目录注入：缓存里的模型 id 放行校验（静态清单外的实时
        // 模型可被选中并真实发请求）。缓存不进会话身份（ChatConfig 的 var 属性，
        // 不参与 equals）——进程存活期重新拉取/清空都不会重建会话。
        config.catalogLlmModels = ModelCatalogCache.idsFor(prefs.provider)
        return sdk.configure(config, modelReady = ready)
    }

    /** 长期记忆总开关（运行时切换，不重建会话）。 */
    fun setMemoryEnabled(enabled: Boolean) {
        sdk.session?.setMemoryEnabled(enabled)
    }

    /** 自动提炼子开关（运行时）。 */
    fun setMemoryAutoExtract(enabled: Boolean) {
        sdk.session?.setMemoryAutoExtract(enabled)
    }

    fun shutdown() {
        sdk.close()
    }
}
