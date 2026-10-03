package com.neethu.orchestrator.pipeline

import com.neethu.aiadapter.api.LipSyncProcessor
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.audio.PcmDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Sentence-level speech pipeline — port of AIRI's `createSpeechPipeline`:
 *
 *  - TTS synthesis runs with up to [ttsMaxConcurrent] requests in flight
 *    (sentence N plays while N+1 is already synthesized, N+2 synthesizing)
 *  - finished clips are scheduled to the [PlaybackQueue] strictly in
 *    submission order (`sequence`), never out of order
 *  - cancellation: [cancelTurn] aborts in-flight synthesis and stops playback
 *
 * The pipeline does not own playback callbacks; the session's queue listener
 * calls [onPlaybackSettled] for every ended/interrupted clip.
 */
class SpeechPipeline(
    parentScope: CoroutineScope,
    private val tts: TtsAdapter,
    private val queue: PlaybackQueue,
    private val lipSyncProcessor: LipSyncProcessor?,
    private val ttsMaxConcurrent: Int = 4,
) {

    interface Listener {
        /** A sentence failed to synthesize/decode; the turn continues without it. */
        fun onSentenceFailed(sequence: Int, text: String, error: Throwable)
    }

    var listener: Listener? = null

    private val pipelineScope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
    private val semaphore = Semaphore(ttsMaxConcurrent.coerceAtLeast(1))

    private val lock = Any()
    private var turnJobs = mutableListOf<Job>()
    private var nextSequence = 0
    private var nextToSchedule = 0
    private var inFlightCount = 0
    private var turnClosed = false
    private val completed = HashMap<Int, PlaybackItem>()
    private val failedSequences = HashSet<Int>()
    private var turnComplete = CompletableDeferred<Unit>()

    fun beginTurn() {
        synchronized(lock) {
            turnJobs = mutableListOf()
            nextSequence = 0
            nextToSchedule = 0
            inFlightCount = 0
            turnClosed = false
            completed.clear()
            failedSequences.clear()
        }
        turnComplete = CompletableDeferred()
    }

    /** Queue one sentence for synthesis + ordered playback. Returns its sequence number. */
    fun submit(text: String, ttsConfig: TtsConfig): Int {
        val seq: Int
        val job: Job
        synchronized(lock) {
            seq = nextSequence++
            inFlightCount++
            job = pipelineScope.launch {
                semaphore.withPermit {
                    try {
                        val result = tts.synthesize(text, ttsConfig)
                        val decoded = PcmDecoder.decode(result)
                        val timeline = lipSyncProcessor?.analyze(decoded.pcm, decoded.sampleRateHz)
                        onSynthesized(
                            PlaybackItem(
                                sequence = seq,
                                text = text,
                                pcm = decoded.pcm,
                                sampleRateHz = decoded.sampleRateHz,
                                timeline = timeline,
                            )
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        onSynthesisFailed(seq, text, t)
                    }
                }
            }
            turnJobs += job
        }
        return seq
    }

    /** Mark the LLM stream as finished; no more sentences will be submitted. */
    fun endTurn() {
        var done = false
        synchronized(lock) {
            turnClosed = true
            done = inFlightCount <= 0
        }
        if (done) turnComplete.complete(Unit)
    }

    /** Suspend until every submitted sentence has been played, failed, or cancelled. */
    suspend fun awaitTurnComplete() = turnComplete.await()

    /** Abort everything: in-flight synthesis, pending clips and active playback. */
    fun cancelTurn(reason: String) {
        val jobs: List<Job>
        synchronized(lock) {
            jobs = turnJobs.toList()
            turnJobs = mutableListOf()
            completed.clear()
            inFlightCount = 0
            turnClosed = true
        }
        jobs.forEach { it.cancel() }
        queue.stopAll(reason)
        turnComplete.cancel()
    }

    /** Report one clip settled (ended naturally or interrupted after starting). */
    fun onPlaybackSettled() = settled()

    private fun settled() {
        var done = false
        synchronized(lock) {
            inFlightCount--
            if (turnClosed && inFlightCount <= 0) done = true
        }
        if (done && !turnComplete.isCompleted) turnComplete.complete(Unit)
    }

    private fun onSynthesisFailed(seq: Int, text: String, t: Throwable) {
        listener?.onSentenceFailed(seq, text, t)
        synchronized(lock) { failedSequences += seq }
        settled()
        scheduleOrdered()
    }

    private fun onSynthesized(item: PlaybackItem) {
        synchronized(lock) { completed[item.sequence] = item }
        scheduleOrdered()
    }

    private fun scheduleOrdered() {
        while (true) {
            val next: PlaybackItem?
            synchronized(lock) {
                // Skip over failed sequences so a gap never wedges the frontier.
                while (nextToSchedule in failedSequences) {
                    failedSequences.remove(nextToSchedule)
                    nextToSchedule++
                }
                next = completed.remove(nextToSchedule)
                if (next != null) nextToSchedule++
            }
            if (next == null) return
            queue.enqueue(next)
        }
    }
}
