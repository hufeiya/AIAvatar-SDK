package com.neethu.corelib.stage

import com.neethu.corelib.stage.internal.GroupShotMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GROUP_SHOT 取景纯数学（多虚拟人 P3 双人同框）：pivot=头部均值、距离随
 * 头距缩放且有下限（单人兜底）、空名单拒绝。
 */
class GroupShotMathTest {

    @Test
    fun `空名单返回 null`() {
        assertNull(GroupShotMath.frame(emptyList()))
    }

    @Test
    fun `pivot 是头部均值`() {
        val frame = GroupShotMath.frame(
            listOf(floatArrayOf(-1f, 1f, -4f), floatArrayOf(1f, 1.5f, -4f)),
        )
        assertNotNull(frame)
        assertEquals(0f, frame!!.pivot[0], 1e-4f)
        assertEquals(1.25f, frame.pivot[1], 1e-4f)
        assertEquals(-4f, frame.pivot[2], 1e-4f)
    }

    @Test
    fun `距离随头距缩放`() {
        val close = GroupShotMath.frame(
            listOf(floatArrayOf(-0.4f, 1f, -4f), floatArrayOf(0.4f, 1f, -4f)),
        )!!
        val far = GroupShotMath.frame(
            listOf(floatArrayOf(-1.6f, 1f, -4f), floatArrayOf(1.6f, 1f, -4f)),
        )!!
        // 头距 0.8 → span 被 minSpan(0.6~)抬进区间；头距 3.2 → 距离更大
        assertTrue(far.distance > close.distance)
        assertEquals(3.2f * 3.2f, far.distance, 1e-3f)
    }

    @Test
    fun `距离有下限 单人也成立`() {
        val single = GroupShotMath.frame(listOf(floatArrayOf(0f, 1f, -4f)))
        assertNotNull(single)
        assertEquals(3.0f, single!!.distance, 1e-4f)
    }

    @Test
    fun `三人取 pivot 为三点均值`() {
        val frame = GroupShotMath.frame(
            listOf(
                floatArrayOf(0f, 1f, -4f),
                floatArrayOf(1f, 1f, -4f),
                floatArrayOf(2f, 1f, -5f),
            ),
        )!!
        assertEquals(1f, frame.pivot[0], 1e-4f)
        assertEquals(-4.3333f, frame.pivot[2], 1e-3f)
    }
}
