package com.neethu.corelib.accessory

import com.google.android.filament.Engine
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.ResourceLoader

/**
 * 配饰渲染宿主（P1.5 单人路径桥接的解耦缝，docs/accessory-feasibility.md
 * §5.7）：[AccessoryManager] 原本直接依赖团播路径的 Stage，抽出本接口后
 * 两条渲染路径都能出配饰——
 *
 * - Stage/AvatarRuntime（团播主线，P0 起全量）：Stage 实现本接口，配饰
 *   资产与演员共用 [AssetLoader]（材质 provider 一致）、资源走独立
 *   同步 [ResourceLoader]（与演员异步管线隔离）。
 * - SoulLinkRenderer（单人路径，P1.5）：专用 loader 对（ModelViewer 内部
 *   loader 不公开，场景 GLB 已有同款先例），挂载原理不变——`setParent`
 *   到骨骼实体后经共享 commit 自动跟随动画。
 *
 * 全部方法主线程调用（与 AccessoryManager 同一线程约定）。
 */
internal interface AccessoryHost {

    val accEngine: Engine

    val accScene: Scene

    /** 配饰 GLB 的创建/销毁 loader；null = 渲染未就绪（attach 失败返回）。 */
    val accAssetLoader: AssetLoader?

    /** 配饰资源的同步 loader（与演员/场景管线隔离）。 */
    val accResourceLoader: ResourceLoader?

    /** 配饰字节读取：绝对路径文件优先，回落 assets（对齐 Stage 双通道）。 */
    fun accReadSourceBytes(source: String): ByteArray
}
