package com.neethu.orchestrator.debate

/**
 * 4v4 辩论赛的核心模型（docs/debate-feasibility.md §3/§5）。
 *
 * 本包是纯 JVM 可测的赛制事实源：规则引擎只认这里的类型，渲染/AI/Intent
 * 都在 app 层适配。赛制推进绝不交给 LLM——本文件的所有枚举/数据类都是
 * 确定性的，唯一可能的不确定性输入是 [DebateSpeechPlayer] 的播放结果。
 */

/** 辩手业务 ID 约定：HOST、PRO1..PRO4、CON1..CON4（配置可换显示名/模型，ID 固定语义）。 */
object DebateIds {
    const val HOST = "HOST"
    const val POSITIONS = 4

    /** 严格解析（防前缀误配：PROX 不是辩手 ID）。 */
    private val ID_REGEX = Regex("^(PRO|CON)([1-4])$")

    fun pro(position: Int): String = "PRO$position"
    fun con(position: Int): String = "CON$position"

    val PRO_ALL: List<String> = (1..POSITIONS).map { pro(it) }
    val CON_ALL: List<String> = (1..POSITIONS).map { con(it) }
    val DEBATERS: List<String> = PRO_ALL + CON_ALL
    val ALL: List<String> = listOf(HOST) + DEBATERS

    fun teamOf(id: String): DebateTeam? {
        val m = ID_REGEX.matchEntire(id) ?: return null
        return if (m.groupValues[1] == "PRO") DebateTeam.PRO else DebateTeam.CON
    }

    fun positionOf(id: String): Int? = ID_REGEX.matchEntire(id)?.groupValues?.get(2)?.toIntOrNull()
}

/** 队伍：正方/反方。立场在整场比赛中固定。 */
enum class DebateTeam {
    PRO, CON;

    val opposing: DebateTeam get() = if (this == PRO) CON else PRO
}

/**
 * 比赛阶段（顺序固定 = 默认四人制攻辩模板；显式 phases[] 重排在 P1+）。
 */
enum class DebatePhase {
    HOST_OPENING,
    OPENING_STATEMENT,
    CROSS_EXAM,
    CROSS_SUMMARY,
    FREE_DEBATE,
    CLOSING,
    HOST_CLOSING,
}

/** 单次发言的种类（决定计时长语义与姿态策略）。 */
enum class TurnKind {
    /** 主持串场（不计入辩手用时）。 */
    HOST,

    /** 立论/小结/陈词：首段音频实际开始起表，直到本次结束。 */
    STATEMENT,

    /** 攻辩提问：个人 deadline 短，且受整轮 90s 墙钟约束。 */
    CROSS_QUESTION,

    /** 攻辩回答：不能反问（内容层面由 P1 AI 约束，引擎只管轮替）。 */
    CROSS_ANSWER,

    /** 自由辩论发言：个人 deadline 与队伍余额取较小值。 */
    FREE,
}

/** 发言结束原因（写入公共记录，区分正常播完/打断/到时/失败/跳过）。 */
enum class TurnEndReason {
    COMPLETED,
    MANUAL_END,
    TIMEOUT,
    PAUSED,
    INTERRUPTED,
    FAILED,
    SKIPPED,
}

/** 发言的公共记录条目（docs/debate-feasibility.md §6：仅完整播出才算对方听到）。 */
data class TranscriptEntry(
    val turnId: Long,
    val epoch: Long,
    val phase: DebatePhase,
    val speakerId: String,
    val team: DebateTeam?,
    val kind: TurnKind,
    val text: String,
    /** generated → queued → played / interrupted（本引擎在结束时落定）。 */
    val status: TranscriptStatus,
    val endReason: TurnEndReason?,
    val audioStartedElapsedMs: Long?,
    val endedElapsedMs: Long?,
    val consumedMs: Long,
)

enum class TranscriptStatus { GENERATED, QUEUED, PLAYED, INTERRUPTED, FAILED, SKIPPED }

/** 姿态指令（引擎 → app 姿态导演）。坐站时序的裁判权在引擎，执行在 app。 */
enum class PostureAction { STAND, SIT }

// ── 编译后的赛程表（RuleCompiler 的产物；引擎只吃这个，不再看原始 JSON）────

/**
 * 赛程段：一段同阶段内容。引擎顺序执行 segments，段内按各自规则推进。
 */
sealed class DebateSegment {
    abstract val phase: DebatePhase

    /** 主持串场：HOST 一个人说。 */
    data class HostSpeech(
        override val phase: DebatePhase,
        val budgetMs: Long,
    ) : DebateSegment()

    /** 顺序发言段（立论/小结/陈词）：名单顺序即发言顺序。 */
    data class Statements(
        override val phase: DebatePhase,
        val turns: List<StatementTurn>,
    ) : DebateSegment()

    /** 攻辩：若干轮问答，每轮固定提问者/应答者、轮内严格交替。 */
    data class CrossExam(
        override val phase: DebatePhase,
        val rounds: List<CrossRound>,
    ) : DebateSegment()

    /** 自由辩论：队伍余额制 + 队内循环辩位 + 队伍严格交替。 */
    data class FreeDebate(
        override val phase: DebatePhase,
        val firstTeam: DebateTeam,
        val teamBudgetMs: Map<DebateTeam, Long>,
        val turnBudgetMs: Long,
        /** 队内循环顺序（编译自辩位 1..4）。 */
        val rotation: Map<DebateTeam, List<String>>,
    ) : DebateSegment()
}

data class StatementTurn(
    val speakerId: String,
    val team: DebateTeam?,
    val budgetMs: Long,
)

/**
 * 攻辩一轮：提问者与应答者在轮开始前确定（应答者选择策略见
 * [AnswererPolicy]）；轮内只能是这两人交替，整轮共享墙钟。
 */
data class CrossRound(
    val roundIndex: Int,
    val questionerId: String,
    val answererId: String,
    val wallBudgetMs: Long,
    val questionBudgetMs: Long,
    val answerBudgetMs: Long,
)

/** 攻辩应答者选择策略（未显式指定时）：对方二/三辩中已应答次数最少者，同次数按辩位。 */
class AnswererPolicy {
    private val answeredCount = mutableMapOf<String, Int>()

    fun pick(questionerTeam: DebateTeam, candidates: List<String>): String {
        val chosen = candidates
            .sortedWith(compareBy({ answeredCount[it] ?: 0 }, { DebateIds.positionOf(it) ?: 9 }))
            .first()
        answeredCount[chosen] = (answeredCount[chosen] ?: 0) + 1
        return chosen
    }
}
