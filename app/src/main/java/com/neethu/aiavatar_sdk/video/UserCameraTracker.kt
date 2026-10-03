package com.neethu.aiavatar_sdk.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.neethu.orchestrator.face.FacePointProjector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * 视频模式的用户相机引擎：CameraX [ImageAnalysis]（480×640，
 * KEEP_ONLY_LATEST）+ ML Kit 人脸检测（BlazeFace 快速模式，bundled 模型）。
 *
 * 每帧产出两路：
 *  1. **注视观测**（人脸中心 nx/ny + 面积占比）→ [pollGazeWorld] 由主线程
 *     ~30Hz 消费：经 [FacePointProjector]（One-Euro 平滑 + 世界投影）变成
 *     世界注视点，喂 FaceDriver 的 POINT 缝。归一化约定见投影器 doc——
 *     前置摄像头原始帧未镜像（脸在屏幕右侧时落在画面左侧），nx 在这里
 *     翻转成"世界对齐"（+1=屏幕右）；竖直不镜像。
 *  2. **抓拍**：每 [SNAPSHOT_INTERVAL_MS] 一帧转 512×512 JPEG(80)，
 *     压入 [ring]（深 3）——ASR 松手/打字发送时取 [snapshotDataUrl]（最
 *     清晰一帧）组装多模态请求。抓拍与脸检测解耦：没人脸也持续缓存
 *     （"看看房间里有什么"同样要图）。
 *
 * 生命周期：进出视频模式配对 [start]/[stop]；[switchLens] 重绑前后摄。
 * ML Kit 检测器随 start/stop 重建/释放。线程：相机绑定在主线程（CameraX
 * 要求），分析回调解在单线程 executor。
 */
class UserCameraTracker(private val context: android.content.Context) {

    /** 一帧人脸观测：世界对齐 nx（+1=屏幕右）、ny（+1=画面下缘）、面积占比。 */
    data class FaceObservation(
        val nx: Float,
        val ny: Float,
        val area: Float,
        val atMs: Long,
    )

