package com.neethu.aiavatar_sdk.video

import com.neethu.corelib.MimicPose
import com.neethu.orchestrator.face.OneEuroFilter
import kotlin.math.sqrt

/** MediaPipe 世界关键点的最小投影（与 MediaPipe 类型解耦，JVM 单测友好）。 */
data class PLandmark(val x: Float, val y: Float, val z: Float, val visibility: Float)

/**
 * 「模仿我」方向解算纯函数层（docs/mimic-skill-feasibility.md §4/§5）：
 * MediaPipe 世界关键点 → **虚拟人世界系**的骨段方向集（[MimicPose]），
 * 镜像语义在此烘进数据。JVM 单测主场——合成关键点已知答案锁死全部符号。
 *
 * ## 坐标系推导（真机实证定稿，2026-10-06 用户两轮反馈修订；标定协议见可行性文档 §5.2）
 *
 * - MediaPipe **Pose 世界系**（真机实证）：x+ = 画面右 = 用户左、y+ = 画面下、
 *   **z+ = 朝向相机**。⚠ z 轴与 normalized landmarks 文档的"z 越小越近"相反——
 *   首版按"z+ 远离相机"实现，冠状面动作（抬臂）全对而深度动作全反（用户报告
 *   「手放胸前虚拟人的手还在很远处」= 用户肘向前、虚拟人肘向后），真机归因后
 *   z 语义翻转。x/y 两轴由 P1 真机验收（镜像换侧正确）锁死。
 * - **FaceLandmarker 矩阵系**（真机实证，与 Pose 系**不同构**）：x+ = 画面左、
 *   y+ = 上、z+ = 远离相机（右手系）——x、y 与 Pose 系恰好相反。判定依据：
 *   lookhere 真机标定锚点（yaw正=用户转左、pitch正=低头）唯二筛选出
 *   (x左,y上,z远) 与 (x右,y下,z远) 两解，后者无法产生用户报告的「我向左转头
 *   虚拟人向右转」而被排除（详见 [headForwardFromMatrix] / [matrixFrame] doc）。
 *   两套转换必须分开，共用一个 mirrorFrame 会把矩阵的 x/y 双双弄反。
 * - 虚拟人（avatar）世界系：X+ = 屏幕右（观察者视角）、Y+ = 上、Z+ = 朝观察者。
 * - 前摄分析帧不镜像（`UserCameraTracker.rotatedUpright` 只旋转）：画面右 =
 *   用户自己的左侧（面对面效应）。
 *
 * **前后轴 z 恒取反（语义反射）**：用户与虚拟人面对面，"手臂向前伸"的模仿
 * 语义方向相反（用户指手机=−Z_A，虚拟人指用户=+Z_A）——两种玩法都做这次
 * 镜面反射；区别只在左右轴：
 *
 * ```
 * mirror（默认）: Pose 系 (−x,−y,+z) / 矩阵系 (+x,+y,−z)，用户左 ←→ 虚拟人右（换侧）
 * puppet（人偶） : Pose 系 (+x,−y,+z) / 矩阵系 (−x,+y,−z)，同侧跟随
 * ```
 *
 * 已知答案自检（合成 T-pose）：用户左臂水平指向他自己的左 = (+1,0,0)_mp →
 * (−1,0,0)_A = 虚拟人右臂 T-pose 的自然指向 ✓；抬左臂 (0,−1,0)_mp → (0,1,0)_A ✓。
 * 真机若某轴仍反：改 [mirrorFrame] / [matrixFrame] 其中之一 + 单测，不动逻辑。
 *
 * P2 追加：躯干轴（髋中点→肩中点）与肩线（镜像换侧后的 虚拟人左肩→右肩）
 * 同走 mirrorFrame（线性映射，方向差=逐点差）；头部朝向可由 FaceLandmarker
 * 的变换矩阵精解（[headForwardFromMatrix]，穿插车道 6.25Hz），鼻-耳估算作
 * 降级兜底。
 */
object PoseMimicMath {

    // MediaPipe pose 关键点通用编号（P1 只消费上半身 0-16）
    const val NOSE = 0
    const val LEFT_EYE = 2
    const val RIGHT_EYE = 5
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24

