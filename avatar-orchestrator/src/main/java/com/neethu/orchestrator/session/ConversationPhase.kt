package com.neethu.orchestrator.session

/**
 * High-level conversation phase of an [AvatarSession].
 *
 * Distinct from `corelib.AvatarState` (which models the render pipeline):
 * this is the dialog state machine driving UI hints and the face driver.
 *
 * Transitions:
 * ```
 * IDLE ──send()──▶ THINKING ──first audio starts──▶ SPEAKING ──last clip ends──▶ IDLE
 *   ▲                    │
 *   └─────interrupt()────┴──(also valid from SPEAKING)──▶ IDLE
 * ```
 */
enum class ConversationPhase {
    /** Nothing in flight; the avatar is at rest. */
    IDLE,

    /** LLM streaming in progress; TTS may already be prefetching. */
    THINKING,

    /** Audio is being played through the playback queue. */
    SPEAKING,
}
