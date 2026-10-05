package com.neethu.orchestrator.skill

import kotlin.random.Random

/**
 * 猜拳技能（石头剪刀布）——技能框架的第一个实现（docs/rps-skill-feasibility.md §4.4）。
 *
 * 实时性的关键取舍：大模型只当「裁判」，不当「选手」。用户喊三二一出拳时，
 * 虚拟人的手势由**本地随机**决定并立即用 VRMA 展示（[SkillHost.playGestureFile]），
 * 同时抓一帧相机画面存进技能态；等这句的 ASR 文本回来，再以「看图裁判」回合
 * 发给大模型（指令行告知本地已出的是什么，模型不改口，只判断用户的手势并
 * 宣布胜负）。
 *
 * 触发（P0 形态）：ARMED 态下 VAD 一判完句尾（技能激活时已把悬停收短到
 * [THROW_MAX_WAV_MS] 以下短句的节奏），先于 ASR 看 wav 时长——短句即视为出拳
 * 信号。误触发面=游戏语境里的随口短语，代价只是多玩一局，可接受。
 *
 * 状态机：
 * ```
 * IDLE ──命中激活词──▶ INVITED（收短 VAD 悬停；激活回合照常发送）
 * INVITED ──回合完成/被打断──▶ ARMED
 * ARMED ──短句 VAD──▶ THROWN（本地出拳+抓帧）──ASR 文本──▶ JUDGING（裁判回合）
 * JUDGING ──回合完成/失败/被让位──▶ ARMED
 * 任意态 ──命中退出词 / 强制退场──▶ IDLE（恢复 VAD 悬停）
 * ```
 */
