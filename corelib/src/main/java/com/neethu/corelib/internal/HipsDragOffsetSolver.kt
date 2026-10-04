package com.neethu.corelib.internal

/**
 * hips 拖拽位移的逐帧求解（纯函数，JVM 单测覆盖不变量）。
 *
 * 背景（挪动人物 bug）：拖拽语义是平移 humanoid hips 骨骼（three-vrm
 * mouse.html 语义，弹簧骨骼能感知真实身体运动），但 VRMA 平移轨道/内置
 * glTF 动画/restoreRestPose 每帧或停播时把 hips 局部平移整个重写——
 * 触摸时一次性 setTransform 的位移下一帧就被冲掉，只有 T-pose 能拖。
 *
 * 修复：触摸只累加位移；渲染器每帧在动画覆写之后调 [solve]，以「动画刚
 * 写出的平移」为基线叠加位移再落盘。基线检测双信号：
 *  - 帧内对比（before vs current）：覆盖逐帧动画覆写（含内置 glTF 动画）；
 *  - 引擎信号（engineRewritten）：覆盖帧间隙的 restoreRestPose。
 */
internal object HipsDragOffsetSolver {

    /**
     * 跨帧持久状态。[base] = 不含拖拽位移的 hips 局部平移基线；null = 尚未
     * 捕获（首帧/重置后）。[dirty] = 有待落盘的变更（触摸增量或覆写重钉）。
     */
    data class State(val base: FloatArray?, val dirty: Boolean)

    /** 无基线的初始状态。 */
    fun initial(): State = State(null, false)

    /**
     * 求解本帧动作。[before] 本帧动画分支前的 hips 平移快照（null = 未捕获，
     * 如 dragMovesHips 关闭）；[current] 当前平移（动画分支后）；[rewritten]
     * 动画引擎的覆写信号；[offset] 累计拖拽位移（hips 局部系，VRM0 的 X 翻转
     * 已在累加时处理）。
     *
     * 返回新状态与（可选）要写回的 hips 局部平移——target == base + offset。
     * 保证不逐帧累加：基线与位移都不变时不重复落盘。
     */
    fun solve(
        before: FloatArray?,
        current: FloatArray,
        rewritten: Boolean,
        offset: FloatArray,
        state: State,
    ): Pair<State, FloatArray?> {
        var base = state.base
        var dirty = state.dirty

        val frameRewrote = rewritten || (before != null &&
            (current[0] != before[0] || current[1] != before[1] || current[2] != before[2]))
        if (frameRewrote || base == null) {
            // 动画刚重写平移（或首帧捕获）：当前平移即新基线（不含位移）
            base = current.copyOf()
            dirty = if (frameRewrote) {
                true // 覆写后必须重钉位移
            } else {
                // 仅首帧捕获：已有位移才需要落盘
                offset[0] != 0f || offset[1] != 0f || offset[2] != 0f
            }
        }
        if (!dirty || base == null) return State(base, false) to null

        val target = floatArrayOf(
            base[0] + offset[0],
            base[1] + offset[1],
            base[2] + offset[2],
        )
        return State(base, false) to target
    }
}
