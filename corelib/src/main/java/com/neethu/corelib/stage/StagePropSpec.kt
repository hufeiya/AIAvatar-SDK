package com.neethu.corelib.stage

/**
 * 世界静态道具规格（assets 相对路径 + 世界变换）。坐标是最终渲染世界
 * 单位（模型都经 unit-cube 归一，米即画面尺寸）。
 */
data class StagePropSpec(
    val assetPath: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val yawDeg: Float = 0f,
    val scale: Float = 1f,
)
