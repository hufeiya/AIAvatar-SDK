package com.neethu.orchestrator.locomotion

import com.neethu.corelib.WalkPhase
import com.neethu.corelib.WalkSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [skipNearFirstLeg]（P0 道具验收②r2 卡死现场的驱动侧预防）：图路由 from
 * 取「全局最近锚点」,人站在途经点旁边时的 0.5m/90° U 型首腿是围目标振荡
 * 不收敛的温床——距首途经点 <[NEAR_FIRST_LEG_SKIP_M] 且有
 * 后续腿时直接从下一腿起步。纯函数锁行为。
 */
class SkipNearFirstLegTest {

    private fun snap(x: Float, z: Float) = WalkSnapshot(
        phase = WalkPhase.WALKING, x = x, z = z, headingDeg = -103f,
        targetX = 0f, targetZ = 0f, speed = 1f,
    )

    private val route = listOf(
        MoveAnchor("aisle_east_lower", 1.8f, -4.7f),
        MoveAnchor("desk_approach", 1.0f, -4.73f),
        MoveAnchor("spawn", -0.1f, -3.35f),
    )

    @Test
    fun `r2 现场——站在首途经点 0_52m 处跳过首腿`() {
        // 实测坐标:人 (1.85,-5.21),首腿 aisle_east_lower (1.80,-4.70),距 0.52m
        val out = skipNearFirstLeg(route, snap(1.85f, -5.21f))
        assertEquals(listOf("desk_approach", "spawn"), out.map { it.name })
    }

    @Test
    fun `距首途经点远（1m+）不跳`() {
        val out = skipNearFirstLeg(route, snap(2.8f, -4.7f))
        assertEquals(route, out)
    }

    @Test
    fun `null 快照（JVM 无渲染器或冷启动）原样返回`() {
        assertEquals(route, skipNearFirstLeg(route, null))
    }

    @Test
    fun `单段路不跳（目标就是唯一腿）`() {
        val single = listOf(MoveAnchor("seat", 1f, 1f))
        assertEquals(single, skipNearFirstLeg(single, snap(1.05f, 1.04f)))
    }

    @Test
    fun `边界值——恰好 0_6m 不跳（严格小于）`() {
        val at = snap(1.8f + 0.6f, -4.7f)
        assertEquals(route, skipNearFirstLeg(route, at))
        val justInside = snap(1.8f + 0.59f, -4.7f)
        assertEquals(listOf("desk_approach", "spawn"), skipNearFirstLeg(route, justInside).map { it.name })
    }
}
