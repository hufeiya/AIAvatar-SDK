package com.neethu.orchestrator.card

import kotlinx.serialization.json.JsonObject

/**
 * Normalized character persona, independent of the source card spec
 * (SillyTavern Character Card V1/V2/V3, PNG or JSON).
 */
data class CharacterCard(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    /** V1 `first_mes`; V2/V3 `data.first_mes`. */
    val firstMessage: String = "",
    val alternateGreetings: List<String> = emptyList(),
    /** V1 `mes_example` (with `<START>` separators), kept raw. */
    val messageExample: String = "",
    /** V2+ `system_prompt` override. */
    val systemPrompt: String = "",
    /** V2+ `post_history_instructions`. */
    val postHistoryInstructions: String = "",
    val creator: String = "",
    val creatorNotes: String = "",
    val characterVersion: String = "",
    val tags: List<String> = emptyList(),
    /** Which spec the card was parsed from: `chara_card_v2`, `chara_card_v3`, `v1`, or `unknown`. */
    val spec: String = "unknown",
    /** Unmodified `data.extensions` (or root `extensions` for V1), for forward compatibility. */
    val extensions: JsonObject? = null,
    /**
     * 卡内嵌世界书（CCv2/v3 `data.character_book`，docs/tavern-companion-
     * feasibility.md P1-1）。null = 卡不带书；注入仍受会话级开关与绑定覆盖
     * （见 AvatarSession.lorebook）。
     */
    val characterBook: Lorebook? = null,
)

// SillyTavern's two core macros. Greetings are spoken aloud, so they must not
// leak into TTS literally. The SDK has no persona concept yet, so `{{user}}`
// resolves to a generic second-person address (tune per app if needed).
private val CHAR_MACRO = Regex("""\{\{\s*char\s*\}\}""", RegexOption.IGNORE_CASE)
private val USER_MACRO = Regex("""\{\{\s*user\s*\}\}""", RegexOption.IGNORE_CASE)

/**
 * `{{char}}` → [charName]、`{{user}}` → [userAlias]（大小写/空白容忍）。
 * 开场白、mes_example、post_history_instructions 三处共用；无卡宏字段原样。
 */
fun expandCardMacros(text: String, charName: String, userAlias: String): String =
    text
        .replace(CHAR_MACRO, charName)
        .replace(USER_MACRO, userAlias)

/**
 * [CharacterCard.firstMessage] prepared for [com.neethu.orchestrator.session.AvatarSession.speak]:
 * `{{char}}` → the card's name, `{{user}}` → [userAlias].
 */
fun CharacterCard.spokenGreeting(userAlias: String = "你"): String =
    expandCardMacros(firstMessage, name, userAlias).trim()
