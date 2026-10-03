package com.neethu.aiavatar_sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.openai.OpenAiCompatibleLlmAdapter
import com.neethu.aiadapter.openai.OpenAiCompatibleTtsAdapter
import com.neethu.corelib.AvatarController
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.CoroutineScope

/** AI 对话配置（LLM 与 TTS 共用同一个 OpenAI 兼容端点 + Key）。 */
data class AiChatPrefs(
    val baseUrl: String = "",
    val apiKey: String = "",
    val llmModel: String = "",
    val ttsModel: String = "",
    val voice: String = "",
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

fun SharedPreferences.loadAiPrefs(): AiChatPrefs = AiChatPrefs(
    baseUrl = getString(KEY_AI_BASE_URL, "").orEmpty(),
    apiKey = getString(KEY_AI_API_KEY, "").orEmpty(),
    llmModel = getString(KEY_AI_LLM_MODEL, "").orEmpty(),
    ttsModel = getString(KEY_AI_TTS_MODEL, "").orEmpty(),
    voice = getString(KEY_AI_VOICE, "").orEmpty(),
)

fun SharedPreferences.saveAiPrefs(p: AiChatPrefs) {
    edit()
        .putString(KEY_AI_BASE_URL, p.baseUrl.trim())
        .putString(KEY_AI_API_KEY, p.apiKey.trim())
        .putString(KEY_AI_LLM_MODEL, p.llmModel.trim())
        .putString(KEY_AI_TTS_MODEL, p.ttsModel.trim())
        .putString(KEY_AI_VOICE, p.voice.trim())
        .apply()
}

/**
 * Demo 的会话装配器：把 [AiChatPrefs] 变成一条 [AvatarSession]。
 * 配置变化时用 [rebuild] 丢弃旧会话重建（AIRI getProviderInstance 的
 * "凭据变化即重建实例"语义）。
 */
class AiChatController(
    private val scope: CoroutineScope,
    private val avatarController: AvatarController,
) {
    var session: AvatarSession? = null
        private set

    /** 当前会话所用的配置；用于判断是否需要重建。 */
    var builtFor: AiChatPrefs? = null
        private set

    /**
     * 确保存在一个与 [prefs] 匹配、且在模型 [ready] 后启动了面部驱动的会话。
     * 配置未变且会话存活时是 no-op。
     */
    fun ensure(prefs: AiChatPrefs, ready: Boolean): AvatarSession? {
        if (!prefs.isConfigured) return null
        val existing = session
        if (existing != null && builtFor == prefs) {
            if (ready && !faceDrivingStarted) existing.startFaceDriving().also { faceDrivingStarted = true }
            return existing
        }
        existing?.close()

        val llm = OpenAiCompatibleLlmAdapter(prefs.baseUrl, prefs.apiKey)
        val tts = OpenAiCompatibleTtsAdapter(prefs.baseUrl, prefs.apiKey)
        val session = AvatarSession(scope, llm, tts, avatarController)
        session.llmConfig = LlmConfig(
            baseUrl = prefs.baseUrl,
            apiKey = prefs.apiKey,
            model = prefs.llmModel,
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
        builtFor = prefs
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
