package com.neethu.orchestrator.face

/**
 * 中文情绪别名 → canonical 情绪名（[EmotionBlender.defs] 键集）。
 *
 * 协议词表是「英文名(中文释义)」形态（PromptTexts.emotionNames），规则虽然写明
 * 「一个字都不要改」，中文模型偶尔仍会按中文语感自造仿写（真机踩过
 * `<emo:轻笑>`/`<emo:大笑>`——词表里没有"笑"类情绪）。提取器按 Unicode 字母
 * 提取一切 `<emo:xx>` 形状的标签后，这类名字走不到 canonical 也匹配不到
 * morph，在 dispatchCues 被静默丢弃。先过这张表让意图生效：轻笑→happy 等。
 *
 * 键全部是中文，canonical 名与模型 morph 名都是 ASCII，先查别名不会遮蔽任何
 * 已有解析路径；别名落空的名字原样返回（lowercase 归一，与旧行为一致）。
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
}
