package com.neethu.orchestrator.skill

import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.MimicTexts

/**
 * 「模仿我」动作模仿技能——第三个技能实现
 * （docs/mimic-skill-feasibility.md）。三个技能里"感知最重、LLM 最轻"的一个：
 * MediaPipe PoseLandmarker 逐帧解算用户上半身方向 → **app 车道直驱渲染引擎**
 * （`AvatarController.setMimicPose`，**绕过 orchestrator、绕过 LLM**），本技能
 * 只管生命周期——激活/退场/可见性降级阶梯：
 *
 * ```
 * IDLE ──激活词──▶ INTRO（激活回合照常发送,指令教 LLM 答应+讲玩法）
 * INTRO ──回合完成──▶ ACTIVE（app 车道门控放开,开始逐帧模仿）
 * ACTIVE ──退出词──▶ BANTER（战报回合=用户最后姿势抓拍 + 调侃指令）──▶ IDLE
 * ACTIVE ──可见性阶梯走完──▶ speak 提示 ──▶ IDLE（没人在场,无战报）
 * INTRO ──打断/失败──▶ ACTIVE（模仿不依赖 LLM,开场被打断照样开始）
 * ```
 *
 * 与看这边"插话一律消费"刻意不同：**模仿无回合节奏，用户边模仿边聊天是合理
 * 场景**——非退出词一律放行（return false），插话经 activeDirective 保持短句。
 */
class MimicSkill : AvatarSkill {

    override val id: String = "mimic"

    /** 提示词语言（多语言支持），demo 由 MainActivity 随界面语言更新。 */
    var lang: Lang = Lang.ZH

    private val texts: MimicTexts get() = MimicTexts.of(lang)

    enum class State { IDLE, INTRO, ACTIVE, BANTER }

    var state: State = State.IDLE
        private set

    /** 已催促的「不可见 episode」数（每次重新看见重置当前 episode 计数）。 */
    var promptedEpisodes: Int = 0
        private set

    /** 当前不可见 episode 是否已催促过。 */
    var promptedThisEpisode: Boolean = false
        private set

    /** 本场是否真的模仿过（有可见样本）——决定退场要不要战报。 */
    var hadSignal: Boolean = false
        private set

    private var falseSinceMs = -1L  // -1 = 当前不在「不可见 episode」中

    override val isActive: Boolean get() = state != State.IDLE

    // ── 事件缝 ────────────────────────────────────────────────────────────

    override fun onUtterance(text: String, ctx: SkillContext): Boolean {
        val t = text.trim()
        if (state == State.IDLE) {
            if (ACTIVATE_PATTERN.containsMatchIn(t)) {
                promptedEpisodes = 0
                promptedThisEpisode = false
                hadSignal = false
                falseSinceMs = 0L
                state = State.INTRO
                ctx.event("activated, intro turn follows (body lane warms up)")
            }
            return false
        }
        if (EXIT_PATTERN.containsMatchIn(t)) {
            exitWithReport(ctx, "user asked to stop")
            return true
        }
        // INTRO/ACTIVE/BANTER 的其余插话：放行（模仿无回合节奏，聊天合法）
        return false
    }

    override fun onBodyTracking(visible: Boolean, ctx: SkillContext) {
        if (state != State.ACTIVE) return
        if (visible) {
            hadSignal = true
            falseSinceMs = -1L
            promptedThisEpisode = false
            return
        }
        val now = ctx.host.nowMs()
        if (falseSinceMs < 0L) falseSinceMs = now
        val goneFor = now - falseSinceMs
        when {
            goneFor >= GONE_EXIT_MS || promptedEpisodes >= MAX_PROMPT_EPISODES -> {
                state = State.IDLE
                ctx.host.speak(texts.goneHint())
                ctx.event("body lost for ${goneFor}ms (episodes=$promptedEpisodes) — exited with a camera hint")
            }
            goneFor >= GONE_PROMPT_MS && !promptedThisEpisode -> {
                promptedThisEpisode = true
                promptedEpisodes += 1
                ctx.host.speak(texts.gonePrompt())
                ctx.event("body lost ${goneFor}ms — prompted ($promptedEpisodes/$MAX_PROMPT_EPISODES)")
            }
            else -> Unit
        }
    }