    /** 关键点可见性门槛：低于此值的部位本帧不更新（引擎保持上一目标）。 */
    const val MIN_VISIBILITY = 0.5f

    /**
     * FaceLandmarker 规范脸空间里的面部朝向（±Z 轴）。**已真机锁定为 −1**
     * （2026-10-06：rest 态矩阵前向=朝相机=虚拟人面向用户；翻 +1 会变成背对，
     * 与「转头方向反」的观感截然不同，可据此区分两类故障）。
     */
    const val FACE_FORWARD_LOCAL = -1f

    /** 骨段两端距离低于此值（米）视为关键点塌缩，该段本帧不更新。 */
    const val MIN_SEGMENT_M = 0.02f

    /**
     * 世界关键点 → [MimicPose]。返回 null 仅当连人形质量 ping 都不成立
     * （关键点列表为空）；部分部位可见性不足 = 对应字段 null（引擎保持）。
     * 全部部位失效时返回 visible=false 的保活 ping（只续新鲜度不更新目标）。
     */
    fun mimicPoseFromLandmarks(
        pts: List<PLandmark>,
        timestampMs: Long,
        mirror: Boolean = true,
    ): MimicPose? {
        if (pts.size <= RIGHT_WRIST) return null
        fun at(i: Int): PLandmark? = pts.getOrNull(i)

        val head = headForward(at(NOSE), at(LEFT_EAR), at(RIGHT_EAR), mirror)
        val lUpper = segment(at(LEFT_SHOULDER), at(LEFT_ELBOW), mirror)   // 用户左→虚拟人右
        val lLower = segment(at(LEFT_ELBOW), at(LEFT_WRIST), mirror)
        val rUpper = segment(at(RIGHT_SHOULDER), at(RIGHT_ELBOW), mirror) // 用户右→虚拟人左
        val rLower = segment(at(RIGHT_ELBOW), at(RIGHT_WRIST), mirror)
        val torso = torsoAxes(at(LEFT_SHOULDER), at(RIGHT_SHOULDER), at(LEFT_HIP), at(RIGHT_HIP), mirror)

        val anySignal = head != null || lUpper != null || lLower != null ||
            rUpper != null || rLower != null || torso != null
        // 镜像换侧：用户左臂数据填虚拟人 right 字段（MimicPose 字段名=虚拟人侧）
        return if (mirror) {
            MimicPose(timestampMs, head, rUpper, rLower, lUpper, lLower, torso?.axis, torso?.line, anySignal)
        } else {
            MimicPose(timestampMs, head, lUpper, lLower, rUpper, rLower, torso?.axis, torso?.line, anySignal)
        }
    }

    /**
     * FaceLandmarker 变换矩阵（列主序 4×4）→ 头部朝向（avatar 系单位向量）。
     * forward_mp = R·[FACE_FORWARD_LOCAL]；该常量是**标定旋钮**——已真机锁定
     * 为 −1（rest 朝向：矩阵 rest 态虚拟人面向用户，翻 +1 会变成背对）。
     *
     * ⚠ 矩阵系与 Pose 世界系**不同构**（真机实证 2026-10-06，见 [matrixFrame]），
     * 走 [matrixFrame] 专用转换——首版误用 [mirrorFrame] 把 x/y 双双弄反
     * （用户报告「我向左转头，虚拟人向右转头了」）。
     */
    fun headForwardFromMatrix(m: FloatArray, mirror: Boolean = true): FloatArray? {
        if (m.size < 12) return null
        // R·(0,0,fz) = fz·col2 = fz·(m[8], m[9], m[10])
        val fz = FACE_FORWARD_LOCAL
        return matrixFrame(fz * m[8], fz * m[9], fz * m[10], mirror)
    }

