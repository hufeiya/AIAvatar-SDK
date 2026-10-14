package com.neethu.corelib.accessory

import kotlinx.coroutines.flow.StateFlow

/**
 * 单个虚拟人的配饰控制器（docs/accessory-feasibility.md §5.5；P1 起含
 * bindToAnimation 动画联动）。
 *
 * 实现（[AccessoryManager]）每个 [com.neethu.corelib.stage.AvatarHandle]
 * 一份：团播 N 个演员各持独立配饰状态与独立 GLB 实例，互不干扰。
 */
interface AccessoryController {

    /**
     * 加载 GLB → 挂到语义骨骼 → 入场景。挂载后配饰自动跟随骨骼动画
     * （VRMA 引擎写骨骼局部变换，共享 commit 传播世界变换，配饰侧零每帧
     * 代码）。同一 [AccessorySpec.id] 重复挂载返回失败（先 detach）。
     *
     * P0 走主线程同步小文件路径（内置资产 <2MB、毫秒级）；大资产转
     * heavyWorkDispatcher 异步化排 P1。
     */
    fun attach(spec: AccessorySpec): Result<Unit>

    /** 卸载并销毁（Scene 摘 renderable → destroyAsset，SIGABRT 纪律同 avatar）。 */
    fun detach(id: String): Boolean

    /** 不卸载只显隐（Scene 增删实现；未挂载返回 false）。 */
    fun setVisible(id: String, visible: Boolean): Boolean

    /**
     * 动画联动：注册「播 [animationClipPath] 期间 [accessoryId] 自动在场」
     * （显式白名单，路径精确匹配）。命中 [VrmaStarted] 时自动挂载（规格由
     * 舞台级解析器给出，sidecar 默认+per-model 校准）并显示；收尾/被打断/
     * 被顶替经 crossfade 窗口（0.25s）隐藏。true = 已登记。
     */
    fun bindToAnimation(accessoryId: String, animationClipPath: String): Boolean

    /** 解除动画绑定（剪辑与配饰都匹配才解除；true = 确有此绑定）。 */
    fun unbindFromAnimation(accessoryId: String, animationClipPath: String): Boolean

    /** 当前绑定表快照（clipPath → accessoryId，诊断/观测用）。 */
    val animationBindings: Map<String, String>

    /** 当前已挂载配饰（调试/观测）。 */
    val accessories: StateFlow<List<AccessoryState>>
}
