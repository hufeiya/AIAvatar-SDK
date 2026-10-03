package com.neethu.aiadapter.api

/**
 * A control instruction extracted from the LLM stream — one variant per tag
 * family of the multimodal inline protocol (docs/ai-layer-handoff.md §7).
 *
 * Cues carry plain strings only: mapping names onto corelib assets
 * (CameraShot presets, VRMA gesture files) belongs to the orchestrator,
 * never to this module (adapter must not depend on corelib).
 */
sealed interface TagCue {

    /** `<emo:name[:intensity]>` (and the legacy `<|emotion:…|>` form). */
    data class Emotion(val name: String, val intensity: Float = 1.0f) : TagCue

    /** `<act:tag>` — a gesture from the session's action catalog. */
    data class Action(val name: String) : TagCue

    /** `<cam:shot>` — a preset camera framing. */
    data class Camera(val name: String) : TagCue
}
