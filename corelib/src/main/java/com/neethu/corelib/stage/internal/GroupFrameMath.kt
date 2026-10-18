package com.neethu.corelib.stage.internal

import kotlin.math.atan
import kotlin.math.tan

/**
 * 分组取景的视锥拟合数学（辩论赛 P0a，docs/debate-feasibility.md §8）：
 * 4v4 全景 + 主持人九人构图，目标组头距远超旧 [GroupShotMath] 的 4m span
 * 上限，且横屏 aspect 会显著放宽水平视场——距离必须按「实际目标边界投影 +
 * 实时纵横比 + 视锥半角」计算，而不是头距 × 常数。
 *
 * 坑 22 纪律同样适用：本对象只吃世界坐标点与相机参数，不碰相机状态。
 */
internal object GroupFrameMath {

    /**
     * [points] = 目标组的世界坐标采样点（头/hips/脚等，混合坐站时两组都要给）。
     * [vFovDeg] = 相机竖直 FOV；[aspect] = 视图宽高比（>1 横屏）。
     * [viewYawDeg]/[viewPitchDeg] = 机位相对 pivot 的方位（与 Stage 的
     * orbitDirection 一致：yaw 0 = 正前方 +Z）。距离取「水平装得下」与
     * 「竖直装得下」的较大者，加 [marginMeters] 安全边距。
     */
    fun frame(
        points: List<FloatArray>,
        vFovDeg: Float,
        aspect: Float,
        marginMeters: Float = 0.4f,
        minDistance: Float = 2.5f,
        viewYawDeg: Float = 0f,
        viewPitchDeg: Float = 8f,
    ): com.neethu.corelib.stage.internal.GroupShotMath.Frame? {
        if (points.isEmpty()) return null
        val safeAspect = if (aspect > 0.01f) aspect else 1f

        var cx = 0f; var cy = 0f; var cz = 0f
        for (p in points) { cx += p[0]; cy += p[1]; cz += p[2] }
        val n = points.size.toFloat()
        cx /= n; cy /= n; cz /= n

        // 相机右向量 / 上向量（yaw/pitch 与 Stage.orbitDirection 同约定）
        val yaw = Math.toRadians(viewYawDeg.toDouble())
        val pitch = Math.toRadians(viewPitchDeg.toDouble())
        // 前向（相机指向 pivot 的反方向）：eye 在 pivot 的 +Z 偏 yaw/pitch 处
        val fwd = doubleArrayOf(
            kotlin.math.sin(yaw) * kotlin.math.cos(pitch),
            kotlin.math.sin(pitch),
            kotlin.math.cos(yaw) * kotlin.math.cos(pitch),
        )
        val right = doubleArrayOf(kotlin.math.cos(yaw), 0.0, -kotlin.math.sin(yaw))
        // up = fwd × right（yaw=0、pitch=8° 时 ≈ (0, 0.99, -0.14)，指天）
        val up = doubleArrayOf(
            fwd[1] * right[2] - fwd[2] * right[1],
            fwd[2] * right[0] - fwd[0] * right[2],
            fwd[0] * right[1] - fwd[1] * right[0],
        )

        // 目标点在相机平面上的半展幅
        var halfW = 0f; var halfH = 0f
        for (p in points) {
            val dx = (p[0] - cx).toDouble(); val dy = (p[1] - cy).toDouble(); val dz = (p[2] - cz).toDouble()
            val w = kotlin.math.abs(dx * right[0] + dy * right[1] + dz * right[2]).toFloat()
            val h = kotlin.math.abs(dx * up[0] + dy * up[1] + dz * up[2]).toFloat()
            if (w > halfW) halfW = w
            if (h > halfH) halfH = h
        }

        val vHalf = Math.toRadians((vFovDeg / 2f).toDouble())
        val hHalf = atan(tan(vHalf) * safeAspect)
        val distH = if (halfW + marginMeters <= 1e-4f) 0.0 else (halfW + marginMeters) / tan(hHalf)
        val distV = if (halfH + marginMeters <= 1e-4f) 0.0 else (halfH + marginMeters) / tan(vHalf)
        val distance = maxOf(minDistance.toDouble(), distH, distV).toFloat()

        return GroupShotMath.Frame(floatArrayOf(cx, cy, cz), distance)
    }
}
