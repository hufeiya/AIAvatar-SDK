package com.neethu.corelib.stage.internal

import com.neethu.corelib.internal.VrmaParser.VrmaAnimation

/**
 * 舞台级 VRMA 只读解析缓存（docs/multi-avatar-feasibility.md §3.1「必须补的
 * per-avatar 能力」）：`source → VrmaAnimation`，N 个 runtime 挂同一 idle /
 * 同一步态时省 N-1 份解析与内存。
 *
 * 正确性依据：[VrmaAnimation] 的解析产物是「归一化空间」数据（旋转在
 * rest 逆空间内归一，见 VrmaParser 头注释），与具体模型无关——同一文件
 * 解析一次即可被任意多个 [com.neethu.corelib.internal.VrmaAnimationEngine]
 * 只读共享。引擎侧对动画只采样不改写（混合权重等状态都在引擎实例里）。
 *
 * 本类是**纯记忆化器**：parse 委托给调用方注入的 [getOrParse] 第二参
 * （assets / 文件读取与 VrmaParser 归舞台所有），缓存语义（同源复用、
 * 异源隔离、失败负缓存、clear 重试）可 JVM 单测。
 *
 * 线程模型：load 可能从后台预处理线程发生（异步加载），全部方法
 * synchronized。缓存为只读设计——无失效逻辑，[clear] 仅随舞台销毁释放
 * 内存（纯数据对象，无 GPU 资源）。
 */
internal class VrmaClipCache {

    private val cache = HashMap<String, VrmaAnimation?>()

    /** 已缓存条目数（诊断，含负缓存）。 */
    val size: Int get() = synchronized(this) { cache.size }

    /**
     * 取（或解析并缓存）[source] 对应的动画。
     *
     * @param parse 读源并解析；抛异常或返回 null 都算失败（负缓存——坏文件
     *   不反复 parse 开销，[clear] 后重试可恢复）。
     * @return 共享实例（调用方不得改写）；null = 解析失败。
     */
    fun getOrParse(source: String, parse: (String) -> VrmaAnimation?): VrmaAnimation? {
        synchronized(this) {
            if (cache.containsKey(source)) return cache[source]
            val animation = try {
                parse(source)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "VRMA load failed: $source", e)
                null
            }
            if (animation == null) {
                android.util.Log.e(TAG, "VRMA parse failed: $source")
            }
            cache[source] = animation
            return animation
        }
    }

    /** 丢弃全部缓存（舞台销毁时；纯数据，无 GPU 资源需要释放）。 */
    fun clear() {
        synchronized(this) { cache.clear() }
    }

    companion object {
        private const val TAG = "VrmaClipCache"
    }
}
