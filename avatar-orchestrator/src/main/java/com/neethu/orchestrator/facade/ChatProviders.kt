package com.neethu.orchestrator.facade

import com.neethu.aiadapter.text.EdgeTtsTexts
import com.neethu.aiadapter.text.VolcanoTtsTexts
import com.neethu.corelib.Lang
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AI 服务商目录（SDK 侧单一事实源）：端点、可选模型、音色清单与默认值全部
 * 收口在这一个枚举——[AIAvatarSdk.ChatConfig] 除 API Key 外全部按目录解析，
 * 模型/音色留空即用服务商默认（见 [resolveLlmModel]/[resolveTtsModel]/
 * [resolveVoice]）。Demo app 的 `AiProvider` 枚举只包一层 UI 标签，事实数据
 * 全部委托到这里，改清单只动这一处。
 *
 * 模型/音色清单均经真实接口核验（LLM 三项在 /v1/models 在册；硅基流动 TTS
 * 两模型音色引用互认，火山 seed-tts-2.0 六音色实测可合成），默认值按需求指定。
 */
enum class ChatProvider(
    /** OpenAI 兼容端点（火山即方舟 Ark v3，chat/completions 同构）。 */
    val baseUrl: String,
    val llmModels: List<String>,
    /**
     * 清单里支持图片输入（多模态）的子集——视觉能力的准入门控依据。
     * 2026-10-03 用真实请求核验（64px 红/蓝图 chat 回色=过，报
     * "not a VLM"/答非所问=不过）：硅基流动 DeepSeek 系明确拒图；
     * 火山 doubao 系全部真看图；`deepseek-v4-flash-ga` 是假视觉——API
     * 收下 image_url 但答"image data incomplete"（同图 doubao 描述正确），
     * 必须排除；`doubao-seedream-5-0-pro` 是图像生成模型，chat 不可用照旧排除。
     */
    val visionLlmModels: List<String>,
    /** TTS 请求体里的 model id。 */
    val ttsModels: List<String>,
    val defaultTtsModel: String,
    /**
     * 音色引用前缀：硅基流动的请求音色格式是 `FunAudioLLM/CosyVoice2-0.5B:<短名>`，
     * 且实测 MOSS-TTSD 也接受该引用（两模型音色互认）；火山音色 id 原样发送（null）。
     */
    val voicePrefix: String?,
    /** 音色清单接口拉不到时的静态兜底。 */
    val voices: List<String>,
    val defaultVoice: String,
) {
    SILICONFLOW(
        baseUrl = "https://api.siliconflow.cn/v1",
        llmModels = listOf(
            "deepseek-ai/DeepSeek-V4-Flash",
            "deepseek-ai/DeepSeek-V3",
            "Qwen/Qwen3.8-27B",
            "Qwen/Qwen3-VL-32B-Instruct",
        ),
        visionLlmModels = listOf(
            // VL-32B 刻意在前（defaultLlmModel 视觉优先取首位）：非思考模型、
            // 工具调用矩阵 3/3、观察链路真机验证过；Qwen3.8-27B 关思考下工具
            // 可靠性 ~4/10（矩阵文档），排后
            "Qwen/Qwen3-VL-32B-Instruct",
            "Qwen/Qwen3.8-27B",
        ),
        ttsModels = listOf(
            "FunAudioLLM/CosyVoice2-0.5B",
            "fnlp/MOSS-TTSD-v0.5",
        ),
        // 默认 CosyVoice2（2026-10-11 用户实测反馈：MOSS-TTSD 断句不精准太拉胯）；
        // 显式选过 MOSS 的存量用户不受影响（resolveTtsModel 只在清单外/留空落默认）
        defaultTtsModel = "FunAudioLLM/CosyVoice2-0.5B",
        voicePrefix = "FunAudioLLM/CosyVoice2-0.5B",
        voices = listOf("alex", "anna", "bella", "benjamin", "charles", "claire"),
        defaultVoice = "anna",
    ),
    VOLCANO(
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
    ),
    OPENROUTER(
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
    ),
    /**
     * DeepSeek 官方 API（api.deepseek.com，OpenAI 兼容）：**只有 chat**——
     * /audio/speech、/audio/transcriptions、/embeddings 实测全 404
     * （2026-10-18 真 key 核验）。模型清单取自 /models 在册：`deepseek-flash`
     * （DeepSeek-V4.1-Flash，1M 上下文，输入 text+image=真视觉，64px 红/蓝图
     * 逐张实测描述正确）与 `deepseek-v4-pro`（纯文本，喂图明说看不到，排除出
     * 视觉清单）。两模型默认开思考但走独立 `reasoning_content` 字段——适配器
     * 只读 delta.content，不污染输出；简单问答全程 <1s，`effort` 参数三档实测
     * 无感（不发，默认即可）。无自有 TTS/ASR：TTS 在 facade 回落 Edge-TTS
     * （见 [AIAvatarSdk.ChatConfig.ttsEngineResolved]），ASR 回落硅基流动
     * （见 [asrProviderFor]）。
     */
    DEEPSEEK(
        baseUrl = "https://api.deepseek.com/v1",
        llmModels = listOf(
            "deepseek-flash",
            "deepseek-v4-pro",
        ),
        visionLlmModels = listOf(
            "deepseek-flash",
        ),
        // 官方无语音合成端点（实测 404）：清单留空，OpenAI 兼容引擎选到它时
        // ttsEngineResolved 整体回落 Edge-TTS，模型/音色/Key 三行配置全部旁路
        ttsModels = emptyList(),
        defaultTtsModel = "",
        voicePrefix = null,
        voices = emptyList(),
        defaultVoice = "",
    ),
    /**
     * 阿里云百炼（第五服务商，2026-10-18 真 key 全链核验）：OpenAI 兼容 chat
     * 走企业专属端点（ws-*.maas.aliyuncs.com，与公共 dashscope compatible-mode
     * 同 key 双通），/models 在册 176 模型（含 GLM/kimi/MiniMax 等第三方，目录
     * ≠可调——实测有 403 无权限与 not exist 的在册项，实时清单仅供挑选）。
     * 流式/工具调用/图片输入均为标准 OpenAI 形态；qwen3.8 系混合思考默认开、
     * 走独立 reasoning_content 字段（适配器只读 delta.content），`enable_thinking:
     * false` 实测有效且 qwen 全系无害——glm 系例外（「restricted to True」400），
     * 关思考方言只对 qwen* 发（见 [llmExtraBody]）。语音两端点均为定制形态：
     * TTS 走 DashScope 原生协议（/audio/speech 实测 404，见
     * [com.neethu.aiadapter.bailian.BailianTtsAdapter]），ASR 走 chat+input_audio
     * （/audio/transcriptions 实测 404，见
     * [com.neethu.aiadapter.bailian.BailianAsrAdapter]）——同一把 key 三端通用。
     * 静态清单仅收 chat/视觉实测过的模型，全量目录经 /models 实时拉取。
     */
    BAILIAN(
        baseUrl = "https://ws-xej6bspqc7ct974u.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
        llmModels = listOf(
            "qwen3.8-flash", // 64px 红/蓝图实测真视觉（usage 计 image_tokens）
            "qwen3-vl-plus", // 视觉专项
            "qwen-flash-character", // 角色扮演向
            "deepseek-v4.1-flash",
            "kimi-k3",
            "qwen-plus",
        ),
        visionLlmModels = listOf(
            "qwen3.8-flash",
            "qwen3-vl-plus",
        ),
        ttsModels = listOf("qwen3-tts-flash"),
        defaultTtsModel = "qwen3-tts-flash",
        voicePrefix = null,
        // 音色逐一实测可合成（Cherry 女声默认；Ethan/Jennifer/Ryan 男声，Serena/Chelsie 女声）
        voices = listOf("Cherry", "Serena", "Ethan", "Chelsie", "Jennifer", "Ryan"),
        defaultVoice = "Cherry",
    ),
    ;

    /**
     * 大模型默认模型：**视觉优先**（2026-10-18 需求：默认即可看图——视频模式/
     * observe_camera 开箱即用），取视觉清单中第一个在 LLM 清单里的模型，视觉
     * 清单为空才落 LLM 清单首位。硅基流动视觉序刻意 VL-32B 在前（非思考模型，
     * 工具调用矩阵 3/3；Qwen3.8-27B 关思考工具可靠性差，见矩阵文档）。
     */
    val defaultLlmModel: String
        get() = visionLlmModels.firstOrNull { it in llmModels } ?: llmModels.first()
}

