package com.neethu.corelib.internal

import android.os.SystemClock
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.TransformManager
import com.neethu.corelib.MimicInfo
import com.neethu.corelib.MimicPose
import kotlin.math.abs
import kotlin.math.exp

/**
 * 「模仿我」骨骼叠加引擎（docs/mimic-skill-feasibility.md §4/§6）——第四个
 * 骨骼写入层，与视线/呼吸同挂点（动画写完局部变换之后、蒙皮传播之前），但
 * 驱动模型不同：**方向绝对驱动**（[PoseMimicSolver]），不是相对偏移叠加。
 *
 * 数据通路：app 相机车道（PoseLandmarker）解算镜像后的 [MimicPose] →
 * `AvatarController.setMimicPose`（原子换手）→ 本引擎在渲染线程逐帧消费。
 * **不经 orchestrator、不经 LLM**——模仿是感知-渲染直通，技能层只管激活/退出。
 *
 * 每帧骨骼所有权（后写者赢，见 §6.1）：
 * ```
 * idle/VRMA(全身) → hipsDrag(hips平移) → breath(脊柱/胸/肩) → lookAt(头颈偏移+眼)
 * → 本引擎(neck/head 绝对方向 + 双臂大臂/小臂) → commit+updateBoneMatrices
 * ```
 * - 头/颈被本引擎覆写后，lookAt 的偏移记账自动走"被重写→全量写"分支（双信号
 *   设计的鲁棒性），**眼睛保持注视相机**=模仿中的眼神接触。
 * - 呼吸层 P1 零骨骼重叠（大臂/小臂 ≠ 肩/锁骨）；P2 躯干接管时由调用方
 *   `setBreathEnabled(false)` 让位。
 *
 * 生命周期语义：
 *  - **一次性 VRMA 播放期间挂起**（[update] 的 oneShotActive）：`<act:>` 动作
 *    完整播完，播完回 idle 自动续上（或按新鲜度自愈还原）。
 *  - **断供自愈**：超过 [HOLD_MS] 无新鲜帧（出画/切后摄/退出视频模式）→
 *    [RESTORE_SECONDS] 缓动滑回动画姿势（idle 在播=每帧重写，rest pose=补写
 *    bind 时的 rest 局部旋转）——渲染端不依赖技能层。
 *  - **保活定格**：[MimicPose.visible]=false 的 ping 帧只续新鲜度不更新目标
 *    （用户短暂被遮挡=虚拟人定格最后的姿势，不是弹回待机）。
 *  - 引擎不自查动画状态：animationWrote / oneShotActive 由渲染循环判定传入
 *    （与 lookAt/breath 的挂点约定一致）。
 */
internal class VrmPoseMimicEngine(private val engine: Engine) {

    companion object {
        private const val TAG = "VrmMimic"

        /** 目标断供多久放弃驱动进还原：车道路径任何一环断掉的自愈窗口。 */
        const val HOLD_MS = 600L

        /** 退场缓动时长（硬 snap 回 idle 观感生硬——呼吸顿挫教训同源）。 */
        const val RESTORE_SECONDS = 0.3f

        /** 目标方向/头部角的指数趋近速率（1/s）：15 ≈ 70ms 收敛。One-Euro 已在 */
        /** app 车道滤掉噪声，这里只补 12.5Hz 采样→60fps 渲染间的时间相干。 */
        const val SMOOTH_RATE = 15f

        /** 头部自然转角限幅（同 lookAt：相对当前动画面朝方向）。 */
        private val HEAD_MAX_YAW_RAD = Math.toRadians(55.0).toFloat()
        private val HEAD_MAX_PITCH_RAD = Math.toRadians(35.0).toFloat()

        /** 颈/头分摊（和恒 1，闭环收敛后脸朝向精确等于目标；lookAt 同款）。 */
        private const val HEAD_GAIN = 0.65f
        private const val NECK_GAIN = 0.35f

        // ── 躯干（P2）：欧拉分量限幅与三骨分摊（和恒 1，顶端合成全量转角）────
        private val TORSO_MAX_YAW_RAD = Math.toRadians(40.0).toFloat()
        private val TORSO_MAX_PITCH_RAD = Math.toRadians(30.0).toFloat()
        private val TORSO_MAX_ROLL_RAD = Math.toRadians(30.0).toFloat()
        private const val SPINE_GAIN = 0.35f
        private const val CHEST_GAIN = 0.35f
        private const val UPPER_CHEST_GAIN = 0.30f

        /** 锁骨跟随肩线倾滚的增益与限幅（一肩高一肩低；观感激进就调小）。 */
        private const val CLAVICLE_GAIN = 0.5f
        private val CLAVICLE_MAX_RAD = Math.toRadians(15.0).toFloat()

        /** 目标变化小于此角（rad）跳写——静止时零写入。 */
        private const val WRITE_EPS_RAD = 0.002f

        /** 骨段两端世界距离低于此值（关键点塌缩/深度噪声）→ 该骨本帧跳写。 */
        private const val MIN_SEGMENT_M = 0.02f

        private val IDENTITY_Q = floatArrayOf(0f, 0f, 0f, 1f)
    }

