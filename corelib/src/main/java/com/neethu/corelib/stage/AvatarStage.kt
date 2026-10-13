package com.neethu.corelib.stage

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.AvatarState
import com.neethu.corelib.CameraShot
import com.neethu.corelib.ScenePlacement
import com.neethu.corelib.stage.internal.ModelPreprocessor
import com.neethu.corelib.stage.internal.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 多虚拟人舞台的门面（docs/multi-avatar-feasibility.md P0）：持有 [Stage]
 * 引擎并对外暴露「舞台级」操作——加载/移除演员、场景、渲染设置、运镜、
 * 截帧、FPS——per-演员操作走 [AvatarHandle]。
 *
 * 生命周期：[AvatarStageView]（View/Compose 两种形态）把它绑到
 * LifecycleOwner——resume 起帧泵，pause 停，destroy 释放全部 Filament 资源。
 * 一个舞台同一时刻挂一个 SurfaceView。
 *
 * 加载管线是异步的（P0 顺手项）：字节读取与预处理（morph 补丁/骨骼裁剪/
 * MToon 描边复制）在 Default 线程，createAsset + 引擎绑定在主线程，纹理经
 * ResourceLoader 流式上传——双人启动不再有 2×1.2s 的主线程整块阻塞。
 */
class AvatarStage internal constructor(
    val config: AvatarConfig = AvatarConfig(),
) : DefaultLifecycleObserver {

    internal var impl: Stage? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _fps = MutableStateFlow(0)

    private val _attached = MutableStateFlow(false)

    /** 舞台帧率（每 0.5s 更新；未渲染 = 0）。 */
    val fps: StateFlow<Int> = _fps.asStateFlow()

    /**
     * 视图挂载状态：true = [Stage] 引擎就绪，[loadAvatar]/[loadScene] 可用。
     * Compose 侧用它门控开场装配（替代旧的 onStage 回调——回调写状态会与
     * 组合结构变化互相触发，产生重组风暴）。
     */
    val attached: StateFlow<Boolean> = _attached.asStateFlow()

    private val _scenePath = MutableStateFlow<String?>(null)

    /** 当前场景 assets 路径（null = 无场景）。 */
    val scenePath: StateFlow<String?> = _scenePath.asStateFlow()

    private var runtimeSeq = 0

    /** 已加载/加载中的演员（按加载顺序）。 */
    val avatars: List<AvatarHandle>
        get() = implHandles.toList()

    private val implHandles = ArrayList<AvatarHandle>()

    private val _avatarsVersion = MutableStateFlow(0)

    /** [avatars] 的变更计数（Compose 驱动重组用）。 */
    val avatarsVersion: StateFlow<Int> = _avatarsVersion.asStateFlow()

    // ── 演员加载 ─────────────────────────────────────────────────────────

    /**
     * 异步加载一个虚拟人。立即返回句柄（state = Loading），字节管线后台跑，
     * 完成后 state → Ready/Error——UI collect [state] 渲染进度即可。
     *
     * fresh 加载跟随模型材质自动选渲染模式（MToon 模型 → MTOON），与单人
     * 控制器语义一致；结果同步到 [AvatarHandle.detectedRenderMode]。
     */
    fun loadAvatar(spec: AvatarSpec): AvatarHandle {
        val stage = impl ?: error("AvatarStage not attached to a view yet")
        val id = (++runtimeSeq).let { n -> ('A' + n - 1).toString() }
        val runtime = stage.createRuntime(id)
        val handle = AvatarHandle(id, spec, runtime, this)
        implHandles.add(handle)
        _avatarsVersion.value += 1

        val pathKey = spec.modelAssetPath ?: spec.modelFilePath ?: "?"
        handle.setState(AvatarState.Loading(pathKey))

        scope.launch {
            val prepared = try {
                withContext(Dispatchers.Default) {
                    val bytes = readModelBytes(spec)
                    ModelPreprocessor.process(
                        bytes,
                        requestedMode = stage.renderSettings.renderMode,
                        autoRenderMode = true,
                    )
                }
            } catch (e: Exception) {
                handle.setState(
                    AvatarState.Error(message = "Failed to load model: ${e.message}", cause = e)
                )
                return@launch
            }
            try {
                prepared.detectedRenderMode?.let { detected ->
                    stage.setDetectedRenderMode(detected)
                }
                runtime.bind(prepared, spec.spawn)
                handle.setState(
                    AvatarState.Ready(
                        animationCount = runtime.getAnimationCount(),
                        expressions = runtime.getAvailableExpressions(),
                    )
                )
            } catch (e: Exception) {
                handle.setState(
                    AvatarState.Error(message = "Failed to load model: ${e.message}", cause = e)
                )
            }
        }
        return handle
    }

    private fun readModelBytes(spec: AvatarSpec): ByteArray {
        val modelAssetPath = spec.modelAssetPath
        if (modelAssetPath != null) {
            val view = attachedView()
            return view.context.assets.open(modelAssetPath).use { it.readBytes() }
        }
        val file = java.io.File(
            requireNotNull(spec.modelFilePath) { "AvatarSpec has no model path" }
        )
        if (!file.isFile) throw java.io.FileNotFoundException("model file not found: ${file.path}")
        return file.inputStream().use { it.readBytes() }
    }

    private fun attachedView(): SurfaceView =
        implView ?: error("AvatarStage not attached to a view yet")

    private var implView: SurfaceView? = null

    /** 移除一个演员（释放其 asset / MToon GPU 资源；运镜目标指向它时一并解除）。 */
    fun removeAvatar(handle: AvatarHandle) {
        val stage = impl ?: return
        stage.removeRuntime(handle.runtime)
        implHandles.remove(handle)
        handle.setState(AvatarState.Idle)
        _avatarsVersion.value += 1
    }

    // ── 场景 ─────────────────────────────────────────────────────────────

    fun loadScene(assetPath: String, placement: ScenePlacement? = null) {
        impl?.loadScene(assetPath, placement)
        _scenePath.value = assetPath
    }

    fun setScenePlacement(placement: ScenePlacement?) {
        impl?.setScenePlacement(placement)
    }

    fun getScenePlacement(): ScenePlacement? = impl?.getScenePlacement()

    fun removeScene() {
        impl?.removeScene()
        _scenePath.value = null
    }

    // ── 渲染设置（舞台级，全演员共享）────────────────────────────────────

    fun applyRenderSettings(settings: AvatarRenderSettings) {
        impl?.applyRenderSettings(settings)
    }

    // ── 相机 ─────────────────────────────────────────────────────────────

    fun zoomCamera(spreadPx: Float) = impl?.zoomCamera(spreadPx)

    fun panCamera(dxPx: Float, dyPx: Float) = impl?.panCamera(dxPx, dyPx)

    fun orbitCamera(yawPx: Float, pitchPx: Float) = impl?.orbitCamera(yawPx, pitchPx)

    fun resetCamera() = impl?.resetCamera()

    fun getCameraLookAt(): Triple<FloatArray, FloatArray, FloatArray>? =
        impl?.cameraLookAt()

    /**
     * 滑向预设 [shot] 并锚定 [target]（null = 当前聚焦演员）——P0 的运镜
     * 目标参数化：特写可以点名跟 A 还是跟 B。
     */
    fun setCameraShot(shot: CameraShot, target: AvatarHandle? = null) {
        impl?.setCameraShot(shot, target?.runtime)
    }

    fun clearCameraShot() = impl?.clearCameraShot()

    fun getActiveCameraShot(): CameraShot? = impl?.getActiveCameraShot()

    // ── 拖拽 ─────────────────────────────────────────────────────────────

    /** 拖拽模式：触摸位移作用于聚焦演员（[AvatarHandle.focus] 切换对象）。 */
    fun setDragMode(enabled: Boolean) {
        val stage = impl ?: return
        stage.isDragMode = enabled
    }

    fun isDragMode(): Boolean = impl?.isDragMode ?: false

    // ── 截帧 ─────────────────────────────────────────────────────────────

    /** 下一帧渲染完成回调 Bitmap（主线程）；渲染未启动 = false。 */
    fun captureFrame(onCaptured: (Bitmap?) -> Unit): Boolean {
        val stage = impl ?: return false
        return stage.captureNextFrame(onCaptured)
    }

    // ── 视图挂载 / 生命周期 ──────────────────────────────────────────────

    /** [AvatarStageView] 工厂里调用：创建引擎并挂到该 SurfaceView。 */
    internal fun attachToView(view: SurfaceView) {
        if (impl != null) detachFromView()
        implView = view
        val stage = Stage(view, config)
        impl = stage
        stage.startup()
        // fps 回调在这里接（Stage 每帧回调 → 门面 StateFlow）
        stage.onFpsUpdated = { value -> _fps.value = value }
        _attached.value = true
    }

    internal fun detachFromView() {
        impl?.onFpsUpdated = null
        impl?.onDestroy()
        impl = null
        implView = null
        _fps.value = 0
        _attached.value = false
    }

    override fun onResume(owner: LifecycleOwner) {
        impl?.onResume()
    }

    override fun onPause(owner: LifecycleOwner) {
        impl?.onPause()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
        detachFromView()
    }
}
