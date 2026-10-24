package com.neethu.corelib.internal

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 天空盒管理器（单人 [SoulLinkRenderer] 与团播 Stage 共用）：360° 全景壁纸
 * （equirect 等距圆柱投影，约 2:1 的 JPG/PNG/WebP）→ Filament [Skybox]，
 * 替代纯色背景。
 *
 * 转换走自研 CPU 通道 [EquirectToCubemapConverter]（纯 Kotlin，与 filament
 * GPU 通道逐约定一致、观感相同）：**不能**用 filament-utils 的
 * IBLPrefilterContext.EquirectangularToCubemap——其原生实现把输出 cubemap 硬编码
 * 为 256×256/面且 Java 绑定无自定义尺寸入口，4096 宽的源会被压到 256/面再拉伸
 * 到整屏，天空盒模糊（坑 7）。本通道按源宽 1/4 输出（上限 [MAX_FACE_SIZE]）。
 *
 * 亮度补偿（坑 9，[SkyboxTexturePacker] 头注有完整推导）：filament 天空盒着色器
 * 会把采样值乘 `iblLuminance = IBL强度 × 相机曝光`（本项目 ≈ 0.34），LDR 全景
 * 会被压暗约 1.6 档。上传前预乘 `gain = [brightness] / envLuminance` 抵消，并改用
 * RGBA16F（线性值超 1.0，8-bit sRGB 装不下）。渲染端职责：构造后立即
 * [setEnvironmentLuminance]，IBL 强度/曝光变化后再调（触发按需重派生）；
 * [setBrightness] 是用户亮度旋钮（1.0 = 与原图逐像素一致）。
 *
 * 显存：1024/面 RGBA16F 单层 ≈ 50MB（天空盒 shader 只采样 LOD 0，无 mip 链；
 * 日后做「全景派生 IBL」时需另起带 mip 的通道）。
 *
 * 线程模型：公开方法必须在主线程调（与渲染器其余 API 同约定）。流程 =
 * 主线程做位图解码后处理（ARGB 化/限宽/快照 IntArray）→ 串行后台线程做
 * 金字塔构建 + 六面并行转换（纯数组运算，不碰引擎）→ 回主线程上传 cubemap、
 * 换装、销毁旧资源。全部状态变更走 epoch 计数：换新请求/纯色/销毁都会推进
 * epoch，落后的转换结果在落盘前被丢弃，不会互相覆盖或使用已销毁的引擎。
 *
 * 资源上限：源位图降采样到 ≤[MAX_SOURCE_WIDTH]；输出 cubemap 每面
 * [MIN_FACE_SIZE]~[MAX_FACE_SIZE]（1024/面 RGBA16F 单层 ≈ 50MB 显存）。
 * 位图快照成 IntArray 后立即回收，驻留的是不可变像素数组（yaw 重派生零拷贝解码）。
 *
 * 失败语义：解码/入参错误在主线程同步返回 false；GPU 上传异常保留旧背景
 * 不黑屏（log 后丢弃本次结果）。
 */
