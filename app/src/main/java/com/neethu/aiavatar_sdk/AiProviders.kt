package com.neethu.aiavatar_sdk

import com.neethu.corelib.Lang
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AI 服务商目录：端点、可选模型、音色清单与默认值全部收口在这一个枚举——
 * 设置页除 API Key 外的所有 AI 配置都是下拉框选择，杜绝手输模型名（2026-10
 * 多厂商：硅基流动 + 火山引擎 + OpenRouter〔面向海外用户，一把 key 通吃
 * 大模型/TTS/ASR〕）。
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
    OPENROUTER(
        label = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        // 2026-10-05 host 实测核验（64px 红图逐个过=真视觉；gemini-3.8/gpt-5-nano
        // 系 reasoning 强制开启、请求关思考 400，好在 flash 系自适应思考很快，
        // 不发参数即可；qwen3.7-flash 关思考有效、claude 默认不思考）
        llmModels = listOf(
            "google/gemini-3.8-flash",
            "openai/gpt-6-luna",
            "qwen/qwen3.7-flash",
            "anthropic/claude-sonnet-4.6",
            // 免费路由 slug（2026-10-06 实测 64px 红/蓝图逐个过=真视觉，红 4.3s/蓝
            // 10.1s）：自动路由到当前可用的免费模型，海外新手引导的默认落点（服务
            // 商默认仍是首位，存量用户不受影响）。同日候选核验失败记录：gemma-4
            // free 系 429 限流、inkling 系 403 不可用、dots-3 一次答空
            "openrouter/free",
        ),
        visionLlmModels = listOf(
            "google/gemini-3.8-flash",
            "openai/gpt-6-luna",
            "qwen/qwen3.7-flash",
            "anthropic/claude-sonnet-4.6",
            "openrouter/free",
        ),
        // OpenRouter /audio/speech 只认 mp3/pcm（wav 请求体不认）；TTS 模型
        // 各有独立音色表，只收一个默认模型防音色跨模型泄漏（同火山单模型模式）
        ttsModels = listOf("mistralai/voxtral-mini-tts-2603"),
        defaultTtsModel = "mistralai/voxtral-mini-tts-2603",
        voicePrefix = null,
        // Voxtral 30 音色的精选子集（API supported_voices 实测在册）：语言_说话人_情绪；
        // 零余额账户实测可合成（en/gb/fr 三说话人 × 核心情绪）
        voices = listOf(
            "en_paul_neutral",
            "en_paul_happy",
            "en_paul_excited",
            "en_paul_sad",
            "en_paul_confident",
            "gb_oliver_neutral",
            "gb_oliver_cheerful",
            "gb_oliver_excited",
            "gb_jane_neutral",
            "gb_jane_curious",
            "gb_jane_sarcasm",
            "fr_marie_neutral",
        ),
        defaultVoice = "en_paul_neutral",
        // 一把 key 通吃 大模型/TTS/ASR（同硅基流动模式）
        llmKeyLabel = "OpenRouter API Key",
        llmKeyHint = "sk-or-v1-...",
        ttsKeyLabel = "OpenRouter API Key（语音合成用）",
        ttsKeyHint = "sk-or-v1-...",
    ),
    ;

    /** TTS 模型展示名：剥掉组织前缀（fnlp/MOSS-TTSD-v0.5 → MOSS-TTSD-v0.5）。 */
    fun ttsModelLabel(id: String): String = id.substringAfterLast('/')

    /** 音色显示名：剥掉引用前缀（…:anna → anna）；火山 id 本身就是显示名。 */
    fun voiceLabel(value: String): String = value.substringAfterLast(':')

    /** 大模型默认模型（清单首位）。 */
    val defaultLlmModel: String get() = llmModels.first()
}

/**
 * 语音合成引擎（任务 6，开源友好）：OpenAI 兼容（跟随下方 TTS 服务商配置，
 * 需要 Key）或 Edge-TTS（微软 Edge「大声朗读」接口，免费、无需 baseUrl/Key；
 * 接口无 SLA，失败由错误条给出明确提示）。
 */
enum class TtsEngine(val label: String) {
    OPENAI_COMPATIBLE("OpenAI 兼容"),
    EDGE("Edge-TTS（免费无 Key）"),
}

/**
 * Edge-TTS 音色目录：**逐个实测可合成**（2026-10-05 host 端点探测，4 个候选
 * 名不存在被剔除），显示名与存储值一致（voice id 原样，不再加引用前缀）。
 */
object EdgeTtsCatalog {
    /** (voice id, 显示名)。 */
    val voices: List<Pair<String, String>> = listOf(
        "zh-CN-XiaoxiaoNeural" to "zh-CN-XiaoxiaoNeural（女·晓晓）",
        "zh-CN-XiaoyiNeural" to "zh-CN-XiaoyiNeural（女·晓伊）",
        "zh-CN-XiaoxuanNeural" to "zh-CN-XiaoxuanNeural（女·晓萱）",
        "zh-CN-YunxiNeural" to "zh-CN-YunxiNeural（男·云希）",
        "zh-CN-YunyangNeural" to "zh-CN-YunyangNeural（男·云扬）",
        "zh-CN-YunjianNeural" to "zh-CN-YunjianNeural（男·云健）",
        "zh-CN-YunxiaNeural" to "zh-CN-YunxiaNeural（男·云夏）",
        "zh-CN-liaoning-XiaobeiNeural" to "zh-CN-liaoning-XiaobeiNeural（女·东北）",
        "zh-CN-shaanxi-XiaoniNeural" to "zh-CN-shaanxi-XiaoniNeural（女·陕西）",
        "en-US-EmmaMultilingualNeural" to "en-US-EmmaMultilingualNeural（女·多语种）",
        "en-US-AndrewMultilingualNeural" to "en-US-AndrewMultilingualNeural（男·多语种）",
    )

