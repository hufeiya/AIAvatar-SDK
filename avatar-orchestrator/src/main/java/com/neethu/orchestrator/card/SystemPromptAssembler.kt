package com.neethu.orchestrator.card

/**
 * Builds system prompts.
 *
 * [assemble] joins a [CharacterCard]'s persona fields in AIRI's
 * `resolveSystemPrompt` order (airi-card.ts): `[systemPrompt, description,
 * personality, scenario]`. `post_history_instructions` / `mes_example` are
 * deliberately NOT injected here (position-sensitive); expose them to
 * integrators via the card object.
 *
 * The multimodal protocol block ([multimodalProtocolBlock]) is appended
 * separately by AvatarSession.buildRequestMessages at send time — once, with
 * the live tag catalog. assemble() intentionally does NOT include it (the
 * old emotion block was double-appended for card users), so the protocol
 * always reflects the current session configuration.
 */
class SystemPromptAssembler(
    private val emotionNames: List<String> =
        listOf("happy", "sad", "angry", "surprised", "think", "relaxed", "neutral"),
) {

    /** Persona only — protocol blocks live in [multimodalProtocolBlock]. */
    fun assemble(card: CharacterCard?): String {
        val parts = listOfNotNull(
            card?.systemPrompt?.takeIf { it.isNotBlank() },
            card?.description?.takeIf { it.isNotBlank() },
            card?.personality?.takeIf { it.isNotBlank() },
            card?.scenario?.takeIf { it.isNotBlank() },
        )
        return parts.joinToString("\n\n").trim()
    }

    /**
     * Instruction block teaching the model the multimodal inline-tag protocol
     * (docs/ai-layer-handoff.md §7.1). Sections whose usable list is empty
     * are omitted entirely, so a headless session (no controller, no action
     * catalog, camera disabled) never advertises tags it cannot run.
     *
     * @param cameras tag→label pairs of preset camera shots.
     * @param actionGroups category→tags of the live action catalog, listed
     *   grouped so a few hundred gestures stay readable for the model.
     * @param directExpressions the loaded model's own expression names — the
     *   model may drive any of them directly via `<emo:name:weight>` (§7.10).
     */
    fun multimodalProtocolBlock(
        cameras: List<Pair<String, String>> = DEFAULT_CAMERA_TAGS,
        actionGroups: List<Pair<String, List<String>>> = emptyList(),
        directExpressions: List<String> = emptyList(),
    ): String = buildString {
        append("[多模态输出协议 / Multimodal protocol]\n")
        append("你在回复中可以插入行内标签，实时驱动你的镜头、身体动作和面部表情。标签不会被朗读，也不会出现在对话记录里，与文字一起自然地输出即可。\n")
        append("规则（必须遵守）：只能使用下面列出的名字，一个字都不要改，没有合适的就不要发标签；标签放在语义对应的位置，不要堆叠在句尾；不要连续插入多个同类标签；不发标签的纯文字回复也是允许的；\n")
        append("需要做动作或表情时【必须用标签实现】，禁止用（括号）或*星号*在台词里描写动作表情——括号里的内容会被朗读出来。用户让你做具体表情（眨眼/闭眼/皱眉/张嘴等）时，必须从【表情】列表里选名字发标签。\n")
        // 表情统一为一个 <emo> 词表：标准情绪（组合表情）在前，模型原生 morph（直驱）随后。
        // 示例名必须是本模型真实支持的——旧版硬编码 blink_l 在 ARKit 命名的模型上
        // 不存在，模型照抄后被表达式门控静默丢弃（真机踩过）。
        val winkName = directExpressions.firstOrNull {
            it.contains("blinkleft", ignoreCase = true) && !it.contains("eye", ignoreCase = true)
        } ?: directExpressions.firstOrNull { it.startsWith("blink", ignoreCase = true) }
        append("- 表情 <emo:名字:强度>（强度 0.0~1.0，0=恢复）：标准情绪有 ${emotionNames.joinToString(" ")}")
        if (directExpressions.isNotEmpty()) {
            append("；单个细节表情用模型原生名：${directExpressions.joinToString(" ")}")
            if (winkName != null) {
                append("。例如眨一下左眼 = <emo:$winkName:1> 紧接着 <emo:$winkName:0>，闭着眼保持 = 只发 <emo:$winkName:1>")
            }
        }
        append("。情绪变化处发一个。\n")
        if (cameras.isNotEmpty()) {
            append("- 镜头 <cam:机位>：${cameras.joinToString(" ") { "${it.first}(${it.second})" }}。回复开头或场景转换时给一个，一个回复通常 0~2 个。\n")
        }
        // 动作列表量大（几百个），放在最后以免稀释前面的指令
        if (actionGroups.isNotEmpty()) {
            append("- 动作 <act:动作>：动作英文名即其含义（下划线分隔单词），按语义挑选合适的：\n")
            append(actionGroups.joinToString("；\n") { (category, tags) -> "  $category: ${tags.joinToString(" ")}" })
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
        if (gesture != null) append("<act:$gesture>很高兴见到你！")
        append("\n")
        append("记住：动作和表情一律用上面的行内标签实现，绝不用（括号）或*星号*描写。")
    }

    companion object {
        /** Mirrors [com.neethu.corelib.CameraShot]; mapping lives in AvatarSession. */
        val DEFAULT_CAMERA_TAGS = listOf(
            "close_up" to "面部特写",
            "medium_shot" to "中景半身",
            "full_shot" to "全身",
            "long_shot" to "远景",
            "over_shoulder" to "侧景",
        )
    }
}
