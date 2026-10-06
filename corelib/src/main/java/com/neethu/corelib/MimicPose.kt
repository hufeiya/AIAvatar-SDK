package com.neethu.corelib

/**
 * 一帧「模仿我」姿态目标（app 相机车道解算、经镜像映射后的**虚拟人世界系**方向集），
 * 经 [AvatarController.setMimicPose] 原子换手喂给渲染端的 `VrmPoseMimicEngine`。
 *
 * 所有方向都是**单位向量、avatar 世界系**（+X 屏幕右、+Y 上、+Z 朝观察者），
 * 且镜像语义已在 app 侧烘进数据：`leftUpperArm` 等字段名指**虚拟人**的左/右，
 * 来自用户哪只手由 app 的 mirror 常量决定（docs/mimic-skill-feasibility.md §5）。
 *
 * 方向字段允许为 null = 该部位本帧不可用（关键点可见性不足/骨段塌缩）——引擎对
 * null 部位保持上一目标（短暂遮挡=定格姿势，不是弹回待机）。
 *
 * [timestampMs] 用 SystemClock.elapsedRealtime() 时钟；引擎超过 `HOLD_MS`（600ms）
 * 收不到新帧就交还动画（出画/切后摄/模式退出全是这条路自愈，渲染端不依赖技能层）。
 *
 * [visible]=false 表示「人还在但质量不够」的**保活 ping**：刷新新鲜度但不更新目标
 * （姿势定格）；整帧缺失（null）才走过期还原。
 */
class MimicPose(
    val timestampMs: Long,
    /** 用户面部朝向（镜像后，P2=FaceLandmarker 矩阵精解；null = 不驱动头/颈）。 */
    val headForward: FloatArray?,
    /** 虚拟人左臂（镜像后=用户右臂）大臂方向（肩→肘）；null = 保持。 */
    val leftUpperArm: FloatArray?,
    /** 虚拟人左臂小臂方向（肘→腕）；null = 保持。 */
    val leftLowerArm: FloatArray?,
    /** 虚拟人右臂（镜像后=用户左臂）大臂方向；null = 保持。 */
    val rightUpperArm: FloatArray?,
    /** 虚拟人右臂小臂方向；null = 保持。 */
    val rightLowerArm: FloatArray?,
    /** 躯干轴（髋中点→肩中点，镜像后；静止≈(0,1,0)）；null = 躯干不驱（P2）。 */
    val torsoAxis: FloatArray?,
    /** 肩线（虚拟人左肩→右肩，镜像后；静止≈(1,0,0)）；null = 肩/转体不驱（P2）。 */
    val shoulderLine: FloatArray?,
    /** false = 人形关键点质量不足（保活 ping，只续新鲜度不更新目标）。 */
    val visible: Boolean,
)
