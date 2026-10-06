package com.neethu.aiavatar_sdk

import com.neethu.corelib.Lang
import com.neethu.orchestrator.facade.ChatProvider

/**
 * Demo 的服务商目录：UI 层壳（中文标签/Key 输入框提示）包着 SDK 的
 * [ChatProvider] 目录——端点、模型/音色清单与默认值等**事实数据**的单一
 * 事实源在 avatar-orchestrator 的 facade 包（[AIAvatarSdk] 同源），这里只
 * 委托转发，改清单不需要动这个文件。
 *
 * 设置页除 API Key 外的所有 AI 配置都是下拉框选择，杜绝手输模型名
 * （2026-10 多厂商：硅基流动 + 火山引擎 + OpenRouter〔面向海外用户，一把
 * key 通吃大模型/TTS/ASR〕）。
 */
enum class AiProvider(
    val label: String,
    /** 事实数据源（SDK 目录）：baseUrl/模型清单/音色清单全部从这里读。 */
    val sdk: ChatProvider,
    /** 大模型服务的 API Key 字段标签/占位（火山的语音与大模型是两把 key，见下）。 */
    val llmKeyLabel: String,
    val llmKeyHint: String,
    /** 语音合成服务的 API Key 字段标签/占位。 */
    val ttsKeyLabel: String,
    val ttsKeyHint: String,
) {
    SILICONFLOW(
        label = "硅基流动",
        sdk = ChatProvider.SILICONFLOW,
        // 硅基流动一家一把 key，LLM/TTS/ASR 共用
        llmKeyLabel = "硅基流动 API Key",
        llmKeyHint = "sk-...",
        ttsKeyLabel = "硅基流动 API Key（语音合成用）",
        ttsKeyHint = "sk-...",
    ),
    VOLCANO(
        label = "火山引擎",
        sdk = ChatProvider.VOLCANO,
        // 火山是两把钥匙（2026-10-03 实测互不通用）：大模型走方舟 Ark 控制台，
        // 语音合成走豆包语音控制台——同选火山也必须各填各的
        llmKeyLabel = "火山方舟 API Key（大模型用）",
        llmKeyHint = "ark-...",
        ttsKeyLabel = "豆包语音 API Key（语音合成用）",
        ttsKeyHint = "粘贴豆包语音控制台 Key",
    ),
    OPENROUTER(
        label = "OpenRouter",
        sdk = ChatProvider.OPENROUTER,
        // 一把 key 通吃 大模型/TTS/ASR（同硅基流动模式）
        llmKeyLabel = "OpenRouter API Key",
        llmKeyHint = "sk-or-v1-...",
        ttsKeyLabel = "OpenRouter API Key（语音合成用）",
        ttsKeyHint = "sk-or-v1-...",
    ),
    ;

    /** OpenAI 兼容端点（火山即方舟 Ark v3，chat/completions 同构）。 */
    val baseUrl: String get() = sdk.baseUrl
    val llmModels: List<String> get() = sdk.llmModels
    /** 清单里支持图片输入（多模态）的子集——视频模式准入门控的实测清单。 */
    val visionLlmModels: List<String> get() = sdk.visionLlmModels
    val ttsModels: List<String> get() = sdk.ttsModels
    val defaultTtsModel: String get() = sdk.defaultTtsModel
    val voicePrefix: String? get() = sdk.voicePrefix
    val voices: List<String> get() = sdk.voices
    val defaultVoice: String get() = sdk.defaultVoice

    /** 大模型默认模型（清单首位）。 */
    val defaultLlmModel: String get() = sdk.defaultLlmModel

    /** TTS 模型展示名：剥掉组织前缀（fnlp/MOSS-TTSD-v0.5 → MOSS-TTSD-v0.5）。 */
    fun ttsModelLabel(id: String): String = id.substringAfterLast('/')

    /** 音色显示名：剥掉引用前缀（…:anna → anna）；火山 id 本身就是显示名。 */
    fun voiceLabel(value: String): String = value.substringAfterLast(':')
}

