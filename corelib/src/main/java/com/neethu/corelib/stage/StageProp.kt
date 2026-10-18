package com.neethu.corelib.stage

import com.google.android.filament.gltfio.FilamentAsset

/**
 * 世界静态道具句柄（辩论赛 P0a：座椅等布置，docs/debate-feasibility.md §7.1）。
 * 由 [AvatarStage.loadProp] 创建；渲染实体与销毁归舞台管，句柄只作移除凭证。
 */
class StageProp internal constructor(internal val asset: FilamentAsset) {
    /** 纹理就绪渐进进场是否完成（观测用）。 */
    internal var pending = true

    override fun equals(other: Any?): Boolean = other is StageProp && other.asset == asset
    override fun hashCode(): Int = asset.hashCode()
}
