package com.neethu.orchestrator.audio

import com.neethu.aiadapter.api.VisemeTimeline

/** One ready-to-play utterance: decoded mono 16-bit PCM plus its lip-sync timeline. */
class PlaybackItem(
    val sequence: Int,
    val text: String,
    val pcm: ShortArray,
    val sampleRateHz: Int,
    val timeline: VisemeTimeline?,
)

/** Handle over the currently playing clip, answering "where are we" for the face driver. */
interface ActivePlayback {
    val item: PlaybackItem

    /** Seconds elapsed within [ActivePlayback.item.pcm], sampled from the audio clock. */
    fun positionSeconds(): Float
}

/**
 * FIFO speech clip player. Enqueued items play strictly in order; a playing
 * clip's lifecycle is reported through [Listener] callbacks (audio thread).
 *
 * Mirrors AIRI's PlaybackManager semantics at `maxVoices = 1`:
 * `onPlaybackEnded` of clip N is what allows the pipeline to consider N done.
 */
interface PlaybackQueue {
    /** Only one listener; the session fans out. Called from the audio thread. */
    var listener: Listener?

    /** The clip currently playing, or `null`. */
    fun active(): ActivePlayback?

    fun enqueue(item: PlaybackItem)

    /**
     * Drop everything queued and cut the current clip immediately.
     * Triggers [Listener.onPlaybackInterrupted] if something was playing.
     */
    fun stopAll(reason: String)

    /** Release underlying audio resources; the queue must not be reused. */
    fun release()

    interface Listener {
        fun onPlaybackStarted(item: PlaybackItem)
        fun onPlaybackEnded(item: PlaybackItem)
        /** [item] is the clip that was cut short, or `null` if only queued items were dropped. */
        fun onPlaybackInterrupted(item: PlaybackItem?)
    }
}
