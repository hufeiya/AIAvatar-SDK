package com.neethu.corelib.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.SurfaceView
import android.view.Choreographer
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Scene
import com.google.android.filament.RenderableManager
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.neethu.corelib.DriveMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

import com.neethu.corelib.AmbientOcclusionQuality
import com.neethu.corelib.AntiAliasingMode
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.BreathInfo
import com.neethu.corelib.CameraShot
import com.neethu.corelib.ContactPoseRequest
import com.neethu.corelib.ContactPoseResult
import com.neethu.corelib.ContactPoseSnapshot
import com.neethu.corelib.LightingRig
import com.neethu.corelib.LookAtInfo
import com.neethu.corelib.MimicInfo
import com.neethu.corelib.MimicPose
import com.neethu.corelib.RenderMode
import com.neethu.corelib.ScenePlacement
import com.neethu.corelib.ToneMappingMode
import com.neethu.corelib.WalkStartResult
import com.neethu.corelib.WalkSnapshot
import com.neethu.corelib.accessory.AccessoryManager
import com.neethu.corelib.accessory.AccessoryHost
import com.neethu.corelib.accessory.AccessorySpec
import com.neethu.corelib.accessory.AccessoryState
import com.neethu.corelib.vfx.VfxAnchor
import com.neethu.corelib.vfx.VfxHandle
import com.neethu.corelib.vfx.VfxInfo
import com.neethu.corelib.vfx.VfxSpec
import com.neethu.corelib.vfx.internal.VfxHost
import com.neethu.corelib.vfx.internal.VfxManager

/**
 * Internal rendering engine backed by Filament [ModelViewer].
 *
 * This class is an implementation detail of the SDK and is NOT part of the
 * public API. External consumers should use [com.neethu.corelib.AvatarController]
 * to interact with the avatar.
 */
