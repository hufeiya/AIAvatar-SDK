package com.neethu.aiavatar_sdk

/**
 * AI 服务商目录：端点、可选模型、音色清单与默认值全部收口在这一个枚举——
 * 设置页除 API Key 外的所有 AI 配置都是下拉框选择，杜绝手输模型名（2026-10
 * 双厂商打通：硅基流动 + 火山引擎）。
 *
 * 模型/音色清单均经真实接口核验（LLM 三项在 /v1/models 在册；硅基流动 TTS
 * 两模型音色引用互认，火山 seed-tts-2.0 六音色实测可合成），默认值按需求指定。
 */
enum class AiProvider(
    val label: String,
    /** OpenAI 兼容端点（火山即方舟 Ark v3，chat/completions 同构）。 */
    val baseUrl: String,
    val llmModels: List<String>,
    /** TTS 请求体里的 model id；展示名见 [ttsModelLabel]。 */
    val ttsModels: List<String>,
    val defaultTtsModel: String,
    /**
     * 音色引用前缀：硅基流动的请求音色格式是 `FunAudioLLM/CosyVoice2-0.5B:<短名>`，
     * 且实测 MOSS-TTSD 也接受该引用（两模型音色互认）；火山音色 id 原样发送（null）。
     */
    val voicePrefix: String?,
    /** 音色清单接口拉不到时的静态兜底（显示名与存储短名一致）。 */
    val voices: List<String>,
    val defaultVoice: String,
) {
    SILICONFLOW(
        label = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        llmModels = listOf(
            "deepseek-ai/DeepSeek-V4-Flash",
            "deepseek-ai/DeepSeek-V3",
            "Qwen/Qwen3.8-27B",
        ),
        ttsModels = listOf(
            "fnlp/MOSS-TTSD-v0.5",
            "FunAudioLLM/CosyVoice2-0.5B",
        ),
        defaultTtsModel = "fnlp/MOSS-TTSD-v0.5",
        voicePrefix = "FunAudioLLM/CosyVoice2-0.5B",
        voices = listOf("alex", "anna", "bella", "benjamin", "charles", "claire"),
        defaultVoice = "anna",
    ),
    VOLCANO(
        label = "火山引擎",
        baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        llmModels = listOf(
            "doubao-seed-2-0-mini-260428",
            "doubao-seed-2-1-turbo-260628",
            "doubao-seedream-5-0-pro-260628",
            "doubao-seed-2-1-pro-260915",
            "deepseek-v4-flash-ga-260731",
            "doubao-seed-character-260628",
        ),
        ttsModels = listOf("seed-tts-2.0"),
        defaultTtsModel = "seed-tts-2.0",
        voicePrefix = null,
        voices = listOf(
            "zh_female_vv_uranus_bigtts",
            "zh_female_xiaohe_uranus_bigtts",
            "zh_male_m191_uranus_bigtts",
            "zh_male_taocheng_uranus_bigtts",
            "zh_male_shaonianzixin_uranus_bigtts",
            "zh_female_meilinvyou_uranus_bigtts",
        ),
        defaultVoice = "zh_female_vv_uranus_bigtts",
    ),
    ;

    /** TTS 模型展示名：剥掉组织前缀（fnlp/MOSS-TTSD-v0.5 → MOSS-TTSD-v0.5）。 */
    fun ttsModelLabel(id: String): String = id.substringAfterLast('/')

    /** 音色显示名：剥掉引用前缀（…:anna → anna）；火山 id 本身就是显示名。 */
    fun voiceLabel(value: String): String = value.substringAfterLast(':')

    /** 大模型默认模型（清单首位）。 */
    val defaultLlmModel: String get() = llmModels.first()
}

/** 旧版 prefs 只有 baseUrl；按端点推断服务商（迁移用，纯函数可测）。 */
fun inferProviderFromBaseUrl(baseUrl: String): AiProvider =
    if (baseUrl.contains("volces.com", ignoreCase = true)) AiProvider.VOLCANO
    else AiProvider.SILICONFLOW

/**
 * 把存储的音色值拼成请求用完整引用：已含 `:`（完整引用/自定义音色 URI）原样，
 * 短名补前缀，火山原样。空串返回空串（由 [resolveVoice] 落到默认值）。
 */
fun composeVoiceRef(provider: AiProvider, voice: String): String = when {
    voice.isBlank() -> ""
    voice.contains(':') -> voice
    provider.voicePrefix != null -> "${provider.voicePrefix}:$voice"
    else -> voice
}

/** 硅基流动自定义克隆音色的 URI 前缀（/audio/voice/list 返回的 custom 音色形态）。 */
private const val CUSTOM_VOICE_URI_PREFIX = "speech:"

/**
 * LLM 模型解析 = **校验 + 默认**：配置值必须在本服务商清单里，否则（含留空、
 * 切服务商后残留的旧清单值）一律落回服务商默认。设置页全部是下拉框选择，
 * 不存在清单外模型——校验兜住 UI / ai_cmd / 旧 prefs 三条写入路径的跨服务商
 * 泄漏（真机实测：切火山后 DeepSeek-V3 残留被原样发给 Ark）。
 */
fun resolveLlmModel(provider: AiProvider, configured: String): String {
    val v = configured.trim()
    return if (v in provider.llmModels) v else provider.defaultLlmModel
}

/** TTS 模型解析 = 校验 + 默认（同 [resolveLlmModel] 的跨服务商防泄漏语义）。 */
fun resolveTtsModel(provider: AiProvider, configured: String): String {
    val v = configured.trim()
    return if (v in provider.ttsModels) v else provider.defaultTtsModel
}

/**
 * 音色解析 = 校验 + 默认：已知音色（静态清单/接口拉取的短名或完整引用）与
 * 自定义克隆 URI（speech:…）原样放行，其余落回服务商默认音色。
 */
fun resolveVoice(provider: AiProvider, configured: String): String {
    val v = configured.trim()
    if (v.isBlank()) return composeVoiceRef(provider, provider.defaultVoice)
    val composed = composeVoiceRef(provider, v)
    val known = provider.voices.any { composeVoiceRef(provider, it) == composed }
    return if (known || composed.startsWith(CUSTOM_VOICE_URI_PREFIX)) composed
    else composeVoiceRef(provider, provider.defaultVoice)
}
