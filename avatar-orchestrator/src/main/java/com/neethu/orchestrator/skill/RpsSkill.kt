package com.neethu.orchestrator.skill

import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.RpsTexts
import com.neethu.orchestrator.i18n.RpsVerdict
import com.neethu.orchestrator.i18n.rpsTextsOf
import kotlin.random.Random

/**
 * 猜拳技能（石头剪刀布）——技能框架的第一个实现（docs/rps-skill-feasibility.md §4.4）。
 *
 * 触发有两条路（P0/P2 并存，谁先到谁主导）：
 *  - **P0 语音快路径**：ARMED 态 VAD 一判完句尾、先于 ASR 看 wav 时长——短句
 *    （≤[THROW_MAX_WAV_MS]）即视为出拳信号，本地随机出手势（VRMA）+抓帧；
 *    ASR 文本稍后到，作为「看图裁判」回合发给大模型（指令行告知本地已出的
 *    手，模型不改口，只看图判断用户的手并宣布胜负）。
 *  - **P2 视觉路径**（[onUserGesture]，MediaPipe GestureRecognizer 端侧识别）：
 *    用户手势在本地直接已知 → 虚拟人随机出拳后**当场判胜负并 speak() 即时
 *    宣判**（TTS ~1.2s 出声，比 LLM 裁判的 3-5s 快一个量级，且判定是确定值）。
 *    ASR 文本到达后的回合降级为「气氛组」：指令写明本地判定结果，模型只做
 *    临场反应、不得重新判定。VAD 先触发的拳若随后观测到手势，就地补记用户
 *    的手、升级成 P2 判定（VLM 看图误判的根治入口）。
 *
 * 状态机：
 * ```
 * IDLE ──命中激活词──▶ INVITED（收短 VAD 悬停；激活回合照常发送）
 * INVITED ──回合完成/被打断──▶ ARMED
 * ARMED ──短句 VAD──▶ THROWN(本地随机出拳+抓帧,用户的手未知)
 * ARMED ──相机手势──▶ THROWN(本地判定+speak 宣判,用户的手已知)
 * THROWN(手未知) ──相机手势──▶ THROWN(补记用户的手,升级为本地判定)
 * THROWN(手已知) ──相机手势──▶ THROWN(纯手势连局,直接开下一拳)
 * THROWN ──ASR 文本──▶ JUDGING(手未知=VLM 裁判回合;手已知=气氛组回合)
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

    /**
     * 提示词语言（多语言支持）：指令行与宣判词随之切换。demo 由 MainActivity
     * 在语言设置变化时更新（实例跨会话重建保持状态，语言只影响文案）；
     * 默认中文保持既有行为与单测不变。
     */
    var lang: Lang = Lang.ZH

    private val texts: RpsTexts get() = rpsTextsOf(lang)

    enum class Hand(val assetKey: String, val zh: String, val en: String) {
        ROCK("rock", "石头", "rock"),
        SCISSORS("scissor", "剪刀", "scissors"),
        PAPER("paper", "布", "paper");

        /** 语言化显示名（指令行/宣判词/调试输出用）。 */
        fun label(lang: Lang): String = if (lang == Lang.EN) en else zh

        /** 本手是否赢 [other]（P2 本地判定用；P0 由 LLM 看图裁判）。 */
        fun beats(other: Hand): Boolean = when (this) {
            ROCK -> other == SCISSORS
            SCISSORS -> other == PAPER
            PAPER -> other == ROCK
        }

        companion object {
            fun parse(text: String): Hand? = entries.firstOrNull {
                it.assetKey == text || it.zh == text || it.en == text || it.name.lowercase() == text
            }
        }
    }

    enum class State { IDLE, INVITED, ARMED, THROWN, JUDGING }

    /** P2 本地判定结论（以用户视角命名，宣判与气氛组指令共用）。 */
    enum class Verdict(val zh: String, val en: String) {
        USER_WIN("用户赢", "the user won"),
        AVATAR_WIN("你赢", "you (the avatar) won"),
        DRAW("平局", "a draw");

        /** 语言化显示名（指令行/宣判词/调试输出用）。 */
        fun label(lang: Lang): String = if (lang == Lang.EN) en else zh

        /** 映射到文案目录的宣判结论键。 */
        fun toRps(): RpsVerdict = when (this) {
            USER_WIN -> RpsVerdict.USER_WIN
            AVATAR_WIN -> RpsVerdict.AVATAR_WIN
            DRAW -> RpsVerdict.DRAW
        }
    }

    /** 待裁判的一拳：本地已出的手势 + 用户的手（P2 本地判定时非空）+ 出拳瞬间的抓拍帧。 */
    data class PendingThrow(val choice: Hand, val userChoice: Hand?, val frame: String?, val round: Int)

    var state: State = State.IDLE
        private set
    var round: Int = 0
        private set
    var lastChoice: Hand? = null
        private set

    /** 用户最近一次被相机确认的手势（P2 观测/调试用）。 */
    var lastUserGesture: Hand? = null
        private set

    /** 最近一局的本地判定结论（P2；VLM 裁判局不写）。 */
    var lastVerdict: Verdict? = null
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

    /**
     * P2 视觉路径：相机确认的用户手势（1=石头,2=剪刀,3=布，0/未知忽略）。
     * 用户的手本地已知，这一拳直接本地判定+speak 宣判，不等 LLM——见类 doc。
     * JUDGING 中忽略（气氛组回合还在流式输出，这时的手势多半是说话时的比划）。
     */
    override fun onUserGesture(gesture: Int, ctx: SkillContext) {
        val user = gestureCodeToHand(gesture)
        if (user == null) return
        when {
            state == State.ARMED -> throwWithUser(user, ctx)
            state == State.THROWN && pending?.userChoice == null -> upgradeWithUser(user, ctx)
            state == State.THROWN -> throwWithUser(user, ctx)
            else -> Unit
        }
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
        State.INVITED -> texts.invitedDirective()
        State.ARMED -> texts.armedDirective(round)
        State.THROWN, State.JUDGING -> judgeDirective(pending)
    }

    private fun judgeDirective(p: PendingThrow?): String {
        val n = p?.round ?: round
        val choice = p?.choice ?: lastChoice ?: Hand.SCISSORS
        val announced = texts.announced(choice.label(lang))
        // P2 本地判定局:用户的手摄像头已识别,胜负当场宣布过,LLM 只做气氛组
        val user = p?.userChoice
        if (user != null) {
            val verdict = verdictOf(user, choice)
            return texts.judgedDirective(n, user.label(lang), verdict.label(lang), announced)
        }
        return if (p?.frame != null) {
            texts.frameJudgeDirective(n, choice.label(lang), announced)
        } else {
            texts.noFrameJudgeDirective(n, choice.label(lang), announced)
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
        pending = PendingThrow(choice, userChoice = null, frame = frame, round = round)
        state = State.THROWN
        ctx.event(
            "throw #$round choice=${choice.zh}${if (viaDebug) " (debug)" else ""} " +
                "played=$played frame=${frame != null}"
        )
    }

    /**
     * P2 本地权威判定出拳：用户的手由相机确认，本地随机出自己的手、当场算
     * 胜负并 speak() 即时宣判（无 LLM 往返）。之后的 ASR 文本走气氛组回合。
     */
    private fun throwWithUser(user: Hand, ctx: SkillContext) {
        val mine = Hand.entries[randomInt(Hand.entries.size).coerceIn(0, Hand.entries.lastIndex)]
        val asset = handAssets[mine]
        val played = asset != null && ctx.host.playGestureFile(asset)
        val frame = ctx.host.snapshotImage()
        round += 1
        lastChoice = mine
        lastUserGesture = user
        lastVerdict = verdictOf(user, mine)
        pending = PendingThrow(mine, userChoice = user, frame = frame, round = round)
        state = State.THROWN
        ctx.host.speak(verdictLine(lastVerdict!!, user, mine))
        ctx.event(
            "gesture throw #$round user=${user.zh} mine=${mine.zh} verdict=${lastVerdict} " +
                "played=$played frame=${frame != null} (local judging)"
        )
    }

    /**
     * VAD 先触发的拳（用户的手未知）随后观测到手势：同一局补记用户的手，
     * 升级为本地判定并即时宣判——出拳/抓帧不重做（这一拳已经摆出来了）。
     */
    private fun upgradeWithUser(user: Hand, ctx: SkillContext) {
        val p = pending ?: return
        lastUserGesture = user
        lastVerdict = verdictOf(user, p.choice)
        pending = p.copy(userChoice = user)
        ctx.host.speak(verdictLine(lastVerdict!!, user, p.choice))
        ctx.event(
            "late gesture upgraded round #${p.round} to local judging: user=${user.zh} " +
                "mine=${p.choice.zh} verdict=$lastVerdict"
        )
    }

    private fun verdictOf(user: Hand, mine: Hand): Verdict = when {
        user == mine -> Verdict.DRAW
        user.beats(mine) -> Verdict.USER_WIN
        else -> Verdict.AVATAR_WIN
    }

    /** 即时宣判词（直通 TTS，无 LLM）；变体按局数轮换，避免连局复读机。 */
    private fun verdictLine(verdict: Verdict, user: Hand, mine: Hand): String =
        texts.handsLine(user.label(lang), mine.label(lang)) +
            texts.verdictTail(verdict.toRps(), round % 2 == 1)

    private fun judge(spoken: String, ctx: SkillContext) {
        val p = pending
        if (p == null) {
            state = State.ARMED
            return
        }
        state = State.JUDGING
        // 文本进历史与字幕面板由调用方推送;这里发出的回合文本带出拳语境
        ctx.host.sendTurn(spoken.ifBlank { texts.throwPlaceholder }, listOfNotNull(p.frame))
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
            return "rps: state=$state round=$round last=${lastChoice?.label(lang) ?: "-"} " +
                "user=${lastUserGesture?.label(lang) ?: "-"} verdict=${lastVerdict?.label(lang) ?: "-"} " +
                "pendingFrame=${pending?.frame != null}"
        }
        if (a == "exit") {
            exit(ctx, "debug command")
            return "rps: exited (state=${state})"
        }
        // gesture_<手>：模拟一次相机确认的用户手势（ai_cmd rps_gesture），
        // 走真机 MediaPipe 之外的同一入口，免摄像头驱动本地判定路径
        if (a.startsWith("gesture_")) {
            val hand = Hand.parse(a.removePrefix("gesture_"))
                ?: return "rps: gesture expects gesture_rock|gesture_scissor|gesture_paper"
            val before = round
            onUserGesture(hand.ordinal + 1, ctx)
            return if (round != before) {
                "rps: user gesture ${hand.label(lang)} → mine=${lastChoice?.label(lang)} verdict=${lastVerdict?.label(lang)}, state=$state"
            } else {
                "rps: gesture ignored in state=$state (need ARMED, or THROWN for the next round)"
            }
        }
        val hand = Hand.parse(a)
        if (hand != null) {
            if (state != State.ARMED) return "rps: cannot throw in state=$state (need ARMED)"
            doThrow(hand, ctx, viaDebug = true)
            return "rps: threw ${hand.label(lang)}, state=$state — next utterance becomes the judge turn"
        }
        return "rps: debug expects status|exit|rock|scissor|paper|gesture_<hand>"
    }

    companion object {
        /**
         * 命中即激活技能（本地正则，不等 LLM）。中英双语词表合并在同一正则里
         * （不随 [lang] 切换）：激活词来自用户语音，识别语言跟随设备/识别引擎
         * 而非界面语言，双语都收才不漏触发。
         */
        val ACTIVATE_PATTERN = Regex(
            "猜拳|石头剪刀布|剪刀石头布|划拳|rock\\W*paper\\W*scissors",
            RegexOption.IGNORE_CASE,
        )

        /** 命中即退场（同 [ACTIVATE_PATTERN]，双语合并）。 */
        val EXIT_PATTERN = Regex(
            "不玩了|不玩啦|退出猜拳|结束猜拳|别猜了" +
                "|stop\\s+playing|let'?s\\s+(stop|quit)|i('m|\\s+am)\\s+done|no\\s+more\\s+rounds",
            RegexOption.IGNORE_CASE,
        )

        /** ARMED 态短于此的语音句视为出拳信号。 */
        const val THROW_MAX_WAV_MS = 2_500L

        /** 技能态的 VAD 句尾悬停（默认 800→400，出拳延迟减半）。 */
        const val FAST_HANGOVER_MS = 400L

        /** [AvatarSkill.onUserGesture] 的手势码约定：0=无,1=石头,2=剪刀,3=布。 */
        fun gestureCodeToHand(code: Int): Hand? = Hand.entries.getOrNull(code - 1)
    }
}
