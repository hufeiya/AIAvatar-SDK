package com.neethu.corelib.stage

import com.neethu.corelib.AvatarActor
import com.neethu.corelib.AvatarState
import com.neethu.corelib.BreathInfo
import com.neethu.corelib.CameraShot
import com.neethu.corelib.LookAtInfo
import com.neethu.corelib.MimicInfo
import com.neethu.corelib.MimicPose
import com.neethu.corelib.RenderMode
import com.neethu.corelib.WalkFinished
import com.neethu.corelib.WalkSnapshot
import com.neethu.corelib.WalkStartResult
import com.neethu.corelib.internal.WalkGaitKind
import com.neethu.corelib.stage.internal.AvatarRuntime
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 舞台上的一个虚拟人句柄（[AvatarStage.loadAvatar] 的产物）。
 *
 * 实现 [com.neethu.corelib.AvatarActor]：API 面与单人
 * [com.neethu.corelib.AvatarController] 的 per-model 部分一致（表情/VRMA/
 * 视线/呼吸/弹簧骨/走动/拖拽），差异只有两点：场景与运镜是舞台级共享资源
 * （走 [AvatarStage]，机位写入经 [setCameraShot] 代理到舞台并把本演员锚定
 * 为目标）；多出 [spawn]/[spec]/[focus] 与「看对方」这类多演员便捷方法。
 */
