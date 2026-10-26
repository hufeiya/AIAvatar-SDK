package com.neethu.orchestrator.autonomy

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * 决策上下文（一次「现在想干嘛」询问的全部输入，[buildMessages] 的原料）。
 * 全部为投影值——FSM 状态/锚点由集成方从 [AutonomyDirector] 读出，活动清单
 * 从活动目录映射（与 LLM 工具投影同一惯例：orchestrator 不感知资产表）。
 */
data class AutonomyDecisionContext(
    /** FSM 当前状态（如 SITTING）。 */
    val state: String,
    /** 当前所在锚点 id（无 = none）。 */
    val anchorId: String?,
    /** 当前正在做的活动 id（无 = null）。 */
    val currentActivityId: String?,
    /** 现在几点（0-23，作息语境）。 */
    val hour: Int,
    /** 可做活动清单（白名单正本——输出约束与双向校验都用它）。 */
    val activities: List<AutonomyDecisionChoice>,
    /** 一行记忆事实（素材，可空——冷启动无记忆）。 */
    val memoryLine: String? = null,
    /** 最近一句对话摘要（可空）。 */
    val recentLine: String? = null,
)

/** 活动清单投影项（id/本地化名/一句话描述/类别）。 */
data class AutonomyDecisionChoice(
    val id: String,
    val label: String,
    val description: String,
    val category: String,
)

/** 一次询问的裁决：[activityId] 为 null = 「保持现状」（模型明确不想换）。 */
data class AutonomyDecision(
    val activityId: String?,
    val reason: String,
    val latencyMs: Long,
    /** 模型原始输出截断（复盘/文档评估用）。 */
    val raw: String,
)

/**
 * LLM 自发决策增强（docs/autonomy-feasibility.md §4.2 的缝,docs/prop-system-
 * feasibility.md §3.2/§7-P2.3）：FSM 状态转移时低频把「当前状态+记忆一行+活动
 * 清单」发给小模型问「现在想干嘛」，输出受限枚举（活动 id 白名单或空=保持），
 * 命中即走与 FSM/用户指令同一条 start_activity 执行链。**默认关**——确定性
 * 作息表零成本零延迟已是正本，此缝是 P2 评估项（评估结论见 prop 文档 §7 落地
 * 记录）。
 *
 * 防失控三件套沿用点播路由（DanceRequestRouter 同款纪律）：白名单双向校验
 * （清单外 id 一律按失败处理）+ 失败/超时回落本地决策（调用方在 ask 返回
 * null/keep 时照常执行原 directive）+ 低频门（minIntervalMs 内不重复问）。
 *
 * 纯 JVM 可测：LLM 调用经 [LlmAdapter] 注入,时间经 [nowMs]。
 */
