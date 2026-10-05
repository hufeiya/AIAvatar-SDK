package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.EmotionCue
import com.neethu.corelib.CameraShot

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

    /** An emotion tag was extracted from the LLM stream. */
    data class EmotionChanged(val cue: EmotionCue) : AvatarEvent

    /** An `<act:…>` tag started a gesture from the action catalog. */
    data class ActionStarted(val tag: String, val label: String) : AvatarEvent

    /** A `<cam:…>` tag switched the preset camera framing. */
    data class CameraChanged(val shot: CameraShot) : AvatarEvent

    /** The current turn finished to the end (LLM + TTS + playback all drained). */
    data class TurnCompleted(val interrupted: Boolean) : AvatarEvent

    /** The turn aborted with an error (LLM/TTS/decode failure). */
    data class TurnFailed(val error: Throwable) : AvatarEvent

    /** Active playback was cut short by [AvatarSession.interrupt]. */
    data object PlaybackInterrupted : AvatarEvent

    /** 技能进度事件（激活/出拳/判定/退场…），skill/ 框架经 SkillContext.event 发出。 */
    data class SkillEvent(val skillId: String, val detail: String) : AvatarEvent
}