class AvatarHandle internal constructor(
    /** 舞台内唯一 ID（"A"/"B"/…，诊断与 adb 调试通道的目标选择）。 */
    val id: String,
    val spec: AvatarSpec,
    internal val runtime: AvatarRuntime,
    private val stage: AvatarStage,
) : AvatarActor {
    private val _state = MutableStateFlow<AvatarState>(AvatarState.Idle)

    /** 加载状态（Idle → Loading → Ready/Error），Compose 直接 collect。 */
    val state: StateFlow<AvatarState> = _state.asStateFlow()

    internal fun setState(state: AvatarState) {
        _state.value = state
    }

    private val _detectedRenderMode = MutableStateFlow<RenderMode?>(null)

    /** fresh 加载按材质自动检测出的渲染模式（null = 尚未检测）。 */
    val detectedRenderMode: StateFlow<RenderMode?> = _detectedRenderMode.asStateFlow()

    private val _walkFinished = MutableSharedFlow<WalkFinished>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** 每次走动结束一条（arrived / cancelled）。 */
    override val walkFinished: SharedFlow<WalkFinished> = _walkFinished.asSharedFlow()

    init {
        runtime.onRenderModeDetected = { mode ->
            _detectedRenderMode.value = mode
        }
        runtime.onWalkFinished = { finished ->
            _walkFinished.tryEmit(finished)
        }
    }

    /** 是否已就绪渲染。 */
    val isReady: Boolean get() = _state.value is AvatarState.Ready

    // ── 舞台交互 ─────────────────────────────────────────────────────────

    /** 设为聚焦演员（拖拽命中的对象；新运镜 shot 的默认目标）。 */
    fun focus() {
        stage.impl?.let { it.focusedRuntime = runtime }
    }

    // ── 内置动画 ─────────────────────────────────────────────────────────

    fun playAnimation(index: Int, loop: Boolean = true) = runtime.playAnimation(index, loop)

    fun stopAnimation() = runtime.stopAnimation()

    // ── 表情（blend shape / morph）──────────────────────────────────────

    override fun setExpression(name: String, weight: Float) = runtime.setExpression(name, weight)

    override fun clearAllExpressions() = runtime.clearAllExpressions()

    override fun getAvailableExpressions(): List<String> = runtime.getAvailableExpressions()

    override fun setExpressionTransitionDuration(durationMs: Long) =
        runtime.setExpressionTransitionDuration(durationMs)

    // ── VRMA ─────────────────────────────────────────────────────────────

    override fun loadVrmaAnimation(assetPath: String): Boolean = runtime.loadVrmaAnimation(assetPath)

    override fun loadVrmaAnimationFromFile(path: String): Boolean = runtime.loadVrmaAnimationFromFile(path)

    override fun playVrmaAnimation(loop: Boolean, speed: Float, fadeSeconds: Float) =
        runtime.playVrmaAnimation(loop, speed, fadeSeconds)

    override fun stopVrmaAnimation(fadeSeconds: Float) = runtime.stopVrmaAnimation(fadeSeconds)

    fun getVrmaAnimationDuration(): Float = runtime.getVrmaDuration()

    override fun setVrmaIdleAnimation(assetPath: String): Boolean = runtime.setVrmaIdleAnimation(assetPath)

    override fun setVrmaIdleAnimationFromFile(path: String): Boolean =
        runtime.setVrmaIdleAnimationFromFile(path)

    override fun clearVrmaIdleAnimation() = runtime.clearVrmaIdleAnimation()

    // ── 视线 ─────────────────────────────────────────────────────────────

    /** 看世界坐标点（听者看说话者的头部、看镜头等都走这里）。 */
    override fun setLookAtTarget(x: Float, y: Float, z: Float) = runtime.setLookAtTarget(x, y, z)

    /** 停止注视，头/眼回到动画姿势。 */
    override fun clearLookAtTarget() = runtime.clearLookAtTarget()

    /** 看舞台相机（= 看用户），多演员「发言完毕回看镜头」的便捷入口。 */
    fun lookAtCamera() {
        val (eye, _, _) = stage.impl?.cameraLookAt() ?: return
        runtime.setLookAtTarget(eye[0], eye[1], eye[2])
    }

    /** 看另一个虚拟人的头部（倾听感的最大来源，P1 导演注视接力的现成抓手）。 */
    fun lookAt(other: AvatarHandle) {
        val p = other.headWorldPosition() ?: return
        runtime.setLookAtTarget(p[0], p[1], p[2])
    }

    fun getLookAtInfo(): LookAtInfo? = runtime.lookAtInfo()

    override fun getCameraLookAt(): Triple<FloatArray, FloatArray, FloatArray>? =
        stage.impl?.cameraLookAt()

    /** 头部世界坐标（运镜/注视目标）；模型未就绪 = null。 */
    fun headWorldPosition(): FloatArray? =
        if (runtime.asset != null) runtime.headWorldPosition() else null

    // ── 模仿 ─────────────────────────────────────────────────────────────

    fun setMimicPose(pose: MimicPose?) = runtime.setMimicPose(pose)

    fun clearMimicPose() = runtime.setMimicPose(null)

    fun getMimicInfo(): MimicInfo? = runtime.mimicInfo()

    // ── 呼吸 / 弹簧骨 / 剔除 ─────────────────────────────────────────────

    override fun setBreathEnabled(enabled: Boolean) = runtime.setBreathEnabled(enabled)

    override fun setBreathAmplitudeScale(scale: Float) = runtime.setBreathAmplitudeScale(scale)

    override fun setBreathRateBpm(bpm: Float) = runtime.setBreathRateBpm(bpm)

    override fun setBreathSpeaking(speaking: Boolean) = runtime.setBreathSpeaking(speaking)

    fun getBreathInfo(): BreathInfo? = runtime.breathInfo()

    fun setSpringBoneEnabled(enabled: Boolean) = runtime.setSpringBoneEnabled(enabled)

    fun setDragMovesHips(enabled: Boolean) = runtime.setDragMovesHips(enabled)

    fun setAvatarCulling(enabled: Boolean) = runtime.setAvatarCulling(enabled)

    // ── 走动 ─────────────────────────────────────────────────────────────

    fun setWalkGait(assetPath: String, baseSpeed: Float): Boolean =
        runtime.setWalkGait(assetPath, baseSpeed, WalkGaitKind.WALK)

    fun setRunGait(assetPath: String, baseSpeed: Float = 0f): Boolean =
        runtime.setWalkGait(assetPath, baseSpeed, WalkGaitKind.RUN)

    fun setStrafeGaits(leftAssetPath: String, rightAssetPath: String, baseSpeed: Float = 0f): Boolean =
        runtime.setStrafeGaits(leftAssetPath, rightAssetPath, baseSpeed)

    fun isWalkGaitConfigured(): Boolean = runtime.hasWalkGait()

    fun setWalkFadeSeconds(seconds: Float) = runtime.setWalkFadeSeconds(seconds)

    fun getWalkFadeSeconds(): Float = runtime.getWalkFadeSeconds()

    fun setGestureOverlayEnabled(enabled: Boolean) = runtime.setGestureOverlayEnabled(enabled)

    fun isGestureOverlayEnabled(): Boolean = runtime.isGestureOverlayEnabled()

    fun setExtractionEnabled(enabled: Boolean) = runtime.setExtractionEnabled(enabled)

    fun isExtractionEnabled(): Boolean = runtime.isExtractionEnabled()

    fun setWalkTransitions(startAssetPath: String?, stopAssetPath: String?): Boolean =
        runtime.setWalkTransitions(startAssetPath, stopAssetPath)

    fun hasWalkTransitions(): Boolean = runtime.hasWalkTransitions()

    fun walkGaitsSummary(): String = runtime.walkGaitsSummary()

    override fun startWalk(targetX: Float, targetZ: Float, speed: Float): WalkStartResult =
        runtime.startWalk(targetX, targetZ, speed)

    fun startStrafe(left: Boolean, meters: Float, speed: Float = 1.0f): WalkStartResult =
        runtime.startStrafe(left, meters, speed)

    override fun stopWalk() = runtime.stopWalk()

    override fun getWalkState(): WalkSnapshot? =
        if (runtime.asset != null) runtime.walkSnapshot() else null

    // ── 程序化移动 ───────────────────────────────────────────────────────

    fun moveAvatar(dx: Float, dy: Float, dz: Float = 0f) = runtime.moveAvatar(dx, dy, dz)

    // ── 机位（舞台级共享相机的演员侧代理）────────────────────────────────

    override fun getActiveCameraShot(): CameraShot? = stage.impl?.getActiveCameraShot()

    /**
     * 演员侧机位写入：切**舞台**相机并把本演员锚定为目标（LLM `<cam:>` 标签
     * 通道的落点——多个演员各自发标签时，每次都只是「给自己点机位」，共享
     * 一台相机的仲裁天然收敛到「最后说话的人」，不会互相拉扯）。
     */
    override fun setCameraShot(shot: CameraShot) {
        stage.impl?.setCameraShot(shot, runtime)
    }
}
