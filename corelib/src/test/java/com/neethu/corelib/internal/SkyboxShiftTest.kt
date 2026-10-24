package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 天空盒 yaw 的纯数组核心（SkyboxManager.kt 顶层函数）：循环横移语义锁
 * ——正 shift 内容左移（右侧像素绕到左边）、负 shift 与整圈 shift 归一。
 * equirect→cubemap 转换的方向约定见 EquirectToCubemapConverterTest。
 */
class SkyboxShiftTest {

    private fun row(pixels: IntArray, width: Int, y: Int): List<Int> =
        pixels.slice(y * width until (y + 1) * width)

    @Test
    fun `positive shift moves content left and wraps right edge`() {
        // 4x2 图，每行 [1,2,3,4]；shift=1 → [2,3,4,1]（内容左移一位）
        val pixels = IntArray(8) { it % 4 + 1 }
        val out = shiftPixelsHorizontally(pixels, width = 4, height = 2, shiftColumns = 1)
        assertEquals(listOf(2, 3, 4, 1), row(out, 4, 0))
        assertEquals(listOf(2, 3, 4, 1), row(out, 4, 1))
    }

    @Test
    fun `negative shift normalizes to positive equivalent`() {
        val pixels = IntArray(8) { it % 4 + 1 }
        val byNegative = shiftPixelsHorizontally(pixels, 4, 2, shiftColumns = -3)
        val byPositive = shiftPixelsHorizontally(pixels, 4, 2, shiftColumns = 1)
        assertEquals(byPositive.toList(), byNegative.toList())
        assertEquals(listOf(2, 3, 4, 1), row(byNegative, 4, 0))
    }

    @Test
    fun `zero or full-width shift returns original array untouched`() {
        val pixels = IntArray(8) { it }
        assertSame(pixels, shiftPixelsHorizontally(pixels, 4, 2, 0))
        assertSame(pixels, shiftPixelsHorizontally(pixels, 4, 2, 4))
        assertSame(pixels, shiftPixelsHorizontally(pixels, 4, 2, -8))
    }

    @Test
    fun `shift beyond width wraps modulo`() {
        // shift=5 ≡ shift=1 (mod 4)
        val pixels = IntArray(4) { it + 1 }
        val out = shiftPixelsHorizontally(pixels, 4, 1, 5)
        assertEquals(listOf(2, 3, 4, 1), out.toList())
    }
}