    private class BoneRef(val entity: Int, val restLocalQ: FloatArray) {
        var lastWritten: FloatArray? = null
    }

    /** Phase A 读出的一条手臂链关节世界位置（写任何骨骼**之前**拍——写上臂会移动肘/腕）。 */
    private class LimbRead(
        val upperPos: FloatArray,
        val lowerPos: FloatArray,
        val handPos: FloatArray?,
    )

    /** 一条手臂链：大臂（必绑）、小臂/手关节（缺手骨=小臂不驱，优雅降级）。 */
    private class LimbChain(
        val upper: BoneRef,
        val lower: BoneRef?,
        val hand: BoneRef?,
    ) {
        var smoothUpper: FloatArray? = null
        var smoothLower: FloatArray? = null
    }

    private var head: BoneRef? = null
    private var neck: BoneRef? = null
    private var leftChain: LimbChain? = null
    private var rightChain: LimbChain? = null

    // 躯干（P2）：脊柱三段 + 双锁骨。与呼吸层骨骼重叠——mimic 激活期 app 侧
    // 会 setBreathEnabled(false) 让位；即便不关，本层写在后=赢也无累积。
    private var spine: BoneRef? = null
    private var chest: BoneRef? = null
    private var upperChest: BoneRef? = null
    private var leftShoulder: BoneRef? = null
    private var rightShoulder: BoneRef? = null

    /** 模型的脸朝向在头局部空间（bind 时从静止世界四元数反推，lookAt 同款）。 */
    private var faceLocalDir: FloatArray = floatArrayOf(0f, 0f, 1f)

    @Volatile private var incoming: MimicPose? = null

    private var smoothYaw = 0f
    private var smoothPitch = 0f
    // 躯干欧拉分量的平滑值（度→弧度，同头部标量平滑）
    private var smoothTorsoPitch = 0f
    private var smoothTorsoRoll = 0f
    private var smoothTorsoYaw = 0f
    private var smoothClavicleTilt = 0f
    private var appliedAny = false
    private var restoring = false
    private var restoreElapsed = 0f

    /** Bind humanoid bone entities (0 = missing). Must run after the VRM 0.x root flip. */
    fun bind(
        neckEntity: Int,
        headEntity: Int,
        leftUpperArmEntity: Int,
        leftLowerArmEntity: Int,
        leftHandEntity: Int,
        rightUpperArmEntity: Int,
        rightLowerArmEntity: Int,
        rightHandEntity: Int,
        spineEntity: Int = 0,
        chestEntity: Int = 0,
        upperChestEntity: Int = 0,
        leftShoulderEntity: Int = 0,
        rightShoulderEntity: Int = 0,
    ) {
        val tm = engine.transformManager
        neck = ref(tm, neckEntity)
        head = ref(tm, headEntity)
        val lUpper = ref(tm, leftUpperArmEntity)
        val lLower = ref(tm, leftLowerArmEntity)
        val lHand = ref(tm, leftHandEntity)
        val rUpper = ref(tm, rightUpperArmEntity)
        val rLower = ref(tm, rightLowerArmEntity)
        val rHand = ref(tm, rightHandEntity)
        leftChain = lUpper?.let { LimbChain(it, lLower, lHand) }
        rightChain = rUpper?.let { LimbChain(it, rLower, rHand) }
        spine = ref(tm, spineEntity)
        chest = ref(tm, chestEntity)
        upperChest = ref(tm, upperChestEntity)
        leftShoulder = ref(tm, leftShoulderEntity)
        rightShoulder = ref(tm, rightShoulderEntity)

        val h = head
        if (h == null) {
            Log.w(TAG, "no head bone — head mimicry disabled for this model")
            return
        }
        val inst = tm.getInstance(h.entity)
        if (inst != 0) {
            val headWorld = FloatArray(16)
            tm.getWorldTransform(inst, headWorld)
            val restWorldQ = GazeMath.matToQuat(headWorld)
            faceLocalDir = GazeMath.normalize3(
                GazeMath.rotateVector(GazeMath.quatInverse(restWorldQ), floatArrayOf(0f, 0f, 1f))
            )
        }
        Log.i(
            TAG,
            "bound: head=${headEntity != 0} neck=${neckEntity != 0} " +
                "arms=${leftChain != null}/${rightChain != null} " +
                "faceLocal=${faceLocalDir.joinToString { "%.2f".format(it) }}",
        )
    }

