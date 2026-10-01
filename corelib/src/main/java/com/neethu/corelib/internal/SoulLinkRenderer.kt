package com.neethu.corelib.internal

import android.content.Context
import android.view.SurfaceView
import android.view.Choreographer
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.ModelViewer
import java.nio.ByteBuffer

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

import com.neethu.corelib.AvatarConfig

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

    // MToon material helper for VRM toon shading
    private var mtoonHelper: MToonMaterialHelper? = null
    private var useMToonMaterial: Boolean = config.enableMToon

    // Built-in animation state
    private var currentAnimationIndex: Int = -1
    private var isAnimationLooping: Boolean = true

    // VRMA animation support
    private var vrmaParser: VrmaParser = VrmaParser()
    private var vrmaEngine: VrmaAnimationEngine? = null
    private var vrmaStartTime: Long = 0L
    private var currentModelGlbBytes: ByteArray? = null

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

    companion object {
        init {
            com.google.android.filament.utils.Utils.init()
        }

        // Scene directional light rig. Shared between light creation and MToon
        // material normalization (MToonMaterialHelper.setLightRig).
        private val SUN_COLOR = floatArrayOf(1.0f, 0.98f, 0.95f)
        private const val SUN_LUX = 90_000f
        private val FILL_COLOR = floatArrayOf(0.8f, 0.85f, 1.0f)
        private const val FILL_LUX = 30_000f
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

            // Progressively populate scene entities as textures become ready
            populateSceneEntities()

            modelViewer.render(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setupLighting()
        if (useMToonMaterial) {
            setupMToonMaterial()
        }
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

    private fun setupMToonMaterial() {
        mtoonHelper = MToonMaterialHelper(modelViewer.engine, surfaceView.context)
        try {
            val loaded = mtoonHelper?.loadMaterial() ?: false
            if (!loaded) {
                android.util.Log.w("SoulLinkRenderer", "MToon material not found, using default PBR")
                useMToonMaterial = false
            } else {
                // Mirror the light rig so the shader can normalize lux into three-vrm units
                mtoonHelper?.setLightRig(SUN_COLOR, SUN_LUX, FILL_COLOR, FILL_LUX)
            }
        } catch (e: Exception) {
            android.util.Log.w("SoulLinkRenderer", "Failed to load MToon material", e)
            useMToonMaterial = false
        }
    }

    private fun setupLighting() {
        val engine = modelViewer.engine
        val entityManager = EntityManager.get()

        // Skybox
        val bg = config.backgroundColor
        val skybox = Skybox.Builder()
            .color(bg[0], bg[1], bg[2], bg.getOrElse(3) { 1.0f })
            .build(engine)
        modelViewer.scene.skybox = skybox

        // Main directional light
        val sunEntity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(SUN_COLOR[0], SUN_COLOR[1], SUN_COLOR[2])
            .intensity(SUN_LUX)
            .direction(-0.5f, -1.0f, -0.5f)
            .castShadows(true)
            .build(engine, sunEntity)
        modelViewer.scene.addEntity(sunEntity)

        // Fill light
        val fillEntity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(FILL_COLOR[0], FILL_COLOR[1], FILL_COLOR[2])
            .intensity(FILL_LUX)
            .direction(0.5f, -0.5f, 0.5f)
            .castShadows(false)
            .build(engine, fillEntity)
        modelViewer.scene.addEntity(fillEntity)
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

            // Store raw bytes for VRMA bone mapping later
            currentModelGlbBytes = bytes

            // Parse VRM/GLB to extract textures for MToon
            var parsedVrm: VrmGlbParser.ParsedVrm? = null
            var materialInfos: List<VrmGlbParser.MaterialInfo> = emptyList()
            var primitiveInfos: List<VrmGlbParser.PrimitiveInfo> = emptyList()

            if (useMToonMaterial) {
                try {
                    val parser = VrmGlbParser(modelViewer.engine)
                    val parseBuffer = ByteBuffer.wrap(bytes)
                    parsedVrm = parser.parse(parseBuffer)
                    parsedVrm?.let { vrm ->
                        materialInfos = parser.getMaterialInfos(vrm.json)
                        primitiveInfos = parser.getPrimitiveMaterialMapping(vrm.json)
                        android.util.Log.i("SoulLinkRenderer",
                            "Parsed VRM: ${vrm.textures.size} textures, ${materialInfos.size} materials, ${primitiveInfos.size} primitives")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("SoulLinkRenderer", "VRM parsing failed, using default", e)
                }
            }

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

            // Apply custom MToon material
            if (useMToonMaterial) {
                modelViewer.asset?.let { asset ->
                    // VRM 0.x models need shade clamping to prevent overbright
                    val isV0 = vrmaEngine?.getVrmMetaVersion() == "0"
                    if (parsedVrm != null && parsedVrm.textures.isNotEmpty()) {
                        mtoonHelper?.applyToAssetWithTextures(asset, parsedVrm.textures, materialInfos, primitiveInfos, isV0)
                    } else {
                        mtoonHelper?.applyToAsset(asset)
                    }
                }
            }
        }

        // Load IBL if configured
        config.iblPath?.let { iblPath ->
            loadEnvironment(iblPath)
        }
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
                    ibl.intensity = 5000f
                    modelViewer.scene.indirectLight = ibl
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to load IBL from $iblPath", e)
        }

        try {
            val view = modelViewer.view
            view.renderQuality = view.renderQuality.apply {
                hdrColorBuffer = com.google.android.filament.View.QualityLevel.HIGH
            }
            view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
                enabled = false
            }
            view.toneMapping = com.google.android.filament.View.ToneMapping.ACES
        } catch (e: Exception) {
            android.util.Log.e("SoulLinkRenderer", "Failed to apply view settings", e)
        }
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
        mtoonHelper?.destroy()
        mtoonHelper = null
        springBoneManager = null
    }

    // ── Spring Bone API ──────────────────────────────────────────────────

    fun setSpringBoneEnabled(enabled: Boolean) {
        springBoneManager?.setEnabled(enabled)
    }
}
