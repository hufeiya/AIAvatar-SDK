package com.neethu.orchestrator.memory

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.corelib.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * 关系状态（酒馆 P3-1 / 人机恋深化，docs/tavern-companion-feasibility.md §5）：
 * 卡级 KV——好感度（0..100）、相识纪念日、专属称呼。数据进 MemoryDatabase
 * （与记忆同生命周期，v3 起独立表）；好感度由对话事件经 LLM 判定增量，
 * UI 呈现为低调状态行而非数值游戏化（数值只进 LLM 提示行作锚点）。
 *
 * 红线（§8.3）：可关闭、可导出（读 store 即得）、可删除（[RelationshipStore.clear]）。
 */

/** 关系阶段（0..100 的分带视图；UI 与提示行都用阶段词，不裸奔数值）。 */
enum class RelationshipLevel {
    /** 0..15 刚认识，客气而拘谨。 */
    STRANGER,

    /** 16..40 熟识，能聊日常。 */
    ACQUAINTANCE,

    /** 41..70 亲近，有默契会惦记。 */
    CLOSE,

    /** 71..100 挚爱，无可替代的存在。 */
    INTIMATE,
}

fun levelOf(favorability: Int): RelationshipLevel = when {
    favorability >= 71 -> RelationshipLevel.INTIMATE
    favorability >= 41 -> RelationshipLevel.CLOSE
    favorability >= 16 -> RelationshipLevel.ACQUAINTANCE
    else -> RelationshipLevel.STRANGER
}

/** 阶段词（UI 低调状态行/日志用；中文）。 */
fun levelLabelOf(level: RelationshipLevel, lang: Lang = Lang.ZH): String = when (lang) {
    Lang.EN -> when (level) {
        RelationshipLevel.STRANGER -> "just met"
        RelationshipLevel.ACQUAINTANCE -> "familiar"
        RelationshipLevel.CLOSE -> "close"
        RelationshipLevel.INTIMATE -> "beloved"
    }
    else -> when (level) {
        RelationshipLevel.STRANGER -> "初识"
        RelationshipLevel.ACQUAINTANCE -> "熟识"
        RelationshipLevel.CLOSE -> "亲近"
        RelationshipLevel.INTIMATE -> "挚爱"
    }
}

/** 判定增量边界：单轮最多 ±[MAX_DELTA]，防一句糖话直冲满好感。 */
const val RELATIONSHIP_MAX_DELTA = 3

/** 关系记录（持久化形态，卡级一行）。 */
data class RelationshipRecord(
    /** 分片键：卡文件名 / avatarId；'' = 全局单关系。 */
    val avatarId: String = "",
    /** 好感度 0..100，初识默认 20（给 LLM 留双向操作空间）。 */
    val favorability: Int = RELATIONSHIP_INITIAL_FAVORABILITY,
    /** 相识纪念日（首次激活该卡的 EpochDay）。 */
    val anniversaryEpochDay: Long,
    /** 角色对用户的专属称呼；null = 还没有。 */
    val nickname: String? = null,
    val updatedAt: Long,
) {
    companion object {
        /** 初识默认好感度。 */
        const val RELATIONSHIP_INITIAL_FAVORABILITY = 20
    }
}

/** 关系快照（派生视图：阶段/天数/纪念日由记录与当天日期现算，不落盘）。 */
data class RelationshipSnapshot(
    val record: RelationshipRecord,
    val level: RelationshipLevel,
    /** 相识天数（首日 = 1）。 */
    val daysTogether: Int,
    /** 今天是否相识整周年（365 的整倍数天）。 */
    val isAnniversaryToday: Boolean,
)

/**
 * 关系存储 SPI：集成方注入 [RoomRelationshipStore]（与记忆同库）或测试注入
 * [InMemoryRelationshipStore]。[scoped] 语义对齐 [MemoryStore.scoped]。
 */
interface RelationshipStore {
    suspend fun load(avatarId: String): RelationshipRecord?
    suspend fun save(record: RelationshipRecord)
    suspend fun clear(avatarId: String): Boolean

