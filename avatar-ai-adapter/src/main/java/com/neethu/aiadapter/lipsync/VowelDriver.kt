package com.neethu.aiadapter.lipsync

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/**
 * Port of AIRI's `createWLipSyncVowelDriver` post-processing (see
 * `packages/model-driver-lipsync/src/shared/wlipsync/vowel-driver.ts`).
 *
 * Converts raw phoneme scores + volume into five VRM vowel weights with
 * winner/runner capping, silence hysteresis and asymmetric exponential
 * smoothing (attack 50/s, release 30/s). All constants match AIRI verbatim.
 */
class VowelDriver {

    /** Canonical vowel order used across the SDK: AA, IH, OU, EE, OH (VRM preset names). */
    companion object {
        const val VOWEL_COUNT = 5
        val VOWEL_NAMES = arrayOf("aa", "ih", "ou", "ee", "oh")

        // AIRI vowel-driver constants
        const val VOLUME_SCALE = 0.9f
        const val VOLUME_EXPONENT = 0.7f
        const val SILENCE_AMPLITUDE = 0.04f
        const val SILENCE_WEIGHT = 0.05f
        const val SILENCE_HOLDOUT_S = 0.16f
        const val WINNER_CAP = 0.7f
        const val RUNNER_CAP = 0.35f
        const val RUNNER_FACTOR = 0.6f
        const val ATTACK_RATE = 50f
        const val RELEASE_RATE = 30f
        const val DEAD_ZONE = 0.01f
        const val OUTPUT_GAIN = 0.7f

        /**
         * Default wLipSync layout: profile phonemes are `A I U E O S` (+ possible
         * duplicate re-recording sets). Vowel slots pull from phonemes by name;
         * `S` (sibilant) is treated as an alias of `ih`, matching AIRI's
         * RAW_TO_VOWEL mapping.
         */
        fun defaultLayoutFor(phonemes: List<String>): Array<IntArray> {
            val slots = Array(VOWEL_COUNT) { IntArray(0) }
            val names = arrayOf("A", "I", "U", "E", "O")
            val lists = Array(VOWEL_COUNT) { ArrayList<Int>() }
            phonemes.forEachIndexed { index, name ->
                val slot = when (name) {
                    in names -> names.indexOf(name)
                    "S" -> 1 // sibilant → ih
                    else -> -1
                }
                if (slot >= 0) lists[slot] += index
            }
            for (v in 0 until VOWEL_COUNT) slots[v] = lists[v].toIntArray()
            return slots
        }
    }

    private val smooth = FloatArray(VOWEL_COUNT)
    // AIRI parity: the clock starts at 0 and only moves forward (initializing at
    // -infinity would make t=0 report "silent for an eternity" on the first frame).
    private var lastActiveTime = 0f

    /** Indices into the raw phoneme score array feeding each canonical vowel slot. */
    private var phonemeGroups: Array<IntArray> = Array(VOWEL_COUNT) { IntArray(0) }

    /**
     * Bind raw score layout to canonical vowels. Each vowel slot may pull from
     * several phoneme entries (duplicate calibration sets); their projected
     * weights merge by max.
     */
    fun setPhonemeGroups(groups: Array<IntArray>) {
        require(groups.size == VOWEL_COUNT) { "expected $VOWEL_COUNT entries" }
        phonemeGroups = Array(VOWEL_COUNT) { v -> groups[v].copyOf() }
    }

    fun reset() {
        smooth.fill(0f)
        lastActiveTime = 0f
    }

    /**
     * Advance the driver state.
     *
     * @param volume frame RMS (0..1)
     * @param phonemeScores raw matcher scores aligned with the profile phonemes
     * @param timeSeconds timeline time of [phonemeScores] (drives silence holdout)
     * @param deltaSeconds time since the previous update
     * @return five vowel weights in [VOWEL_NAMES] order, ready to drive morphs
     */
    fun update(volume: Float, phonemeScores: FloatArray, timeSeconds: Float, deltaSeconds: Float): FloatArray {
        val amplitude = min(volume * VOLUME_SCALE, 1f).pow(VOLUME_EXPONENT)

        // Project raw phonemes onto canonical vowels, merging aliases by max
        // (wLipSync 'S' → 'ih', same as AIRI's RAW_TO_VOWEL mapping).
        val projected = FloatArray(VOWEL_COUNT)
        for (v in 0 until VOWEL_COUNT) {
            for (src in phonemeGroups[v]) {
                if (src in phonemeScores.indices) {
                    projected[v] = maxOf(projected[v], phonemeScores[src] * amplitude)
                }
            }
        }

        var winner = -1
        var winnerWeight = 0f
        var runner = -1
        var runnerWeight = 0f
        for (v in 0 until VOWEL_COUNT) {
            val w = projected[v]
            if (w > winnerWeight) {
                runner = winner; runnerWeight = winnerWeight
                winner = v; winnerWeight = w
            } else if (w > runnerWeight) {
                runner = v; runnerWeight = w
            }
        }

        val silent = amplitude < SILENCE_AMPLITUDE ||
            (winner < 0 || winnerWeight < SILENCE_WEIGHT) ||
            (timeSeconds - lastActiveTime > SILENCE_HOLDOUT_S)
        if (!silent) lastActiveTime = timeSeconds

        val target = FloatArray(VOWEL_COUNT)
        if (!silent && winner >= 0) {
            target[winner] = min(WINNER_CAP, winnerWeight)
            if (runner >= 0) target[runner] = min(RUNNER_CAP, runnerWeight * RUNNER_FACTOR)
        }

        for (v in 0 until VOWEL_COUNT) {
            val from = smooth[v]
            val to = target[v]
            val rate = 1f - exp(-(if (to > from) ATTACK_RATE else RELEASE_RATE) * deltaSeconds)
            smooth[v] = from + (to - from) * rate
        }

        val out = FloatArray(VOWEL_COUNT)
        for (v in 0 until VOWEL_COUNT) {
            val s = smooth[v]
            out[v] = (if (s <= DEAD_ZONE) 0f else s) * OUTPUT_GAIN
        }
        return out
    }
}
