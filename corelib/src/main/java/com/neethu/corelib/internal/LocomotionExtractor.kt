package com.neethu.corelib.internal

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 方案 C：运行时位移提取（docs/locomotion-feasibility.md §4.3）——把**非原地**
 * 素材烘焙在 hips 平移里的水平前向分量从 hips 轨道剥离出来，改由渲染层积分进
 * root 位移。效果：任意非原地素材变成可循环、可控速、不回弹的步态/过渡剪辑
 * （delta 语义下「播完 hips 回 rest、身体瞬移回原点」的致命伤就此消除）。
 *
 * 分解原则（§4.3 原文）：水平**前向**分量抽进 root；竖直分量留在 hips（保上下
 * 起伏）；**侧向分量也留在 hips**——脏素材的侧向晃动留在原地是肉眼可见的质检
 * 信号（坑 11），不悄悄抽走。
 *
 * 坐标口径：轨道值是 VrmaParser 归一化空间的 hips 平移；[vrmFlip] 对应 VRM0 的
 * x/z 取反（与 VrmaAnimationEngine 的平移分支同一翻转），因此 [forwardDir] 直接
 * 是 **模型空间**（hips 父链空间）的单位向量。[heightScale]（targetHipsY/
 * animHipsY）由调用方传入，[forwardAt] 返回值已是模型尺度（米）。
 *
 * 纯数学零依赖（坑 21：不带 android.util.Log，JVM 单测无 mock）。
 */
internal class LocomotionExtractor private constructor(
    /** 素材周期（秒）。 */
    val duration: Float,
    /** 每循环净前向位移（模型尺度，米，≥0）。 */
    val netForwardMeters: Float,
    /** 前向单位方向（模型空间 XZ，y=0，已含 VRM0 翻转）。 */
    val forwardDir: FloatArray,
    private val times: FloatArray,
    /** 每关键帧的前向投影（模型尺度，f[0]=0）。 */
    private val forwardAtKey: FloatArray,
) {

    /**
     * 展开循环后的累计前向位移：f(t) = ⌊t/dur⌋·net + f(t mod dur)。循环素材
     * 每圈净前向 net，播放头跨圈时函数连续增长——渲染层逐帧取差值积分进 root。
     */
    fun forwardAt(timeSeconds: Float): Float {
        if (duration <= 0f) return 0f
        val t = timeSeconds.coerceIn(0f, Float.MAX_VALUE)
        val loops = (t / duration).toInt()
        return loops * netForwardMeters + sampleForward(t - loops * duration)
    }

    /** 单循环内的前向位移（关键帧分段线性插值）。 */
    private fun sampleForward(t: Float): Float {
        if (times.isEmpty()) return 0f
        if (t <= times.first()) return forwardAtKey[0]
        if (t >= times.last()) return forwardAtKey[times.size - 1]
        var idx = 0
        for (i in 0 until times.size - 1) {
            if (t >= times[i] && t < times[i + 1]) { idx = i; break }
        }
        val span = times[idx + 1] - times[idx]
        val a = if (span > 0f) (t - times[idx]) / span else 0f
        return forwardAtKey[idx] + (forwardAtKey[idx + 1] - forwardAtKey[idx]) * a
    }

    companion object {

        /**
         * 判定「非原地」的最小净前向位移（模型尺度，米/循环）：低于它的素材
         * 视为原地（net 是脚步起伏等噪声的残差），不启用提取。
         */
        const val MIN_NET_FORWARD_METERS = 0.12f

        /**
         * 从解析好的 VRMA 分析前向位移剖面。素材无 hips 平移轨道 / 帧数据不足 /
         * 净前向低于阈值时返回 null（= 原地素材，走方案 B 程序位移）。
         *
         * @param hipsTrack hips 的 translation 轨道（归一化空间，未翻转）
         * @param restHipsPosition 素材 rest hips 位置（delta 基准）
         * @param vrmFlip VRM0.x 传 true（x/z 取反，与引擎平移分支一致）
         * @param heightScale 模型 hips 高 / 素材 hips 高
         * @param clipDuration 素材总时长（= VrmaAnimation.duration；循环展开的
         *   取模基数必须与引擎 playhead 同一口径，不能用轨道首末时间差）
         */
        fun analyze(
            hipsTrack: VrmaParser.HumanoidTrack?,
            restHipsPosition: FloatArray,
            vrmFlip: Boolean,
            heightScale: Float,
            clipDuration: Float,
        ): LocomotionExtractor? {
            if (clipDuration <= 0f) return null
            if (hipsTrack == null || hipsTrack.path != "translation") return null
            val times = hipsTrack.times
            val values = hipsTrack.values
            if (times.size < 2 || values.size < times.size * 3) return null

            val scale = if (heightScale > 0.01f) heightScale else 1f
            val n = times.size

            // 逐关键帧求 delta（相对素材 rest，含 VRM0 翻转）——与引擎
            // VrmaAnimationEngine 平移分支完全同一坐标口径。
            fun delta(i: Int): FloatArray {
                val j = i * 3
                val dx = values[j] - restHipsPosition[0]
                val dy = values[j + 1] - restHipsPosition[1]
                val dz = values[j + 2] - restHipsPosition[2]
                return if (vrmFlip) floatArrayOf(-dx, dy, -dz) else floatArrayOf(dx, dy, dz)
            }

            // 前向方向 = 首末关键帧水平位移的归一化（循环素材首尾未必重合，
            // 末端即每圈净位移方向；侧向晃动的素材会投影出偏斜 dir，质检信号）。
            // 「非原地」判定按 **素材空间米**（net/scale 还原）——engine 单位
            // 随模型的 hips 缩放比浮动（bindToModel 的 targetHipsY 可能是
            // 陈旧世界高度，unit-cube 缩放模型上差一个数量级），固定阈值只
            // 能定在素材口径上。
            val last = delta(n - 1)
            val netX = last[0] * scale
            val netZ = last[2] * scale
            val netLen = sqrt(netX * netX + netZ * netZ)
            if (netLen / scale < MIN_NET_FORWARD_METERS) return null
            val dirX = netX / netLen
            val dirZ = netZ / netLen

            // 每关键帧的前向投影（首帧归零：素材可以从任意相位起播）。
            // dir 就是净位移方向，投影恒正增长——「素材反向行进」已由 dir
            // 的符号吸收，无需翻转分支。
            val forwardAtKey = FloatArray(n)
            for (i in 0 until n) {
                val d = delta(i)
                forwardAtKey[i] = (d[0] * dirX + d[2] * dirZ) * scale
            }
            val base = forwardAtKey[0]
            for (i in 0 until n) forwardAtKey[i] -= base

            val net = forwardAtKey[n - 1]
            if (abs(net) / scale < MIN_NET_FORWARD_METERS) return null
            return LocomotionExtractor(
                duration = clipDuration,
                netForwardMeters = net,
                forwardDir = floatArrayOf(dirX, 0f, dirZ),
                times = times,
                forwardAtKey = forwardAtKey,
            )
        }
    }
}