    /** 喂一帧姿态目标（任意线程；原子换手，渲染线程消费）。null = 请求退场还原。 */
    fun setPose(pose: MimicPose?) {
        incoming = pose
    }

    fun info(nowMs: Long = SystemClock.elapsedRealtime()): MimicInfo {
        val p = incoming
        return MimicInfo(
            engaged = appliedAny && !restoring,
            restoring = restoring,
            poseAgeMs = p?.let { nowMs - it.timestampMs } ?: -1L,
            headYawDeg = Math.toDegrees(smoothYaw.toDouble()).toFloat(),
            headPitchDeg = Math.toDegrees(smoothPitch.toDouble()).toFloat(),
            torsoPitchDeg = Math.toDegrees(smoothTorsoPitch.toDouble()).toFloat(),
            torsoRollDeg = Math.toDegrees(smoothTorsoRoll.toDouble()).toFloat(),
            torsoYawDeg = Math.toDegrees(smoothTorsoYaw.toDouble()).toFloat(),
        )
    }

    // ── Frame update (render thread) ─────────────────────────────────────

    fun update(dtSeconds: Float, nowMs: Long, animationWrote: Boolean, oneShotActive: Boolean) {
        // 一次性动作让路：不写不判过期（时间冻结）——播完回 idle 后下一帧按
        // 真实新鲜度继续（续模仿或还原自愈）
        if (oneShotActive) return

        val p = incoming
        val fresh = p != null && nowMs - p.timestampMs <= HOLD_MS

        if (restoring) {
            restoreElapsed += dtSeconds
            if (fresh && p!!.visible) {
                // 用户在缓动中途回来了：取消还原直接续模仿（平滑状态重新吸附）
                restoring = false
                resetSmoothing(p)
            } else {
                stepRestore(animationWrote)
                return
            }
        }

        if (p == null || !fresh) return
        if (!appliedAny) resetSmoothing(p)
        appliedAny = true
        driveFrame(p, dtSeconds)
    }

    /** 首次驱动/还原后重驱动：平滑状态直接吸附到本帧目标（不从旧姿势扫过去）。 */
    private fun resetSmoothing(p: MimicPose) {
        leftChain?.let { c ->
            c.smoothUpper = p.leftUpperArm?.copyOf()
            c.smoothLower = p.leftLowerArm?.copyOf()
        }
        rightChain?.let { c ->
            c.smoothUpper = p.rightUpperArm?.copyOf()
            c.smoothLower = p.rightLowerArm?.copyOf()
        }
        smoothYaw = 0f
        smoothPitch = 0f
        smoothTorsoPitch = 0f
        smoothTorsoRoll = 0f
        smoothTorsoYaw = 0f
        smoothClavicleTilt = 0f
    }

