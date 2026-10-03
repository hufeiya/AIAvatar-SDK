package com.neethu.aiadapter.api

/**
 * One analysis frame: raw per-phoneme matcher scores plus normalized loudness.
 *
 * [phonemeScores] is aligned with the profile's phoneme order (for the bundled
 * wLipSync profile: `A, E, I, O, U, S`); values are normalized to sum to 1.
 * [volume] is the frame RMS in 0..1 of full-scale input.
 */
class PhonemeFrame(
    val timeSeconds: Float,
    val volume: Float,
    val phonemeScores: FloatArray,
)

/**
 * Offline lip-sync analysis result for one utterance.
 *
 * Frames are laid out on a fixed hop (one input window ≈ 64 ms) so a player can
 * index them by playback position instead of analyzing audio in real time.
 */
class VisemeTimeline(
    val frames: List<PhonemeFrame>,
    val durationSeconds: Float,
    val sampleRateHz: Int,
) {
    /** The last frame at or before [timeSeconds], or `null` before the first frame. */
    fun sampleAt(timeSeconds: Float): PhonemeFrame? {
        if (frames.isEmpty() || timeSeconds < 0f) return null
        // Linear scan is fine: frame counts per sentence are small (< 200).
        var result: PhonemeFrame? = null
        for (frame in frames) {
            if (frame.timeSeconds <= timeSeconds) result = frame else break
        }
        return result ?: frames.last()
    }
}

/**
 * Turns 16-bit PCM audio into a [VisemeTimeline].
 *
 * The bundled implementation reproduces the wLipSync / uLipSync MFCC pipeline
 * (16 kHz, 1024-sample windows, 30 mel bands, 12 cepstral coefficients) and
 * matches against per-vowel calibration templates.
 */
interface LipSyncProcessor {
    fun analyze(pcm16: ShortArray, sampleRateHz: Int): VisemeTimeline
}
