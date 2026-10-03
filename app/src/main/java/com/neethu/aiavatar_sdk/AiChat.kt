package com.neethu.aiavatar_sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.openai.OpenAiCompatibleLlmAdapter
import com.neethu.aiadapter.openai.OpenAiCompatibleTtsAdapter
import com.neethu.corelib.AvatarController
import com.neethu.orchestrator.history.ConversationDatabase
import com.neethu.orchestrator.history.RoomConversationStore
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.CoroutineScope

/** AI 对话配置（LLM 与 TTS 共用同一个 OpenAI 兼容端点 + Key）。 */
data class AiChatPrefs(
    val baseUrl: String = "",
    val apiKey: String = "",
    val llmModel: String = "",
    val ttsModel: String = "",
    val voice: String = "",
    /** 允许模型用 <cam:…> 标签切换视角；关闭后镜头主权归用户。 */
    val llmCamera: Boolean = true,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && llmModel.isNotBlank() &&
            ttsModel.isNotBlank() && voice.isNotBlank()
}

private const val KEY_AI_BASE_URL = "ai_baseUrl"
private const val KEY_AI_API_KEY = "ai_apiKey"
private const val KEY_AI_LLM_MODEL = "ai_llmModel"
private const val KEY_AI_TTS_MODEL = "ai_ttsModel"
private const val KEY_AI_VOICE = "ai_voice"
private const val KEY_AI_LLM_CAMERA = "ai_llm_camera"

fun SharedPreferences.loadAiPrefs(): AiChatPrefs = AiChatPrefs(
    baseUrl = getString(KEY_AI_BASE_URL, "").orEmpty(),
    apiKey = getString(KEY_AI_API_KEY, "").orEmpty(),
    llmModel = getString(KEY_AI_LLM_MODEL, "").orEmpty(),
    ttsModel = getString(KEY_AI_TTS_MODEL, "").orEmpty(),
    voice = getString(KEY_AI_VOICE, "").orEmpty(),
    llmCamera = getBoolean(KEY_AI_LLM_CAMERA, true),
)

fun SharedPreferences.saveAiPrefs(p: AiChatPrefs) {
    edit()
        .putString(KEY_AI_BASE_URL, p.baseUrl.trim())
        .putString(KEY_AI_API_KEY, p.apiKey.trim())
        .putString(KEY_AI_LLM_MODEL, p.llmModel.trim())
        .putString(KEY_AI_TTS_MODEL, p.ttsModel.trim())
        .putString(KEY_AI_VOICE, p.voice.trim())
        .putBoolean(KEY_AI_LLM_CAMERA, p.llmCamera)
        .apply()
}

/**
 * 语音输入配置（任务 4）：ASR 模型 + 松手行为。
 * 刻意不放进 [AiChatPrefs]——会话身份 = AiChatPrefs + 上下文，改 ASR 配置
 * 不该触发会话重建（正在播的回复会被杀掉）。
 */
data class VoicePrefs(
    /** ASR 模型名；留空 = 按端点自动推断（见 [resolveAsrModel]）。 */
    val asrModel: String = "",
    /** 松手识别成功后直接发送；关闭则识别文本填入输入框，由用户确认后发送。 */
    val autoSend: Boolean = true,
)

private const val KEY_AI_ASR_MODEL = "ai_asr_model"
private const val KEY_AI_VOICE_AUTO_SEND = "ai_voice_auto_send"

fun SharedPreferences.loadVoicePrefs(): VoicePrefs = VoicePrefs(
    asrModel = getString(KEY_AI_ASR_MODEL, "").orEmpty(),
    autoSend = getBoolean(KEY_AI_VOICE_AUTO_SEND, true),
)

fun SharedPreferences.saveVoicePrefs(p: VoicePrefs) {
    edit()
        .putString(KEY_AI_ASR_MODEL, p.asrModel.trim())
        .putBoolean(KEY_AI_VOICE_AUTO_SEND, p.autoSend)
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
 * Demo 的会话装配器：把 [AiChatPrefs] + 上下文 id 变成一条 [AvatarSession]。
 * 配置或上下文变化时用 [rebuild] 丢弃旧会话重建（AIRI getProviderInstance 的
 * "凭据变化即重建实例"语义）；上下文 id 变化即切换对话历史（任务 3）。
 */
class AiChatController(
    private val scope: CoroutineScope,
    private val avatarController: AvatarController,
    /** application context：Room 库单例的持有者。 */
    private val appContext: Context,
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

        val llm = OpenAiCompatibleLlmAdapter(prefs.baseUrl, prefs.apiKey)
        val tts = OpenAiCompatibleTtsAdapter(prefs.baseUrl, prefs.apiKey)
        val session = AvatarSession(
            scope, llm, tts, avatarController,
            AvatarSession.Options(
                enableLlmCamera = prefs.llmCamera,
                // Room 持久化后上下文可无限增长；请求只带最近 40 条 user/assistant
                //（≈20 轮），system 提示词（~14.5K chars）本就每次现拼不受影响
                recentTurnLimit = 40,
            ),
            store = store,
        )
        session.llmConfig = LlmConfig(
            baseUrl = prefs.baseUrl,
            apiKey = prefs.apiKey,
            model = prefs.llmModel,
            // 略低于默认 0.8：多模态行内标签协议对指令遵循敏感（真机实测
            // 0.8 下模型偶尔完全忽略标签/用括号演戏），0.6 是遵循与创意折中
            temperature = 0.6f,
        )
        session.ttsConfig = TtsConfig(
            model = prefs.ttsModel,
            voice = prefs.voice,
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
