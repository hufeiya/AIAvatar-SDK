package com.neethu.orchestrator.card

/**
 * Builds the system prompt from a [CharacterCard].
 *
 * Field order follows AIRI's `resolveSystemPrompt` (airi-card.ts):
 * `[systemPrompt, description, personality, scenario]` joined by blank lines.
 * `post_history_instructions` / `mes_example` are deliberately NOT injected
 * here (position-sensitive); expose them to integrators via the card object.
 *
 * An emotion-protocol block is appended so the model knows how to emit
 * `<|emotion:…|>` markers the [com.neethu.aiadapter.api.EmotionExtractor]
 * understands.
 */
class SystemPromptAssembler(
    private val includeEmotionProtocol: Boolean = true,
    private val emotionNames: List<String> =
        listOf("happy", "sad", "angry", "surprised", "think", "relaxed", "neutral"),
) {

    fun assemble(card: CharacterCard?): String = buildString {
        val parts = listOfNotNull(
            card?.systemPrompt?.takeIf { it.isNotBlank() },
            card?.description?.takeIf { it.isNotBlank() },
            card?.personality?.takeIf { it.isNotBlank() },
            card?.scenario?.takeIf { it.isNotBlank() },
        )
        append(parts.joinToString("\n\n"))
        if (includeEmotionProtocol) {
            if (isNotEmpty()) append("\n\n")
            append(emotionProtocolBlock())
        }
    }.trim()

    /** Instruction block teaching the model the emotion marker protocol. */
    fun emotionProtocolBlock(): String = buildString {
        append("[表情指令 / Emotion protocol]\n")
        append("你可以在回复中插入情绪标记来驱动你的面部表情。格式：<|emotion:名称|>，或带强度：<|emotion:名称:0.8|>（强度 0~1）。\n")
        append("可用情绪：${emotionNames.joinToString(", ")}。\n")
        append("标记不会被朗读，也不会出现在对话记录里；每次情绪变化处插入一个即可，不要连续插入多个。\n")
        append("Insert an emotion marker wherever your expression changes; it is stripped from speech automatically.")
    }
}
