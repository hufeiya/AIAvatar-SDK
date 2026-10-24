package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 天空盒纹理打包数学锁（SkyboxTexturePacker.kt，docs/skybox.md 坑 9）。
 *
 * 增益语义自证：显示值 = sRGB编码( linear(原图) × gain × envLuminance )，
 * gain = brightness / envLuminance ⇒ brightness=1 时显示 = 原图 sRGB。
 * 参考式独立写在测试里（filament Exposure.cpp / IES sRGB EOTF），与实现互为对照。
 */
class SkyboxTexturePackerTest {

    // ── float → half ─────────────────────────────────────────────────────

    /** 测试侧独立解码器（IEEE 754 binary16 → Float），与实现互为对照。 */
    private fun halfBitsToFloat(bits: Int): Float {
        val sign = if (bits and 0x8000 != 0) -1f else 1f
        val exp = (bits ushr 10) and 0x1F
        val mant = bits and 0x3FF
        return when {
            exp == 0 -> sign * mant * (2f.pow(-24))
            exp == 0x1F -> if (mant == 0) sign * Float.POSITIVE_INFINITY else Float.NaN
            else -> sign * (1f + mant / 1024f) * 2f.pow(exp - 15)
        }
    }

    private fun Float.pow(e: Int): Float = Math.pow(this.toDouble(), e.toDouble()).toFloat()

    @Test
    fun `half known bit patterns`() {
        assertEquals(0x0000, SkyboxTexturePacker.floatToHalfBits(0f))
        assertEquals(0x8000, SkyboxTexturePacker.floatToHalfBits(-0f))
        assertEquals(0x3C00, SkyboxTexturePacker.floatToHalfBits(1f))
        assertEquals(0xBC00, SkyboxTexturePacker.floatToHalfBits(-1f))
        assertEquals(0x3800, SkyboxTexturePacker.floatToHalfBits(0.5f))
        assertEquals(0x4000, SkyboxTexturePacker.floatToHalfBits(2f))
        assertEquals(0x3400, SkyboxTexturePacker.floatToHalfBits(0.25f))
        assertEquals(0x7BFF, SkyboxTexturePacker.floatToHalfBits(65504f))   // half 最大正规数
        assertEquals(0x7C00, SkyboxTexturePacker.floatToHalfBits(Float.POSITIVE_INFINITY))
        assertEquals(0x7E00, SkyboxTexturePacker.floatToHalfBits(Float.NaN))
    }

    @Test
    fun `half roundtrip within half precision`() {
        // 正规域内往返误差 ≤ 半个 ulp(2^-11 相对)
        var v = 1e-3f
        while (v < 60000f) {
            val decoded = halfBitsToFloat(SkyboxTexturePacker.floatToHalfBits(v))
            assertTrue("v=$v decoded=$decoded", abs(decoded - v) <= v * 6e-4f + 1e-9f)
            v *= 1.37f
        }
    }

    @Test
    fun `half subnormal and underflow`() {
        // 6e-8 ≈ 1.0067×2^-24 → 次正规最小格 0x0001
        assertEquals(0x0001, SkyboxTexturePacker.floatToHalfBits(6e-8f))
        // 2^-25 ≈ 半格 → 向偶舍入到 0
        assertEquals(0x0000, SkyboxTexturePacker.floatToHalfBits(2f.pow(-25)))
        // 2^-24 恰好 1 格
        assertEquals(0x0001, SkyboxTexturePacker.floatToHalfBits(2f.pow(-24)))
        // 深度下溢 → ±0
        assertEquals(0x0000, SkyboxTexturePacker.floatToHalfBits(1e-9f))
        assertEquals(0x8000, SkyboxTexturePacker.floatToHalfBits(-1e-9f))
    }

    @Test
    fun `half mantissa rounding to nearest even`() {
        // 尾数落在半格之外 → 进位;恰半格 → 向偶舍入(奇进偶舍)
        assertEquals(0x3C01, SkyboxTexturePacker.floatToHalfBits(1f + 0x1001 / 8388608f))
        assertEquals(0x3C00, SkyboxTexturePacker.floatToHalfBits(1f + 0x1000 / 8388608f))
        assertEquals(0x3C02, SkyboxTexturePacker.floatToHalfBits(1f + 0x3000 / 8388608f))
        assertEquals(0x3C02, SkyboxTexturePacker.floatToHalfBits(1f + 0x5000 / 8388608f))
        // 尾数全 1 → 进位跨入指数(0x3FFF+1 = 0x4000 = 2.0,最近邻也确实是 2.0)
        assertEquals(0x4000, SkyboxTexturePacker.floatToHalfBits(Float.fromBits(0x3FFFFFFF)))
    }

    // ── sRGB→linear LUT ──────────────────────────────────────────────────