class RpsSkill(
    /** 手势 → 播放路径（assets 相对路径或绝对路径），app 注入，缺一个手势=该手出拳播不出。 */
    private val handAssets: Map<Hand, String>,
    /** 可注入随机源（单测复现）。返回 [0, bound)。 */
    private val randomInt: (Int) -> Int = { Random.nextInt(it) },
) : AvatarSkill {

    override val id: String = "rps"

    enum class Hand(val assetKey: String, val zh: String) {
        ROCK("rock", "石头"),
        SCISSORS("scissor", "剪刀"),
        PAPER("paper", "布");

        /** 本手是否赢 [other]（P2 本地判定用；P0 由 LLM 看图裁判）。 */
        fun beats(other: Hand): Boolean = when (this) {
            ROCK -> other == SCISSORS
            SCISSORS -> other == PAPER
            PAPER -> other == ROCK
        }

        companion object {
            fun parse(text: String): Hand? = entries.firstOrNull {
                it.assetKey == text || it.zh == text || it.name.lowercase() == text
            }
        }
    }

    enum class State { IDLE, INVITED, ARMED, THROWN, JUDGING }

    /** 待裁判的一拳：本地已出的手势 + 出拳瞬间的抓拍帧。 */
    data class PendingThrow(val choice: Hand, val frame: String?, val round: Int)

    var state: State = State.IDLE
        private set
    var round: Int = 0
        private set
    var lastChoice: Hand? = null
        private set
    private var pending: PendingThrow? = null

    /** 待裁判的一拳（观测/测试用，只读）。 */
    val pendingThrow: PendingThrow? get() = pending

    override val isActive: Boolean get() = state != State.IDLE

    // ── 事件缝 ────────────────────────────────────────────────────────────

    override fun onUtterance(text: String, ctx: SkillContext): Boolean {
        val t = text.trim()
        if (state == State.IDLE) {
            if (ACTIVATE_PATTERN.containsMatchIn(t)) {
                state = State.INVITED
                ctx.host.setVadHangover(FAST_HANGOVER_MS)
                ctx.event("activated (vad hangover→${FAST_HANGOVER_MS}ms), invitation turn follows")
            }
            return false
        }
        if (EXIT_PATTERN.containsMatchIn(t)) {
            exit(ctx, "user asked to stop")
            return false
        }
        if (state == State.THROWN) {
            judge(t, ctx)
            return true
        }
        if (state == State.JUDGING) {
            // 裁判回合还在流式输出时用户又开了口：接下来的发送会 supersede 掉
            // 裁判回合（session.send 的打断语义），这里先自愈回 ARMED，避免
            // TurnCompleted 永远不来导致卡死在 JUDGING。这一拳作废，下一拳重开。
            state = State.ARMED
            ctx.event("judge turn superseded by a new utterance, back to ARMED (round dropped)")
            return false
        }
        return false
    }

    override fun onVadUtterance(wavMs: Long, ctx: SkillContext) {
        if (state == State.JUDGING) {
            ctx.event("throw ignored — previous round still being judged")
            return
        }
        if (state != State.ARMED) return
        // 长句=游戏中的普通对话；短句（三二一/石头剪刀布!）=出拳信号
        if (wavMs > THROW_MAX_WAV_MS) return
        val choice = Hand.entries[randomInt(Hand.entries.size).coerceIn(0, Hand.entries.lastIndex)]
        doThrow(choice, ctx, viaDebug = false)
    }

    override fun onTurnCompleted(reply: String, ctx: SkillContext) {
        when (state) {
            State.INVITED -> {
                state = State.ARMED
                ctx.event("invitation accepted, ARMED — waiting for the throw")
            }
            State.JUDGING -> {
                state = State.ARMED
                ctx.event("round judged, ARMED for the next throw")
            }
            else -> Unit
        }
    }

    override fun onTurnFailed(ctx: SkillContext) {
        when (state) {
            State.INVITED -> exit(ctx, "invitation turn failed")
            State.JUDGING -> {
                state = State.ARMED
                ctx.event("judge turn failed, back to ARMED")
            }
            else -> Unit
        }
    }

    override fun onInterrupted(ctx: SkillContext) {
        when (state) {
            State.INVITED -> {
                state = State.ARMED
                ctx.event("invitation interrupted, ARMED")
            }
            State.JUDGING -> {
                state = State.ARMED
                ctx.event("judge turn interrupted, back to ARMED")
            }
            else -> Unit
        }
    }

    override fun onExit(ctx: SkillContext) = exit(ctx, "forced exit")

    // ── 指令注入 ──────────────────────────────────────────────────────────

    override fun turnDirective(ctx: SkillContext): String? = when (state) {
        State.IDLE -> null
        State.INVITED ->
            "【技能:猜拳】用户刚提议玩石头剪刀布。请爽快答应,用一两句话说明玩法:两人同时喊" +
                "「三、二、一」一起出拳,用户出什么手势会由摄像头拍下来给你判定;然后邀请用户出拳。"
        State.ARMED ->
            "【技能:猜拳·进行中】你们正在玩猜拳,已完成 $round 局。用户随时会喊「三二一」出拳," +
                "出拳后系统会自动把摄像头画面发给你裁判;在收到出拳画面之前不要替任何一局宣布结果。" +
                "用户这句话是出拳间隙的普通对话,正常回应,可以顺带催促出拳。"
        State.THROWN, State.JUDGING -> judgeDirective(pending)
    }

    private fun judgeDirective(p: PendingThrow?): String {
        val n = p?.round ?: round
        val choice = p?.choice?.zh ?: lastChoice?.zh ?: Hand.SCISSORS.zh
        val announced = "你(虚拟人)出的是「$choice」,已经当着用户的面做出来了,不要改口。"
        return if (p?.frame != null) {
            "【技能:猜拳·第${n}局判定】用户刚刚喊完三二一并出拳。$announced" +
                "请看随本轮附上的抓拍画面,判断用户出的是石头、剪刀还是布,宣布这一轮胜负并自然地反应" +
                "(赢了别太得意,输了可以不服气或约再一局)。如果画面里看不清手或没有手,就直说没看清," +
                "邀请用户把手举到镜头前再出一局。"
        } else {
            "【技能:猜拳·第${n}局判定】用户刚刚喊完三二一并出拳。$announced" +
                "但这一轮系统没能抓拍到用户画面(相机没开或不在视频模式),请告诉用户你没看到," +
                "提醒进入视频模式(相机开着)再玩,把这一局自然带过。"
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun doThrow(choice: Hand, ctx: SkillContext, viaDebug: Boolean) {
        val asset = handAssets[choice]
        val played = asset != null && ctx.host.playGestureFile(asset)
        // 出拳瞬间立刻抓帧存进技能态——ASR 往返的 ~1s 里抓拍环(深3≈1.5s)可能已被覆盖
        val frame = ctx.host.snapshotImage()
        round += 1
        lastChoice = choice
        pending = PendingThrow(choice, frame, round)
        state = State.THROWN
        ctx.event(
            "throw #$round choice=${choice.zh}${if (viaDebug) " (debug)" else ""} " +
                "played=$played frame=${frame != null}"
        )
    }

    private fun judge(spoken: String, ctx: SkillContext) {
        val p = pending
        if (p == null) {
            state = State.ARMED
            return
        }
        state = State.JUDGING
        // 文本进历史与字幕面板由调用方推送;这里发出的回合文本带出拳语境
        ctx.host.sendTurn(spoken.ifBlank { "（出拳）" }, listOfNotNull(p.frame))
        ctx.event("judge turn #$round sent (spoken=\"${spoken.take(24)}\", images=${if (p.frame != null) 1 else 0})")
    }

    private fun exit(ctx: SkillContext, why: String) {
        val wasActive = state != State.IDLE
        state = State.IDLE
        pending = null
        ctx.host.setVadHangover(ctx.host.defaultVadHangoverMs)
        if (wasActive) ctx.event("deactivated ($why), vad hangover restored")
    }

    // ── 调试（ai_cmd skill rps …）──────────────────────────────────────────

    override fun debugCommand(arg: String?, ctx: SkillContext): String {
        val a = arg?.trim()?.lowercase()
        if (a == null || a == "status" || a == "state") {
            return "rps: state=$state round=$round last=${lastChoice?.zh ?: "-"} pendingFrame=${pending?.frame != null}"
        }
        if (a == "exit") {
            exit(ctx, "debug command")
            return "rps: exited (state=${state})"
        }
        val hand = Hand.parse(a)
        if (hand != null) {
            if (state != State.ARMED) return "rps: cannot throw in state=$state (need ARMED)"
            doThrow(hand, ctx, viaDebug = true)
            return "rps: threw ${hand.zh}, state=$state — next utterance becomes the judge turn"
        }
        return "rps: debug expects status|exit|rock|scissor|paper"
    }

    companion object {
        /** 命中即激活技能（本地正则，不等 LLM）。 */
        val ACTIVATE_PATTERN = Regex("猜拳|石头剪刀布|剪刀石头布|划拳")

        /** 命中即退场。 */
        val EXIT_PATTERN = Regex("不玩了|不玩啦|退出猜拳|结束猜拳|别猜了")

        /** ARMED 态短于此的语音句视为出拳信号。 */
        const val THROW_MAX_WAV_MS = 2_500L

        /** 技能态的 VAD 句尾悬停（默认 800→400，出拳延迟减半）。 */
        const val FAST_HANGOVER_MS = 400L
    }
}
