package com.neethu.orchestrator.i18n

import com.neethu.corelib.Lang

/**
 * 全部 LLM 提示词文案的中英双语目录（多语言支持，2026-10）。
 *
 * **维护约定（重要）**：提示词会被频繁修改——所有提示词文案只允许出现在
 * 本文件（及同目录 [RpsTexts]）里，每个成员中英两版**按相同顺序对齐**，
 * 改一处提示词必须同步改另一语言；新增成员时两个 object 都要补齐。
 * `PromptTextsI18nTest` 逐成员断言 EN 版无中文字符，防漂移。
 *
 * 设计约束：orchestrator 是纯 JVM 可测模块，不读 Android 资源——语言由
 * 集成方解析后经 [AvatarSession.Options.lang]（app 侧见 AiChatController）
 * 显式传入，默认 [Lang.ZH] 保持既有行为与既有单测不变。
 */
interface PromptTexts {
    val lang: Lang

    // ── 身份前言（每轮 system 恒带段，[AvatarSession.Options.identityPreamble] 缺省值）──
    val identityPreamble: String

    // ── 协议遵循提醒（protocolInstructions=true 时拼在前言末尾；词表只随首轮发送）──
    val protocolReminder: String

    // ── 视角行（挂末尾 user 消息前缀，逐轮注入）──
    /** 预设机位（[shotLabel] 已按语言取好显示名，[shotName] 是枚举名）。 */
    fun viewLine(shotLabel: String, shotName: String): String

    /** 自由视角（无预设机位、用户手动控制镜头）。 */
    val viewLineFree: String

    // ── 协议块（多模态标签目录，上下文首轮随 system 发送）──
    /** 标准情绪词表（与 EmotionBlender.defs 的键对应，括号内是语言化释义）。 */
    val emotionNames: List<String>

    /** 镜头 tag → 显示名对（协议块用）。 */
    val cameraTags: List<Pair<String, String>>

    /** 动作分类名语言化（assets 分类文件夹名 → 协议块展示名；未知名原样返回）。 */
    fun actionCategory(raw: String): String

    /** 开场白 `{{user}}` 宏的口语替身（spokenGreeting 用）。 */
    val userAlias: String

    /**
     * 多模态协议块全文。段结构（表情/镜头/动作/few-shot 示例）与中文版逐段
     * 对齐；空段（无镜头/无动作）的省略规则在两个实现里必须一致。
     *
     * @param cameras tag→显示名（已本地化）。
     * @param actionGroups 分类（原始名即可，块内部经 [actionCategory] 语言化）→ tag 列表。
     * @param directExpressions 当前模型原生表情名（直驱 `<emo:>` 词表）。
     * @param winkName 眨眼示例用的原生表情名（null = 省略眨眼示例）。
     */
    fun protocolBlock(
        cameras: List<Pair<String, String>>,
        actionGroups: List<Pair<String, List<String>>>,
        directExpressions: List<String>,
        winkName: String?,
    ): String

    companion object {
        /** 语言 → 提示词目录。 */
        fun of(lang: Lang): PromptTexts = if (lang == Lang.EN) PromptTextsEn else PromptTextsZh
    }
}

/** 语言 → 提示词目录（顶层便捷入口）。 */
fun promptTextsOf(lang: Lang): PromptTexts = PromptTexts.of(lang)

/** 语言 → 猜拳技能文案目录（顶层便捷入口）。 */
fun rpsTextsOf(lang: Lang): RpsTexts = RpsTexts.of(lang)

// ─────────────────────────────────────────────────────────────────────────────
// 简体中文（默认；文本自 AvatarSession/SystemPromptAssembler 迁入，逐字未动）
// ─────────────────────────────────────────────────────────────────────────────

object PromptTextsZh : PromptTexts {
    override val lang: Lang = Lang.ZH

    override val identityPreamble: String =
        "【身份与任务】你是一个 3D 虚拟人,正在与用户实时交流:你说出的每句话都会被语音合成" +
            "朗读出来,用户看得到你虚拟形象的表情与动作;你的任务就是演好当前这个角色," +
            "自然地陪用户聊天互动。\n" +
            "【台词纪律】只说角色开口要说的话,像面对面聊天一样简短自然;不要念出小说式旁白、" +
            "心理活动或场景描写,也不要用(括号)或*星号*描写动作神态——情绪与动作请用行内标签" +
            "表达,让虚拟形象替你演出来。"

    override val protocolReminder: String =
        "【协议遵循】完整的多模态标签词表(<emo:/<act:/<cam:>)只在对话开头的系统消息里提供过" +
            "一次,后续请求不再重复;请继续遵循该协议:标签放在语义对应的位置," +
            "只使用你历史回复中出现过的标签名,记不准就不要发标签。"

    override fun viewLine(shotLabel: String, shotName: String): String =
        "【当前镜头视角】$shotLabel（$shotName），用户正以这个机位看着你。"

    override val viewLineFree: String =
        "【当前镜头视角】自由视角（FREE），用户正手动控制镜头。"

