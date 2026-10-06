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
import com.neethu.corelib.MimicPose
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
 *  3. **手势识别**（猜拳 P2）：[gestureEnabled] 为真时（技能激活）在前摄
 *     分析帧上跑 MediaPipe GestureRecognizer（bundled ~8MB，IMAGE 模式
 *     同步 10-20ms），结果经 [GestureStabilityGate] 确认后投 [onGestureConfirmed]
 *     到主线程 → 技能缝。关着时零开销（不建引擎不转位图）。
 *  4. **头部姿态**（「看这边」技能）：[headPoseEnabled] 为真时（技能激活）
 *     跑 MediaPipe FaceLandmarker（~3.7MB，IMAGE 模式同步 10-30ms），
 *     facialTransformationMatrix 分解出 yaw/pitch（度）经 [onHeadPose] 投
 *     主线程 → 技能缝，最近一帧存 [latestHeadPose]（face_pose 调试观测）。
 *  5. **身体模仿**（「模仿我」技能）：[bodyMimicEnabled] 为真时跑 MediaPipe
 *     PoseLandmarker（~5.5MB，IMAGE 同步 15-30ms），世界关键点经
 *     PoseMimicMath 镜像解算 + MimicDirectionFilter 滤波成 [latestMimicPose]
 *     （虚拟人世界系方向集，主线程 ticker 消费直驱渲染引擎——不经技能层）；
 *     人形可见性走 [latestBodyVisible]。三条 MediaPipe 车道互斥（同一分析
 *     线程不并跑），关=零开销。
 *  6. **表情/精确头部**（「模仿我」P2）：[faceMimicEnabled] 且身体车道活跃时
 *     **隔帧**跑 FaceLandmarker（blendshapes 开启的独立实例），52 ARKit
 *     blendshapes → [latestMimicFace]（主线程喂 FaceDriver 表情通道），
 *     变换矩阵在保鲜窗内替代鼻-耳估算作头部朝向（±2° 级精解）。
 *
 * 生命周期：进出视频模式配对 [start]/[stop]；[switchLens] 重绑前后摄。
 * ML Kit 检测器随 start/stop 重建/释放；手势/姿态引擎懒创建、stop 时经分析
 * executor 串行释放（与识别调用同队列，杜绝并发 close 原生实例）。
 * 线程：相机绑定在主线程（CameraX 要求），分析回调解在单线程 executor。
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

    // ── 手势识别车道（猜拳 P2）───────────────────────────────────────────
    // 开关/回调主线程写、分析线程读（@Volatile 保证可见性）；引擎与门控状态
    // 只在分析线程触碰（创建/识别/释放都在 executor 队列里串行）。

    /** 技能要手势吗（app 接 rpsSkill.isActive）：关=整条车道零开销。 */
    @Volatile
    var gestureEnabled: () -> Boolean = { false }

    /** 确认手势回调（主线程投递）：1=石头,2=剪刀,3=布；0 永不发出。 */
    @Volatile
    var onGestureConfirmed: ((Int) -> Unit)? = null

    private var gestureEngine: HandGestureRecognizer? = null
    private val gestureGate = GestureStabilityGate()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // ── 头部姿态车道（「看这边」技能）────────────────────────────────────
    // 线程约定与手势车道相同；判定窗/稳定性门在技能层（LookHereJudge），
    // 这里只逐帧上报原始姿态。

    /** 一帧头部姿态（相机系欧拉角，度；屏幕方向映射在技能层标定）。 */
    data class HeadPoseSample(val yawDeg: Float, val pitchDeg: Float, val atMs: Long)

    /** 技能要头部姿态吗（app 接 lookHereSkill.isActive）：关=整条车道零开销。 */
    @Volatile
    var headPoseEnabled: () -> Boolean = { false }

    /** 头部姿态回调（主线程投递，度）。 */
    @Volatile
    var onHeadPose: ((Float, Float) -> Unit)? = null

    /** 最近一次头部姿态（分析线程写，ai_cmd face_pose 主线程读）。 */
    @Volatile
    var latestHeadPose: HeadPoseSample? = null
        private set

    private var poseEngine: FaceLandmarkerEngine? = null

    // ── 身体模仿车道（「模仿我」技能）────────────────────────────────────
    // 线程约定与手势/头部姿态车道相同；方向解算/镜像/滤波在这里做（PoseMimicMath
    // + MimicDirectionFilter），引擎消费的 MimicPose 已经是虚拟人世界系方向集。

    /** 技能要身体姿态吗（app 接 mimicSkill.isActive）：关=整条车道零开销。 */
    @Volatile
    var bodyMimicEnabled: () -> Boolean = { false }

    /**
     * 最近一帧模仿目标（虚拟人世界系方向集，滤波后；分析线程写，主线程
     * ticker 消费 → controller.setMimicPose）。人形质量不足时为 visible=false
     * 的保活 ping；人整个不在时停在旧帧（引擎按新鲜度自愈还原）。
     */
    @Volatile
    var latestMimicPose: MimicPose? = null
        private set

    /** 最近一帧人形可见性（ticker 节流后经 skills.onBodyTracking 喂技能层）。 */
    @Volatile
    var latestBodyVisible: Boolean = false
        private set

    private var bodyEngine: PoseLandmarkerEngine? = null
    private val mimicFilter = MimicDirectionFilter()

    // ── 表情/精确头部车道（「模仿我」P2，与身体车道穿插）─────────────────
    // 身体每帧（80ms）、表情隔帧（160ms）——同一分析线程串行两个 MediaPipe
    // 任务（PoseLandmarker + FaceLandmarker）。低成本机跳脸：表情慢变量，
    // 丢几帧无感，身体方向才是主通道。

    /** 表情车道开关（app 接 mimicFaceEnabled；仅身体车道活跃时被咨询）。 */
    @Volatile
    var faceMimicEnabled: () -> Boolean = { false }

    /** 一帧表情观测：头部变换矩阵（列主序 4×4，可空）+ ARKit blendshapes。 */
    class MimicFaceFrame(
        val matrix: FloatArray?,
        val shapes: Map<String, Float>,
        val atMs: Long,
    )

    /** 最近一帧表情观测（分析线程写，主线程 ticker 消费喂 FaceDriver）。 */
    @Volatile
    var latestMimicFace: MimicFaceFrame? = null
        private set

    private var faceMimicEngine: FaceLandmarkerEngine? = null

    /** 懒建表情引擎（blendshapes 开启的 FaceLandmarker 实例，与看这边的实例分开）。 */
    private fun obtainFaceMimicEngine(): FaceLandmarkerEngine? {
        faceMimicEngine?.let { return it }
        return runCatching { FaceLandmarkerEngine(context, blendshapes = true).also { faceMimicEngine = it } }
            .onFailure { Log.w(TAG, "face mimic landmarker init failed: ${it.message}") }
            .getOrNull()
    }

    /** 表情矩阵有效期内头部朝向用它（防双源切换抖动）；断供后回落鼻-耳。 */
    @Volatile
    private var faceHeadUntilMs = 0L

    private var lastSnapshotMs = 0L
    private var lastDetectMs = 0L
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
        // 重绑（换镜头/补 Preview/进出视频模式）后门控从干净态起步，别让上一段
        // 会话遗留的「未重新武装」吞掉本段的第一个手势
        gestureGate.reset()
        // 模仿车道同理：滤波状态/旧目标随重绑作废（镜头换了坐标系没变，但
        // 目标断流期间的旧方向不该续上）
        mimicFilter.reset()
        latestMimicPose = null
        latestBodyVisible = false
        faceHeadUntilMs = 0L
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
        // 手势/姿态/身体引擎在分析线程释放：与可能还在跑的 recognize()/detect()
        // 同队列串行，杜绝主线程 close 撞上原生实例并发调用
        executor.execute {
            gestureEngine?.close()
            gestureEngine = null
            poseEngine?.close()
            poseEngine = null
            bodyEngine?.close()
            bodyEngine = null
            faceMimicEngine?.close()
            faceMimicEngine = null
        }
        latestMimicPose = null
        latestBodyVisible = false
        latestMimicFace = null
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

    /** 最新一帧（不管清晰度）：「看这边」判负瞬间的懵逼表情要新鲜度。 */
    fun snapshotLatestDataUrl(): String? = ring.newest()?.dataUrl

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

    /** 懒建手势引擎（只在分析线程触碰；模型缺失/初始化失败=手势车道静默停用）。 */
    private fun obtainGestureEngine(): HandGestureRecognizer? {
        gestureEngine?.let { return it }
        return runCatching { HandGestureRecognizer(context).also { gestureEngine = it } }
            .onFailure { Log.w(TAG, "gesture recognizer init failed: ${it.message}") }
            .getOrNull()
    }

    /** 懒建姿态引擎（同手势引擎的线程与静默降级约定）。 */
    private fun obtainPoseEngine(): FaceLandmarkerEngine? {
        poseEngine?.let { return it }
        return runCatching { FaceLandmarkerEngine(context).also { poseEngine = it } }
            .onFailure { Log.w(TAG, "face landmarker init failed: ${it.message}") }
            .getOrNull()
    }

    /** 懒建身体引擎（「模仿我」；模型缺失/初始化失败=车道静默停用）。 */
    private fun obtainBodyEngine(): PoseLandmarkerEngine? {
        bodyEngine?.let { return it }
        return runCatching { PoseLandmarkerEngine(context).also { bodyEngine = it } }
            .onFailure { Log.w(TAG, "pose landmarker init failed: ${it.message}") }
            .getOrNull()
    }

    // ── 分析管线（analysis executor 线程）────────────────────────────────

    private fun analyzeFrame(proxy: ImageProxy) {
        // 帧级门:检测 ~12.5fps 足够注视追踪(One-Euro 平滑兜着),既省 CPU 又把
        // ML Kit 原生层的逐帧 V 级日志(FaceDetectorV2Jni,应用无法关)压掉 ~60%
        val now = SystemClock.elapsedRealtime()
        val detectDue = now - lastDetectMs >= DETECT_INTERVAL_MS
        val snapDue = now - lastSnapshotMs >= SNAPSHOT_INTERVAL_MS
        if (!detectDue && !snapDue) {
            proxy.close()
            return
        }
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
            // 检测车道只在前摄+技能激活的分析帧上跑（关=零开销）；两条
            // MediaPipe 车道互斥（同一分析线程不并跑），激活技能方优先
            val wantPose = detectDue && lensFacing == CameraSelector.LENS_FACING_FRONT &&
                headPoseEnabled()
            val wantGesture = detectDue && lensFacing == CameraSelector.LENS_FACING_FRONT &&
                gestureEnabled() && !wantPose
            val wantBody = detectDue && lensFacing == CameraSelector.LENS_FACING_FRONT &&
                bodyMimicEnabled() && !wantPose && !wantGesture
            // 表情隔帧穿插（时间槽奇偶），身体是主通道
            val wantFaceMimic = wantBody && faceMimicEnabled() &&
                (now / DETECT_INTERVAL_MS) % 2L == 0L
            val snapBitmap = if (snapDue) {
                runCatching { rotatedUpright(proxy, rotation) }.getOrNull()
            } else {
                null
            }
            if (snapBitmap != null) lastSnapshotMs = now

            if (!detectDue) {
                // 只差抓拍的帧:不进 ML Kit(每次调用都会打一对原生 V 级日志)
                if (snapBitmap != null) executor.execute { encodeSnapshot(snapBitmap) }
                proxy.close()
                busy.set(false)
                return
            }
            lastDetectMs = now

            // 手势识别与人脸检测同 80ms 节奏：ML Kit 走异步 Task，这里同步
            // 10-20ms，同一 executor 串行不互抢。抓拍帧位图已转好就直接复用，
            // 省一次 YUV→RGB（复用时所有权仍归 encodeSnapshot，不 recycle）。
            if (wantGesture) {
                val bmp = snapBitmap ?: runCatching { rotatedUpright(proxy, rotation) }.getOrNull()
                if (bmp != null) {
                    val code = runCatching {
                        obtainGestureEngine()?.recognize(bmp) ?: PresetGestures.NONE
                    }.getOrDefault(PresetGestures.NONE)
                    if (bmp !== snapBitmap) bmp.recycle()
                    val confirmed = gestureGate.onDetection(code, now)
                    val cb = onGestureConfirmed
                    if (confirmed != PresetGestures.NONE && cb != null) {
                        mainHandler.post { cb(confirmed) }
                    }
                }
            }

            // 头部姿态（「看这边」）：与手势块同款复用抓拍位图的套路；无门控
            // ——判定窗/基线/稳定性都在技能层（LookHereJudge），这里逐帧直报
            if (wantPose) {
                val bmp = snapBitmap ?: runCatching { rotatedUpright(proxy, rotation) }.getOrNull()
                if (bmp != null) {
                    val pose = runCatching { obtainPoseEngine()?.detect(bmp) }.getOrNull()
                    if (bmp !== snapBitmap) bmp.recycle()
                    if (pose != null) {
                        latestHeadPose = HeadPoseSample(pose.first, pose.second, now)
                        val cb = onHeadPose
                        if (cb != null) mainHandler.post { cb(pose.first, pose.second) }
                    }
                }
            }

            // 身体模仿（「模仿我」）：世界关键点 → 镜像解算 → 滤波 → 目标集。
            // 判定/平滑全在数据层（PoseMimicMath），这里只换手 latestMimicPose；
            // 没人时不投喂（引擎按新鲜度自愈还原），关键点在但质量差时投
            // visible=false 的保活 ping（定格而非弹回）
            if (wantFaceMimic) {
                val bmp = snapBitmap ?: runCatching { rotatedUpright(proxy, rotation) }.getOrNull()
                if (bmp != null) {
                    val result = runCatching { obtainFaceMimicEngine()?.detectResult(bmp) }.getOrNull()
                    if (bmp !== snapBitmap) bmp.recycle()
                    if (result != null) {
                        val shapes = result.faceBlendshapes().orElse(null)?.firstOrNull()
                            ?.filter { it.score() > 0.01f && !it.categoryName().equals("neutral", true) }
                            ?.associate { it.categoryName() to it.score() }
                        val matrix = result.facialTransformationMatrixes().orElse(null)?.firstOrNull()
                        if (!shapes.isNullOrEmpty() || matrix != null) {
                            latestMimicFace = MimicFaceFrame(matrix, shapes.orEmpty(), now)
                            faceHeadUntilMs = now + FACE_HEAD_FRESH_MS
                        }
                    }
                }
            }

            if (wantBody) {
                val bmp = snapBitmap ?: runCatching { rotatedUpright(proxy, rotation) }.getOrNull()
                if (bmp != null) {
                    val result = runCatching { obtainBodyEngine()?.detect(bmp) }.getOrNull()
                    if (bmp !== snapBitmap) bmp.recycle()
                    val world = result?.worldLandmarks()?.firstOrNull()
                    if (world != null) {
                        // ⚠ Landmark.visibility() 是 Optional<Float>（可能缺省）
                        val pts = world.map {
                            PLandmark(it.x(), it.y(), it.z(), it.visibility().orElse(0f))
                        }
                        val solved = PoseMimicMath.mimicPoseFromLandmarks(pts, now, mirror = true)
                        if (solved != null) {
                            // 头部朝向优先用 FaceLandmarker 矩阵精解（±2°），鼻-耳
                            // 估算（±5-10°）只在表情车道没数据/刚断供时兜底——
                            // 切换点被 faceHeadUntilMs 钉住，避免双源逐帧抖动
                            val face = latestMimicFace
                            val final = if (face?.matrix != null && now <= faceHeadUntilMs) {
                                PoseMimicMath.withHeadOverride(
                                    solved,
                                    PoseMimicMath.headForwardFromMatrix(face.matrix!!, mirror = true),
                                )
                            } else {
                                solved
                            }
                            latestMimicPose = mimicFilter.filter(final)
                            latestBodyVisible = final.visible
                        } else {
                            latestBodyVisible = false
                        }
                    } else {
                        latestBodyVisible = false
                    }
                }
            }

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
        /** 人脸检测最小间隔（≈12.5fps）：注视追踪够用，原生逐帧日志与 CPU 大幅下降。 */
        private const val DETECT_INTERVAL_MS = 80L
        private const val SNAPSHOT_SIZE = 512
        private const val SNAPSHOT_JPEG_QUALITY = 80

        /** 表情矩阵驱动头部的保鲜窗（隔帧 160ms 采样 ×2 的容忍）。 */
        private const val FACE_HEAD_FRESH_MS = 400L
    }
}
