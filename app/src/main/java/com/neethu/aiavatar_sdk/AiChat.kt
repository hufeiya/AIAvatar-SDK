package com.neethu.aiavatar_sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.openai.OpenAiCompatibleLlmAdapter
import com.neethu.aiadapter.openai.OpenAiCompatibleTtsAdapter
import com.neethu.aiadapter.volcengine.VolcanoEngineTtsAdapter
import com.neethu.corelib.AvatarController
import com.neethu.orchestrator.history.ConversationDatabase
import com.neethu.orchestrator.history.RoomConversationStore
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.skill.SkillHost
import kotlinx.coroutines.CoroutineScope

/**
 * AI 对话配置（双服务商版）。除 API Key 外全部下拉框选择，模型/音色留空即用
 * 服务商默认（见 [resolveLlmModel]/[resolveTtsModel]/[resolveVoice]）。
 *
 * Key 按服务分存（硅基流动一把通用；**火山的语音与大模型是两把 key**：
 * [apiKeyVolcano]=方舟 Ark 大模型，[apiKeyVolcanoTts]=豆包语音合成，实测互不
 * 通用）；TTS 可独立选服务商（[ttsSameProvider]=false 时用 [ttsProvider]）。
 */
data class AiChatPrefs(
    val provider: AiProvider = AiProvider.SILICONFLOW,
    val llmModel: String = "",
    val apiKeySiliconflow: String = "",
    /** 火山方舟（Ark）API Key——火山大模型链路。 */
    val apiKeyVolcano: String = "",
    /** 豆包语音控制台 API Key——火山语音合成（seed-tts-2.0 WebSocket）链路。 */
    val apiKeyVolcanoTts: String = "",
    /** TTS 与大模型同服务商（默认勾选）。 */
    val ttsSameProvider: Boolean = true,
    /** [ttsSameProvider]=false 时生效的 TTS 服务商。 */
    val ttsProvider: AiProvider = AiProvider.SILICONFLOW,
    val ttsModel: String = "",
    /** 存储值为完整引用或服务商短名（解析见 [resolveVoice]），留空=默认音色。 */
    val voice: String = "",
    /** 允许模型用 <cam:…> 标签切换视角；关闭后镜头主权归用户。 */
    val llmCamera: Boolean = true,
) {
    /** TTS 实际生效的服务商。 */
    val ttsProviderResolved: AiProvider
        get() = if (ttsSameProvider) provider else ttsProvider

    /** 大模型服务的 API Key（硅基流动 / 火山方舟）。 */
    fun apiKeyFor(p: AiProvider): String = when (p) {
        AiProvider.SILICONFLOW -> apiKeySiliconflow
        AiProvider.VOLCANO -> apiKeyVolcano
    }

    /** 语音合成服务的 API Key（硅基流动共用一份；火山语音用豆包语音那把）。 */
    fun apiKeyForTts(): String = when (ttsProviderResolved) {
        AiProvider.SILICONFLOW -> apiKeySiliconflow
        AiProvider.VOLCANO -> apiKeyVolcanoTts
    }

    val isConfigured: Boolean
        get() = apiKeyFor(provider).isNotBlank() && apiKeyForTts().isNotBlank() &&
            resolveLlmModel(provider, llmModel).isNotBlank() &&
            resolveTtsModel(ttsProviderResolved, ttsModel).isNotBlank() &&
            resolveVoice(ttsProviderResolved, voice).isNotBlank()
}