    /**
     * **FaceLandmarker 矩阵系** → avatar 世界系的单位方向（与 [mirrorFrame]
     * 的 Pose 世界系转换分开——两个数据源的坐标系不同构，共用会把头转反）。
     *
     * 矩阵系约定（真机实证）：x+ = 画面左、y+ = 上、z+ = 远离相机。判定依据 =
     * lookhere 真机标定锚点（yaw正=用户转左、pitch正=低头）唯二允许
     * (x左,y上,z远) 与 (x右,y下,z远) 两解；后者（S1）下 mimic 头不可能反
     * （rest 朝向与转向语义绑定），与用户实测「我向左转头虚拟人向右转」矛盾
     * 被排除。纯帧转换 = `(+x, +y, +z)`（x左=用户右=+X_A、y上=Y上、z远=朝
     * 用户=+Z_A），叠加语义 z 反射后：
     *
     * ```
     * mirror = (+x, +y, −z)   puppet（人偶） = (−x, +y, −z)
     * ```
     *
     * （x 取反的角色与 [mirrorFrame] 相反：两空间 x 轴指向相反。）
     */
    private fun matrixFrame(x: Float, y: Float, z: Float, mirror: Boolean): FloatArray {
        val v = if (mirror) floatArrayOf(x, y, -z) else floatArrayOf(-x, y, -z)
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-6f) return v
        v[0] /= len; v[1] /= len; v[2] /= len
        // 归一化 −0.0 → +0.0（同 mirrorFrame：float 位比较的消费者会假失败）
        for (i in 0..2) v[i] += 0f
        return v
    }

    /** 用更精确的头部朝向（FaceLandmarker 矩阵）替换解算结果里的头部字段。 */
    fun withHeadOverride(pose: MimicPose, headForward: FloatArray?): MimicPose =
        MimicPose(
            pose.timestampMs, headForward,
            pose.leftUpperArm, pose.leftLowerArm,
            pose.rightUpperArm, pose.rightLowerArm,
            pose.torsoAxis, pose.shoulderLine, pose.visible,
        )

    /**
     * 躯干轴（髋中点→肩中点）与肩线（虚拟人左肩→虚拟人右肩）。肩线方向 =
     * 「虚拟人左肩源点 − 虚拟人右肩源点」的镜像差（mirrorFrame 线性→逐点差）：
     * 镜像模式下虚拟人左肩=用户右肩（下标 12），静止时 ≈(1,0,0)=虚拟人左侧 ✓。
     * 双肩+双髋任一不可见 → null（躯干通道本帧保持）。
     */
    private fun torsoAxes(
        shoulderL: PLandmark?, shoulderR: PLandmark?,
        hipL: PLandmark?, hipR: PLandmark?,
        mirror: Boolean,
    ): TorsoFrame? {
        if (shoulderL == null || shoulderR == null || hipL == null || hipR == null) return null
        if (shoulderL.visibility < MIN_VISIBILITY || shoulderR.visibility < MIN_VISIBILITY ||
            hipL.visibility < MIN_VISIBILITY || hipR.visibility < MIN_VISIBILITY
        ) {
            return null
        }
        val axis = mirrorFrame(
            (shoulderL.x + shoulderR.x) / 2f - (hipL.x + hipR.x) / 2f,
            (shoulderL.y + shoulderR.y) / 2f - (hipL.y + hipR.y) / 2f,
            (shoulderL.z + shoulderR.z) / 2f - (hipL.z + hipR.z) / 2f,
            mirror,
        )
        val avatarLeft = if (mirror) shoulderR else shoulderL
        val avatarRight = if (mirror) shoulderL else shoulderR
        val line = mirrorFrame(avatarLeft.x - avatarRight.x, avatarLeft.y - avatarRight.y, avatarLeft.z - avatarRight.z, mirror)
        return TorsoFrame(axis, line)
    }

    /** 躯干轴 + 肩线的一帧组合（都是镜像后的 avatar 系单位向量）。 */
    data class TorsoFrame(val axis: FloatArray, val line: FloatArray)

    /** 面部朝向 = 鼻尖 − 双耳中点（双耳都可见才可靠；捏着鼻子建模的人除外）。 */
    private fun headForward(nose: PLandmark?, earL: PLandmark?, earR: PLandmark?, mirror: Boolean): FloatArray? {
        if (nose == null || earL == null || earR == null) return null
        if (nose.visibility < MIN_VISIBILITY || earL.visibility < MIN_VISIBILITY ||
            earR.visibility < MIN_VISIBILITY
        ) {
            return null
        }
        val mx = (earL.x + earR.x) / 2f
        val my = (earL.y + earR.y) / 2f
        val mz = (earL.z + earR.z) / 2f
        return mirrorFrame(nose.x - mx, nose.y - my, nose.z - mz, mirror)
    }

    /** 骨段方向（a→b），两端可见性 + 塌缩保护 + 帧转换。 */
    private fun segment(a: PLandmark?, b: PLandmark?, mirror: Boolean): FloatArray? {
        if (a == null || b == null) return null
        if (a.visibility < MIN_VISIBILITY || b.visibility < MIN_VISIBILITY) return null
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < MIN_SEGMENT_M) return null
        return mirrorFrame(dx / len, dy / len, dz / len, mirror)
    }

    /**
     * **Pose 世界系** → avatar 世界系的单位方向。
     *
     * 纯帧转换（x右/y下/z朝相机 → X右/Y上/Z朝用户）= `(−x, −y, −z)`；叠加
     * 面对面模仿的语义 z 反射（用户前伸↔虚拟人前伸）后合并为：
     *
     * ```
     * mirror = (−x, −y, +z)   puppet（人偶） = (+x, −y, +z)
     * ```
     *
     * ⚠ z 语义真机实证（2026-10-06）：MP Pose 世界关键点 **z+ = 朝向相机**，
     * 与 normalized landmarks 文档"z 越小越近"相反。首版按"z+ 远离相机"实现，
     * 抬臂等冠状面动作全对、手放胸前等深度动作全反（用户肘向前、虚拟人肘
     * 向后，用户报告「虚拟人的手还在很远处」）——z 号是那次归因的修正；x/y
     * 由 P1 真机验收（镜像换侧正确）锁死。人偶分支的 y 原为 +y（未随 mirror
     * 同步），一并修正为与镜像相同的 −y（y 下→Y 上与玩法无关）。
     */
    private fun mirrorFrame(x: Float, y: Float, z: Float, mirror: Boolean): FloatArray {
        val v = if (mirror) floatArrayOf(-x, -y, z) else floatArrayOf(x, -y, z)
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-6f) return v
        v[0] /= len; v[1] /= len; v[2] /= len
        // 归一化 −0.0 → +0.0（float 位比较的消费者——单测 contentEquals/序列化
        // ——会把 −0.0 判成不等于 0.0，先归一掉这个坑）
        for (i in 0..2) v[i] += 0f
        return v
    }

    /**
     * 合成调试姿态（`ai_cmd mimic_force <preset>`）：在 MP 坐标里搭一个用户
     * 骨架然后走**同一解算路径**（与真实检测共享符号表，免相机 A/B 的铁证入口）。
     * 支持 tpose / left_up / right_up / both_up / forward / off（off=null）。
     */
    fun forcedPreset(name: String?, timestampMs: Long, mirror: Boolean = true): MimicPose? {
        if (name == null || name == "off" || name == "none") return null
        val pts = MutableList(33) { PLandmark(0f, 0f, 0f, 0f) }
        fun put(i: Int, x: Float, y: Float, z: Float, vis: Float = 0.9f) {
            pts[i] = PLandmark(x, y, z, vis)
        }
        // 头：y 越小越高（MP y 向下）；z 越大越近相机（鼻尖略近；Pose 系
        // z+ = 朝向相机，真机实证见 mirrorFrame doc）
        put(NOSE, 0f, -0.62f, 0.08f)
        put(LEFT_EYE, 0.03f, -0.65f, 0.08f)
        put(RIGHT_EYE, -0.03f, -0.65f, 0.08f)
        put(LEFT_EAR, 0.08f, -0.62f, 0f)
        put(RIGHT_EAR, -0.08f, -0.62f, 0f)
        put(LEFT_SHOULDER, 0.2f, -0.35f, 0f)
        put(RIGHT_SHOULDER, -0.2f, -0.35f, 0f)
        put(LEFT_HIP, 0.1f, 0.05f, 0f)
        put(RIGHT_HIP, -0.1f, 0.05f, 0f)

        /** 躯干预设：把双肩摆到偏移位置（髋固定），arms 从肩自然下垂。 */
        fun torso(shiftL: Triple<Float, Float, Float>, shiftR: Triple<Float, Float, Float>) {
            put(LEFT_SHOULDER, shiftL.first, shiftL.second, shiftL.third)
            put(RIGHT_SHOULDER, shiftR.first, shiftR.second, shiftR.third)
            // 手臂随肩自然下垂（肘/腕在肩正下方）
            put(LEFT_ELBOW, shiftL.first + 0.02f, shiftL.second + 0.25f, shiftL.third)
            put(LEFT_WRIST, shiftL.first + 0.04f, shiftL.second + 0.47f, shiftL.third)
            put(RIGHT_ELBOW, shiftR.first - 0.02f, shiftR.second + 0.25f, shiftR.third)
            put(RIGHT_WRIST, shiftR.first - 0.04f, shiftR.second + 0.47f, shiftR.third)
        }

        /** side=+1 用户左臂（+x_mp 侧）/ -1 用户右臂；肘/腕坐标按侧镜像。 */
        fun arm(side: Int, elbowX: Float, elbowY: Float, elbowZ: Float, wristX: Float, wristY: Float, wristZ: Float) {
            val sx = side.toFloat()
            put(if (side > 0) LEFT_ELBOW else RIGHT_ELBOW, elbowX * sx, elbowY, elbowZ)
            put(if (side > 0) LEFT_WRIST else RIGHT_WRIST, wristX * sx, wristY, wristZ)
        }

        // 预设按「用户左臂」语义写（x 为正=用户左侧），右臂统一 -1 镜像
        when (name) {
            "tpose" -> {
                arm(+1, 0.45f, -0.35f, 0f, 0.70f, -0.35f, 0f)
                arm(-1, 0.45f, -0.35f, 0f, 0.70f, -0.35f, 0f)
            }
            "left_up" -> {
                arm(+1, 0.22f, -0.62f, 0f, 0.26f, -0.88f, 0f)
                arm(-1, 0.22f, -0.10f, 0f, 0.24f, 0.12f, 0f)
            }
            "right_up" -> {
                arm(+1, 0.22f, -0.10f, 0f, 0.24f, 0.12f, 0f)
                arm(-1, 0.22f, -0.62f, 0f, 0.26f, -0.88f, 0f)
            }
            "both_up" -> {
                arm(+1, 0.22f, -0.62f, 0f, 0.26f, -0.88f, 0f)
                arm(-1, 0.22f, -0.62f, 0f, 0.26f, -0.88f, 0f)
            }
            "forward" -> {
                arm(+1, 0.2f, -0.35f, 0.25f, 0.2f, -0.35f, 0.45f)
                arm(-1, 0.2f, -0.35f, 0.25f, 0.2f, -0.35f, 0.45f)
            }
            // P2 躯干预设（观察躯干/锁骨通道的方向语义）：用户向自己左侧倾 /
            // 右侧倾 / 前倾（鞠躬）/ 向左转身（左肩前移；前移=朝相机=+z）
            "lean_left" -> torso(Triple(0.35f, -0.32f, 0f), Triple(-0.05f, -0.42f, 0f))
            "lean_right" -> torso(Triple(0.05f, -0.42f, 0f), Triple(-0.35f, -0.32f, 0f))
            "bow" -> torso(Triple(0.2f, -0.28f, 0.22f), Triple(-0.2f, -0.28f, 0.22f))
            "turn_left" -> torso(Triple(0.1f, -0.35f, 0.2f), Triple(-0.25f, -0.35f, -0.1f))
            else -> return null
        }
        return mimicPoseFromLandmarks(pts, timestampMs, mirror)
    }

    /** 合成姿态的合法清单（ai_cmd 报错文案用）。 */
    val FORCED_PRESETS = listOf(
        "tpose", "left_up", "right_up", "both_up", "forward",
        "lean_left", "lean_right", "bow", "turn_left", "off",
    )
}