    override val emotionNames: List<String> = listOf(
        "happy(开心)", "sad(难过)", "angry(生气)", "surprised(惊讶)",
        "think(思考)", "relaxed(放松)", "smug(得意)", "shy(害羞)",
        "worried(担忧)", "confused(困惑)", "sleepy(困倦)", "determined(坚定)",
        "neutral(平静)",
    )

    // 协议块镜头段文案的源头（SystemPromptAssembler.DEFAULT_CAMERA_TAGS 引用这里）
    override val cameraTags: List<Pair<String, String>> = listOf(
        "close_up" to "面部特写",
        "macro" to "面部微距（比特写更近，面部充满画面）",
        "medium_shot" to "中景半身",
        "full_shot" to "全身",
        "long_shot" to "远景",
        "over_shoulder" to "侧景",
    )

    override fun actionCategory(raw: String): String = raw

    override val userAlias: String = "你"

    override fun protocolBlock(
        cameras: List<Pair<String, String>>,
        actionGroups: List<Pair<String, List<String>>>,
        directExpressions: List<String>,
        winkName: String?,
    ): String = buildString {
        append("[多模态输出协议 / Multimodal protocol]\n")
        append("你在回复中可以插入行内标签，实时驱动你的镜头、身体动作和面部表情。标签不会被朗读，也不会出现在对话记录里，与文字一起自然地输出即可。\n")
        append("规则（必须遵守）：只能使用下面列出的名字，一个字都不要改，没有合适的就不要发标签；标签放在语义对应的位置，不要堆叠在句尾；不要连续插入多个同类标签；不发标签的纯文字回复也是允许的；\n")
        append("需要做动作或表情时【必须用标签实现】，禁止用（括号）或*星号*在台词里描写动作表情——括号里的内容会被朗读出来。用户让你做具体表情（眨眼/闭眼/皱眉/张嘴等）时，必须从【表情】列表里选名字发标签。\n")
        // 表情统一为一个 <emo> 词表：标准情绪（组合表情）在前，模型原生 morph（直驱）随后。
        // 示例名必须是本模型真实支持的——旧版硬编码 blink_l 在 ARKit 命名的模型上
        // 不存在，模型照抄后被表达式门控静默丢弃（真机踩过）。
        append("- 表情 <emo:名字:强度>（强度 0.0~1.0，0=恢复）：标准情绪有 ${emotionNames.joinToString(" ")}")
        if (directExpressions.isNotEmpty()) {
            append("；单个细节表情用模型原生名：${directExpressions.joinToString(" ")}")
            if (winkName != null) {
                append("。例如眨一下左眼 = <emo:$winkName:1> 紧接着 <emo:$winkName:0>，闭着眼保持 = 只发 <emo:$winkName:1>")
            }
        }
        append(
            "。情绪标签放在它修饰的那句话的句首，说话时大约每 1~2 句换一个情绪（表情跟着内容走），同一句话不要堆多个情绪标签。\n",
        )
        if (cameras.isNotEmpty()) {
            append("- 镜头 <cam:机位>：${cameras.joinToString(" ") { "${it.first}(${it.second})" }}。回复开头或场景转换时给一个，一个回复通常 0~2 个。\n")
        }
        // 动作列表量大（几百个），放在最后以免稀释前面的指令
        if (actionGroups.isNotEmpty()) {
            append("- 动作 <act:动作>：动作英文名即其含义（下划线分隔单词），按语义挑选合适的：\n")
            append(actionGroups.joinToString("；\n") { (category, tags) -> "  ${actionCategory(category)}: ${tags.joinToString(" ")}" })
            append("。\n")
        }
        // few-shot 示例放在最末尾——长提示词的结尾权重最高（真机实测有效）
        val gesture = actionGroups.firstOrNull()?.second?.firstOrNull()
        append("【输出示例】\n")
        append("用户：你能眨一下左眼，然后开心地跟我打个招呼吗？\n")
        append("你：")
        if (cameras.isNotEmpty()) append("<cam:medium_shot>")
        append("<emo:happy:0.8>当然可以！")
        if (winkName != null) append("<emo:$winkName:1><emo:$winkName:0>看到我眨眼了吗？")
        append("<emo:surprised:0.7>哇，你居然真的看到了！")
        if (gesture != null) append("<act:$gesture>很高兴见到你！")
        append("\n")
        append("记住：动作和表情一律用上面的行内标签实现，绝不用（括号）或*星号*描写。")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// English（海外用户；与中文版逐段对齐，规则一条不减）
// ─────────────────────────────────────────────────────────────────────────────

object PromptTextsEn : PromptTexts {
    override val lang: Lang = Lang.EN

    override val identityPreamble: String =
        "[Identity & task] You are a 3D virtual avatar talking with the user in real time: " +
            "every sentence you say is read aloud by speech synthesis, and the user can see your " +
            "avatar's facial expressions and body movements. Your task is to play the current " +
            "character and keep the user company naturally.\n" +
            "[Line discipline] Say only what the character would say out loud — keep it short and " +
            "natural, like a face-to-face chat. Never narrate like a novel, never voice inner " +
            "thoughts or scene descriptions, and never use (parentheses) or *asterisks* to describe " +
            "actions or expressions — convey emotions and actions with inline tags and let the " +
            "avatar act them out."

    override val protocolReminder: String =
        "[Protocol adherence] The full multimodal tag vocabulary (<emo:/<act:/<cam:>) was provided " +
            "once in the system message at the very start of the conversation and is not repeated " +
            "afterwards. Keep following that protocol: place tags where they semantically belong, " +
            "use only tag names that already appeared in your previous replies, and if you are not " +
            "sure of a name, do not emit a tag."

    override fun viewLine(shotLabel: String, shotName: String): String =
        "[Current camera view] $shotLabel ($shotName) — the user is looking at you through this framing."

    override val viewLineFree: String =
        "[Current camera view] Free view (FREE) — the user is controlling the camera manually."

    override val emotionNames: List<String> = listOf(
        "happy", "sad", "angry", "surprised",
        "think", "relaxed", "smug", "shy",
        "worried", "confused", "sleepy", "determined",
        "neutral",
    )

    override val cameraTags: List<Pair<String, String>> = listOf(
        "close_up" to "facial close-up",
        "macro" to "face macro (tighter than close-up, face fills the frame)",
        "medium_shot" to "medium shot (waist-up)",
        "full_shot" to "full shot (head to feet)",
        "long_shot" to "long shot",
        "over_shoulder" to "over-the-shoulder",
    )

    override fun actionCategory(raw: String): String = when (raw) {
        "03_打招呼与礼仪" -> "Greetings & Etiquette"
        "04_交流手势" -> "Conversational Gestures"
        "05_情绪表达" -> "Emotional Expression"
        "01_待机与站姿" -> "Idle & Stance"
        "07_日常互动" -> "Daily Interactions"
        "09_状态过渡" -> "Transitions"
        "10_跳跃" -> "Jumping"
        "08_舞蹈与表演" -> "Dance & Performance"
        "06_坐姿" -> "Sitting"
        "基础动作" -> "Basic Actions"
        "外置库" -> "External Library"
        "待机" -> "Idle"
        else -> raw
    }

    override val userAlias: String = "you"

    override fun protocolBlock(
        cameras: List<Pair<String, String>>,
        actionGroups: List<Pair<String, List<String>>>,
        directExpressions: List<String>,
        winkName: String?,
    ): String = buildString {
        append("[Multimodal output protocol]\n")
        append("You may insert inline tags in your replies to drive your camera, body movements and facial expressions in real time. Tags are never read aloud and never appear in the chat transcript — just write them naturally together with the text.\n")
        append("Rules (must follow): use only the exact names listed below without changing a single character; if none fits, do not emit a tag; place tags where they semantically belong, never stacked at the end of a reply; do not insert several tags of the same kind in a row; plain-text replies without tags are also allowed;\n")
        append("Actions and expressions MUST be done with tags. Never describe actions or expressions in (parentheses) or *asterisks* — parenthesized content will be read aloud. When the user asks for a specific expression (blink / close eyes / frown / open mouth, etc.), you must pick a name from the [Expressions] list and emit the tag.\n")
        append("- Expressions <emo:name:intensity> (intensity 0.0~1.0, 0 = reset). Standard emotions: ${emotionNames.joinToString(" ")}")
        if (directExpressions.isNotEmpty()) {
            append("; for fine-grained expressions use the model's native names: ${directExpressions.joinToString(" ")}")
            if (winkName != null) {
                append(". For example, a left-eye wink = <emo:$winkName:1> immediately followed by <emo:$winkName:0>; keeping it closed = just <emo:$winkName:1>")
            }
        }
        append(
            ". Put an emotion tag at the start of the sentence it colors; switch emotions roughly every 1~2 sentences (expressions follow the content) and never pile multiple emotion tags onto one sentence.\n",
        )
        if (cameras.isNotEmpty()) {
            append("- Camera <cam:shot>: ${cameras.joinToString(" ") { "${it.first}(${it.second})" }}. Give one at the start of a reply or on scene changes; usually 0~2 per reply.\n")
        }
        if (actionGroups.isNotEmpty()) {
            append("- Actions <act:action>: the English name is the meaning (words separated by underscores); pick suitable ones by semantics:\n")
            // 分类名本地化在协议块内部做：调用方可以只给 assets 原分类（中文文件夹名）
            append(actionGroups.joinToString(";\n") { (category, tags) -> "  ${actionCategory(category)}: ${tags.joinToString(" ")}" })
            append(".\n")
        }
        val gesture = actionGroups.firstOrNull()?.second?.firstOrNull()
        append("[Output example]\n")
        append("User: Can you wink your left eye, then greet me happily?\n")
        append("You: ")
        if (cameras.isNotEmpty()) append("<cam:medium_shot>")
        append("<emo:happy:0.8>Of course!")
        if (winkName != null) append("<emo:$winkName:1><emo:$winkName:0>Did you see me wink?")
        append("<emo:surprised:0.7>Wow, you actually saw it!")
        if (gesture != null) append("<act:$gesture>Nice to meet you!")
        append("\n")
        append("Remember: actions and expressions are always done with the inline tags above — never (parentheses) or *asterisks*.")
    }
}