internal class SkyboxManager(
    private val engine: Engine,
    private val scene: Scene,
) {

    /** 驻留全景源（ARGB 像素，已 ≤[MAX_SOURCE_WIDTH]；纯色模式下为 null）。yaw 重派生用。 */
    private class Source(val pixels: IntArray, val width: Int, val height: Int)

    private var source: Source? = null

    /** 已受理未落位的全景源（转换在途）。yaw 在此期间到达也能正确作用于首图。 */
    private var pendingSource: Source? = null
    private var yawDeg = 0f
    private var skybox: Skybox? = null
    private var cubemap: Texture? = null

    /** 环境亮度（IBL 强度 lux × 相机曝光；渲染端在 applyIblSettings 后推送）。 */
    private var envLuminance = 1f

    /** 用户亮度倍率（1.0 = 与原图逐像素一致），见 [setBrightness]。 */
    var brightness: Float = 1f
        private set

    /** 最近一次入队转换所用的增益；NaN = 尚未入队过（首派生不做去重）。 */
    private var lastEnqueuedGain = Float.NaN

    /** 主线程状态版本号：任何状态变更（换图/调 yaw/纯色/销毁）+1，使在途转换结果失效。 */
    private var epoch = 0

    private var destroyed = false

    /** 转换编排线程（串行；六面转换再并行到专用池）。纯数组运算，不碰引擎。
     *  专用池而非 ForkJoinPool.commonPool：模型加载期步态提取等重活同抢公共池，
     *  曾观测到六面被拖成串行（22s/次），独占池隔离后 0.5s 内完成。 */
    private val conversionThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "skybox-converter").apply { isDaemon = true }
    }
    private val facePool = Executors.newFixedThreadPool(6) { r ->
        Thread(r, "skybox-face").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 全景天空盒是否已请求生效（调试命令 status 用；异步换装，最长滞后一次转换）。 */
    val hasPanorama: Boolean get() = source != null

    /** 当前全景的水平偏移角（度；纯色模式下无意义）。 */
    fun currentYaw(): Float = yawDeg

    /** 当前生效的亮度倍率（ai_cmd `skybox` 状态行用）。 */
    fun currentBrightness(): Float = brightness

    /**
     * 推送环境亮度（IBL 强度 lux × 相机曝光；有 IBL 时 View.cpp 用 IBL 强度做
     * 天空盒倍率）。全景激活且增益实际变化时自动重派生（同 yaw 的后台换装路径）。
     * 渲染端在构造后与每次 applyIblSettings 后调用。
     */
    fun setEnvironmentLuminance(luminance: Float) {
        if (destroyed) return
        envLuminance = luminance.coerceAtLeast(SkyboxTexturePacker.MIN_ENV_LUMINANCE)
        maybeReenqueueForGainChange()
    }

    /**
     * 用户亮度倍率（线性乘子）：1.0 = 显示与原图逐像素一致（默认）；>1 提亮，
     * <1 压暗。纯色模式下只记参数，下次全景生效。全景激活时返回 true（已重派生）。
     */
    fun setBrightness(factor: Float): Boolean {
        if (destroyed) return false
        brightness = factor.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        maybeReenqueueForGainChange()
        return hasPanorama
    }

    /** 增益（亮度倍率/环境亮度）实际变化才重派生——两条推送路径在同一次
     *  applyRenderSettings 里先后到达时，第二条按等值跳过。 */
    private fun maybeReenqueueForGainChange() {
        val gain = SkyboxTexturePacker.gainFor(brightness, envLuminance)
        val last = lastEnqueuedGain
        if (!last.isNaN() && kotlin.math.abs(gain - last) <= GAIN_EPSILON * maxOf(1f, kotlin.math.abs(last))) return
        val src = source ?: pendingSource ?: return
        val carryResident = source == null && pendingSource != null
        enqueue(src.pixels, src.width, src.height, yawDeg, newResident = if (carryResident) pendingSource else null)
    }

    /**
     * 纯色背景（初始态 / 清除态）。接管了原 setupSkybox 的
     * `Skybox.Builder().color(...)` 行为，同时释放全景资源与在途转换。
     */
    fun setColor(rgba: FloatArray) {
        if (destroyed) return
        val color = rgba.copyOf(4)
        epoch++
        source = null
        pendingSource = null
        yawDeg = 0f
        val box = Skybox.Builder()
            .color(color[0], color[1], color[2], color[3])
            .build(engine)
        val oldBox = skybox
        val oldCube = cubemap
        skybox = box
        cubemap = null
        scene.skybox = box
        // 先挂新再毁旧：旧 box/cube 在本帧前仍在 scene 上，先毁会有一帧采样已毁纹理
        oldBox?.let { engine.destroySkybox(it) }
        oldCube?.let { engine.destroyTexture(it) }
    }

    /**
     * 全景天空盒。[bitmap] 为等距圆柱投影全景；**管理器取得位图所有权**
     * （ARGB 化/限宽/快照像素后立即回收，调用方此后不得再碰它）。
     * 宽高比偏离 2:1 只告警不拒绝（拉伸畸变，调用方可自行裁）。
     *
     * 转换异步执行（主线程仅做位图后处理，约几十毫秒）；成功后下一帧起生效。
     *
     * @param yaw 水平循环偏移角（度）。正 = 原画面右侧的内容转到正前方
     *   （把全景绕竖轴向右转 |yaw| 度看）；实现 = 转换时像素循环横移 yaw/360 圈。
     * @return false = 入参无效（解码失败/图太小）或已销毁；true = 已受理。
     */
    fun setPanorama(bitmap: Bitmap, yaw: Float = 0f): Boolean {
        if (destroyed) {
            bitmap.recycle()
            return false
        }
        val prepared = try {
            fitSource(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "panorama prepare failed", e)
            null
        }
        if (prepared == null) return false
        if (prepared.width < 4 || prepared.height < 4) {
            Log.w(TAG, "panorama too small: ${prepared.width}x${prepared.height}")
            return false
        }
        val aspect = prepared.width.toFloat() / prepared.height
        if (aspect < 1.7f || aspect > 2.3f) {
            Log.w(TAG, "panorama aspect ${"%.2f".format(aspect)} not ~2:1 (${prepared.width}x${prepared.height}); expect distortion")
        }
        pendingSource = prepared
        enqueue(prepared.pixels, prepared.width, prepared.height, yaw, newResident = prepared)
        return true    }

    /** 运行时只调水平偏移（ai_cmd `skybox yaw <deg>`；全景模式下才有意义）。
     *  首图转换在途时同样受理（作用于在途首图，随其一起落位）。 */
    fun setYaw(yaw: Float): Boolean {
        if (destroyed) return false
        val src = source ?: pendingSource ?: return false
        val carryResident = source == null && pendingSource != null
        if (yaw == yawDeg && !carryResident) return true
        yawDeg = yaw
        enqueue(src.pixels, src.width, src.height, yaw, newResident = if (carryResident) pendingSource else null)
        return true
    }

    /**
     * 全部资源收尾。必须在引擎 shutdown 前调（引擎随 ModelViewer 的 detach
     * listener 销毁，之后任何 engine 调用都会崩——同两个渲染器 onDestroy
     * 护栏块的约束）。销毁后在途转换结果会被 epoch 丢弃，不会触引擎。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        epoch++
        source = null
        pendingSource = null
        val box = skybox
        val cube = cubemap
        skybox = null
        cubemap = null
        scene.skybox = null
        box?.let { engine.destroySkybox(it) }
        cube?.let { engine.destroyTexture(it) }
        conversionThread.shutdownNow()
        facePool.shutdownNow()
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /** 入队一次转换（主线程）：推进 epoch 使旧结果失效，转后台线程执行。
     *  增益在入队时定格（转换线程不读主线程状态）；环境亮度中途变化由
     *  [maybeReenqueueForGainChange] 追加一次更高 epoch 的重派生顶掉本次。 */
    private fun enqueue(pixels: IntArray, width: Int, height: Int, yaw: Float, newResident: Source?) {
        epoch++
        val myEpoch = epoch
        val faceSize = EquirectToCubemapConverter.faceSizeFor(width)
        val gain = SkyboxTexturePacker.gainFor(brightness, envLuminance)
        lastEnqueuedGain = gain
        conversionThread.execute {
            val startedAt = System.currentTimeMillis()
            val faces = try {
                convertFaces(pixels, width, height, yaw, faceSize)
            } catch (e: InterruptedException) {
                return@execute
            } catch (e: Exception) {
                Log.e(TAG, "equirect→cubemap conversion failed", e)
                return@execute
            }
            Log.i(TAG, "panorama converted ${width}x$height -> $faceSize/face in ${System.currentTimeMillis() - startedAt}ms")
            mainHandler.post { swapIn(faces, faceSize, yaw, myEpoch, newResident, gain) }
        }
    }

    /** 金字塔构建（串行）+ yaw 位移 + 六面转换（公共池并行）。在转换线程上执行。 */
    private fun convertFaces(pixels: IntArray, width: Int, height: Int, yaw: Float, faceSize: Int): Array<IntArray> {
        val shiftColumns = Math.round(width * (yaw / 360f))
        val shifted = shiftPixelsHorizontally(pixels, width, height, shiftColumns)
        val pyramid = EquirectToCubemapConverter.createPyramid(shifted, width, height)
        val pool = facePool
        Log.i(TAG, "convert: cores=${Runtime.getRuntime().availableProcessors()}")
        val jobs = (0..5).map { face ->
            Callable {
                val s = System.currentTimeMillis()
                val r = EquirectToCubemapConverter.convertFace(pyramid, face, faceSize)
                Log.i(TAG, "face $face: ${System.currentTimeMillis() - s}ms on ${Thread.currentThread().name}")
                r
            }
        }
        return pool.invokeAll(jobs).map { it.get() }.toTypedArray()
    }

    /**
     * 转换完成落盘（主线程）：epoch 已变 = 有更新状态（新图/纯色/销毁）插队，
     * 丢弃本次结果。成功路径内完成 cubemap 上传、换装与旧资源回收。
     */
    private fun swapIn(faces: Array<IntArray>, faceSize: Int, yaw: Float, myEpoch: Int, newResident: Source?, gain: Float) {
        if (destroyed || myEpoch != epoch) return
        val startedAt = System.currentTimeMillis()
        val cube = try {
            uploadCubemap(faces, faceSize, gain)
        } catch (e: Exception) {
            Log.e(TAG, "cubemap upload failed; keep old skybox", e)
            return
        }
        faces.fill(IntArray(0))
        val box = Skybox.Builder().environment(cube).build(engine)
        val oldBox = skybox
        val oldCube = cubemap
        if (newResident != null) {
            source = newResident
            pendingSource = null
        }
        yawDeg = yaw
        skybox = box
        cubemap = cube
        scene.skybox = box
        // 先挂新再毁旧：旧 box/cube 在本帧前仍在 scene 上，先毁会有一帧采样已毁纹理
        oldBox?.let { engine.destroySkybox(it) }
        oldCube?.let { engine.destroyTexture(it) }
        Log.i(TAG, "skybox swapped ($faceSize/face, gain=${"%.2f".format(gain)}) in ${System.currentTimeMillis() - startedAt}ms")
    }

    /**
     * 六面 ARGB → 线性化×增益 cubemap（RGBA16F 单层）。增益抵消着色器的
     * `iblLuminance` 乘子（[SkyboxTexturePacker] 头注）；无 mip 链——天空盒
     * shader 固定采样 `textureLod(…, 0.0)`，单层即完整（cubemap 完备性不受影响）。
     */
    private fun uploadCubemap(faces: Array<IntArray>, faceSize: Int, gain: Float): Texture {
        val texture = Texture.Builder()
            .width(faceSize)
            .height(faceSize)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.RGBA16F)
            .usage(Texture.Usage.DEFAULT)
            .build(engine)
        try {
            for (face in 0..5) {
                val buffer = ByteBuffer.allocateDirect(faceSize * faceSize * 8)
                SkyboxTexturePacker.packFaceLinearHalf(faces[face], gain, buffer)
                buffer.flip()
                texture.setImage(
                    engine, 0,
                    0, 0, face, faceSize, faceSize, 1,
                    Texture.PixelBufferDescriptor(
                        buffer,
                        Texture.Format.RGBA,
                        Texture.Type.HALF,
                        1, 0, 0, 0,
                        null, null,
                    ),
                )
            }
        } catch (e: Exception) {
            engine.destroyTexture(texture)
            throw e
        }
        return texture
    }

    /** 位图 → ARGB_8888 + 宽度 ≤[MAX_SOURCE_WIDTH] + 像素快照；位图即刻回收。
     *  返回 (pixels, width, height)，归管理器所有。 */
    private fun fitSource(bitmap: Bitmap): Source {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        }
        val target = if (argb.width > MAX_SOURCE_WIDTH) {
            argb.width.toDouble() / MAX_SOURCE_WIDTH
        } else {
            1.0
        }
        val scaled = if (target == 1.0) argb else Bitmap.createScaledBitmap(
            argb,
            (argb.width / target).toInt().coerceAtLeast(1),
            (argb.height / target).toInt().coerceAtLeast(1),
            true,
        ).also { if (it !== argb) argb.recycle() }
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        scaled.recycle()
        return Source(pixels, scaled.width, scaled.height)
    }

    companion object {
        private const val TAG = "SkyboxManager"

        /** 全景源位图宽度上限：cubemap 每面 = 宽/4，4096 → 1024/面（全 mip ≈ 33MB 显存）。 */
        internal const val MAX_SOURCE_WIDTH = 4096

        /** 输出 cubemap 每面下限（极小源图保底可用分辨率）。 */
        internal const val MIN_FACE_SIZE = 128

        /** 输出 cubemap 每面上限（再大只吃显存不涨观感）。 */
        internal const val MAX_FACE_SIZE = 1024

        /** [setBrightness] 允许范围（线性倍率；1.0 = 与原图一致）。 */
        internal const val MIN_BRIGHTNESS = 0.1f
        internal const val MAX_BRIGHTNESS = 4f

        /** 增益变化去重阈值（相对量；两条推送路径同帧到达时避免重复重派生）。 */
        private const val GAIN_EPSILON = 1e-3f
    }
}

/**
 * 整行循环横移 [pixels]（width×height 的像素矩阵）：[shiftColumns] > 0 =
 * 每行内容左移（右侧像素绕到左边）。纯数组运算，可单测。返回新数组；
 * shift 为 0 或整圈宽时原样返回。
 */
internal fun shiftPixelsHorizontally(pixels: IntArray, width: Int, height: Int, shiftColumns: Int): IntArray {
    val shift = ((shiftColumns % width) + width) % width
    if (shift == 0 || pixels.size != width * height) return pixels
    val out = IntArray(pixels.size)
    for (y in 0 until height) {
        val row = y * width
        System.arraycopy(pixels, row + shift, out, row, width - shift)
        System.arraycopy(pixels, row, out, row + width - shift, shift)
    }
    return out
}
