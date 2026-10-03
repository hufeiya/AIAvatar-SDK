package com.neethu.aiadapter.api

/**
 * An emotion instruction extracted from the LLM stream.
 *
 * [name] is one of the canonical emotions (happy, sad, angry, surprised,
 * think, relaxed, neutral); [intensity] is clamped to 0..1.
 */
data class EmotionCue(
    val name: String,
    val intensity: Float = 1.0f,
)

/** Incremental output of [EmotionExtractor.feed]. */
class ExtractionResult(
    /** Delta text with all markers removed — safe to forward to the sentence chunker. */
    val cleanText: String,
    val cues: List<EmotionCue>,
)

/**
 * Stateful streaming filter between the LLM delta stream and the sentence
 * chunker. It pulls emotion markers (e.g. `<|emotion:happy:0.8|>`) out of the
 * text, converts them to [EmotionCue]s, and forwards the remaining text.
 *
 * Partial markers split across deltas are buffered until complete.
 */
interface EmotionExtractor {
    fun feed(delta: String): ExtractionResult
    fun flush(): ExtractionResult
    fun reset()
}
