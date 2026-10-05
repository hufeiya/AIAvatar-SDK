package com.neethu.orchestrator.i18n

import com.neethu.corelib.Lang

/**
 * 猜拳技能（RpsSkill）的双语文案目录：发给 LLM 的指令行（turnDirective/judge
 * 指令）与直通 TTS 的即时宣判词。维护约定同 [PromptTexts]——中英两版按相同
 * 顺序对齐，改一处同步两语；`PromptTextsI18nTest` 断言 EN 版无中文字符。
 */
interface RpsTexts {
    val lang: Lang

    /** 发给 LLM 的「你(虚拟人)已出某手」固定声明（裁判/气氛组指令共用）。 */
    fun announced(myHand: String): String

    /** INVITED：激活回合的指令行（答应邀请 + 讲规则 + 邀请出拳）。 */
    fun invitedDirective(): String

    /** ARMED：出拳间隙普通对话的指令行。 */
    fun armedDirective(round: Int): String

    /** JUDGING·P2 本地判定局（气氛组）：结果已宣判，LLM 只做临场反应。 */
    fun judgedDirective(round: Int, userHand: String, verdict: String, announced: String): String

    /** JUDGING·带抓拍帧的 VLM 裁判局：看图判三选一并宣布胜负。 */
    fun frameJudgeDirective(round: Int, myHand: String, announced: String): String

    /** JUDGING·无帧（相机没开/不在视频模式）：坦白没看到 + 提醒进视频模式。 */
    fun noFrameJudgeDirective(round: Int, myHand: String, announced: String): String

    /** 即时宣判词前半：两手各出了什么。 */
    fun handsLine(userHand: String, myHand: String): String

    /**
     * 即时宣判词后半：胜负结论（两个变体按局数奇偶轮换防复读机）；
     * [tail] 自带前导标点（中文 "——"/","），与 [handsLine] 直接拼接。
     */
    fun verdictTail(verdict: RpsVerdict, oddRound: Boolean): String

    /** 裁判回合的占位语音文本（用户出拳时喊的口令没被识别出来）。 */
    val throwPlaceholder: String

    companion object {
        /** 语言 → 猜拳文案目录。 */
        fun of(lang: Lang): RpsTexts = if (lang == Lang.EN) RpsTextsEn else RpsTextsZh
    }
}

/** 即时宣判的胜负结论（用户视角；P2 本地判定用）。 */
enum class RpsVerdict { USER_WIN, AVATAR_WIN, DRAW }

// ─────────────────────────────────────────────────────────────────────────────
// 简体中文（默认；文本自 RpsSkill 迁入，逐字未动）
// ─────────────────────────────────────────────────────────────────────────────

object RpsTextsZh : RpsTexts {
    override val lang: Lang = Lang.ZH

    override fun announced(myHand: String): String =
        "你(虚拟人)出的是「$myHand」,已经当着用户的面做出来了,不要改口。"

    override fun invitedDirective(): String =
        "【技能:猜拳】用户刚提议玩石头剪刀布。请爽快答应,用一两句话说明玩法与胜负规则:" +
            "两人同时喊「三、二、一」一起出拳(把手举到镜头前,摄像头会拍下来);" +
            "规则是剪刀赢布、布赢石头、石头赢剪刀,出一样的算平局。然后邀请用户出拳。"

    override fun armedDirective(round: Int): String =
        "【技能:猜拳·进行中】你们正在玩猜拳,已完成 $round 局(胜负规则:剪刀赢布、布赢石头、" +
            "石头赢剪刀,出一样算平局)。用户会喊「三二一」出拳,或直接把手势亮给摄像头;" +
            "每局的结果会由系统判定后告诉你,在那之前不要替任何一局宣布结果。" +
            "用户这句话是出拳间隙的普通对话,正常回应,可以顺带催促出拳。"

    override fun judgedDirective(round: Int, userHand: String, verdict: String, announced: String): String =
        "【技能:猜拳·第${round}局已判】本地摄像头识别:用户出的是「$userHand」,$announced" +
            "这一局$verdict,结果你已经当着用户的面宣布过了,不要重新判定、不要改口。" +
            "用户这句话是出拳前后喊的,请自然回应,顺势对这一局做点临场反应" +
            "(赢了别太得意,输了可以不服气),然后邀请用户出下一局。"

    override fun frameJudgeDirective(round: Int, myHand: String, announced: String): String =
        "【技能:猜拳·第${round}局判定】用户刚刚喊完三二一并出拳。$announced" +
            "请看随本轮附上的抓拍画面,判断用户出的是石头、剪刀还是布(胜负规则:剪刀赢布、" +
            "布赢石头、石头赢剪刀,出一样算平局),宣布这一轮胜负并自然地反应" +
            "(赢了别太得意,输了可以不服气或约再一局)。如果画面里看不清手或没有手,就直说没看清," +
            "邀请用户把手举到镜头前再出一局。"

