package com.neethu.corelib.internal

import android.content.Context
import android.view.SurfaceView
import android.view.Choreographer
import com.google.android.filament.ColorGrading
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.ModelViewer
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

import com.neethu.corelib.AmbientOcclusionQuality
import com.neethu.corelib.AntiAliasingMode
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.LightingRig
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

    private var modelViewer: ModelViewer = ModelViewer(surfaceView)

    private var animator: com.google.android.filament.gltfio.Animator? = null
    private var startTime = System.nanoTime()

    // Built-in animation state
    private var currentAnimationIndex: Int = -1
    private var isAnimationLooping: Boolean = true

    // VRMA animation support
    private var vrmaParser: VrmaParser = VrmaParser()
    private var vrmaEngine: VrmaAnimationEngine? = null
    private var vrmaStartTime: Long = 0L

    // Expression (morph target / blend shape) support
    private var expressionManager: VrmExpressionManager? = null

    // Spring bone physics support
    private var springBoneManager: VrmSpringBoneManager? = null
    private var lastFrameTimeNanos: Long = 0L

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
    private var lightRigSignature: LightRigSignature? = null

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

        // Three-point studio rig. Direction = the way the light travels:
        // key and fill come from above the camera side (+Z), the rim sits
        // behind and above the model (source at -Z) to outline hair/shoulders.
        private val KEY_COLOR = floatArrayOf(1.0f, 0.98f, 0.95f)
        private const val KEY_LUX = 90_000f
        private val KEY_DIRECTION = floatArrayOf(-0.5f, -1.0f, -0.5f)
        private val FILL_COLOR = floatArrayOf(0.8f, 0.85f, 1.0f)
        private const val FILL_LUX = 30_000f
        private val FILL_DIRECTION = floatArrayOf(0.5f, -0.4f, -0.5f)
        private val RIM_COLOR = floatArrayOf(0.95f, 0.97f, 1.0f)
        private const val RIM_LUX = 120_000f
        private val RIM_DIRECTION = floatArrayOf(0.0f, -0.6f, 0.8f)

        private const val CONTACT_SHADOW_STEPS = 8
        private const val AO_RADIUS = 0.15f

        private const val FPS_WINDOW_NANOS = 500_000_000L

        // Material name hints for applyMaterialEnhancements (VRM/glTF conventions)
        private val EYE_NAME_HINTS = listOf("eye", "目", "瞳")
        private val SKIN_NAME_HINTS = listOf("skin", "face", "body", "顔", "脸", "肌")
        private val HAIR_NAME_HINTS = listOf("hair", "髪", "发")
    }

    private val choreoCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // VRMA animation takes priority over built-in animations
            val vrma = vrmaEngine
            if (vrma != null && vrma.isActive()) {
                val elapsed = (frameTimeNanos - vrmaStartTime).toDouble() / 1_000_000_000.0
                vrma.update(elapsed.toFloat())
                animator?.updateBoneMatrices()
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
                        anim.updateBoneMatrices()
                    }
                }
            }

            // Update expression morph weights each frame (with smooth transitions)
            expressionManager?.update(frameTimeNanos)

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
                    true
                } else {
                    modelViewer.onTouchEvent(event)
                    true
                }
            }
        }
    }

    // ── Render Settings API ──────────────────────────────────────────────

    /**
     * Apply a full set of PBR render settings. Every part is hot-swappable;
     * safe to call repeatedly with unchanged values (light entities are only
     * rebuilt when the rig or shadow options actually change).
     */
    fun applyRenderSettings(settings: AvatarRenderSettings) {
        renderSettings = settings
        applyLighting()
        applyIblSettings()
        applyViewSettings()
        applyMaterialEnhancements()
    }

    /** Identity of everything that requires rebuilding the light entities. */
    private data class LightRigSignature(
        val rig: LightingRig,
        val shadowMapSize: Int,
        val contactShadows: Boolean,
    )

    private fun applyLighting() {
        val signature = LightRigSignature(
            rig = renderSettings.lightingRig,
            shadowMapSize = renderSettings.shadowMapSize,
            contactShadows = renderSettings.contactShadows,
        )
        if (signature == lightRigSignature && keyLightEntity != 0) return
        lightRigSignature = signature

        val engine = modelViewer.engine
        val scene = modelViewer.scene
        val entityManager = EntityManager.get()

        listOf(keyLightEntity, fillLightEntity, rimLightEntity)
            .filter { it != 0 }
            .forEach { entity ->
                scene.removeEntity(entity)
                engine.destroyEntity(entity)
            }
        keyLightEntity = 0
        fillLightEntity = 0
        rimLightEntity = 0

        // Key light — the only shadow caster of the rig
        keyLightEntity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(KEY_COLOR[0], KEY_COLOR[1], KEY_COLOR[2])
            .intensity(KEY_LUX)
            .direction(KEY_DIRECTION[0], KEY_DIRECTION[1], KEY_DIRECTION[2])
            .castShadows(true)
            .shadowOptions(
                LightManager.ShadowOptions().apply {
                    mapSize = renderSettings.shadowMapSize
                    screenSpaceContactShadows = renderSettings.contactShadows
                    stepCount = CONTACT_SHADOW_STEPS
                }
            )
            .build(engine, keyLightEntity)
        scene.addEntity(keyLightEntity)

        if (renderSettings.lightingRig != LightingRig.KEY_ONLY) {
            fillLightEntity = entityManager.create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(FILL_COLOR[0], FILL_COLOR[1], FILL_COLOR[2])
                .intensity(FILL_LUX)
                .direction(FILL_DIRECTION[0], FILL_DIRECTION[1], FILL_DIRECTION[2])
                .castShadows(false)
                .build(engine, fillLightEntity)
            scene.addEntity(fillLightEntity)
        }

        if (renderSettings.lightingRig == LightingRig.STUDIO) {
            rimLightEntity = entityManager.create()
            LightManager.Builder(LightManager.Type.DIRECTIONAL)
                .color(RIM_COLOR[0], RIM_COLOR[1], RIM_COLOR[2])
                .intensity(RIM_LUX)
                .direction(RIM_DIRECTION[0], RIM_DIRECTION[1], RIM_DIRECTION[2])
                .castShadows(false)
                .build(engine, rimLightEntity)
            scene.addEntity(rimLightEntity)
        }
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
            expressionManager = null
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
                        mgr.expandVrm0Chains(asset)
                        mgr.bindToAsset(asset, bytes)
                    }
                }
            }
        }

        // Load IBL if configured
        config.iblPath?.let { iblPath ->
            loadEnvironment(iblPath)
        }

        applyMaterialEnhancements()
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
                val buffer = ByteBuffer.wrap(bytes)
                val animation = vrmaParser.parse(buffer)
                if (animation != null) {
                    vrmaEngine?.setAnimation(animation)
                    android.util.Log.i("SoulLinkRenderer",
                        "Loaded VRMA: ${animation.duration}s, ${animation.humanoidTracks.size} bone tracks")
                    true
                } else {
                    android.util.Log.e("SoulLinkRenderer", "Failed to parse VRMA: $assetsPath")
                    false
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to load VRMA: $assetsPath", e)
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
    }

    /**
     * Stop the VRMA animation.
     */
    fun stopVrmaAnimation() {
        vrmaEngine?.stop()
    }

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

    private fun startRendering() {
        Choreographer.getInstance().postFrameCallback(choreoCallback)
    }

    private fun stopRendering() {
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
        lightRigSignature = null
        colorGrading?.let { engine.destroyColorGrading(it) }
        colorGrading = null
        appliedToneMapping = null
    }

    // ── Spring Bone API ──────────────────────────────────────────────────

    fun setSpringBoneEnabled(enabled: Boolean) {
        springBoneManager?.setEnabled(enabled)
    }
}
