package com.neethu.aiadapter.lipsync

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Exact port of the wLipSync (uLipSync-lineage) DSP helpers used by the MFCC
 * frontend. The bundled calibration profile was captured through this exact
 * pipeline, so the operations and their order must not be "improved" casually.
 */
internal object Dsp {

    fun rmsVolume(array: FloatArray, size: Int): Float {
        var acc = 0.0
        for (i in 0 until size) acc += array[i].toDouble() * array[i].toDouble()
        return sqrt(acc / size).toFloat()
    }

    /**
     * Windowed-sinc FIR low-pass. Mirrors wLipSync `low_pass_filter`, including
     * its quirk of adding filter terms on top of the (untouched) original data
     * — the calibration data was produced with this behavior.
     */
    fun lowPassFilter(data: FloatArray, size: Int, sampleRate: Float, cutoff: Float, range: Float, skip: Int) {
        val c = (cutoff - range) / sampleRate
        val r = range / sampleRate
        var n = (3.1 / r).roundToInt()
        if (n % 2 != 0) n++
        val tmp = FloatArray(n + size)
        System.arraycopy(data, 0, tmp, n, size)
        for (j in 0 until n / 2) {
            val x = j - (n - 1) / 2.0f
            val ang = 2.0 * PI * c * x
            val b = 2.0 * c * sin(ang) / ang
            var i = j + (skip - j % skip) % skip
            while (i < size) {
                data[i] += (b * (tmp[n + i - j] + tmp[n + i - (n - 1 - j)])).toFloat()
                i += skip
            }
        }
    }

    fun downSampleExact(input: FloatArray, output: FloatArray, outputSize: Int, skip: Int) {
        for (i in 0 until outputSize) output[i] = input[i * skip]
    }

    fun downSample(input: FloatArray, size: Int, output: FloatArray, outputSize: Int, df: Float) {
        for (j in 0 until outputSize) {
            val fIndex = df * j
            val i0 = floor(fIndex).toInt()
            val i1 = max(i0 + 1, 0).coerceAtMost(size - 1)
            val t = fIndex - i0
            val x0 = input[i0]
            val x1 = input[i1]
            output[j] = x0 + (x1 - x0) * t
        }
    }

    fun preEmphasis(data: FloatArray, len: Int, p: Float) {
        for (i in len - 1 downTo 1) {
            data[i] = data[i] - p * data[i - 1]
        }
    }

    fun hammingWindow(data: FloatArray, len: Int) {
        for (i in 0 until len) {
            val x = i.toFloat() / (len - 1)
            data[i] *= (0.54 - 0.46 * cos(2.0 * PI * x)).toFloat()
        }
    }

    fun normalize(data: FloatArray, len: Int, value: Float) {
        var max = 0f
        for (i in 0 until len) max = max(max, abs(data[i]))
        if (max < 1e-7f) return
        val r = value / max
        for (i in 0 until len) data[i] *= r
    }

    /** In-place iterative radix-2 FFT; [spectrum] receives magnitudes. [size] must be a power of two. */
    fun fftMagnitude(data: FloatArray, spectrum: FloatArray, size: Int) {
        val re = DoubleArray(size)
        val im = DoubleArray(size)

        // Bit-reverse copy
        val bits = 31 - java.lang.Integer.numberOfLeadingZeros(size)
        for (i in 0 until size) {
            var reversed = 0
            for (j in 0 until bits) {
                reversed = (reversed shl 1) or ((i shr j) and 1)
            }
            re[i] = data[reversed].toDouble()
            im[i] = 0.0
        }

        // Butterflies
        var halfSize = 1
        while (halfSize < size) {
            val stride = size / (halfSize * 2)
            var i = 0
            while (i < size) {
                for (j in 0 until halfSize) {
                    val er = re[i + j]
                    val ei = im[i + j]
                    val or_ = re[i + j + halfSize]
                    val oi = im[i + j + halfSize]
                    val ti = j * stride
                    val theta = -2.0 * PI * ti / size
                    val cx = cos(theta)
                    val cy = sin(theta)
                    val cx2 = cx * or_ - cy * oi
                    val cy2 = cx * oi + cy * or_
                    re[i + j] = er + cx2
                    im[i + j] = ei + cy2
                    re[i + j + halfSize] = er - cx2
                    im[i + j + halfSize] = ei - cy2
                }
                i += 2 * halfSize
            }
            halfSize = halfSize shl 1
        }

        for (i in 0 until size) {
            spectrum[i] = sqrt(re[i] * re[i] + im[i] * im[i]).toFloat()
        }
    }

    private fun toMel(hz: Float): Float = (1127.0 * ln(hz / 700.0 + 1.0)).toFloat()
    private fun toHz(mel: Float): Float = (700.0 * exp(mel / 1127.0 - 1.0)).toFloat()

    fun melFilterBank(
        spectrum: FloatArray,
        spectrumSize: Int,
        melSpectrum: FloatArray,
        sampleRate: Float,
        melDiv: Int,
    ) {
        val fMax = sampleRate / 2
        val melMax = toMel(fMax)
        val nMax = spectrumSize / 2
        val df = fMax / nMax
        val dMel = melMax / (melDiv + 1)

        for (n in 0 until melDiv) {
            val melBegin = dMel * n
            val melCenter = dMel * (n + 1)
            val melEnd = dMel * (n + 2)

            val fBegin = toHz(melBegin)
            val fCenter = toHz(melCenter)
            val fEnd = toHz(melEnd)

            val iBegin = ceil(fBegin / df).toInt()
            val iCenter = (fCenter / df).roundToInt()
            val iEnd = floor(fEnd / df).toInt()

            var sum = 0f
            for (i in (iBegin + 1)..iEnd) {
                val f = df * i
                val a = if (i < iCenter) {
                    (f - fBegin) / (fCenter - fBegin)
                } else {
                    (fEnd - f) / (fEnd - fCenter)
                } / ((fEnd - fBegin) * 0.5f)
                if (i in spectrum.indices) sum += a * spectrum[i]
            }
            melSpectrum[n] = sum
        }
    }

    fun powerToDb(array: FloatArray, size: Int) {
        for (i in 0 until size) {
            array[i] = (10.0 * log10(max(array[i].toDouble(), 1e-10))).toFloat()
        }
    }

    fun dct(spectrum: FloatArray, cepstrum: FloatArray, size: Int) {
        val a = PI / size
        for (i in 0 until size) {
            var sum = 0.0
            for (j in 0 until size) {
                val ang = (j + 0.5) * i * a
                sum += spectrum[j] * cos(ang)
            }
            cepstrum[i] = sum.toFloat()
        }
    }
}