    override fun noFrameJudgeDirective(round: Int, myHand: String, announced: String): String =
        "【技能:猜拳·第${round}局判定】用户刚刚喊完三二一并出拳。$announced" +
            "但这一轮系统没能抓拍到用户画面(相机没开或不在视频模式),请告诉用户你没看到," +
            "提醒进入视频模式(相机开着)再玩,把这一局自然带过。"

    override fun handsLine(userHand: String, myHand: String): String =
        "你出$userHand,我出$myHand"

    override fun verdictTail(verdict: RpsVerdict, oddRound: Boolean): String = when (verdict) {
        RpsVerdict.USER_WIN -> if (oddRound) "——这局你赢了!" else ",你赢了,再来!"
        RpsVerdict.AVATAR_WIN -> if (oddRound) "——这局我赢咯!" else ",我赢啦,再比一局!"
        RpsVerdict.DRAW -> if (oddRound) ",平局!再来一局!" else "——打平了,再来!"
    }

    override val throwPlaceholder: String = "（出拳）"
}

// ─────────────────────────────────────────────────────────────────────────────
// English
// ─────────────────────────────────────────────────────────────────────────────

object RpsTextsEn : RpsTexts {
    override val lang: Lang = Lang.EN

    override fun announced(myHand: String): String =
        "You (the avatar) have already thrown \"$myHand\" in front of the user — do not change your throw."

    override fun invitedDirective(): String =
        "[Skill: Rock-Paper-Scissors] The user just proposed a round of rock-paper-scissors. " +
            "Enthusiastically agree and explain the rules in a sentence or two: both players chant " +
            "\"rock, paper, scissors\" (or \"three, two, one\") and throw a hand at the same time " +
            "(raise your hand to the camera — it will be captured); scissors beat paper, paper beats " +
            "rock, rock beats scissors, and identical throws are a draw. Then invite the user to throw."

    override fun armedDirective(round: Int): String =
        "[Skill: Rock-Paper-Scissors · in progress] You are mid-game: $round round(s) played " +
            "(rules: scissors beat paper, paper beats rock, rock beats scissors, identical throws " +
            "are a draw). The user will chant and throw, or hold their hand up to the camera; the " +
            "result of each round is judged by the system and told to you — never announce a result " +
            "yourself before that. This utterance is just small talk between throws: respond " +
            "naturally, and you may nudge the user to throw the next hand."

    override fun judgedDirective(round: Int, userHand: String, verdict: String, announced: String): String =
        "[Skill: Rock-Paper-Scissors · round $round already judged] The local camera recognized " +
            "the user's throw as \"$userHand\". $announced This round was $verdict, and you have " +
            "already announced the result to the user — do not re-judge it or change your call. " +
            "This utterance was shouted around the throw: respond naturally, react to the round " +
            "(don't gloat too hard when you win, and you may refuse to accept defeat), then invite " +
            "the next throw."

    override fun frameJudgeDirective(round: Int, myHand: String, announced: String): String =
        "[Skill: Rock-Paper-Scissors · round $round judging] The user just finished chanting and " +
            "threw. $announced Look at the snapshot attached to this turn, decide whether the user " +
            "threw rock, scissors or paper (rules: scissors beat paper, paper beats rock, rock beats " +
            "scissors, identical throws are a draw), announce the winner and react naturally (don't " +
            "gloat too hard when you win; when you lose you may refuse to accept defeat or ask for a " +
            "rematch). If the hand is unclear or missing from the frame, say so honestly and invite " +
            "the user to hold their hand up to the camera and throw again."

    override fun noFrameJudgeDirective(round: Int, myHand: String, announced: String): String =
        "[Skill: Rock-Paper-Scissors · round $round judging] The user just finished chanting and " +
            "threw. $announced But the system failed to capture the user's frame this round (camera " +
            "off or not in video mode): tell the user you didn't see anything, remind them to enter " +
            "video mode (camera on) to play, and smoothly move past this round."

    override fun handsLine(userHand: String, myHand: String): String =
        "You threw $userHand, I threw $myHand"

    override fun verdictTail(verdict: RpsVerdict, oddRound: Boolean): String = when (verdict) {
        RpsVerdict.USER_WIN -> if (oddRound) " — you win this round!" else " — you win, again!"
        RpsVerdict.AVATAR_WIN -> if (oddRound) " — I win this one!" else " — I win, let's go again!"
        RpsVerdict.DRAW -> if (oddRound) " — a draw! One more!" else " — it's a tie, again!"
    }

    override val throwPlaceholder: String = "(a throw)"
}
