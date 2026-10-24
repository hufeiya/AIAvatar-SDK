package com.neethu.corelib.internal

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * equirect(等距圆柱投影)全景 → cubemap 的纯 Kotlin CPU 转换器(无 Android 依赖,
 * 可 JVM 单测)。
 *
 * 为什么不用 filament-utils 的 IBLPrefilterContext.EquirectangularToCubemap:其原生
 * 实现(libs/iblprefilter IBLPrefilterContext.cpp)把输出 cubemap 硬编码为 256×256/面,
 * Java 绑定没有暴露自定义尺寸的入口;4096 宽的源被压到 256/面再拉伸到 2000+ px 的
 * 屏幕,天空盒必然模糊。本转换器按源宽 1/4 输出(上限 [SkyboxManager.MAX_FACE_SIZE]),
 * 分辨率提升 4 倍。
 *
 * 方向约定与 GPU 通道逐像素一致(观感不变,只有清晰度差异),两处公式分别取自
 * filament 源码并互为逆映射(即 GL 硬件 cubemap 采样约定):
 * - 面像素 → 方向:libs/ibl Cubemap.h `getDirectionFor`(cmgen 写 cubemap 的同一张表)
 * - 方向 → equirect UV:libs/iblprefilter equirectToCube.mat `toEquirect`
 *   (u = (atan2(dir.x, dir.z)/π + 1)/2,顶行为天顶;v = (1 − asin(dir.y)·2/π)/2)
 *
 * 采样:源图建 box 降采样金字塔,按每像素水平足迹选层(equirect 在近极区横向压缩,
 * 足迹被 1/cosE 放大;不选层会噪点,对应 GPU 通道的 trilinear+各向异性 16),层内
 * 双线性,水平环绕(与 GPU REPEAT wrap 一致)、垂直钳制。滤波在 sRGB 空间做(GPU
 * 通道在线性空间,自然图像上差异不可感知);输出保持 sRGB 编码,上传 SRGB8_A8 由
 * 硬件解码,最终进 shader 的线性值与 GPU 通道等价。
 */
internal object EquirectToCubemapConverter {

