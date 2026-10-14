package com.neethu.corelib

/**
 * VRMA 动画开始播放——舞台路径 [com.neethu.corelib.stage.AvatarHandle.vrmaStarted]
 * 的载荷，[VrmaFinished] 的对称事件（配饰动画联动 P1，docs/accessory-feasibility.md §5.4）。
 *
 * [clipPath] 为加载时的 assets 路径或文件绝对路径（绑定表按此精确匹配）；
 * [loop] 为本次播放是否循环。走动步态/手势 overlay 等内部播放同样发射——
 * 消费方（配饰绑定表）按白名单自行过滤。
 */
data class VrmaStarted(
    val avatarId: String,
    val clipPath: String,
    val loop: Boolean,
)
