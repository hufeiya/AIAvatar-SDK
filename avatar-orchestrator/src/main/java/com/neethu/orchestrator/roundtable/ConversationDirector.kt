package com.neethu.orchestrator.roundtable

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.orchestrator.memory.MemoryParser

/**
 * 圆桌对话的一句话（transcript 一行，导演的输入与 fanout 的原子）：[slotId]
 * 为 null = 用户插话/话题。正文是标签剥离后的干净文本（别人的标签喂给第三
 * 个模型只会困惑）。
 */
data class RoundtableLine(
    val slotId: String?,
    val speakerName: String,
    val text: String,
) {
    val isUser: Boolean get() = slotId == null

    /** 进历史的形态（§4.4）：用户发言加【用户】前缀，他人发言加【名字】前缀。 */
    fun prefixed(): String = if (isUser) "【用户】$text" else "【$speakerName】$text"
}

/** 导演的一次裁决：授予发言权 / 交回用户 / 收场。 */
sealed interface FloorDecision {
    data class Grant(val slotId: String, val reason: String) : FloorDecision

    /** 舞台交回用户：话题暂停，等用户插话再继续（导演认为用户该表态了）。 */
    data object ToUser : FloorDecision

    /** 话题收场：最后一条发言 fanout 后由话题发起者说收场白（[reason] 进导演日志）。 */
    data class End(val reason: String) : FloorDecision
}

/**
 * 一次裁决的输入快照（纯数据；导演实现不持有舞台引用，全靠它决策）。
 */
data class RoundtableContext(
    val topic: String,
    /** 已完成的虚拟人发言轮数（含失败轮）。 */
    val round: Int,
    val maxRounds: Int,
    val slots: List<StageSlotSpec>,
    /** 上一条发言的槽位；null = 开题轮（还没人说过话）。 */
    val lastSpeakerId: String?,
    /** 上一条发言文本（复读检测与 LLM 输入）。 */
    val lastReply: String,
    /** 最近对话（含用户行，时间升序，已截窗）。 */
    val recentLines: List<RoundtableLine>,
    /** [lastSpeakerId] 连续发言的轮数（连续上限用）。 */
    val consecutiveCount: Int,
    /** 待回应的用户插话（发言进行中收到、等本轮结束后安排）。 */
    val pendingUserLine: String?,
    val lastAddressedId: String?,
)

/** 导演策略（同一接口两档热切换，§4.3）。 */
sealed interface DirectorPolicy {
    val maxRounds: Int

    /** 快照/日志用档位名。 */
    val label: String
        get() = when (this) {
            is Rule -> "rule"
            is Llm -> "llm"
        }

    /** 规则档：轮转 + 点名优先 + 连续上限，零网络成本。 */
    data class Rule(
        override val maxRounds: Int = 20,
        /** 同一人连续发言上限（2 人严格轮转时天然到不了；LLM 档兜底与 3+ 人生效）。 */
        val maxConsecutive: Int = 2,
    ) : DirectorPolicy

    /**
     * LLM 档：一次非流式轻量调用裁决下一个发言者（便宜模型 + 低温度 +
     * 关思考，复用提炼 prompt 的可靠性矩阵结论）；输出垃圾/网络失败自动
     * 回退规则档。轮数上限与复读检测是舞台层硬守卫，不依赖模型自觉。
     */
    data class Llm(
        val llm: LlmAdapter,
        val config: () -> LlmConfig?,
        override val maxRounds: Int = 20,
        /** 进 prompt 的最近对话行数。 */
        val recentWindow: Int = 12,
        val temperature: Float = 0.2f,
    ) : DirectorPolicy
}

interface ConversationDirector {
    val label: String
    suspend fun decide(ctx: RoundtableContext): FloorDecision
}

/**
 * 规则档导演（默认，零成本）：开题=点名优先；插话=点名优先否则轮换；常规=
 * 严格轮转（排除上一发言者，声明序下一个）；单槽连续发言达上限收场。
 * 轮数上限与复读检测不在这里——它们是 [com.neethu.orchestrator.roundtable]
 * RoundtableSession 的硬守卫，规则/LLM 两档共同底线。
 */
class RuleBasedDirector(private val policy: DirectorPolicy.Rule) : ConversationDirector {

    override val label = "rule"

