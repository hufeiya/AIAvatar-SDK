package com.neethu.orchestrator.card

import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.PromptTexts
import com.neethu.orchestrator.i18n.promptTextsOf

/**
 * Builds system prompts.
 *
 * [assemble] joins a [CharacterCard]'s persona fields in AIRI's
 * `resolveSystemPrompt` order (airi-card.ts): `[systemPrompt, description,
 * personality, scenario]`. `post_history_instructions` / `mes_example` are
 * deliberately NOT injected here (position-sensitive); expose them to
 * integrators via the card object.
 *
 * The multimodal protocol block ([multimodalProtocolBlock]) is injected
 * separately by AvatarSession as its own system message — assembled once per
 * conversation context and reused verbatim across turns (the catalog only
 * depends on the loaded model, not the turn). assemble() intentionally does
 * NOT include it (the old emotion block was double-appended for card users),
 * so the protocol always reflects the live session configuration.
 *
 * 全部提示词文案（协议块各段、情绪词表、镜头目录）都在
 * [com.neethu.orchestrator.i18n.PromptTexts] 里按语言维护；本类只保留拼装
 * 结构与眨眼示例名的探测逻辑。
 */
class SystemPromptAssembler(
    /** 提示词文案目录（语言随之）；默认中文保持既有行为与单测不变。 */
    val texts: PromptTexts = promptTextsOf(Lang.ZH),
    /**
     * 标准情绪词表（随协议块告知 LLM）。必须与
     * [com.neethu.orchestrator.face.EmotionBlender.defs] 的键一一对应——
     * SystemPromptAssemblerTest 锁这个不变量（`think` 曾不在模型预设里、
     * 整条 def 被门控丢弃，词表与 defs 分叉是这类静默失效的根源）。
     * 默认取文案目录的语言化词表（中文带释义括号，英文为裸词）。
     */
    val emotionNames: List<String> = texts.emotionNames,
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
        cameras: List<Pair<String, String>> = texts.cameraTags,
        actionGroups: List<Pair<String, List<String>>> = emptyList(),
        directExpressions: List<String> = emptyList(),
    ): String {
        // 示例名必须是本模型真实支持的——旧版硬编码 blink_l 在 ARKit 命名的模型上
        // 不存在，模型照抄后被表达式门控静默丢弃（真机踩过）。
        val winkName = directExpressions.firstOrNull {
            it.contains("blinkleft", ignoreCase = true) && !it.contains("eye", ignoreCase = true)
        } ?: directExpressions.firstOrNull { it.startsWith("blink", ignoreCase = true) }
        return texts.protocolBlock(
            cameras = cameras,
            actionGroups = actionGroups,
            directExpressions = directExpressions,
            winkName = winkName,
        )
    }

    companion object {
        /**
         * Mirrors [com.neethu.corelib.CameraShot]; mapping lives in AvatarSession.
         * 中文标签的源头在 PromptTextsZh.cameraTags（多语言后这里只是兼容别名）。
         */
        val DEFAULT_CAMERA_TAGS: List<Pair<String, String>> =
            com.neethu.orchestrator.i18n.PromptTextsZh.cameraTags
    }
}
