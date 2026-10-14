package com.neethu.aiavatar_sdk.multiavatar.show

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 点播路由的两档策略（圆桌 DirectorPolicy 同款，§4.1：LLM 只进窄口）。
 */
sealed interface RequestPolicy {
    /** 档位名（状态行/日志用）。 */
    val label: String

    /** 规则档：人名+曲目名精确匹配直接插幕，零网络成本零幻觉。 */
    data object Rule : RequestPolicy {
        override val label: String get() = "rule"
    }

    /**
     * LLM 档：一次非流式轻量调用把点播翻译成 {performer,clip,line}（便宜模型 +
     * 低温度 + 关思考，圆桌导演/提炼 prompt 同款可靠性档）。超时/垃圾输出/白名单
     * 校验失败由调用方降级——导播挂了演出不能停。
     */
    data class Llm(
        val llm: LlmAdapter,
        val config: () -> LlmConfig?,
        /** 点播是幕间动作，超时即拒绝（固定台本继续），不留悬挂等待。 */
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) : RequestPolicy {
        override val label: String get() = "llm"

        companion object {
            const val DEFAULT_TIMEOUT_MS = 8_000L
        }
    }
}

/** 在场演员条目：id（槽位）+ 显示名（规则档两处匹配、LLM 档进名册）。 */
data class RequestCastMember(val id: String, val displayName: String)

/** 点播裁决。 */
sealed interface RequestDecision {
    /** 命中插幕：act 的 clip 已归一成完整 assets 路径；track=rule/llm。 */
    data class Insert(val act: ShowAct, val track: String, val reason: String) : RequestDecision

    /** 拒绝（只留提示，演出照常推进——固定台本无缝续演，§7-P2 验收口径）。 */
    data class Reject(val reason: String) : RequestDecision
}

/**
 * 点播路由（P2，docs/multi-dance-feasibility.md §7-P2）：先规则后 LLM。
 * 规则档 = 演员名+曲目名都精确命中才直接插幕（否则信息不全交给 LLM 补全）；
 * LLM 档 = 输入在场名册+曲目白名单+点播文本，输出 JSON 过**双向白名单校验**
 * （人不在场/曲不在单 → 拒绝）。防失控三件套沿用：白名单 + 校验失败降级 +
 * 串场词长度硬上限。纯 JVM 可单测；LLM 调用次数/失败/token 在 [costLine]。
 */
class DanceRequestRouter(private val policy: RequestPolicy) {

    /** LLM 调用与失败计数（成本记录，`dance log` 可复盘）。 */
    var llmCalls = 0
        private set
    var llmFails = 0
        private set

    /** 最近一次调用的耗时与 token 用量（服务商未报明细时为 0）。 */
    var lastLatencyMs = 0L
        private set
    var tokPrompt = 0
        private set
    var tokCompletion = 0
        private set

    val policyLabel: String get() = policy.label

    /**
     * 点播入口：先规则档；未中走 LLM 档（未开启/缺 Key/超时/垃圾输出/白名单
     * 校验失败 → Reject，原因行供 director_log 回显）。
     */
    suspend fun route(
        text: String,
        cast: List<RequestCastMember>,
        whitelist: Set<String>,
    ): RequestDecision {
        ruleTrack(text, cast, whitelist)?.let { return it }
        val llmPolicy = policy as? RequestPolicy.Llm
            ?: return RequestDecision.Reject(
                "规则档未命中（精确报「演员+曲目名」可直接插幕；LLM 导播未开启，dance auto on 打开）",
            )
        val cfg = llmPolicy.config()?.takeIf { it.model.isNotBlank() }
            ?: return RequestDecision.Reject("LLM 导播缺 Key（配硅基流动 Key 后自然语言点播生效）")
        llmCalls++
        val startedAt = System.currentTimeMillis()
        val result = withTimeoutOrNull(llmPolicy.timeoutMs) {
            runCatching { complete(llmPolicy.llm, buildMessages(text, cast, whitelist), cfg) }.getOrNull()
        }
        lastLatencyMs = System.currentTimeMillis() - startedAt
        if (result == null) {
            llmFails++
            return RequestDecision.Reject("LLM 超时/网络失败（兜底${llmPolicy.timeoutMs}ms），固定台本继续")
        }
        val (raw, usage) = result
        tokPrompt += usage?.promptTokens ?: 0
        tokCompletion += usage?.completionTokens ?: 0
        return parse(raw, cast, whitelist)
            ?: run {
                llmFails++
                RequestDecision.Reject("LLM 输出未过白名单校验: ${raw.take(RAW_SNIPPET)}")
            }
    }

