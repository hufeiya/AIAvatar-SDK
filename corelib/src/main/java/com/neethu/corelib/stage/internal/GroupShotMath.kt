package com.neethu.corelib.stage.internal

/**
 * GROUP_SHOT 取景的纯数学（多虚拟人 P3，docs/multi-avatar-feasibility.md
 * §4.7「GROUP_SHOT 双人同框」）：pivot = 全体头部锚点均值，距离按最远头距
 * 缩放——两个模特走近走远、三个圆桌站位都装得下。坑 22 纪律的依据：本函数
 * 只吃世界坐标锚点，不碰相机状态（yaw/pitch 固定常量，在 Stage 里），
 * 「防自参照固化」在输入层面就不成立。
 */
internal object GroupShotMath {

    /** 一帧取景：注视点（头部均值）与相机距离。 */
    data class Frame(val pivot: FloatArray, val distance: Float)

    /**
     * [heads] = 各演员头部世界坐标（至少一个）。
     * 距离 = maxPairDist.coerceIn(MIN_SPAN, MAX_SPAN) × [spanScale]，且不低于
     * [minDistance]（单人兜底 ≈ 中景的取景余量）。
     */
    fun frame(
        heads: List<FloatArray>,
        spanScale: Float = 3.2f,
        minDistance: Float = 3.0f,
        minSpan: Float = 0.6f,
        maxSpan: Float = 4f,
    ): Frame? {
        if (heads.isEmpty()) return null
        var n = 0f
        val pivot = FloatArray(3)
        for (h in heads) {
            pivot[0] += h[0]; pivot[1] += h[1]; pivot[2] += h[2]
            n += 1f
        }
        pivot[0] /= n; pivot[1] /= n; pivot[2] /= n
        var maxPair = 0f
        for (i in heads.indices) for (j in i + 1 until heads.size) {
            val d = dist(heads[i], heads[j])
            if (d > maxPair) maxPair = d
        }
        val distance = maxOf(minDistance, maxPair.coerceIn(minSpan, maxSpan) * spanScale)
        return Frame(pivot, distance)
    }

    private fun dist(a: FloatArray, b: FloatArray): Float =
        kotlin.math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
}