    /** 卡级分片视图：key 前缀隔离，同一物理库按卡各存一行。 */
    fun scoped(avatarId: String): RelationshipStore =
        ScopedRelationshipStore(this, avatarId)
}

/** 前缀分片实现（[RelationshipStore.scoped] 缺省）。 */
private class ScopedRelationshipStore(
    private val upstream: RelationshipStore,
    private val prefix: String,
) : RelationshipStore {
    private fun key(avatarId: String) = "$prefix/$avatarId"
    override suspend fun load(avatarId: String): RelationshipRecord? =
        upstream.load(key(avatarId))?.copy(avatarId = avatarId)
    override suspend fun save(record: RelationshipRecord) =
        upstream.save(record.copy(avatarId = key(record.avatarId)))
    override suspend fun clear(avatarId: String): Boolean = upstream.clear(key(avatarId))
}

/** 进程内实现（测试 / 无持久化接入）。 */
class InMemoryRelationshipStore(private val nowMs: () -> Long = { System.currentTimeMillis() }) : RelationshipStore {
    private val map = ConcurrentHashMap<String, RelationshipRecord>()

    override suspend fun load(avatarId: String): RelationshipRecord? = map[avatarId]

    override suspend fun save(record: RelationshipRecord) {
        map[record.avatarId] = record.copy(updatedAt = nowMs())
    }

    override suspend fun clear(avatarId: String): Boolean = map.remove(avatarId) != null
}

/** 判定与提示行的双语文案（MemoryTexts 同惯例：小目录自带，不进 PromptTexts）。 */
internal object RelationshipTexts {
    fun levelLabel(level: RelationshipLevel, lang: Lang): String = when (lang) {
        Lang.EN -> when (level) {
            RelationshipLevel.STRANGER -> "just getting to know each other"
            RelationshipLevel.ACQUAINTANCE -> "familiar friends"
            RelationshipLevel.CLOSE -> "close"
            RelationshipLevel.INTIMATE -> "beloved"
        }
        else -> when (level) {
            RelationshipLevel.STRANGER -> "初识"
            RelationshipLevel.ACQUAINTANCE -> "熟识"
            RelationshipLevel.CLOSE -> "亲近"
            RelationshipLevel.INTIMATE -> "挚爱"
        }
    }

    /**
     * 每轮 system 注入的关系行：低调上下文（阶段词 + 相识天数 + 称呼），
     * 数值只作 LLM 锚点；纪念日附加仪式感指令。
     */
    fun promptLine(snapshot: RelationshipSnapshot, lang: Lang): String {
        val r = snapshot.record
        return if (lang == Lang.EN) {
            buildString {
                append("[Relationship] You have known the user for ${snapshot.daysTogether} day(s). ")
                append("Current stage: ${levelLabel(snapshot.level, lang)} (affinity ${r.favorability}/100).")
                r.nickname?.takeIf { it.isNotBlank() }?.let { append(" You call the user \"$it\".") }
                if (snapshot.isAnniversaryToday) {
                    append(" Today marks the anniversary of your first meeting — let it show naturally.")
                }
            }
        } else {
            buildString {
                append("【关系状态】你与用户相识 ${snapshot.daysTogether} 天。")
                append("当前关系阶段：${levelLabel(snapshot.level, lang)}（好感 ${r.favorability}/100）。")
                r.nickname?.takeIf { it.isNotBlank() }?.let { append("你平时称呼用户为「$it」。") }
                if (snapshot.isAnniversaryToday) {
                    append("今天是相识纪念日，语气自然带一点纪念的仪式感。")
                }
            }
        }
    }