    override fun onTurnCompleted(reply: String, ctx: SkillContext) {
        when (state) {
            State.INTRO -> {
                state = State.ACTIVE
                ctx.event("intro done, mimicry starts (body lane now feeding the render engine)")
            }
            State.BANTER -> {
                state = State.IDLE
                ctx.event("banter done, deactivated")
            }
            else -> Unit
        }
    }

    override fun onTurnFailed(ctx: SkillContext) {
        onInterrupted(ctx)
    }

    override fun onInterrupted(ctx: SkillContext) {
        when (state) {
            // 模仿是端侧直通，开场回合被 barge-in 掐掉也照常开始
            State.INTRO -> {
                state = State.ACTIVE
                ctx.event("intro interrupted, mimicry starts anyway")
            }
            State.BANTER -> {
                state = State.IDLE
                ctx.event("banter interrupted, deactivated")
            }
            else -> Unit
        }
    }

    override fun onExit(ctx: SkillContext) {
        // registry 单活跃收口/ai_cmd skill_exit 的强制退场：静默清理（app 侧
        // ticker 观察到 isActive=false 会 clearMimicPose 让引擎缓动回待机）
        if (state == State.IDLE) return
        state = State.IDLE
        ctx.event("deactivated (forced)")
    }

    // ── 指令注入 ──────────────────────────────────────────────────────────

    override fun turnDirective(ctx: SkillContext): String? = when (state) {
        State.INTRO -> texts.introDirective()
        State.ACTIVE -> texts.activeDirective()
        else -> null
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private fun exitWithReport(ctx: SkillContext, why: String) {
        if (state == State.IDLE) return
        state = State.BANTER // 先离 ACTIVE：sendTurn 的事件不再触发可见性阶梯
        falseSinceMs = -1L
        if (hadSignal) {
            val frame = ctx.host.snapshotLatest()
            ctx.host.sendTurn(texts.banterDirective(frame != null), listOfNotNull(frame))
            ctx.event("exiting ($why), banter turn sent (frame=${frame != null})")
        } else {
            // 从没看见过人（激活即退）：只提示不战报
            state = State.IDLE
            ctx.host.speak(texts.goneHint())
            ctx.event("exiting ($why), never saw a body — camera hint only")
        }
    }

    // ── 调试（ai_cmd mimic_status）────────────────────────────────────────

    override fun debugCommand(arg: String?, ctx: SkillContext): String {
        val a = arg?.trim()?.lowercase()
        if (a == null || a == "status" || a == "state") {
            return "mimic: state=$state prompts=$promptedEpisodes " +
                "promptedThisEpisode=$promptedThisEpisode hadSignal=$hadSignal"
        }
        if (a == "exit") {
            exitWithReport(ctx, "debug command")
            return "mimic: exited (state=$state)"
        }
        return "mimic: debug expects status|exit"
    }

    companion object {
        /**
         * 命中即激活（本地正则，不等 LLM）。中英合并、不随 [lang] 切换（激活词
         * 来自用户语音，识别语言跟随设备而非界面语言，猜拳/看这边同款约定）。
         */
        val ACTIVATE_PATTERN = Regex(
            "模仿我|学我动作|跟我做|学我做|copy\\W*me|mimic\\W*me|imitate\\W*me|do\\W*as\\W*i\\W*do",
            RegexOption.IGNORE_CASE,
        )

        /** 命中即退场（战报回合收尾）。刻意不收"好了/停下"这类高频日常词。 */
        val EXIT_PATTERN = Regex(
            "不模仿了|停止模仿|别学了|不学了|stop\\s+mimicking|stop\\s+copying\\W*me",
            RegexOption.IGNORE_CASE,
        )

        /** 不可见持续多久催促一次（每个 episode 一次）。 */
        const val GONE_PROMPT_MS = 2_000L

        /** 不可见持续多久直接退场提示。 */
        const val GONE_EXIT_MS = 8_000L

        /** 累计催促多少个 episode 后退场（反复出画的用户）。 */
        const val MAX_PROMPT_EPISODES = 3
    }
}
