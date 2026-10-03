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
)
