package com.neethu.aiadapter.lipsync

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Sliding-window MFCC frontend — exact port of the wLipSync `execute()` frame
 * pipeline: RMS volume → low-pass → down-sample to 16 kHz → pre-emphasis(0.97)
 * → Hamming window → peak-normalize → FFT → 30-band mel filter bank → dB →
 * DCT → cepstral coefficients 1..12.
 */
internal class MfccFrontend(
    private val targetRate: Int,
    private val frameSamples: Int,
    private val melBands: Int,
    private val mfccCount: Int,
) {
    /** Window length in input samples for a given input sample rate. */
    fun inputWindowSize(inputRate: Int): Int =
        ceil(frameSamples * inputRate.toDouble() / targetRate).toInt()

    /**
     * Analyze one input window. Fills [outMfcc] with [mfccCount] coefficients
     * and returns the frame RMS volume (input-rate domain, 0..1 full scale).
     */
    fun compute(window: FloatArray, windowSize: Int, inputRate: Int, outMfcc: FloatArray): Float {
        val cutoff = targetRate / 2f
        val range = 500f
        val volume = Dsp.rmsVolume(window, windowSize)

        var data: FloatArray
        if (inputRate <= targetRate) {
            Dsp.lowPassFilter(window, windowSize, inputRate.toFloat(), cutoff, range, 1)
            data = window
        } else if (inputRate % targetRate == 0) {
            val skip = inputRate / targetRate
            Dsp.lowPassFilter(window, windowSize, inputRate.toFloat(), cutoff, range, skip)
            data = window
        } else {
            // Fractional rate: linear-resample the window to exactly [frameSamples]
            // so the FFT stays a power of two and mel bin geometry matches calibration.
            Dsp.lowPassFilter(window, windowSize, inputRate.toFloat(), cutoff, range, 1)
            data = FloatArray(frameSamples)
            Dsp.downSample(window, windowSize, data, frameSamples, windowSize.toFloat() / frameSamples)
        }

        val dataSize = frameSamples
        Dsp.preEmphasis(data, dataSize, 0.97f)
        Dsp.hammingWindow(data, dataSize)
        Dsp.normalize(data, dataSize, 1f)

        val spectrum = FloatArray(dataSize)
        Dsp.fftMagnitude(data, spectrum, dataSize)

        val mel = FloatArray(melBands)
        Dsp.melFilterBank(spectrum, dataSize, mel, targetRate.toFloat(), melBands)
        Dsp.powerToDb(mel, melBands)

        val cepstrum = FloatArray(melBands)
        Dsp.dct(mel, cepstrum, melBands)
        for (i in 1..mfccCount) outMfcc[i - 1] = cepstrum[i]
        return volume
    }
}
