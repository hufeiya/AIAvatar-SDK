package com.neethu.orchestrator.i18n

import com.neethu.corelib.Lang

/**
 * 「模仿我」技能（MimicSkill）的双语文案目录。维护约定同 [PromptTexts]/
 * [LookHereTexts]——中英两版按相同顺序对齐，改一处同步两语；
 * `PromptTextsI18nTest` 断言 EN 版无中文字符。
 */
interface MimicTexts {
    val lang: Lang

    /** INTRO：激活回合的指令行（答应 + 讲玩法 + 引导进视频模式/露出上半身）。 */
    fun introDirective(): String

    /** 模仿进行中用户插话的每轮指令行（保持短句，别长篇大论打断模仿节奏）。 */
    fun activeDirective(): String

    /** 持续看不到上半身时的催促（直通 TTS，短句）。 */
    fun gonePrompt(): String

    /** 可见性阶梯走完退场前的提示（直通 TTS，短句）。 */
    fun goneHint(): String

    /**
     * 退场战报回合的指令行（[hadFrame]=是否附了用户最后姿势的抓拍）。
     * LLM 只做点评调侃，不复述玩法。
     */
    fun banterDirective(hadFrame: Boolean): String

    companion object {
        /** 语言 → 文案目录。 */
        fun of(lang: Lang): MimicTexts = if (lang == Lang.EN) MimicTextsEn else MimicTextsZh
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 简体中文（默认）
// ─────────────────────────────────────────────────────────────────────────────

object MimicTextsZh : MimicTexts {
    override val lang: Lang = Lang.ZH

    override fun introDirective(): String =
        "【技能:模仿我】用户想让你模仿他的动作。请爽快答应并一句话讲清:" +
            "他做什么你就镜像做什么——他动左手你动右手,像照镜子一样。" +
            "提醒他对着手机摄像头露出上半身、退后一点距离,然后开始跟着他动。"

    override fun activeDirective(): String =
        "【技能:模仿我】你正在实时镜像模仿用户的动作(摄像头驱动,无需你做任何操作)。" +
            "用户此刻在插话:回复保持一两句短口语,别打断模仿的节奏,也别复述玩法。"

    override fun gonePrompt(): String =
        "咦?我看不到你的上半身了,退后一点,让摄像头拍到胳膊,模仿才准!"

    override fun goneHint(): String =
        "我找不到你啦,想继续模仿的话,回到镜头前再喊我!"

    override fun banterDirective(hadFrame: Boolean): String =
        "【技能:模仿我·战报】用户刚结束「模仿我」游戏,你们互相镜像模仿了一阵。" +
            (if (hadFrame) "附图是他最后的姿势,结合画面调侃他学得怎么样(其实是你学他)。" else "这一场没有留下抓拍画面。") +
            "请用一两句口语自然点评,可以夸可以损,不要复述玩法,可加表情动作标签。"
}

// ─────────────────────────────────────────────────────────────────────────────
// English
// ─────────────────────────────────────────────────────────────────────────────

object MimicTextsEn : MimicTexts {
    override val lang: Lang = Lang.EN

    override fun introDirective(): String =
        "[Skill: Imitate Me] The user wants you to mirror their movements. Agree and explain " +
            "in one sentence: whatever they do, you mirror — when they move their left hand, " +
            "you move your right hand, like a reflection. Tell them to step back so the front " +
            "camera sees their upper body, then start following their moves."

    override fun activeDirective(): String =
        "[Skill: Imitate Me] You are currently mirroring the user's movements in real time " +
            "(camera-driven, you don't need to do anything). The user is chatting mid-mimicry: " +
            "reply in one or two short sentences, don't break the rhythm, don't restate the rules."

    override fun gonePrompt(): String =
        "Huh? I can't see your upper body — step back a little so the camera sees your arms!"

    override fun goneHint(): String =
        "I lost you! Come back in front of the camera and call me if you want to play again!"

    override fun banterDirective(hadFrame: Boolean): String =
        "[Skill: Imitate Me · game report] The user just finished a round of \"Imitate Me\", " +
            "mirroring each other for a while. " +
            (if (hadFrame) "The attached photo is their final pose — tease them about how " +
                "well they copied you (well, technically you copied them). " else "No snapshot was captured. ") +
            "Give a one-or-two sentence natural verdict, praise or roast freely, don't restate " +
            "the rules; expression and action tags are welcome."
}
