package com.neethu.corelib.internal

/**
 * 「模仿我」骨骼解算的纯函数层（JVM 可测，已知答案锁公式）。
 *
 * 驱动模型 = **逐段方向绝对驱动（swing-only）**：对每根臂骨读「本帧动画刚写完
 * 的世界方向」dCurrent，与目标方向 dTarget 求世界系最短弧旋转 D，再经父骨骼
 * 世界旋转换算成局部旋转（与 VrmLookAtEngine.applyOffset 同式 `L' = inv(P)·D·P·L`）。
 *
 * 为什么绝对驱动不需要"先剥再叠"记账：D 永远是"从当前指向摆到目标指向"而非
 * 相对偏移增量——写多少帧都不会累积，动画这帧把基座重写成什么都能纠正回来。
 * （相对偏移叠加层——视线/呼吸——才需要 strip/lastWritten 双信号。）
 *
 * 不做两骨 IK：大臂/小臂各按自己的骨段方向摆到目标，天然无极向量翻转、无
 * 误差累积（Kalidokit 同思路；代价=丢小臂沿轴扭转，观感可接受，见
 * docs/mimic-skill-feasibility.md §4）。
 */
internal object PoseMimicSolver {

    /**
     * 把骨骼从当前局部旋转 [currentLocal]（本帧动画刚写完）驱到「骨段世界方向
     * = [dTarget]」。返回新的局部四元数；目标与当前方向夹角小于 [writeEps]（弧
     * 度）时返回 null（静止零写入，同 FaceDriver/lookAt 的去重语义）。
     *
     * [dCurrent] = 本帧读到的骨段世界方向（单位化），[parentQ] = 父骨骼世界旋转
     * （写在任何 mimic 写入**之前**读——链式写入的父系一帧滞后是二阶噪声，
     * lookAt 同款取舍）。
     */
    fun driveRotation(
        dCurrent: FloatArray,
        dTarget: FloatArray,
        parentQ: FloatArray,
        currentLocal: FloatArray,
        writeEps: Float,
    ): FloatArray? {
        val d = GazeMath.quatAngle(
            floatArrayOf(0f, 0f, 0f, 1f),
            GazeMath.quatFromTo(dCurrent, dTarget),
        )
        if (d < writeEps) return null
        return applyWorldRotation(GazeMath.quatFromTo(dCurrent, dTarget), parentQ, currentLocal)
    }

    /**
     * 把世界系旋转 [worldRot] 作用到骨骼上（预乘）：Q' = worldRot·Q_current =
     * worldRot·P·L → `L' = inv(P)·worldRot·P·L`。躯干的欧拉分量驱动（绕世界
     * 轴的 axis-angle，非 from-to 方向对）走这里。
     */
    fun applyWorldRotation(
        worldRot: FloatArray,
        parentQ: FloatArray,
        currentLocal: FloatArray,
    ): FloatArray {
        val dp = GazeMath.quatMultiply(worldRot, parentQ)
        return GazeMath.quatMultiply(GazeMath.quatInverse(parentQ), dp)
            .let { GazeMath.quatMultiply(it, currentLocal) }
    }

    /**
     * 躯干姿态分解（纯函数）：从「躯干轴」（髋中点→肩中点，avatar 世界系单位
     * 向量，静止≈(0,1,0)）与「肩线」（虚拟人左肩→右肩，静止≈(1,0,0)）解出
     * 三个欧拉分量（弧度，绕**世界轴**）：
     *  - pitch（前倾/后仰，绕 +X）：atan2(axis.z, axis.y)，正=躯干顶端朝观察者；
     *  - roll（侧倾，绕 +Z）：atan2(axis.x, axis.y)，正=顶端朝虚拟人左侧；
     *  - yaw（转身，绕 +Y，来自肩线的水平旋转）：atan2(−line.z, line.x)。
     * 肩线出画面的倾滚（一肩高一肩低）另由 [shoulderLineTilt] 取出给锁骨。
     * 分量的镜像正确性由 app 侧 mirrorFrame 保证（torsoAxis/shoulderLine 都是
     * 镜像后的 avatar 系向量）。
     */
    fun torsoAngles(
        torsoAxis: FloatArray,
        shoulderLine: FloatArray,
    ): Triple<Float, Float, Float> {
        val pitch = kotlin.math.atan2(torsoAxis[2], torsoAxis[1])
        val roll = kotlin.math.atan2(torsoAxis[0], torsoAxis[1])
        val yaw = kotlin.math.atan2(-shoulderLine[2], shoulderLine[0])
        return Triple(pitch, roll, yaw)
    }

    /** 肩线出水平面的倾滚（弧度）：一肩高一肩低的量，锁骨小幅度跟随用。 */
    fun shoulderLineTilt(shoulderLine: FloatArray): Float =
        kotlin.math.atan2(
            shoulderLine[1],
            kotlin.math.sqrt(
                shoulderLine[0] * shoulderLine[0] + shoulderLine[2] * shoulderLine[2],
            ),
        )

    /**
     * 头部解算：当前脸朝向 [faceForward]（世界系单位向量，来自本帧头骨世界旋转
     * × faceLocalDir）转到目标朝向 [targetForward]，返回限幅后的 (yaw, pitch)
     * 弧度——yaw 绕世界 +Y、pitch 为仰角差（[GazeMath.signedYawPitch] 约定）。
     * 引擎再做颈/头分摊与时间平滑。
     */
    fun headYawPitch(
        faceForward: FloatArray,
        targetForward: FloatArray,
        maxYawRad: Float,
        maxPitchRad: Float,
    ): Pair<Float, Float> {
        val raw = GazeMath.signedYawPitch(faceForward, targetForward)
        return raw[0].coerceIn(-maxYawRad, maxYawRad) to
            raw[1].coerceIn(-maxPitchRad, maxPitchRad)
    }
}
