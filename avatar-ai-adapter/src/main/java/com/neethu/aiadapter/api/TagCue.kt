package com.neethu.aiadapter.api

/**
 * A control instruction extracted from the LLM stream — one variant per tag
 * family of the multimodal inline protocol (docs/ai-layer-handoff.md §7).
 *
 * Cues carry plain strings only: mapping names onto corelib assets
 * (CameraShot presets, VRMA gesture files) belongs to the orchestrator,
 * never to this module (adapter must not depend on corelib).
 *
 * [raw] preserves the original tag segment (e.g. `<act:laugh:0.5>`) so the
 * orchestrator can surface unrecognized names verbatim in events/UI for
 * debugging; hand-built cues may leave it empty.
 */
sealed interface TagCue {

    /** `<emo:name[:intensity]>` (and the legacy `<|emotion:…|>` form). */
    data class Emotion(
        val name: String,
        val intensity: Float = 1.0f,
        val raw: String = "",
    ) : TagCue

    /**
     * `<act:tag[:intensity]>` — a gesture from the session's action catalog.
     * Models habitually append an intensity segment learned from `<emo:>`;
     * it carries no meaning for gestures and is dropped at extraction.
     */
    data class Action(val name: String, val raw: String = "") : TagCue

    /** `<cam:shot[:intensity]>` — a preset camera framing; intensity tolerated and dropped. */
    data class Camera(val name: String, val raw: String = "") : TagCue

    /**
     * `<move:anchor[:intensity]>` — a named walk target (scene anchor, e.g.
     * "sofa") of the free-locomotion protocol (docs/locomotion-feasibility.md
     * §7.3). Independent namespace (never collides with act names); intensity
     * tolerated and dropped like act/cam. Names resolve against the session's
     * anchor table downstream — the adapter stays asset-free.
     */
    data class Move(val name: String, val raw: String = "") : TagCue

    /** Malformed, oversized or unknown-namespace control text; never executable. */
    data class InvalidControl(val raw: String) : TagCue

    /**
     * A native tool call the model emitted as *text* instead of through the
     * tool-call channel — `<move_to sofa>`, `<move_to anchor="desk">`,
     * `<stop_moving>` (P1 真机踩过: DeepSeek mid-prose). Never executed (text
     * is not the tool channel; the proper cure is the provider-side required
     * retry, docs/agent-provider-matrix.md) and never spoken (层 0: tag-shaped
     * segments don't leak into TTS); surfaced downstream as an UnknownTag
     * report so the panel/log shows what the model attempted.
     */
    data class ToolCallText(val raw: String = "") : TagCue
}
