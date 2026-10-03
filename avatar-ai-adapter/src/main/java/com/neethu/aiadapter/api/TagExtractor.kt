package com.neethu.aiadapter.api

/**
 * An emotion instruction — the canonical payload of [TagCue.Emotion].
 * [name] is one of the canonical emotions (happy, sad, angry, surprised,
 * think, relaxed, neutral); [intensity] is clamped to 0..1.
 */
data class EmotionCue(
    val name: String,
    val intensity: Float = 1.0f,
)

/** Incremental output of [TagExtractor.feed]. */
class ExtractionResult(
    /** Delta text with all tags removed — safe to forward to the sentence chunker. */
    val cleanText: String,
    val cues: List<TagCue>,
)

/**
 * Stateful streaming filter between the LLM delta stream and the sentence
 * chunker. It pulls the multimodal inline tags (`<emo:…>`, `<act:…>`,
 * `<cam:…>`, plus the legacy `<|emotion:…|>`) out of the text, converts them
 * to [TagCue]s, and forwards the remaining text.
 *
 * Partial tags split across deltas are buffered until complete.
 */
interface TagExtractor {
    fun feed(delta: String): ExtractionResult
    fun flush(): ExtractionResult
    fun reset()
}