    /**
     * 串场词润色（§7-P2，默认关）：LLM 按表演者一句话变体；关闭/缺 Key/失败
     * 一律返回 null = 调用方用原文（失败降级原文）。
     */
    suspend fun polishLine(performerName: String, text: String): String? {
        val llmPolicy = policy as? RequestPolicy.Llm ?: return null
        val cfg = llmPolicy.config()?.takeIf { it.model.isNotBlank() } ?: return null
        llmCalls++
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, POLISH_SYSTEM),
            ChatMessage(ChatRole.USER, "表演者：$performerName\n原句：$text"),
        )
        val out = withTimeoutOrNull(POLISH_TIMEOUT_MS) {
            runCatching { complete(llmPolicy.llm, messages, cfg) }.getOrNull()
        }
        val polished = out?.first?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_LINE_CHARS)
        if (polished == null) {
            llmFails++
            return null
        }
        out.second?.let {
            tokPrompt += it.promptTokens
            tokCompletion += it.completionTokens
        }
        return polished
    }

    // ── 规则档 ───────────────────────────────────────────────────────────

    /**
     * 人名+曲目名都精确命中才插幕（只中其一=信息不全，交给 LLM 补全）。
     * 未命中返回 null（调用方继续 LLM 档）。
     */
    internal fun ruleTrack(
        text: String,
        cast: List<RequestCastMember>,
        whitelist: Set<String>,
    ): RequestDecision? {
        val performer = cast.firstOrNull { matchesToken(text, it.displayName) || matchesToken(text, it.id) }
            ?: return null
        val clip = matchClip(text, whitelist) ?: return null
        return RequestDecision.Insert(
            insertAct(performer.id, clip, line = null),
            track = "rule",
            reason = "规则档精确命中",
        )
    }

    /**
     * ASCII 词（槽位 id 多为单字母）用词边界匹配防误中——「bad」不能命中 id
     * 「a」；中文（显示名）用包含匹配。
     */
    internal fun matchesToken(text: String, token: String): Boolean {
        if (token.isBlank()) return false
        return if (token.all { it.code < 128 }) {
            Regex("(^|[^0-9A-Za-z])${Regex.escape(token)}($|[^0-9A-Za-z])", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        } else {
            text.contains(token, ignoreCase = true)
        }
    }

    /**
     * 曲目名匹配：归一化（小写+去空白）后的文件名（不含 .vrma）是文本子串即中，
     * 多个命中取名字最长的（最具体）。过短名（<6 字符归一化后）不参与，防两三
     * 个字母的子串满天命中。
     */
    internal fun matchClip(text: String, whitelist: Set<String>): String? {
        val normText = normalize(text)
        return whitelist.map { it to normalize(it.substringAfterLast('/').removeSuffix(".vrma")) }
            .filter { (path, key) -> key.length >= MIN_CLIP_KEY_LEN && normText.contains(key) }
            .maxByOrNull { it.second.length }?.first
    }

    private fun normalize(s: String): String = s.lowercase().filter { !it.isWhitespace() }

    // ── LLM 档 ───────────────────────────────────────────────────────────

    /** 非流式语义（圆桌 LlmDirector 同款）：收集 TextDelta，Error 直接抛。 */
    private suspend fun complete(
        llm: LlmAdapter,
        messages: List<ChatMessage>,
        cfg: LlmConfig,
    ): Pair<String, com.neethu.aiadapter.model.LlmUsage?> {
        val reply = StringBuilder()
        var usage: com.neethu.aiadapter.model.LlmUsage? = null
        llm.streamChat(messages, cfg.copy(temperature = LLM_TEMPERATURE)).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> reply.append(event.text)
                is LlmStreamEvent.Finish -> usage = event.usage
                is LlmStreamEvent.Error -> throw event.throwable
                else -> Unit
            }
        }
        return reply.toString() to usage
    }

    internal fun buildMessages(
        text: String,
        cast: List<RequestCastMember>,
        whitelist: Set<String>,
    ): List<ChatMessage> {
        val roster = cast.joinToString("\n") { "- ${it.id}（${it.displayName}）" }
        val clips = whitelist.map { it.removePrefix("animations/").removeSuffix(".vrma") }
            .sorted().joinToString("\n")
        val user = buildString {
            appendLine("在场演员：")
            appendLine(roster)
            appendLine("曲目清单（类别/文件名，点播跳舞优先从 08_舞蹈与表演 类挑）：")
            appendLine(clips)
            append("观众点播：「$text」。请从清单里翻译成一场插入演出并输出 JSON。")
        }
        return listOf(
            ChatMessage(ChatRole.SYSTEM, ROUTER_SYSTEM),
            ChatMessage(ChatRole.USER, user),
        )
    }

    /**
     * 解析+白名单双向校验：截首个平衡 JSON（圆桌 LlmDirector.parse 同款），
     * performer 解析回在场演员 id（id 或显示名）、clip 归一后必须在白名单内；
     * 任何一关不过返回 null = 调用方拒绝降级。
     */
    internal fun parse(
        raw: String,
        cast: List<RequestCastMember>,
        whitelist: Set<String>,
    ): RequestDecision? {
        val obj = firstJsonObject(raw)?.let {
            runCatching { lenientJson.parseToJsonElement(it) }.getOrNull() as? JsonObject
        } ?: return null
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.content?.trim()
        val performer = resolvePerformer(str("performer").orEmpty(), cast) ?: return null
        val clip = str("clip")?.takeIf { it.isNotBlank() }
            ?.let { resolveClipAsset(it) }?.takeIf { it in whitelist } ?: return null
        val line = str("line")?.takeIf { it.isNotBlank() }?.take(MAX_LINE_CHARS)
        val reason = str("reason")?.take(24).orEmpty().ifEmpty { "LLM 点播" }
        return RequestDecision.Insert(
            insertAct(performer, clip, line),
            track = "llm",
            reason = reason,
        )
    }

    /** 点播文本里的演员 → 在场演员 id（id/显示名精确相等优先，容忍「让A跳」截出的 id）。 */
    internal fun resolvePerformer(raw: String, cast: List<RequestCastMember>): String? {
        if (raw.isBlank()) return null
        return cast.firstOrNull {
            it.id.equals(raw, true) || (it.displayName.isNotBlank() && it.displayName.equals(raw, true))
        }?.id
            ?: cast.firstOrNull { matchesToken(raw, it.id) }?.id
    }

    /** 插出的独舞幕：FULL 锚定表演者 + 应援鼓掌，与默认节目单同款形态。 */
    private fun insertAct(performer: String, clip: String, line: String?) = ShowAct(
        type = ActTypes.DANCE,
        performer = performer,
        clip = clip,
        camera = "full",
        fans = listOf(FanActions.CLAP),
        line = line,
    )

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 截首个平衡的 `{...}`（orchestrator MemoryParser 同款算法；app 侧不可见
     * 其 internal 成员，此处复制——模型偶尔在 JSON 前后带客套话或 markdown 围栏）。
     * 字符串里的花括号不计数。
     */
    private fun firstJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 成本行（状态/日志回显：调用次数、失败、最近时延、累计 token）。 */
    fun costLine(): String =
        "llm调用${llmCalls}次(败${llmFails}) 最近${lastLatencyMs}ms tok=${tokPrompt}+${tokCompletion}"

    private companion object {
        val ROUTER_SYSTEM =
            "你是虚拟人团播的导播。观众用一句话点播（可能口语、含糊），你把它翻译成一场插入演出。" +
                "只输出一个 JSON 对象：" +
                "{\"performer\":\"演员id\",\"clip\":\"清单里的曲目\",\"line\":\"不超过20字的串场词\",\"reason\":\"不超过12字理由\"}；" +
                "清单里挑不出合适曲目时输出 {\"performer\":\"\",\"clip\":\"\",\"reason\":\"无合适曲目\"}。" +
                "严禁发明清单外的任何演员或曲目，不要输出其他内容。"

        val POLISH_SYSTEM =
            "你是团播串场词润色师。把给定的串场词改写成一句意思相同的口语变体（不超过20字），保持热情的主持人语气。" +
                "只输出改写后的一句话本身，不要引号、不要解释。"

        /** 润色是幕内动作（演出在等串场词），超时更短，失败即用原文。 */
        const val POLISH_TIMEOUT_MS = 5_000L

        /** 串场词长度硬上限（防失控三件套之三）。 */
        const val MAX_LINE_CHARS = 30

        /** 拒绝原因里附带的原始输出截断长度（复盘用）。 */
        const val RAW_SNIPPET = 48

        /** 便宜模型低温度（圆桌导演/提炼 prompt 同款档）。 */
        const val LLM_TEMPERATURE = 0.2f

        /** 曲目名归一化后参与规则匹配的最短长度。 */
        const val MIN_CLIP_KEY_LEN = 6
    }
}