    private fun driveFrame(p: MimicPose, dtSeconds: Float) {
        val tm = engine.transformManager
        // 写入前显式提交：动画/呼吸/视线本帧的局部变换先落盘，世界变换（关节
        // 位置、父旋转）读的才是本帧姿势（lookAt 同款）
        tm.commitLocalTransformTransaction()

        val k = 1f - exp(-dtSeconds * SMOOTH_RATE)

        // ── Phase A：全部读（写上臂会移动肘/腕的世界位置，先读后写）────────
        fun readChain(chain: LimbChain): LimbRead? {
            val upperPos = worldPos(tm, chain.upper.entity) ?: return null
            val lowerPos = worldPos(tm, chain.lower?.entity) ?: return null
            val handPos = worldPos(tm, chain.hand?.entity)
            return LimbRead(upperPos, lowerPos, handPos)
        }

        val leftRead = leftChain?.let { readChain(it) }
        val rightRead = rightChain?.let { readChain(it) }

        // ── Phase B：目标平滑（visible=false 的 ping 帧保持旧目标=定格）────
        if (p.visible) {
            leftChain?.let { c ->
                p.leftUpperArm?.let { c.smoothUpper = smoothDir(c.smoothUpper, it, k) }
                p.leftLowerArm?.let { c.smoothLower = smoothDir(c.smoothLower, it, k) }
            }
            rightChain?.let { c ->
                p.rightUpperArm?.let { c.smoothUpper = smoothDir(c.smoothUpper, it, k) }
                p.rightLowerArm?.let { c.smoothLower = smoothDir(c.smoothLower, it, k) }
            }
            val headFwd = p.headForward
            if (headFwd != null && head != null) {
                val inst = tm.getInstance(head!!.entity)
                if (inst != 0) {
                    val headWorld = FloatArray(16)
                    tm.getWorldTransform(inst, headWorld)
                    val headQ = GazeMath.matToQuat(headWorld)
                    val faceForward = GazeMath.normalize3(GazeMath.rotateVector(headQ, faceLocalDir))
                    val (yaw, pitch) = PoseMimicSolver.headYawPitch(
                        faceForward, headFwd, HEAD_MAX_YAW_RAD, HEAD_MAX_PITCH_RAD,
                    )
                    smoothYaw += (yaw - smoothYaw) * k
                    smoothPitch += (pitch - smoothPitch) * k
                }
            }
            // 躯干欧拉分量平滑（P2）：轴/肩线 → (pitch, roll, yaw) + 锁骨倾滚
            val torsoTarget = p.torsoAxis
            val lineTarget = p.shoulderLine
            if (torsoTarget != null && lineTarget != null) {
                val (tp, tr, ty) = PoseMimicSolver.torsoAngles(torsoTarget, lineTarget)
                smoothTorsoPitch += (tp.coerceIn(-TORSO_MAX_PITCH_RAD, TORSO_MAX_PITCH_RAD) - smoothTorsoPitch) * k
                smoothTorsoRoll += (tr.coerceIn(-TORSO_MAX_ROLL_RAD, TORSO_MAX_ROLL_RAD) - smoothTorsoRoll) * k
                smoothTorsoYaw += (ty.coerceIn(-TORSO_MAX_YAW_RAD, TORSO_MAX_YAW_RAD) - smoothTorsoYaw) * k
                val tilt = PoseMimicSolver.shoulderLineTilt(lineTarget)
                    .coerceIn(-CLAVICLE_MAX_RAD, CLAVICLE_MAX_RAD)
                smoothClavicleTilt += (tilt - smoothClavicleTilt) * k
            }
        }

        // ── Phase C：写入（先躯干后臂再头——臂/头的父系读在 Phase A，一帧滞后
        // 是既定取舍；父旋转在写前读=lookAt 同款）──────────────────────────
        driveTorso(tm, p.visible, p.torsoAxis != null && p.shoulderLine != null)
        driveLimb(tm, leftChain, leftRead, p.leftUpperArm)
        driveLimb(tm, rightChain, rightRead, p.rightUpperArm)

        // 头/颈：分摊后的目标方向 = faceForward 经 (yaw·gain, pitch·gain) 旋转
        if (p.visible && p.headForward != null && head != null &&
            (abs(smoothYaw) > WRITE_EPS_RAD || abs(smoothPitch) > WRITE_EPS_RAD)
        ) {
            val inst = tm.getInstance(head!!.entity)
            if (inst != 0) {
                val headWorld = FloatArray(16)
                tm.getWorldTransform(inst, headWorld)
                val faceForward = GazeMath.normalize3(
                    GazeMath.rotateVector(GazeMath.matToQuat(headWorld), faceLocalDir)
                )
                val neckDir = GazeMath.dirFromYawPitch(faceForward, smoothYaw * NECK_GAIN, smoothPitch * NECK_GAIN)
                val headDir = GazeMath.dirFromYawPitch(faceForward, smoothYaw * HEAD_GAIN, smoothPitch * HEAD_GAIN)
                driveBoneDirection(tm, neck, faceForward, neckDir)
                driveBoneDirection(tm, head, faceForward, headDir)
            }
        }
    }