/**
 * 方向目标滤波（app 车道侧，喂引擎前）：逐分量 One-Euro（自适应低通：慢动作
 * 重平滑、快动作近零滞后）+ **z 轴阻滞**（单目深度的噪声主轴，输出 z 向上一帧
 * 收敛）+ 死区（目标变化 < [deadzoneRad] 维持上一输出，压微抖）。
 *
 * 滤的是**解算后的方向向量**（每骨一条）而非原始关键点——直接平滑引擎消费的
 * 东西，且方向数量固定（5 条）远少于 33 点。引擎侧另有轻度指数趋近（时间相干
 * 补采样间隙），两层都是调参入口。
 */
class MimicDirectionFilter(
    private val minCutoff: Float = 1.2f,
    private val beta: Float = 0.4f,
    private val deadzoneRad: Float = 0.02f,
    /** 深度轴（z）变化保留率：1=不阻滞，0.7=z 变化打七折（噪声主轴，先牺牲它）。 */
    private val zPass: Float = 0.7f,
) {

    private class VecFilter(minCutoff: Float, beta: Float) {
        val fx = OneEuroFilter(minCutoff, beta)
        val fy = OneEuroFilter(minCutoff, beta)
        val fz = OneEuroFilter(minCutoff, beta)
        var lastOut: FloatArray? = null
    }

    private val head = VecFilter(minCutoff, beta)
    private val lUpper = VecFilter(minCutoff, beta)
    private val lLower = VecFilter(minCutoff, beta)
    private val rUpper = VecFilter(minCutoff, beta)
    private val rLower = VecFilter(minCutoff, beta)
    private val torso = VecFilter(minCutoff, beta)
    private val shoulderLine = VecFilter(minCutoff, beta)
    private var lastTs = 0L

    fun filter(pose: MimicPose): MimicPose {
        val dt = if (lastTs == 0L) 0f else (pose.timestampMs - lastTs) / 1000f
        lastTs = pose.timestampMs
        if (!pose.visible) return pose // ping 帧原样透传（引擎侧保持目标）
        return MimicPose(
            timestampMs = pose.timestampMs,
            headForward = pose.headForward?.let { head.filterVec(it, dt) },
            leftUpperArm = pose.leftUpperArm?.let { lUpper.filterVec(it, dt) },
            leftLowerArm = pose.leftLowerArm?.let { lLower.filterVec(it, dt) },
            rightUpperArm = pose.rightUpperArm?.let { rUpper.filterVec(it, dt) },
            rightLowerArm = pose.rightLowerArm?.let { rLower.filterVec(it, dt) },
            torsoAxis = pose.torsoAxis?.let { torso.filterVec(it, dt) },
            shoulderLine = pose.shoulderLine?.let { shoulderLine.filterVec(it, dt) },
            visible = true,
        )
    }

    fun reset() {
        listOf(head, lUpper, lLower, rUpper, rLower, torso, shoulderLine).forEach {
            it.fx.reset(); it.fy.reset(); it.fz.reset(); it.lastOut = null
        }
        lastTs = 0L
    }

    private fun VecFilter.filterVec(input: FloatArray, dt: Float): FloatArray {
        var x = fx.filter(input[0], dt)
        var y = fy.filter(input[1], dt)
        var z = fz.filter(input[2], dt)
        val last = lastOut
        if (last != null) {
            // z 阻滞：深度轴输出向上帧收敛（单目 z 噪声主轴）
            z = last[2] + (z - last[2]) * zPass
            // 死区：合变化角低于阈值 → 维持上一输出（含 z 阻滞后的量）
            val dx = x - last[0]; val dy = y - last[1]; val dz = z - last[2]
            if (sqrt(dx * dx + dy * dy + dz * dz) < deadzoneRad) return last.copyOf()
        }
        val len = sqrt(x * x + y * y + z * z)
        if (len < 1e-6f) return input.copyOf()
        val out = floatArrayOf(x / len, y / len, z / len)
        lastOut = out.copyOf()
        return out
    }
}
