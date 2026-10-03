package com.neethu.orchestrator.gesture

/**
 * One entry of the LLM action catalog: the tag the model writes in
 * `<act:tag>`, a human-readable label (for events/UI), the semantic category
 * (used to group the protocol block), and exactly one playable source.
 * See docs/ai-layer-handoff.md §7.2 — the catalog is injected into the
 * system prompt, so the model only ever sees names that actually exist.
 */
data class ActionEntry(
    val tag: String,
    val label: String,
    /** Semantic group shown in the protocol block, e.g. "04_交流手势". */
    val category: String = "基础动作",
    /** Path inside APK assets, e.g. `"animations/Greeting While Standing.vrma"`. */
    val assetPath: String? = null,
    /** Absolute file path (external animation library). */
    val filePath: String? = null,
) {
    init {
        require(assetPath != null || filePath != null) {
            "ActionEntry '$tag' needs an assetPath or a filePath"
        }
    }
}
