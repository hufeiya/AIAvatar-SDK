package com.neethu.aiavatar_sdk

import com.neethu.aiadapter.openai.ModelCatalogEntry
import com.neethu.aiadapter.openai.OpenAiCompatibleModelCatalog
import com.neethu.orchestrator.facade.looksLikeVisionModel

/** 模型下拉的一个选项：[vision] = 是否带「视觉」标签（判定链见 [chatModelOptions]）。 */
data class ModelOption(val id: String, val vision: Boolean)

/**
 * 大模型实时目录的**进程内缓存**：设置页模型下拉展开时若无缓存则拉
 * `GET {base}/models`，拉到的全量目录按服务商暂存。刻意不落 prefs——
 * 模型版本实时更新，退出应用再进来重新拉（需求语义：重启即失效）。
 * 线程安全：写只发生在 UI 协程回投后，并发访问用 [java.util.concurrent] 兜底。
 */
object ModelCatalogCache {
    private val cache = java.util.concurrent.ConcurrentHashMap<AiProvider, List<ModelCatalogEntry>>()

    /** 该服务商是否已有缓存（有则下拉直接用，不重复请求）。 */
    fun has(provider: AiProvider): Boolean = cache.containsKey(provider)

    fun entriesFor(provider: AiProvider): List<ModelCatalogEntry> = cache[provider].orEmpty()

    /** 实时目录 id 集（喂给 [resolveLlmModel] 的校验放行，见 ChatConfig.catalogLlmModels）。 */
    fun idsFor(provider: AiProvider): Set<String> = cache[provider]?.map { it.id }?.toSet().orEmpty()

    fun put(provider: AiProvider, entries: List<ModelCatalogEntry>) {
        cache[provider] = entries
    }

    /** 拉取（网络在 IO 线程，失败/为空返回空列表由调用方回退静态清单）。 */
    suspend fun fetch(provider: AiProvider, apiKey: String): List<ModelCatalogEntry> {
        if (apiKey.isBlank()) return emptyList()
        val entries = OpenAiCompatibleModelCatalog().fetch(provider.baseUrl, apiKey)
        if (entries.isNotEmpty()) put(provider, entries)
        return entries
    }
}

/**
 * 非对话模型的目录过滤（匹配前转小写）。各家 /models 目录都混着非 chat 条目
 * （embedding/rerank/图像视频生成/语音识别合成/翻译/研究代理/内部测试桩），
 * 2026-10-18 按百炼 176 模型目录逐族实测校准：
 * - 实测可调被误杀风险高的族**不在**表里：gui-plus（chat 实测 200）、
 *   qwen-flash-character（角色扮演，chat 实测 200）、coder/math 系（chat 可调）；
 * - 实测不可调的 ^test-/^sre-/cmaas- 前缀（内部测试桩/企业变体）剔除；
 * - ocr 专项剔除（qwen-vl-ocr 只做文档解析，非通用对话）。
 */
private val NON_CHAT_PATTERNS = listOf(
    // 向量/排序
    Regex("embedding|rerank|bge-"),
    // 语音族（识别/合成/克隆）
    Regex("tts|asr|speech|cosyvoice|sambert|paraformer|sensevoice|whisper|unisound|moss-"),
    // 图像/视频/3D 生成
    Regex("image|diffusion|flux|seedream|seedance|seed3d|wan[0-9.]|-3d|3d-|^hyper3d|hitem3d|video"),
    // 文档解析/翻译/研究代理/路由（⚠ smart-router 全词，裸 router 会误杀 openrouter）
    Regex("ocr|^qwen-mt|translat|research|deep-search|search-planning|smart-router"),
    // 实时音频/语音对话（WebSocket 专线模型，非 chat 文本形态）
    Regex("realtime|-s2s-|livetranslate"),
    // 内部测试桩/企业专属变体（chat 实测 400/不可调）
    Regex("^test-|^sre-|^cmaas-"),
    // 百炼目录里的古董代次（qwen1.5/qwen2.x/qwen-7b-chat 时代，工作区实测 403 无权限）
    Regex("^qwen1|^qwen2|^qwen-[0-9]"),
)

/**
 * 全量目录 → 模型下拉选项（纯函数，JVM 可测）：
 * 1. 元数据过滤（有则权威）：火山 Ark 的 domain 只留 LLM/VLM、status 剔除
 *    Shutdown（Retiring 保留=可用但将下线）；OpenRouter 只留 text 输出模型；
 * 2. 名字过滤兜底（目录无元数据的服务商：硅基流动/百炼/DeepSeek）；
 * 3. 视觉标注判定链：元数据（火山 domain=VLM / OpenRouter 含 image 输入）
 *    → 静态实测清单（[AiProvider.visionLlmModels]）→ 命名启发式
 *    （[looksLikeVisionModel]）；
 * 4. 静态清单兜底并集：实时目录缺项（网络子集/拉取失败前的空目录）时补上
 *    手工验证过的模型，去重保序（目录序在前）。
 */
fun chatModelOptions(provider: AiProvider, entries: List<ModelCatalogEntry>): List<ModelOption> {
    val options = mutableListOf<ModelOption>()
    val seen = mutableSetOf<String>()
    for (entry in entries) {
        if (!isChatCandidate(provider, entry)) continue
        if (seen.add(entry.id)) {
            options += ModelOption(entry.id, isVisionCandidate(provider, entry))
        }
    }
    for (id in provider.llmModels) {
        if (seen.add(id)) {
            options += ModelOption(id, id in provider.visionLlmModels)
        }
    }
    return options
}

/** 单条目录是否当作对话模型保留（元数据优先，名字兜底）。 */
internal fun isChatCandidate(provider: AiProvider, entry: ModelCatalogEntry): Boolean {
    if (provider == AiProvider.VOLCANO) {
        // Ark 元数据权威：非 LLM/VLM 域（Embedding/ImageGeneration/VideoGeneration/
        // Router/3DGeneration…）与已下架（Shutdown）都不是可调对话模型
        val domain = entry.domain?.uppercase()
        if (!domain.isNullOrEmpty() && domain != "LLM" && domain != "VLM") return false
        if (entry.status.equals("Shutdown", ignoreCase = true)) return false
    }
    if (provider == AiProvider.OPENROUTER) {
        val output = entry.outputModalities
        if (output != null && !output.any { it.equals("text", ignoreCase = true) }) return false
    }
    val id = entry.id.lowercase()
    return NON_CHAT_PATTERNS.none { it.containsMatchIn(id) }
}

/** 单条目录的视觉标注（元数据 → 静态清单 → 命名启发式）。 */
internal fun isVisionCandidate(provider: AiProvider, entry: ModelCatalogEntry): Boolean {
    if (provider == AiProvider.VOLCANO) {
        // Ark 的 domain=VLM 是官方口径，优先级最高（LLM 域=官方说纯文本，不误标）
        entry.domain?.uppercase()?.let { return it == "VLM" }
    }
    if (provider == AiProvider.OPENROUTER) {
        entry.inputModalities?.let { return it.any { m -> m.equals("image", ignoreCase = true) } }
    }
    if (entry.id in provider.visionLlmModels) return true
    return looksLikeVisionModel(provider.sdk, entry.id)
}