    val ring = SnapshotRingBuffer(capacity = 3)
    private val projector = FacePointProjector()

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "video-analysis") }
    private var provider: ProcessCameraProvider? = null
    private var detector: com.google.mlkit.vision.face.FaceDetector? = null
    private var previewView: PreviewView? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_FRONT
    private var boundLens: Int = 0
    private var analysis: ImageAnalysis? = null
    private var lifecycleOwner: LifecycleOwner? = null

    /** 最近一次 bind 是否包含了 Preview 用例（黑屏守卫的判据）。 */
    private var previewBound: Boolean = false

    /** 最近一次检测命中的人脸观测（分析线程写，主线程读）。 */
    @Volatile
    var latestFace: FaceObservation? = null
        private set

    private var lastSnapshotMs = 0L
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    val isActive: Boolean get() = provider != null
    val currentLensFront: Boolean get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    /** 人脸观测距今的毫秒数；从未见过返回 Long.MAX_VALUE。 */
    fun faceAgeMs(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        latestFace?.let { nowMs - it.atMs } ?: Long.MAX_VALUE

    /**
     * PiP 预览面。**必须在挂上时检查重绑**：组合时序上 `start()` 可能先于
     * PreviewView 挂载执行（PiP 首帧容器尺寸未定、AndroidView 尚未创建），
     * 那次 bind 只带了分析流 → PiP 永远黑屏；此时补一次带 Preview 的强制
     * 重绑。传 [owner] 便于这路重绑。
     */
    fun attachPreview(view: PreviewView, owner: LifecycleOwner? = null) {
        previewView = view
        owner?.let { lifecycleOwner = it }
        if (provider != null && !previewBound) {
            val o = lifecycleOwner
            if (o != null) {
                Log.i(TAG, "preview attached after bind — rebinding with preview")
                start(o, lensFacing, force = true)
            }
        }
    }

    fun detachPreview() {
        previewView = null
    }

    /**
     * 绑定相机并开始分析。已激活时：镜头一致且预览状态一致 → no-op，否则
     * （[switchLens] / 补绑 Preview / [force]）解绑重绑。必须在主线程调用。
     */
    @SuppressLint("RestrictedApi")
    fun start(owner: LifecycleOwner, lens: Int = lensFacing, force: Boolean = false) {
        lensFacing = lens
        lifecycleOwner = owner
        if (!force && isActive && boundLens == lensFacing && previewBound == (previewView != null)) return

        val newProvider = ProcessCameraProvider.getInstance(context).get()
        newProvider.unbindAll()
        detector?.close()
        detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.15f)
                .build()
        )

        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(android.util.Size(480, 640), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        val newAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        newAnalysis.setAnalyzer(executor, ::analyzeFrame)

        val useCases = mutableListOf<UseCase>(newAnalysis)
        previewView?.let { pv ->
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(pv.surfaceProvider)
            useCases += preview
        }
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        newProvider.bindToLifecycle(owner, selector, *useCases.toTypedArray())

        provider = newProvider
        analysis = newAnalysis
        boundLens = lensFacing
        previewBound = useCases.any { it is Preview }
        Log.i(
            TAG,
            "camera bound: lens=${if (currentLensFront) "front" else "back"} " +
                "preview=$previewBound analysis=on",
        )
    }

    fun stop() {
        provider?.unbindAll()
        provider = null
        analysis?.clearAnalyzer()
        analysis = null
        boundLens = 0
        previewBound = false
        detector?.close()
        detector = null
        latestFace = null
        busy.set(false)
        Log.i(TAG, "camera released")
    }

    /** 前后摄切换（重绑）。 */
    fun switchLens(owner: LifecycleOwner) {
        val next =
            if (lensFacing == CameraSelector.LENS_FACING_FRONT) CameraSelector.LENS_FACING_BACK
            else CameraSelector.LENS_FACING_FRONT
        start(owner, next)
    }

    /**
     * 主线程 ~30Hz 消费：有新的人脸观测就投影成世界注视点（内部 One-Euro
     * 平滑），返回 null 表示本周期无新观测。face lost 的回退（超时切回
     * CAMERA 看镜头）由调用方按 [faceAgeMs] 决定。
     */
    fun pollGazeWorld(
        cameraPose: Triple<FloatArray, FloatArray, FloatArray>?,
        deltaSeconds: Float,
    ): FloatArray? {
        val face = latestFace ?: return null
        if (face === lastProjected) return null
        lastProjected = face
        val (eye, target, up) = cameraPose ?: return null
        return projector.project(face.nx, face.ny, face.area, eye, target, up, deltaSeconds)
    }

    @Volatile
    private var lastProjected: FaceObservation? = null

    /** 发送时刻取帧：缓存窗内最清晰一帧的 data URL；无缓存返回 null。 */
    fun snapshotDataUrl(): String? = ring.best()?.dataUrl

    /** ai_cmd 观测行。 */
    fun debugStatus(): String {
        val face = latestFace
        val faceStr = face
            ?.let { "face=(%.2f,%.2f) area=%.2f age=%dms".format(it.nx, it.ny, it.area, faceAgeMs()) }
            ?: "face=none"
        val best = ring.best()
        return "active=$isActive lens=${if (currentLensFront) "front" else "back"} $faceStr " +
            "ring=${ring.size()} best=" +
            (best?.let { "${it.byteCount}B sharpness=${"%.1f".format(it.sharpness)}" } ?: "none")
    }

    // ── 分析管线（analysis executor 线程）────────────────────────────────

    private fun analyzeFrame(proxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        val d = detector
        if (mediaImage == null || d == null) {
            proxy.close()
            busy.set(false)
            return
        }
        try {
            val rotation = proxy.imageInfo.rotationDegrees
            val now = SystemClock.elapsedRealtime()
            val snapDue = now - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS
            // 抓拍位图必须在 proxy 关闭前取（toBitmap 复制像素）
            val snapBitmap = if (snapDue) {
                runCatching { rotatedUpright(proxy, rotation) }.getOrNull()
            } else {
                null
            }
            if (snapBitmap != null) lastSnapshotMs = now

            val w = proxy.width
            val h = proxy.height
            val front = lensFacing == CameraSelector.LENS_FACING_FRONT
            val input = InputImage.fromMediaImage(mediaImage, rotation)
            d.process(input)
                .addOnSuccessListener { faces ->
                    val biggest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    if (biggest != null) {
                        val box = biggest.boundingBox
                        // ⚠️ boundingBox 是旋转后直立系的——归一化必须用直立系
                        // 尺寸（FaceFrameMath.normalize），用 proxy 缓冲尺寸会把
                        // 居中的脸算出恒定右下偏置（真机实录，见该类 doc）
                        val nf = FaceFrameMath.normalize(
                            centerX = box.centerX().toFloat(),
                            centerY = box.centerY().toFloat(),
                            boxW = box.width().toFloat(),
                            boxH = box.height().toFloat(),
                            bufferW = w,
                            bufferH = h,
                            rotationDegrees = rotation,
                            frontCamera = front,
                        )
                        latestFace = FaceObservation(
                            nx = nf.nx,
                            ny = nf.ny,
                            area = nf.area,
                            atMs = SystemClock.elapsedRealtime(),
                        )
                    }
                    // 无人脸：保留旧观测（faceAgeMs 自然增长，调用方超时回退）
                }
                .addOnFailureListener { t ->
                    Log.w(TAG, "face detect failed: ${t.message}")
                }
                // ML Kit 在 Task 完成前异步读 mediaImage 的 buffer —— proxy 的
                // close（与 busy 复位）统一挂在 complete（默认主线程回调，close 线程安全）
                .addOnCompleteListener {
                    if (snapBitmap != null) executor.execute { encodeSnapshot(snapBitmap) }
                    proxy.close()
                    busy.set(false)
                }
        } catch (t: Throwable) {
            Log.w(TAG, "analyze error: ${t.message}")
            runCatching { proxy.close() }
            busy.set(false)
        }
    }

    /** 缩放/旋转成直立位图（抓拍与检测共用同一旋转语义）。 */
    private fun rotatedUpright(proxy: ImageProxy, rotationDegrees: Int): Bitmap {
        val src = proxy.toBitmap()
        val m = Matrix()
        m.postRotate(rotationDegrees.toFloat())
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
        return if (rotated != src) src.recycle().let { rotated } else rotated
    }

    /** 512×512 中心裁剪 JPEG(80) → 清晰度 → 环形缓存。 */
    private fun encodeSnapshot(upright: Bitmap) {
        try {
            val side = minOf(upright.width, upright.height)
            val cropX = (upright.width - side) / 2
            val cropY = (upright.height - side) / 2
            val square = Bitmap.createBitmap(upright, cropX, cropY, side, side)
            val snap = Bitmap.createScaledBitmap(square, SNAPSHOT_SIZE, SNAPSHOT_SIZE, true)
            if (snap != square) square.recycle()
            val bytes = ByteArrayOutputStream(SNAPSHOT_SIZE * SNAPSHOT_SIZE / 4).use { buf ->
                snap.compress(Bitmap.CompressFormat.JPEG, SNAPSHOT_JPEG_QUALITY, buf)
                buf.toByteArray()
            }
            val small = Bitmap.createScaledBitmap(snap, 64, 64, true)
            val pixels = IntArray(64 * 64)
            small.getPixels(pixels, 0, 64, 0, 0, 64, 64)
            val luma = IntArray(pixels.size) { i ->
                val p = pixels[i]
                (p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29 shr 8
            }
            if (small != snap) small.recycle()
            val sharpness = FrameQuality.laplacianVariance(luma, 64, 64)
            ring.push(
                SnapshotFrame(
                    dataUrl = "data:image/jpeg;base64," +
                        Base64.encodeToString(bytes, Base64.NO_WRAP),
                    byteCount = bytes.size,
                    sharpness = sharpness,
                    atMs = SystemClock.elapsedRealtime(),
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "snapshot encode failed: ${t.message}")
        } finally {
            upright.recycle()
        }
    }

    companion object {
        private const val TAG = "VideoTracker"
        private const val SNAPSHOT_INTERVAL_MS = 500L
        private const val SNAPSHOT_SIZE = 512
        private const val SNAPSHOT_JPEG_QUALITY = 80
    }
}