    /** 一层源图:ARGB IntArray(getPixels 语义)+ 尺寸。 */
    internal class Level(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * 源图金字塔:level 0 = 原图,每层 2×2 box 降采样(边缘 2 或 1 求均)。
     * 一次构建、六面共用;构建代价 ≈ 源像素数的 4/3 次读取。
     */
    internal fun createPyramid(pixels: IntArray, width: Int, height: Int): List<Level> {
        require(pixels.size == width * height) { "pixels size ${pixels.size} != $width*$height" }
        require(width >= 2 && height >= 1) { "source too small: ${width}x$height" }
        val levels = ArrayList<Level>(13)
        levels.add(Level(pixels, width, height))
        while (levels.size < 12) {
            val prev = levels.last()
            if (prev.width == 1 && prev.height == 1) break
            levels.add(boxDownsample(prev))
        }
        return levels
    }

    /**
     * 顺序转换全部 6 面(单测/小图用;生产走 [convertFace] 并行)。
     * 返回面序:+X, −X, +Y, −Y, +Z, −Z(filament CubemapFace ordinal)。
     */
    fun convert(pixels: IntArray, width: Int, height: Int, faceSize: Int): Array<IntArray> {
        val pyramid = createPyramid(pixels, width, height)
        return Array(6) { face -> convertFace(pyramid, face, faceSize) }
    }

    /** 转换单个面(可跨线程并行:只读金字塔)。 */
    internal fun convertFace(pyramid: List<Level>, face: Int, faceSize: Int): IntArray {
        require(face in 0..5) { "face $face out of range" }
        require(faceSize >= 1) { "faceSize $faceSize too small" }
        val out = IntArray(faceSize * faceSize)
        val src = pyramid[0]
        // 层深封顶:密度点(源宽/4 ≈ 每面像素密度)再往下 2 层为止。极区水平足迹
        // →∞(equirect 方位角压缩),各向同性金字塔若不封顶会一路取到全图均值,
        // 色调漂移;封顶后极区≈顶部条带均值,是环向均值的合理近似(GPU mip 链同病)。
        val maxLevel = run {
            val density = log2(src.width / (4.0 * faceSize))
            (ceil(density).toInt() + 2).coerceIn(0, pyramid.size - 1)
        }
        // 每列预计算 cx(面内切空间 x ∈ [-1,1],像素中心)
        val cx = DoubleArray(faceSize)
        for (i in 0 until faceSize) cx[i] = 2.0 * (i + 0.5) / faceSize - 1.0
        for (j in 0 until faceSize) {
            val cy = 1.0 - 2.0 * (j + 0.5) / faceSize
            val row = j * faceSize
            for (i in 0 until faceSize) {
                // 面像素 → 方向(cmgen Cubemap.h getDirectionFor,未归一)
                val dx: Double; val dy: Double; val dz: Double
                when (face) {
                    0 -> { dx = 1.0; dy = cy; dz = -cx[i] }          // +X
                    1 -> { dx = -1.0; dy = cy; dz = cx[i] }          // -X
                    2 -> { dx = cx[i]; dy = 1.0; dz = -cy }          // +Y
                    3 -> { dx = cx[i]; dy = -1.0; dz = cy }          // -Y
                    4 -> { dx = cx[i]; dy = cy; dz = 1.0 }           // +Z
                    else -> { dx = -cx[i]; dy = cy; dz = -1.0 }      // -Z
                }
                val l = sqrt(1.0 + cx[i] * cx[i] + cy * cy)
                val dirY = dy / l
                // 方向 → equirect UV(equirectToCube.mat toEquirect;atan2 对齐 GLSL atan(y,x))
                val u = (atan2(dx, dz) / Math.PI + 1.0) * 0.5
                val v = (1.0 - asin(dirY.coerceIn(-1.0, 1.0)) * (2.0 / Math.PI)) * 0.5
                // 水平足迹(源像素数):面像素方位角跨度 (π/2)/N/cosE × 源每弧度像素 W/2π
                val cosE = max(sqrt(max(0.0, 1.0 - dirY * dirY)), 1e-3)
                val lf = log2(src.width / (8.0 * faceSize * cosE))
                val l0 = floor(lf).toInt().coerceIn(0, maxLevel)
                val l1 = min(l0 + 1, maxLevel)
                val t = (lf - l0).coerceIn(0.0, 1.0)
                val c0 = sampleBilinear(pyramid[l0], u, v)
                val c = if (t > 0.0 && l1 != l0) {
                    val c1 = sampleBilinear(pyramid[l1], u, v)
                    blend(c0, c1, t)
                } else c0
                out[row + i] = c or OPAQUE_ALPHA
            }
        }
        return out
    }

    /** 双线性采样:u/v ∈ [0,1];水平环绕(REPEAT)、垂直钳制。 */
    private fun sampleBilinear(level: Level, u: Double, v: Double): Int {
        val w = level.width
        val h = level.height
        val x = u * w - 0.5
        // y 先钳到 [0, h-1] 再取整,保证 fy ∈ [0,1) 不外推(极区 v≈0/1 常踩)
        val y = (v.coerceIn(0.0, 1.0) * h - 0.5).coerceIn(0.0, (h - 1).toDouble())
        var i0 = floor(x).toInt()
        val fx = x - i0
        i0 = ((i0 % w) + w) % w
        val i1 = (i0 + 1) % w
        val j0 = floor(y).toInt()
        val fy = y - j0
        val j1 = min(j0 + 1, h - 1)
        val p00 = level.pixels[j0 * w + i0]
        val p10 = level.pixels[j0 * w + i1]
        val p01 = level.pixels[j1 * w + i0]
        val p11 = level.pixels[j1 * w + i1]
        val r = bilerpChannel(p00, p10, p01, p11, fx, fy) { (it ushr 16) and 0xFF }
        val g = bilerpChannel(p00, p10, p01, p11, fx, fy) { (it ushr 8) and 0xFF }
        val b = bilerpChannel(p00, p10, p01, p11, fx, fy) { it and 0xFF }
        return (r shl 16) or (g shl 8) or b
    }

    private inline fun bilerpChannel(
        p00: Int, p10: Int, p01: Int, p11: Int,
        fx: Double, fy: Double, channel: (Int) -> Int,
    ): Int {
        val top = channel(p00) + (channel(p10) - channel(p00)) * fx
        val bottom = channel(p01) + (channel(p11) - channel(p01)) * fx
        return (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
    }

    private fun blend(c0: Int, c1: Int, t: Double): Int {
        val r = blendChannel(c0, c1, t) { (it ushr 16) and 0xFF }
        val g = blendChannel(c0, c1, t) { (it ushr 8) and 0xFF }
        val b = blendChannel(c0, c1, t) { it and 0xFF }
        return (r shl 16) or (g shl 8) or b
    }

    private inline fun blendChannel(c0: Int, c1: Int, t: Double, channel: (Int) -> Int): Int =
        (channel(c0) + (channel(c1) - channel(c0)) * t).toInt().coerceIn(0, 255)

    private fun boxDownsample(prev: Level): Level {
        // ceil 分块:奇数尺寸的末列/末行也进块(单 texel 块原样),整体均值无偏
        // (floor 丢弃会让极区粗层色调漂移);尺寸只在此处定,避免与 Level 标注错位
        val w = max(1, (prev.width + 1) shr 1)
        val h = max(1, (prev.height + 1) shr 1)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val x0 = min(2 * x, prev.width - 1)
                val x1 = min(2 * x + 1, prev.width - 1)
                val y0 = min(2 * y, prev.height - 1)
                val y1 = min(2 * y + 1, prev.height - 1)
                val a = prev.pixels[y0 * prev.width + x0]
                val b = prev.pixels[y0 * prev.width + x1]
                val c = prev.pixels[y1 * prev.width + x0]
                val d = prev.pixels[y1 * prev.width + x1]
                val r = ((a ushr 16 and 0xFF) + (b ushr 16 and 0xFF) + (c ushr 16 and 0xFF) + (d ushr 16 and 0xFF) + 2) shr 2
                val g = ((a ushr 8 and 0xFF) + (b ushr 8 and 0xFF) + (c ushr 8 and 0xFF) + (d ushr 8 and 0xFF) + 2) shr 2
                val bl = ((a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF) + 2) shr 2
                out[y * w + x] = (r shl 16) or (g shl 8) or bl
            }
        }
        return Level(out, w, h)
    }

    /** 输出强制不透明(天空盒背景不吃 alpha,与 GPU 通道 SRGB8_A8 纹理同观感)。 */
    private const val OPAQUE_ALPHA = 0xFF shl 24

    /**
     * 面尺寸自适应:源宽的 1/4,夹到 [SkyboxManager.MIN_FACE_SIZE, SkyboxManager.MAX_FACE_SIZE]
     * (equirect 每面 90° 视场,宽/4 即源像素密度对齐;上限防显存)。
     */
    internal fun faceSizeFor(sourceWidth: Int): Int =
        ceil(sourceWidth / 4.0).toInt().coerceIn(SkyboxManager.MIN_FACE_SIZE, SkyboxManager.MAX_FACE_SIZE)
}
