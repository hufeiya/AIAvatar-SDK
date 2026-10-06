package com.neethu.corelib.internal

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.SurfaceView
import android.view.Choreographer
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
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
import com.neethu.corelib.LightingRig
import com.neethu.corelib.LookAtInfo
import com.neethu.corelib.MimicInfo
import com.neethu.corelib.MimicPose
import com.neethu.corelib.ToneMappingMode

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

    // Set when the model pose is about to snap (animation / VRMA start-stop); the
    // spring bone tails are re-anchored after the first frame of the new pose.
    private var pendingSpringReset: Boolean = false

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

    // Scene (environment/background GLB) support
    private var sceneAsset: FilamentAsset? = null
    private var sceneAssetLoader: AssetLoader? = null
    private var sceneResourceLoader: ResourceLoader? = null
    private val sceneReadyRenderables = IntArray(128)

    // Drag mode support
    var isDragMode: Boolean = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Hot-swappable PBR render settings, driven by applyRenderSettings()
    private var renderSettings: AvatarRenderSettings = config.renderSettings
    private var colorGrading: ColorGrading? = null
    private var appliedToneMapping: ToneMappingMode? = null

    // Studio light rig entities (0 = not built)
    private var keyLightEntity: Int = 0
    private var fillLightEntity: Int = 0
    private var rimLightEntity: Int = 0

    // FPS measurement (reported via onFpsUpdated every FPS_WINDOW_NANOS)
    var onFpsUpdated: ((Int) -> Unit)? = null
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
        // a pathological pose can never wedge the driver on.
        private const val SHOT_APPROACH_RATE = 7f
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

        private const val CONTACT_SHADOW_STEPS = 8
        private const val AO_RADIUS = 0.15f

        private const val FPS_WINDOW_NANOS = 500_000_000L

        // Pinch-pixels → manipulator scroll delta, matching GestureDetector's
        // private kZoomSpeed so programmatic zoom scales identically to fingers.
        private const val ZOOM_SPEED_PER_PIXEL = 0.1f

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

        applyToneMapping(settings.toneMapping)
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

    private fun setupSkybox() {
        val bg = config.backgroundColor
        val skybox = Skybox.Builder()
            .color(bg[0], bg[1], bg[2], bg.getOrElse(3) { 1.0f })
            .build(modelViewer.engine)
        modelViewer.scene.skybox = skybox
    }

    // ── Public API (for AvatarController only, internal) ─────────────────

    /**
     * Load a VRM/GLB model from assets.
     * @throws Exception if the model cannot be loaded.
     */
    fun loadModel(assetsPath: String) {
        val assets = surfaceView.context.assets
        assets.open(assetsPath).use { input ->
            val bytes = input.readBytes()
            val buffer = ByteBuffer.wrap(bytes)

            // Pre-process: inject default morph weights so gltfio uploads morph target
            // normals (gltfio skips that upload when mesh.weights_count == 0, which
            // corrupts shading as soon as an expression drives a weight non-zero).
            buffer.rewind()
            val morphPatched = GlbMorphPatcher.injectMorphDefaultWeights(buffer)

            // Pre-process: cull unused bones to stay within Filament's 256 bone limit
            val loadBuffer = GlbBoneCuller.cullUnusedBones(morphPatched)
                ?: morphPatched.also { it.rewind() }

            // Clear old physics and animation state before loading the new model.
            // This prevents the Choreographer from trying to access destroyed entities
            // if the ensuing initialization crashes or throws an exception.
            stopAnimation()
            stopVrmaAnimation()
            vrmaEngine = null
            idleSource = null
            expressionManager = null
            lookAtEngine = null
            breathEngine = null
            mimicEngine = null
            springBoneManager = null
            animator = null

            // Load model into Filament
            modelViewer.loadModelGlb(loadBuffer)
            modelViewer.transformToUnitCube()

            // Get animation controller
            animator = modelViewer.animator

            // Default: play first animation if available
            if ((animator?.animationCount ?: 0) > 0) {
                playAnimation(0, loop = true)
            }

            // Initialize VRMA engine and bind to model
            vrmaEngine = VrmaAnimationEngine(modelViewer.engine)
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
        }

        // Load IBL if configured
        config.iblPath?.let { iblPath ->
            loadEnvironment(iblPath)
        }

        applyMaterialEnhancements()
        // 蒙皮/morph 顶点会离开 gltfio 的绑定姿态静态 culling 盒（眼球丢失
        // 根因，见 [avatarCullingEnabled] 注释）——按当前开关重新应用
        applyAvatarCulling()
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
     * Stops any built-in animation that's playing.
     */
    fun playVrmaAnimation(loop: Boolean = true) {
        // Stop built-in animation
        currentAnimationIndex = -1
        // Start VRMA
        vrmaStartTime = System.nanoTime()
        vrmaEngine?.play(loop)
        pendingSpringReset = true
    }

    /**
     * Stop the VRMA animation. With an idle configured the engine resumes the
     * idle loop, so restart the playback clock for a clean phase.
     */
    fun stopVrmaAnimation() {
        vrmaEngine?.stop()
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
     * @param assetsPath Path to the .glb scene file in assets.
     */
    fun loadScene(assetsPath: String) {
        // Remove any existing scene first
        removeScene()

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
                    android.util.Log.i("SoulLinkRenderer",
                        "Scene loaded: $assetsPath (${asset.entities.size} entities)")
                } else {
                    android.util.Log.e("SoulLinkRenderer", "Failed to create scene asset: $assetsPath")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to load scene: $assetsPath", e)
        }
    }

    /**
     * Remove the currently loaded scene from the Filament scene.
     */
    fun removeScene() {
        sceneAsset?.let { asset ->
            sceneResourceLoader?.asyncCancelLoad()
            sceneResourceLoader?.evictResourceData()
            modelViewer.scene.removeEntities(asset.entities)
            sceneAssetLoader?.destroyAsset(asset)
            android.util.Log.i("SoulLinkRenderer", "Scene removed")
        }
        sceneAsset = null
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

    override fun onDestroy(owner: LifecycleOwner) {
        stopRendering()
        shotSteering = false
        activeShot = null
        removeScene()
        sceneResourceLoader?.destroy()
        sceneResourceLoader = null
        sceneAssetLoader?.destroy()
        sceneAssetLoader = null
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
    }

    /** Release the camera-shot mode; the camera stays where it is. */
    fun clearCameraShot() {
        activeShot = null
        shotSteering = false
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
        val pose = shotPose(activeShot ?: return) ?: run { shotSteering = false; return }
        if (nowNanos > shotDeadlineNanos) {
            android.util.Log.w("SoulLinkRenderer", "Camera shot transition timed out")
            shotSteering = false
            return
        }
        val w = surfaceView.width
        val h = surfaceView.height
        if (w <= 0 || h <= 0) return
        val dt = ((nowNanos - lastFrameTimeNanos) / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
        val step = 1f - exp(-dt * SHOT_APPROACH_RATE)

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
        // pose is within thresholds so gestures work normally again.
        cameraManipulator.getLookAt(eye, target, up)
        val gazeDot = dot(normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2])), goalDir)
        if (dist3(eye, goalEye) < SHOT_ARRIVE_DIST && gazeDot > cos(SHOT_ARRIVE_ANGLE_RAD)) {
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
        }
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
