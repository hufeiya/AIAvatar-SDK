package com.neethu.corelib.internal

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.TransformManager
import com.neethu.corelib.BreathInfo
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 呼吸叠加引擎 —— 骨骼程序化胸肩起伏（方案一，docs/avatar-realism-feasibility.md
 * §2.1）。
 *
 * SK_Sun 没有任何呼吸类 morph（52 个 ARKit + 18 个 VRM 预设里 breathe/breath
 * 零命中），morph 路线不存在；且默认待机 Arms Down.vrma 是单帧静态姿势，模型
 * 挂机完全冻结——呼吸直接消灭最大的塑料感来源。
 *
 * 结构与 [VrmLookAtEngine] 同构（「先剥再叠 + 幂等落盘」统一范式，A.1 第 36 条
 * 的正面解法）：
 *  - [bind] 抓取 spine/chest/upperChest/双肩 五骨的 rest 局部变换；
 *  - 每帧在动画分支之后、`updateBoneMatrices()` 之前，呼吸相位器按 [BreathWave]
 *    算归一化幅度 a ∈ [0,1]，换算成各骨绕世界轴的小角度旋转（吸气 = 胸廓上抬
 *    后展 + 双肩微耸），经「世界系 → 父骨骼系」换算 `L' = inv(P)·D·P·L` 写入
 *    局部旋转；
 *  - VRMA 每帧重写全脊柱链（52 条轨道，实测），动画刚写完 → 呼吸叠加，与任何
 *    idle/手势 clip 天然无冲突；本引擎写完的脊柱起伏被后续 lookAt 的头部位姿
 *    读取自然感知（视线跟随呼吸起伏，方向正确）。
 *
 * 说话调制：频率 ×[SPEAKING_HZ_FACTOR]、幅度 ×[SPEAKING_AMP_FACTOR]，幅度系数
 * 经 [BreathWave.ampFactor] 一阶趋近，置位/复位不突跳。幅度为度级，不会把胸部
 * mesh 顶出 gltfio 的绑定姿态静态剔除盒（角色剔除本就已全局关闭，见
 * SoulLinkRenderer.applyAvatarCulling）。
 *
 * 轴语义（模型加载后恒面向 +Z，VRM 0.x 已被渲染器翻转；见 lookAt 绑定注释）：
 *  - 脊柱俯仰绕世界 X（左右轴），轴取 **−X** 使正幅度 = 胸前上抬（绕 +X 正转
 *    会把 +Z 前方压向 −Y，所以取反）；
 *  - 耸肩绕世界 Z（前后轴）：模型左手在 +X，绕 +Z 正转 = 左肩上抬/右肩下沉，
 *    所以左肩 +shrug、右肩 −shrug 才是双肩同时微抬。
 */