    override suspend fun decide(ctx: RoundtableContext): FloorDecision {
        val others = ctx.slots.filter { it.id != ctx.lastSpeakerId }
        // 开题轮（还没人说过话）：最后对话对象优先，其次声明序第一个
        if (ctx.lastSpeakerId == null) {
            val first = ctx.lastAddressedId
                ?.takeIf { id -> ctx.slots.any { it.id == id } }
                ?: ctx.slots.firstOrNull()?.id
                ?: return FloorDecision.End("台上无人")
            return FloorDecision.Grant(first, "开题：最后对话对象优先")
        }
        // 用户插话待回应：点名优先（不指名则轮换到下一位）
        if (!ctx.pendingUserLine.isNullOrBlank()) {
            val named = ctx.lastAddressedId
                ?.takeIf { it != ctx.lastSpeakerId && ctx.slots.any { s -> s.id == it } }
            val pick = named ?: others.firstOrNull()?.id ?: ctx.lastSpeakerId
            return FloorDecision.Grant(pick!!, "回应用户插话")
        }
        // 常规轮转：声明序里上一发言者的下一位（2 人=严格交替；3 人=A→B→C→A）
        if (others.isNotEmpty()) {
            val lastIdx = ctx.slots.indexOfFirst { it.id == ctx.lastSpeakerId }
            val next = if (lastIdx >= 0) {
                ctx.slots[(lastIdx + 1) % ctx.slots.size].id
            } else {
                others.first().id
            }
            return FloorDecision.Grant(next, "轮流发言")
        }
        // 单槽：允许连续发言到上限（守卫层还有轮数上限兜底）
        return if (ctx.consecutiveCount >= policy.maxConsecutive) {
            FloorDecision.End("唯一发言者连续 ${ctx.consecutiveCount} 轮")
        } else {
            FloorDecision.Grant(ctx.lastSpeakerId!!, "唯一发言者继续")
        }
    }
}

/**
 * LLM 档导演：读最近对话 + 各槽位一句人设简介，输出结构化 JSON
 * `{"next": "槽位id"|"USER"|"END", "reason": "..."}`。解析走 MemoryParser
 * 的「截首个平衡 JSON + lenient」套路；任何失败（无配置/网络/垃圾输出/无效
 * 槽位）静默回退规则档——导演挂了圆桌不能停。**禁止连续授予上一发言者**：
 * 输出 lastSpeaker（2+ 人时）按无效处理走回退（轮转公平性的硬约束）。
 */
