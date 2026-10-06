package com.neethu.aiavatar_sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.neethu.corelib.AvatarController
import com.neethu.corelib.Lang
import com.neethu.orchestrator.facade.AIAvatarSdk
import com.neethu.orchestrator.facade.ChatProvider
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.skill.SkillHost
import kotlinx.coroutines.CoroutineScope

/**
 * AI 对话配置（多服务商版：硅基流动/火山引擎/OpenRouter）。除 API Key 外全部
 * 下拉框选择，模型/音色留空即用服务商默认（解析正本在 SDK 目录
 * [ChatProvider]，见 [resolveLlmModel]/[resolveTtsModel]/[resolveVoice]）。
 *
 * Key 按服务分存（硅基流动与 OpenRouter 一把通用；**火山的语音与大模型是两把
 * key**：[apiKeyVolcano]=方舟 Ark 大模型，[apiKeyVolcanoTts]=豆包语音合成，实测
 * 互不通用）；TTS 可独立选服务商（[ttsSameProvider]=false 时用 [ttsProvider]）。
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
) {
    /** TTS 实际生效的服务商。 */
    val ttsProviderResolved: AiProvider
        get() = if (ttsSameProvider) provider else ttsProvider

    /** 大模型服务的 API Key（硅基流动 / 火山方舟 / OpenRouter）。 */
    fun apiKeyFor(p: AiProvider): String = when (p) {
        AiProvider.SILICONFLOW -> apiKeySiliconflow
        AiProvider.VOLCANO -> apiKeyVolcano
        AiProvider.OPENROUTER -> apiKeyOpenrouter
    }

    /** 语音合成服务的 API Key（硅基流动/OpenRouter 共用一份；火山语音用豆包语音那把）。 */
    fun apiKeyForTts(): String = when (ttsProviderResolved) {
        AiProvider.SILICONFLOW -> apiKeySiliconflow
        AiProvider.VOLCANO -> apiKeyVolcanoTts
        AiProvider.OPENROUTER -> apiKeyOpenrouter
    }

    /** TTS 侧的就绪条件：Edge-TTS 免费（无 Key 也算就绪），OpenAI 兼容需 Key。 */
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
            lang = lang,
            enableLlmCamera = llmCamera,
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
private const val KEY_AI_LLM_MODEL = "ai_llmModel"
private const val KEY_AI_TTS_SAME_PROVIDER = "ai_tts_same_provider"
private const val KEY_AI_TTS_PROVIDER = "ai_tts_provider"
private const val KEY_AI_TTS_MODEL = "ai_ttsModel"
private const val KEY_AI_TTS_ENGINE = "ai_tts_engine"
private const val KEY_AI_VOICE = "ai_voice"
private const val KEY_AI_LLM_CAMERA = "ai_llm_camera"

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
        ttsSameProvider = getBoolean(KEY_AI_TTS_SAME_PROVIDER, true),
        ttsProvider = prefsEnum<AiProvider>(this, KEY_AI_TTS_PROVIDER) ?: AiProvider.SILICONFLOW,
        ttsModel = getString(KEY_AI_TTS_MODEL, "").orEmpty(),
        ttsEngine = prefsEnum<TtsEngine>(this, KEY_AI_TTS_ENGINE) ?: TtsEngine.OPENAI_COMPATIBLE,
        voice = getString(KEY_AI_VOICE, "").orEmpty(),
        llmCamera = getBoolean(KEY_AI_LLM_CAMERA, true),
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
        .putBoolean(KEY_AI_TTS_SAME_PROVIDER, p.ttsSameProvider)
        .putString(KEY_AI_TTS_PROVIDER, p.ttsProvider.name)
        .putString(KEY_AI_TTS_MODEL, p.ttsModel.trim())
        .putString(KEY_AI_TTS_ENGINE, p.ttsEngine.name)
        .putString(KEY_AI_VOICE, p.voice.trim())
        .putBoolean(KEY_AI_LLM_CAMERA, p.llmCamera)
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
 */
fun llmIdentitySignature(p: AiChatPrefs): String =
    "${p.provider.name}|${resolveLlmModel(p.provider, p.llmModel)}"

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
    )

    /** 当前会话（[ensure] 装配成功后非空）；进阶定制直接操作它。 */
    val session: AvatarSession? get() = sdk.session

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
        return sdk.configure(
            prefs.toChatConfig(contextId, characterId, lang),
            modelReady = ready,
        )
    }

    fun shutdown() {
        sdk.close()
    }
}
