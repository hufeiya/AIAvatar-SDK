package com.neethu.orchestrator.face

/**
 * 情绪别名 → canonical 情绪名（[EmotionBlender.defs] 键集），中英两表。
 *
 * 协议词表是「英文名(中文释义)」形态（PromptTexts.emotionNames），规则虽然写明
 * 「一个字都不要改」，模型偶尔仍会自造词表外的名字：中文按中文语感仿写（真机
 * 踩过 `<emo:轻笑>`/`<emo:大笑>`——词表里没有"笑"类情绪），英文则写高频情绪
 * 词（`<emo:laugh>`，甚至误挂进动作槽 `<act:laugh:0.5>`）。提取器按 Unicode
 * 字母提取一切 `<emo:xx>` 形状的标签后，这类名字走不到 canonical 也匹配不到
 * morph，在 dispatchCues 被丢弃。先过这两张表让意图生效：轻笑/laugh→happy 等。
 *
 * 消费顺序（dispatchCues）：canonical 组合优先 → 模型原生 morph 直驱次之 →
 * 别名表最后只补空缺，所以两表都不会遮蔽模型真实支持的名字。别名落空的名字
 * 原样返回（lowercase 归一，与旧行为一致）。
 */
object EmotionAliases {

    val zhToCanonical: Map<String, String> = mapOf(
        // happy：笑类全部归 happy，轻重由强度表达
        "笑" to "happy", "微笑" to "happy", "轻笑" to "happy", "浅笑" to "happy",
        "偷笑" to "happy", "憨笑" to "happy", "欢笑" to "happy", "大笑" to "happy",
        "开心" to "happy", "高兴" to "happy", "愉快" to "happy", "喜悦" to "happy",
        // sad
        "难过" to "sad", "伤心" to "sad", "悲伤" to "sad", "委屈" to "sad",
        "沮丧" to "sad", "忧郁" to "sad", "哭" to "sad",
        // angry
        "生气" to "angry", "愤怒" to "angry", "恼火" to "angry", "不满" to "angry",
        // surprised
        "惊讶" to "surprised", "吃惊" to "surprised", "震惊" to "surprised",
        "惊喜" to "surprised",
        // think
        "思考" to "think", "沉思" to "think", "琢磨" to "think",
        // relaxed
        "放松" to "relaxed", "惬意" to "relaxed", "悠闲" to "relaxed", "淡定" to "relaxed",
        // smug：坏笑是单侧上翘的坏，不是开心
        "得意" to "smug", "骄傲" to "smug", "坏笑" to "smug",
        // shy
        "害羞" to "shy", "羞涩" to "shy", "脸红" to "shy", "不好意思" to "shy",
        // worried
        "担忧" to "worried", "担心" to "worried", "忧虑" to "worried",
        "不安" to "worried", "焦虑" to "worried",
        // confused
        "困惑" to "confused", "疑惑" to "confused", "迷惑" to "confused",
        "纳闷" to "confused", "不解" to "confused",
        // sleepy
        "困倦" to "sleepy", "犯困" to "sleepy", "瞌睡" to "sleepy", "困" to "sleepy",
        // determined
        "坚定" to "determined", "认真" to "determined", "决心" to "determined",
        // neutral：模型用 <emo:恢复> 之类表达"收表情"
        "平静" to "neutral", "冷静" to "neutral", "淡然" to "neutral",
        "面无表情" to "neutral", "无表情" to "neutral", "恢复" to "neutral",
    )

    /** 中文别名→canonical；其余名字原样返回（lowercase 归一）。 */
    fun resolve(name: String): String =
        zhToCanonical[name.lowercase()] ?: name.lowercase()

    /**
     * 英文自造词 → canonical。模型会把词表外的英文情绪词塞进标签，还常把
     * 情绪词误挂进动作槽（真机踩过 `<act:laugh:0.5>`——"laugh"既不在动作
     * 目录、也不在 13 情绪词表里）。键一律小写（提取器已统一 lowercase）；
     * 消费方 [com.neethu.orchestrator.session.AvatarSession.dispatchCues]
     * 在 canonical/morph 解析落空后才查这张表——模型原生 morph 名（如
     * smile）优先，表不遮蔽。joy/fun/sorrow 与 corelib VrmExpressionManager
     * 的 VRM 0.x legacy 别名对齐。
     */
    val enToCanonical: Map<String, String> = buildMap {
        fun group(names: List<String>, canonical: String) = names.forEach { put(it, canonical) }
        // happy：笑类/愉悦类全部归 happy，轻重由强度表达
        group(listOf("laugh", "laughing", "laughter", "giggle", "giggling", "chuckle",
            "chuckling", "smile", "smiling", "grin", "grinning", "joy", "joyful",
            "cheerful", "delighted", "amused", "excited", "glad"), "happy")
        group(listOf("cry", "crying", "sob", "sobbing", "tears", "sorrow", "sadness",
            "unhappy", "disappointed", "disappointment", "gloomy", "heartbroken",
            "depressed"), "sad")
        group(listOf("mad", "anger", "rage", "furious", "annoyed", "irritated"), "angry")
        group(listOf("surprise", "shocked", "astonished", "astounded", "startled",
            "stunned"), "surprised")
        group(listOf("thinking", "pensive", "pondering"), "think")
        group(listOf("calm", "serene", "relieved", "content", "fun"), "relaxed")
        group(listOf("smirk", "smirking", "proud", "triumphant"), "smug")
        group(listOf("embarrassed", "blush", "blushing", "timid"), "shy")
        group(listOf("anxious", "nervous", "scared", "afraid", "fear", "uneasy",
            "apprehensive"), "worried")
        group(listOf("puzzled", "perplexed", "baffled", "bewildered"), "confused")
        group(listOf("tired", "exhausted", "drowsy", "yawn", "yawning"), "sleepy")
        group(listOf("resolute", "determination"), "determined")
    }

    /**
     * 任意语言别名→canonical；不在表中的名字返回 null。供英文兜底与跨类
     * 改写（动作槽里的情绪词）用——与 [resolve] 的「落空原样返回」语义区分
     * 开，消费方需要区分「映射到了 canonical」和「没映射」。
     */
    fun resolveEmotion(name: String): String? =
        zhToCanonical[name.lowercase()] ?: enToCanonical[name.lowercase()]
}
