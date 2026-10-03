package com.neethu.aiadapter.lipsync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Parsed + precomputed wLipSync calibration profile.
 *
 * Reproduces the C `precompute_profile()` semantics: per-phoneme template =
 * element-wise mean over its calibration list; with `useStandardization=false`
 * the comparison uses means=0 / stdDev=1 (plain cosine similarity), matching
 * the bundled profile (`compareMethod: 2`, cosine^100 scoring).
 */
class WlipsyncProfile private constructor(
    /** Phoneme labels in profile order, e.g. [A, E, I, O, U, S]. */
    val phonemes: List<String>,
    private val templates: List<FloatArray>,
    val targetSampleRate: Int,
    val sampleCount: Int,
    val melFilterBankChannels: Int,
    val compareMethod: Int,
    val mfccCount: Int,
    private val means: FloatArray,
    private val stdDevs: FloatArray,
) {
    val phonemeCount: Int get() = phonemes.size

    /** A copy of phoneme [index]'s averaged calibration template (test/tool use). */
    fun template(index: Int): FloatArray = templates[index].copyOf()

    /** Normalized phoneme scores for one MFCC vector (sums to 1). */
    fun scores(mfcc: FloatArray): FloatArray {
        val scores = FloatArray(phonemeCount)
        when (compareMethod) {
            METHOD_L1 -> scoreL1(mfcc, scores)
            METHOD_L2 -> scoreL2(mfcc, scores)
            else -> scoreCosine(mfcc, scores)
        }
        var sum = 0f
        for (s in scores) sum += s
        if (sum > 0f) for (i in scores.indices) scores[i] /= sum
        return scores
    }

    private fun scoreCosine(mfcc: FloatArray, scores: FloatArray) {
        for (p in 0 until phonemeCount) {
            var xNorm = 0.0
            var yNorm = 0.0
            var prod = 0.0
            for (i in 0 until mfccCount) {
                val x = (mfcc[i] - means[i]) / stdDevs[i]
                val y = (templates[p][i] - means[i]) / stdDevs[i]
                xNorm += x * x
                yNorm += y * y
                prod += x * y
            }
            var similarity = prod / (sqrt(xNorm) * sqrt(yNorm) + 1e-12)
            similarity = maxOf(similarity, 0.0)
            scores[p] = similarity.pow(100.0).toFloat()
        }
    }

    private fun scoreL1(mfcc: FloatArray, scores: FloatArray) {
        for (p in 0 until phonemeCount) {
            var acc = 0f
            for (i in 0 until mfccCount) {
                val x = (mfcc[i] - means[i]) / stdDevs[i]
                val y = (templates[p][i] - means[i]) / stdDevs[i]
                acc += kotlin.math.abs(x - y)
            }
            scores[p] = 10f.pow(-acc / mfccCount)
        }
    }

    private fun scoreL2(mfcc: FloatArray, scores: FloatArray) {
        for (p in 0 until phonemeCount) {
            var acc = 0f
            for (i in 0 until mfccCount) {
                val x = (mfcc[i] - means[i]) / stdDevs[i]
                val y = (templates[p][i] - means[i]) / stdDevs[i]
                acc += (x - y) * (x - y)
            }
            scores[p] = 10f.pow(-sqrt(acc))
        }
    }

    companion object {
        const val METHOD_L1 = 0
        const val METHOD_L2 = 1
        const val METHOD_COSINE = 2

        private val json = Json { ignoreUnknownKeys = true }

        /** Load the profile bundled with this library (`/wlipsync/profile.json`). */
        fun bundled(): WlipsyncProfile {
            val stream = WlipsyncProfile::class.java.getResourceAsStream("/wlipsync/profile.json")
                ?: error("Bundled wLipSync profile not found on classpath")
            return fromJson(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }

        fun fromJson(text: String): WlipsyncProfile {
            val root = json.parseToJsonElement(text).jsonObject
            val mfccCount = root["mfccNum"]!!.jsonPrimitive.int
            val dataCount = root["mfccDataCount"]!!.jsonPrimitive.int

            val phonemes = ArrayList<String>()
            val templates = ArrayList<FloatArray>()
            root["mfccs"]!!.jsonArray.forEach { entry ->
                val obj = entry.jsonObject
                phonemes += obj["name"]!!.jsonPrimitive.content
                val lists = obj["mfccCalibrationDataList"]!!.jsonArray
                val mean = FloatArray(mfccCount)
                lists.forEach { sample ->
                    val arr = sample.jsonObject["array"]!!.jsonArray
                    for (i in 0 until mfccCount) mean[i] += arr[i].jsonPrimitive.float / dataCount
                }
                templates += mean
            }

            val useStandardization = when (val flag = root["useStandardization"]?.jsonPrimitive?.content) {
                null -> false
                else -> flag == "true" || flag == "1"
            }
            val means = FloatArray(mfccCount)
            val stdDevs = FloatArray(mfccCount)
            if (useStandardization) {
                for (p in templates.indices) for (i in 0 until mfccCount) means[i] += templates[p][i] / templates.size
                for (p in templates.indices) for (i in 0 until mfccCount) {
                    // NOTE: deviation is computed against raw calibration samples, matching C.
                    // The bundled profile has useStandardization=false, so this branch is
                    // informational only unless a standardized profile is supplied.
                    val d = templates[p][i] - means[i]
                    stdDevs[i] += (d * d / templates.size).toFloat()
                }
                for (i in 0 until mfccCount) stdDevs[i] = sqrt(stdDevs[i])
            } else {
                for (i in 0 until mfccCount) stdDevs[i] = 1f
            }

            return WlipsyncProfile(
                phonemes = phonemes,
                templates = templates,
                targetSampleRate = root["targetSampleRate"]!!.jsonPrimitive.int,
                sampleCount = root["sampleCount"]!!.jsonPrimitive.int,
                melFilterBankChannels = root["melFilterBankChannels"]!!.jsonPrimitive.int,
                compareMethod = root["compareMethod"]!!.jsonPrimitive.int,
                mfccCount = mfccCount,
                means = means,
                stdDevs = stdDevs,
            )
        }
    }
}
