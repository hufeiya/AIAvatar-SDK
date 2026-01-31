package com.neethu.corelib

import android.content.Context
import android.view.SurfaceView
import android.view.Choreographer
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Colors
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.utils.KTX1Loader
import com.google.android.filament.utils.ModelViewer
import java.nio.ByteBuffer

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class SoulLinkRenderer(
    context: Context,
    private val surfaceView: SurfaceView
) : DefaultLifecycleObserver {
    private var modelViewer: ModelViewer = ModelViewer(surfaceView)
    // 在 SoulLinkRenderer 类中添加变量
    private var animator: com.google.android.filament.gltfio.Animator? = null
    // 在 SoulLinkRenderer 类中添加变量用于计算时间
    private var startTime = System.nanoTime()
    
    // MToon material helper for VRM toon shading
    private var mtoonHelper: MToonMaterialHelper? = null
    private var useMToonMaterial: Boolean = true

    companion object {
        init {
             com.google.android.filament.utils.Utils.init()
        }
    }

    private val choreoCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            modelViewer.render(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
            // 【核心逻辑】更新动画
            animator?.let { anim ->
                if (anim.animationCount > 0) {
                    // 1. 计算流逝的时间 (秒)
                    val elapsedTimeSeconds = (frameTimeNanos - startTime).toDouble() / 1_000_000_000.0

                    // 2. 应用动画
                    // 参数 0: 播放第 0 个动画
                    // 参数 time: 当前播放进度
                    anim.applyAnimation(0, elapsedTimeSeconds.toFloat())

                    // 3. 极其重要！告诉引擎更新骨骼矩阵
                    anim.updateBoneMatrices()
                }
            }

            modelViewer.render(frameTimeNanos)
        }
    }

    init {
        setupLighting()
        setupMToonMaterial()
        // allow users to rotate the model
        surfaceView.setOnTouchListener { _, event ->
            modelViewer.onTouchEvent(event)
            true
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

        // 1. Skybox - Light gray background
        val skybox = Skybox.Builder()
            .color(0.2f, 0.2f, 0.25f, 1.0f)
            .build(engine)
        modelViewer.scene.skybox = skybox
        
        // 2. Add directional sunlight - soft lighting for VRM characters
        val sunEntity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.98f, 0.95f)  // Warm white
            .intensity(90_000f)          // Balanced main light
            .direction(-0.5f, -1.0f, -0.5f)  // From upper-front-left
            .castShadows(true)
            .build(engine, sunEntity)
        modelViewer.scene.addEntity(sunEntity)
        
        // 3. Add fill light from opposite direction
        val fillEntity = entityManager.create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.8f, 0.85f, 1.0f)   // Cool white (slight blue)
            .intensity(30_000f)          // Softer fill light
            .direction(0.5f, -0.5f, 0.5f)  // From lower-back-right
            .castShadows(false)
            .build(engine, fillEntity)
        modelViewer.scene.addEntity(fillEntity)
    }

    fun loadModel(assetsPath: String) {
        try {
            val assets = surfaceView.context.assets
            assets.open(assetsPath).use { input ->
                val bytes = input.readBytes()
                val buffer = ByteBuffer.wrap(bytes)
                
                // Parse VRM/GLB first to extract textures (before loadModelGlb consumes buffer)
                var parsedVrm: VrmGlbParser.ParsedVrm? = null
                var materialInfos: List<VrmGlbParser.MaterialInfo> = emptyList()
                var primitiveInfos: List<VrmGlbParser.PrimitiveInfo> = emptyList()
                
                if (useMToonMaterial) {
                    try {
                        val parser = VrmGlbParser(modelViewer.engine)
                        val parseBuffer = ByteBuffer.wrap(bytes) // Fresh buffer for parsing
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
                
                // Load model into Filament
                buffer.rewind()
                modelViewer.loadModelGlb(buffer)
                modelViewer.transformToUnitCube()
                
                // 【新增】获取动画控制器
                // 注意：每次加载新模型，animator 都会变，需要重新获取
                animator = modelViewer.animator

                // 【新增】如果有动画，默认开始播放第 0 个
                if (animator?.animationCount ?: 0 > 0) {
                    startTime = System.nanoTime()
                }
                
                // 应用自定义 MToon 材质
                // testFilamentDefault = true 时使用 Filament 默认渲染（用于测试）
                val testFilamentDefault = false  // 设为 true 测试 Filament 默认渲染
                
                if (useMToonMaterial && !testFilamentDefault) {
                    modelViewer.asset?.let { asset ->
                        if (parsedVrm != null && parsedVrm.textures.isNotEmpty()) {
                            // Use extracted textures with proper primitive mapping
                            mtoonHelper?.applyToAssetWithTextures(asset, parsedVrm.textures, materialInfos, primitiveInfos)
                            android.util.Log.i("SoulLinkRenderer", 
                                "Applied MToon with ${parsedVrm.textures.size} textures, ${primitiveInfos.size} primitive mappings")
                        } else {
                            // Fallback to dummy textures
                            mtoonHelper?.applyToAsset(asset)
                            android.util.Log.i("SoulLinkRenderer", "Applied MToon with default textures")
                        }
                    }
                } else {
                    android.util.Log.i("SoulLinkRenderer", "Using Filament default PBR rendering (test mode)")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                    ibl.intensity = 5000f  // Reduced from 30000f to fix overbright
                    modelViewer.scene.indirectLight = ibl
                }
            }
        } catch (e: Exception) {
             e.printStackTrace()

             android.util.Log.e("SoulLinkRenderer", "Failed to load IBL from $iblPath", e)
        }

        try {
            // Apply some view settings for better look
            val view = modelViewer.view
            view.renderQuality = view.renderQuality.apply {
                hdrColorBuffer = com.google.android.filament.View.QualityLevel.HIGH
            }
            view.dynamicResolutionOptions = view.dynamicResolutionOptions.apply {
                enabled = false
            }

            view.toneMapping = com.google.android.filament.View.ToneMapping.ACES
            
        } catch (e: Exception) {
             e.printStackTrace()
        }
    }
    
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
        // modelViewer.destroy() // Unresolved in this version
    }
    
    /**
     * Enable or disable MToon material for VRM models.
     * When disabled, default PBR rendering will be used.
     */
    fun setUseMToonMaterial(enabled: Boolean) {
        useMToonMaterial = enabled
    }
    
    /**
     * Check if MToon material is currently available and enabled
     */
    fun isMToonMaterialEnabled(): Boolean = useMToonMaterial && (mtoonHelper?.isAvailable() ?: false)
}