    /** 后台判定的 system 指令（复用当前 provider 的 LLM，无工具无标签）。 */
    fun judgeInstruction(current: RelationshipRecord, lang: Lang): String = if (lang == Lang.EN) {
        "You maintain the relationship state of a roleplay companion. Current affinity: " +
            "${current.favorability}/100 (stage: ${levelLabel(levelOf(current.favorability), Lang.EN)}). " +
            "Judge from the latest exchange how the user's attitude shifts the affinity, and note any special " +
            "nickname the user asks to be called. Reply with ONLY a JSON object: " +
            "{\"delta\": <-3..3 integer>, \"nickname\": <string or null>}. " +
            "Neutral small talk is 0; warmth +1..2; cold/rude -1..-3. Never invent a nickname unless clearly established."
    } else {
        "你在维护一个角色扮演伴侣的关系状态。当前好感度：${current.favorability}/100" +
            "（阶段：${levelLabel(levelOf(current.favorability), Lang.ZH)}）。" +
            "根据最新一轮对话判断用户态度对好感度的影响；若用户明确确立了专属称呼（角色对用户的昵称），一并记录。" +
            "只输出 JSON 对象：{\"delta\": <-3..3 的整数>, \"nickname\": <字符串或 null>}。" +
            "日常寒暄记 0；暖心 +1..2；冷淡/无礼 -1..-3；称呼未明确确立时绝不安造。"
    }
}

/**
 * 关系状态协调器：快照流（UI 低调状态行的数据源）+ 每轮 LLM 判定增量 +
 * system 提示行。llm 未就绪时判定静默跳过（快照/提示行不受影响）；失败
 * 保留旧记录下轮重试（与摘要压缩同策略）。
 *
 * 分片键 [subjectIdProvider] 每次读写现取——卡切换不需要重建会话，
 * 调用方切换后调 [refresh] 即可。
 */
