package com.neethu.orchestrator.i18n

import com.neethu.corelib.Lang

/**
 * 「看这边」技能（LookHereSkill）的双语文案目录。维护约定同 [PromptTexts]/
 * [RpsTexts]——中英两版按相同顺序对齐，改一处同步两语；`PromptTextsI18nTest`
 * 断言 EN 版无中文字符。所有直通 TTS 的短语都刻意短（宣判是每轮节奏的
 * 地板，见 docs/lookhere-skill-feasibility.md §4）。
 */
interface LookHereTexts {
    val lang: Lang

    /** INTRO：激活回合的指令行（答应邀约 + 讲规则——反向转头、同向判负）。 */
    fun introDirective(): String

    /** 每轮指向时喊的 go 信号（与指向手势同时发出）。 */
    val lookHereCry: String

    /** 即时宣判：用户与指向同向被抓（两个变体按局数奇偶轮换防复读机）。 */
    fun caughtTail(oddRound: Boolean): String

    /** 即时宣判：用户呆住没动（同判负）。 */
    fun frozenTail(oddRound: Boolean): String

    /** 即时宣判：用户反向躲开（赢）。 */
    fun dodgedTail(oddRound: Boolean): String

    /** 连续几局画面里没有脸：提示检查摄像头后退场。 */
    fun noFaceHint(): String

    /**
     * 退场战报回合的指令行（带最终比分；[hasFrame]=是否附了被判负瞬间的
     * 抓拍帧）。LLM 只做调侃，不重判、不复述规则。
     */
    fun reportDirective(scoreMe: Int, scoreYou: Int, rounds: Int, hasFrame: Boolean): String

    companion object {
        /** 语言 → 文案目录。 */
        fun of(lang: Lang): LookHereTexts = if (lang == Lang.EN) LookHereTextsEn else LookHereTextsZh
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 简体中文（默认）
// ─────────────────────────────────────────────────────────────────────────────

object LookHereTextsZh : LookHereTexts {
    override val lang: Lang = Lang.ZH

    override fun introDirective(): String =
        "【技能:看这边】用户想玩「看这边」反应游戏。请爽快答应,用一两句话讲清规则:" +
            "你随机朝上下左右一个方向指,用户要立刻往相反方向转头躲开;" +
            "和你指的同向算输,反向算赢,呆住不动也算输。" +
            "提醒他先把头转回正中、看准镜头,然后盯紧你的手,准备好就喊开始。"

    override val lookHereCry: String = "看这边!"

    override fun caughtTail(oddRound: Boolean): String =
        if (oddRound) "——抓到你了!头偏得这么快,还说没看!" else ",抓到你了,眼疾手快也没用!"

    override fun frozenTail(oddRound: Boolean): String =
        if (oddRound) "——发什么呆呢,被抓个正着!" else ",呆住不动,这局算你输!"

    override fun dodgedTail(oddRound: Boolean): String =
        if (oddRound) "——哦?!竟让你躲开了!" else ",好快!这局你赢了!"

    override fun noFaceHint(): String =
        "咦?我看不到你的脸,检查一下摄像头(要在视频模式+前置镜头)再继续!"

    override fun reportDirective(scoreMe: Int, scoreYou: Int, rounds: Int, hasFrame: Boolean): String =
        "【技能:看这边·战报】用户刚结束「看这边」游戏,共${rounds}局,比分你${scoreMe}:${scoreYou}。" +
            (if (hasFrame) "附图是他被判负瞬间的抓拍,结合他的表情调侃。" else "这一场没有留下抓拍画面。") +
            "请用一两句口语自然调侃他刚才的反应,不要复述规则,可以顺势约下次再战。"
}

// ─────────────────────────────────────────────────────────────────────────────
// English
// ─────────────────────────────────────────────────────────────────────────────

object LookHereTextsEn : LookHereTexts {
    override val lang: Lang = Lang.EN

    override fun introDirective(): String =
        "[Skill: Look Over There] The user wants to play the \"Look Over There\" reaction game. " +
            "Agree enthusiastically and explain the rules in a sentence or two: you will point in " +
            "one random direction (up, down, left or right) and the user must instantly turn their " +
            "head the OPPOSITE way to dodge; if they turn the same way you pointed, they lose; the " +
            "opposite way, they win; freezing without moving also loses. Tell them to center their " +
            "head toward the camera first, keep their eyes on your hand, and say you are starting " +
            "when they are ready."

    override val lookHereCry: String = "Look over there!"

    override fun caughtTail(oddRound: Boolean): String =
        if (oddRound) " — caught you! Your head turned so fast, and you still said you weren't looking!"
        else " — caught you! Quick head or not, it's useless!"

    override fun frozenTail(oddRound: Boolean): String =
        if (oddRound) " — what are you daydreaming about? Caught red-handed!" else " — frozen solid, that round is mine!"

    override fun dodgedTail(oddRound: Boolean): String =
        if (oddRound) " — huh?! You actually dodged it!" else " — so fast! You win this round!"

    override fun noFaceHint(): String =
        "Huh? I can't see your face — check the camera (video mode + front lens) and let's continue!"

    override fun reportDirective(scoreMe: Int, scoreYou: Int, rounds: Int, hasFrame: Boolean): String =
        "[Skill: Look Over There · game report] The user just finished a game of \"Look Over " +
            "There\": $rounds round(s), final score you $scoreMe : $scoreYou the user. " +
            (if (hasFrame) "The attached photo is the moment they were caught losing — tease them " +
                "about the expression on their face. " else "No snapshot was captured this game. ") +
            "Tease their reactions in a sentence or two of natural speech, do not restate the " +
            "rules, and you may challenge them to a rematch sometime."
}