    const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

    /** 英文模式的默认音色（多语种女声，中英都能读）。 */
    const val DEFAULT_VOICE_EN = "en-US-EmmaMultilingualNeural"
}

/**
 * Edge-TTS 音色解析 = 校验 + 默认（与 [resolveVoice] 同语义）：存储值不在
 * 目录（含从 OpenAI 兼容引擎切过来残留的 CosyVoice/火山音色引用）一律落默认。
 * 默认音色按语言取（多语言支持）：英文模式落英文多语种音色（中文用户不受影响
 * ——默认语言仍落晓晓）；用户显式选过的音色原样保留。
 */
fun resolveEdgeVoice(configured: String, lang: Lang = Lang.ZH): String {
    val v = configured.trim()
    if (EdgeTtsCatalog.voices.any { it.first == v }) return v
    return if (lang == Lang.EN) EdgeTtsCatalog.DEFAULT_VOICE_EN else EdgeTtsCatalog.DEFAULT_VOICE
}

/** 旧版 prefs 只有 baseUrl；按端点推断服务商（迁移用，纯函数可测）。 */
fun inferProviderFromBaseUrl(baseUrl: String): AiProvider = when {
    baseUrl.contains("volces.com", ignoreCase = true) -> AiProvider.VOLCANO
    baseUrl.contains("openrouter.ai", ignoreCase = true) -> AiProvider.OPENROUTER
    else -> AiProvider.SILICONFLOW
}

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
 * LLM 请求体的服务商专属参数（[LlmConfig.extraBody] 原样并入请求 JSON）。
 * 各家「关思考」的参数名互不相认，按 服务商+模型族 分发：
 *
 * - 火山 doubao-seed 系：`thinking: {"type":"disabled"}`。默认「自适应深度
 *   思考」，思考 token 走 `reasoning_content` 流式返回而适配器只读
 *   `delta.content`，聊天场景下全部变成首句前的纯等待（2026-10-04 真机
 *   4.5s 首句的主因，关掉后立竿见影）。
 * - 硅基流动 Qwen3 系：`enable_thinking: false`（参数平铺在请求体顶层）。
 *   Qwen3 系混合推理模型**默认开思考**，不显式传 false 就会思考——同样的
 *   `reasoning_content` 隐形等待（2026-10-04 用户实测千问慢的根因）。
 *   Qwen3-VL-Instruct 本身非思考模型，该参数对其是无害 no-op 一并带上。
 * - OpenRouter Qwen 系：统一参数 `reasoning: {"enabled": false}`（实测有效，
 *   一句话 235 思考 token → 2）。⚠ 只对 Qwen 发：OpenRouter 部分模型
 *   （gemini-3.8-flash / gpt-5-nano 系）reasoning **强制开启**，收到关思考
 *   请求直接 400 "Reasoning is mandatory"（2026-10-05 实测）；gemini flash
 *   自适应思考很快、claude/gpt-6-luna 默认不思考，都不发参数。
 *
 * 其余模型（deepseek 系等）不认识这些参数，返回 null 不发，避免严格端点 400。
 */
fun llmExtraBody(provider: AiProvider, model: String): JsonObject? {
    val m = model.trim()
    return when {
        provider == AiProvider.VOLCANO && m.startsWith("doubao-seed") ->
            buildJsonObject { put("thinking", buildJsonObject { put("type", "disabled") }) }
        provider == AiProvider.SILICONFLOW && m.startsWith("Qwen/Qwen3") ->
            buildJsonObject { put("enable_thinking", false) }
        provider == AiProvider.OPENROUTER && m.startsWith("qwen/") ->
            buildJsonObject {
                put("reasoning", buildJsonObject { put("enabled", false) })
            }
        else -> null
    }
}

/**
 * 语音识别（ASR）实际生效的服务商：OpenRouter 的 /audio/transcriptions 与
 * 硅基流动同构（OpenAI 兼容 multipart），跟随大模型服务商共用那把 key；
 * 火山不提供该形态的 ASR 端点，回落硅基流动（既有行为：火山大模型用户
 * 需另填硅基流动 Key 做语音识别）。
 */
fun asrProviderFor(llmProvider: AiProvider): AiProvider =
    if (llmProvider == AiProvider.VOLCANO) AiProvider.SILICONFLOW else llmProvider

/** ASR 模型下拉的显式候选（「自动」之外）；留空时按端点推断见 [resolveAsrModel]。 */
fun asrExplicitAsrModels(provider: AiProvider): List<String> = when (provider) {
    AiProvider.SILICONFLOW -> listOf("Qwen/Qwen3-ASR-1.7B")
    AiProvider.OPENROUTER -> listOf("openai/whisper-large-v3")
    AiProvider.VOLCANO -> emptyList()
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
