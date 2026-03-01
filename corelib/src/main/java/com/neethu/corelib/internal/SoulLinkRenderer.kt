package com.neethu.corelib.internal

import android.content.Context
import android.view.SurfaceView
import android.view.Choreographer
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Skybox
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

    // Animation state
    private var currentAnimationIndex: Int = -1
    private var isAnimationLooping: Boolean = true

    companion object {
        init {
            com.google.android.filament.utils.Utils.init()
        }
    }

    private val choreoCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
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
                modelViewer.onTouchEvent(event)
                true
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
            .color(1.0f, 0.98f, 0.95f)
            .intensity(90_000f)
            .direction(-0.5f, -1.0f, -0.5f)
            .castShadows(true)
            .build(engine, sunEntity)
        modelViewer.scene.addEntity(sunEntity)

        // Fill light
        val fillEntity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.8f, 0.85f, 1.0f)
            .intensity(30_000f)
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

            // Pre-process: cull unused bones to stay within Filament's 256 bone limit
            buffer.rewind()
            val loadBuffer = GlbBoneCuller.cullUnusedBones(buffer) ?: buffer.also { it.rewind() }

            // Load model into Filament
            modelViewer.loadModelGlb(loadBuffer)
            modelViewer.transformToUnitCube()

            // Get animation controller
            animator = modelViewer.animator

            // Default: play first animation if available
            if ((animator?.animationCount ?: 0) > 0) {
                playAnimation(0, loop = true)
            }

            // Apply custom MToon material
            if (useMToonMaterial) {
                modelViewer.asset?.let { asset ->
                    if (parsedVrm != null && parsedVrm.textures.isNotEmpty()) {
                        mtoonHelper?.applyToAssetWithTextures(asset, parsedVrm.textures, materialInfos, primitiveInfos)
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

    /**
     * @return the number of animations in the current model, or 0 if no model is loaded.
     */
    fun getAnimationCount(): Int = animator?.animationCount ?: 0

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
        mtoonHelper?.destroy()
        mtoonHelper = null
    }
}