internal class SoulLinkRenderer(
    context: Context,
    private val surfaceView: SurfaceView,
    private val config: AvatarConfig = AvatarConfig()
) : DefaultLifecycleObserver {

    // Owned camera manipulator. Constructing it ourselves (instead of letting
    // ModelViewer build its default) exposes programmatic camera control —
    // zoom/pan/orbit/reset use the exact same manipulator calls that
    // GestureDetector drives with real touch input, so the behavior matches
    // the on-screen gestures. Constructor args replicate ModelViewer's own
    // defaults (Engine.create(), UiHelper DONT_CHECK, ORBIT mode targeting
    // MODEL_CENTER; the viewport is set later by ModelViewer on surface resize).
    //
    // The zoom/orbit/fov/far values are pinned to camutils' defaults on
    // purpose: the camera-shot driver below converts world-space corrections
    // into scroll/grab deltas using these exact constants, so the conversions
    // must not silently drift if the library defaults ever change.
    private val cameraManipulator: Manipulator = Manipulator.Builder()
        .targetPosition(MODEL_CENTER[0], MODEL_CENTER[1], MODEL_CENTER[2])
        .zoomSpeed(MANIP_ZOOM_SPEED)
        .orbitSpeed(MANIP_ORBIT_SPEED, MANIP_ORBIT_SPEED)
        .fovDegrees(MANIP_FOV_DEGREES)
        .farPlane(MANIP_FAR_PLANE)
        .build(Manipulator.Mode.ORBIT)

    private var modelViewer: ModelViewer = ModelViewer(
        surfaceView,
        Engine.create(),
        UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK),
        cameraManipulator
    )

    private var animator: com.google.android.filament.gltfio.Animator? = null
    private var startTime = System.nanoTime()

    // Built-in animation state
    private var currentAnimationIndex: Int = -1
    private var isAnimationLooping: Boolean = true

    // VRMA animation support
    private var vrmaParser: VrmaParser = VrmaParser()
    private var vrmaEngine: VrmaAnimationEngine? = null
    private var vrmaStartTime: Long = 0L

    /**
     * 当前已挂载 idle 的来源（assets 路径或文件绝对路径）；null = 无。
     * 用于同源去重——produceState/LaunchedEffect 在重组时会反复用同一来源
     * 调 setVrmaIdleAnimation，不去重会每次重新解析并从头起播（待机莫名词跳）。
     * 模型重载时随引擎一并清空（引擎重建，idle 槽丢失）。
     */
    private var idleSource: String? = null

    // Expression (morph target / blend shape) support
    private var expressionManager: VrmExpressionManager? = null

    // Gaze (look-at) bone overlay support
    private var lookAtEngine: VrmLookAtEngine? = null

    // Breath (chest/shoulder) bone overlay support（方案一呼吸）。开关与说话
    // 标志跨模型重载保留——引擎随模型重建时把两者重新套用。
    private var breathEngine: VrmBreathEngine? = null
    private var breathEnabled = true
    private var breathSpeaking = false
    private var breathAmplitudeScale = 1f
    private var breathRateBpm = -1f  // <0 = 引擎默认

    // Pose mimic (「模仿我」) bone overlay support——方向绝对驱动，数据由 app
    // 相机车道经 AvatarController.setMimicPose 原子换手喂入（不经 orchestrator）
    private var mimicEngine: VrmPoseMimicEngine? = null

    // Spring bone physics support
    internal var springBoneManager: VrmSpringBoneManager? = null
    private var lastFrameTimeNanos: Long = 0L

    // ── 配饰（P1.5 单人路径桥接，docs/accessory-feasibility.md §5.7）──────
    // 专用 loader 对（ModelViewer 内部 loader 不公开；场景 GLB 已有同款先例）。
    // 管理器随模型重载重建（骨骼实体失效）；规格解析器/观测回调跨模型保留。

    private var accessoryMaterialProvider: UbershaderProvider? = null
    private var accessoryAssetLoader: AssetLoader? = null
    private var accessoryResourceLoader: ResourceLoader? = null
    private var accessoryManager: AccessoryManager? = null

    /** 语义骨骼名（小写）→ 实体（bind 时一次解析；配饰挂点查询共用）。 */
    private var boneSemantics: Map<String, Int> = emptyMap()

    /** 当前已加载的 VRMA 剪辑路径（VrmaStarted/Finished 载荷；idle 不在此列）。 */
    private var currentVrmaPath: String? = null

    /** one-shot 激活沿（VrmaFinished 边沿检测；与团播 AvatarRuntime 同款）。 */
    private var prevOneShotActive = false

    /**
     * 配饰挂载规格解析（动画联动自动挂载用）：配饰 ID → spec（app 层注入
     * sidecar 默认+per-model 校准合并结果）；null = 未注入，绑定命中只显隐
     * 已挂载件、无法自动挂载。
     */
    var accessorySpecResolver: ((accessoryId: String) -> AccessorySpec?)? = null

    /** 配饰列表变化（挂/卸/显隐）→ AvatarController 的稳定 StateFlow 中继。 */
    var onAccessoriesUpdated: ((List<AccessoryState>) -> Unit)? = null

    /** 配饰宿主缝：引擎/场景取自 ModelViewer，loader 对专用，字节双通道。 */
    private val accessoryHost = object : AccessoryHost {
        override val accEngine: Engine get() = modelViewer.engine
        override val accScene: Scene get() = modelViewer.scene
        override val accAssetLoader: AssetLoader? get() = accessoryAssetLoader
        override val accResourceLoader: ResourceLoader? get() = accessoryResourceLoader
        override fun accReadSourceBytes(source: String): ByteArray {
            val file = java.io.File(source)
            if (file.isFile) return file.inputStream().use { it.readBytes() }
            return surfaceView.context.assets.open(source).use { it.readBytes() }
        }
    }

    // Set when the model pose is about to snap (animation / VRMA start-stop); the
    // spring bone tails are re-anchored after the first frame of the new pose.
    private var pendingSpringReset: Boolean = false

    // ── VFX 特效库（世界系，与模型生命周期解耦；docs/vfx-feasibility.md）──

    /**
     * 特效管理器：首次 playVfx 惰性创建（材质运行时编译 ~百毫秒级，不用
     * 特效的消费者零启动开销）；destroy 走 [onDestroy] 护栏块（引擎 shutdown
     * 前必须跑完，坑 13 顺序）。
     */
    private var vfxManager: VfxManager? = null

    /** 特效列表变化（play/stop/播完自停）→ AvatarController 稳定 StateFlow 中继。 */
    var onEffectsUpdated: ((List<VfxInfo>) -> Unit)? = null

    /** 特效宿主缝：引擎/场景取自 ModelViewer，相机三元组来自自有 Manipulator。 */
    private val vfxHost = object : VfxHost {
        override val vfxEngine: Engine get() = modelViewer.engine
        override val vfxScene: Scene get() = modelViewer.scene
        override fun vfxCameraLookAt(outEye: FloatArray, outTarget: FloatArray, outUp: FloatArray) {
            cameraManipulator.getLookAt(outEye, outTarget, outUp)
        }

        override fun vfxResolveBone(avatarId: String?, boneName: String, out: FloatArray): Boolean =
            resolveBonePosition(boneName, out)

        override fun vfxReadAssetBytes(path: String): ByteArray? = runCatching {
            surfaceView.context.assets.open(path).use { it.readBytes() }
        }.getOrNull()

        override fun vfxPushMToonStageLight(
            pos: FloatArray,
            dir: FloatArray,
            colorRGB: FloatArray,
            cosInner: Float,
            cosOuter: Float,
            rangeM: Float,
        ) {
            stageLightConeScratch[0] = cosInner
            stageLightConeScratch[1] = cosOuter
            mtoonApplier?.setStageLight(pos, dir, colorRGB, stageLightConeScratch, rangeM)
        }
    }

    /** VfxHost → MToonApplier.setStageLight 的 cone 打包 scratch（主线程唯一）。 */
    private val stageLightConeScratch = FloatArray(2)

    private val vfxBoneMat = FloatArray(16)

    /**
     * 语义骨世界位置（VfxAnchor.Bone 数据源）：boneSemantics 查实体 →
     * TransformManager 读世界变换平移列。false = 无模型/语义骨缺失/实体已
     * 失效（模型重载后 boneSemantics 被置空/重建，陈旧实体 getInstance=0）。
     */
    private fun resolveBonePosition(boneName: String, out: FloatArray): Boolean {
        val entity = boneSemantics[boneName.lowercase()] ?: return false
        if (entity == 0) return false
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(entity)
        if (instance == 0) return false
        tm.getWorldTransform(instance, vfxBoneMat)
        out[0] = vfxBoneMat[12]
        out[1] = vfxBoneMat[13]
        out[2] = vfxBoneMat[14]
        return true
    }

    // Drag mode translates the humanoid hips bone (three-vrm mouse.html semantics)
    // instead of the asset root, so spring bones react to the body motion.
    private var dragMovesHips: Boolean = true
    private var hipsEntity: Int = 0
    private var isVrm0: Boolean = false

    // Hips-drag persistent offset（挪动人物 bug 修复）：VRMA/内置动画每帧重写
    // hips 局部平移，触摸时一次性写入的位移下一帧就被冲掉——只有 T-pose 能拖。
    // 触摸只把位移累加进 [hipsDragOffset]（VRM0 的 X 翻转在累加时处理），真正
    // 落盘在每帧动画覆写之后，由 [HipsDragOffsetSolver] 以动画新写出的平移为
    // 基线再叠加。
    private val hipsDragOffset = FloatArray(3)
    private var hipsDragState = HipsDragOffsetSolver.initial()

    // Humanoid bones used by the camera-shot driver to frame the model
    // (0 = unresolved → proportional fallback).
    private var headEntity: Int = 0
    private var chestEntity: Int = 0
    private var spineEntity: Int = 0
    private var neckEntity: Int = 0

    // Camera-shot framing. `activeShot` stays set until cleared or replaced —
    // switching models re-frames the new character to the same shot.
    // `shotSteering` is true only while a transition is gliding toward the
    // shot pose; any camera touch/call cancels the glide but keeps the mode.
    private var activeShot: CameraShot? = null
    private var shotSteering = false
    private var shotDeadlineNanos = 0L
    // FOLLOW 跟拍的激活瞬间快照：yaw/pitch 固定为激活时的相机角度（每帧从
    // 当前 gaze 重取会自参照固化漂移——实测收成低机位仰拍）；距离取
    // max(激活时距离, 2.5×span)，跟拍=保持当前取景只跟随，不拉近。
    private var followYawPitch: Pair<Float, Float>? = null

    // Scene (environment/background GLB) support
    private var sceneAsset: FilamentAsset? = null
    private var sceneAssetLoader: AssetLoader? = null
    private var sceneResourceLoader: ResourceLoader? = null
    private val sceneReadyRenderables = IntArray(128)
    // Scene placement（对位标定）：一次性把房间对齐到角色世界（ Rotation+平移，
    // 基于 load 时抓拍的场景根矩阵），并携带可走区域（walk clamp 用）。
    private var scenePlacement: ScenePlacement? = null
    private var sceneBaseMatrix: FloatArray? = null

    // 防遮挡（场景墙体自动剖切）：相机在房间外时，挡住角色视线的墙/玻璃/
    // 天花板自动隐藏（layerMask bit0 清零），移开后 0.25s 恢复。闭室 GLB
    // （如 dream_bedroom）远距离环视的解法，docs/dream-bedroom-occlusion。
    private val sceneOcclusion: SceneOcclusionController by lazy {
        SceneOcclusionController(modelViewer.engine)
    }
    // 遮挡更新的抓拍缓冲（每帧零分配）
    private val occlusionEye = FloatArray(3)
    private val occlusionTarget = FloatArray(3)
    private val occlusionUp = FloatArray(3)
    private val occlusionBodyPoints = listOf(FloatArray(3), FloatArray(3), FloatArray(3))

    // 场景交互定姿（坐/躺接触对齐）：走位到接近点后 hips 吸附到家具接触面。
    private val contactPose: ContactPoseController by lazy {
        ContactPoseController(
            getRootMatrix = {
                val asset = modelViewer.asset ?: return@ContactPoseController null
                val tm = modelViewer.engine.transformManager
                val instance = tm.getInstance(asset.root)
                if (instance == 0) return@ContactPoseController null
                tm.getTransform(instance, FloatArray(16))
            },
            getHipsWorld = {
                val hips = boneAnchor(hipsEntity, HIPS_FALLBACK_Y)
                floatArrayOf(hips[0], hips[1], hips[2])
            },
            playClip = { path, loop, fade ->
                if (loadVrmaAnimation(path)) {
                    playVrmaAnimationInternal(loop = loop, speed = 1f, fadeSeconds = fade, extractor = null)
                    true
                } else {
                    false
                }
            },
            isOneShotActive = { vrmaEngine?.isOneShotActive() == true },
            headingOf = { rootHeadingRad(it) },
            onReturnToStand = { standMat ->
                locomotionEngine.resyncHeadingRad(rootHeadingRad(standMat))
            },
            onLog = { android.util.Log.i("SoulLinkRenderer", it) },
        )
    }

    // Locomotion（自由走动，P0）：纯数学状态机 + 走路步态，见
    // docs/locomotion-feasibility.md §4.2/§5 —— 原地步态素材继续播，
    // 引擎每帧推 root（合成 root motion），与 hips 局部动画正交。
    private val locomotionEngine = LocomotionEngine()
    private var walkGait: WalkGait? = null

    // P2 增强（docs/locomotion-feasibility.md §11）：跑步/侧移步态槽、
    // 走动 crossfade 时长、走动中手势 overlay 开关、当前生效步态。
    private var walkRunGait: WalkGait? = null
    private var strafeLeftGait: WalkGait? = null
    private var strafeRightGait: WalkGait? = null

    /** 本次走动正在驱动的步态（mode C 判定 externalGait + 速度换算都用它）。 */
    private var activeGait: WalkGait? = null

    /**
     * 起步/停步 crossfade 时长（秒，P2 §5.2 档 2）：0 = 关（硬切回 P1 行为）。
     * ai_cmd `walk_fade` 真机 A/B 入口。
     */
    private var walkFadeSeconds = 0.25f

    /**
     * 走动中 `<act:>` 手势以「上半身 mask + 淡入淡出 overlay」叠加（P2 §5.3
     * 边走边比划）；false = 回 P1 行为（停步比划）。ai_cmd `walk_overlay` 开关。
     */
    private var gestureOverlayEnabled = true

    /** 走动中角色逼近静态机位时自动切 FOLLOW（P1 遗留形态：走进相机粉屏）。 */
    private val cameraApproachGuardMeters = 0.9f
    // 相机自护的 getLookAt 复用缓冲（每帧分配逃逸）
    private val shotEye = FloatArray(3)
    private val shotTarget = FloatArray(3)
    private val shotUp = FloatArray(3)

    /**
     * 方案 C 总开关（P2）：false = 强制程序位移（A/B 对拍用，ai_cmd
     * `walk_extract`）。默认开——默认步态 Female Normal Walk 实测带 1.49m/
     * 循环烘焙位移，提取模式消除循环回弹且步频与地面速度天然同步。
     */
    private var extractionEnabled = true

    // 过渡素材（P1 档 1，§5.2）：起步/停步剪辑可选配置；起步剪辑播放期间
    // 程序位移冻结（引擎 start-hold），播完切回步态循环。⚠库内候选素材的
    // 位移测量与取舍见 docs/locomotion-feasibility.md §5.2 标定项——默认关，
    // ai_cmd `walk_transit` 真机 A/B 后再定默认。P2 起步剪辑可带方案 C 提取
    // （烘焙位移积分进 root，双重位移+回弹消除）。
    private var walkStartClip: WalkGait? = null
    private var walkStopClip: WalkGait? = null
    /** >0 = 起步剪辑播放中（墙钟到期切步态循环）；stopWalk 清零。 */
    private var startClipDeadlineNanos = 0L
    /** 本次起步请求的速度（m/s）——起步剪辑播完切步态循环时换算 timeScale 用。 */
    private var walkSpeed = 1.0f

    // Drag mode support
    var isDragMode: Boolean = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Hot-swappable PBR render settings, driven by applyRenderSettings()
    private var renderSettings: AvatarRenderSettings = config.renderSettings
    private var colorGrading: ColorGrading? = null
    private var appliedToneMapping: ToneMappingMode? = null

    // MToon (cel shading) active state — built per model load when
    // [AvatarRenderSettings.renderMode] is MTOON. Owns its GPU resources;
    // destroyed before the next model load / on engine teardown.
    private var mtoonFactory: MToonMaterialFactory? = null
    private var mtoonApplier: MToonApplier? = null
    /** True when the current model actually got MToon materials (drives the LinearToneMapper override). */
    private var mtoonActive = false

    // Studio light rig entities (0 = not built)
    private var keyLightEntity: Int = 0
    private var fillLightEntity: Int = 0
    private var rimLightEntity: Int = 0

    // FPS measurement (reported via onFpsUpdated every FPS_WINDOW_NANOS)
    var onFpsUpdated: ((Int) -> Unit)? = null

    /**
     * Fired when a fresh model load auto-switched the render mode to match the
     * model's materials (fresh switches follow the model: MToon materials →
     * MTOON, otherwise PBR — regardless of the pushed setting). Carries the
     * mode the model was actually loaded with; the controller relays it so the
     * app can sync its settings UI/persistence. Manual reloads (pushed mode)
     * never fire this.
     */
    var onRenderModeAutoDetected: ((RenderMode) -> Unit)? = null

    /**
     * Fired once per walk when it ends: [com.neethu.corelib.WalkFinished.arrived]=true
     * = the state machine reached the target; false = the walk was cancelled
     * (user/LLM stop). AvatarController relays this to its [walkFinished] flow
     * (AvatarSession turns it into UI events).
     */
    var onWalkFinished: ((com.neethu.corelib.WalkFinished) -> Unit)? = null
    private var fpsFrameCount = 0
    private var fpsWindowStartNanos = 0L

    companion object {
        init {
            com.google.android.filament.utils.Utils.init()
        }

        // Camera side of the scene: ModelViewer places the model at MODEL_CENTER
        // and the default orbit manipulator looks at it from +Z.
        private val MODEL_CENTER = floatArrayOf(0f, 0f, -4f)

        // camutils internal constants, pinned via the Manipulator.Builder so the
        // camera-shot driver's world→gesture conversions are exact:
        // - zoomSpeed: scroll(delta) moves eye+target along the gaze by
        //   zoomSpeed × (−delta) meters (linear).
        // - orbitSpeed: grabUpdate moves yaw/pitch by pixels × orbitSpeed radians
        //   (yaw from (grabX − x), pitch from (grabY − y)).
        // - fovDegrees/farPlane: only feed the strafe-pan raycast math; the
        //   render camera itself is a 28 mm lens set by ModelViewer.
        private const val MANIP_ZOOM_SPEED = 0.01f
        private const val MANIP_ORBIT_SPEED = 0.01f
        private const val MANIP_FOV_DEGREES = 33f
        private const val MANIP_FAR_PLANE = 5000f

        // Camera-shot transition tuning: exponential approach rate (1/s) toward
        // the shot pose, error thresholds for "arrived", and a hard timeout so
        // a pathological pose can never wedge the driver on. FOLLOW never
        // "arrives" (it tracks a moving body) — it uses a softer rate and
        // skips the timeout/arrival gates.
        private const val SHOT_APPROACH_RATE = 7f
        private const val FOLLOW_APPROACH_RATE = 5f
        private const val SHOT_ARRIVE_DIST = 0.012f
        private const val SHOT_ARRIVE_ANGLE_RAD = 0.02f
        private const val SHOT_TIMEOUT_NANOS = 4_000_000_000L

        // tan(fovDegrees/2) — feeds the pan world-per-pixel conversion.
        private val MANIP_FOV_TAN = tan(Math.toRadians((MANIP_FOV_DEGREES / 2f).toDouble())).toFloat()

        private val WORLD_UP = floatArrayOf(0f, 1f, 0f)

        // Proportional bone anchors for models without a humanoid rig. The
        // unit-cube normalization puts feet at y ≈ −1 and the head top at
        // y ≈ +1 around MODEL_CENTER; these match typical VRM bone heights.
        private const val HEAD_FALLBACK_Y = 0.8f
        private const val CHEST_FALLBACK_Y = 0.35f
        private const val HIPS_FALLBACK_Y = 0.0f

        // Three-point studio rig, built around MODEL_CENTER (the model sits at
        // (0, 0, -4)). Direction = the way the light travels. Every light in the
        // rig is a SPOT light — including the "sun": Filament composes a
        // directional light's direction with the camera/world transform in
        // FScene::prepare, and in practice that path lit this scene as if the
        // key were rotated 180° around Y (bright floor/backs, black faces and
        // front walls) no matter what transform the light entity carried. The
        // punctual (spot) path proved position- and direction-correct, so the
        // key is a wide-cone shadow-casting spot — a studio softbox in effect.
        // Spot intensity is candela; illuminance ≈ cd / distance².
        private val KEY_COLOR = floatArrayOf(1.0f, 0.98f, 0.95f)
        private const val KEY_CANDELA = 2_250_000f
        private val KEY_POSITION = floatArrayOf(2.0f, 5.0f, -2.0f)
        private val KEY_TARGET = floatArrayOf(0.0f, 0.9f, -4.0f)
        private const val KEY_CONE_INNER = 0.35f
        private const val KEY_CONE_OUTER = 0.7f

        // Fill: cool spot from up-front-left, aimed at the model's chest.
        // ~29000 lux at the subject ≈ a 1:2~1:3 key-to-fill ratio.
        private val FILL_COLOR = floatArrayOf(0.8f, 0.85f, 1.0f)
        private const val FILL_CANDELA = 300_000f
        private val FILL_POSITION = floatArrayOf(-2.0f, 2.6f, -2.0f)
        private val FILL_TARGET = floatArrayOf(0.0f, 1.1f, -4.0f)
        private const val FILL_CONE_INNER = 0.55f
        private const val FILL_CONE_OUTER = 1.0f

        // Rim: cool-white spot from up-behind, aimed past the model's head.
        // ~70000 lux on the model's back, outlining hair and shoulders.
        private val RIM_COLOR = floatArrayOf(0.95f, 0.97f, 1.0f)
        private const val RIM_CANDELA = 350_000f
        private val RIM_POSITION = floatArrayOf(0.0f, 2.7f, -6.0f)
        private val RIM_TARGET = floatArrayOf(0.0f, 1.3f, -4.0f)
        private const val RIM_CONE_INNER = 0.5f
        private const val RIM_CONE_OUTER = 0.9f

        private const val SPOT_FALLOFF_METERS = 15f

        /** 头发风外力：风速→加速度增益与安全限幅（模型单位/秒²；Verlet 稳定优先）。 */
        private const val SPRINGBONE_WIND_GAIN = 1.1f
        private const val SPRINGBONE_WIND_MAX = 1.3f

        private const val CONTACT_SHADOW_STEPS = 8
        private const val AO_RADIUS = 0.15f

        private const val FPS_WINDOW_NANOS = 500_000_000L

        // Pinch-pixels → manipulator scroll delta, matching GestureDetector's
        // private kZoomSpeed so programmatic zoom scales identically to fingers.
        private const val ZOOM_SPEED_PER_PIXEL = 0.1f

        // 走动停滞看门狗：WALKING 相位 5s 净位移 <0.15m 即判停滞
        private const val WALK_STALL_TIMEOUT_MS = 5_000L
        private const val WALK_STALL_MAX_RETRIES = 2

        /** 同目标持续 WALKING 的强停上限（ms）：正常腿 ≤10s,40s = 4× 余量。 */
        private const val WALK_NON_CONVERGENCE_MS = 40_000L

        // Material name hints for applyMaterialEnhancements (VRM/glTF conventions)
        private val EYE_NAME_HINTS = listOf("eye", "目", "瞳")
        private val SKIN_NAME_HINTS = listOf("skin", "face", "body", "顔", "脸", "肌")
        private val HAIR_NAME_HINTS = listOf("hair", "髪", "发")
    }

    private val choreoCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // 拖拽位移的覆写检测基线：本帧动画分支若改写 hips 平移，写入值与
            // 之不同（基线里还带着上一帧叠加的拖拽位移）
            val hipsBefore = captureHipsTranslation()
            // 呼吸层双信号判定的快照：必须在动画分支之前拍（BreathBaseResolver
            // 用「动画前 vs 动画后」判定动画是否重写过，防 strip 误判锁死）
            breathEngine?.capturePreAnimation()
            // 视线层同款快照（头/颈被动画重写→全量写防顿挫；眼骨保留 strip）
            lookAtEngine?.capturePreAnimation()
            // VRMA animation takes priority over built-in animations
            val vrma = vrmaEngine
            if (vrma != null && vrma.isActive()) {
                if (vrma.consumeIdleSwap()) pendingSpringReset = true
                val elapsed = (frameTimeNanos - vrmaStartTime).toDouble() / 1_000_000_000.0
                vrma.update(elapsed.toFloat())
            } else {
                animator?.let { anim ->
                    if (currentAnimationIndex >= 0 && currentAnimationIndex < anim.animationCount) {
                        val elapsedTimeSeconds = (frameTimeNanos - startTime).toDouble() / 1_000_000_000.0
                        val animDuration = anim.getAnimationDuration(currentAnimationIndex)

                        val time = if (isAnimationLooping) {
                            (elapsedTimeSeconds % animDuration).toFloat()
                        } else {
                            elapsedTimeSeconds.toFloat().coerceAtMost(animDuration)
                        }

                        anim.applyAnimation(currentAnimationIndex, time)
                    }
                }
            }

            // Hips drag offset re-application: runs after the animation pass
            // (which rewrites the hips local transform every frame) and before
            // the gaze overlay / bone matrices read the pose — see
            // [applyHipsDragOffset].
            applyHipsDragOffset(hipsBefore)

            val gazeDt = if (lastFrameTimeNanos == 0L) 0.016f
            else ((frameTimeNanos - lastFrameTimeNanos).coerceAtLeast(0L)) / 1_000_000_000.0f

            // Locomotion root motion（自由走动）：root 是骨骼层级的父节点，
            // 写 root 变换与骨骼局部写入正交；放在动画/drag 钉层之后、蒙皮
            // 传播之前（程序化层的统一落点）。到达时由引擎切回 idle。
            updateLocomotion(gazeDt.coerceIn(0.001f, 0.05f))

            // 场景交互定姿（坐/躺接触对齐）：root 动画与走动层互斥
            // （applyContactPose 拒绝 WALKING），放在走动层之后的统一落点。
            updateContactPose(gazeDt.coerceIn(0.001f, 0.05f))

            // 防遮挡（墙体自动剖切）：相机→身体检测线，命中即隐藏（layerMask）。
            // 放 updateCameraShot 之前——本帧运镜基向量刚写入 Manipulator，
            // 遮挡判定用最新机位；TransformManager 已在本帧 commit。
            updateSceneOcclusion(gazeDt.coerceIn(0.001f, 0.05f))

            // Breath overlay（呼吸）: spine/shoulder micro-rotation rides on
            // the animated pose — runs after the animation wrote bone locals
            // (its strip-and-overlay bookkeeping re-anchors on the fresh pose)
            // and before the gaze overlay reads the head world pose, so the
            // gaze follows the breath lift naturally. All bone-programmatic
            // layers must land before updateBoneMatrices() propagates skin
            // matrices.
            breathEngine?.update(gazeDt.coerceIn(0.001f, 0.05f))

            // Gaze overlay: rides on top of the animated pose — reads the
            // post-animation head world transform and adds clamped head/neck/
            // eye rotation toward the look-at target — so it must run after
            // the animation has written bone locals and before skin matrices
            // propagate. (The single updateBoneMatrices below covers both
            // animation branches; the spring-bone block re-runs it later.)
            lookAtEngine?.update(gazeDt.coerceIn(0.001f, 0.05f))

            // Mimic overlay（「模仿我」）：neck/head + 双臂的**绝对方向**驱动，
            // 排在视线之后（头/颈 mimic 赢=跟用户转头，眼睛保持注视相机），
            // 蒙皮传播之前（骨骼程序化层的统一落点）。一次性 VRMA 播放期间挂起
            // （`<act:>` 手势完整播完，播完自动续上）；断供 >600ms 引擎自愈还原。
            val animationWrote = (vrma != null && vrma.isActive()) ||
                (animator != null && currentAnimationIndex >= 0)
            val oneShotActive = (vrma?.isOneShotActive() == true) ||
                (animator != null && currentAnimationIndex >= 0 && !isAnimationLooping)
            mimicEngine?.update(
                gazeDt.coerceIn(0.001f, 0.05f),
                SystemClock.elapsedRealtime(),
                animationWrote,
                oneShotActive,
            )
            // VrmaFinished 边沿（one-shot 激活沿下降 = 播完/被打断/被顶替淡出）：
            // 喂配饰状态机延迟隐藏（对齐团播 AvatarRuntime.postAnimation）
            if (prevOneShotActive && !oneShotActive) {
                currentVrmaPath?.let { accessoryManager?.onVrmaFinished(it) }
            }
            prevOneShotActive = oneShotActive
            // 显式提交本帧全部局部变换写入（idle/呼吸/视线）：TransformManager
            // 惰性求值，commit 前所有 getWorldTransform/蒙皮都读旧世界变换。
            // gti 依赖 lookAt 内部的 commit——gaze off（target=null 提前 return）
            // 时无人提交，本帧呼吸写入全部丢失（头部方波抽搐根因）。
            modelViewer.engine.transformManager.commitLocalTransformTransaction()
            animator?.updateBoneMatrices()

            // Update expression morph weights each frame (with smooth transitions)
            expressionManager?.update(frameTimeNanos)

            // Re-anchor spring bone tails after a pose snap (animation / VRMA start-stop).
            // Runs once, after the first frame of the new pose has been applied.
            if (pendingSpringReset && springBoneManager != null) {
                springBoneManager?.reset()
                pendingSpringReset = false
            }

            // Spring bone physics — runs after animation, before render
            if (lastFrameTimeNanos > 0L) {
                val dt = ((frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0f)
                    .coerceIn(0.001f, 0.05f)
                springBoneManager?.update(dt)
                // Re-propagate bone matrices after spring bone modifies transforms
                if (springBoneManager != null) {
                    animator?.updateBoneMatrices()
                }
            }
            // Camera-shot framing: steer the manipulator toward the shot pose
            // (bones may have moved this very frame, so this runs after the
            // animation/spring-bone updates, right before render).
            updateCameraShot(frameTimeNanos)
            // VFX 泵：updateCameraShot 之后（同帧相机基向量刚写入 Manipulator，
            // Billboard 无一帧滞后）、render 之前（docs/vfx-feasibility.md §4.4）。
            vfxManager?.update(frameTimeNanos)
            feedSpringBoneWind()
            lastFrameTimeNanos = frameTimeNanos

            updateFps(frameTimeNanos)

            // Progressively populate scene entities as textures become ready
            populateSceneEntities()

            modelViewer.render(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun updateFps(frameTimeNanos: Long) {
        if (fpsWindowStartNanos == 0L) {
            fpsWindowStartNanos = frameTimeNanos
            fpsFrameCount = 0
        }
        fpsFrameCount++
        val elapsed = frameTimeNanos - fpsWindowStartNanos
        if (elapsed >= FPS_WINDOW_NANOS) {
            onFpsUpdated?.invoke(((fpsFrameCount * 1_000_000_000L) / elapsed).toInt())
            fpsFrameCount = 0
            fpsWindowStartNanos = frameTimeNanos
        }
    }

    init {
        setupSkybox()
        applyRenderSettings(config.renderSettings)
        if (config.enableTouch) {
            surfaceView.setOnTouchListener { _, event ->
                if (isDragMode) {
                    when (event.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            lastTouchX = event.x
                            lastTouchY = event.y
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - lastTouchX
                            val dy = event.y - lastTouchY
                            lastTouchX = event.x
                            lastTouchY = event.y
                            if (dragMovesHips && hipsEntity != 0) {
                                // VRM 0.x models carry a 180° Y root rotation: the hips
                                // local frame is flipped relative to the world.
                                val sign = if (isVrm0) -1f else 1f
                                // 只累加；落盘在每帧动画覆写之后（applyHipsDragOffset），
                                // 否则直接写 hips 下一帧就被动画冲掉（T-pose 才能拖的 bug）
                                hipsDragOffset[0] += dx * 0.005f * sign
                                hipsDragOffset[1] -= dy * 0.005f
                                hipsDragState = HipsDragOffsetSolver.State(hipsDragState.base, true)
                            } else {
                                modelViewer.asset?.let { asset ->
                                    val tm = modelViewer.engine.transformManager
                                    val instance = tm.getInstance(asset.root)
                                    if (instance != 0) {
                                        val mat = FloatArray(16)
                                        tm.getTransform(instance, mat)
                                        mat[12] += dx * 0.005f
                                        mat[13] -= dy * 0.005f
                                        tm.setTransform(instance, mat)
                                    }
                                }
                            }
                        }
                    }
                    true
                } else {
                    // Any camera-touch cancels the shot transition — the user
                    // takes over from wherever the camera currently is.
                    if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                        event.actionMasked == android.view.MotionEvent.ACTION_POINTER_DOWN
                    ) {
                        cancelShotTransition()
                    }
                    modelViewer.onTouchEvent(event)
                    true
                }
            }
        }
    }

    // ── Render Settings API ──────────────────────────────────────────────

    /**
     * Apply a full set of PBR render settings. Every part is hot-swappable;
     * safe to call repeatedly with unchanged values (the shadow-casting key
     * light is only rebuilt when its shadow options change, and fill/rim are
     * only touched when the rig actually changes).
     */
    fun applyRenderSettings(settings: AvatarRenderSettings) {
        renderSettings = settings
        applyLighting()
        applyIblSettings()
        applyViewSettings()
        applyMaterialEnhancements()
        skyboxManager?.setBrightness(settings.skyboxBrightness)
    }

    /** Shadow-related key-light options; changing them requires a light rebuild. */
    private data class ShadowSignature(
        val shadowMapSize: Int,
        val contactShadows: Boolean,
    )

    private var keyShadowSignature: ShadowSignature? = null

    private fun applyLighting() {
        // The key light entity is kept across rig switches (only its shadow
        // options force a rebuild): destroying it would drop the shadow map
        // and re-trigger shadow shader variants, which shows up as a visible
        // hitch every time the user changes the lighting rig.
        val shadowSignature = ShadowSignature(
            shadowMapSize = renderSettings.shadowMapSize,
            contactShadows = renderSettings.contactShadows,
        )
        if (keyLightEntity == 0 || shadowSignature != keyShadowSignature) {
            keyShadowSignature = shadowSignature
            destroyLight(keyLightEntity)
            keyLightEntity = 0
            keyLightEntity = createSpotLight(
                color = KEY_COLOR,
                candela = KEY_CANDELA,
                position = KEY_POSITION,
                target = KEY_TARGET,
                coneInner = KEY_CONE_INNER,
                coneOuter = KEY_CONE_OUTER,
                castShadows = true,
                shadowMapSize = renderSettings.shadowMapSize,
                contactShadows = renderSettings.contactShadows,
            )
        }

        // Fill / rim are punctual (spot) lights, so they can be added and
        // removed freely without touching the shadow-casting key.
        when (renderSettings.lightingRig) {
            LightingRig.KEY_ONLY -> {
                destroyLight(fillLightEntity); fillLightEntity = 0
                destroyLight(rimLightEntity); rimLightEntity = 0
            }
            LightingRig.KEY_FILL -> {
                destroyLight(rimLightEntity); rimLightEntity = 0
                if (fillLightEntity == 0) {
                    fillLightEntity = createSpotLight(
                        color = FILL_COLOR,
                        candela = FILL_CANDELA,
                        position = FILL_POSITION,
                        target = FILL_TARGET,
                        coneInner = FILL_CONE_INNER,
                        coneOuter = FILL_CONE_OUTER,
                    )
                }
            }
            LightingRig.STUDIO -> {
                if (fillLightEntity == 0) {
                    fillLightEntity = createSpotLight(
                        color = FILL_COLOR,
                        candela = FILL_CANDELA,
                        position = FILL_POSITION,
                        target = FILL_TARGET,
                        coneInner = FILL_CONE_INNER,
                        coneOuter = FILL_CONE_OUTER,
                    )
                }
                if (rimLightEntity == 0) {
                    rimLightEntity = createSpotLight(
                        color = RIM_COLOR,
                        candela = RIM_CANDELA,
                        position = RIM_POSITION,
                        target = RIM_TARGET,
                        coneInner = RIM_CONE_INNER,
                        coneOuter = RIM_CONE_OUTER,
                    )
                }
            }
        }

        // The MToon math ignores scene lights; it takes the key light as a
        // material parameter instead (direction TOWARD the light).
        applyMToonKeyLight()
    }

    /** Push the studio key light into the MToon materials (no-op when inactive). */
    private fun applyMToonKeyLight() {
        val applier = mtoonApplier ?: return
        val dir = normalize(
            floatArrayOf(
                KEY_POSITION[0] - KEY_TARGET[0],
                KEY_POSITION[1] - KEY_TARGET[1],
                KEY_POSITION[2] - KEY_TARGET[2],
            )
        )
        applier.setKeyLight(dir, MToonApplier.defaultLightColor(KEY_COLOR))
    }

    /**
     * Build a light entity and add it to the scene. Every light gets a
     * TransformManager component: Filament composes a light's world direction
     * and position with its entity transform, and a light without one falls
     * back to transform instance 0 (whatever component happens to live there),
     * which mangles the authored direction.
     */
    private fun createLightEntity(builder: LightManager.Builder, position: FloatArray): Int {
        val engine = modelViewer.engine
        val entity = EntityManager.get().create()
        builder.build(engine, entity)
        val tm = engine.transformManager
        val instance = tm.create(entity)
        val transform = FloatArray(16).apply {
            this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f
            this[12] = position[0]; this[13] = position[1]; this[14] = position[2]
        }
        tm.setTransform(instance, transform)
        modelViewer.scene.addEntity(entity)
        return entity
    }

    /** Spot light at [position] aimed at [target]; intensity in candela. */
    private fun createSpotLight(
        color: FloatArray,
        candela: Float,
        position: FloatArray,
        target: FloatArray,
        coneInner: Float,
        coneOuter: Float,
        castShadows: Boolean = false,
        shadowMapSize: Int = 1024,
        contactShadows: Boolean = false,
    ): Int {
        val aim = normalize(
            floatArrayOf(
                target[0] - position[0],
                target[1] - position[1],
                target[2] - position[2],
            )
        )
        val builder = LightManager.Builder(LightManager.Type.SPOT)
            .color(color[0], color[1], color[2])
            .intensity(candela)
            .direction(aim[0], aim[1], aim[2])
            .spotLightCone(coneInner, coneOuter)
            .falloff(SPOT_FALLOFF_METERS)
            .castShadows(castShadows)
        if (castShadows) {
            builder.shadowOptions(
                LightManager.ShadowOptions().apply {
                    mapSize = shadowMapSize
                    screenSpaceContactShadows = contactShadows
                    stepCount = CONTACT_SHADOW_STEPS
                }
            )
        }
        return createLightEntity(builder, position)
    }

    private fun destroyLight(entity: Int) {
        if (entity == 0) return
        val engine = modelViewer.engine
        modelViewer.scene.removeEntity(entity)
        engine.destroyEntity(entity)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val length = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (length > 0f) floatArrayOf(v[0] / length, v[1] / length, v[2] / length) else v
    }

    private fun applyIblSettings() {
        val ibl = modelViewer.scene.indirectLight ?: return
        ibl.setIntensity(renderSettings.iblIntensity)
        val radians = Math.toRadians(renderSettings.iblRotationDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        ibl.setRotation(floatArrayOf(c, 0f, -s, 0f, 1f, 0f, s, 0f, c))
        skyboxManager?.setEnvironmentLuminance(currentSkyboxEnvLuminance())
    }

    private fun applyViewSettings() {
        val view = modelViewer.view
        val settings = renderSettings

        // Ambient occlusion
        view.setAmbientOcclusionOptions(
            View.AmbientOcclusionOptions().apply {
                enabled = settings.ambientOcclusion != AmbientOcclusionQuality.OFF
                aoType = if (settings.ambientOcclusion == AmbientOcclusionQuality.STANDARD) {
                    View.AmbientOcclusionOptions.AmbientOcclusionType.SAO
                } else {
                    View.AmbientOcclusionOptions.AmbientOcclusionType.GTAO
                }
                quality = if (settings.ambientOcclusion == AmbientOcclusionQuality.HIGH) {
                    View.QualityLevel.HIGH
                } else {
                    View.QualityLevel.MEDIUM
                }
                radius = AO_RADIUS
            }
        )

        // Bloom
        view.setBloomOptions(
            View.BloomOptions().apply {
                enabled = settings.bloomEnabled
                strength = settings.bloomStrength.coerceIn(0f, 0.5f)
                quality = View.QualityLevel.MEDIUM
            }
        )

        // Anti-aliasing (the three strategies are mutually exclusive)
        val msaa = View.MultiSampleAntiAliasingOptions()
        val taa = View.TemporalAntiAliasingOptions()
        when (settings.antiAliasing) {
            AntiAliasingMode.NONE -> view.antiAliasing = View.AntiAliasing.NONE
            AntiAliasingMode.FXAA -> view.antiAliasing = View.AntiAliasing.FXAA
            AntiAliasingMode.MSAA_4X -> {
                view.antiAliasing = View.AntiAliasing.NONE
                msaa.enabled = true
                msaa.sampleCount = 4
            }
            AntiAliasingMode.TAA -> {
                view.antiAliasing = View.AntiAliasing.NONE
                taa.enabled = true
                taa.sharpness = 0.25f
            }
        }
        view.setMultiSampleAntiAliasingOptions(msaa)
        view.setTemporalAntiAliasingOptions(taa)

        // Shadow look (map size / contact shadows live on the key light entity)
        view.setShadowType(if (settings.softShadows) View.ShadowType.PCSS else View.ShadowType.PCF)

        // Depth of field, focused on the model
        view.setDepthOfFieldOptions(
            View.DepthOfFieldOptions().apply {
                enabled = settings.depthOfFieldEnabled
                maxApertureDiameter = 0.01f
            }
        )
        if (settings.depthOfFieldEnabled) {
            val eye = FloatArray(3)
            modelViewer.camera.getPosition(eye)
            val dx = eye[0] - MODEL_CENTER[0]
            val dy = eye[1] - MODEL_CENTER[1]
            val dz = eye[2] - MODEL_CENTER[2]
            modelViewer.camera.setFocusDistance(sqrt(dx * dx + dy * dy + dz * dz))
        }

        // HDR color buffer is required for bloom/tone mapping to behave
        view.renderQuality = view.renderQuality.apply {
            hdrColorBuffer = View.QualityLevel.HIGH
        }
        view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
            enabled = false
        }

        // MToon materials output linear color and count on the grading pass
        // for the sRGB encode (parity with the desktop viewer's Linear setup +
        // three's NoToneMapping); only applied when the model actually has
        // MToon materials, so PBR models keep the user's tone mapping.
        applyToneMapping(if (mtoonActive) ToneMappingMode.LINEAR else settings.toneMapping)
    }

    private fun applyToneMapping(mode: ToneMappingMode) {
        // Building the color grading LUT is not free — skip when unchanged
        if (mode == appliedToneMapping && colorGrading != null) return
        appliedToneMapping = mode
        val engine = modelViewer.engine
        val toneMapper: ToneMapper = when (mode) {
            ToneMappingMode.LINEAR -> ToneMapper.Linear()
            ToneMappingMode.FILMIC -> ToneMapper.Filmic()
            ToneMappingMode.ACES -> ToneMapper.ACES()
        }
        val grading = ColorGrading.Builder()
            .quality(ColorGrading.QualityLevel.HIGH)
            .toneMapper(toneMapper)
            .build(engine)
        modelViewer.view.setColorGrading(grading)
        colorGrading?.let { engine.destroyColorGrading(it) }
        colorGrading = grading
    }

    /**
     * Best-effort material upgrades applied to the loaded model:
     * clear coat on eye-named materials, lower roughness on skin/hair-named
     * materials. Only parameters actually declared by the model's material
     * are touched, so unsupported models are left untouched.
     */
    private fun applyMaterialEnhancements() {
        if (!renderSettings.enhanceMaterials) return
        val asset: FilamentAsset = modelViewer.asset ?: return
        val instances = asset.getInstance()?.materialInstances ?: return
        instances.forEach { instance ->
            val name = instance.name?.lowercase() ?: return@forEach
            val material = instance.material
            when {
                EYE_NAME_HINTS.any { name.contains(it) } -> {
                    if (material.hasParameter("clearCoat")) {
                        instance.setParameter("clearCoat", 1.0f)
                        if (material.hasParameter("clearCoatRoughness")) {
                            instance.setParameter("clearCoatRoughness", 0.08f)
                        }
                    }
                }
                SKIN_NAME_HINTS.any { name.contains(it) } -> {
                    if (material.hasParameter("roughnessFactor")) {
                        instance.setParameter("roughnessFactor", 0.5f)
                    }
                }
                HAIR_NAME_HINTS.any { name.contains(it) } -> {
                    if (material.hasParameter("roughnessFactor")) {
                        instance.setParameter("roughnessFactor", 0.4f)
                    }
                }
            }
        }
    }

    // ── Skybox（360° 全景壁纸 → cubemap；docs/skybox.md）────────────────

    private var skyboxManager: SkyboxManager? = null

    /** 当前天空盒来源（全景 assets 路径或绝对文件路径；null = 纯色背景）。 */
    var skyboxSource: String? = null
        private set

    private fun setupSkybox() {
        val manager = SkyboxManager(modelViewer.engine, modelViewer.scene)
        manager.setEnvironmentLuminance(currentSkyboxEnvLuminance())
        manager.setBrightness(renderSettings.skyboxBrightness)
        skyboxManager = manager
        val path = config.skyboxPath
        val loaded = path != null && runCatching { loadSkybox(path) }.getOrDefault(false)
        if (!loaded) {
            skyboxSource = null
            manager.setColor(config.backgroundColor)
        }
    }

    /**
     * 天空盒亮度补偿基准：IBL 强度(lux) × 相机曝光。filament 天空盒着色器乘的
     * 就是这个乘积（iblLuminance，docs/skybox.md 坑 9），管理器据此反推上传增益。
     */
    private fun currentSkyboxEnvLuminance(): Float =
        renderSettings.iblIntensity * SkyboxTexturePacker.cameraExposure(
            modelViewer.camera.aperture, modelViewer.camera.shutterSpeed, modelViewer.camera.sensitivity,
        )

    /**
     * 360° 全景壁纸（equirect，约 2:1 的 JPG/PNG/WebP）→ GPU 转 cubemap →
     * 天空盒。失败（路径不存在/解码/转换异常）保留旧背景返回 false。
     *
     * @param yawDeg 水平循环偏移（度）：正 = 原画面右侧内容转到正前方。
     */
    fun loadSkybox(assetsPath: String, yawDeg: Float = 0f): Boolean {
        val manager = skyboxManager ?: return false
        val bitmap = runCatching {
            surfaceView.context.assets.open(assetsPath).use { input ->
                decodePanoramaBitmap(input.readBytes())
            }
        }.getOrNull() ?: return false
        if (!manager.setPanorama(bitmap, yawDeg)) return false
        skyboxSource = assetsPath
        return true
    }

    /** 同 [loadSkybox]，但读本地存储的绝对路径（运行时导入的全景）。 */
    fun loadSkyboxFromFile(path: String, yawDeg: Float = 0f): Boolean {
        val manager = skyboxManager ?: return false
        val file = java.io.File(path)
        if (!file.isFile) return false
        val bitmap = runCatching { decodePanoramaBitmap(file.readBytes()) }.getOrNull() ?: return false
        if (!manager.setPanorama(bitmap, yawDeg)) return false
        skyboxSource = path
        return true
    }

    /** 只调水平偏移（重派生自驻留位图，不重新解码；纯色模式下返回 false）。 */
    fun setSkyboxYaw(yawDeg: Float): Boolean = skyboxManager?.setYaw(yawDeg) ?: false

    /**
     * 全景亮度倍率（线性乘子，1.0 = 与原图逐像素一致）。全景激活时后台重派生
     * （约 0.7s 后下一帧生效）；纯色模式下只记参数，返回 false。
     */
    fun setSkyboxBrightness(factor: Float): Boolean = skyboxManager?.setBrightness(factor) ?: false

    /** 当前亮度倍率（渲染器未就绪时回落到设置值）。 */
    val skyboxBrightness: Float
        get() = skyboxManager?.brightness ?: renderSettings.skyboxBrightness

    /** 恢复纯色背景（config.backgroundColor）。 */
    fun clearSkybox() {
        skyboxSource = null
        skyboxManager?.setColor(config.backgroundColor)
    }

    private fun decodePanoramaBitmap(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > SkyboxManager.MAX_SOURCE_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    // ── Public API (for AvatarController only, internal) ─────────────────

    /**
     * Load a VRM/GLB model from assets.
     *
     * With [autoRenderMode] (fresh model switches) the render mode is decided
     * by the model's own materials — MToon materials load as MTOON, anything
     * else as PBR — overriding the pushed setting and reporting the choice via
     * [onRenderModeAutoDetected]. Without it (explicit reloads) the pushed
     * setting wins unchanged.
     *
     * @throws Exception if the model cannot be loaded.
     */
    fun loadModel(assetsPath: String, autoRenderMode: Boolean = false) {
        val assets = surfaceView.context.assets
        assets.open(assetsPath).use { input ->
            loadModelBytes(input.readBytes(), autoRenderMode)
        }
    }

    /**
     * Load a VRM/GLB model from a file on local storage — the entry point for
     * models imported at runtime into the app data dir (filesDir/vrms). Same
     * pipeline as [loadModel]; imported models behave exactly like built-ins.
     * @throws Exception if the model cannot be loaded.
     */
    fun loadModelFromFile(path: String, autoRenderMode: Boolean = false) {
        val file = java.io.File(path)
        if (!file.isFile) throw java.io.FileNotFoundException("model file not found: $path")
        file.inputStream().use { input ->
            loadModelBytes(input.readBytes(), autoRenderMode)
        }
    }

    /**
     * Everything after the GLB bytes are in hand: morph patcher, bone culling,
     * engine teardown/rebuild, VRM 0.x root flip, expression/springbone/gaze/
     * breath/mimic binding. Shared by [loadModel] and [loadModelFromFile].
     */
    private fun loadModelBytes(bytes: ByteArray, autoRenderMode: Boolean) {
        val buffer = ByteBuffer.wrap(bytes)

        // Pre-process: inject default morph weights so gltfio uploads morph target
        // normals (gltfio skips that upload when mesh.weights_count == 0, which
        // corrupts shading as soon as an expression drives a weight non-zero).
        buffer.rewind()
        val morphPatched = GlbMorphPatcher.injectMorphDefaultWeights(buffer)

        // Pre-process: cull unused bones to stay within Filament's 256 bone limit
        val loadBuffer = GlbBoneCuller.cullUnusedBones(morphPatched)
            ?: morphPatched.also { it.rewind() }

        // Pre-process: parse the VRM material extensions. The parse decides two
        // things: with [autoRenderMode], whether this fresh load switches the
        // render mode to match the model (MToon materials → MTOON, else PBR —
        // the model's own look wins over the pushed setting on every model
        // switch; manual mode changes reload with autoRenderMode=false and
        // keep the pushed mode); and — in the effective MTOON mode — which
        // primitives get duplicated as outline pass slots.
        // Runs AFTER the bone culler — the culler rewrites joint accessors
        // shared by primitives, and duplicating first would make it process
        // the shared accessors twice.
        var mtoonParse: GlbMToon.ParseResult? = null
        var finalBuffer = loadBuffer
        try {
            // readJson on a duplicate: leaves loadBuffer's position untouched
            val gltfJson = GlbMToon.readJson(loadBuffer.duplicate())
            if (autoRenderMode) {
                val detected = GlbMToon.detectRenderMode(gltfJson)
                if (detected != renderSettings.renderMode) {
                    renderSettings = renderSettings.copy(renderMode = detected)
                    onRenderModeAutoDetected?.invoke(detected)
                }
            }
            val materials = gltfJson?.let { GlbMToon.parseMaterials(it) }
            if (renderSettings.renderMode == RenderMode.MTOON && !materials.isNullOrEmpty()) {
                val json = gltfJson ?: error("GLB json unreadable for MToon patch")
                val (patched, layouts) = GlbMToon.patchGlb(loadBuffer, json, materials)
                if (layouts.isNotEmpty()) {
                    finalBuffer = patched
                    mtoonParse = GlbMToon.ParseResult(
                        isVrm0 = parseVrmMetaVersion(bytes) == "0",
                        materials = materials,
                        meshLayouts = layouts,
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "MToon GLB patch failed, falling back to PBR", e)
            mtoonParse = null
            finalBuffer = loadBuffer
        }

        // Clear old physics and animation state before loading the new model.
        // This prevents the Choreographer from trying to access destroyed entities
        // if the ensuing initialization crashes or throws an exception.
        stopAnimation()
        stopVrmaAnimation()
        vrmaEngine = null
        idleSource = null
        currentVrmaPath = null
        prevOneShotActive = false
        expressionManager = null
        lookAtEngine = null
        breathEngine = null
        mimicEngine = null
        springBoneManager = null
        animator = null
        contactPose.reset()

        // 配饰先于旧资产销毁：配饰 re-parent 在骨骼层级内，旧骨骼实体随
        // destroyModel 失效（对齐 AvatarRuntime 的换装顺序）。
        accessoryManager?.destroyAll()
        accessoryManager = null
        boneSemantics = emptyMap()
        onAccessoriesUpdated?.invoke(emptyList())

        // Free the outgoing model BEFORE the MToon applier: filament refuses
        // Engine.destroyMaterialInstance while any live renderable still holds
        // the instance (PreconditionPanic "still in use by Renderable"), and
        // the MToon instances live on the outgoing asset's renderables.
        // loadModelGlb destroys the old asset only inside itself — one step
        // too late (SIGABRT on every MToon→MToon model switch, 2026-10-09).
        modelViewer.destroyModel()
        mtoonApplier?.destroy()
        mtoonApplier = null
        mtoonActive = false

        // Load model into Filament
        modelViewer.loadModelGlb(finalBuffer)
        modelViewer.transformToUnitCube()

        // Get animation controller
        animator = modelViewer.animator

        // Default: play first animation if available
        if ((animator?.animationCount ?: 0) > 0) {
            playAnimation(0, loop = true)
        }

        // Initialize VRMA engine and bind to model
        vrmaEngine = VrmaAnimationEngine(modelViewer.engine)
        // 模型切换 = 骨架/根矩阵全部重建：走动立即停止（步态配置保留，
        // 素材是 assets 路径与模型无关）
        activeGait = null
        locomotionEngine.stopWalk()
        modelViewer.asset?.let { asset ->
            vrmaEngine?.bindToModel(asset, bytes)

            // VRM 0.x models face the opposite direction from VRM 1.0.
            // Apply a 180° Y rotation to the root so the character faces the camera.
            if (vrmaEngine?.getVrmMetaVersion() == "0") {
                val rootEntity = asset.root
                val tm = modelViewer.engine.transformManager
                val rootInstance = tm.getInstance(rootEntity)
                if (rootInstance != 0) {
                    val rootMat = FloatArray(16)
                    tm.getTransform(rootInstance, rootMat)
                    // 180° Y rotation matrix (cos180=-1, sin180=0):
                    //   [-1  0  0]     flips X and Z
                    //   [ 0  1  0]
                    //   [ 0  0 -1]
                    val rot180 = floatArrayOf(
                        -1f, 0f, 0f, 0f,
                         0f, 1f, 0f, 0f,
                         0f, 0f,-1f, 0f,
                         0f, 0f, 0f, 1f
                    )
                    // Multiply: newTransform = rootMat * rot180
                    val result = FloatArray(16)
                    for (row in 0..3) {
                        for (col in 0..3) {
                            var sum = 0f
                            for (k in 0..3) sum += rootMat[row + k * 4] * rot180[k + col * 4]
                            result[row + col * 4] = sum
                        }
                    }
                    tm.setTransform(rootInstance, result)
                    android.util.Log.i("SoulLinkRenderer", "Applied 180° Y rotation for VRM 0.x model")
                }
            }

            // Initialize expression (morph target / blend shape) manager
            expressionManager = VrmExpressionManager(modelViewer.engine).also { exprMgr ->
                exprMgr.parseFromGlb(bytes)
                exprMgr.bindToAsset(asset, bytes)
            }

            // Initialize spring bone physics manager
            if (config.enableSpringBone) {
                springBoneManager = VrmSpringBoneManager(modelViewer.engine).also { mgr ->
                    mgr.parseFromGlb(bytes)
                    mgr.bindToAsset(asset, bytes)
                }
            }

            // Resolve the humanoid hips bone for hips-drag (mouse.html semantics)
            isVrm0 = parseVrmMetaVersion(bytes) == "0"
            hipsEntity = resolveHipsEntity(asset, bytes)
            // 换模型丢弃上一个角色的拖拽位移，基线重新捕获
            hipsDragOffset.fill(0f)
            hipsDragState = HipsDragOffsetSolver.initial()

            // Resolve the bones the camera-shot driver frames against
            // (chest falls back spine → upperChest is tried first).
            headEntity = resolveHumanoidEntity(asset, bytes, "head")
            chestEntity = resolveHumanoidEntity(asset, bytes, "upperChest", "chest", "spine")
            spineEntity = resolveHumanoidEntity(asset, bytes, "spine")
            neckEntity = resolveHumanoidEntity(asset, bytes, "neck")

            // 配饰管理器（P1.5 单人桥接）：与团播同构——一次解析全语义表供挂点
            // 查询（HumanoidBoneResolver 与 AvatarRuntime 共用），管理器随骨架
            // 重建；spec 解析器/碰撞缝经回调注入，跨模型保留。
            boneSemantics = com.neethu.corelib.stage.internal.HumanoidBoneResolver
                .resolveAll(asset, bytes)
            ensureAccessoryLoaders()
            accessoryManager = AccessoryManager(
                host = accessoryHost,
                ownerTag = "single",
                resolveBone = { semantic -> boneSemantics[semantic.lowercase()] ?: 0 },
                resolveSpec = { accId -> accessorySpecResolver?.invoke(accId) },
                springCollider = { spec, boneEntity, register ->
                    val mgr = springBoneManager
                    if (mgr != null && boneEntity != 0) {
                        if (register) {
                            mgr.addExternalCollider(boneEntity, spec.position, spec.springColliderRadius)
                        } else {
                            mgr.removeExternalCollider(boneEntity)
                        }
                    }
                },
            ).also { mgr ->
                mgr.onChange = { onAccessoriesUpdated?.invoke(mgr.accessories.value) }
            }
            onAccessoriesUpdated?.invoke(emptyList())

            // Gaze overlay engine — bound after the VRM 0.x root flip so the
            // captured rest orientation already faces the camera (+Z).
            lookAtEngine = VrmLookAtEngine(modelViewer.engine).also { gaze ->
                gaze.bind(
                    headEntity,
                    resolveHumanoidEntity(asset, bytes, "neck"),
                    resolveHumanoidEntity(asset, bytes, "leftEye"),
                    resolveHumanoidEntity(asset, bytes, "rightEye"),
                )
            }

            // Breath overlay engine — spine/shoulder chain on the same
            // strip-then-overlay paradigm, bound after the VRM 0.x root
            // flip like the gaze engine (world-axis offsets assume the
            // model faces +Z).
            breathEngine = VrmBreathEngine(modelViewer.engine).also { breath ->
                breath.bind(
                    resolveHumanoidEntity(asset, bytes, "spine"),
                    resolveHumanoidEntity(asset, bytes, "chest"),
                    resolveHumanoidEntity(asset, bytes, "upperChest"),
                    resolveHumanoidEntity(asset, bytes, "leftShoulder"),
                    resolveHumanoidEntity(asset, bytes, "rightShoulder"),
                )
                breath.setEnabled(breathEnabled)
                breath.setSpeaking(breathSpeaking)
                breath.setAmplitudeScale(breathAmplitudeScale)
                if (breathRateBpm > 0) breath.setRateHz(breathRateBpm / 60f)
            }

            // Mimic overlay engine（「模仿我」）——P1 头/颈+双臂 + P2 躯干三段
            // 与双锁骨的绝对方向驱动，与视线引擎一样在 VRM 0.x root 翻转之后
            // 绑定（faceLocalDir 假设模型面向 +Z）。缺骨优雅降级：缺头=头部
            // 不驱，缺手骨=小臂不驱，缺脊柱段=该段不参与分摊。
            mimicEngine = VrmPoseMimicEngine(modelViewer.engine).also { mimic ->
                mimic.bind(
                    neckEntity,
                    headEntity,
                    resolveHumanoidEntity(asset, bytes, "leftUpperArm"),
                    resolveHumanoidEntity(asset, bytes, "leftLowerArm"),
                    resolveHumanoidEntity(asset, bytes, "leftHand"),
                    resolveHumanoidEntity(asset, bytes, "rightUpperArm"),
                    resolveHumanoidEntity(asset, bytes, "rightLowerArm"),
                    resolveHumanoidEntity(asset, bytes, "rightHand"),
                    resolveHumanoidEntity(asset, bytes, "spine"),
                    resolveHumanoidEntity(asset, bytes, "chest"),
                    resolveHumanoidEntity(asset, bytes, "upperChest"),
                    resolveHumanoidEntity(asset, bytes, "leftShoulder"),
                    resolveHumanoidEntity(asset, bytes, "rightShoulder"),
                )
            }

            // A pending shot re-frames the freshly loaded character to the
            // same framing (shot survives model switches until cancelled).
            activeShot?.let { shot ->
                shotSteering = true
                shotDeadlineNanos = System.nanoTime() + SHOT_TIMEOUT_NANOS
                android.util.Log.i("SoulLinkRenderer", "Re-framing camera shot: $shot")
            }
        }

        // Load IBL: explicit asset path > built-in default > IBL_NONE disables
        // (AvatarConfig() 零资产开箱即得环境光，见 AvatarConfig.BUILT_IN_IBL)
        when (config.iblPath) {
            AvatarConfig.IBL_NONE -> Unit
            null -> loadEnvironment(AvatarConfig.BUILT_IN_IBL)
            else -> loadEnvironment(config.iblPath)
        }

        applyMaterialEnhancements()
        // 蒙皮/morph 顶点会离开 gltfio 的绑定姿态静态 culling 盒（眼球丢失
        // 根因，见 [avatarCullingEnabled] 注释）——按当前开关重新应用
        applyAvatarCulling()

        // MToon material swap — must run after the gltfio renderables exist.
        // The applier parses textures/materials from the ORIGINAL GLB bytes
        // (identical material JSON; the patched buffer only adds primitives).
        val parse = mtoonParse
        if (parse != null) {
            val factory = mtoonFactory ?: MToonMaterialFactory.create(modelViewer.engine)
                ?.also { mtoonFactory = it }
            if (factory != null) {
                val applier = MToonApplier(modelViewer.engine, modelViewer.asset!!, bytes, parse, factory)
                applier.apply()
                mtoonApplier = applier
                mtoonActive = true
                applyMToonKeyLight()
                // The applyViewSettings inside loadEnvironment ran while
                // mtoonActive was still false, so the MToon LINEAR tone mapping
                // (mtoonActive drives it, see applyViewSettings) was not yet in
                // effect — re-apply now that the swap actually happened.
                applyViewSettings()
            }
        }
    }

    /** Detects the VRM meta version ("0" or "1") from GLB bytes. */
    private fun parseVrmMetaVersion(glbBytes: ByteArray): String {
        return try {
            val json = parseGlbJson(glbBytes) ?: return "1"
            val ext = json.getAsJsonObject("extensions") ?: return "1"
            if (ext.has("VRMC_vrm")) "1" else if (ext.has("VRM")) "0" else "1"
        } catch (e: Exception) {
            "1"
        }
    }

    /**
     * Resolves a humanoid bone node entity by its VRM semantic name(s), trying
     * each in order (VRM 1.0: `VRMC_vrm.humanoid.humanBones.<name>.node`;
     * VRM 0.x: `VRM.humanoid.humanBones[]` with `bone == <name>`).
     * Returns 0 when none of [semantics] resolve.
     */
    private fun resolveHumanoidEntity(asset: FilamentAsset, glbBytes: ByteArray, vararg semantics: String): Int {
        return try {
            val json = parseGlbJson(glbBytes) ?: return 0
            val ext = json.getAsJsonObject("extensions") ?: return 0
            val nodes = json.getAsJsonArray("nodes")

            for (semantic in semantics) {
                var nodeIndex = -1

                // VRM 1.0
                ext.getAsJsonObject("VRMC_vrm")?.getAsJsonObject("humanoid")
                    ?.getAsJsonObject("humanBones")?.getAsJsonObject(semantic)
                    ?.get("node")?.asInt?.let { nodeIndex = it }

                // VRM 0.x
                if (nodeIndex < 0) {
                    ext.getAsJsonObject("VRM")?.getAsJsonObject("humanoid")
                        ?.getAsJsonArray("humanBones")?.forEach { el ->
                            val obj = el.asJsonObject
                            if (obj.get("bone")?.asString == semantic) {
                                nodeIndex = obj.get("node")?.asInt ?: -1
                            }
                        }
                }
                if (nodeIndex < 0 || nodes == null) continue

                val nodeName = nodes[nodeIndex].asJsonObject.get("name")?.asString ?: continue
                asset.getFirstEntityByName(nodeName)?.let { return it }
            }
            0
        } catch (e: Exception) {
            android.util.Log.w("SoulLinkRenderer", "Failed to resolve humanoid entity", e)
            0
        }
    }

    /** Resolves the humanoid hips node entity for hips-drag (three-vrm mouse.html semantics). */
    private fun resolveHipsEntity(asset: FilamentAsset, glbBytes: ByteArray): Int {
        return resolveHumanoidEntity(asset, glbBytes, "hips")
    }

    private fun parseGlbJson(glbBytes: ByteArray): JsonObject? {
        val buf = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.remaining() < 12) return null
        buf.int; buf.int; buf.int // magic, version, length
        if (buf.remaining() < 8) return null
        val chunkLen = buf.int
        val chunkType = buf.int
        if (chunkType != 0x4E4F534A) return null
        val jsonBytes = ByteArray(chunkLen)
        buf.get(jsonBytes)
        return Gson().fromJson(String(jsonBytes, Charsets.UTF_8), JsonObject::class.java)
    }

    fun loadEnvironment(iblPath: String) {
        try {
            val assets = surfaceView.context.assets
            assets.open(iblPath).use { input ->
                val bytes = input.readBytes()
                val buffer = ByteBuffer.wrap(bytes)
                val engine = modelViewer.engine

                val iblBundle = KTX1Loader.createIndirectLight(engine, buffer)
                val ibl = iblBundle.indirectLight
                if (ibl != null) {
                    modelViewer.scene.indirectLight = ibl
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to load IBL from $iblPath", e)
        }
        applyIblSettings()
        applyViewSettings()
    }

    /**
     * Play animation at [index].
     */
    fun playAnimation(index: Int, loop: Boolean = true) {
        currentAnimationIndex = index
        isAnimationLooping = loop
        startTime = System.nanoTime()
        pendingSpringReset = true
    }

    /**
     * Stop all animations.
     */
    fun stopAnimation() {
        currentAnimationIndex = -1
    }

    // ── Expression (Blend Shape / Morph Target) API ──────────────────────

    /**
     * Set expression weight.
     * @param name Expression name (e.g. "happy", "sad", "blink")
     * @param weight Weight value 0.0–1.0
     */
    fun setExpression(name: String, weight: Float) {
        expressionManager?.setExpression(name, weight)
    }

    /**
     * Clear all active expressions (return to neutral face).
     */
    fun clearAllExpressions() {
        expressionManager?.clearAllExpressions()
    }

    /**
     * Get list of available expression names from the loaded model.
     */
    fun getAvailableExpressions(): List<String> {
        return expressionManager?.getAvailableExpressions() ?: emptyList()
    }

    /**
     * Set the duration for expression transitions.
     * @param durationMs Transition duration in milliseconds. Use 0 for instant.
     */
    fun setExpressionTransitionDuration(durationMs: Long) {
        expressionManager?.setTransitionDuration(durationMs)
    }

    // ── Gaze (Look-At) API ───────────────────────────────────────────────

    /**
     * Track a world-space gaze target (typically the camera eye — where the
     * user's face is). The gaze overlay adds clamped, smoothed head/neck
     * rotation and snappy eye-bone rotation toward the target on top of
     * whatever animation is playing.
     */
    fun setLookAtTarget(x: Float, y: Float, z: Float) {
        lookAtEngine?.setTarget(x, y, z)
    }

    /** Stop tracking; the driven bones return to their rest locals. */
    fun clearLookAtTarget() {
        lookAtEngine?.clearTarget()
    }

    /** Last-frame gaze state for diagnostics. */
    fun lookAtInfo(): LookAtInfo? = lookAtEngine?.info()

    // ── Mimic API（「模仿我」）────────────────────────────────────────────

    /**
     * Feed one frame of mimic pose targets (avatar-world unit directions,
     * mirror semantics already baked in by the app lane). Thread-safe atomic
     * handoff — the render thread consumes the latest frame. `null` starts the
     * ease-back to the animated pose; a frame older than
     * [VrmPoseMimicEngine.HOLD_MS] triggers the same restore automatically.
     */
    fun setMimicPose(pose: MimicPose?) {
        mimicEngine?.setPose(pose)
    }

    /** Last-frame mimic state for diagnostics (`ai_cmd mimic_status`). */
    fun mimicInfo(): MimicInfo? = mimicEngine?.info()

    // ── Breath API ───────────────────────────────────────────────────────

    /**
     * Toggle the procedural breath overlay (chest/shoulder motion on top of
     * whatever animation plays). Default on; the setting survives model
     * switches.
     */
    fun setBreathEnabled(enabled: Boolean) {
        breathEnabled = enabled
        breathEngine?.setEnabled(enabled)
    }

    /**
     * Mark the avatar as speaking — breath turns shallower and faster while
     * talking (one-shot flags, no per-frame calls needed; the amplitude eases
     * over ~0.5 s). The session's FaceDriver feeds this automatically on
     * playback start/end.
     */
    fun setBreathSpeaking(speaking: Boolean) {
        breathSpeaking = speaking
        breathEngine?.setSpeaking(speaking)
    }

    /** 呼吸幅度倍率（0~3，1 = 设计幅度）；跨模型重载保留。 */
    fun setBreathAmplitudeScale(scale: Float) {
        breathAmplitudeScale = scale
        breathEngine?.setAmplitudeScale(scale)
    }

    /** 呼吸频率（次/分）；≤0 = 引擎默认；跨模型重载保留。 */
    fun setBreathRateBpm(bpm: Float) {
        breathRateBpm = bpm
        breathEngine?.setRateHz(bpm / 60f)
    }

    /** Last-frame breath state for diagnostics. */
    fun breathInfo(): BreathInfo? = breathEngine?.info()

    /**
     * @return the number of animations in the current model, or 0 if no model is loaded.
     */
    fun getAnimationCount(): Int = animator?.animationCount ?: 0

    // ── VRMA Animation API ───────────────────────────────────────────────

    /**
     * Load a VRMA animation from assets.
     * @param assetsPath Path to the .vrma file in assets.
     * @return true if loaded successfully.
     */
    fun loadVrmaAnimation(assetsPath: String): Boolean {
        return try {
            val assets = surfaceView.context.assets
            assets.open(assetsPath).use { input ->
                val bytes = input.readBytes()
                parseVrmaBytes(bytes, assetsPath)
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to load VRMA: $assetsPath", e)
            false
        }
    }

    /**
     * Load a VRMA animation from a file on local storage (e.g. the app's
     * external files dir), so large animation libraries don't have to ship
     * inside the APK.
     * @param path Absolute path to the .vrma file on the filesystem.
     * @return true if loaded successfully.
     */
    fun loadVrmaAnimationFromFile(path: String): Boolean {
        return try {
            val file = java.io.File(path)
            if (!file.isFile) {
                android.util.Log.e("SoulLinkRenderer", "VRMA file not found: $path")
                false
            } else {
                file.inputStream().use { input ->
                    parseVrmaBytes(input.readBytes(), path)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to load VRMA file: $path", e)
            false
        }
    }

    private fun parseVrmaBytes(bytes: ByteArray, source: String): Boolean {
        val animation = vrmaParser.parse(ByteBuffer.wrap(bytes))
        return if (animation != null) {
            vrmaEngine?.setAnimation(animation)
            // 记录当前剪辑路径（VrmaStarted/Finished 载荷；idle 走独立解析入口）
            currentVrmaPath = source
            android.util.Log.i("SoulLinkRenderer",
                "Loaded VRMA: ${animation.duration}s, ${animation.humanoidTracks.size} bone tracks")
            true
        } else {
            android.util.Log.e("SoulLinkRenderer", "Failed to parse VRMA: $source")
            false
        }
    }

    /**
     * Set the looping idle animation from assets — what the model returns to
     * after a one-shot VRMA (LLM gesture) or a manual stop. Without an idle
     * the model falls back to its rest pose. Does not interrupt playback.
     */
    fun setVrmaIdleAnimation(assetsPath: String): Boolean {
        if (idleSource == assetsPath) return true
        return try {
            val assets = surfaceView.context.assets
            assets.open(assetsPath).use { input ->
                parseVrmaIdleBytes(input.readBytes(), assetsPath)
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to load idle VRMA: $assetsPath", e)
            false
        }
    }

    /** Same as [setVrmaIdleAnimation] but from an absolute file path. */
    fun setVrmaIdleAnimationFromFile(path: String): Boolean {
        if (idleSource == path) return true
        return try {
            val file = java.io.File(path)
            if (!file.isFile) {
                android.util.Log.e("SoulLinkRenderer", "Idle VRMA file not found: $path")
                false
            } else {
                file.inputStream().use { input ->
                    parseVrmaIdleBytes(input.readBytes(), path)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to load idle VRMA file: $path", e)
            false
        }
    }

    /** Drop the idle; one-shots and stops return to the rest pose again. */
    fun clearVrmaIdleAnimation() {
        idleSource = null
        vrmaEngine?.setIdleAnimation(null)
    }

    private fun parseVrmaIdleBytes(bytes: ByteArray, source: String): Boolean {
        val animation = vrmaParser.parse(ByteBuffer.wrap(bytes))
        return if (animation != null) {
            vrmaEngine?.setIdleAnimation(animation)
            idleSource = source
            android.util.Log.i("SoulLinkRenderer",
                "Loaded idle VRMA: ${animation.duration}s, ${animation.humanoidTracks.size} bone tracks")
            true
        } else {
            android.util.Log.e("SoulLinkRenderer", "Failed to parse idle VRMA: $source")
            false
        }
    }

    /**
     * Start playing the loaded VRMA animation.
     * Stops any built-in animation that's playing. [speed] scales the clip
     * clock (walk locomotion passes groundSpeed/baseSpeed so footsteps match
     * the actual travel); the default keeps gesture behavior unchanged.
     *
     * P2 走动中手势 overlay（边走边比划，§5.3）：走动中收到 one-shot 手势
     * （[loop]=false 且无显式掩码）时自动以「上半身 mask + 淡入」叠加在步态
     * 上——腿继续走，上半身比划，播完引擎自动淡出回纯步态。[allowWalkOverlay]
     * =false 退出该判定（走动内部分播走 [playVrmaAnimationInternal]）。
     */
    fun playVrmaAnimation(
        loop: Boolean = true,
        speed: Float = 1f,
        fadeSeconds: Float = 0f,
        boneMask: Set<String>? = null,
        allowWalkOverlay: Boolean = true,
    ) {
        // Stop built-in animation
        currentAnimationIndex = -1
        // Start VRMA
        vrmaStartTime = System.nanoTime()
        val overlayGesture = allowWalkOverlay && !loop && boneMask == null &&
            gestureOverlayEnabled &&
            locomotionEngine.snapshot().phase != com.neethu.corelib.WalkPhase.IDLE
        val mask = if (overlayGesture) VrmaAnimationEngine.UPPER_BODY_BONES else boneMask
        val fade = if (overlayGesture && fadeSeconds <= 0f) 0.2f else fadeSeconds
        if (overlayGesture) {
            android.util.Log.i("SoulLinkRenderer", "gesture overlay while walking (upper-body mask, fade=$fade s)")
        }
        vrmaEngine?.play(loop, speed, fade, mask, null)
        pendingSpringReset = true
        notifyVrmaStarted()
    }

    /**
     * VrmaStarted 统一发射点（公开播放入口与走动内部播放共用，对齐团播
     * AvatarRuntime）：引擎确有剪辑在播才发；先同步喂配饰状态机（动画联动
     * 同帧出道具）。
     */
    private fun notifyVrmaStarted() {
        if (vrmaEngine?.isActive() != true) return
        val path = currentVrmaPath ?: return
        accessoryManager?.onVrmaStarted(path)
    }

    /**
     * Stop the VRMA animation. With an idle configured the engine resumes the
     * idle loop, so restart the playback clock for a clean phase.
     * P2：[fadeSeconds] > 0 时淡入淡出（步态淡回 idle / 手势 overlay 淡出）。
     */
    fun stopVrmaAnimation(fadeSeconds: Float = 0f) {
        vrmaEngine?.stop(fadeSeconds)
        vrmaStartTime = System.nanoTime()
        pendingSpringReset = true
    }

    /**
     * Duration of the currently loaded VRMA animation in seconds
     * (0 when none is loaded).
     */
    fun getVrmaDuration(): Float = vrmaEngine?.getDuration() ?: 0f

    // ── Scene (Environment/Background) API ────────────────────────────────

    /**
     * Load a GLB scene (environment/background) from assets.
     * The scene is rendered alongside the current model in the same Filament scene.
     *
     * [placement] composes a one-time rigid alignment (Y rotation + offset,
     * calibrated once via the `scene_offset`/`scene_rot` ai_cmd probes and
     * persisted as `assets/scene/<scene>.meta.json`) on top of the scene's own
     * root transform, and carries the walkable bounds used by locomotion.
     * `null` keeps the previous behavior (scene loaded as-is).
     *
     * @param assetsPath Path to the .glb scene file in assets.
     */
    fun loadScene(assetsPath: String, placement: ScenePlacement? = null) {
        // Remove any existing scene first
        removeScene()
        scenePlacement = placement
        sceneBaseMatrix = null

        val engine = modelViewer.engine

        // Create dedicated loaders for scene if needed
        if (sceneAssetLoader == null) {
            val materialProvider = UbershaderProvider(engine)
            sceneAssetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
        }
        if (sceneResourceLoader == null) {
            sceneResourceLoader = ResourceLoader(engine, true)
        }

        try {
            val assets = surfaceView.context.assets
            assets.open(assetsPath).use { input ->
                val bytes = input.readBytes()
                val buffer = ByteBuffer.wrap(bytes)

                val asset = sceneAssetLoader?.createAsset(buffer)
                if (asset != null) {
                    sceneResourceLoader?.asyncBeginLoad(asset)
                    asset.releaseSourceData()
                    sceneAsset = asset
                    // 防遮挡候选：优先剖切 sidecar（<场景>.cutaway.json 声明组），
                    // 缺失回退节点名前缀发现（墙体/玻璃/天花板/背景）
                    val cutaway = runCatching {
                        surfaceView.context.assets.open(
                            assetsPath.substringBeforeLast('.') + ".cutaway.json",
                        ).use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull()
                    sceneOcclusion.attach(asset, cutaway)
                    applyScenePlacement()
                    android.util.Log.i("SoulLinkRenderer",
                        "Scene loaded: $assetsPath (${asset.entities.size} entities)" +
                            ", occluders=${sceneOcclusion.occluderCount}" +
                            (placement?.let { ", placement(yaw=${it.yawDeg}, off=${it.offsetX}/${it.offsetY}/${it.offsetZ})" } ?: "")
                    )
                } else {
                    android.util.Log.e("SoulLinkRenderer", "Failed to create scene asset: $assetsPath")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to load scene: $assetsPath", e)
        }
    }

    /** Live-adjust the scene placement (calibration probes); re-applies from base. */
    fun setScenePlacement(placement: ScenePlacement?) {
        scenePlacement = placement
        applyScenePlacement()
    }

    fun getScenePlacement(): ScenePlacement? = scenePlacement

    /**
     * Compose the placement onto the captured scene-root base matrix:
     * `world = T(offset) · Ry(yaw) · base`. The base matrix is snapshotted
     * once per load (identity for plain GLBs, or the glTF scene transform) so
     * repeated adjustments never accumulate.
     */
    private fun applyScenePlacement() {
        val asset = sceneAsset ?: return
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return
        if (sceneBaseMatrix == null) {
            val base = FloatArray(16)
            tm.getTransform(instance, base)
            sceneBaseMatrix = base
        }
        val mat = sceneBaseMatrix!!.clone()
        scenePlacement?.let { p ->
            if (p.yawDeg != 0f) {
                val yaw = toRadians(p.yawDeg)
                val c = cos(yaw)
                val s = sin(yaw)
                for (col in 0..2) {
                    val i = col * 4
                    val x = mat[i]
                    val z = mat[i + 2]
                    mat[i] = c * x + s * z
                    mat[i + 2] = -s * x + c * z
                }
            }
            mat[12] += p.offsetX
            mat[13] += p.offsetY
            mat[14] += p.offsetZ
        }
        tm.setTransform(instance, mat)
        // 场景根动了 → 遮挡候选的世界 AABB 缓存全部失效
        sceneOcclusion.invalidateWorldAabbs()
    }

    /**
     * Remove the currently loaded scene from the Filament scene.
     */
    fun removeScene() {
        sceneAsset?.let { asset ->
            // 先还原仍被隐藏的墙（layerMask/阴影），再摘实体销毁资产
            sceneOcclusion.detach()
            sceneResourceLoader?.asyncCancelLoad()
            sceneResourceLoader?.evictResourceData()
            modelViewer.scene.removeEntities(asset.entities)
            sceneAssetLoader?.destroyAsset(asset)
            android.util.Log.i("SoulLinkRenderer", "Scene removed")
        }
        sceneAsset = null
        sceneBaseMatrix = null
    }

    /**
     * Progressively add scene entity renderables to the Filament scene
     * as their textures/resources become ready.
     */
    private fun populateSceneEntities() {
        sceneResourceLoader?.asyncUpdateLoad()
        sceneAsset?.let { asset ->
            var count: Int
            do {
                count = asset.popRenderables(sceneReadyRenderables)
                if (count > 0) {
                    modelViewer.scene.addEntities(sceneReadyRenderables.take(count).toIntArray())
                }
            } while (count > 0)
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    private var isRendering = false

    private fun startRendering() {
        isRendering = true
        Choreographer.getInstance().postFrameCallback(choreoCallback)
    }

    private fun stopRendering() {
        isRendering = false
        Choreographer.getInstance().removeFrameCallback(choreoCallback)
    }

    override fun onResume(owner: LifecycleOwner) {
        startRendering()
    }

    override fun onPause(owner: LifecycleOwner) {
        stopRendering()
    }

    /**
     * onDestroy 的护栏（幂等 + 视图已拆除即跳过）。销毁会从两条路径各到一次：
     * lifecycle ON_DESTROY 先清一遍；随后 SurfaceView 从窗口拆除触发
     * filament-utils ModelViewer 的内建 detach listener（addDetachListener，
     * 1.68.3 ModelViewer.kt:392）执行全套销毁并 engine.destroy()；最后
     * AvatarView 的 onRelease 还会再调一次 [onDestroy]——此时引擎已
     * shutdown，destroyModel → ResourceLoader asyncCancelLoad → flushAndWait
     * 直接 SIGABRT（gltfio 1.68.3 ResourceLoader.cpp:488，真机实证）。
     * PreconditionPanic 是 native abort，Java 层捕获不了，只能保证不再进去：
     * - destroyed：本次 renderer 已清理过，重复调用直接跳过；
     * - !isAttachedToWindow：view 已拆 ⇒ ModelViewer 内建 listener 必已把
     *   引擎销毁（两路径谁先谁后都成立），同样不能碰任何引擎调用。
     */
    private var destroyed = false

    override fun onDestroy(owner: LifecycleOwner) {
        if (destroyed || !surfaceView.isAttachedToWindow) return
        destroyed = true
        stopRendering()
        shotSteering = false
        activeShot = null
        // 配饰先走（renderable 持材质实例 + 挂在骨骼层级内，先于模型销毁）
        accessoryManager?.destroyAll()
        accessoryManager = null
        // 特效批渲染：摘 renderable → 毁 VB/IB → 毁材质实例 → 毁材质/纹理
        // （坑 13 顺序；引擎随 ModelViewer detach listener shutdown 前必须跑完）
        vfxManager?.destroy()
        vfxManager = null
        // 天空盒（skybox/cubemap/位图/转换器；同样要求引擎在位）
        skyboxManager?.destroy()
        skyboxManager = null
        skyboxSource = null
        onEffectsUpdated?.invoke(emptyList())
        // Same in-use precondition as in loadModelBytes: the avatar's
        // renderables still hold the MToon material instances, so the model
        // must go first or destroyMaterialInstance aborts.
        modelViewer.destroyModel()
        mtoonApplier?.destroy()
        mtoonApplier = null
        mtoonFactory?.destroy()
        mtoonFactory = null
        mtoonActive = false
        removeScene()
        sceneResourceLoader?.destroy()
        sceneResourceLoader = null
        sceneAssetLoader?.destroy()
        sceneAssetLoader = null
        accessoryResourceLoader?.destroy()
        accessoryResourceLoader = null
        accessoryAssetLoader?.destroy()
        accessoryAssetLoader = null
        accessoryMaterialProvider = null
        springBoneManager = null

        val engine = modelViewer.engine
        listOf(keyLightEntity, fillLightEntity, rimLightEntity)
            .filter { it != 0 }
            .forEach { entity ->
                modelViewer.scene.removeEntity(entity)
                engine.destroyEntity(entity)
            }
        keyLightEntity = 0
        fillLightEntity = 0
        rimLightEntity = 0
        keyShadowSignature = null
        colorGrading?.let { engine.destroyColorGrading(it) }
        colorGrading = null
        appliedToneMapping = null
    }

    // ── Spring Bone API ──────────────────────────────────────────────────

    fun setSpringBoneEnabled(enabled: Boolean) {
        springBoneManager?.setEnabled(enabled)
    }

    // ── Accessory API（P1.5 单人桥接；语义与团播 AvatarHandle 同面）──────

    private fun ensureAccessoryLoaders() {
        if (accessoryAssetLoader == null) {
            val provider = UbershaderProvider(modelViewer.engine)
            accessoryMaterialProvider = provider
            accessoryAssetLoader = AssetLoader(modelViewer.engine, provider, EntityManager.get())
        }
        if (accessoryResourceLoader == null) {
            accessoryResourceLoader = ResourceLoader(modelViewer.engine, true)
        }
    }

    fun attachAccessory(spec: AccessorySpec): Result<Unit> {
        val mgr = accessoryManager
            ?: return Result.failure(IllegalStateException("no model loaded"))
        return mgr.attach(spec)
    }

    fun detachAccessory(id: String): Boolean = accessoryManager?.detach(id) ?: false

    fun setAccessoryVisible(id: String, visible: Boolean): Boolean =
        accessoryManager?.setVisible(id, visible) ?: false

    fun bindAccessoryToAnimation(accessoryId: String, animationClipPath: String): Boolean =
        accessoryManager?.bindToAnimation(accessoryId, animationClipPath) ?: false

    fun unbindAccessoryFromAnimation(accessoryId: String, animationClipPath: String): Boolean =
        accessoryManager?.unbindFromAnimation(accessoryId, animationClipPath) ?: false

    val accessoryAnimationBindings: Map<String, String>
        get() = accessoryManager?.animationBindings ?: emptyMap()

    fun accessoryStates(): List<AccessoryState> =
        accessoryManager?.accessories?.value ?: emptyList()

    // ── VFX 特效（世界系；播放/停止/快照，docs/vfx-feasibility.md）──────

    /**
     * 播放一个特效。首次调用惰性初始化渲染器（材质编译 + 批缓冲）。
     * @return 句柄；null = 渲染未就绪或对应混合档批容量已满。
     */
    fun playVfx(spec: VfxSpec, anchor: VfxAnchor): VfxHandle? {
        val mgr = vfxManager ?: VfxManager(vfxHost).also { mgr ->
            mgr.onChange = { onEffectsUpdated?.invoke(mgr.effects.value) }
            vfxManager = mgr
        }
        return mgr.play(spec, anchor)
    }

    /** 停止一个特效（凭 [VfxHandle.id]）。 */
    fun stopVfx(id: String): Boolean = vfxManager?.stop(id) ?: false

    /** 停止全部特效。 */
    fun stopAllVfx() {
        vfxManager?.stopAll()
    }

    /** 活跃特效实时快照（调试用；稳定观察走 [onEffectsUpdated] 中继）。 */
    fun vfxSnapshot(): List<VfxInfo> = vfxManager?.snapshot() ?: emptyList()

    private val avatarWorldMat = FloatArray(16)

    /**
     * 角色实时世界位置（脚底，米；asset.root 世界变换平移列）。fx play 的
     * 缺省锚点数据源——自由走动/换模型后角色不在原点，锚原点 = 生成在取景框
     * 外（2026-10-17 真机首验教训）。false = 无模型。
     */
    fun avatarWorldPos(out: FloatArray): Boolean {
        val asset = modelViewer.asset ?: return false
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return false
        tm.getWorldTransform(instance, avatarWorldMat)
        out[0] = avatarWorldMat[12]
        out[1] = avatarWorldMat[13]
        out[2] = avatarWorldMat[14]
        return true
    }

    // ── 头发弹簧骨骼风场馈送（画质升级 2026-10-17）──────────────────────

    private val windSample = FloatArray(2)

    /**
     * 环境风 → 弹簧骨骼外力：有活跃带风特效时取 VfxManager 全局风时钟的同源
     * 采样（阵风与雪/花瓣同相），按头发链长折算为安全小加速度馈送；无活跃
     * 风特效清零。限幅防 Verlet 失稳（docs/vfx-quality-upgrade.md §3.4）。
     */
    private fun feedSpringBoneWind() {
        val mgr = springBoneManager ?: return
        val fed = if (avatarWorldPos(windPosScratch) &&
            vfxManager?.ambientWind(windPosScratch[0], windPosScratch[2], windSample) == true
        ) {
            val k = SPRINGBONE_WIND_GAIN
            mgr.setWindAccel(
                (windSample[0] * k).coerceIn(-SPRINGBONE_WIND_MAX, SPRINGBONE_WIND_MAX),
                0f,
                (windSample[1] * k).coerceIn(-SPRINGBONE_WIND_MAX, SPRINGBONE_WIND_MAX),
            )
            true
        } else {
            false
        }
        if (!fed) mgr.setWindAccel(0f, 0f, 0f)
    }

    private val windPosScratch = FloatArray(3)

    // ── Frustum Culling (avatar) ─────────────────────────────────────────

    /**
     * 角色自身 renderable 的视锥剔除开关，默认**关闭**。
     *
     * 根因（真机复现「多轮对话后眼球丢失、透过眼窝看到后脑勺内壁」）：
     * gltfio 给蒙皮 mesh 的 culling 包围盒是绑定姿态的静态盒（POSITION
     * min/max），而 RenderableManager.Builder 的合同是蒙皮/morph 下该盒
     * "should encompass all possible vertex positions"。眼球顶点蒙皮到眼骨
     * （注视旋转/头部动画驱动），与静态盒最多差几厘米；特写/宏距机位视锥
     * 很窄，运镜 steering 追头部枢轴+注视转头让相机在目标位姿附近摆动，
     * 某些位姿下眼球 renderable 的静态盒整体落到锥外→整个 mesh 被剔除
     * ——脸其余部分（包围盒大）仍在，于是眼窝变空。眼球是最小的蒙皮
     * mesh（SK_Sun 独立眼球 mesh，盒仅 ~2.5cm），所以最先消失。
     *
     * 角色常驻画面中央、体量小（数万三角面），关掉剔除没有可观测代价；
     * 静态环境 scene 资产走独立 loader，保留自身剔除不受影响。
     * `ai_cmd culling on/off` 可在真机上 A/B 复现/验证。
     */
    private var avatarCullingEnabled = false

    /**
     * Toggle frustum culling for every renderable of the loaded avatar
     * (default off — see [avatarCullingEnabled]). Applies immediately and to
     * subsequently loaded models.
     */
    fun setAvatarCulling(enabled: Boolean) {
        avatarCullingEnabled = enabled
        applyAvatarCulling()
    }

    private fun applyAvatarCulling() {
        val asset = modelViewer.asset ?: return
        val rm = modelViewer.engine.renderableManager
        var toggled = 0
        for (entity in asset.entities) {
            val instance = rm.getInstance(entity)
            if (instance == 0) continue
            rm.setCulling(instance, avatarCullingEnabled)
            toggled++
        }
        android.util.Log.i(
            "SoulLinkRenderer",
            "Avatar frustum culling ${if (avatarCullingEnabled) "enabled" else "disabled"} ($toggled renderables)",
        )
    }

    /**
     * When true (default), drag mode translates the humanoid hips bone instead of
     * the asset root — three-vrm mouse.html semantics. The spring bones see real
     * body motion and react with full swings; works for models with or without
     * spring `center` nodes. When false, drag mode translates the asset root
     * (models with a `center` node will show little to no reaction, per VRM spec).
     */
    fun setDragMovesHips(enabled: Boolean) {
        if (dragMovesHips == enabled) return
        dragMovesHips = enabled
        // 切换拖拽目标（hips/root）丢弃 hips 拖拽状态与已累计位移，
        // 避免重新启用时把位移重复计进基线
        clearHipsDragOffset()
    }

    private fun clearHipsDragOffset() {
        hipsDragOffset.fill(0f)
        hipsDragState = HipsDragOffsetSolver.initial()
    }

    /**
     * 本帧动画分支前的 hips 局部平移快照，供 [applyHipsDragOffset] 判定
     * 「动画是否在本帧改写了 hips 平移」。`dragMovesHips` 关闭或无 hips 时
     * 返回 null（此时走 asset-root 拖拽，动画从不写 root，无需检测）。
     */
    private fun captureHipsTranslation(): FloatArray? {
        if (!dragMovesHips || hipsEntity == 0) return null
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(hipsEntity)
        if (instance == 0) return null
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        return floatArrayOf(mat[12], mat[13], mat[14])
    }

    /**
     * 每帧在动画覆写之后重钉拖拽位移（挪动人物 bug 的修复本体，判定逻辑在
     * [HipsDragOffsetSolver]）：以本帧动画刚写出的 hips 平移为基线叠加位移
     * 再落盘，基线与位移都没变时不重复写（不逐帧累加）。必须在动画分支之后、
     * 注视/骨骼矩阵传播之前调用。
     */
    private fun applyHipsDragOffset(hipsBefore: FloatArray?) {
        if (!dragMovesHips || hipsEntity == 0) {
            hipsDragState = HipsDragOffsetSolver.initial()
            return
        }
        val engineRewrote = vrmaEngine?.consumeHipsRewritten() == true
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(hipsEntity)
        if (instance == 0) return
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        val (newState, target) = HipsDragOffsetSolver.solve(
            before = hipsBefore,
            current = floatArrayOf(mat[12], mat[13], mat[14]),
            rewritten = engineRewrote,
            offset = hipsDragOffset,
            state = hipsDragState,
        )
        hipsDragState = newState
        if (target != null) {
            mat[12] = target[0]
            mat[13] = target[1]
            mat[14] = target[2]
            tm.setTransform(instance, mat)
        }
    }

    // ── Programmatic Avatar & Camera Control (AI debugging) ──────────────
    // These mirror the touch-driven behaviors: moveAvatar replicates drag-mode
    // translation, and the camera calls replicate the gestures that
    // GestureDetector feeds into the manipulator (single-finger drag → orbit,
    // two-finger midpoint drag → pan, pinch spread → dolly zoom).

    /**
     * Translate the avatar root in world space (meters).
     * +X right, +Y up, +Z toward the camera.
     */
    fun moveAvatar(dx: Float, dy: Float, dz: Float) {
        val asset = modelViewer.asset ?: return
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        mat[12] += dx
        mat[13] += dy
        mat[14] += dz
        tm.setTransform(instance, mat)
    }

    // ── Locomotion（自由走动，P0，docs/locomotion-feasibility.md）─────────

    /**
     * Parse a gait clip and build its [WalkGait] (方案 C 提取分析在内)。
     *
     * [baseSpeed] ≤ 0 时自动标定：带烘焙位移的素材（非原地）用
     * 净前向/周期求速度（提取器副产品，无需 walk-calib）；原地素材无
     * 提取器时必须显式给速度。烘焙位移低于阈值的素材按原地处理。
     */
    private fun parseWalkGait(assetPath: String, baseSpeed: Float, kind: WalkGaitKind): WalkGait? {
        val bytes = surfaceView.context.assets.open(assetPath).use { it.readBytes() }
        val animation = vrmaParser.parse(ByteBuffer.wrap(bytes))
        if (animation == null || animation.duration <= 0f) {
            android.util.Log.e("SoulLinkRenderer", "Walk gait parse failed: $assetPath")
            return null
        }
        val vrmFlip = vrmaEngine?.getVrmMetaVersion() == "0"
        val modelHipsY = vrmaEngine?.modelHipsHeight() ?: 1f
        val animHipsY = animation.restHipsPosition[1]
        val heightScale = if (animHipsY > 0.01f) modelHipsY / animHipsY else 1f
        val renderScale = hipsRenderScale()
        val hipsTrack = animation.humanoidTracks.firstOrNull {
            it.boneName == "hips" && it.path == "translation"
        }
        val extractor = LocomotionExtractor.analyze(
            hipsTrack, animation.restHipsPosition, vrmFlip, heightScale, animation.duration
        )
        // 方案 C 标定（P0 道具验收②r2）：播放速比的分母必须用**提取口径**——
        // 回灌位移来自素材烘焙（提取单位×渲染尺度），显式 walk-calib 数值是
        // 程序位移时代的脚步口径,两者在跨比例重定向的模型上会差 ~25%（r2 实测
        // treadmill 1.22 vs 提取 1.5@1×,实际步速 1.25×commanded）→ 刹车曲线
        // 失配,末段绕目标振荡不收敛。有提取器一律用提取速率（actual=commanded,
        // 刹车/转弯封顶的引擎模型与真实执行一致）;原地素材（无提取器,程序位移）
        // 仍用显式标定。
        val extractedSpeed = extractor?.let { it.netForwardMeters * renderScale / it.duration } ?: 0f
        val speed = if (extractedSpeed > 0f) extractedSpeed else baseSpeed
        if (speed <= 0f) {
            android.util.Log.e(
                "SoulLinkRenderer",
                "Walk gait '$assetPath' needs an explicit baseSpeed (in-place clip, no calibration given)"
            )
            return null
        }
        if (extractor != null) {
            android.util.Log.i(
                "SoulLinkRenderer",
                "Gait '$assetPath' has baked motion: net=%.2f units/loop → extraction-calibrated %.2f m/s@1x (explicit %.2f, renderScale=%.2f) → 方案C extraction on"
                    .format(extractor.netForwardMeters, extractedSpeed, baseSpeed, renderScale)
            )
        }
        return WalkGait(assetPath, animation.duration, speed, kind, extractor, renderScale)
    }

    /**
     * hips 局部 1 单位的世界渲染尺度（root→hips 链路缩放积，root 自身缩放
     * 除掉——root 平移列不在骨骼放大链上）。⚠bindToModel 时 TransformManager
     * 未 commit、世界矩阵陈旧（已知陷阱），本方法在运行帧读取（已 commit）：
     * world col0 / local col0 的长度比，hips 自身无缩放时即链路积。
     */
    private fun hipsRenderScale(): Float {
        val hipsEntity = vrmaEngine?.let { engine -> engineHipsEntity(engine) } ?: return 1f
        val tm = modelViewer.engine.transformManager
        val inst = tm.getInstance(hipsEntity)
        if (inst == 0) return 1f
        val world = FloatArray(16)
        val local = FloatArray(16)
        tm.getWorldTransform(inst, world)
        tm.getTransform(inst, local)
        fun len(m: FloatArray, c: Int) =
            kotlin.math.sqrt(m[c] * m[c] + m[c + 1] * m[c + 1] + m[c + 2] * m[c + 2])
        val wl = len(world, 0)
        val ll = len(local, 0)
        if (wl < 1e-6f || ll < 1e-6f) return 1f
        return wl / ll
    }

    /** hips 的 Filament entity（经引擎已绑定的骨骼映射反查）。 */
    private fun engineHipsEntity(engine: VrmaAnimationEngine): Int = engine.hipsEntity()

    /**
     * Configure the looping walk gait used by [startWalk]. Parses the clip
     * once for validation but does NOT touch current playback.
     *
     * @param assetPath VRMA clip inside `assets/` (an in-place walk; the
     *   travel comes from the locomotion layer itself).
     * @param baseSpeed the gait's natural ground speed in m/s at playback
     *   speed 1 — calibrate with `tools/walk-calib`; ≤0 and the clip carries
     *   baked root motion, the speed is auto-derived (方案 C). [startWalk]
     *   plays the clip at speed/baseSpeed so footsteps match the travel.
     */
    fun setWalkGait(assetPath: String, baseSpeed: Float): Boolean =
        setWalkGait(assetPath, baseSpeed, WalkGaitKind.WALK)

    /** [setWalkGait] 的种类扩展版（跑步/侧移槽共用）。 */
    fun setWalkGait(assetPath: String, baseSpeed: Float, kind: WalkGaitKind): Boolean {
        return try {
            val gait = parseWalkGait(assetPath, baseSpeed, kind) ?: return false
            when (kind) {
                WalkGaitKind.WALK -> walkGait = gait
                WalkGaitKind.RUN -> walkRunGait = gait
                WalkGaitKind.STRAFE_LEFT -> strafeLeftGait = gait
                WalkGaitKind.STRAFE_RIGHT -> strafeRightGait = gait
            }
            android.util.Log.i(
                "SoulLinkRenderer",
                "Gait set [$kind]: ${gait.clipAssetPath} (${gait.durationSeconds}s, base=${gait.baseSpeed} m/s)"
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Walk gait load failed: $assetPath", e)
            false
        }
    }

    /** Configure the run gait (P2 跑步)：speed ≥ [RUN_SPEED_MPS] 时 [startWalk] 自动选用。 */
    fun setRunGait(assetPath: String, baseSpeed: Float = 0f): Boolean =
        setWalkGait(assetPath, baseSpeed, WalkGaitKind.RUN)

    /** Configure both strafe gaits (P2 侧移)；任一失败返回 false（已成功的槽保留）。 */
    fun setStrafeGaits(leftAssetPath: String, rightAssetPath: String, baseSpeed: Float = 0f): Boolean {
        val left = try {
            parseWalkGait(leftAssetPath, baseSpeed, WalkGaitKind.STRAFE_LEFT)
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Strafe gait load failed: $leftAssetPath", e)
            null
        }
        val right = try {
            parseWalkGait(rightAssetPath, baseSpeed, WalkGaitKind.STRAFE_RIGHT)
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Strafe gait load failed: $rightAssetPath", e)
            null
        }
        if (left != null) strafeLeftGait = left
        if (right != null) strafeRightGait = right
        return left != null && right != null
    }

    fun hasWalkGait(): Boolean = walkGait != null

    /** 走动步态配置一览（ai_cmd walk_gait / walk_state 诊断输出）。 */
    fun walkGaitsSummary(): String {
        fun fmt(g: WalkGait?) = g?.let {
            "${it.clipAssetPath}(base=%.2f%s)".format(
                it.baseSpeed, if (it.extractor != null) ", baked" else ""
            )
        } ?: "off"
        return "walk=${fmt(walkGait)} run=${fmt(walkRunGait)} " +
            "strafeL=${fmt(strafeLeftGait)} strafeR=${fmt(strafeRightGait)}"
    }

    /** 走动 crossfade 时长（秒）：0 = 关（硬切）；`walk_fade` ai_cmd 调节。 */
    fun getWalkFadeSeconds(): Float = walkFadeSeconds

    fun setWalkFadeSeconds(seconds: Float) {
        walkFadeSeconds = seconds.coerceIn(0f, 2f)
        android.util.Log.i("SoulLinkRenderer", "walk fade = $walkFadeSeconds s")
    }

    /** 走动中手势 overlay（边走边比划）开关；`walk_overlay` ai_cmd 调节。 */
    fun isGestureOverlayEnabled(): Boolean = gestureOverlayEnabled

    fun setGestureOverlayEnabled(enabled: Boolean) {
        gestureOverlayEnabled = enabled
        android.util.Log.i("SoulLinkRenderer", "gesture overlay = $enabled")
    }

    /**
     * Configure optional walk transition clips (P1 档 1, §5.2): [startAssetPath]
     * plays once at walk start (program motion held while it runs, then the
     * gait loop takes over), [stopAssetPath] plays once on arrival/stop and
     * the idle takeover returns to the idle loop at its end. `null` clears a
     * slot. Defaults to both-off — the curated candidates carry root motion
     * (measured: docs/locomotion-feasibility.md §5.2) and need on-device A/B
     * (ai_cmd `walk_transit`) before any clip becomes the shipped default.
     *
     * P2 方案 C：带烘焙位移的起步剪辑在播放期把前向分量剥离积分进 root
     * （不再「冻结程序位移+凭空滑步」），双重位移与播完回弹同时消除。
     *
     * @return false when any provided path fails to load/parse (config kept).
     */
    fun setWalkTransitions(startAssetPath: String?, stopAssetPath: String?): Boolean {
        fun parseOrNull(path: String?): WalkGait? {
            if (path == null) return null
            return try {
                parseWalkGait(path, baseSpeed = 1f, kind = WalkGaitKind.WALK)
            } catch (e: Exception) {
                android.util.Log.e("SoulLinkRenderer", "Walk transition load failed: $path", e)
                null
            }
        }
        val start = parseOrNull(startAssetPath)
        if (startAssetPath != null && start == null) return false
        val stop = parseOrNull(stopAssetPath)
        if (stopAssetPath != null && stop == null) return false
        walkStartClip = start
        walkStopClip = stop
        android.util.Log.i(
            "SoulLinkRenderer",
            "walk transitions: start=${start?.clipAssetPath ?: "off"}" +
                (if (start?.extractor != null) " [extract]" else "") +
                " stop=${stop?.clipAssetPath ?: "off"}",
        )
        return true
    }

    fun hasWalkTransitions(): Boolean = walkStartClip != null || walkStopClip != null

    /** Current transition clip config (asset paths, null = off) — diagnostics/ai_cmd dump. */
    fun getWalkTransitions(): Pair<String?, String?> =
        walkStartClip?.clipAssetPath to walkStopClip?.clipAssetPath

    /**
     * 走动内部分播入口：绕过公开 [playVrmaAnimation] 的手势 overlay 自动
     * 判定（起步/步态/停步剪辑绝不能被误判成手势 overlay）。
     */
    private fun playVrmaAnimationInternal(
        loop: Boolean,
        speed: Float,
        fadeSeconds: Float,
        extractor: LocomotionExtractor?,
        boneMask: Set<String>? = null,
    ) {
        currentAnimationIndex = -1
        vrmaStartTime = System.nanoTime()
        vrmaEngine?.play(loop, speed, fadeSeconds, boneMask, extractor)
        pendingSpringReset = true
        notifyVrmaStarted()
    }

    /** 速度 ≥ 此值（m/s）且配置了跑步步态时自动选用跑步（P2 跑步）。 */
    private val RUN_SPEED_MPS = 1.9f

    /**
     * Start walking toward a world-space XZ point: play the configured gait
     * on loop (timeScale = speed/baseSpeed), clear the drag offset so stale
     * hips displacement can't skew the straight line, and engage the
     * locomotion state machine. Mimic owns the bones while engaged — walking
     * under it would fight the pose lanes, so it's refused. Walking with no
     * scene loaded is refused ("walking on air" guard, 坑 4).
     *
     * P2：speed ≥ [RUN_SPEED_MPS] 且配置了跑步步态时自动换跑步步态；起步
     * crossfade（[walkFadeSeconds] > 0 且有剪辑在播）把 idle→步态的硬切变
     * 淡入淡出；带烘焙位移的步态（方案 C）由提取回灌驱动位移（externalGait）。
     */
    fun startWalk(targetX: Float, targetZ: Float, speed: Float): WalkStartResult {
        if (modelViewer.asset == null) return WalkStartResult.NO_MODEL
        if (sceneAsset == null) return WalkStartResult.NO_SCENE
        if (mimicInfo()?.engaged == true) return WalkStartResult.MIMIC_ACTIVE
        if (contactPose.isActive) return WalkStartResult.POSE_ACTIVE
        val gait = (if (speed >= RUN_SPEED_MPS) walkRunGait else null) ?: walkGait
        ?: return WalkStartResult.NO_GAIT
        val prevGait = activeGait
        val prevSpeed = walkSpeed
        val wasIdle = locomotionEngine.snapshot().phase == com.neethu.corelib.WalkPhase.IDLE
        clearHipsDragOffset()
        walkSpeed = speed
        activeGait = gait
        val external = gait.extractor != null && extractionEnabled
        // 起步剪辑只在「从静止起步」时播：走动中改目标 = 步态循环继续，不重播。
        // ⚠external 步态不用起步剪辑——提取位移与 hold 冻结互相打架，crossfade
        // 已覆盖起步衔接（§5.2 档 2 取代档 1 的场景）。
        val startClip = walkStartClip?.takeIf {
            !external && locomotionEngine.snapshot().phase == com.neethu.corelib.WalkPhase.IDLE
        }
        if (startClip != null && loadVrmaAnimation(startClip.clipAssetPath)) {
            // 起步剪辑先行（one-shot）：程序位移在引擎 start-hold 里冻结，
            // 剪辑自带位移（带提取器时）由回灌驱动，播完切回步态循环。
            playVrmaAnimationInternal(loop = false, speed = 1f, fadeSeconds = 0f,
                extractor = if (extractionEnabled) startClip.extractor else null)
            startClipDeadlineNanos = System.nanoTime() + (startClip.durationSeconds * 1_000_000_000L).toLong()
            locomotionEngine.startWalk(targetX, targetZ, speed, scenePlacement?.bounds, startClip.durationSeconds)
        } else {
            startClipDeadlineNanos = 0L
            // 走动中 retarget 且就是当前步态：步态循环继续，不重载不重播
            // （重播=步态周期跳回第 0 帧，视觉顿挫）。⚠速度差异不进本判定——
            // 摇杆连续控制逐帧微调速度，等值判定会退回慢路径每帧重播
            // （docs/gamepad-control-feasibility.md §4.3①）；程序位移步态在
            // 下面补设播速，外部步态本就由 updateLocomotion 每帧按
            // commandedSpeed 设（设了也白设，跳过）。
            val sameGaitPlaying = prevGait == gait &&
                locomotionEngine.snapshot().phase != com.neethu.corelib.WalkPhase.IDLE &&
                vrmaEngine?.isActive() == true
            if (!sameGaitPlaying) {
                if (!loadVrmaAnimation(gait.clipAssetPath)) return WalkStartResult.LOAD_FAILED
                playVrmaAnimationInternal(
                    loop = true,
                    speed = speed / gait.baseSpeed,
                    fadeSeconds = walkFadeSeconds,
                    extractor = if (extractionEnabled) gait.extractor else null,
                )
            } else if (speed != prevSpeed && gait.extractor == null) {
                // 程序位移步态的播速只在播放时设定——retarget 变速要补
                vrmaEngine?.setPlaybackSpeed(speed / gait.baseSpeed)
            }
            locomotionEngine.startWalk(targetX, targetZ, speed, scenePlacement?.bounds)
        }
        // 从静止起步必打日志（新走动事件）；走动中 retarget 摇杆会逐帧刷，
        // 500ms 节流（正常单发 walk_to 不受影响——那是新起步）。
        val nowNanos = System.nanoTime()
        if (wasIdle || nowNanos - lastStartWalkLogNanos > 500_000_000L) {
            lastStartWalkLogNanos = nowNanos
            android.util.Log.i(
                "SoulLinkRenderer",
                "startWalk target=(%.2f, %.2f) speed=%.2f gait=%s%s startClip=%s fade=%.2f bounds=%s".format(
                    targetX, targetZ, speed,
                    gait.kind, if (external) "/extract" else "",
                    if (startClipDeadlineNanos != 0L) "on" else "off",
                    walkFadeSeconds,
                    scenePlacement?.bounds?.toString() ?: "none"
                )
            )
        }
        return WalkStartResult.STARTED
    }

    /**
     * P2 侧移：朝向锁死横步挪 [meters] 米（[left]=true 向角色左侧）。
     * 目标 = 当前位置 ± 角色局部 X 轴方向 × meters（投到水平面）；
     * 步态用侧移剪辑（带烘焙位移的提取回灌驱动，原地素材程序位移直指目标）。
     */
    fun startStrafe(left: Boolean, meters: Float, speed: Float): WalkStartResult {
        if (modelViewer.asset == null) return WalkStartResult.NO_MODEL
        if (sceneAsset == null) return WalkStartResult.NO_SCENE
        if (mimicInfo()?.engaged == true) return WalkStartResult.MIMIC_ACTIVE
        if (contactPose.isActive) return WalkStartResult.POSE_ACTIVE
        val gait = (if (left) strafeLeftGait else strafeRightGait) ?: return WalkStartResult.NO_GAIT
        clearHipsDragOffset()
        walkSpeed = speed
        activeGait = gait
        val external = gait.extractor != null && extractionEnabled

        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(modelViewer.asset!!.root)
        if (instance == 0) return WalkStartResult.NO_MODEL
        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        // 角色局部 X 轴（root 3×3 第 0 列）投到水平面并归一化——含
        // transformToUnitCube 的缩放与 VRM0 的 Y-180 翻转，取方向即可
        var lx = mat[0]
        var lz = mat[2]
        val len = kotlin.math.sqrt(lx * lx + lz * lz)
        if (len < 1e-5f) {
            // 退化（纯俯仰矩阵）：按 heading 推右侧向量
            val h = locomotionEngine.snapshot().headingDeg.toDouble() * Math.PI / 180.0
            lx = kotlin.math.cos(h).toFloat()
            lz = -kotlin.math.sin(h).toFloat()
        } else {
            lx /= len
            lz /= len
        }
        val sign = if (left) -1f else 1f
        val distance = meters.coerceIn(0.2f, 5f)
        val targetX = mat[12] + sign * lx * distance
        val targetZ = mat[14] + sign * lz * distance

        if (!loadVrmaAnimation(gait.clipAssetPath)) return WalkStartResult.LOAD_FAILED
        playVrmaAnimationInternal(
            loop = true,
            speed = speed / gait.baseSpeed,
            fadeSeconds = walkFadeSeconds,
            extractor = if (extractionEnabled) gait.extractor else null,
        )
        locomotionEngine.startWalk(
            targetX, targetZ, speed, scenePlacement?.bounds,
            startHoldSeconds = 0f, headingLocked = true,
        )
        android.util.Log.i(
            "SoulLinkRenderer",
            "startStrafe %s %.1fm target=(%.2f, %.2f) gait=%s%s fade=%.2f".format(
                if (left) "left" else "right", distance, targetX, targetZ,
                gait.kind, if (external) "/extract" else "", walkFadeSeconds
            )
        )
        return WalkStartResult.STARTED
    }

    /** Stop walking and return to the idle loop (optionally via the stop clip). */
    fun stopWalk() {
        val s = locomotionEngine.snapshot()
        val wasWalking = s.phase != com.neethu.corelib.WalkPhase.IDLE
        if (wasWalking) {
            android.util.Log.i("SoulLinkRenderer", "stopWalk at (%.2f, %.2f)".format(s.x, s.z))
        }
        // 起步剪辑还没播完就停步：跳过停步剪辑（身体还没真正起步，播
        // "走→停"剪辑会凭空走出两步）
        val skippedStartClip = startClipDeadlineNanos != 0L
        startClipDeadlineNanos = 0L
        // 方案 C：先冻结步态步速再淡出——提取步态的步进带真实位移，淡出期
        // 继续步进=原地滑步（程序位移已停）；冻结帧→idle 的淡入无滑步。
        if (activeGait?.extractor != null && extractionEnabled) {
            vrmaEngine?.setPlaybackSpeed(0f)
        }
        activeGait = null
        locomotionEngine.stopWalk()
        stopVrmaAnimation(walkFadeSeconds)
        val stopClip = walkStopClip
        if (wasWalking && !skippedStartClip && stopClip != null &&
            loadVrmaAnimation(stopClip.clipAssetPath)
        ) {
            // one-shot：播完引擎自动接管 idle 循环
            playVrmaAnimationInternal(loop = false, speed = 1f, fadeSeconds = 0f, extractor = null)
        }
        if (wasWalking) {
            onWalkFinished?.invoke(com.neethu.corelib.WalkFinished(arrived = false, x = s.x, z = s.z))
        }
    }

    fun walkSnapshot(): WalkSnapshot = locomotionEngine.snapshot()

    /**
     * Turn in place to an absolute world yaw (stand anchors of interaction
     * anchors: arrival should end facing the furniture/TV/window). Uses the
     * engine's turn-only mode against a synthetic target 1 m ahead of the
     * body along the desired heading; arrival flows out via the regular
     * walk-finished path (idle resumes).
     */
    fun turnTo(yawDeg: Float): Boolean {
        if (contactPose.isActive) return false
        val s = locomotionEngine.snapshot()
        val rad = Math.toRadians(yawDeg.toDouble()).toFloat()
        val tx = s.x + sin(rad) * 1f
        val tz = s.z + cos(rad) * 1f
        return locomotionEngine.startTurnTo(tx, tz, scenePlacement?.bounds)
    }

    // ── 模拟量驱动/视角通道（手柄与 ai_cmd pad 共用，docs/gamepad-control-feasibility.md）──

    /** 满杆视角速率（px/s）：orbit 0.01 rad/px → 满杆 ~9 rad/s，实测微调。 */
    private val LOOK_RATE_PX_PER_S = 900f

    private var driveForward = 0f
    private var driveStrafe = 0f
    private var driveRun = false
    private var lookYaw = 0f
    private var lookPitch = 0f
    private var lookResidualX = 0f
    private var lookResidualY = 0f

    /** 驱动通道自己起步的走动在进行中（归零时由它负责停步，AI 走动不受影响）。 */
    private var driveWalking = false
    private var driveRefusalLogNanos = 0L
    private var lastStartWalkLogNanos = 0L
    private val driveEye = FloatArray(3)
    private val driveTarget = FloatArray(3)
    private val driveUp = FloatArray(3)

    /**
     * 模拟量驱动通道：手柄左摇杆与 ai_cmd pad 命令族写同一份轴。
     * [forward]/[strafe] ∈ -1..1，**相机相对**语义（前=远离相机，右=屏幕右）。
     * 主线程写入（与其它 controller API 同契约），下一帧起生效——非零轴在
     * updateLocomotion 里逐帧 retarget 步态目标（lookahead 恒在制动半径外，
     * 恒速平滑），归零停步 crossfade 回待机。走动中 AI walk_to 目标被摇杆
     * 接管（人 > AI）；松手后 AI 可再下指令（LocomotionDriver 防抖照常）。
     */
    fun setDriveAxis(forward: Float, strafe: Float) {
        driveForward = forward.coerceIn(-1f, 1f)
        driveStrafe = strafe.coerceIn(-1f, 1f)
    }

    /**
     * 模拟量视角通道：右摇杆 -1..1，逐帧 orbitCamera（速率按 dt 缩放，
     * 亚像素残差累积防慢杆整帧取整归零卡顿）。非零即取消预设机位
     * steering（orbitCamera 自带 cancel——摇杆接管相机）；机位记录保留，
     * R3/`camera_shot follow` 可召回。
     */
    fun setLookAxis(yaw: Float, pitch: Float) {
        lookYaw = yaw.coerceIn(-1f, 1f)
        lookPitch = pitch.coerceIn(-1f, 1f)
    }

    /** 跑步增益开关（满杆 1.2→2.16 m/s，越 RUN_SPEED_MPS 自动换跑步步态）。 */
    fun setDriveRun(enabled: Boolean) {
        driveRun = enabled
    }

    /** 归零双通道；驱动通道自己起步的走动立即停步（AI walk_to 不受影响）。 */
    fun clearDrive() {
        driveForward = 0f
        driveStrafe = 0f
        lookYaw = 0f
        lookPitch = 0f
        lookResidualX = 0f
        lookResidualY = 0f
        if (driveWalking) {
            driveWalking = false
            stopWalk()
        }
    }

    /** 驱动通道是否正持有一次走动（ai_cmd pad_state 诊断用）。 */
    fun isDriveWalking(): Boolean = driveWalking

    /** 每帧消费视角轴（doFrame → updateLocomotion 调用）。 */
    private fun updateLookAxis(dtSeconds: Float) {
        if (lookYaw == 0f && lookPitch == 0f) return
        val dyaw = lookYaw * LOOK_RATE_PX_PER_S * dtSeconds + lookResidualX
        val dpitch = lookPitch * LOOK_RATE_PX_PER_S * dtSeconds + lookResidualY
        val appliedX = dyaw.roundToInt()
        val appliedY = dpitch.roundToInt()
        lookResidualX = dyaw - appliedX
        lookResidualY = dpitch - appliedY
        if (appliedX != 0 || appliedY != 0) {
            orbitCamera(appliedX.toFloat(), appliedY.toFloat())
        }
    }

    /**
     * 每帧消费驱动轴：目标 = 当前位置 + 相机相对方向 × lookahead（
     * [DriveMath.lookaheadMeters] 保证制动半径外恒速），逐帧 retarget 走
     * startWalk 同款步态快速路径（不重载剪辑）。拒绝态（NO_SCENE 虚空行走
     * 守卫/MIMIC/POSE）按 2s 节流告警，不呛帧。
     */
    private fun updateDriveAxes(mat: FloatArray) {
        val forward = driveForward
        val strafe = driveStrafe
        if (forward == 0f && strafe == 0f) {
            if (driveWalking) {
                driveWalking = false
                stopWalk()
            }
            return
        }
        cameraManipulator.getLookAt(driveEye, driveTarget, driveUp)
        // 俯视相机（gaze 近垂直）退化：沿角色当前朝向走
        val snap = locomotionEngine.snapshot()
        val fallbackX = sin(Math.toRadians(snap.headingDeg.toDouble())).toFloat()
        val fallbackZ = cos(Math.toRadians(snap.headingDeg.toDouble())).toFloat()
        val dir = DriveMath.cameraRelativeDir(
            driveTarget[0] - driveEye[0], driveTarget[2] - driveEye[2],
            forward, strafe, fallbackX, fallbackZ,
        ) ?: return
        val speed = DriveMath.speedMps(kotlin.math.hypot(forward, strafe), driveRun)
        val lookahead = DriveMath.lookaheadMeters(speed)
        val result = startWalk(
            mat[12] + dir.first * lookahead,
            mat[14] + dir.second * lookahead,
            speed,
        )
        driveWalking = result == WalkStartResult.STARTED
        if (result != WalkStartResult.STARTED &&
            System.nanoTime() - driveRefusalLogNanos > 2_000_000_000L
        ) {
            driveRefusalLogNanos = System.nanoTime()
            android.util.Log.w("SoulLinkRenderer", "drive axis refused: $result (throttled 2s)")
        }
    }

    // ── 场景交互定姿（坐/躺接触对齐）────────────────────────────────

    /**
     * Engage a contact pose (sit / lie) at a furniture contact point. The
     * caller walks the avatar to the anchor's approach point first — walking
     * mid-pose is refused ([ContactPoseResult.WALKING]); clear the pose to
     * stand back up ([clearContactPose]).
     */
    fun applyContactPose(request: ContactPoseRequest): ContactPoseResult {
        if (modelViewer.asset == null) return ContactPoseResult.NO_MODEL
        if (mimicInfo()?.engaged == true) return ContactPoseResult.BUSY
        if (locomotionEngine.snapshot().phase != com.neethu.corelib.WalkPhase.IDLE) {
            return ContactPoseResult.WALKING
        }
        // 残留的 hips 拖拽偏移会把定姿整体带偏——定姿前清账（起步清偏移同款）
        clearHipsDragOffset()
        return contactPose.apply(request)
    }

    /** Stand back up (play the stand-up clip / roll upright, return to the
     *  stand matrix captured at pose start). False when no pose is active. */
    fun clearContactPose(): Boolean = contactPose.clear()

    fun contactPoseSnapshot(): ContactPoseSnapshot = contactPose.snapshot()

    /**
     * Teleport the avatar root to a world position + absolute yaw (scene
     * spawn placement / calibration probes). Stops any walk, clears hips
     * drag offsets and resyncs the locomotion heading from the new matrix.
     */
    fun setAvatarWorldPosition(x: Float, y: Float, z: Float, yawDeg: Float): Boolean {
        val asset = modelViewer.asset ?: return false
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return false
        contactPose.reset()
        stopWalk()
        val mat = tm.getTransform(instance, FloatArray(16))
        clearHipsDragOffset()
        mat[12] = x
        mat[13] = y
        mat[14] = z
        val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
        ContactPoseMath.rotateAroundY(mat, yawRad - rootHeadingRad(mat))
        tm.setTransform(instance, mat)
        locomotionEngine.resyncHeadingRad(rootHeadingRad(mat))
        android.util.Log.i(
            "SoulLinkRenderer",
            "setAvatarWorldPosition (%.2f, %.2f, %.2f) yaw=%.0f".format(x, y, z, yawDeg),
        )
        return true
    }

    // ── 防遮挡（墙体自动剖切）开关 ──────────────────────────────────

    fun setOcclusionEnabled(enabled: Boolean) {
        sceneOcclusion.enabled = enabled
        android.util.Log.i("SoulLinkRenderer", "scene occlusion = $enabled")
    }

    fun isOcclusionEnabled(): Boolean = sceneOcclusion.enabled

    fun occlusionDebugString(): String {
        cameraManipulator.getLookAt(occlusionEye, occlusionTarget, occlusionUp)
        return "eye=(%.1f, %.1f, %.1f) target=(%.1f, %.1f, %.1f) occluders=%d hidden=%d%s".format(
            occlusionEye[0], occlusionEye[1], occlusionEye[2],
            occlusionTarget[0], occlusionTarget[1], occlusionTarget[2],
            sceneOcclusion.occluderCount, sceneOcclusion.hiddenCount,
            sceneOcclusion.hiddenNames().joinToString(prefix = " [", postfix = "]"),
        )
    }

    /** 方案 C 提取总开关（ai_cmd `walk_extract` A/B 用）。 */
    fun isExtractionEnabled(): Boolean = extractionEnabled

    fun setExtractionEnabled(enabled: Boolean) {
        extractionEnabled = enabled
        android.util.Log.i("SoulLinkRenderer", "walk extraction = $enabled")
    }

    /**
     * Per-frame root motion: read the authoritative root translation, ask the
     * state machine for this frame's motion, apply translation + world-Y
     * rotation. Root is the parent of the whole bone hierarchy, so this is
     * orthogonal to the bone-local writes above; arrival switches back to the
     * idle loop (or plays the stop clip first). Also orchestrates the start
     * clip → gait loop handoff.
     *
     * P2 新增三条：方案 C 提取位移回灌进 root（externalGait 时程序位移由
     * 引擎置 0，素材步进顶上）；mode C 每帧以 commandedSpeed 设步态变速
     * （制动/ease 自动生效，起步剪辑期除外——剪辑按自然速度播）；角色逼近
     * 静态机位时自动切 FOLLOW（P1 遗留「走进相机粉屏」形态的自护）。
     */
    private fun updateLocomotion(dtSeconds: Float) {
        val asset = modelViewer.asset ?: return
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return

        // 起步剪辑到期 → 切步态循环（引擎 start-hold 同帧到期，程序位移恢复）
        if (startClipDeadlineNanos != 0L) {
            val gait = activeGait
            if (System.nanoTime() >= startClipDeadlineNanos) {
                startClipDeadlineNanos = 0L
                if (gait != null && locomotionEngine.snapshot().phase != com.neethu.corelib.WalkPhase.IDLE &&
                    loadVrmaAnimation(gait.clipAssetPath)
                ) {
                    playVrmaAnimationInternal(
                        loop = true,
                        speed = walkSpeed / gait.baseSpeed,
                        fadeSeconds = walkFadeSeconds,
                        extractor = if (extractionEnabled) gait.extractor else null,
                    )
                }
            }
        }

        val mat = FloatArray(16)
        tm.getTransform(instance, mat)
        // 模拟量通道（手柄/ai_cmd pad）：先消费轴（可能 retarget/停步，
        // 改写 engine 目标），再做本帧状态机更新——同帧生效
        updateLookAxis(dtSeconds)
        updateDriveAxes(mat)
        val external = activeGait?.extractor != null
        // 方案 C 每帧自愈：走动中步态槽的提取链断供（base 被手势/待机/停步
        // 淡入时序顶掉成无提取器剪辑，或播放被停）= 提取位移恒 0 而状态机
        // 照常 WALKING → 原地冻结（真机 2026-10-24 遥测实锤）。起步 crossfade
        // 的 0.25s 是合法窗口（isExtractionHealthy 放行步态 overlay 淡入）。
        // 同帧重载步态（fade=0 直切，姿态已是行走无需混合）。
        if (external && locomotionEngine.snapshot().phase != com.neethu.corelib.WalkPhase.IDLE &&
            vrmaEngine?.isExtractionHealthy() != true
        ) {
            val gait = activeGait
            android.util.Log.w(
                "SoulLinkRenderer",
                "gait extraction dead mid-walk (vrma[${vrmaEngine?.debugState() ?: "n/a"}]) — reloading gait",
            )
            if (gait != null && loadVrmaAnimation(gait.clipAssetPath)) {
                playVrmaAnimationInternal(
                    loop = true,
                    speed = walkSpeed / gait.baseSpeed,
                    fadeSeconds = 0f,
                    extractor = gait.extractor,
                )
            }
        }
        val motion = locomotionEngine.update(
            dtSeconds, mat[12], mat[13], mat[14], external, rootHeadingRad(mat),
        )
        if (motion != null) {
            rotateRootAroundY(mat, motion.dHeadingRad)
            mat[12] += motion.dx
            mat[13] += motion.dy
            mat[14] += motion.dz
            tm.setTransform(instance, mat)
            // mode C：起步剪辑播完后步态变速跟随指令速度（提取步长随之缩放）
            if (external && startClipDeadlineNanos == 0L) {
                vrmaEngine?.setPlaybackSpeed(motion.commandedSpeedMps / (activeGait?.baseSpeed ?: 1f))
            }
            updateWalkStallWatchdog(mat)
        } else {
            resetWalkStallWatchdog()
        }
        // 方案 C 回灌：步态/起步剪辑提取的前向位移映到世界。⚠骨骼写入的
        // 位移被 root→hips 链路缩放（renderScale）放大，root 平移列不在放大
        // 链上——补回同样的倍数，剥离与回灌才严格抵消（否则 unit-cube 大
        // 缩放模型上走动速度差一个数量级，真机实测 0.13 m/s 爬行即此因）。
        // 方向用 root 旋转（归一化，剔除 mat 自带缩放/翻转），乘 renderScale。
        val extracted = vrmaEngine?.consumeExtractedForwardDelta() ?: 0f
        if (extracted != 0f && extractionEnabled) {
            vrmaEngine?.extractorForwardDir()?.let { dir ->
                val scale = activeGait?.renderScale ?: 1f
                val step = extracted * scale
                val c0x = mat[0]; val c0z = mat[2]
                val c2x = mat[8]; val c2z = mat[10]
                val l0 = kotlin.math.sqrt(c0x * c0x + c0z * c0z).coerceAtLeast(1e-6f)
                val l2 = kotlin.math.sqrt(c2x * c2x + c2z * c2z).coerceAtLeast(1e-6f)
                val wx = (c0x / l0) * dir[0] * step + (c2x / l2) * dir[2] * step
                val wz = (c0z / l0) * dir[0] * step + (c2z / l2) * dir[2] * step
                mat[12] += wx
                mat[14] += wz
                tm.setTransform(instance, mat)
            }
        }
        // 相机自护：静态机位下角色走近到脸前 → 自动切 FOLLOW 跟拍
        if (motion != null && activeShot != null && activeShot != CameraShot.FOLLOW) {
            cameraManipulator.getLookAt(shotEye, shotTarget, shotUp)
            val dx = mat[12] - shotEye[0]
            val dy = mat[13] - shotEye[1]
            val dz = mat[14] - shotEye[2]
            if (dx * dx + dy * dy + dz * dz < cameraApproachGuardMeters * cameraApproachGuardMeters) {
                android.util.Log.i("SoulLinkRenderer", "avatar approaching static camera → FOLLOW")
                setCameraShot(CameraShot.FOLLOW)
            }
        }
        if (locomotionEngine.consumeArrived()) {
            val s = locomotionEngine.snapshot()
            activeGait = null
            // 到达：配置了停步剪辑就播（one-shot 播完自动接管 idle），否则
            // crossfade 淡回 idle（fade=0 时硬切，P1 行为）
            val stopClip = walkStopClip
            if (stopClip != null && loadVrmaAnimation(stopClip.clipAssetPath)) {
                playVrmaAnimationInternal(loop = false, speed = 1f, fadeSeconds = 0f, extractor = null)
            } else {
                stopVrmaAnimation(walkFadeSeconds)
            }
            onWalkFinished?.invoke(com.neethu.corelib.WalkFinished(arrived = true, x = s.x, z = s.z))
        }
    }

    /**
     * World-Y rotation composed onto the root: the 3×3 block is left-multiplied
     * by Ry(θ) (column-major, per column), translation column untouched —
     * a turn about the body axis that preserves the unit-cube scale baked
     * into the root matrix. Works identically for VRM 0.x (whose Y-180 flip
     * lives inside the matrix) and VRM 1.0.
     */
    private fun rotateRootAroundY(mat: FloatArray, theta: Float) {
        if (theta == 0f) return
        val c = cos(theta)
        val s = sin(theta)
        for (col in 0..2) {
            val i = col * 4
            val x = mat[i]
            val z = mat[i + 2]
            mat[i] = c * x + s * z
            mat[i + 2] = -s * x + c * z
        }
    }

    // ── 场景交互定姿（坐/躺接触对齐）─────────────────────────────────

    private fun updateContactPose(dtSeconds: Float) {
        val controller = contactPose
        if (!controller.isActive) return
        val asset = modelViewer.asset ?: return
        val tm = modelViewer.engine.transformManager
        val instance = tm.getInstance(asset.root)
        if (instance == 0) return
        val mat = controller.update(dtSeconds) ?: return
        tm.setTransform(instance, mat)
    }

    // ── 防遮挡（墙体自动剖切）────────────────────────────────────────

    private fun updateSceneOcclusion(dtSeconds: Float) {
        if (sceneAsset == null || !sceneOcclusion.enabled) return
        cameraManipulator.getLookAt(occlusionEye, occlusionTarget, occlusionUp)
        val head = boneAnchor(headEntity, HEAD_FALLBACK_Y)
        val chest = boneAnchor(chestEntity, CHEST_FALLBACK_Y)
        val hips = boneAnchor(hipsEntity, HIPS_FALLBACK_Y)
        occlusionBodyPoints[0][0] = head[0]; occlusionBodyPoints[0][1] = head[1]; occlusionBodyPoints[0][2] = head[2]
        occlusionBodyPoints[1][0] = chest[0]; occlusionBodyPoints[1][1] = chest[1]; occlusionBodyPoints[1][2] = chest[2]
        occlusionBodyPoints[2][0] = hips[0]; occlusionBodyPoints[2][1] = hips[1]; occlusionBodyPoints[2][2] = hips[2]
        sceneOcclusion.update(occlusionEye, occlusionBodyPoints, dtSeconds)
    }

    /**
     * Root matrix → locomotion heading (radians). Column 2 IS the belly
     * direction for VRM 1.0, but VRM 0.x's baked Y-180 flip negates it — the
     * engine tracks the belly heading ("heading 0 = +Z toward camera"), so
     * extract with the flip sign.
     */
    private fun rootHeadingRad(mat: FloatArray): Float {
        val sign = if (isVrm0) -1f else 1f
        return atan2(sign * mat[8], sign * mat[10])
    }

    // ── 走动停滞看门狗 ────────────────────────────────────────────────
    // WALKING 相位而净位移≈0（蛇形极限环/提取回灌失效的兜底）：5s 无进展
    // 重发同腿起步（重新对齐朝向 + ease 重爬，即「重发即可」的自动化），
    // 两次重发仍停滞 = 强制到步（walk_state 恢复 IDLE，交互链的
    // awaitStandstill 30s 超时不再整链静默 abort）。位移每帧以 root 矩阵
    // 为权威，看门狗只读不动矩阵，force-arrive 走引擎的 stopWalk 语义。

    private var stallCheckX = Float.NaN
    private var stallCheckZ = Float.NaN
    private var stallSinceMs = 0L
    private var stallRetries = 0

    // 非收敛断路器（P0 道具验收②r2）：同目标 WALKING 超时强停。看门狗只抓
    // 「原地不动」——围目标振荡（一直在动但到不了）两路都不管,r2 实测 12min
    // 卡死把 FSM pending 拖死。目标一变即重计（摇杆逐帧 re-pin 目标 = 天然
    // 豁免,不影响模拟量持续行走）。
    private var breakerTargetX = Float.NaN
    private var breakerTargetZ = Float.NaN
    private var breakerSinceMs = 0L

    private fun updateWalkStallWatchdog(mat: FloatArray) {
        val snap = locomotionEngine.snapshot()
        if (snap.phase != com.neethu.corelib.WalkPhase.WALKING) {
            resetWalkStallWatchdog()
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        // 断路器先于停滞判定：目标没变且超时 → 强停（arrived=false 走
        // walkFinished,driver 清腿/FSM 观望解除,自主循环自愈）
        if (breakerSinceMs == 0L || snap.targetX != breakerTargetX || snap.targetZ != breakerTargetZ) {
            breakerTargetX = snap.targetX; breakerTargetZ = snap.targetZ
            breakerSinceMs = nowMs
        } else if (nowMs - breakerSinceMs > WALK_NON_CONVERGENCE_MS) {
            android.util.Log.w(
                "SoulLinkRenderer",
                "walk to (%.2f, %.2f) not converged in %dms — force stop (breaker)"
                    .format(snap.targetX, snap.targetZ, WALK_NON_CONVERGENCE_MS),
            )
            stallCheckX = mat[12]; stallCheckZ = mat[14]; stallSinceMs = nowMs
            stopWalk()
            return
        }
        if (stallCheckX.isNaN()) {
            stallCheckX = mat[12]; stallCheckZ = mat[14]; stallSinceMs = nowMs
            return
        }
        val moved = kotlin.math.hypot((mat[12] - stallCheckX).toDouble(), (mat[14] - stallCheckZ).toDouble())
        if (moved >= 0.15) {
            stallCheckX = mat[12]; stallCheckZ = mat[14]; stallSinceMs = nowMs
            return
        }
        if (nowMs - stallSinceMs < WALK_STALL_TIMEOUT_MS) return
        val target = kotlin.math.hypot(
            (snap.targetX - snap.x).toDouble(), (snap.targetZ - snap.z).toDouble(),
        )
        if (stallRetries < WALK_STALL_MAX_RETRIES && target > LocomotionEngine.ARRIVE_EPS * 2) {
            stallRetries++
            android.util.Log.w(
                "SoulLinkRenderer",
                "walk stalled %.2fm from target for %dms — forcing gait reload (retry %d, vrma[%s])"
                    .format(target, WALK_STALL_TIMEOUT_MS, stallRetries, vrmaEngine?.debugState() ?: "n/a"),
            )
            // 确定性重载（不走 startWalk——它的 sameGaitPlaying 短路会跳过重载，
            // stop 后 idle 自动续播反而掩护死链）：直载步态挂回提取器 + 引擎级
            // 重起步（重对齐朝向）。每帧自愈是第一道防线，这里是保险丝。
            val gait = activeGait ?: walkGait
            if (gait != null && loadVrmaAnimation(gait.clipAssetPath)) {
                playVrmaAnimationInternal(
                    loop = true,
                    speed = walkSpeed / gait.baseSpeed,
                    fadeSeconds = 0f,
                    extractor = gait.extractor,
                )
            }
            locomotionEngine.startWalk(snap.targetX, snap.targetZ, walkSpeed, scenePlacement?.bounds)
        } else {
            android.util.Log.w(
                "SoulLinkRenderer",
                "walk stalled %.2fm from target after %d retries — forcing stop (vrma[%s])"
                    .format(target, stallRetries, vrmaEngine?.debugState() ?: "n/a"),
            )
            stopWalk()
        }
        stallCheckX = mat[12]; stallCheckZ = mat[14]; stallSinceMs = nowMs
    }

    private fun resetWalkStallWatchdog() {
        stallCheckX = Float.NaN
        stallCheckZ = Float.NaN
        stallRetries = 0
        // 相位离场重挂断路器：下一轮 WALKING 首帧经目标比对重新起算（同目标
        // 立即再走不会秒杀——breakerSinceMs=0 视为未武装）
        breakerSinceMs = 0L
    }

    /**
     * Dolly the camera toward/away from the model.
     * [spreadPx] is expressed in the same units as a two-finger pinch:
     * positive = fingers spread apart = zoom in, negative = zoom out.
     * The scale factor matches GestureDetector's pinch handling
     * (scroll delta = separation change × 0.1).
     */
    fun zoomCamera(spreadPx: Float) {
        cancelShotTransition()
        cameraManipulator.scroll(
            surfaceView.width / 2,
            surfaceView.height / 2,
            -spreadPx * ZOOM_SPEED_PER_PIXEL
        )
    }

    /** Pan the camera laterally, like a two-finger drag of [dxPx]/[dyPx] pixels. */
    fun panCamera(dxPx: Float, dyPx: Float) {
        cancelShotTransition()
        grabCamera(dxPx, dyPx, strafe = true)
    }

    /** Orbit the camera, like a single-finger drag of [yawPx]/[pitchPx] pixels. */
    fun orbitCamera(yawPx: Float, pitchPx: Float) {
        cancelShotTransition()
        grabCamera(yawPx, pitchPx, strafe = false)
    }

    private fun grabCamera(dxPx: Float, dyPx: Float, strafe: Boolean) {
        val cx = surfaceView.width / 2
        val cy = surfaceView.height / 2
        cameraManipulator.grabBegin(cx, cy, strafe)
        cameraManipulator.grabUpdate(cx + dxPx.roundToInt(), cy + dyPx.roundToInt())
        cameraManipulator.grabEnd()
    }

    /** Restore the camera to its initial pose (as when the model was loaded). */
    fun resetCamera() {
        cancelShotTransition()
        cameraManipulator.jumpToBookmark(cameraManipulator.homeBookmark)
    }

    /**
     * Current camera pose: eye position, target position and up vector
     * (each a 3-element array).
     */
    fun cameraLookAt(): Triple<FloatArray, FloatArray, FloatArray> {
        val eye = FloatArray(3)
        val target = FloatArray(3)
        val up = FloatArray(3)
        cameraManipulator.getLookAt(eye, target, up)
        return Triple(eye, target, up)
    }

    // ── 头顶锚点与屏幕投影（思考气泡跟随，docs/autonomy-feasibility.md §6.2/§6.3）──

    /**
     * 头顶世界坐标（头骨实体推导，缺骨按比例兜底）；无模型 null。
     * 气泡跟随的世界坐标源——坐/躺姿势变化天然正确（头骨随姿势动）。
     */
    fun headWorldPosition(): FloatArray? {
        if (modelViewer.asset == null) return null
        return boneAnchor(headEntity, HEAD_FALLBACK_Y)
    }

    /**
     * 世界坐标 → 归一化屏幕坐标 `[u, v]`（u 左→右、v 上→下，各 0..1）；
     * 背对相机或视锥外 null（调用方隐藏）。矩阵每帧从 Filament camera 现取
     * （分辨率变化/运镜中天然正确，不缓存）；线程语义同 [cameraLookAt]。
     */
    fun projectToScreen(x: Float, y: Float, z: Float): FloatArray? {
        if (modelViewer.asset == null) return null
        val view = FloatArray(16)
        modelViewer.camera.getViewMatrix(view)
        val proj = DoubleArray(16)
        modelViewer.camera.getProjectionMatrix(proj)
        val projF = FloatArray(16) { proj[it].toFloat() }
        return ScreenProjector.project(x, y, z, view, projF)
    }

    // ── Camera Shots (smooth preset framing) ─────────────────────────────

    /**
     * Glide the camera to a preset [shot] with an exponential smooth-damp —
     * never a hard cut. The look-at point is derived from the model's humanoid
     * bones (head for [CameraShot.CLOSE_UP], chest/hips for the wider shots),
     * so framing adapts to any model and its current animation pose.
     *
     * The shot also acts as a mode: it survives model switches (the freshly
     * loaded character is re-framed) until [clearCameraShot] or a new
     * [setCameraShot]. Any camera touch or programmatic camera call
     * (zoom/pan/orbit/reset) cancels only the in-flight glide.
     */
    fun setCameraShot(shot: CameraShot) {
        activeShot = shot
        shotSteering = true
        shotDeadlineNanos = System.nanoTime() + SHOT_TIMEOUT_NANOS
        if (shot == CameraShot.FOLLOW) {
            followYawPitch = currentYawPitch()
        } else {
            followYawPitch = null
        }
    }

    /** Release the camera-shot mode; the camera stays where it is. */
    fun clearCameraShot() {
        activeShot = null
        shotSteering = false
        followYawPitch = null
    }

    /** The last requested shot (camera mode), or `null` when released. */
    fun getActiveCameraShot(): CameraShot? = activeShot

    /** Stops steering toward the shot; keeps it as the current mode. */
    private fun cancelShotTransition() {
        shotSteering = false
    }

    /** Goal pose of a shot, in world space. */
    private class ShotPose(
        val pivot: FloatArray, // look-at point (3)
        val distance: Float,   // eye distance from the pivot
        val yaw: Float,        // orbit angle around Y; 0 = in front of the avatar (rad)
        val pitch: Float,      // eye elevation above the pivot (rad)
    )

    /**
     * Per-frame steering toward the active shot. The manipulator only exposes
     * gesture primitives, so the glide is a closed loop: measure eye/gaze,
     * then burn off a damped fraction (`step`) of the remaining error through
     * those primitives —
     *  1. strafe-pan: translates eye+pivot laterally (⊥ gaze), carrying the
     *     pivot to the shot's look-at bone;
     *  2. orbit: rotates the gaze (yaw/pitch) around the pivot;
     *  3. scroll: dollies along the gaze to the shot distance.
     * The world→pixel conversions mirror camutils' OrbitManipulator math
     * (see the MANIP_* constants); whatever conversion error remains is
     * cleaned up by the feedback over the following frames.
     */
    private fun updateCameraShot(nowNanos: Long) {
        if (!shotSteering) return
        val shot = activeShot ?: return
        val follow = shot == CameraShot.FOLLOW
        val pose = shotPose(shot) ?: run { shotSteering = false; return }
        // FOLLOW 跟拍永远"未到位"（主体在动）：跳过超时与到位判定，闭环
        // 持续把 pivot 收向移动中的锚点；阻尼更软防跟随抖动。
        if (!follow && nowNanos > shotDeadlineNanos) {
            android.util.Log.w("SoulLinkRenderer", "Camera shot transition timed out")
            shotSteering = false
            return
        }
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0) return
        val dt = ((nowNanos - lastFrameTimeNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        val step = 1f - exp(-dt * (if (follow) FOLLOW_APPROACH_RATE else SHOT_APPROACH_RATE))

        val goalDir = orbitDirection(pose.yaw, pose.pitch)
        val goalEye = floatArrayOf(
            pose.pivot[0] + goalDir[0] * pose.distance,
            pose.pivot[1] + goalDir[1] * pose.distance,
            pose.pivot[2] + goalDir[2] * pose.distance,
        )

        // Measure #1 — current eye and gaze.
        val eye = FloatArray(3)
        val target = FloatArray(3)
        val up = FloatArray(3)
        cameraManipulator.getLookAt(eye, target, up)
        val gaze = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))

        // 1) Strafe pan: lateral (⊥ gaze) correction. camutils moves eye+pivot
        //    by (2·fovTan·D/h) world units per dragged pixel, D = eye→pivot.
        val err = floatArrayOf(goalEye[0] - eye[0], goalEye[1] - eye[1], goalEye[2] - eye[2])
        val along = dot(err, gaze)
        val right = normalize(cross(gaze, WORLD_UP))
        val upward = cross(right, gaze)
        val panD = dist3(eye, pose.pivot).coerceIn(0.3f, 20f)
        val worldPerPixel = 2f * MANIP_FOV_TAN * panD / h
        val panX = -dot(err, right) * step / worldPerPixel
        val panY = -dot(err, upward) * step / worldPerPixel
        if (abs(panX) >= 1f || abs(panY) >= 1f) {
            cameraManipulator.grabBegin(w / 2, h / 2, true)
            cameraManipulator.grabUpdate(
                w / 2 + panX.roundToInt(),
                h / 2 + panY.roundToInt()
            )
            cameraManipulator.grabEnd()
        }

        // 2) Orbit: rotate the gaze toward the shot direction. camutils applies
        //    (grabX − x)·orbitSpeed to yaw and (grabY − y)·orbitSpeed to pitch.
        val dTheta = wrapAngle(pose.yaw - atan2(-gaze[0], -gaze[2])) * step
        val dPhi = (pose.pitch - asin((-gaze[1]).coerceIn(-1f, 1f))) * step
        if (abs(dTheta) > 1e-4f || abs(dPhi) > 1e-4f) {
            cameraManipulator.grabBegin(w / 2, h / 2, false)
            cameraManipulator.grabUpdate(
                w / 2 - (dTheta / MANIP_ORBIT_SPEED).roundToInt(),
                h / 2 - (dPhi / MANIP_ORBIT_SPEED).roundToInt()
            )
            cameraManipulator.grabEnd()
        }

        // 3) Scroll: dolly along the (post-orbit) gaze. camutils moves the eye
        //    by gaze·zoomSpeed·(−delta) meters.
        cameraManipulator.getLookAt(eye, target, up)
        val newGaze = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        val s = dot(
            floatArrayOf(goalEye[0] - eye[0], goalEye[1] - eye[1], goalEye[2] - eye[2]),
            newGaze
        ) * step
        val delta = (-s / MANIP_ZOOM_SPEED).coerceIn(-500f, 500f)
        if (abs(delta) > 1e-3f) {
            cameraManipulator.scroll(w / 2, h / 2, delta)
        }

        // Arrival: every component decays geometrically; stop once the whole
        // pose is within thresholds so gestures work normally again. FOLLOW
        // keeps steering — its subject keeps moving.
        cameraManipulator.getLookAt(eye, target, up)
        val gazeDot = dot(normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2])), goalDir)
        if (!follow && dist3(eye, goalEye) < SHOT_ARRIVE_DIST && gazeDot > cos(SHOT_ARRIVE_ANGLE_RAD)) {
            shotSteering = false
        }
    }

    /**
     * World-space pose for [shot], built from the humanoid bones. Distances
     * scale with the head→hips span so any model proportion frames correctly.
     * Bones that fail to resolve fall back to proportional anchors of the
     * normalized rig (feet ≈ −1, head top ≈ +1 around MODEL_CENTER).
     */
    private fun shotPose(shot: CameraShot): ShotPose? {
        if (modelViewer.asset == null) return null
        val head = boneAnchor(headEntity, HEAD_FALLBACK_Y)
        val chest = boneAnchor(chestEntity, CHEST_FALLBACK_Y)
        val hips = boneAnchor(hipsEntity, HIPS_FALLBACK_Y)
        val span = dist3(head, hips).coerceIn(0.25f, 1.5f)
        return when (shot) {
            CameraShot.CLOSE_UP -> ShotPose(
                pivot = lerp3(head, chest, 0.22f),
                distance = 1.45f * span,
                yaw = 0f,
                pitch = toRadians(6f),
            )
            CameraShot.MACRO -> ShotPose(
                // 面部微距：比特写更贴近（~0.6× 距离），枢轴同样压在面部
                // （头骨略往胸口方向带一点，头骨在 VRM 里常位于颅顶）
                pivot = lerp3(head, chest, 0.18f),
                distance = 0.9f * span,
                yaw = 0f,
                pitch = toRadians(4f),
            )
            CameraShot.MEDIUM_SHOT -> ShotPose(
                pivot = lerp3(head, hips, 0.45f),
                distance = 1.85f * span,
                yaw = 0f,
                pitch = toRadians(4f),
            )
            CameraShot.FULL_SHOT -> ShotPose(
                pivot = lerp3(head, hips, 1.10f),
                distance = 3.9f * span,
                yaw = 0f,
                pitch = toRadians(5f),
            )
            CameraShot.LONG_SHOT -> ShotPose(
                // Full-body plus generous margin so large dance moves
                // (jumps, arm swings, hip travel) stay in frame.
                pivot = lerp3(head, hips, 1.0f),
                distance = 6.0f * span,
                yaw = 0f,
                pitch = toRadians(6f),
            )
            CameraShot.OVER_SHOULDER -> ShotPose(
                pivot = lerp3(head, chest, 0.55f),
                distance = 2.1f * span,
                yaw = toRadians(38f),
                pitch = toRadians(6f),
            )
            CameraShot.FOLLOW -> {
                // 跟拍：pivot 骑在移动的身体上（hips 为主）；yaw/pitch 用
                // 激活瞬间的快照（orbit 修正≈0，闭环只做 pan+dolly，相机
                // 不随角色转向甩动）；距离保留激活时的取景（下限 2.5×span
                // 防贴脸），从远拉近的构图突变不存在——只跟随。
                val pivot = lerp3(head, hips, 0.9f)
                val yawPitch = followYawPitch ?: currentYawPitch().also { followYawPitch = it }
                val currentDist = run {
                    val (eyeNow, targetNow, _) = cameraLookAt()
                    dist3(eyeNow, pivot)
                }
                ShotPose(
                    pivot = pivot,
                    distance = maxOf(currentDist, 2.5f * span),
                    yaw = yawPitch.first,
                    pitch = yawPitch.second.coerceIn(toRadians(-60f), toRadians(60f)),
                )
            }
            // 多虚拟人 P3 的双人同框：单人路径只有一个演员，退化为全景
            CameraShot.GROUP_SHOT -> ShotPose(
                pivot = lerp3(head, hips, 1.10f),
                distance = 3.9f * span,
                yaw = 0f,
                pitch = toRadians(5f),
            )
        }
    }

    /** Current camera orbit angles (yaw around Y, pitch) derived from the gaze. */
    private fun currentYawPitch(): Pair<Float, Float> {
        val (eye, target, _) = cameraLookAt()
        val gaze = normalize(
            floatArrayOf(
                target[0] - eye[0],
                target[1] - eye[1],
                target[2] - eye[2],
            )
        )
        return atan2(-gaze[0], -gaze[2]) to asin((-gaze[1]).coerceIn(-1f, 1f))
    }

    /** World position of a bone entity, or a proportional fallback anchor. */
    private fun boneAnchor(entity: Int, fallbackY: Float): FloatArray {
        if (entity != 0) {
            val tm = modelViewer.engine.transformManager
            val instance = tm.getInstance(entity)
            if (instance != 0) {
                val m = FloatArray(16)
                tm.getWorldTransform(instance, m)
                return floatArrayOf(m[12], m[13], m[14])
            }
        }
        return floatArrayOf(MODEL_CENTER[0], MODEL_CENTER[1] + fallbackY, MODEL_CENTER[2])
    }

    // ── Small vector helpers (camera shot driver) ────────────────────────

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dist3(a: FloatArray, b: FloatArray): Float =
        sqrt(
            (a[0] - b[0]) * (a[0] - b[0]) +
                (a[1] - b[1]) * (a[1] - b[1]) +
                (a[2] - b[2]) * (a[2] - b[2])
        )

    private fun lerp3(a: FloatArray, b: FloatArray, t: Float): FloatArray = floatArrayOf(
        a[0] + (b[0] - a[0]) * t,
        a[1] + (b[1] - a[1]) * t,
        a[2] + (b[2] - a[2]) * t,
    )

    /** Unit vector from the pivot toward the eye for orbit angles (θ, φ). */
    private fun orbitDirection(theta: Float, phi: Float): FloatArray =
        floatArrayOf(sin(theta) * cos(phi), sin(phi), cos(theta) * cos(phi))

    /** Wrap [x] into (−π, π]. */
    private fun wrapAngle(x: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        return ((x + Math.PI.toFloat()) % twoPi + twoPi) % twoPi - Math.PI.toFloat()
    }

    private fun toRadians(deg: Float): Float = (deg * Math.PI / 180.0).toFloat()

    /**
     * Capture the next rendered frame as a [Bitmap]. The callback fires on the
     * render (main) thread once the frame completes; return `false` if no
     * frame is being rendered.
     */
    fun captureNextFrame(onCaptured: (Bitmap) -> Unit): Boolean {
        return if (!isRendering) false else {
            modelViewer.debugGetNextFrameCallback(onCaptured)
            true
        }
    }
}
