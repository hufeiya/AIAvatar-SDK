package com.neethu.corelib.accessory

/**
 * 动画→配饰 绑定状态机（docs/accessory-feasibility.md §5.4 P1）。
 *
 * 显式白名单绑定表（动画剪辑路径 → 配饰 ID，不做名字猜测/通配，对齐团播
 * P2 词边界防误中的教训），以「**当前激活剪辑**」为准驱动显隐，而非事件对：
 *
 * - `onStarted(clip)`：命中绑定 → 确保挂载 + 显示，并**取消同配饰的待隐藏**
 *   （连播两个手机动画不闪烁）；上一激活剪辑的配饰若与本剪辑不同 → 延迟隐藏
 *   （crossfade 窗口内旧的先不摘）。loop 剪辑被顶替时引擎不发 Finished，
 *   本路径就是那条边界的兜底（§八 风险 7）。
 * - `onFinished(clip)`：只认当前激活剪辑的收尾（旧剪辑迟到的 Finished 忽略，
 *   防止新剪辑刚起播就被旧事件藏掉道具），延迟 [hideDelayMs] 隐藏——动画
 *   crossfade（默认 0.25s）期间道具还在手上，淡出完成才消失。
 *
 * 纯逻辑零 Android 依赖：时间调度经 [Scheduler] 注入（生产 = 主线程
 * Handler.postDelayed，单测 = 手动泵），挂/显/隐经 [show]/[hide] 回调注入
 * （生产 = AccessoryManager 自动挂载+显隐，单测 = 录制列表）。
 *
 * 线程约定：全部方法与调度回调同一线程（主线程）调用。
 */
internal class AccessoryAnimationBinder(
    private val hideDelayMs: Long = DEFAULT_HIDE_DELAY_MS,
    private val scheduler: Scheduler,
    private val show: (accessoryId: String) -> Unit,
    private val hide: (accessoryId: String) -> Unit,
) {

    /** 延迟任务句柄（取消 = crossfade 窗口内新事件撤回待隐藏）。 */
    fun interface Cancelable {
        fun cancel()
    }

    /** 时间调度抽象（生产 = Handler；单测 = 手动队列）。 */
    fun interface Scheduler {
        fun post(delayMs: Long, action: () -> Unit): Cancelable
    }

    private val bindings = LinkedHashMap<String, String>()
    private val pendingHides = LinkedHashMap<String, Cancelable>()
    private var activeClip: String? = null

    /**
     * 剪辑路径归一：绑定表写类别相对路径（`07_日常互动/X.vrma`），播放侧可能
     * 带 `animations/` 前缀（STAGE_CMD play 全路径）——键与查询统一剥前缀，
     * 两种写法都命中。
     */
    private fun norm(clipPath: String): String = clipPath.trim().removePrefix(CLIP_PREFIX)

    /** 注册绑定（同剪辑重复注册覆盖；true = 已登记）。 */
    fun bind(animationClipPath: String, accessoryId: String): Boolean {
        val clip = norm(animationClipPath)
        if (clip.isEmpty() || accessoryId.isBlank()) return false
        bindings[clip] = accessoryId
        return true
    }

    /** 解除绑定（剪辑与配饰都匹配才解除；true = 确有此绑定）。 */
    fun unbind(animationClipPath: String, accessoryId: String): Boolean =
        bindings.remove(norm(animationClipPath), accessoryId)

    /** 当前绑定表快照（归一化 clipPath → accessoryId，诊断命令用）。 */
    fun snapshot(): Map<String, String> = bindings.toMap()

    fun onStarted(clipPath: String) {
        val key = norm(clipPath)
        val newId = bindings[key]
        val prevClip = activeClip
        activeClip = key

        // ① 新命中：撤回待隐藏（连播/同配饰换剪辑不闪）+ 确保在场
        if (newId != null) {
            cancelPendingHide(newId)
            show(newId)
        }
        // ② 旧剪辑的配饰与新剪辑不同 → 延迟隐藏（打断/顶替/换配饰的消失路径）
        if (prevClip != null && prevClip != key) {
            val prevId = bindings[prevClip]
            if (prevId != null && prevId != newId) scheduleHide(prevId)
        }
    }

    fun onFinished(clipPath: String) {
        // 只认当前激活剪辑的收尾：旧剪辑迟到的 Finished（剪辑早已被顶替）忽略
        val key = norm(clipPath)
        if (activeClip != key) return
        activeClip = null
        bindings[key]?.let { scheduleHide(it) }
    }

    private fun scheduleHide(accessoryId: String) {
        cancelPendingHide(accessoryId)
        pendingHides[accessoryId] = scheduler.post(hideDelayMs) {
            pendingHides.remove(accessoryId)
            hide(accessoryId)
        }
    }

    private fun cancelPendingHide(accessoryId: String) {
        pendingHides.remove(accessoryId)?.cancel()
    }

    companion object {
        /** 播完/打断后延迟隐藏：对齐 walk crossfade 默认值，淡出窗口内不闪。 */
        const val DEFAULT_HIDE_DELAY_MS = 250L

        /** 绑定/播放两侧统一剥掉的内置动画库前缀。 */
        const val CLIP_PREFIX = "animations/"
    }
}