class LlmDirector(
    private val llm: LlmAdapter,
    private val configProvider: () -> LlmConfig?,
    policy: DirectorPolicy.Llm,
) : ConversationDirector {

    override val label = "llm"

    private val maxRounds = policy.maxRounds
    private val recentWindow = policy.recentWindow
    private val temperature = policy.temperature
    private val fallback = RuleBasedDirector(DirectorPolicy.Rule(maxRounds = policy.maxRounds))

    override suspend fun decide(ctx: RoundtableContext): FloorDecision {
        val cfg = configProvider()?.takeIf { it.model.isNotBlank() }
            ?: return fallback.decide(ctx)
        // 收集走调用方上下文（MemoryExtractor 同例）：网络在 adapter 内部的
        // IO 协程里，收集方只是挂起等待——测试时序也保持全同步
        val raw = runCatching { complete(buildMessages(ctx), cfg) }.getOrNull()
            ?: return fallback.decide(ctx)
        return parse(raw, ctx) ?: fallback.decide(ctx)
    }

    /** 非流式语义（MemoryExtractor 同款）：收集 TextDelta，Error 直接抛。 */
    private suspend fun complete(messages: List<ChatMessage>, cfg: LlmConfig): String {
        val reply = StringBuilder()
        llm.streamChat(messages, cfg.copy(temperature = temperature)).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> reply.append(event.text)
                is LlmStreamEvent.Error -> throw event.throwable
                else -> Unit
            }
        }
        return reply.toString()
    }

    internal fun buildMessages(ctx: RoundtableContext): List<ChatMessage> {
        val roster = ctx.slots.joinToString("\n") { slot ->
            val intro = slot.intro.take(40)
            "- ${slot.id}（${slot.displayName}）${if (intro.isEmpty()) "" else "：$intro"}"
        }
        val dialogue = ctx.recentLines.takeLast(recentWindow)
            .joinToString("\n") { line ->
                if (line.isUser) "用户：${line.text}" else "${line.slotId}（${line.speakerName}）：${line.text}"
            }
        val pending = ctx.pendingUserLine?.takeIf { it.isNotBlank() }
        val user = buildString {
            appendLine("在场虚拟人：")
            appendLine(roster)
            appendLine("用户本人在旁观，可随时插话。")
            appendLine("话题：${ctx.topic}")
            appendLine("进度：已 ${ctx.round} 轮（上限 ${maxRounds} 轮）")
            if (dialogue.isNotEmpty()) {
                appendLine("最近对话：")
                appendLine(dialogue)
            }
            append("上一条发言来自 ${ctx.lastSpeakerId ?: "无人"}（刚说过话的人不要连续发言）。")
            if (pending != null) append("注意：用户刚插话「${pending}」，优先安排回应。")
            append("请决定下一个开口的谁：想让某位虚拟人接话输出其槽位 id；认为该用户表态输出 USER；话题聊尽、开始重复或接近轮数上限时输出 END。")
        }
        return listOf(
            ChatMessage(ChatRole.SYSTEM, DIRECTOR_SYSTEM),
            ChatMessage(ChatRole.USER, user),
        )
    }

    /** 垃圾输出返回 null（调用方回退规则档）；reason 缺省给占位。 */
    internal fun parse(raw: String, ctx: RoundtableContext): FloorDecision? {
        val fragment = MemoryParser.firstJsonObject(raw) ?: return null
        val root = try {
            lenientJson.parseToJsonElement(fragment) as? kotlinx.serialization.json.JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val next = (root["next"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()
            ?: return null
        val reason = (root["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?.trim()?.take(24).orEmpty().ifEmpty { "导演裁决" }
        return when {
            next.equals("END", true) -> FloorDecision.End(reason)
            next.equals("USER", true) -> FloorDecision.ToUser
            // 槽位 id 大小写不敏感；2+ 人时禁止连续授予上一发言者（单槽除外）
            ctx.slots.any { it.id.equals(next, true) } ->
                if (ctx.slots.size > 1 && next.equals(ctx.lastSpeakerId, true)) null
                else FloorDecision.Grant(
                    ctx.slots.first { it.id.equals(next, true) }.id,
                    reason,
                )
            else -> null
        }
    }

    private val lenientJson = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private companion object {
        val DIRECTOR_SYSTEM =
            "你是虚拟人圆桌对话的主持人（导演），唯一职责是读完对话后决定下一个开口的是谁。" +
                "只输出一个 JSON 对象：{\"next\":\"槽位id或USER或END\",\"reason\":\"不超过16字的理由\"}，" +
                "不要输出任何其他内容。"
    }
}

/**
 * 防失控守卫的纯函数件（轮数上限在舞台层，这里只管复读检测）：归一化
 * （小写+只留字母数字，中文原样保留）后与更早的发言比对——完全相同，或
 * 字符 bigram Jaccard 相似度 ≥ 阈值判复读。过短（<4 个归一化字符）不判：
 * 「嗯」「好的」之类的客套不算失控。
 */
object DialogueGuards {

    /**
     * 与 [earlier]（更早的发言文本，不含本条）任一重复即为复读。阈值 0.6：
     * 插入一两个词的换说法（「真不错」→「真心不错」0.67）要抓，短句泛泛
     * 客套（「我也是这么想的/觉得的」0.43）不误伤。
     */
    fun isRepeat(
        reply: String,
        earlier: List<String>,
        threshold: Float = 0.6f,
    ): Boolean {
        val n = normalized(reply)
        if (n.length < MIN_NORMALIZED_LEN) return false
        val grams = bigrams(n)
        return earlier.any { prev ->
            val m = normalized(prev)
            m.length >= MIN_NORMALIZED_LEN &&
                (m == n || jaccard(grams, bigrams(m)) >= threshold)
        }
    }

    internal fun normalized(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    internal fun bigrams(s: String): Set<String> =
        if (s.length < 2) setOf(s) else (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()

    internal fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val inter = a.count { it in b }
        return inter.toFloat() / (a.size + b.size - inter).coerceAtLeast(1)
    }

    private const val MIN_NORMALIZED_LEN = 4
}
