package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * equirect→cubemap CPU 转换器（EquirectToCubemapConverter.kt）的方向约定锁。
 *
 * 参考公式独立写在测试里（注释标明出处），与实现互为对照：
 * - 面像素→方向：filament libs/ibl Cubemap.h `getDirectionFor`
 *   （cx=2(i+0.5)/N−1, cy=1−2(j+0.5)/N;PX:(1,cy,−cx) NX:(−1,cy,cx) PY:(cx,1,−cy)
 *    NY:(cx,−1,cy) PZ:(cx,cy,1) NZ:(−cx,cy,−1),归一化）——GL 硬件 cubemap 采样约定
 * - 方向→equirect UV：filament libs/iblprefilter equirectToCube.mat `toEquirect`
 *   （u=(atan2(x,z)/π+1)/2, v=(1−asin(y)·2/π)/2,顶行为天顶）
 * 两式互为逆映射 ⇒ GPU 通道（观感基准）与本转换器逐像素同约定。
 *
 * 源图用 u/v 线性梯度编码（r=u·255, g=v·255）：双线性对线性函数采样是精确的
 * （水平环绕缝除外），断言可到 ±3。
 */
class EquirectToCubemapConverterTest {

    /** u/v 梯度源图:pixel(x,y) = (r=(x+0.5)/W·255, g=(y+0.5)/H·255)。 */
    private fun gradientSource(w: Int, h: Int): Pair<IntArray, Pair<Int, Int>> {
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            val r = ((x + 0.5) / w * 255.0).roundToInt()
            val g = ((y + 0.5) / h * 255.0).roundToInt()
            (r shl 16) or (g shl 8)
        }
        return pixels to (w to h)
    }

    /** 独立参考:面像素 (i,j) → equirect (u,v)。公式出处见类注释。 */
    private fun referenceUV(face: Int, i: Int, j: Int, n: Int): Pair<Double, Double> {
        val cx = 2.0 * (i + 0.5) / n - 1.0
        val cy = 1.0 - 2.0 * (j + 0.5) / n
        val (dx, dy, dz) = when (face) {
            0 -> Triple(1.0, cy, -cx)
            1 -> Triple(-1.0, cy, cx)
            2 -> Triple(cx, 1.0, -cy)
            3 -> Triple(cx, -1.0, cy)
            4 -> Triple(cx, cy, 1.0)
            else -> Triple(-cx, cy, -1.0)
        }
        val l = kotlin.math.sqrt(cx * cx + cy * cy + 1.0)
        val y = dy / l
        val u = (atan2(dx, dz) / Math.PI + 1.0) * 0.5
        val v = (1.0 - asin(y) * 2.0 / Math.PI) * 0.5
        return u to v
    }

    private fun channel(pixel: Int, shift: Int) = (pixel ushr shift) and 0xFF

    @Test
    fun `face centers map to canonical equirect coordinates`() {
        // N=9(奇数)使像素 (4,4) 精确落在面中心方向;72×36 与 9 面等密度(金字塔不介入侧面)
        val (pixels, size) = gradientSource(72, 36)
        val (w, h) = size
        val n = 9
        val faces = EquirectToCubemapConverter.convert(pixels, w, h, n)
        val mid = n / 2
        // 手推的面中心期望:PZ(0.5,0.5) PX(0.75,0.5) NX(0.25,0.5);PY/NY 中心 cosE=0,
        // 水平足迹→∞,层深封顶在密度点+2 → 采样第 2 层:r=条带内块均值恒 127.5,
        // g=顶部 4 行(PY)/底部 4 行(NY)均值 —— 极区取条带均值而非全图均值
        val expectedR = mapOf(0 to 191.25, 1 to 63.75, 2 to 127.5, 3 to 127.5, 4 to 127.5)
        for ((face, rExp) in expectedR) {
            val p = faces[face][mid * n + mid]
            assertEquals("face $face r", rExp, channel(p, 16).toDouble(), 2.0)
        }
        val g2 = channel(faces[2][mid * n + mid], 8).toDouble()
        val g3 = channel(faces[3][mid * n + mid], 8).toDouble()
        assertTrue("PY center g=$g2 should be top-band", g2 in 0.0..40.0)
        assertTrue("NY center g=$g3 should be bottom-band", g3 in 215.0..255.0)
    }

    @Test
    fun `pixel mapping matches GL cubemap convention reference on every side face`() {
        // 1:1 密度(256 宽 ↔ 64/面)⇒ 侧面(cosE≥0.707)金字塔不介入,逐像素对照点采样;
        // PY/NY 全面处于极区粗层,不适用点采样参照(中心行为已由上一用例覆盖)
        val (pixels, size) = gradientSource(256, 128)
        val (w, h) = size
        val n = 64
        val faces = EquirectToCubemapConverter.convert(pixels, w, h, n)
        for (face in intArrayOf(0, 1, 4, 5)) {
            for (j in 0 until n) {
                for (i in 0 until n) {
                    val (u, v) = referenceUV(face, i, j, n)
                    if (u < 0.02 || u > 0.98) continue // 环绕缝双线性混 255|0,梯度断言不适用
                    val p = faces[face][j * n + i]
                    assertEquals("face $face ($i,$j) r", u * 255.0, channel(p, 16).toDouble(), 2.0)
                    assertEquals("face $face ($i,$j) g", v * 255.0, channel(p, 8).toDouble(), 2.0)
                }
            }
        }
    }

    @Test
    fun `horizontal seam wraps continuously like REPEAT sampling`() {
        // N=63 奇数使 NZ 面中心像素方向精确为 (0,0,−1):u=1.0 → 采样点 x=W−0.5,
        // fx=0.5,环绕混末列(≈255)与首列(≈0) → ≈127.5(REPEAT 语义)
        val (pixels, size) = gradientSource(252, 126)
        val (w, h) = size
        val n = 63
        val faces = EquirectToCubemapConverter.convert(pixels, w, h, n)
        val p = faces[5][31 * n + 31]
        assertEquals(127.5, channel(p, 16).toDouble(), 5.0)
        assertEquals(127.5, channel(p, 8).toDouble(), 2.0)
    }

    @Test
    fun `zenith sampling picks coarse pyramid level so no checkerboard aliasing`() {
        // 顶部两行放黑白棋盘,其余中灰;天顶方向必须取到粗层(棋盘被盒式均值化)
        val w = 512
        val h = 256
        val pixels = IntArray(w * h) { idx ->
            val x = idx % w
            val y = idx / w
            if (y < 2 && x % 2 == 0) 0x000000 else if (y < 2) 0xFFFFFF else 0x808080
        }
        val n = 128
        val faces = EquirectToCubemapConverter.convert(pixels, w, h, n)
        // +Y 面中心区域(v≈0,极区):无金字塔会采到棋盘黑白极值
        for (j in 0 until n step 16) {
            for (i in 0 until n step 16) {
                val p = faces[2][j * n + i]
                val r = channel(p, 16)
                assertTrue("PY($i,$j) r=$r out of averaged band", r in 40..215)
            }
        }
    }

    @Test
    fun `output is opaque`() {
        val (pixels, size) = gradientSource(64, 32)
        val (w, h) = size
        val faces = EquirectToCubemapConverter.convert(pixels, w, h, 8)
        for (face in faces) {
            for (p in face) {
                assertEquals(0xFF, p ushr 24)
            }
        }
    }

    @Test
    fun `per-face path equals whole-convert path`() {
        val (pixels, size) = gradientSource(64, 32)
        val (w, h) = size
        val all = EquirectToCubemapConverter.convert(pixels, w, h, 16)
        val pyramid = EquirectToCubemapConverter.createPyramid(pixels, w, h)
        for (face in 0..5) {
            val single = EquirectToCubemapConverter.convertFace(pyramid, face, 16)
            assertTrue(single.contentEquals(all[face]))
        }
    }

    @Test
    fun `faceSizeFor aligns to source width and clamps`() {
        assertEquals(1024, EquirectToCubemapConverter.faceSizeFor(4096))
        assertEquals(1024, EquirectToCubemapConverter.faceSizeFor(8192))
        assertEquals(512, EquirectToCubemapConverter.faceSizeFor(2048))
        assertEquals(128, EquirectToCubemapConverter.faceSizeFor(512))
        assertEquals(128, EquirectToCubemapConverter.faceSizeFor(100))
    }
}