    /**
     * 躯干写入（P2）：三段脊柱按增益分摊 (yaw,pitch,roll) 世界轴旋转（顶端
     * 合成全量转角），双锁骨跟随肩线倾滚（左右反号、半幅）。呼吸层的同骨组
     * 靠「mimic 激活期 app 侧关呼吸」+「本层写在后=赢」双保险不冲突。
     */
    private fun driveTorso(tm: TransformManager, visible: Boolean, torsoTargeted: Boolean) {
        if (!visible || !torsoTargeted) return
        if (abs(smoothTorsoPitch) > WRITE_EPS_RAD || abs(smoothTorsoRoll) > WRITE_EPS_RAD ||
            abs(smoothTorsoYaw) > WRITE_EPS_RAD
        ) {
            driveBoneAxisAngle(tm, spine, smoothTorsoYaw * SPINE_GAIN, smoothTorsoPitch * SPINE_GAIN, smoothTorsoRoll * SPINE_GAIN)
            driveBoneAxisAngle(tm, chest, smoothTorsoYaw * CHEST_GAIN, smoothTorsoPitch * CHEST_GAIN, smoothTorsoRoll * CHEST_GAIN)
            driveBoneAxisAngle(tm, upperChest, smoothTorsoYaw * UPPER_CHEST_GAIN, smoothTorsoPitch * UPPER_CHEST_GAIN, smoothTorsoRoll * UPPER_CHEST_GAIN)
        }
        if (abs(smoothClavicleTilt) > WRITE_EPS_RAD) {
            // 正 tilt = 虚拟人左肩高：左锁骨绕 +Z 正转抬起外端、右锁骨反号
            driveBoneAxisAngle(tm, leftShoulder, 0f, 0f, smoothClavicleTilt * CLAVICLE_GAIN)
            driveBoneAxisAngle(tm, rightShoulder, 0f, 0f, -smoothClavicleTilt * CLAVICLE_GAIN)
        }
    }

    /** 绕世界轴组合旋转（yaw 绕+Y → pitch 绕+X → roll 绕+Z）驱动一根骨骼。 */
    private fun driveBoneAxisAngle(tm: TransformManager, bone: BoneRef?, yawRad: Float, pitchRad: Float, rollRad: Float) {
        if (bone == null) return
        val inst = tm.getInstance(bone.entity)
        if (inst == 0) return
        val mat = FloatArray(16)
        tm.getTransform(inst, mat)
        val worldRot = GazeMath.quatMultiply(
            GazeMath.quatMultiply(
                GazeMath.axisAngleQuat(floatArrayOf(0f, 1f, 0f), yawRad),
                GazeMath.axisAngleQuat(floatArrayOf(1f, 0f, 0f), pitchRad),
            ),
            GazeMath.axisAngleQuat(floatArrayOf(0f, 0f, 1f), rollRad),
        )
        val lNew = PoseMimicSolver.applyWorldRotation(worldRot, parentWorldQuat(tm, inst), GazeMath.matToQuat(mat))
        GazeMath.quatToMat(lNew, mat)
        tm.setTransform(inst, mat)
        bone.lastWritten = lNew
    }

    /** Phase C 的臂骨写入：大臂必驱，小臂需手关节可读（腕位置=手骨平移）。 */
    private fun driveLimb(        tm: TransformManager,
        chain: LimbChain?,
        read: LimbRead?,
        upperTarget: FloatArray?,
    ) {
        if (chain == null || read == null) return
        upperTarget ?: return
        chain.smoothUpper ?: return
        val dCurUpper = direction(read.upperPos, read.lowerPos) ?: return
        driveBoneDirection(tm, chain.upper, dCurUpper, chain.smoothUpper!!)

        val lower = chain.lower ?: return
        val lowerSmoothed = chain.smoothLower ?: return
        val dCurLower = read.handPos?.let { direction(read.lowerPos, it) } ?: return
        driveBoneDirection(tm, lower, dCurLower, lowerSmoothed)
    }

    /** 通用骨骼驱动：把骨上的 [from] 方向摆到 [to]（世界系最短弧→局部写入）。 */
    private fun driveBoneDirection(tm: TransformManager, bone: BoneRef?, from: FloatArray, to: FloatArray) {
        if (bone == null) return
        val inst = tm.getInstance(bone.entity)
        if (inst == 0) return
        val mat = FloatArray(16)
        tm.getTransform(inst, mat)
        val lCurrent = GazeMath.matToQuat(mat)
        val lNew = PoseMimicSolver.driveRotation(from, to, parentWorldQuat(tm, inst), lCurrent, WRITE_EPS_RAD)
            ?: return
        GazeMath.quatToMat(lNew, mat)
        tm.setTransform(inst, mat)
        bone.lastWritten = lNew
    }

