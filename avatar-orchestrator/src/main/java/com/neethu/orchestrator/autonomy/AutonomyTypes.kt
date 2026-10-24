package com.neethu.orchestrator.autonomy

/**
 * 自主生命体的状态集（docs/autonomy-feasibility.md §4.1）。数据驱动——
 * FSM 不硬编码场景，动作词汇表（[AutonomySpot]）由集成方从场景交互点表
 * 投影注入（app 层从 interactions.json 派生，与 interact_with 工具同款
 * 「orchestrator 不感知场景表」边界）。
 */
enum class AutonomyStateKind {
    /** 兜底站桩：看着镜头（GazeMode.CAMERA）。 */
    IDLE_STAND,

    /** 随机走一程（waypoint/approach/无视线 stand 锚点，走过去即算）。 */
    WANDER,

    /** 站定看某处（kind=stand 且带 look 的点：窗景/电视/游戏机）。 */
    LOOKING,

    /** 坐着（bed_sit/desk_seat/reading_seat，接触定姿循环剪辑）。 */
    SITTING,

    /** 躺着（bed_lie，作息表深夜加权；白天超 20min 起身）。 */
    LYING,
}

/**
 * 自主动作词汇表的一个候选点：交互点表（id+kind+坐标）对 FSM 的投影。
 * [kind] 决定该点可填充的状态；[look] 非空时 [AutonomyStateKind.LOOKING]
 * 状态期间持续注视这个世界坐标。
 */
data class AutonomySpot(
    val id: String,
    val kind: AutonomyStateKind,
    val x: Float,
    val z: Float,
    /** 就位后的持续视线目标（世界坐标 xyz 米）；null = 不干预视线。 */
    val look: List<Float>? = null,
) {
    /** 与当前位置的平面距离（米）。 */
    fun distanceTo(px: Float, pz: Float): Float = kotlin.math.hypot((x - px).toDouble(), (z - pz).toDouble()).toFloat()
}

/**
 * FSM 决策输出的一条指令：决策层只发指令，身体执行全在集成方注入的
 * executor（app 层包装 InteractionCoordinator/FaceDriver），两层经指令
 * 解耦——orchestrator 不触碰 controller。
 */
sealed interface AutonomyDirective {
    /** 走到 spot 并就位（坐/躺/看）；坐/躺中先起身由就位链自理。 */
    data class GoTo(val spot: AutonomySpot, val state: AutonomyStateKind) : AutonomyDirective

    /** 起身（结束坐/躺），回到站桩。 */
    data object StandUp : AutonomyDirective

    /** 持续注视一个世界坐标点（LOOKING 状态的续盯，就位链 8s 回退的补发）。 */
    data class GazePoint(val x: Float, val y: Float, val z: Float) : AutonomyDirective

    /** 视线交回镜头（状态收尾/冻结兜底）。 */
    data object GazeCamera : AutonomyDirective

    /** 立即停步（用户会话冻结走动车道；复用 stop 语义）。 */
    data object StopWalking : AutonomyDirective
}

/**
 * 每拍（≈1s）注入 FSM 的世界快照——冲突仲裁的全部输入（docs/autonomy-feasibility.md
 * §4.3）。集成方采集自 AvatarSession.phase、FreeSpeech/VAD、技能占用、
 * controller 走动/接触定姿状态与事件时间戳。
 */
data class AutonomyWorld(
    /** 会话相位忙（THINKING/SPEAKING）——FSM 冻结。 */
    val phaseBusy: Boolean = false,
    /** 用户正在说话（VAD/ASR 输入侧活跃）——与 phaseBusy 同级冻结。 */
    val userSpeaking: Boolean = false,
    /** 技能/表演占用身体（FSM 让位）。 */
    val skillBusy: Boolean = false,
    /** 走动中（walkState != IDLE）。 */
    val walking: Boolean = false,
    /** 自主指令还在执行（就位链 busy 或自主起步的走动未站定）。 */
    val autonomyInFlight: Boolean = false,
    /** 接触定姿种类（"sit"/"lie"）或 null（没坐着/躺着）。 */
    val contactKind: String? = null,
    /** 当前接触定姿的锚点 id（外部工具就位后的状态续跑依据）。 */
    val contactAnchorId: String? = null,
    /** 最近几秒内有外部动作（LLM 工具/技能/标签分派）——FSM 观望不决策。 */
    val recentExternalAction: Boolean = false,
) {
    val frozen: Boolean get() = phaseBusy || userSpeaking
}

/** 一条转移记录（ai_cmd autonomy_log 的正本，保留最近 N 条）。 */
data class AutonomyTransition(
    val atMs: Long,
    val from: AutonomyStateKind,
    val to: AutonomyStateKind,
    val anchorId: String?,
    val reason: String,
)

/**
 * 自主行为的一档限速配置（防「抽搐感」，docs/autonomy-feasibility.md §10.4）：
 * 每小时身体动作（GoTo 类）总数上限，超限进纯站桩。
 */
data class AutonomyLimits(
    val maxActionsPerHour: Int = 12,
    /** 最短状态停留（即使作息权重想切也等够）。 */
    val minDwellMs: Long = 15_000,
)
