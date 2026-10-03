package com.neethu.orchestrator.pipeline

import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPipelineTest {

    private class FakeQueue : PlaybackQueue {
        override var listener: PlaybackQueue.Listener? = null
        val enqueued = mutableListOf<Int>()
        var stopped = false
        var autoSettle = true

        override fun active(): ActivePlayback? = null

        override fun enqueue(item: PlaybackItem) {
            enqueued += item.sequence
            if (autoSettle) listener?.onPlaybackEnded(item)
        }

        override fun stopAll(reason: String) {
            stopped = true
        }

        override fun release() = Unit
    }

    /** TTS that sleeps for a per-sentence duration, then returns raw PCM. */
    private class FakeTts(val latencyMs: (String) -> Long) : TtsAdapter {
        override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
            delay(latencyMs(text))
            val pcm = ByteArray(64) { it.toByte() }
            return TtsResult(pcm, TtsAudioFormat.RAW_PCM_16LE, 24000)
        }
    }

    private fun newPipeline(scope: CoroutineScope, tts: TtsAdapter, queue: FakeQueue): SpeechPipeline {
        val pipeline = SpeechPipeline(scope, tts, queue, lipSyncProcessor = null, ttsMaxConcurrent = 4)
        // Mirror AvatarSession's wiring: settled playback advances the turn.
        queue.listener = object : PlaybackQueue.Listener {
            override fun onPlaybackStarted(item: PlaybackItem) = Unit
            override fun onPlaybackEnded(item: PlaybackItem) = pipeline.onPlaybackSettled()
            override fun onPlaybackInterrupted(item: PlaybackItem?) = pipeline.onPlaybackSettled()
        }
        return pipeline
    }

    @Test
    fun `clips are scheduled strictly in submission order despite varied latency`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val queue = FakeQueue()
        // sentence 0 is the slowest, sentence 2 the fastest — order must hold
        val pipeline = newPipeline(
            backgroundScope,
            FakeTts { text -> when (text) { "s0" -> 300; "s1" -> 100; else -> 10 } },
            queue,
        )

        pipeline.beginTurn()
        pipeline.submit("s0", TtsConfig("m", "v"))
        pipeline.submit("s1", TtsConfig("m", "v"))
        pipeline.submit("s2", TtsConfig("m", "v"))
        pipeline.endTurn()
        pipeline.awaitTurnComplete()

        assertEquals(listOf(0, 1, 2), queue.enqueued)
    }

    @Test
    fun `awaitTurnComplete resolves with zero sentences`() = runTest {
        val pipeline = newPipeline(backgroundScope, FakeTts { 0 }, FakeQueue())
        pipeline.beginTurn()
        pipeline.endTurn()
        pipeline.awaitTurnComplete()
    }

    @Test
    fun `failed synthesis does not stall the turn`() = runTest {
        val queue = FakeQueue()
        val failingTts = object : TtsAdapter {
            override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
                if (text == "bad") throw IllegalStateException("boom")
                delay(10)
                return TtsResult(ByteArray(16), TtsAudioFormat.RAW_PCM_16LE, 24000)
            }
        }
        val pipeline = newPipeline(backgroundScope, failingTts, queue)
        pipeline.beginTurn()
        pipeline.submit("bad", TtsConfig("m", "v"))
        pipeline.submit("good", TtsConfig("m", "v"))
        pipeline.endTurn()
        pipeline.awaitTurnComplete()
        assertEquals(listOf(1), queue.enqueued)
    }

    @Test
    fun `cancelTurn stops the queue and cancels the awaiter`() = runTest {
        val queue = FakeQueue()
        val started = CompletableDeferred<Unit>()
        val tts = object : TtsAdapter {
            override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
                started.complete(Unit)
                delay(10_000) // never finishes
                error("unreachable")
            }
        }
        val pipeline = newPipeline(backgroundScope, tts, queue)
        pipeline.beginTurn()
        pipeline.submit("slow", TtsConfig("m", "v"))
        started.await()
        pipeline.cancelTurn("test")
        assertTrue(queue.stopped)
    }

    @Test
    fun `tts concurrency is capped`() = runTest {
        val queue = FakeQueue()
        var concurrent = 0
        var peak = 0
        val tts = object : TtsAdapter {
            override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
                concurrent++
                peak = maxOf(peak, concurrent)
                delay(50)
                concurrent--
                return TtsResult(ByteArray(16), TtsAudioFormat.RAW_PCM_16LE, 24000)
            }
        }
        val pipeline = newPipeline(backgroundScope, tts, queue)
        pipeline.beginTurn()
        repeat(10) { pipeline.submit("s$it", TtsConfig("m", "v")) }
        pipeline.endTurn()
        pipeline.awaitTurnComplete()
        assertEquals(4, peak)
    }
}
