package com.neethu.corelib.stage

import com.neethu.corelib.stage.internal.GroupFrameMath
import com.neethu.corelib.stage.internal.GroupShotMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视锥拟合取景数学（辩论赛 P0a，docs/debate-feasibility.md §8/§13.1）：
 * 横屏不同 aspect、九人两排、混合坐站、span 超旧上限仍在视锥内。
 */
class GroupFrameMathTest {

    /** 九人两排：前排名 x=±0.9/±2.0（z=-3.3），后排名 x=±1.05/±2.15（z=-4.5），头高 ~1.55。 */
    private fun nineHeads(): List<FloatArray> = buildList {
        for (x in listOf(0.9f, 2.0f, 1.05f, 2.15f)) {
            add(floatArrayOf(-x, 1.55f, -3.3f))
            add(floatArrayOf(x, 1.55f, -3.3f))
        }
        for (x in listOf(0.9f, 2.0f, 1.05f, 2.15f)) {
            add(floatArrayOf(-x, 1.55f, -4.5f))
            add(floatArrayOf(x, 1.55f, -4.5f))
        }
    }

    private fun nineFittedPoints(): List<FloatArray> = buildList {
        for (p in nineHeads()) {
            add(p)
            add(floatArrayOf(p[0], 0.95f, p[2])) // hips（坐站混合的低点）
            add(floatArrayOf(p[0], 0.05f, p[2])) // 脚底
        }
    }

    @Test
    fun `九人横屏全景距离按视锥拟合且远小于旧头距常数`() {
        val frame = GroupFrameMath.frame(
            points = nineFittedPoints(),
            vFovDeg = 33f,
            aspect = 2400f / 1080f,
        )
        assertNotNull(frame)
        val fitted = frame!!.distance
        // 手算：半宽 2.15+0.4=2.55 / tan(atan(tan16.5°×2.22)) ≈ 3.87m
        assertTrue("fitted=$fitted", fitted in 3.2f..4.6f)
        // 旧算法：头距 4.3 被 cap 到 4 → 4×3.2 = 12.8m（直接怼穿房间后墙）
        val legacy = GroupShotMath.frame(nineHeads())!!.distance
        assertTrue("legacy=$legacy fitted=$fitted", fitted < legacy * 0.5f)
    }

    @Test
    fun `竖屏同构图距离显著大于横屏`() {
        val landscape = GroupFrameMath.frame(nineFittedPoints(), 33f, 2.22f)!!.distance
        val portrait = GroupFrameMath.frame(nineFittedPoints(), 33f, 1080f / 2400f)!!.distance
        assertTrue("landscape=$landscape portrait=$portrait", portrait > landscape * 1.5f)
    }

    @Test
    fun `混合坐站把竖直包围盒计入距离`() {
        // 只给头（站姿高度带）vs 头+脚（坐站混合）：后者竖直半展幅更大
        val headsOnly = GroupFrameMath.frame(nineHeads(), 33f, 2.22f)!!.distance
        val mixed = GroupFrameMath.frame(nineFittedPoints(), 33f, 2.22f)!!.distance
        assertTrue("headsOnly=$headsOnly mixed=$mixed", mixed > headsOnly)
    }

    @Test
    fun `注视点是目标包围盒中心`() {
        val frame = GroupFrameMath.frame(nineFittedPoints(), 33f, 2.22f)!!
        // 九人左右对称 → x 中心为 0
        assertEquals(0f, frame.pivot[0], 1e-3f)
        assertEquals(-3.9f, frame.pivot[2], 0.1f)
    }

    @Test
    fun `单人小目标落到最小距离兜底`() {
        val frame = GroupFrameMath.frame(
            points = listOf(floatArrayOf(0f, 1.5f, -4f), floatArrayOf(0f, 0f, -4f)),
            vFovDeg = 33f,
            aspect = 2.22f,
            minDistance = 2.5f,
        )
        assertNotNull(frame)
        assertTrue(frame!!.distance >= 2.5f)
    }

    @Test
    fun `空目标返回null`() {
        assertNull(GroupFrameMath.frame(emptyList(), 33f, 2.22f))
    }
}