    /**
     * 退场缓动：把所有被驱动骨骼从最后写入值滑向「动画姿势」（本帧动画重写过）
     * 或「rest 局部旋转」（rest pose 定格）。缓动中姿势仍是绝对可写的（每帧
     * 重算混合目标），完成后清账交还动画。
     */
    private fun stepRestore(animationWrote: Boolean) {
        val tm = engine.transformManager
        val t = (restoreElapsed / RESTORE_SECONDS).coerceIn(0f, 1f)
        var wrote = false
        for (bone in drivenBones()) {
            val lw = bone.lastWritten ?: continue
            val inst = tm.getInstance(bone.entity)
            if (inst == 0) continue
            val mat = FloatArray(16)
            tm.getTransform(inst, mat)
            val base = if (animationWrote) GazeMath.matToQuat(mat) else bone.restLocalQ
            val lNew = if (t >= 1f) base else GazeMath.quatNlerp(lw, base, t)
            GazeMath.quatToMat(lNew, mat)
            tm.setTransform(inst, mat)
            bone.lastWritten = if (t >= 1f) null else lNew
            wrote = true
        }
        if (t >= 1f) {
            leftChain?.let { it.smoothUpper = null; it.smoothLower = null }
            rightChain?.let { it.smoothUpper = null; it.smoothLower = null }
            smoothYaw = 0f
            smoothPitch = 0f
            smoothTorsoPitch = 0f
            smoothTorsoRoll = 0f
            smoothTorsoYaw = 0f
            smoothClavicleTilt = 0f
            appliedAny = false
            restoring = false
            restoreElapsed = 0f
        } else if (wrote) {
            appliedAny = true
        }
    }

    private fun drivenBones(): List<BoneRef> = listOfNotNull(
        spine, chest, upperChest, leftShoulder, rightShoulder,
        neck, head,
        leftChain?.upper, leftChain?.lower,
        rightChain?.upper, rightChain?.lower,
    )

    private fun ref(tm: TransformManager, entity: Int): BoneRef? {
        if (entity == 0) return null
        val inst = tm.getInstance(entity)
        if (inst == 0) return null
        val mat = FloatArray(16)
        tm.getTransform(inst, mat)
        return BoneRef(entity, GazeMath.matToQuat(mat))
    }

    /** 骨骼实体世界平移（关节位置）；实体/实例无效返回 null。 */
    private fun worldPos(tm: TransformManager, entity: Int?): FloatArray? {
        if (entity == null || entity == 0) return null
        val inst = tm.getInstance(entity)
        if (inst == 0) return null
        val m = FloatArray(16)
        tm.getWorldTransform(inst, m)
        return floatArrayOf(m[12], m[13], m[14])
    }

    /** 两关节位置差 → 单位方向；塌缩（< [MIN_SEGMENT_M]）返回 null。 */
    private fun direction(a: FloatArray, b: FloatArray): FloatArray? {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val dz = b[2] - a[2]
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < MIN_SEGMENT_M) return null
        return floatArrayOf(dx / len, dy / len, dz / len)
    }

    /** 方向目标指数趋近（分量 lerp 后归一化；首帧直接吸附）。 */
    private fun smoothDir(current: FloatArray?, target: FloatArray, k: Float): FloatArray {
        val c = current ?: return target.copyOf()
        val x = c[0] + (target[0] - c[0]) * k
        val y = c[1] + (target[1] - c[1]) * k
        val z = c[2] + (target[2] - c[2]) * k
        val len = kotlin.math.sqrt(x * x + y * y + z * z)
        if (len < 1e-6f) return target.copyOf() // 对向穿越零向量：直接吸附目标
        return floatArrayOf(x / len, y / len, z / len)
    }

    /**
     * 父骨骼世界旋转。⚠ TransformManager.getParent 返回父 **entity**，必须
     * getInstance 往返（entity/instance 混用是已知 SIGSEGV 陷阱，见
     * VrmSpringBoneManager / filament-1683-api-constraints 记忆）。
     */
    private fun parentWorldQuat(tm: TransformManager, inst: Int): FloatArray {
        val parentEntity = tm.getParent(inst)
        if (parentEntity == 0) return IDENTITY_Q
        val parentInst = tm.getInstance(parentEntity)
        if (parentInst == 0) return IDENTITY_Q
        val m = FloatArray(16)
        tm.getWorldTransform(parentInst, m)
        return GazeMath.matToQuat(m)
    }
}
