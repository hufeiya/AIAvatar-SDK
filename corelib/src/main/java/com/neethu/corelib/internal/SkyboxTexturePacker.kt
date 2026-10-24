package com.neethu.corelib.internal

import java.nio.ByteBuffer
import kotlin.math.pow

/**
 * 天空盒 cubemap 的纹理打包数学（纯 Kotlin，无 Android 依赖，JVM 可单测）。
 *
 * 为什么上传前要做「sRGB→linear + 增益」预处理（docs/skybox.md 坑 9）：filament
 * 的天空盒着色器（materials/skybox.mat）在采样后执行 `sky.rgb *= iblLuminance`，
 * 而 `iblLuminance = IBL强度(lux) × 相机曝光`（View.cpp/ColorPassDescriptorSet）。
 * 本项目的组合是 13 000 lux × 2.6e-5 ≈ 0.34，LDR 全景（线性值 ≤ 1.0）被整体压暗
 * 约 1.6 档。补偿 = 上传前预乘 `gain = brightness / envLuminance`，使着色器乘回
 * `envLuminance` 后显示值恰为 `sRGB编码(linear(原图) × brightness)`。
 *
 * 格式必须是 **RGBA16F（half）**：增益 1/0.34 ≈ 2.9，线性值会超过 1.0，8-bit
 * sRGB 格式装不下（高光截幅）；half 上限 65504，gain 上限 4/1e-4 = 4e4 也不会
 * 溢出。天空盒着色器只采样 `textureLod(…, 0.0)`，单层即可，无需 mip 链。
 */
internal object SkyboxTexturePacker {

    /** sRGB(0..255) → 线性光查找表（标准 sRGB EOTF，与 GPU SRGB8_A8 硬件解码同式）。 */
    internal val SRGB_TO_LINEAR: FloatArray = FloatArray(256) { v ->
        val s = v / 255f
        if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)
    }

    /**
     * IEEE 754 half（binary16）位模式，向偶数舍入。返回低 16 位有效。
     * filament HALF 上传要求内存字节序为小端（与 ARM 原生一致）。
     */
    internal fun floatToHalfBits(value: Float): Int {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exp8 = (bits ushr 23) and 0xFF
        val mant = bits and 0x7F_FFFF
        if (exp8 == 0xFF) {
            // Inf / NaN(NaN 保持尾数最高位,canonical 0x7E00;极小尾数至少置 1)
            return if (mant == 0) sign or 0x7C00 else sign or 0x7C00 or maxOf(1, mant ushr 13)
        }
        val exp = exp8 - 127 + 15
        if (exp >= 0x1F) return sign or 0x7C00            // 溢出 → ±Inf
        if (exp <= 0) {
            // half 次正规:h = round(value×2^24),value < 2^-25 归零
            if (exp < -10) return sign
            val m = mant or 0x80_0000
            val shift = 14 - exp                           // 14..24
            val h = m ushr shift
            val rem = m and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            val rounded = h + if (rem > halfway || (rem == halfway && (h and 1) == 1)) 1 else 0
            return sign or rounded
        }
        val h = sign or (exp shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        return if (rem > 0x1000 || (rem == 0x1000 && (h and 1) == 1)) h + 1 else h
    }

    /**
     * 一面 sRGB ARGB 像素（0xAARRGGBB）→ 线性化 × [gain] → RGBA half 字节流
     * （8B/px，小端，alpha=1.0）。每面上传前先把 256 项×增益折算成 half 查找表，
     * 逐像素 3 次查表 + 8 次写（≈1M px/面，后台线程零压力）。
     */
    internal fun packFaceLinearHalf(pixels: IntArray, gain: Float, out: ByteBuffer) {
        require(out.remaining() >= pixels.size * 8) {
            "output buffer too small: ${out.remaining()} < ${pixels.size * 8}"
        }
        val halfLut = ShortArray(256) { ch -> floatToHalfBits(SRGB_TO_LINEAR[ch] * gain).toShort() }
        for (p in pixels) {
            putHalfLe(out, halfLut[(p ushr 16) and 0xFF].toInt() and 0xFFFF)
            putHalfLe(out, halfLut[(p ushr 8) and 0xFF].toInt() and 0xFFFF)
            putHalfLe(out, halfLut[p and 0xFF].toInt() and 0xFFFF)
            putHalfLe(out, 0x3C00)                         // alpha = 1.0
        }
    }

    private fun putHalfLe(out: ByteBuffer, bits: Int) {
        out.put(bits.toByte())
        out.put((bits ushr 8).toByte())
    }

    /**
     * 上传增益 = 用户亮度倍率 / 环境亮度（IBL强度×相机曝光）。环境亮度非法时
     * 按最小值兜底（除零防护；真实链路里渲染器传入的值恒 > 0.01）。
     */
    internal fun gainFor(brightness: Float, envLuminance: Float): Float =
        brightness / envLuminance.coerceAtLeast(MIN_ENV_LUMINANCE)

    /**
     * filament 物理相机曝光（Exposure.cpp 同式）：exposure = 1/(1.2 × N²/t × 100/S)。
     * 默认档 f/16、1/125s、ISO 100 → ≈ 2.604e-5（"晴天 16 法则"）。
     */
    internal fun cameraExposure(aperture: Float, shutterSpeed: Float, sensitivity: Float): Float =
        1f / (1.2f * (aperture * aperture) / shutterSpeed * 100f / sensitivity)

    /** [gainFor] 的环境亮度下限（防除零；也防无 IBL 时天空盒强度 0 的病态输入）。 */
    internal const val MIN_ENV_LUMINANCE = 1e-4f
}
