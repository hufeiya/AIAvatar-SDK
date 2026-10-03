package com.neethu.aiadapter.lipsync

import com.neethu.aiadapter.api.LipSyncProcessor
import com.neethu.aiadapter.api.PhonemeFrame
import com.neethu.aiadapter.api.VisemeTimeline
import java.util.Arrays
import kotlin.math.min

/**
 * wLipSync-compatible lip-sync analyzer.
 *
 * Runs the MFCC frontend over non-overlapping input windows (≈64 ms, matching
 * the real-time AudioWorklet cadence the calibration was recorded with) and
 * emits one [PhonemeFrame] per window.
 */
class WlipsyncLipSyncProcessor(
    private val profile: WlipsyncProfile = WlipsyncProfile.bundled(),
) : LipSyncProcessor {

    private val frontend = MfccFrontend(
        targetRate = profile.targetSampleRate,
        frameSamples = profile.sampleCount,
        melBands = profile.melFilterBankChannels,
        mfccCount = profile.mfccCount,
    )

    /** Vowel-slot layout derived from the profile; feed to [VowelDriver.setPhonemeGroups]. */
    val vowelLayout: Array<IntArray> = VowelDriver.defaultLayoutFor(profile.phonemes)

    override fun analyze(pcm16: ShortArray, sampleRateHz: Int): VisemeTimeline {
        if (pcm16.isEmpty() || sampleRateHz <= 0) {
            return VisemeTimeline(emptyList(), 0f, sampleRateHz)
        }
        val windowSize = frontend.inputWindowSize(sampleRateHz)
        val window = FloatArray(windowSize)
        val mfcc = FloatArray(profile.mfccCount)
        val frames = ArrayList<PhonemeFrame>((pcm16.size / windowSize) + 1)

        var start = 0
        while (start < pcm16.size) {
            val n = min(windowSize, pcm16.size - start)
            for (i in 0 until n) window[i] = pcm16[start + i] / 32768f
            if (n < windowSize) Arrays.fill(window, n, windowSize, 0f) // zero-pad the tail

            val volume = frontend.compute(window, windowSize, sampleRateHz, mfcc)
            frames += PhonemeFrame(
                timeSeconds = start.toFloat() / sampleRateHz,
                volume = volume,
                phonemeScores = profile.scores(mfcc),
            )
            start += windowSize
        }
        return VisemeTimeline(frames, pcm16.size.toFloat() / sampleRateHz, sampleRateHz)
    }
}
