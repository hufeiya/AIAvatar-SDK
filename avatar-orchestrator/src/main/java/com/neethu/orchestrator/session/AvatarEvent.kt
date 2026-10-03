package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.EmotionCue

/** Observable happenings during a conversation turn. */
sealed interface AvatarEvent {
    /** A sentence has been chunked out and submitted for synthesis. */
    data class SentenceQueued(val sequence: Int, val text: String) : AvatarEvent

    /** Playback of a sentence started (also when it begins prefetched playback). */
    data class SentenceStarted(val sequence: Int, val text: String) : AvatarEvent

    /** Playback of a sentence finished naturally. */
    data class SentenceEnded(val sequence: Int, val text: String) : AvatarEvent

    /** A sentence failed to synthesize/decode; the turn continues without it. */
    data class SentenceFailed(val sequence: Int, val text: String, val message: String) : AvatarEvent

    /** An emotion marker was extracted from the LLM stream. */
    data class EmotionChanged(val cue: EmotionCue) : AvatarEvent

    /** The current turn finished to the end (LLM + TTS + playback all drained). */
    data class TurnCompleted(val interrupted: Boolean) : AvatarEvent

    /** The turn aborted with an error (LLM/TTS/decode failure). */
    data class TurnFailed(val error: Throwable) : AvatarEvent

    /** Active playback was cut short by [AvatarSession.interrupt]. */
    data object PlaybackInterrupted : AvatarEvent
}
