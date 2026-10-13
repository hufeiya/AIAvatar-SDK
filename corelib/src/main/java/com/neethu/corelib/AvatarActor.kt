package com.neethu.corelib

import kotlinx.coroutines.flow.SharedFlow

/**
 * 舞台上「一个演员」的操控缝（docs/multi-avatar-feasibility.md P1）：per-actor
 * 渲染/表情/视线/走动控制的最小公共面，[AvatarController]（单虚拟人路径）与
 * [com.neethu.corelib.stage.AvatarHandle]（多虚拟人舞台路径）共同实现——
 * orchestrator 的 FaceDriver/GestureDriver/LocomotionDriver/AvatarSession
 * 只吃这个接口，同一套会话装配对两种形态都成立。
 *
 * 面内成员来自既有编排层的真实调用清单，刻意保持最小：场景、灯光、渲染
 * 设置、截帧等舞台级共享资源不在此处（单人形态走 [AvatarController] 自有
 * API；舞台形态走 [com.neethu.corelib.stage.AvatarStage]）。
 */
interface AvatarActor {

    // ── 表情 ─────────────────────────────────────────────────────────────

    fun setExpression(name: String, weight: Float = 1f)

    fun clearAllExpressions()

    fun getAvailableExpressions(): List<String>

    fun setExpressionTransitionDuration(durationMs: Long)

    // ── VRMA 动画 ────────────────────────────────────────────────────────

    fun loadVrmaAnimation(assetPath: String): Boolean

    fun loadVrmaAnimationFromFile(path: String): Boolean

    fun playVrmaAnimation(loop: Boolean = true, speed: Float = 1f, fadeSeconds: Float = 0f)

    fun stopVrmaAnimation(fadeSeconds: Float = 0f)

    fun setVrmaIdleAnimation(assetPath: String): Boolean

    fun setVrmaIdleAnimationFromFile(path: String): Boolean

    fun clearVrmaIdleAnimation()

    // ── 视线 ─────────────────────────────────────────────────────────────

    fun setLookAtTarget(x: Float, y: Float, z: Float)

    fun clearLookAtTarget()

    /** 相机 eye/target/up（FaceDriver 的 CAMERA 注视模式取 eye = 用户方向）。 */
    fun getCameraLookAt(): Triple<FloatArray, FloatArray, FloatArray>?

    // ── 呼吸 ─────────────────────────────────────────────────────────────

    fun setBreathEnabled(enabled: Boolean)

    fun setBreathAmplitudeScale(scale: Float)

    fun setBreathRateBpm(bpm: Float)

    /** 说话时呼吸幅度放大（FaceDriver 在播放开始/结束时切换）。 */
    fun setBreathSpeaking(speaking: Boolean)

    // ── 走动 ─────────────────────────────────────────────────────────────

    fun startWalk(targetX: Float, targetZ: Float, speed: Float = 1.0f): WalkStartResult

    fun stopWalk()

    fun getWalkState(): WalkSnapshot?

    /** 每次走动结束一条（arrived/cancelled），AvatarSession 转成事件与续腿。 */
    val walkFinished: SharedFlow<WalkFinished>

    // ── 机位 ─────────────────────────────────────────────────────────────

    /** 当前生效的预设机位（null = 自由视角）；每轮请求的视角行数据源。 */
    fun getActiveCameraShot(): CameraShot?

    /**
     * 切到预设机位。单人形态=切自己的相机；舞台形态=切**舞台**相机并把
     * 本演员作为锚定目标（多演员共享一台相机的仲裁语义：模型只能给自己
     * 点机位，抢不到别人的）。
     */
    fun setCameraShot(shot: CameraShot)
}
