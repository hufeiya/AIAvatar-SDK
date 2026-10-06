package com.neethu.orchestrator.skill

import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.LookHereTexts
import com.neethu.orchestrator.skill.LookHereJudge.Outcome
import kotlin.random.Random

/**
 * 「看这边」反应力技能（方向反转游戏）——第二个技能实现
 * （docs/lookhere-skill-feasibility.md）。与猜拳相反，本技能**判定完全端侧、
 * 赛中大模型零参与**：虚拟人随机指一个方向（VRMA + speak go 信号），用户必须
 * 反方向转头；头部姿态由 MediaPipe FaceLandmarker 端侧给出（app 层经
 * [AvatarSkill.onHeadPose] 喂入），[LookHereJudge] 本地判定，`speak()` 直通
 * TTS 即时宣判——一局 2~3s，LLM 往返（3~5s）根本跟不上这个节奏。大模型只在
 * 退场时收到一次「战报」回合（比分 + 被判负瞬间的抓拍帧），做调侃反应。
 *
 * 回合节拍靠 [onSpeakCompleted]（speak 播放完成事件）链式推进：
 * ```
 * IDLE ──激活词──▶ INTRO（激活回合照常发送,指令教 LLM 讲规则;pose 车道预热）
 * INTRO ──回合完成──▶ POINTING（指 VRMA + speak"看这边!",判定窗开）
 * POINTING ──onHeadPose 早退/窗截止──▶ 本局结论落定(败局当场抓帧存证)
 * POINTING ──go 信号 speak 完成──▶ 宣判 speak ──▶ ANNOUNCING
 * ANNOUNCING ──宣判 speak 完成──▶ POINTING（自动连局）
 * 任意态 ──退出词──▶ sendTurn 战报回合(消费该句)──▶ IDLE
 * POINTING ──go 信号完成时仍无结论(窗已截止+无样本=没脸)──▶ 作废重指;连续
 *            [MAX_NOFACE_ROUNDS] 局 ──▶ speak 提示查摄像头 ──▶ IDLE
 * 打断(barge-in/被让位)──▶ 弃当前局,立即重指(链式节拍不允许卡死等待)
 * ```
 * 游戏中用户插话（非退出词）一律消费掉不发送——语音不参与本玩法，全收口
 * 保护节奏（与猜拳"JUDGING 消费、其余放行"不同）。
 */
