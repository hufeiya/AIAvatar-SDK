package com.neethu.orchestrator.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack

/**
 * [PlaybackQueue] on a dedicated writer thread backed by [AudioTrack].
 *
 * Stop semantics match AIRI's playback manager: `stopAll` clears the waiting
 * queue and pauses+flushes the active track from the calling thread (instant
 * silence), after which the writer loop unwinds and reports the interruption.
 */
class AudioTrackPlaybackQueue : PlaybackQueue {

    override var listener: PlaybackQueue.Listener? = null

    private val lock = Object()
    private val waiting = ArrayDeque<PlaybackItem>()

    private var writer: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var released = false

    private object NoPlayback : ActivePlayback {
        override val item get() = throw IllegalStateException("no active playback")
        override fun positionSeconds() = 0f
    }

    private class TrackPlayback(
        override val item: PlaybackItem,
        val track: AudioTrack,
    ) : ActivePlayback {
        private val timestamp = AudioTimestamp()

        override fun positionSeconds(): Float {
            // The writer thread may release this track concurrently (natural end
            // or interrupt) while FaceDriver still holds us as `activePlayback`;
            // sampling a released track throws IllegalStateException on MIUI,
            // so fall back to "fully played" (viseme mouth closes) instead of
            // crashing the Choreographer.
            val frames = try {
                if (track.getTimestamp(timestamp)) {
                    val elapsedNs = System.nanoTime() - timestamp.nanoTime
                    timestamp.framePosition + elapsedNs * item.sampleRateHz / 1_000_000_000.0
                } else {
                    track.playbackHeadPosition.toDouble()
                }
            } catch (_: IllegalStateException) {
                return item.pcm.size.toFloat() / item.sampleRateHz
            }
            return (frames / item.sampleRateHz).toFloat()
                .coerceIn(0f, item.pcm.size.toFloat() / item.sampleRateHz)
        }
    }

    @Volatile private var activePlayback: ActivePlayback? = null

    override fun active(): ActivePlayback? = activePlayback?.takeIf { it !== NoPlayback }

    init {
        startWriter()
    }

    override fun enqueue(item: PlaybackItem) {
        if (released) return
        synchronized(lock) {
            waiting.addLast(item)
            lock.notifyAll()
        }
    }

    override fun stopAll(reason: String) {
        synchronized(lock) {
            waiting.clear()
            stopRequested = true
            lock.notifyAll()
        }
        // Cut the current track from *this* thread for immediate silence.
        activePlayback?.let { current ->
            (current as? TrackPlayback)?.let {
                try {
                    it.track.pause()
                    it.track.flush()
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    override fun release() {
        stopAll("release")
        released = true
        writer?.interrupt()
    }

    private fun startWriter() {
        writer = Thread({
            writeLoop()
        }, "avatar-playback").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    private fun writeLoop() {
        while (!released) {
            val item: PlaybackItem?
            var stopSeen = false
            synchronized(lock) {
                while (waiting.isEmpty() && !stopRequested && !released) {
                    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
                    (lock as Object).wait()
                }
                stopSeen = stopRequested
                item = waiting.removeFirstOrNull()
            }
            when {
                item != null -> playItem(item)
                stopSeen -> {
                    synchronized(lock) { stopRequested = false }
                    listener?.onPlaybackInterrupted(null)
                }
                else -> Unit // released
            }
        }
    }

    private fun playItem(item: PlaybackItem) {
        val track = createTrack(item.sampleRateHz) ?: run {
            listener?.onPlaybackEnded(item) // treat as failed-silent end
            return
        }
        val playback = TrackPlayback(item, track)
        activePlayback = playback
        listener?.onPlaybackStarted(item)

        var interrupted = false
        try {
            track.play()
            val pcm = item.pcm
            val chunkFrames = 4096
            var offset = 0
            while (offset < pcm.size) {
                if (stopRequested || released) {
                    interrupted = true
                    break
                }
                val n = minOf(chunkFrames, pcm.size - offset)
                val written = track.write(pcm, offset, n, AudioTrack.WRITE_BLOCKING)
                if (written < 0) break
                offset += written
            }
            if (!interrupted) {
                track.stop() // drains remaining buffer
            }
        } catch (_: IllegalStateException) {
            // Track already torn down by stopAll — treat as interrupted.
            interrupted = true
        } finally {
            try {
                track.release()
            } catch (_: IllegalStateException) {
            }
            activePlayback = null
        }

        if (interrupted) {
            listener?.onPlaybackInterrupted(item)
            synchronized(lock) {
                waiting.clear()
                stopRequested = false
            }
        } else {
            listener?.onPlaybackEnded(item)
        }
    }

    private fun createTrack(sampleRateHz: Int): AudioTrack? = try {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRateHz)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
    } catch (_: UnsupportedOperationException) {
        null
    }
}