/** TTS 引擎枚举本体在 SDK 目录（门面同源）；demo 只补 UI 展示名。 */
typealias TtsEngine = com.neethu.orchestrator.facade.TtsEngine

/** TTS 引擎的中文展示名（设置页下拉框；i18n 版本见 [Strings.ttsEngine]）。 */
val TtsEngine.label: String
    get() = when (this) {
        TtsEngine.OPENAI_COMPATIBLE -> "OpenAI 兼容"
        TtsEngine.EDGE -> "Edge-TTS（免费无 Key）"
    }

/** Edge-TTS 音色目录本体在 SDK 目录（门面同源）。 */
typealias EdgeTtsCatalog = com.neethu.orchestrator.facade.EdgeTtsCatalog

/** ASR 模型按端点自动推断——正本在 SDK 目录（Qwen3-ASR/whisper 按 baseUrl 分发）。 */
fun resolveAsrModel(baseUrl: String, configured: String): String =
    com.neethu.orchestrator.facade.resolveAsrModel(baseUrl, configured)

/** Edge-TTS 音色解析 = 校验 + 默认（跨引擎残留值落默认，默认音色随语言）。 */
fun resolveEdgeVoice(configured: String, lang: Lang = Lang.ZH): String =
    com.neethu.orchestrator.facade.resolveEdgeVoice(configured, lang)

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

// ── SDK 目录解析函数的 demo 包装（签名里的 AiProvider → sdk 目录，逻辑正本
//    在 com.neethu.orchestrator.facade，app 内调用点/测试保持原名直调）────

/**
 * 把存储的音色值拼成请求用完整引用：已含 `:`（完整引用/自定义音色 URI）原样，
 * 短名补前缀，火山原样。空串返回空串（由 [resolveVoice] 落到默认值）。
 */
fun composeVoiceRef(provider: AiProvider, voice: String): String =
    com.neethu.orchestrator.facade.composeVoiceRef(provider.sdk, voice)

/** LLM 模型解析 = 校验 + 默认（清单外/留空/跨服务商残留一律落服务商默认）。 */
fun resolveLlmModel(provider: AiProvider, configured: String): String =
    com.neethu.orchestrator.facade.resolveLlmModel(provider.sdk, configured)

/** TTS 模型解析 = 校验 + 默认（同 [resolveLlmModel] 的跨服务商防泄漏语义）。 */
fun resolveTtsModel(provider: AiProvider, configured: String): String =
    com.neethu.orchestrator.facade.resolveTtsModel(provider.sdk, configured)

/** 音色解析 = 校验 + 默认；自定义克隆 URI（speech:…）原样放行。 */
fun resolveVoice(provider: AiProvider, configured: String): String =
    com.neethu.orchestrator.facade.resolveVoice(provider.sdk, configured)

/** LLM 请求体的服务商专属参数（各家「关思考」方言，见 SDK 目录 KDoc）。 */
fun llmExtraBody(provider: AiProvider, model: String) =
    com.neethu.orchestrator.facade.llmExtraBody(provider.sdk, model)

/** ASR 实际生效的服务商：火山缺 OpenAI 兼容 ASR 端点，回落硅基流动。 */
fun asrProviderFor(llmProvider: AiProvider): AiProvider =
    when (com.neethu.orchestrator.facade.asrProviderFor(llmProvider.sdk)) {
        ChatProvider.SILICONFLOW -> AiProvider.SILICONFLOW
        ChatProvider.VOLCANO -> AiProvider.VOLCANO
        ChatProvider.OPENROUTER -> AiProvider.OPENROUTER
    }

/** ASR 模型下拉的显式候选（「自动」之外）；留空时按端点推断见 [resolveAsrModel]。 */
fun asrExplicitAsrModels(provider: AiProvider): List<String> =
    com.neethu.orchestrator.facade.asrExplicitAsrModels(provider.sdk)

/** 模型是否支持图片输入（视频模式准入门控；先经 [resolveLlmModel] 归位）。 */
fun isVisionLlm(provider: AiProvider, configured: String): Boolean =
    com.neethu.orchestrator.facade.isVisionLlm(provider.sdk, configured)