class AutonomyDecisionEnhancer(
    /**
     * LLM 适配器**现取**（与 [config] 对称）：OpenAiCompatibleLlmAdapter 只认
     * 构造器 apiKey 出鉴权头,而 Key 可能运行时更换——适配器由集成方按当前
     * Key 缓存/重建后交出（验收 r1 的教训:以空 key 构造一次性适配器,真 key
     * 只进 config,401 秒败且无痕迹）。
     */
    private val llm: () -> LlmAdapter,
    private val config: () -> LlmConfig?,
    private val rng: Random = Random.Default,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** 低频门：两次询问的最小间隔（默认 8min,§4.2 的「每 5~10min」档）。 */
    var minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS

    /** 单次询问超时（超时/失败回落本地决策,不留悬挂等待）。 */
    var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    // 初始可问（0 起点会把「装配后第一次询问」锁到门限外）
    private var lastAskAtMs = Long.MIN_VALUE / 2

    // ── 评估统计（ai_cmd autonomy_decision state 的正本）─────────────────

    /** 累计询问次数（含失败）。 */
    var asks = 0
        private set

    /** 失败次数（超时/网络/输出解析不过白名单）。 */
    var fails = 0
        private set

    /** 模型换了活动的次数（接受采纳）。 */
    var overrides = 0
        private set

    /** 模型答「保持现状」的次数。 */
    var keeps = 0
        private set

    /** 最近一次时延（ms）。 */
    var lastLatencyMs = 0L
        private set

    /**
     * 最近一次失败的原因（成功/尚未询问 = null）：「缺Key」/「超时>Nms」/
     * HTTP 错误消息/「输出未过白名单: <原文截断>」。验收 r1 的教训——401 被
     * runCatching 吞进 fails++,五连败只剩秒回时延,归因全靠猜;此字段让
     * stateLine/fire 一眼可辨。
     */
    var lastError: String? = null
        private set

    /** 低频门是否已开（到点可问）。 */
    fun dueToAsk(): Boolean = nowMs() - lastAskAtMs >= minIntervalMs

    /** 评估用：绕开低频门立即问一次（ai_cmd autonomy_decision fire 干跑入口）。 */
    fun forceDue() {
        lastAskAtMs = 0L
    }

    /**
     * 问一次「现在想干嘛」。返回 null = 超时/失败/输出不过白名单（调用方回落
     * 本地决策,原因记 [lastError]）；返回 [AutonomyDecision] 且 activityId=null
     * = 模型明确「保持现状」。任何路径都不抛（增强挂了不能拖垮 FSM）。
     */
    suspend fun ask(ctx: AutonomyDecisionContext): AutonomyDecision? {
        if (ctx.activities.isEmpty()) return null
        asks++
        lastAskAtMs = nowMs()
        lastError = null
        val cfg = config()?.takeIf { it.model.isNotBlank() } ?: run {
            fails++
            lastError = "缺Key(适配器config返回null)"
            return null
        }
        val startedAt = nowMs()
        // 区分「超时」与「请求内异常」（401/网络）——runCatching 原样吞并两者,
        // 只留 getOrNull 会把归因压成同一格
        val outcome = withTimeoutOrNull(timeoutMs) {
            runCatching { complete(buildMessages(ctx), cfg) }
        }
        lastLatencyMs = nowMs() - startedAt
        val raw = outcome?.getOrNull()
        if (raw == null) {
            fails++
            lastError = when {
                outcome == null -> "超时>${timeoutMs}ms"
                else -> (outcome.exceptionOrNull()?.message
                    ?: outcome.exceptionOrNull()?.javaClass?.simpleName ?: "未知错误").take(ERROR_SNIPPET_MAX)
            }
            return null
        }
        val decision = parseAnswer(raw, ctx.activities.map { c -> c.id })
        if (decision == null) {
            fails++
            lastError = "输出未过白名单: ${raw.take(RAW_SNIPPET_MAX)}"
            return null
        }
        if (decision.activityId == null) keeps++ else overrides++
        return decision.copy(latencyMs = lastLatencyMs, raw = raw.take(RAW_SNIPPET_MAX))
    }

    /** 统计快照一行（观测/文档评估口径;失败时带原因）。 */
    fun stateLine(): String =
        "llm决策: 问${asks}次(败$fails 换$overrides 留$keeps) 最近${lastLatencyMs}ms 门限${minIntervalMs / 1000}s" +
            (lastError?.let { " 失败:$it" } ?: "")

    // ── prompt 组装与输出解析（纯函数,JVM 可测）──────────────────────────

    internal fun buildMessages(ctx: AutonomyDecisionContext): List<ChatMessage> {
        val activities = ctx.activities.joinToString("\n") {
            "- ${it.id} ${it.label}[${it.category}]: ${it.description}"
        }
        val user = buildString {
            appendLine("现在 ${ctx.hour} 点,状态=${ctx.state},位置=${ctx.anchorId ?: "none"},正在做=${ctx.currentActivityId ?: "无"}")
            ctx.memoryLine?.let { appendLine("记忆:$it") }
            ctx.recentLine?.let { appendLine("刚聊过:$it") }
            appendLine("可做活动清单（id 名称[类别]: 描述）:")
            append(activities)
        }
        return listOf(
            ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
            ChatMessage(ChatRole.USER, user),
        )
    }

    /**
     * 白名单双向校验：截首个平衡 JSON,activity 解析回清单 id（大小写不敏感）；
     * 空串/缺字段 = 「保持现状」；清单外 id 或非 JSON 输出 = null（按失败回落）。
     */
    internal fun parseAnswer(raw: String, whitelist: List<String>): AutonomyDecision? {
        val obj = firstJsonObject(raw)?.let {
            runCatching { lenientJson.parseToJsonElement(it) }.getOrNull() as? JsonObject
        } ?: return null
        val activity = (obj["activity"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val reason = ((obj["reason"] as? JsonPrimitive)?.content ?: "").take(24)
        if (activity.isEmpty()) return AutonomyDecision(null, reason, 0, raw)
        val known = whitelist.firstOrNull { it.equals(activity, ignoreCase = true) } ?: return null
        return AutonomyDecision(known, reason, 0, raw)
    }

    /** 非流式语义（点播路由同款）：收集 TextDelta,Error 直接抛。 */
    private suspend fun complete(
        messages: List<ChatMessage>,
        cfg: LlmConfig,
    ): String {
        val reply = StringBuilder()
        llm().streamChat(messages, cfg.copy(temperature = LLM_TEMPERATURE)).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> reply.append(event.text)
                is LlmStreamEvent.Error -> throw event.throwable
                else -> Unit
            }
        }
        return reply.toString()
    }

    private val lenientJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 截首个平衡的 `{...}`（MemoryParser/DanceRequestRouter 同款算法；模型偶尔
     * 在 JSON 前后带客套话或 markdown 围栏）。字符串里的花括号不计数。
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

    companion object {
        /**
         * 输出契约（nl-action-reliability 纪律：提示词只提高概率,确定性校验在
         * 客户端——parseAnswer 的白名单才是硬门）。
         */
        val SYSTEM_PROMPT =
            "你是虚拟人的自主决策大脑。她按自己的作息生活,现在状态转移时问你想让她接下来做什么。" +
                "结合时段/状态/记忆从清单里挑最自然的一件事,也可以选择保持现状。" +
                "只输出一个 JSON 对象：{\"activity\":\"清单里的活动id或空串\",\"reason\":\"不超过12字理由\"}；" +
                "不想换就输出 {\"activity\":\"\",\"reason\":\"保持现状\"}。" +
                "严禁发明清单外的活动 id,不要输出其他内容。"

        /** 低频门缺省（§4.2「每 5~10min」取上限——转移本来就低频,再兜一层）。 */
        const val DEFAULT_MIN_INTERVAL_MS = 8 * 60_000L

        /**
         * 单次询问超时。2026-10-26 端点实测（Qwen/Qwen3.8-27B,关思考,49 条清单,
         * 6 样本,非流式全响应）：6.2~12.2s/均值 8.6s——6s 档会失败大半,按实测
         * 上限放行;超时/失败依旧回落本地决策（这也是评估结论「默认关」的主因,
         * 见 prop 文档 §7 落地记录）。
         */
        const val DEFAULT_TIMEOUT_MS = 15_000L

        /** 便宜模型低温度（结构化输出可靠性档,DanceRequestRouter 同款）。 */
        const val LLM_TEMPERATURE = 0.2f

        /** 评估记录里原始输出截断长度。 */
        const val RAW_SNIPPET_MAX = 48

        /** 失败原因（HTTP 消息/异常文本）截断长度。 */
        const val ERROR_SNIPPET_MAX = 120
    }
}
