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
    /**
     * 清单里支持图片输入（多模态）的子集——视频模式的准入门控依据。
     * 2026-10-03 用真实请求核验（64px 红/蓝图 chat 回色=过，报
     * "not a VLM"/答非所问=不过）：硅基流动 DeepSeek 系明确拒图；
     * 火山 doubao 系全部真看图；`deepseek-v4-flash-ga` 是假视觉——API
     * 收下 image_url 但答"image data incomplete"（同图 doubao 描述正确），
     * 必须排除；`doubao-seedream-5-0-pro` 是图像生成模型，chat 不可用照旧排除。
     */
    val visionLlmModels: List<String>,
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
    /** 大模型服务的 API Key 字段标签/占位（火山的语音与大模型是两把 key，见下）。 */
    val llmKeyLabel: String,
    val llmKeyHint: String,
    /** 语音合成服务的 API Key 字段标签/占位。 */
    val ttsKeyLabel: String,
    val ttsKeyHint: String,
) {
    SILICONFLOW(
        label = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        llmModels = listOf(
            "deepseek-ai/DeepSeek-V4-Flash",
            "deepseek-ai/DeepSeek-V3",
            "Qwen/Qwen3.8-27B",
            "Qwen/Qwen3-VL-32B-Instruct",
        ),
        visionLlmModels = listOf(
            "Qwen/Qwen3.8-27B",
            "Qwen/Qwen3-VL-32B-Instruct",
        ),
        ttsModels = listOf(
            "fnlp/MOSS-TTSD-v0.5",
            "FunAudioLLM/CosyVoice2-0.5B",
        ),
        defaultTtsModel = "fnlp/MOSS-TTSD-v0.5",
        voicePrefix = "FunAudioLLM/CosyVoice2-0.5B",
        voices = listOf("alex", "anna", "bella", "benjamin", "charles", "claire"),
        defaultVoice = "anna",
        // 硅基流动一家一把 key，LLM/TTS/ASR 共用
        llmKeyLabel = "硅基流动 API Key",
        llmKeyHint = "sk-...",
        ttsKeyLabel = "硅基流动 API Key（语音合成用）",
        ttsKeyHint = "sk-...",
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
        visionLlmModels = listOf(
            "doubao-seed-2-0-mini-260428",
            "doubao-seed-2-1-turbo-260628",
            "doubao-seed-2-1-pro-260915",
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
        // 火山是两把钥匙（2026-10-03 实测互不通用）：大模型走方舟 Ark 控制台，
        // 语音合成走豆包语音控制台——同选火山也必须各填各的
        llmKeyLabel = "火山方舟 API Key（大模型用）",
        llmKeyHint = "ark-...",
        ttsKeyLabel = "豆包语音 API Key（语音合成用）",
        ttsKeyHint = "粘贴豆包语音控制台 Key",
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
 * 旧版只有一个「火山引擎 API Key」字段（单字段时代两把 key 填过哪把算哪把）；
 * 拆分为方舟（大模型）/豆包语音（合成）两个字段时按 key 形态归类——
 * 方舟 key 带 `ark-` 前缀，豆包语音 key 是 UUID 形。返回 (大模型key, 语音key)。
 */
fun splitLegacyVolcanoKey(legacy: String): Pair<String, String> {
    val v = legacy.trim()
    return if (v.startsWith("ark-", ignoreCase = true)) v to "" else "" to v
}

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
 * 模型是否支持图片输入（视频模式准入门控）：只认 [AiProvider.visionLlmModels]
 * 实测清单，先经 [resolveLlmModel] 归位（跨服务商残留值不算）。
 */
fun isVisionLlm(provider: AiProvider, configured: String): Boolean =
    resolveLlmModel(provider, configured) in provider.visionLlmModels

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