/**
 * 语音合成引擎：OpenAI 兼容（跟随 [ChatConfig] 的 TTS 服务商配置，需要 Key）
 * 或 Edge-TTS（微软 Edge「大声朗读」接口，免费、无需 baseUrl/Key；接口无 SLA，
 * 失败由 [com.neethu.orchestrator.session.AvatarEvent.TurnFailed] 给出提示）。
 */
enum class TtsEngine {
    OPENAI_COMPATIBLE,
    EDGE,
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

/**
 * 把存储的音色值拼成请求用完整引用：已含 `:`（完整引用/自定义音色 URI）原样，
 * 短名补前缀，火山原样。空串返回空串（由 [resolveVoice] 落到默认值）。
 */
fun composeVoiceRef(provider: ChatProvider, voice: String): String = when {
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
 * 不存在清单外模型——校验兜住 UI / 调试命令 / 旧 prefs 三条写入路径的跨服务商
 * 泄漏（真机实测：切火山后 DeepSeek-V3 残留被原样发给 Ark）。自定义端点
 * （[AIAvatarSdk.ChatConfig.baseUrl] 覆盖）不走校验，模型名原样透传。
 *
 * [extraAllowed] = /models 实时拉取的目录 id（内存缓存，见 demo 的
 * ModelCatalogCache）：实时清单里的模型不在静态清单也放行，重启后缓存清空、
 * 拉回之前自然恢复。
 */
fun resolveLlmModel(provider: ChatProvider, configured: String, extraAllowed: Set<String> = emptySet()): String {
    val v = configured.trim()
    return if (v in provider.llmModels || v in extraAllowed) v else provider.defaultLlmModel
}

/** TTS 模型解析 = 校验 + 默认（同 [resolveLlmModel] 的跨服务商防泄漏语义）。 */
fun resolveTtsModel(provider: ChatProvider, configured: String): String {
    val v = configured.trim()
    return if (v in provider.ttsModels) v else provider.defaultTtsModel
}

/**
 * LLM 请求体的服务商专属参数（[com.neethu.aiadapter.model.LlmConfig.extraBody]
 * 原样并入请求 JSON）。各家「关思考」的参数名互不相认，按 服务商+模型族 分发：
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
fun llmExtraBody(provider: ChatProvider, model: String): JsonObject? {
    val m = model.trim()
    return when {
        provider == ChatProvider.VOLCANO && m.startsWith("doubao-seed") ->
            buildJsonObject { put("thinking", buildJsonObject { put("type", "disabled") }) }
        provider == ChatProvider.SILICONFLOW && m.startsWith("Qwen/Qwen3") ->
            buildJsonObject { put("enable_thinking", false) }
        provider == ChatProvider.OPENROUTER && m.startsWith("qwen/") ->
            buildJsonObject {
                put("reasoning", buildJsonObject { put("enabled", false) })
            }
        // 百炼：qwen3.8 系混合思考默认开（reasoning_content 独立字段+首句前
        // 隐形等待），enable_thinking:false 实测有效且 qwen 全系无害（qwen-plus
        // 实测接受；老 qwen 系该工作区本就 403 无权限）。⚠ 只对 qwen* 发——
        // glm-5.3-prime 实测「enable_thinking is restricted to True」400，
        // kimi/deepseek 系不认识该参数
        provider == ChatProvider.BAILIAN && m.startsWith("qwen") ->
            buildJsonObject { put("enable_thinking", false) }
        else -> null
    }
}

/**
 * 语音识别（ASR）实际生效的服务商：OpenRouter 的 /audio/transcriptions 与
 * 硅基流动同构（OpenAI 兼容 multipart），跟随大模型服务商共用那把 key；
 * 百炼走自家 chat+input_audio 形态（同商直连，见
 * [com.neethu.aiadapter.bailian.BailianAsrAdapter]）；火山与 DeepSeek 官方不
 * 提供云端 ASR 端点，回落硅基流动（既有行为：这两家的大模型用户需另填硅基
 * 流动 Key 做语音识别，或改用系统识别引擎）。
 */
fun asrProviderFor(llmProvider: ChatProvider): ChatProvider =
    if (llmProvider == ChatProvider.VOLCANO || llmProvider == ChatProvider.DEEPSEEK) {
        ChatProvider.SILICONFLOW
    } else {
        llmProvider
    }

/** ASR 模型的显式候选（按端点）；留空时的自动推断见 [resolveAsrModel]。 */
fun asrExplicitAsrModels(provider: ChatProvider): List<String> = when (provider) {
    ChatProvider.SILICONFLOW -> listOf("Qwen/Qwen3-ASR-1.7B")
    ChatProvider.OPENROUTER -> listOf("openai/whisper-large-v3")
    ChatProvider.BAILIAN -> listOf("qwen3-asr-flash")
    ChatProvider.VOLCANO, ChatProvider.DEEPSEEK -> emptyList()
}

/**
 * ASR 模型留空时按端点推断：硅基流动 → `Qwen/Qwen3-ASR-1.7B`（其
 * /audio/transcriptions 端点的默认语音识别模型），OpenRouter →
 * `openai/whisper-large-v3`（OpenRouter 文档示例模型，multipart 实测在册），
 * 百炼 → `qwen3-asr-flash`（chat+input_audio 形态），其他 → OpenAI 的
 * `whisper-1`。
 */
fun resolveAsrModel(baseUrl: String, configured: String): String =
    configured.trim().ifBlank {
        when {
            baseUrl.contains("siliconflow", ignoreCase = true) -> "Qwen/Qwen3-ASR-1.7B"
            baseUrl.contains("openrouter.ai", ignoreCase = true) -> "openai/whisper-large-v3"
            baseUrl.contains("aliyuncs.com", ignoreCase = true) -> "qwen3-asr-flash"
            else -> "whisper-1"
        }
    }

/**
 * 模型是否支持图片输入（视觉能力准入门控）：静态实测清单优先，清单外的
 * 实时目录模型走命名启发式（[looksLikeVisionModel]）。先经 [resolveLlmModel]
 * 归位（跨服务商残留值不算）。
 */
fun isVisionLlm(provider: ChatProvider, configured: String): Boolean {
    val resolved = resolveLlmModel(provider, configured)
    if (resolved in provider.visionLlmModels) return true
    return looksLikeVisionModel(provider, resolved)
}

/** 视觉命名启发式的判定词元（qwen3-vl-/vision-/omni 系实测真视觉）。 */
private val VISION_NAME_TOKEN = Regex("(^|[-./_])vl([-_@./]|$|[0-9])|vision|omni", RegexOption.IGNORE_CASE)

/**
 * 实时目录模型的视觉能力命名启发式（静态清单的补充，设置页「视觉」标签与
 * 视频模式门控同源）：id 含 vl/vision/omni 词元 → 视觉；火山的 doubao-seed
 * 系对话模型全族带图（Ark /models 的 domain=VLM 实证，2026-10-18），图像/
 * 视频生成系（seedream/seedance/seed3d/translation）除外。启发式可能漏标
 * （如不带词元的视觉新模型），宁可漏标不误标——误标会让视频模式发图给纯
 * 文本模型。
 */
fun looksLikeVisionModel(provider: ChatProvider, modelId: String): Boolean {
    val m = modelId.lowercase()
    if (VISION_NAME_TOKEN.containsMatchIn(m)) return true
    if (provider == ChatProvider.VOLCANO && m.startsWith("doubao-seed")) {
        return listOf("seedream", "seedance", "seed3d", "translation").none { m.contains(it) }
    }
    return false
}

/**
 * 音色解析 = 校验 + 默认：已知音色（静态清单/接口拉取的短名或完整引用）与
 * 自定义克隆 URI（speech:…）原样放行，其余落回服务商默认音色。
 */
fun resolveVoice(provider: ChatProvider, configured: String): String {
    val v = configured.trim()
    if (v.isBlank()) return composeVoiceRef(provider, provider.defaultVoice)
    val composed = composeVoiceRef(provider, v)
    val known = provider.voices.any { composeVoiceRef(provider, it) == composed }
    return if (known || composed.startsWith(CUSTOM_VOICE_URI_PREFIX)) composed
    else composeVoiceRef(provider, provider.defaultVoice)
}

/** 界面语言 → Edge-TTS 错误文案目录（适配器模块不依赖 corelib，这里做映射）。 */
fun edgeTtsTexts(lang: Lang): EdgeTtsTexts =
    if (lang == Lang.EN) EdgeTtsTexts.EN else EdgeTtsTexts.ZH

/** 界面语言 → 火山 TTS 错误文案目录。 */
fun volcanoTtsTexts(lang: Lang): VolcanoTtsTexts =
    if (lang == Lang.EN) VolcanoTtsTexts.EN else VolcanoTtsTexts.ZH