    /** 测试侧独立参考式（IEC 61966-2-1 sRGB EOTF）。 */
    private fun srgbToLinearRef(v: Int): Float {
        val s = v / 255f
        return if (s <= 0.04045f) s / 12.92f
        else Math.pow(((s + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    }

    @Test
    fun `srgb lut endpoints and monotonicity`() {
        assertEquals(0f, SkyboxTexturePacker.SRGB_TO_LINEAR[0], 0f)
        assertEquals(1f, SkyboxTexturePacker.SRGB_TO_LINEAR[255], 1e-7f)
        assertEquals(srgbToLinearRef(128), SkyboxTexturePacker.SRGB_TO_LINEAR[128], 1e-6f)
        assertEquals(srgbToLinearRef(10), SkyboxTexturePacker.SRGB_TO_LINEAR[10], 1e-7f)
        for (v in 1..255) {
            assertTrue(SRGB_MONO_MSG, SkyboxTexturePacker.SRGB_TO_LINEAR[v] >= SkyboxTexturePacker.SRGB_TO_LINEAR[v - 1])
        }
    }

    // ── 增益打包 ─────────────────────────────────────────────────────────

    @Test
    fun `pack writes linear-gain half rgba little endian with opaque alpha`() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), // 黑
            0xFFFFFFFF.toInt(), // 白
            0xFF804020.toInt(), // 中间调
        )
        val gain = 1f / 0.34f
        val out = ByteBuffer.allocate(pixels.size * 8)
        SkyboxTexturePacker.packFaceLinearHalf(pixels, gain, out)
        val bytes = out.array()
        fun b(i: Int): Int = bytes[i].toInt() and 0xFF
        // 黑 → RGB 全 0,alpha 1.0(0x3C00 小端 = 00 3C)
        assertTrue(bytes.copyOfRange(0, 6).all { it.toInt() == 0 })
        assertEquals(0x00, b(6)); assertEquals(0x3C, b(7))
        // 白 → RGB = half(1.0×gain) 小端,alpha 1.0
        val whiteBits = SkyboxTexturePacker.floatToHalfBits(1f * gain)
        assertEquals(whiteBits and 0xFF, b(8))
        assertEquals((whiteBits ushr 8) and 0xFF, b(9))
        // 中间调 R=0x80 → half(lut[128]×gain),逐通道对照
        val rBits = SkyboxTexturePacker.floatToHalfBits(SkyboxTexturePacker.SRGB_TO_LINEAR[0x80] * gain)
        val gBits = SkyboxTexturePacker.floatToHalfBits(SkyboxTexturePacker.SRGB_TO_LINEAR[0x40] * gain)
        val bBits = SkyboxTexturePacker.floatToHalfBits(SkyboxTexturePacker.SRGB_TO_LINEAR[0x20] * gain)
        assertEquals(rBits and 0xFF, b(16))
        assertEquals((rBits ushr 8) and 0xFF, b(17))
        assertEquals(gBits and 0xFF, b(18))
        assertEquals((gBits ushr 8) and 0xFF, b(19))
        assertEquals(bBits and 0xFF, b(20))
        assertEquals((bBits ushr 8) and 0xFF, b(21))
        assertEquals(0x00, b(22)); assertEquals(0x3C, b(23))
    }

    @Test
    fun `gain semantics - brightness 1 displays source srgb exactly`() {
        // 核心承诺:gain(1.0, envLuminance) × envLuminance = 1.0 ⇒ shader 乘回后
        // 显示 = sRGB编码(linear(原图)) = 原图
        for (envLum in listOf(0.34f, 0.09f, 0.57f, 1f, 2.6e-5f * 50000f)) {
            val gain = SkyboxTexturePacker.gainFor(1f, envLum)
            assertEquals("envLum=$envLum", 1f, gain * envLum, 1e-4f)
            // 显示链端到端:pack 出的线性值 × envLum = 原线性
            val pixel = 0xFF800000.toInt()
            val out = ByteBuffer.allocate(8)
            SkyboxTexturePacker.packFaceLinearHalf(intArrayOf(pixel), gain, out)
            val halfBits = (out.get(0).toInt() and 0xFF) or ((out.get(1).toInt() and 0xFF) shl 8)
            val linearAfterShader = halfBitsToFloat(halfBits) * envLum
            assertEquals(srgbToLinearRef(0x80), linearAfterShader, 2e-3f)
        }
    }

    @Test
    fun `gainFor guards non-positive env luminance`() {
        assertEquals(1e4f, SkyboxTexturePacker.gainFor(1f, 0f), 1e-3f)
        assertEquals(1e4f, SkyboxTexturePacker.gainFor(1f, -1f), 1e-3f)
    }

    // ── 相机曝光 ─────────────────────────────────────────────────────────

    /** 测试侧独立参考式（filament Exposure.cpp）。 */
    private fun exposureRef(n: Float, t: Float, s: Float): Float =
        (1.0 / (1.2 * (n * n) / t * 100.0 / s)).toFloat()

    @Test
    fun `camera exposure matches filament formula`() {
        // 默认档 f/16, 1/125s, ISO 100 → ≈ 2.604e-5(正午阳光,本功能变暗根因的一半)
        assertEquals(2.6041667e-5f, SkyboxTexturePacker.cameraExposure(16f, 1f / 125f, 100f), 1e-10f)
        for (triple in listOf(
            Triple(16f, 1f / 125f, 100f),
            Triple(2f, 1f / 60f, 400f),
            Triple(1.8f, 1f / 2000f, 800f),
        )) {
            assertEquals(
                exposureRef(triple.first, triple.second, triple.third),
                SkyboxTexturePacker.cameraExposure(triple.first, triple.second, triple.third),
                1e-9f,
            )
        }
    }

    companion object {
        private const val SRGB_MONO_MSG = "sRGB LUT must be non-decreasing"
    }
}
