package com.neethu.orchestrator.roundtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 对谈位编排纯数学（多虚拟人 P3，docs/multi-avatar-feasibility.md §4.7）：
 * 两人沿「相机→质心」垂直轴排开互相对着（用户在侧）、少走路择向、锚点
 * 优先、多人环布面朝圆心、不足两人不成局。
 */
class StageFormationTest {

    @Test
    fun `不足两人不成局`() {
        assertTrue(StageFormation.plan(emptyMap()).isEmpty())
        assertTrue(
            StageFormation.plan(mapOf("A" to (0f to -4f))).isEmpty(),
        )
    }

    @Test
    fun `两人垂直相机轴排开 互相对着`() {
        // 相机在 +Z (0,0)，两人都在 (0,-4) 附近（spawn 并排叠点也成立）
        val plans = StageFormation.plan(
            positions = mapOf("A" to (0f to -4f), "B" to (0f to -4f)),
            camera = 0f to 0f,
        )
        assertEquals(2, plans.size)
        val a = plans.first { it.slotId == "A" }
        val b = plans.first { it.slotId == "B" }
        // 落点在质心 (0,-4) 两侧、沿 X 轴（相机→质心 = -Z，垂直 = X）
        assertEquals(-0.8f, a.x, 1e-4f)
        assertEquals(0.8f, b.x, 1e-4f)
        assertEquals(-4f, a.z, 1e-4f)
        // 互指对方落点：面朝彼此
        assertEquals(b.x, a.faceX, 1e-4f)
        assertEquals(a.x, b.faceX, 1e-4f)
        assertEquals(-4f, a.faceZ, 1e-4f)
    }

    @Test
    fun `少走路择向`() {
        // A 在左 B 在右：A 应分到左落点（keep 组合）
        val plans = StageFormation.plan(
            positions = mapOf("A" to (-1.5f to -4f), "B" to (1.5f to -4f)),
            camera = 0f to 0f,
        )
        val a = plans.first { it.slotId == "A" }
        val b = plans.first { it.slotId == "B" }
        assertTrue(a.x < b.x)
    }

    @Test
    fun `锚点覆盖推导落点 互指按最终落点`() {
        val plans = StageFormation.plan(
            positions = mapOf("A" to (0f to -4f), "B" to (0f to -4f)),
            anchors = mapOf("A" to (-2f to -4.5f), "B" to (2f to -4.5f)),
            camera = 0f to 0f,
        )
        val a = plans.first { it.slotId == "A" }
        val b = plans.first { it.slotId == "B" }
        assertEquals(-2f, a.x, 1e-4f)
        assertEquals(-4.5f, a.z, 1e-4f)
        // A 面向 B 的锚点落点
        assertEquals(2f, a.faceX, 1e-4f)
        assertEquals(-4.5f, a.faceZ, 1e-4f)
    }

    @Test
    fun `相机在侧向时对谈轴随之旋转`() {
        // 相机在 -X 侧（x=-10）：垂直轴变为 Z——两人沿 Z 排开
        val plans = StageFormation.plan(
            positions = mapOf("A" to (0f to -4f), "B" to (0f to -4f)),
            camera = -10f to -4f,
        )
        val xs = plans.map { it.x }.sorted()
        val zs = plans.map { it.z }.sorted()
        assertEquals(0f, xs[0], 1e-4f)
        assertEquals(0f, xs[1], 1e-4f)
        assertTrue(abs(zs[1] - zs[0]) > 1.5f)
    }

    @Test
    fun `多人环布 面朝其余人均值`() {
        val plans = StageFormation.plan(
            positions = mapOf("A" to (0f to -4f), "B" to (0f to -4f), "C" to (0f to -4f)),
            camera = 0f to 0f,
        )
        assertEquals(3, plans.size)
        // 落点各不相同、到质心距离一致（正三角形）
        val spots = plans.map { it.x to it.z }
        assertEquals(3, spots.map { "${"%.3f".format(it.first)},${"%.3f".format(it.second)}" }.toSet().size)
        val cx = spots.map { it.first }.average()
        val cz = spots.map { it.second }.average()
        spots.forEach { (x, z) ->
            val d = kotlin.math.sqrt((x - cx).let { it * it } + (z - cz).let { it * it })
            assertEquals(0.923f, d.toFloat(), 0.02f) // spacing 1.6 / (2 sin 60°)
        }
        // 每人面朝其余两人的均值 ≈ 质心
        plans.forEach { p ->
            val others = spots.filter { it != (p.x to p.z) }
            assertEquals(others.map { it.first }.average(), p.faceX.toDouble(), 1e-4)
        }
    }

    @Test
    fun `camera 缺省按正前处理`() {
        val plans = StageFormation.plan(
            positions = mapOf("A" to (0f to -4f), "B" to (0f to -4f)),
        )
        // 与显式 (0,0) 相机一致：沿 X 排开
        val a = plans.first { it.slotId == "A" }
        assertEquals(-0.8f, a.x, 1e-4f)
    }
}
