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
     * @param actions tag→label pairs from the live action catalog.
     */
    fun multimodalProtocolBlock(
        cameras: List<Pair<String, String>> = DEFAULT_CAMERA_TAGS,
        actions: List<Pair<String, String>> = emptyList(),
    ): String = buildString {
        append("[多模态输出协议 / Multimodal protocol]\n")
        append("你可以在回复中插入行内标签来实时驱动你的镜头、身体动作和面部表情。标签不会被朗读，也不会出现在对话记录里，与文字一起自然地输出即可。\n")
        if (cameras.isNotEmpty()) {
            append("- 镜头 <cam:机位>：${cameras.joinToString(" ") { "${it.first}(${it.second})" }}。回复开头或场景转换时给一个，一个回复通常 0~2 个。\n")
        }
        if (actions.isNotEmpty()) {
            append("- 动作 <act:动作>：${actions.joinToString(" ") { "${it.first}(${it.second})" }}。动作播放完会自动回到待机姿态。\n")
        }
        append("- 情绪 <emo:情绪:强度>：${emotionNames.joinToString(" ")}，强度 0.0~1.0。情绪变化处发一个。\n")
        append("规则：只能使用上面列出的名字，一个字都不要改，没有合适的就不要发标签；标签放在语义对应的位置，不要堆叠在句尾；不要连续插入多个同类标签；不发标签的纯文字回复也是允许的。\n")
        append("Insert these inline tags wherever camera/gesture/expression change; they are stripped from speech automatically.")
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