private const val KEY_AI_PROVIDER = "ai_provider"
private const val KEY_AI_BASE_URL = "ai_baseUrl" // 旧版遗留：仅作迁移推断，不再写入
private const val KEY_AI_API_KEY = "ai_apiKey" // 旧版遗留：等价于硅基流动 key，仅作迁移
private const val KEY_AI_API_KEY_SILICONFLOW = "ai_api_key_siliconflow"
private const val KEY_AI_API_KEY_VOLCANO = "ai_api_key_volcano" // 火山方舟（大模型）
private const val KEY_AI_API_KEY_VOLCANO_TTS = "ai_api_key_volcano_tts" // 豆包语音（合成）
private const val KEY_AI_LLM_MODEL = "ai_llmModel"
private const val KEY_AI_TTS_SAME_PROVIDER = "ai_tts_same_provider"
private const val KEY_AI_TTS_PROVIDER = "ai_tts_provider"
private const val KEY_AI_TTS_MODEL = "ai_ttsModel"
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
        ttsSameProvider = getBoolean(KEY_AI_TTS_SAME_PROVIDER, true),
        ttsProvider = prefsEnum<AiProvider>(this, KEY_AI_TTS_PROVIDER) ?: AiProvider.SILICONFLOW,
        ttsModel = getString(KEY_AI_TTS_MODEL, "").orEmpty(),
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
        .putBoolean(KEY_AI_TTS_SAME_PROVIDER, p.ttsSameProvider)
        .putString(KEY_AI_TTS_PROVIDER, p.ttsProvider.name)
        .putString(KEY_AI_TTS_MODEL, p.ttsModel.trim())
        .putString(KEY_AI_VOICE, p.voice.trim())
        .putBoolean(KEY_AI_LLM_CAMERA, p.llmCamera)
        .apply()
}

/**
 * 语音输入配置（任务 4）：ASR 模型 + 松手行为 + 说话方式。
 * 刻意不放进 [AiChatPrefs]——会话身份 = AiChatPrefs + 上下文，改 ASR 配置
 * 不该触发会话重建（正在播的回复会被杀掉）。
 */
