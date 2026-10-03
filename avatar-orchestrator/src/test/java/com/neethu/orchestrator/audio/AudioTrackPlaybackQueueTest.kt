package com.neethu.orchestrator.audio

import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTrackPlaybackQueueTest {

    /**
     * release() interrupts the writer thread as its shutdown signal. The
     * writer is usually parked in lock.wait() at that moment — an uncaught
     * InterruptedException there is a FATAL crash on avatar-playback (seen
     * live when a config rebuild closed the session, 2026-10-03, appendix
     * A.1 #17). Regression: release must unwind the parked writer quietly.
     */
    @Test
    fun `release while the writer is parked does not crash the writer thread`() {
        val crashes = mutableListOf<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
        try {
            val queue = AudioTrackPlaybackQueue() // init starts the writer
            Thread.sleep(200) // let the writer park in lock.wait()
            queue.release()
            Thread.sleep(200) // give a crashing thread time to die loudly
            assertTrue("writer crashed: $crashes", crashes.isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
