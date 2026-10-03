package com.neethu.orchestrator.gesture

/**
 * One entry of the LLM action catalog: the tag the model writes in
 * `<act:tag>`, a human-readable label (for events/UI), and exactly one
 * playable source. See docs/ai-layer-handoff.md §7.2 — the catalog is
 * injected into the system prompt, so the model only ever sees names that
 * actually exist.
 */
data class ActionEntry(
    val tag: String,
    val label: String,
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
