package com.neethu.corelib.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * hips 拖拽位移逐帧求解的不变量（挪动人物 bug 修复本体）：
 *  1. 动画每帧重写 hips 平移后，位移以新基线重钉——永不逐帧累加；
 *  2. 基线/位移都不变时不重复落盘（帧间无漂移）；
 *  3. restoreRestPose（帧间隙覆写）之后位移仍在；
 *  4. 旋转轨道保留平移的帧不改写位移。
 */
class HipsDragOffsetSolverTest {

    private fun v(x: Float, y: Float, z: Float = 0f) = floatArrayOf(x, y, z)

    @Test
    fun `first frame without offset captures base and writes nothing`() {
        val s = HipsDragOffsetSolver.solve(
            before = v(1f, 2f), current = v(1f, 2f), rewritten = false,
            offset = v(0f, 0f), state = HipsDragOffsetSolver.initial(),
        )
        val (state, target) = s
        assertEquals(v(1f, 2f).toList(), state.base!!.toList())
        assertNull(target)
    }

    @Test
    fun `animation rewrite re-anchors offset on the fresh pose without compounding`() {
        // 第 1 帧：T-pose 静止（hipsY=2），用户已拖出 offset=(0.5, 0)，落盘 base+offset
        val offset = v(0.5f, 0f)
        var state = HipsDragOffsetSolver.initial()
        var result = HipsDragOffsetSolver.solve(v(1f, 2f), v(1f, 2f), false, offset, state)
        state = result.first
        assertEquals(v(1.5f, 2f).toList(), result.second!!.toList())

        // 第 2 帧：待机动画平移轨道把 hips 重写到 (1.1, 1.95)——位移曾被冲掉，
        // 求解必须以新姿态为基线重钉：写出 恰好 base+offset，而不是 base+2*offset
        result = HipsDragOffsetSolver.solve(
            before = v(1f, 2f), current = v(1.1f, 1.95f), rewritten = true, offset = offset, state = state,
        )
        state = result.first
        assertEquals(v(1.1f, 1.95f).toList(), state.base!!.toList())
        assertEquals(v(1.6f, 1.95f).toList(), result.second!!.toList())

        // 第 3 帧：动画继续覆写到 (1.2, 1.9)——依旧恰好 base+offset（核心不变量）
        result = HipsDragOffsetSolver.solve(
            before = v(1.1f, 1.95f), current = v(1.2f, 1.9f), rewritten = false, offset = offset, state = state,
        )
        assertEquals(v(1.7f, 1.9f).toList(), result.second!!.toList())
        assertEquals(v(1.2f, 1.9f).toList(), result.first.base!!.toList())
    }

    @Test
    fun `steady frame with applied offset writes nothing`() {
        // T-pose 静止、位移已落盘（before == current == base+offset）：不写，不漂移
        val offset = v(0.5f, -0.2f)
        val r1 = HipsDragOffsetSolver.solve(
            v(1f, 2f), v(1f, 2f), false, offset, HipsDragOffsetSolver.initial(),
        )
        assertEquals(v(1.5f, 1.8f).toList(), r1.second!!.toList())

        val r2 = HipsDragOffsetSolver.solve(
            before = v(1.5f, 1.8f), current = v(1.5f, 1.8f), rewritten = false,
            offset = offset, state = r1.first,
        )
        assertNull(r2.second)
        assertEquals(v(1f, 2f).toList(), r2.first.base!!.toList())
    }

    @Test
    fun `touch delta in steady t-pose lands exactly once`() {
        // T-pose：第 1 帧无位移捕获基线；触摸增量后第 2 帧落盘一次；第 3 帧不再写
        var offset = v(0f, 0f)
        var state = HipsDragOffsetSolver.initial()
        var (_, target) = HipsDragOffsetSolver.solve(v(1f, 2f), v(1f, 2f), false, offset, state)
        assertNull(target)

        // 触摸：offset += (0.3, 0.4)，状态置脏
        offset = v(0.3f, 0.4f)
        state = HipsDragOffsetSolver.State(state.base, true)
        val r2 = HipsDragOffsetSolver.solve(
            before = v(1f, 2f), current = v(1f, 2f), rewritten = false, offset = offset, state = state,
        )
        state = r2.first
        assertEquals(v(1.3f, 2.4f).toList(), r2.second!!.toList())

        // 第 3 帧：无动画、无触摸——不重复写
        val r3 = HipsDragOffsetSolver.solve(
            before = v(1.3f, 2.4f), current = v(1.3f, 2.4f), rewritten = false, offset = offset, state = state,
        )
        assertNull(r3.second)
    }

    @Test
    fun `restoreRestPose between frames keeps the offset`() {
        // 一次性动作播完且无待机：restoreRestPose 在帧间隙把 hips 打回 rest(1,2)，
        // 引擎信号 rewritten=true——基线重捕获为 rest，位移继续钉上
        val offset = v(0.5f, 0f)
        var state = HipsDragOffsetSolver.State(v(3f, 2f), false) // 动画期间的旧基线
        val (newState, target) = HipsDragOffsetSolver.solve(
            before = v(3.5f, 2f), current = v(1f, 2f), rewritten = true, offset = offset, state = state,
        )
        state = newState
        assertEquals(v(1f, 2f).toList(), state.base!!.toList())
        assertEquals(v(1.5f, 2f).toList(), target!!.toList())
    }

    @Test
    fun `rotation-only frame preserves translation and writes nothing`() {
        // VRMA 只有 hips 旋转轨道：applyRotation 保留平移（含已落盘位移）——
        // before == current，求解不改写（若改写会丢失旋转/平移组合的一致性）
        val offset = v(0.5f, 0f)
        var (state, _) = HipsDragOffsetSolver.solve(
            v(1f, 2f), v(1f, 2f), false, offset, HipsDragOffsetSolver.initial(),
        )
        val (newState, target) = HipsDragOffsetSolver.solve(
            before = v(1.5f, 2f), current = v(1.5f, 2f), rewritten = false, offset = offset, state = state,
        )
        assertNull(target)
        assertEquals(v(1f, 2f).toList(), newState.base!!.toList())
    }

    @Test
    fun `offset from a touch before first frame still lands`() {
        // 理论边界（帧循环先于触摸运行，实际到不了）：首帧即带位移也只落盘一次
        val (state, target) = HipsDragOffsetSolver.solve(
            before = null, current = v(1f, 2f), rewritten = false,
            offset = v(0.2f, 0.1f), state = HipsDragOffsetSolver.initial(),
        )
        assertEquals(v(1.2f, 2.1f).toList(), target!!.toList())
        assertEquals(v(1f, 2f).toList(), state.base!!.toList())
    }
}
