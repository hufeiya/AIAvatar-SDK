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
        // allow users to rotate the model
        surfaceView.setOnTouchListener { _, event ->
            modelViewer.onTouchEvent(event)
            true
        }
    }

    private fun setupLighting() {
        val engine = modelViewer.engine
        val entityManager = EntityManager.get()

        // 1. Skybox - Keep it gray to see silhouette
        val skybox = Skybox.Builder()
            .color(0.1f, 0.1f, 0.1f, 1.0f)
            .build(engine)
        modelViewer.scene.skybox = skybox

    }

    fun loadModel(assetsPath: String) {
        try {
            val assets = surfaceView.context.assets
            assets.open(assetsPath).use { input ->
                val bytes = input.readBytes()
                val buffer = ByteBuffer.wrap(bytes)
                modelViewer.loadModelGlb(buffer)
                modelViewer.transformToUnitCube()
                // 【新增】获取动画控制器
                // 注意：每次加载新模型，animator 都会变，需要重新获取
                animator = modelViewer.animator

                // 【新增】如果有动画，默认开始播放第 0 个 (通常就是你从 Mixamo 下载的那个)
                if (animator?.animationCount ?: 0 > 0) {
                    // playAnimation(index) 方法等会儿在下面定义
                    // 这里仅仅是重置动画时间，真正的播放靠 doFrame
                    startTime = System.nanoTime()
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
                    ibl.intensity = 30000f
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
        // modelViewer.destroy() // Unresolved in this version
    }
}