internal class VrmBreathEngine(
    private val engine: Engine,
    /** 静息呼吸频率（Hz）：0.25 = 15 次/分，成年人静息值。 */
    private val restHz: Float = DEFAULT_REST_HZ,
    /** 说话时频率倍率（呼吸变快）。 */
    private val speakingHzFactor: Float = SPEAKING_HZ_FACTOR,
    /** 说话时幅度倍率（呼吸变浅）。 */
    private val speakingAmpFactor: Float = SPEAKING_AMP_FACTOR,
    /** 吸气峰值的 spine 俯仰幅度（度）。 */
    private val spinePitchDegrees: Float = SPINE_PITCH_DEG,
    /** 吸气峰值的 chest 俯仰幅度（度）。 */
    private val chestPitchDegrees: Float = CHEST_PITCH_DEG,
    /** 吸气峰值的 upperChest 俯仰幅度（度），三段脊柱链合计 ~2.6°。 */
    private val upperChestPitchDegrees: Float = UPPER_CHEST_PITCH_DEG,
    /** 吸气峰值的双肩耸肩幅度（度）。 */
    private val shoulderShrugDegrees: Float = SHOULDER_SHRUG_DEG,
) {

    companion object {
        private const val TAG = "VrmBreath"

        private const val DEFAULT_REST_HZ = 0.25f
        private const val SPEAKING_HZ_FACTOR = 1.15f
        private const val SPEAKING_AMP_FACTOR = 0.6f

        // 吸气峰值的分骨幅度（度）：链合计 = 三段俯仰之和（头部继承同量级俯仰）。
        // 真机观感定档史：24°（深呼吸级）→7°→链 ~4.7°（自然呼吸级；视线引擎
        // 会把头部起伏对冲掉大半，实际观感以肩部起伏为主）。
        private const val SPINE_PITCH_DEG = 1.2f
        private const val CHEST_PITCH_DEG = 2.4f
        private const val UPPER_CHEST_PITCH_DEG = 1.0f
        private const val SHOULDER_SHRUG_DEG = 1.2f

        // 低于此幅度（rad ≈ 0.11°）跳过写入，只负责剥残留——呼气谷底完全安静
        //（与 VrmLookAtEngine.WRITE_EPS_RAD 同值）。
        // 写入阈值（rad）：**必须接近零**。呼吸是发骨弹簧链的驱动源——阈值以下
        // 跳过写入会让骨骼位移出现 0.1° 级台阶，弹簧 verlet 对台阶输入响应为
        // 抖动（真机：头发抽搐）。连续写入的成本只是每帧数次微小 setTransform。
        private const val WRITE_EPS_RAD = 1e-5f

        private val SPINE_PITCH_AXIS = floatArrayOf(-1f, 0f, 0f)
        private val LEFT_SHRUG_AXIS = floatArrayOf(0f, 0f, 1f)
        private val RIGHT_SHRUG_AXIS = floatArrayOf(0f, 0f, -1f)

        private val IDENTITY_Q = floatArrayOf(0f, 0f, 0f, 1f)

        private val DEG_TO_RAD = (Math.PI / 180.0).toFloat()
    }

    /**
     * 骨骼引用 + 叠加层记账。双信号判定（[BreathBaseResolver]，HipsDragOffsetSolver
     * 同款模式）：[preAnimLocal] 是渲染循环在动画分支前拍的快照，与动画写完后的
     * 当前值比对判定「动画是否重写过」；[lastWritten]/[lastOffset] 服务于未重写
     * regime（rest pose 定格）的 strip。单靠「当前≈上次写入」推断在呼吸层会锁死
     * （详见 [BreathBaseResolver] 注释）。
     */
    private class BoneRef(val entity: Int, val restLocalMat: FloatArray) {
        var lastWritten: FloatArray? = null
        var lastOffset: FloatArray? = null
        var preAnimLocal: FloatArray? = null
    }

    private var spine: BoneRef? = null
    private var chest: BoneRef? = null
    private var upperChest: BoneRef? = null
    private var leftShoulder: BoneRef? = null
    private var rightShoulder: BoneRef? = null
    private var bonesBound = 0

    @Volatile private var enabled = true
    @Volatile private var speaking = false

    private var phase = 0f
    /** 当前幅度调制系数（静息 1 ⇄ 说话 [speakingAmpFactor]，一阶趋近）。 */
    private var ampScale = 1f
    private var appliedAny = false

    // 用户设置（设置面板实时可调）
    @Volatile private var userAmplitudeScale = 1f
    @Volatile private var userRateHz = -1f  // <0 = 用 restHz

    /** Bind humanoid bone entities (0 = missing); missing bones are skipped. */
    fun bind(
        spineEntity: Int,
        chestEntity: Int,
        upperChestEntity: Int,
        leftShoulderEntity: Int,
        rightShoulderEntity: Int,
    ) {
        val tm = engine.transformManager
        this.spine = ref(tm, spineEntity)
        this.chest = ref(tm, chestEntity)
        this.upperChest = ref(tm, upperChestEntity)
        this.leftShoulder = ref(tm, leftShoulderEntity)
        this.rightShoulder = ref(tm, rightShoulderEntity)
        bonesBound = listOf(spine, chest, upperChest, leftShoulder, rightShoulder)
            .count { it != null }
        phase = 0f
        ampScale = 1f
        appliedAny = false
        if (bonesBound == 0) {
            Log.w(TAG, "no spine/shoulder bones — breath disabled for this model")
        } else {
            Log.i(
                TAG,
                "bound: spine=${spineEntity != 0} chest=${chestEntity != 0} " +
                    "upperChest=${upperChestEntity != 0} " +
                    "shoulders=${leftShoulderEntity != 0 && rightShoulderEntity != 0} " +
                    "($bonesBound bones)",
            )
        }
    }

    /** Toggle the overlay (default on); disabling restores the animated pose on the next update. */
    fun setEnabled(value: Boolean) {
        enabled = value
    }

    /** Mark the avatar as speaking — breath turns shallower ([speakingAmpFactor]) and faster. */
    fun setSpeaking(value: Boolean) {
        speaking = value
    }

    /** 用户幅度倍率（0~3，1 = 设计幅度），实时生效（设置面板）。 */
    fun setAmplitudeScale(scale: Float) {
        userAmplitudeScale = scale.coerceIn(0f, 3f)
    }

    /** 用户呼吸频率覆盖（Hz）；≤0 恢复默认 restHz。实时生效（设置面板）。 */
    fun setRateHz(hz: Float) {
        userRateHz = if (hz > 0.05f) hz.coerceAtMost(2f) else -1f
    }

    fun info(): BreathInfo {
        val activeHz = (if (userRateHz > 0) userRateHz else restHz) *
            (if (speaking) speakingHzFactor else 1f)
        return BreathInfo(
            enabled = enabled,
            speaking = speaking,
            phase = phase,
            breathsPerMinute = activeHz * 60f,
            amplitude = BreathWave.amplitude(phase) * ampScale * userAmplitudeScale,
            bonesBound = bonesBound,
        )
    }

    /**
     * 渲染循环在动画分支**之前**调用：快照五骨的局部旋转，供 [BreathBaseResolver]
     * 判定「动画是否重写过」。必须发生在 vrma/animator 写入之前。
     */
    fun capturePreAnimation() {
        val tm = engine.transformManager
        for (bone in listOf(spine, chest, upperChest, leftShoulder, rightShoulder)) {
            val b = bone ?: continue
            val inst = tm.getInstance(b.entity)
            if (inst == 0) {
                b.preAnimLocal = null
                continue
            }
            val mat = FloatArray(16)
            tm.getTransform(inst, mat)
            b.preAnimLocal = GazeMath.matToQuat(mat)
        }
    }

    // ── Frame update (render thread) ─────────────────────────────────────

    fun update(dtSeconds: Float) {
        if (!enabled) {
            if (appliedAny) restoreRest()
            return
        }

        // 说话⇄静息的幅度系数一阶趋近：开/关说话都不产生幅度突跳
        ampScale = BreathWave.ampFactor(ampScale, if (speaking) speakingAmpFactor else 1f, dtSeconds)
        val activeHz = (if (userRateHz > 0) userRateHz else restHz) *
            (if (speaking) speakingHzFactor else 1f)
        phase = BreathWave.advance(phase, dtSeconds, activeHz)
        val a = BreathWave.amplitude(phase) * ampScale * userAmplitudeScale

        applyOffset(spine, SPINE_PITCH_AXIS, a * spinePitchDegrees * DEG_TO_RAD)
        applyOffset(chest, SPINE_PITCH_AXIS, a * chestPitchDegrees * DEG_TO_RAD)
        applyOffset(upperChest, SPINE_PITCH_AXIS, a * upperChestPitchDegrees * DEG_TO_RAD)
        applyOffset(leftShoulder, LEFT_SHRUG_AXIS, a * shoulderShrugDegrees * DEG_TO_RAD)
        applyOffset(rightShoulder, RIGHT_SHRUG_AXIS, a * shoulderShrugDegrees * DEG_TO_RAD)

    }


    /**
     * Rotate [bone] about the world-frame [axis] by [angleRad], keeping the
     * local translation. 父骨骼世界四元数读的是上一帧提交的世界变换（本帧动画
     * 只写了局部），对 0.25 Hz 的呼吸是二阶噪声——与 lookAt 同款取舍，闭环
     * 逐帧收敛。先剥再叠见 [BoneRef] 注释。
     */
    private fun applyOffset(bone: BoneRef?, axis: FloatArray, angleRad: Float) {
        if (bone == null) return
        val tm = engine.transformManager
        val inst = tm.getInstance(bone.entity)
        if (inst == 0) return

        val mat = FloatArray(16)
        tm.getTransform(inst, mat)
        val lCurrent = GazeMath.matToQuat(mat)

        // 双信号基座判定（BreathBaseResolver）：动画重写过→以动画新姿势为基座，
        // 绝不 strip；未重写（rest pose 定格）→strip 掉自己上次的偏移。
        val strip = BreathBaseResolver.resolve(
            lCurrent, bone.preAnimLocal, bone.lastWritten, bone.lastOffset,
        )
        val base = strip.first
        bone.lastWritten = null
        bone.lastOffset = null

        // 本帧幅度近零：只负责清掉可能残留的旧偏移（rest pose 定格态）
        if (abs(angleRad) < WRITE_EPS_RAD) {
            if (strip.second) {
                GazeMath.quatToMat(base, mat)
                tm.setTransform(inst, mat)
            }
            return
        }        // ⚠ TransformManager.getParent 返回父 entity，必须 getInstance 后再用
        //（entity/instance 混用是已知 SIGSEGV 坑，见 VrmSpringBoneManager）
        val parentEntity = tm.getParent(inst)
        val parentQ = if (parentEntity != 0) {
            val parentInst = tm.getInstance(parentEntity)
            if (parentInst != 0) {
                val m = FloatArray(16)
                tm.getWorldTransform(parentInst, m)
                GazeMath.matToQuat(m)
            } else {
                IDENTITY_Q
            }
        } else {
            IDENTITY_Q
        }

        val dWorld = axisAngleQuat(axis, angleRad)
        val localOffset = GazeMath.quatMultiply(
            GazeMath.quatMultiply(GazeMath.quatInverse(parentQ), dWorld),
            parentQ,
        )
        val lNew = GazeMath.quatMultiply(localOffset, base)
        GazeMath.quatToMat(lNew, mat)
        tm.setTransform(inst, mat)
        bone.lastWritten = lNew
        bone.lastOffset = localOffset
        appliedAny = true
    }

    /**
     * Strip the overlay off the driven bones (breath disabled)：走同一双信号判定
     * ——动画重写过的骨骼已是干净姿势（不写）；rest pose 定格的骨骼剥掉我的
     * 偏移。直接写 rest 会闪一帧错误姿势。
     */
    private fun restoreRest() {
        val tm = engine.transformManager
        for (bone in listOf(spine, chest, upperChest, leftShoulder, rightShoulder)) {
            val b = bone ?: continue
            val inst = tm.getInstance(b.entity)
            if (inst == 0) continue
            val mat = FloatArray(16)
            tm.getTransform(inst, mat)
            val (base, stripped) = BreathBaseResolver.resolve(
                GazeMath.matToQuat(mat), b.preAnimLocal, b.lastWritten, b.lastOffset,
            )
            if (stripped) {
                GazeMath.quatToMat(base, mat)
                tm.setTransform(inst, mat)
            }
            b.lastWritten = null
            b.lastOffset = null
        }
        appliedAny = false
    }

    private fun ref(tm: TransformManager, entity: Int): BoneRef? {
        if (entity == 0) return null
        val inst = tm.getInstance(entity)
        if (inst == 0) return null
        val mat = FloatArray(16)
        tm.getTransform(inst, mat)
        return BoneRef(entity, mat.clone())
    }

    /** Unit-axis [axis] rotated by [angle] as a quaternion ([x,y,z,w]). */
    private fun axisAngleQuat(axis: FloatArray, angle: Float): FloatArray {
        val s = sin(angle / 2f)
        return floatArrayOf(axis[0] * s, axis[1] * s, axis[2] * s, cos(angle / 2f))
    }
}
