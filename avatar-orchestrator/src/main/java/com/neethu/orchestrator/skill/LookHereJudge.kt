package com.neethu.orchestrator.skill

/**
 * 屏幕视角的四个方向（「看这边」技能）：虚拟人指向哪边、用户头往哪边偏，
 * 都以**用户看到的画面**为准（虚拟人面向观众，它抬右手指向的是画面左）。
 */
enum class LookDir(val zh: String, val en: String) {
    UP("上", "up"),
    DOWN("下", "down"),
    LEFT("左", "left"),
    RIGHT("右", "right");

    fun opposite(): LookDir = when (this) {
        UP -> DOWN
        DOWN -> UP
        LEFT -> RIGHT
        RIGHT -> LEFT
    }

    /** 语言化显示名（宣判/调试输出用）。 */
    fun label(lang: com.neethu.corelib.Lang): String = if (lang == com.neethu.corelib.Lang.EN) en else zh

    companion object {
        /** 调试命令的方向参数：英文与中文都收。 */
        fun parse(text: String): LookDir? = entries.firstOrNull {
            it.name.equals(text, ignoreCase = true) || it.en == text.lowercase() || it.zh == text
        }
    }
}

/**
 * 判定窗参数。阈值是真机调优入口；两个布尔是**符号标定常量**
 * （docs/lookhere-skill-feasibility.md §5）：相机系 yaw/pitch 的正号对应屏幕
 * 哪个方向由 FaceLandmarker 输出约定 + 前摄朝向决定，必须真机用
 * `ai_cmd face_pose` 标定后锁死——标定只改这两个布尔，逻辑不写死方向。
 */
data class LookHereTuning(
    /** 窗口开头取基线（中位数）的时长：用户来不及做出反应（人类反应时 ≥200ms）。 */
    val baselineMs: Long = 250L,
    /** 判定窗总长（指向时刻起算；早退确认可提前出结论）。
     *  2200ms 的依据（真机 2026-10-06 第 1 局 Frozen 误判归因）：用户 cues 的是
     *  go 信号**语音**，而云 TTS 首声晚 ~0.7s、短语播完 ~1.9s，再加反应+转头
     *  0.3~0.6s——1200ms 的窗在语音响起来之前就关了。加长几乎不伤节奏：宣判
     *  本来就要等 go 信号播完（~1.9s）才发，快反应者仍走 2 帧早退。 */
    val windowMs: Long = 2_200L,
    /** yaw 轴确认线（连续 [confirmFrames] 帧越线即判）。 */
    val confirmYawDeg: Float = 15f,
    /** pitch 轴确认线（颈部俯仰天然幅度小，阈值更低）。 */
    val confirmPitchDeg: Float = 12f,
    /** 确认所需连续同向帧数（80ms 分析节奏下 2 帧 ≈160ms，满足 200ms 检测要求）。 */
    val confirmFrames: Int = 2,
    /** 标定常量：相机 yaw 正值 = 用户转向屏幕左？ */
    val yawPositiveIsScreenLeft: Boolean = true,
    /** 标定常量：相机 pitch 正值 = 用户抬头（屏幕上方向）？
     *  **真机标定 2026-10-06**：三次"指上"局系统全部读 user=上 而用户实际在躲
     *  （日志 14:17 round#16/17/21）→ pitch 符号整体反向（FaceLandmarker 相机系
     *  约定：yaw 正=屏幕左 与 pitch 正=屏幕下 并存，两轴不同号），翻转为 false。
     *  yaw 符号同日实证正确（face_pose 探针 yaw=+34 → 左），保持 true。 */
    val pitchPositiveIsScreenUp: Boolean = false,
) {
    /** 相机 yaw 正/负号对应的屏幕方向（判定与 face_pose 调试探针共用同一映射）。 */
    fun yawToScreen(dyaw: Float): LookDir? = when {
        dyaw > 0f -> if (yawPositiveIsScreenLeft) LookDir.LEFT else LookDir.RIGHT
        dyaw < 0f -> if (yawPositiveIsScreenLeft) LookDir.RIGHT else LookDir.LEFT
        else -> null
    }

    /** 相机 pitch 正/负号对应的屏幕方向。 */
    fun pitchToScreen(dpitch: Float): LookDir? = when {
        dpitch > 0f -> if (pitchPositiveIsScreenUp) LookDir.UP else LookDir.DOWN
        dpitch < 0f -> if (pitchPositiveIsScreenUp) LookDir.DOWN else LookDir.UP
        else -> null
    }

    /** 主轴方向（|yaw|≥|pitch| 取 yaw）——face_pose 探针显示"系统怎么解读当前头姿"用。 */
    fun dominantDirection(yawDeg: Float, pitchDeg: Float): LookDir? =
        if (Math.abs(yawDeg) >= Math.abs(pitchDeg)) yawToScreen(yawDeg) else pitchToScreen(pitchDeg)
}