class LookHereSkill(
    /** 屏幕方向 → 播放路径（assets 相对路径），app 注入；缺一个方向=该向指不出。 */
    private val dirAssets: Map<LookDir, String>,
    /** 可注入随机源（单测复现）。返回 [0, bound)。 */
    private val randomInt: (Int) -> Int = { Random.nextInt(it) },
) : AvatarSkill {

    override val id: String = "look"

    /** 提示词语言（多语言支持），demo 由 MainActivity 随界面语言更新。 */
    var lang: Lang = Lang.ZH

    private val texts: LookHereTexts get() = LookHereTexts.of(lang)

    /** 判定窗参数（真机调优入口；两个符号布尔是标定常量，见 [LookHereTuning] doc）。 */
    var tuning: LookHereTuning = LookHereTuning()

    /**
     * 屏幕方向 → 手势文件方向。**真机标定结果（2026-10-06）**：四个 VRMA 全部
     * 只动左臂（角色视角命名）——`gesture_left.vrma` 实际指向画面右、
     * `gesture_right.vrma` 实际指向画面左（face_pose 探针证明头部符号无误 +
     * 用户"同向判赢"症状反推），故左右交换；up/down 不受角色镜像影响，同名直映。
     */
    var screenToAssetDir: Map<LookDir, LookDir> = mapOf(
        LookDir.UP to LookDir.UP,
        LookDir.DOWN to LookDir.DOWN,
        LookDir.LEFT to LookDir.RIGHT,
        LookDir.RIGHT to LookDir.LEFT,
    )

    enum class State { IDLE, INTRO, POINTING, ANNOUNCING }

    /** 一局结论（宣判/战报/调试用）。 */
    data class RoundResult(val userWon: Boolean, val userDir: LookDir?, val frozen: Boolean)

    var state: State = State.IDLE
        private set
    /** 已判定局数（无脸作废的局不计）。 */
    var round: Int = 0
        private set
    var scoreMe: Int = 0
        private set
    var scoreYou: Int = 0
        private set
    var lastPoint: LookDir? = null
        private set
    var lastResult: RoundResult? = null
        private set
    /** 最近一次被判负瞬间的抓拍帧（≤1 个采样周期的新鲜度,战报用）。 */
    var caughtFrame: String? = null
        private set
    var noFaceStreak: Int = 0
        private set

    private var judge: LookHereJudge? = null
    private var roundStartedAtMs = 0L
    /** go 信号的 speak 已完成（或已失败）——判定落定后宣判只等它，防自抢音频。 */
    private var goSignalSettled = false

    /**
     * 会话中性位（判定=相对它的**绝对方向**，用户不回正也能被持续读出方向，
     * 真机 2026-10-06："我一直往左看"却连判发呆的根治）。来源=激活介绍期
     * （用户被告知回正看镜头）最后 1s 采样的中位数；此后每回合仅当候选中位数
     * 贴近现行中性位时才跟踪漂移（用户保持偏头时中性位绝不跟着跑）。
     */
    private var sessionNeutral: Pair<Float, Float>? = null
    private val introPoses = ArrayDeque<Triple<Long, Float, Float>>()

    override val isActive: Boolean get() = state != State.IDLE

    // ── 事件缝 ────────────────────────────────────────────────────────────

    override fun onUtterance(text: String, ctx: SkillContext): Boolean {
        val t = text.trim()
        if (state == State.IDLE) {
            if (ACTIVATE_PATTERN.containsMatchIn(t)) {
                round = 0
                scoreMe = 0
                scoreYou = 0
                noFaceStreak = 0
                caughtFrame = null
                sessionNeutral = null
                introPoses.clear()
                state = State.INTRO
                ctx.event("activated, intro turn follows (head-pose lane warms up)")
            }
            return false
        }
        if (EXIT_PATTERN.containsMatchIn(t)) {
            exitWithReport(ctx, "user asked to stop")
            return true
        }
        if (state == State.POINTING) {
            // 游戏中插话：消费掉保护节奏。刚开局 [RESTART_GUARD_MS] 内的重指忽略
            // （打断路径已自动重开局，防插话+打断双触发连环重指）。
            if (ctx.host.nowMs() - roundStartedAtMs >= RESTART_GUARD_MS) {
                ctx.event("mid-game utterance — round restarted")
                startRound(ctx)
            }
            return true
        }
        if (state == State.ANNOUNCING) {
            ctx.event("mid-game utterance consumed (announcing)")
            return true
        }
        return false // INTRO：激活回合照常默认发送
    }

    override fun onHeadPose(yawDeg: Float, pitchDeg: Float, ctx: SkillContext) {
        // INTRO 期持续采样（用户此时被告知回正看镜头）——首局的中性位来源
        if (state == State.INTRO) {
            val now = ctx.host.nowMs()
            introPoses.addLast(Triple(now, yawDeg, pitchDeg))
            while (introPoses.isNotEmpty() && now - introPoses.first().first > INTRO_NEUTRAL_WINDOW_MS) {
                introPoses.removeFirst()
            }
            return
        }
        if (state != State.POINTING) return
        val j = judge ?: return
        val outcome = j.onSample(ctx.host.nowMs(), yawDeg, pitchDeg)
        if (outcome != null) {
            resolve(outcome, ctx)
        } else if (lastResult != null && goSignalSettled) {
            // go 信号已结束（典型：TTS 失败即时完成）而结论随后才由样本落定
            // ——没有下一次 speak 完成事件可等了，这里直接宣判自愈
            announce(ctx)
        }
    }

    /**
     * speak 播放完成（成功或失败都会来；被 interrupt 掐断的不会）：回合节拍
     * 的推进事件。go 信号完成 → 宣判（或无脸路径）；宣判完成 → 下一局。
     */
    override fun onSpeakCompleted(spokenText: String, ctx: SkillContext) {
        when (state) {
            State.POINTING -> {
                goSignalSettled = true
                val elapsed = ctx.host.nowMs() - roundStartedAtMs
                when {
                    lastResult != null -> announce(ctx)
                    elapsed >= tuning.windowMs + NOFACE_SLACK_MS -> noFaceRound(ctx)
                    // go 信号被提前掐断（打断/无 TTS）：样本仍会驱动判定/终判
                    else -> ctx.event("go-signal cut short, samples still driving the window")
                }
            }
            State.ANNOUNCING -> startRound(ctx)
            else -> Unit
        }
    }

    override fun onTurnCompleted(reply: String, ctx: SkillContext) {
        if (state == State.INTRO) {
            ctx.event("intro done, first round starts")
            startRound(ctx)
        }
    }

    override fun onTurnFailed(ctx: SkillContext) {
        if (state == State.INTRO) {
            state = State.IDLE
            ctx.event("intro turn failed, deactivated")
        }
    }

    override fun onInterrupted(ctx: SkillContext) {
        when (state) {
            State.INTRO -> {
                ctx.event("intro interrupted, starting anyway")
                startRound(ctx)
            }
            // 打断源（barge-in/被让位）没有可靠的后续事件，弃局立即重指，
            // 不允许链式节拍卡死；退出路径先置 IDLE 再 sendTurn，不会走到这里
            State.POINTING, State.ANNOUNCING -> {
                ctx.event("round interrupted, restarting")
                startRound(ctx)
            }
            else -> Unit
        }
    }

    override fun onExit(ctx: SkillContext) {
        // registry 单活跃收口/ai_cmd skill_exit 的强制退场：静默清理，不发战报
        // （战报回合会与新激活技能的回合抢播放）
        if (state == State.IDLE) return
        state = State.IDLE
        ctx.event("deactivated (forced), rounds=$round score=$scoreMe:$scoreYou")
    }

    // ── 指令注入 ──────────────────────────────────────────────────────────

    override fun turnDirective(ctx: SkillContext): String? =
        if (state == State.INTRO) texts.introDirective() else null

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun startRound(ctx: SkillContext, forced: LookDir? = null) {
        val dir = forced
            ?: LookDir.entries[randomInt(LookDir.entries.size).coerceIn(0, LookDir.entries.lastIndex)]
        lastPoint = dir
        val asset = dirAssets[screenToAssetDir[dir] ?: dir]
        val played = asset != null && ctx.host.playGestureFile(asset)
        // 首局中性位 = 介绍期最后 1s 采样的中位数（null=介绍期没样本，本局自我形成）
        if (sessionNeutral == null && introPoses.isNotEmpty()) {
            val yaws = introPoses.map { it.second }
            val pitches = introPoses.map { it.third }
            sessionNeutral = median(yaws) to median(pitches)
        }
        introPoses.clear()
        judge = LookHereJudge(tuning, ctx.host.nowMs(), sessionNeutral)
        roundStartedAtMs = ctx.host.nowMs()
        goSignalSettled = false
        state = State.POINTING
        ctx.host.speak(texts.lookHereCry)
        ctx.event(
            "round point=${dir.zh} played=$played forced=${forced != null} " +
                "neutral=${sessionNeutral?.let { "y=%.0f/p=%.0f".format(it.first, it.second) } ?: "forming"} " +
                "(window=${tuning.windowMs}ms)"
        )
    }

    /** 判定落定（早退或窗截止）：当场算胜负、更新比分，败局瞬间抓帧存证。 */
    private fun resolve(outcome: Outcome, ctx: SkillContext) {
        val point = lastPoint ?: return
        val r = when (outcome) {
            is Outcome.Turned -> RoundResult(
                userWon = outcome.dir.opposite() == point,
                userDir = outcome.dir,
                frozen = false,
            )
            Outcome.Frozen -> RoundResult(userWon = false, userDir = null, frozen = true)
        }
        lastResult = r
        round += 1
        noFaceStreak = 0
        // 中性位跨回合持久化（仅当本局候选贴近时 judge 才会更新它——保持偏头不跟跑）
        judge?.currentNeutral?.let { sessionNeutral = it }
        if (!r.userWon) caughtFrame = ctx.host.snapshotLatest()
        if (r.userWon) scoreYou++ else scoreMe++
        ctx.event(
            "round #$round resolved: point=${point.zh} " +
                (if (r.frozen) "frozen" else "user=${r.userDir?.zh}") +
                " → ${if (r.userWon) "user wins" else "avatar wins"} " +
                "($scoreMe:$scoreYou), frame captured=${!r.userWon && caughtFrame != null}"
        )
    }

    /** 宣判 speak 发起（go 信号播完才发，不打断自己的 go 信号音频）。 */
    private fun announce(ctx: SkillContext) {
        val r = lastResult ?: return
        state = State.ANNOUNCING
        val tail = when {
            r.frozen -> texts.frozenTail(round % 2 == 1)
            r.userWon -> texts.dodgedTail(round % 2 == 1)
            else -> texts.caughtTail(round % 2 == 1)
        }
        ctx.host.speak(tail)
        ctx.event("announced round #$round (${if (r.userWon) "dodged" else "caught"})")
    }

    private fun noFaceRound(ctx: SkillContext) {
        noFaceStreak += 1
        if (noFaceStreak >= MAX_NOFACE_ROUNDS) {
            state = State.IDLE
            ctx.host.speak(texts.noFaceHint())
            ctx.event("no face for $noFaceStreak rounds — exited with a camera hint")
        } else {
            ctx.event("round void — no face samples (streak=$noFaceStreak), re-pointing")
            startRound(ctx)
        }
    }

    private fun exitWithReport(ctx: SkillContext, why: String) {
        if (state == State.IDLE) return
        val hadRounds = round > 0
        state = State.IDLE // 先置 IDLE：sendTurn 打断在播 speak 的 onInterrupted 广播到此为止
        if (hadRounds) {
            val frame = caughtFrame
            ctx.host.sendTurn(
                texts.reportDirective(scoreMe, scoreYou, round, frame != null),
                listOfNotNull(frame),
            )
        }
        ctx.event("deactivated ($why), rounds=$round score=$scoreMe:$scoreYou report=$hadRounds")
    }

    // ── 调试（ai_cmd look_status / look_throw <方向>）─────────────────────

    override fun debugCommand(arg: String?, ctx: SkillContext): String {
        val a = arg?.trim()?.lowercase()
        if (a == null || a == "status" || a == "state") {
            val outcome = lastResult?.let { r ->
                if (r.frozen) "frozen" else "user=${r.userDir?.label(lang)} " +
                    if (r.userWon) "(dodged)" else "(caught)"
            } ?: "-"
            return "look: state=$state rounds=$round score=$scoreMe:$scoreYou " +
                "point=${lastPoint?.label(lang) ?: "-"} outcome=$outcome " +
                "noFaceStreak=$noFaceStreak caughtFrame=${caughtFrame != null}"
        }
        if (a == "exit") {
            exitWithReport(ctx, "debug command")
            return "look: exited (state=$state)"
        }
        if (a.startsWith("throw_")) {
            val dir = LookDir.parse(a.removePrefix("throw_"))
                ?: return "look: throw expects throw_up|throw_down|throw_left|throw_right"
            val file = screenToAssetDir[dir]?.let { dirAssets[it] } ?: "(missing asset!)"
            startRound(ctx, dir)
            return "look: forced point ${dir.label(lang)} file=$file, state=$state " +
                "(watch which way the avatar points on screen — calibration entry)"
        }
        return "look: debug expects status|exit|throw_up|throw_down|throw_left|throw_right"
    }

    companion object {
        /**
         * 命中即激活（本地正则，不等 LLM）。中英合并、不随 [lang] 切换（激活词
         * 来自用户语音，识别语言跟随设备而非界面语言，猜拳同款约定）。
         */
        val ACTIVATE_PATTERN = Regex(
            "看这边|看你这边|方向反转|反应力测试|look\\W*over\\W*there|look\\W*this\\W*way|acchi\\W*muite",
            RegexOption.IGNORE_CASE,
        )

        /** 命中即退场（战报回合收尾）。 */
        val EXIT_PATTERN = Regex(
            "不玩了|不玩啦|退出看这边|结束游戏|别玩了|stop\\s+playing|let'?s\\s+(stop|quit)|i('m|\\s+am)\\s+done",
            RegexOption.IGNORE_CASE,
        )

        /** 游戏中插话重启当前局的保护窗：刚开局的插话只消费不重指。 */
        const val RESTART_GUARD_MS = 1_500L

        /** go 信号完成后判定"这局没脸"需超过窗长的宽限（TTS 提前完成的场景）。 */
        const val NOFACE_SLACK_MS = 250L

        /** 连续无脸作废多少局后退场并提示查摄像头。 */
        const val MAX_NOFACE_ROUNDS = 3

        /** 技能手势 assets 目录（LLM 动作目录扫描排除用）。 */
        const val SKILL_ASSET_DIR = "12_技能_看这边"

        /** 首局会话中性位的介绍期采样窗长。 */
        const val INTRO_NEUTRAL_WINDOW_MS = 1_000L

        private fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.toFloatArray().also { it.sort() }
            return sorted[sorted.size / 2]
        }
    }
}
