package com.neethu.corelib.stage.internal

/**
 * 站位（spawn）数学（docs/multi-avatar-feasibility.md §4.7）：把 per-avatar
 * 的 spawn 偏移（世界 XZ 平移 + 朝向）合成到模型根矩阵上。
 *
 * 调用时机：unit-cube 归一 + （VRM 0.x 的 Y-180 翻转，若适用）之后——
 * 即 spawn 叠加在「模型最终朝向」之上，两个操作都与走动的 root 写入
 * （rotateRootAroundY + 平移累加）同一套矩阵语义，走动/站位天然兼容。
 *
 * 列主序 4×4，平移在 [12..14]（TransformManager 约定，与
 * SoulLinkRenderer.rotateRootAroundY 同一存储布局）。
 */
internal object SpawnMath {

    /**
     * 把 spawn 合成到 [mat]（就地改写）：
     * 1. 3×3 块左乘 Ry([yawDeg])——绕世界 Y 轴转身，保留 unit-cube 缩放
     *    （与 VRM 0.x 翻转共存：翻转活在矩阵内部，这里再左乘一次）；
     * 2. 平移列 += ([x], [y], [z])（米，世界系）。
     *
     * yawDeg 语义与 WalkSnapshot.headingDeg 一致：0 = 面向 +Z（镜头方向）。
     */
    fun applySpawn(mat: FloatArray, x: Float, y: Float, z: Float, yawDeg: Float) {
        if (yawDeg != 0f) {
            val theta = (yawDeg * Math.PI / 180.0).toFloat()
            val c = kotlin.math.cos(theta)
            val s = kotlin.math.sin(theta)
            for (col in 0..2) {
                val i = col * 4
                val mx = mat[i]
                val mz = mat[i + 2]
                mat[i] = c * mx + s * mz
                mat[i + 2] = -s * mx + c * mz
            }
        }
        mat[12] += x
        mat[13] += y
        mat[14] += z
    }

    /**
     * unit-cube 根变换合成（ModelViewer.transformToUnitCube 的复刻，per-asset
     * 版本——Stage 的多演员各自走自己的 asset，不再有「当前唯一模型」）。
     *
     * 把包围盒中心映到 [centerX/Y/Z]、最长边归一到 2：
     * `root = Scale(s) · Translate(centerPoint − center·s)`，其中
     * `s = 2 / (2·maxHalfExtent)`。纯列主序构造，无旋转 → 3×3 对角。
     */
    fun unitCubeTransform(
        centerX: Float, centerY: Float, centerZ: Float,
        halfExtentX: Float, halfExtentY: Float, halfExtentZ: Float,
        centerPointX: Float = 0f, centerPointY: Float = 0f, centerPointZ: Float = -4f,
    ): FloatArray {
        val maxHalf = maxOf(halfExtentX, halfExtentY, halfExtentZ)
            .coerceAtLeast(1e-6f)
        val s = 1f / maxHalf
        return floatArrayOf(
            s, 0f, 0f, 0f,
            0f, s, 0f, 0f,
            0f, 0f, s, 0f,
            centerPointX - centerX * s,
            centerPointY - centerY * s,
            centerPointZ - centerZ * s,
            1f,
        )
    }
}