/**
 * 「看这边」单局判定窗（纯 JVM，合成姿态流单测全覆盖）。
 *
 * **判定语义（真机 2026-10-06 二次归因后定稿）：相对「会话中性位」的绝对方向**，
 * 不是回合起点的相对位移——用户保持偏头不回正时（"我一直往左看"却连判发呆），
 * 回合相对位移恒≈0，只有绝对方向才符合"头朝哪边"的游戏规则。中性位由技能层
 * 跨回合持久化（[currentNeutral]），来源=激活介绍期的采样（那时用户被告知回正
 * 看镜头）；每回合窗口头的候选中位数**仅在贴近现行中性位时**才采纳（姿态漂移
 * 跟踪），明显偏头的候选被拒绝——保持左偏的用户每一局都会被读出"左"。
 *
 * 方向=主轴投票（|Δyaw|≥|Δpitch| 取 yaw），连续 [LookHereTuning.confirmFrames]
 * 帧同向越确认线早退；过 [LookHereTuning.windowMs] 的第一个样本按峰值终判，
 * 否则 [Outcome.Frozen]（真呆住=判负）。无人脸=无样本=不出结论，技能层按
 * 无脸路径处理。
 */
class LookHereJudge(
    private val tuning: LookHereTuning,
    private val startMs: Long,
    /** 会话中性位（上回合结算时的 [currentNeutral]）；null=本回合自我形成（首局）。 */
    initialNeutral: Pair<Float, Float>? = null,
) {
    /** 一局结论：用户转头方向（已映射到屏幕方向）或呆住。 */
    sealed interface Outcome {
        data class Turned(val dir: LookDir) : Outcome
        data object Frozen : Outcome
    }

    /** 当前会话中性位（技能在回合结算时取回，跨回合跟踪姿态漂移）。 */
    val currentNeutral: Pair<Float, Float>?
        get() = neutral

    private var neutral: Pair<Float, Float>? = initialNeutral
    private val candYaw = ArrayList<Float>()
    private val candPitch = ArrayList<Float>()
    private var candidateSettled = false
    private var streakDir: LookDir? = null
    private var streakLen = 0
    private var peak: Pair<LookDir, Float>? = null
    private var finished = false

    /**
     * 喂一帧头部姿态（度），返回结论或 null（本局未决）。结论一旦返回本窗
     * 终结——后续样本一律 null（技能层据此不会重复结算同一局）。
     */
    fun onSample(nowMs: Long, yawDeg: Float, pitchDeg: Float): Outcome? {
        if (finished) return null

        // 漂移跟踪候选：窗口头 ~330ms 的中位数，仅当贴近现行中性位时采纳；
        // 明显偏头的候选被拒绝（用户保持偏头时中性位绝不跟着跑）
        if (!candidateSettled) {
            if (nowMs - startMs <= tuning.baselineMs + 80L) {
                candYaw += yawDeg
                candPitch += pitchDeg
            } else {
                candidateSettled = true
                if (candYaw.isEmpty()) {
                    // 脸在截止之后才出现（迟到首样本）：现样本即候选，否则中性位
                    // 永远建不起来、判定全程返回 null
                    candYaw += yawDeg
                    candPitch += pitchDeg
                }
                val cy = median(candYaw)
                val cp = median(candPitch)
                val n = neutral
                if (n == null) {
                    neutral = cy to cp // 首局自我形成
                } else if (Math.abs(cy - n.first) < tuning.confirmYawDeg &&
                    Math.abs(cp - n.second) < tuning.confirmPitchDeg
                ) {
                    neutral = cy to cp
                }
            }
        }

        val n = neutral ?: return null // 中性位未形成（首局基线窗内）：不判定

        val dyaw = yawDeg - n.first
        val dpitch = pitchDeg - n.second
        val useYawAxis = Math.abs(dyaw) >= Math.abs(dpitch)
        val dir = if (useYawAxis) tuning.yawToScreen(dyaw) else tuning.pitchToScreen(dpitch)
        val mag = if (useYawAxis) Math.abs(dyaw) else Math.abs(dpitch)
        val confirmAt = if (useYawAxis) tuning.confirmYawDeg else tuning.confirmPitchDeg

        if (dir != null) {
            if (peak == null || mag > peak!!.second) peak = dir to mag
            if (dir == streakDir) streakLen++ else {
                streakDir = dir
                streakLen = 1
            }
            if (streakLen >= tuning.confirmFrames && mag >= confirmAt) {
                return Outcome.Turned(dir).also { finished = true }
            }
        } else {
            streakDir = null
            streakLen = 0
        }

        if (nowMs - startMs >= tuning.windowMs) {
            finished = true
            val p = peak
            return if (p != null && p.second >= confirmAtFor(p.first)) Outcome.Turned(p.first)
            else Outcome.Frozen
        }
        return null
    }

    private fun confirmAtFor(dir: LookDir): Float =
        if (dir == LookDir.UP || dir == LookDir.DOWN) tuning.confirmPitchDeg else tuning.confirmYawDeg

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.toFloatArray().also { it.sort() }
        return sorted[sorted.size / 2]
    }
}