data class VoicePrefs(
    /** ASR 模型名；留空 = 按端点自动推断（见 [resolveAsrModel]）。 */
    val asrModel: String = "",
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
private const val KEY_AI_VOICE_AUTO_SEND = "ai_voice_auto_send"
private const val KEY_AI_VOICE_FREE_TALK = "ai_voice_free_talk"

fun SharedPreferences.loadVoicePrefs(): VoicePrefs = VoicePrefs(
    asrModel = getString(KEY_AI_ASR_MODEL, "").orEmpty(),
    autoSend = getBoolean(KEY_AI_VOICE_AUTO_SEND, true),
    freeTalk = getBoolean(KEY_AI_VOICE_FREE_TALK, false),
)

fun SharedPreferences.saveVoicePrefs(p: VoicePrefs) {
    edit()
        .putString(KEY_AI_ASR_MODEL, p.asrModel.trim())
        .putBoolean(KEY_AI_VOICE_AUTO_SEND, p.autoSend)
        .putBoolean(KEY_AI_VOICE_FREE_TALK, p.freeTalk)
        .apply()
}

/**
 * ASR 模型留空时按端点推断：硅基流动 → `Qwen/Qwen3-ASR-1.7B`（其
 * /audio/transcriptions 端点的默认语音识别模型），其他 → OpenAI 的 `whisper-1`。
 */
fun resolveAsrModel(baseUrl: String, configured: String): String =
    configured.trim().ifBlank {
        if (baseUrl.contains("siliconflow", ignoreCase = true)) {
            "Qwen/Qwen3-ASR-1.7B"
        } else {
            "whisper-1"
        }
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
 * 配置或上下文变化时用 [rebuild] 丢弃旧会话重建（AIRI getProviderInstance 的
 * "凭据变化即重建实例"语义）；上下文 id 变化即切换对话历史（任务 3）。
 */
class AiChatController(
    private val scope: CoroutineScope,
    private val avatarController: AvatarController,
    /** application context：Room 库单例的持有者。 */
    private val appContext: Context,
    /** 技能框架能力缝（docs/rps-skill-feasibility.md §4.3）；null = 无技能能力。 */
    private val skillHost: SkillHost? = null,
) {
    /** 会话身份：配置 + 上下文。任一变化都触发重建。 */
    data class SessionIdentity(val prefs: AiChatPrefs, val contextId: String)

    var session: AvatarSession? = null
        private set

    /** 当前会话所用的身份；用于判断是否需要重建。 */
    var builtFor: SessionIdentity? = null
        private set

    /**
     * 确保存在一个与 [prefs]/[contextId] 匹配、且在模型 [ready] 后启动了面部
     * 驱动的会话。身份未变且会话存活时是 no-op。
     *
     * @param characterId 信息性字段，随 Room 会话行落库（上下文列表展示用）。
     */
    fun ensure(
        prefs: AiChatPrefs,
        ready: Boolean,
        contextId: String,
        characterId: String?,
    ): AvatarSession? {
        if (!prefs.isConfigured) return null
        val identity = SessionIdentity(prefs, contextId)
        val existing = session
        if (existing != null && builtFor == identity) {
            if (ready && !faceDrivingStarted) existing.startFaceDriving().also { faceDrivingStarted = true }
            return existing
        }
        existing?.close()

        val db = ConversationDatabase.getInstance(appContext)
        val store = RoomConversationStore.from(db, sessionId = contextId, characterId = characterId)

        val llm = OpenAiCompatibleLlmAdapter(prefs.provider.baseUrl, prefs.apiKeyFor(prefs.provider))
        val ttsProvider = prefs.ttsProviderResolved
        val tts = when (ttsProvider) {
            // 硅基流动走 OpenAI 兼容 /audio/speech（wav 16k，两 TTS 模型实测可用）
            AiProvider.SILICONFLOW ->
                OpenAiCompatibleTtsAdapter(ttsProvider.baseUrl, prefs.apiKeyForTts())
            // 火山 seed-tts-2.0 只提供 V3 双向流式 WebSocket；适配器对外仍是
            // 句级整段语义，输出恒为 PCM 16k（口型管线免解码直喂）
            AiProvider.VOLCANO ->
                VolcanoEngineTtsAdapter(prefs.apiKeyForTts())
        }
        val session = AvatarSession(
            scope, llm, tts, avatarController,
            AvatarSession.Options(
                enableLlmCamera = prefs.llmCamera,
                // Room 持久化后上下文可无限增长；请求只带最近 40 条 user/assistant
                //（≈20 轮）。人设与协议两条 system 消息不进 store、不受裁剪；
                // 协议块（~12K chars 目录）每上下文只钉一次，不逐轮重拼
                recentTurnLimit = 40,
            ),
            store = store,
            skillHost = skillHost,
        )
        val model = resolveLlmModel(prefs.provider, prefs.llmModel)
        session.llmConfig = LlmConfig(
            baseUrl = prefs.provider.baseUrl,
            apiKey = prefs.apiKeyFor(prefs.provider),
            model = model,
            // 略低于默认 0.8：多模态行内标签协议对指令遵循敏感（真机实测
            // 0.8 下模型偶尔完全忽略标签/用括号演戏），0.6 是遵循与创意折中
            temperature = 0.6f,
            // 关深度思考（首句延迟治理，见 [llmExtraBody]）：火山 doubao-seed 系
            // 与硅基流动 Qwen3 系默认都开思考，思考 token 全成首句前的隐形等待；
            // 其他模型无额外参数
            extraBody = llmExtraBody(prefs.provider, model),
        )
        session.ttsConfig = TtsConfig(
            model = resolveTtsModel(ttsProvider, prefs.ttsModel),
            voice = resolveVoice(ttsProvider, prefs.voice),
            responseFormat = "wav",
            // wLipSync 标定输入是 16kHz；CosyVoice2 默认 24kHz 会走 MFCC 前端的
            // 分数降采样路径，实测口型得分塌缩（见 docs/ai-layer-handoff.md 附录A）
            sampleRate = 16_000,
        )
        if (ready) {
            session.startFaceDriving()
            faceDrivingStarted = true
        }
        this.session = session
        builtFor = identity
        return session
    }

    private var faceDrivingStarted = false

    fun shutdown() {
        session?.close()
        session = null
        builtFor = null
        faceDrivingStarted = false
    }
}