class RelationshipTracker(
    private val store: RelationshipStore,
    private val scope: CoroutineScope,
    private val llmProvider: () -> LlmAdapter?,
    private val llmConfigProvider: () -> LlmConfig?,
    private val subjectIdProvider: () -> String = { "" },
    private val lang: Lang = Lang.ZH,
    /** 判定门槛：本轮双方文字合计不足该字符数不调 LLM（寒暄零成本）。 */
    private val judgeMinChars: Int = 16,
    private val todayEpochDay: () -> Long = { System.currentTimeMillis() / DAY_MS },
) {

    /** UI 观测缝：当前分片的快照；null = 还没加载（初次 [refresh] 前）。 */
    val snapshots: StateFlow<RelationshipSnapshot?> get() = _snapshots.asStateFlow()
    private val _snapshots = MutableStateFlow<RelationshipSnapshot?>(null)

    /** 运行时开关（§8.3 可关闭）：关 = 不再判定、提示行冻结为关闭前状态。 */
    @Volatile
    var enabled: Boolean = true

    /** 每轮 system 注入的关系行；null = 无记录或已关闭。 */
    @Volatile
    var promptLine: String? = null
        private set

    private var judgeJob: Job? = null

    /**
     * 拉取当前分片记录并重算快照/提示行（会话创建与卡切换时调用）。首次
     * 相遇（无记录）持久化初识记录——相识纪念日从首次激活该卡那天起算，
     * 之后纯寒暄（判定增量 0）也不会丢纪念日。
     */
    suspend fun refresh(): RelationshipSnapshot? {
        val record = store.load(subjectIdProvider())
            ?: RelationshipRecord(avatarId = subjectIdProvider(), anniversaryEpochDay = todayEpochDay(), updatedAt = 0)
        return updateSnapshot(record, persistNew = true)
    }

    /**
     * 回合完成钩子（[enabled] 且 llm 就绪且够长才判定）：fire-and-forget，
     * 永不阻塞回合收尾；返回本批判定 Job（测试 join 缝）。
     */
    fun onTurnCompleted(userText: String, assistantText: String): Job? {
        if (!enabled) return null
        if (userText.length + assistantText.length < judgeMinChars) return null
        val llm = llmProvider() ?: return null
        val cfg = llmConfigProvider() ?: return null
        return scope.launch { judgeNow(llm, cfg, userText, assistantText) }.also { judgeJob = it }
    }

    /** 数据主权（§8.3）：清当前分片记录并重置为初识。 */
    suspend fun reset() {
        store.clear(subjectIdProvider())
        judgeJob?.cancel()
        promptLine = null
        _snapshots.value = null
    }

    /** 测试观测缝：等待在途判定结束。 */
    internal suspend fun awaitJudgeForTest() {
        judgeJob?.join()
    }

    /**
     * 同步判定路径（onTurnCompleted 的协程体；测试直调免时序）。LLM 输出
     * 非 JSON / 缺字段 / 越界 delta 一律按 0 处理——判定失败不动记录。
     */
    internal suspend fun judgeNow(llm: LlmAdapter, cfg: LlmConfig, userText: String, assistantText: String) {
        val current = _snapshots.value?.record
            ?: store.load(subjectIdProvider())
            ?: RelationshipRecord(avatarId = subjectIdProvider(), anniversaryEpochDay = todayEpochDay(), updatedAt = 0)
        val reply = StringBuilder()
        try {
            llm.streamChat(
                listOf(
                    ChatMessage(ChatRole.SYSTEM, RelationshipTexts.judgeInstruction(current, lang)),
                    ChatMessage(
                        ChatRole.USER,
                        "用户：${userText.take(400)}\n角色：${assistantText.take(400)}\n请输出 JSON。",
                    ),
                ),
                cfg.copy(tools = emptyList(), toolChoice = null, maxTokens = 64, temperature = 0.1f),
            ).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> reply.append(event.text)
                    is LlmStreamEvent.Error -> throw event.throwable
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return // 失败保留旧记录，下轮重试
        }
        val delta = parseDelta(reply.toString()) ?: return
        val nickname = parseNickname(reply.toString())
        val nextFavorability = (current.favorability + delta).coerceIn(0, 100)
        if (nextFavorability == current.favorability && nickname == null) return
        val next = current.copy(
            favorability = nextFavorability,
            nickname = nickname ?: current.nickname,
            updatedAt = System.currentTimeMillis(),
        )
        store.save(next)
        updateSnapshot(next, persistNew = false)
    }

    private suspend fun updateSnapshot(record: RelationshipRecord, persistNew: Boolean): RelationshipSnapshot {
        val day = todayEpochDay()
        // 初次相遇（无记录）持久化纪念日——相识零点从激活该卡那天起算
        if (persistNew && store.load(record.avatarId) == null) store.save(record)
        val daysTogether = (day - record.anniversaryEpochDay).toInt().coerceAtLeast(0) + 1
        val snapshot = RelationshipSnapshot(
            record = record,
            level = levelOf(record.favorability),
            daysTogether = daysTogether,
            // 相识当天 daysTogether=1；整周年 = 相识满 365 的整倍数天
            isAnniversaryToday = daysTogether > 1 && (daysTogether - 1) % 365 == 0,
        )
        _snapshots.value = snapshot
        promptLine = RelationshipTexts.promptLine(snapshot, lang)
        return snapshot
    }

    companion object {
        /** 一天的毫秒数（EpochDay 换算）。 */
        const val DAY_MS = 24 * 60 * 60 * 1000L

        internal fun parseDelta(reply: String): Int? {
            val json = runCatching {
                Json.parseToJsonElement(extractJsonObject(reply)).jsonObject
            }.getOrNull() ?: return null
            val delta = json["delta"]?.jsonPrimitive?.intOrNull ?: return null
            return delta.takeIf { abs(it) <= RELATIONSHIP_MAX_DELTA }
        }

        internal fun parseNickname(reply: String): String? = runCatching {
            val obj = Json.parseToJsonElement(extractJsonObject(reply)).jsonObject
            when (val raw = obj["nickname"]) {
                null, is kotlinx.serialization.json.JsonNull -> null
                else -> raw.jsonPrimitive.content.trim().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()

        /** 容错提取首个 {...}（模型爱裹 ```json 围栏或前后缀话）。 */
        private fun extractJsonObject(text: String): String {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return text
            return text.substring(start, end + 1)
        }
    }
}
