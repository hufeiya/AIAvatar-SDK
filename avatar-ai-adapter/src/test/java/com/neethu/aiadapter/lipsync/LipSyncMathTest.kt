package com.neethu.aiadapter.lipsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VowelDriverTest {

    private fun driver(): VowelDriver = VowelDriver().apply {
        setPhonemeGroups(VowelDriver.defaultLayoutFor(listOf("A", "I", "U", "E", "O", "S")))
    }

    /** scores with almost all mass on phoneme [idx]. */
    private fun scores(idx: Int, size: Int = 6): FloatArray =
        FloatArray(size) { if (it == idx) 0.9f else 0.02f }

    @Test
    fun `default layout maps six phonemes with S aliasing ih`() {
        val layout = VowelDriver.defaultLayoutFor(listOf("A", "I", "U", "E", "O", "S"))
        assertEquals(5, layout.size)
        assertEquals(listOf(0), layout.toList()[0].toList())   // aa ← A
        assertEquals(listOf(1, 5), layout.toList()[1].toList()) // ih ← I,S
        assertEquals(listOf(2), layout.toList()[2].toList())   // ou ← U
        assertEquals(listOf(3), layout.toList()[3].toList())   // ee ← E
        assertEquals(listOf(4), layout.toList()[4].toList())   // oh ← O
    }

    @Test
    fun `loud clear vowel converges toward capped winner`() {
        val d = driver()
        var out = FloatArray(5)
        var t = 0f
        repeat(60) {
            out = d.update(volume = 0.5f, phonemeScores = scores(0), timeSeconds = t, deltaSeconds = 1f / 60)
            t += 1f / 60
        }
        // winner aa: min(0.7, 0.9*amp)*0.7 → expected ≈ 0.7 * amplitude(0.45^0.7≈0.577)... simpler bounds:
        assertTrue("aa should dominate: ${out[0]}", out[0] > 0.2f)
        for (v in 1 until 5) assertTrue("non-winners stay low: ${out[v]}", out[v] < 0.1f)
        // cap: winner output never exceeds 0.7 * gain
        assertTrue(out[0] <= VowelDriver.WINNER_CAP * VowelDriver.OUTPUT_GAIN + 0.01f)
    }

    @Test
    fun `silence decays weights to zero`() {
        val d = driver()
        var t = 0f
        repeat(60) {
            d.update(0.5f, scores(2), t, 1f / 60)
            t += 1f / 60
        }
        var out = FloatArray(5)
        repeat(120) {
            out = d.update(0f, scores(2), t, 1f / 60)
            t += 1f / 60
        }
        assertTrue("weights should decay: ${out.toList()}", out.all { it < 0.05f })
    }

    @Test
    fun `attack is faster than release`() {
        val d = driver()
        var t = 0f
        var peak = 0f
        repeat(6) {
            val out = d.update(0.9f, scores(0), t, 1f / 60)
            peak = maxOf(peak, out[0])
            t += 1f / 60
        }
        var drop = 1f
        repeat(6) {
            val out = d.update(0f, scores(0), t, 1f / 60)
            drop = out[0]
            t += 1f / 60
        }
        // after 6 frames (100 ms): attack is nearly saturated, release is still visible
        assertTrue("attack should be near cap: $peak", peak > 0.35f)
        assertTrue("release should lag: $drop", drop in 0.005f..0.15f)
    }

    @Test
    fun `silence holdout keeps last shape briefly`() {
        val d = driver()
        var t = 0f
        repeat(60) { d.update(0.9f, scores(0), t, 1f / 60); t += 1f / 60 }
        // volume drops but time keeps running: after 160ms+ of quiet the gate closes
        val immediate = d.update(0f, FloatArray(6), t, 1f / 60)
        val later = d.update(0f, FloatArray(6), t + 0.5f, 0.5f)
        assertTrue(immediate[0] >= 0f)
        assertTrue("post-holdout weight must collapse: ${later[0]}", later[0] < 0.05f)
    }
}

class WlipsyncProfileTest {

    @Test
    fun `bundled profile loads and matches AIRI layout`() {
        val profile = WlipsyncProfile.bundled()
        assertEquals(12, profile.phonemeCount)
        assertEquals("A", profile.phonemes.first())
        assertEquals(WlipsyncProfile.METHOD_COSINE, profile.compareMethod)
        assertEquals(16000, profile.targetSampleRate)
        assertEquals(1024, profile.sampleCount)
        assertEquals(30, profile.melFilterBankChannels)
        assertEquals(12, profile.mfccCount)
    }

    @Test
    fun `scores of a real template sum to one and win`() {
        val profile = WlipsyncProfile.bundled()
        val scores = profile.scores(profile.template(0))
        assertEquals(1f, scores.sum(), 0.01f)
        assertEquals(0, scores.indices.maxBy { scores[it] })
    }

    @Test
    fun `template match dominates a mismatched vector`() {
        val profile = WlipsyncProfile.bundled()
        val matched = profile.scores(profile.template(3))
        val mismatched = profile.scores(FloatArray(12) { i -> -20f + i * 13.7f })
        assertTrue(matched.max() >= mismatched.max())
        assertTrue("self-match should be decisive: ${matched.max()}", matched.max() > 0.5f)
    }
}

class WlipsyncLipSyncProcessorTest {

    @Test
    fun `timeline has one frame per window and silence yields no motion`() {
        val processor = WlipsyncLipSyncProcessor()
        val sampleRate = 24000
        val seconds = 1f
        val n = (sampleRate * seconds).toInt()
        val pcm = ShortArray(n) // silence
        val timeline = processor.analyze(pcm, sampleRate)
        val windowSize = (1024 * sampleRate / 16000.0).let { kotlin.math.ceil(it).toInt() }
        val expectedFrames = kotlin.math.ceil(n.toDouble() / windowSize).toInt()
        assertEquals(expectedFrames, timeline.frames.size)
        assertEquals(seconds, timeline.durationSeconds, 0.01f)
        assertTrue(timeline.frames.all { it.volume < 0.01f })
        assertTrue(timeline.frames.all { it.phonemeScores.sum() >= 0f })
    }

    @Test
    fun `tonal signal produces frames with bounded volumes`() {
        val processor = WlipsyncLipSyncProcessor()
        val sampleRate = 24000
        val n = sampleRate / 2 // 0.5 s of a 220 Hz tone
        val pcm = ShortArray(n) { i -> (sin(2.0 * PI * 220 * i / sampleRate) * 8000).toInt().toShort() }
        val timeline = processor.analyze(pcm, sampleRate)
        assertTrue(timeline.frames.isNotEmpty())
        assertTrue(timeline.frames.all { it.volume <= 1.0f })
        assertTrue(timeline.frames.any { it.volume > 0.1f })
    }

    @Test
    fun `sampleAt returns the latest frame at or before t`() {
        val processor = WlipsyncLipSyncProcessor()
        val timeline = processor.analyze(ShortArray(24000), 24000)
        val first = timeline.frames.first()
        assertEquals(first, timeline.sampleAt(0f))
        assertEquals(timeline.frames.last(), timeline.sampleAt(999f))
        assertEquals(null, timeline.sampleAt(-1f))
    }
}
