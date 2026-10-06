package com.neethu.corelib

/**
 * Last-frame mimic state (diagnostics surface, `ai_cmd mimic_status`), or the
 * fields of a detached engine. Mirrors [LookAtInfo]/[BreathInfo].
 */
class MimicInfo(
    /** 引擎正在驱动骨骼（有新鲜姿态且未在还原）。 */
    val engaged: Boolean,
    /** 退场缓动进行中（从模仿姿势滑回动画/rest 姿势）。 */
    val restoring: Boolean,
    /** 最近一帧 [MimicPose] 的年龄（ms）；从未收到 = -1。 */
    val poseAgeMs: Long,
    /** 平滑后的头部偏航（度，相对模型当前面朝方向的附加转角）。 */
    val headYawDeg: Float,
    /** 平滑后的头部俯仰（度）。 */
    val headPitchDeg: Float,
    /** 平滑后的躯干前倾/后仰（度，正=朝观察者倾；P2 躯干通道）。 */
    val torsoPitchDeg: Float = 0f,
    /** 平滑后的躯干侧倾（度，正=朝虚拟人左侧倾）。 */
    val torsoRollDeg: Float = 0f,
    /** 平滑后的躯干转身（度，来自肩线水平旋转）。 */
    val torsoYawDeg: Float = 0f,
)
