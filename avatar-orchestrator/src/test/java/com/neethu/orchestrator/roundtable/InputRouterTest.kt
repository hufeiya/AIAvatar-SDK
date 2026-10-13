package com.neethu.orchestrator.roundtable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人 ↔ 虚拟人听觉路由（一屏多虚拟人 P1，docs/multi-avatar-feasibility.md §4.6）：
 * @token 点名 / 自然语言点名 / 默认 lastAddressed / 无可路由。
 */
class InputRouterTest {

    private val slots = listOf(
        StageSlotSpec("A", "小柔", aliases = listOf("小柔酱")),
        StageSlotSpec("B", "小刚", aliases = listOf("阿刚")),
    )

    private fun route(text: String, last: String? = null): RoutingDecision =
        InputRouter(slots).route(text, last)

    private fun target(text: String, last: String? = null): RoutingDecision.Target =
        route(text, last) as RoutingDecision.Target

    @Test
    fun `at-token routes to slot and is stripped`() {
        val t = target("@A 你好呀")
        assertEquals("A", t.slotId)
        assertEquals("你好呀", t.cleanedText)
    }

    @Test
    fun `at-token is case insensitive and matches display name`() {
        assertEquals("A", target("@a 早").slotId)
        assertEquals("B", target("@小刚 在吗").slotId)
    }

    @Test
    fun `at-token with trailing punctuation is stripped`() {
        val t = target("@小柔：今天天气如何")
        assertEquals("A", t.slotId)
        assertEquals("今天天气如何", t.cleanedText)
    }

    @Test
    fun `unknown at-token falls through to natural name`() {
        val t = target("@央视 小柔你看")
        assertEquals("A", t.slotId)
    }

    @Test
    fun `natural display name routes without stripping`() {
        val t = target("小柔你好")
        assertEquals("A", t.slotId)
        assertEquals("小柔你好", t.cleanedText)
        assertEquals("小柔", t.matchedBy)
    }

    @Test
    fun `alias name routes`() {
        assertEquals("A", target("小柔酱早上好").slotId)
        assertEquals("B", target("阿刚该你了").slotId)
    }

    @Test
    fun `earliest occurrence wins on multiple matches`() {
        assertEquals("B", target("小刚先说，然后小柔补充").slotId)
        assertEquals("A", target("小柔先说，然后小刚补充").slotId)
    }

    @Test
    fun `longer name preferred on same offset`() {
        // "小柔酱" 与 "小柔" 同起点：更长的称呼赢（避免酱字残留在 cleanedText）
        val t = target("小柔酱好")
        assertEquals("A", t.slotId)
    }

    @Test
    fun `no mention falls back to lastAddressed`() {
        val t = target("你好呀", last = "B")
        assertEquals("B", t.slotId)
        assertEquals("你好呀", t.cleanedText)
    }

    @Test
    fun `unroutable when no mention and no lastAddressed`() {
        assertEquals(RoutingDecision.Unroutable, route("你好"))
    }

    @Test
    fun `unroutable on blank text or empty stage`() {
        assertEquals(RoutingDecision.Unroutable, route("   ", last = "A"))
        assertEquals(
            RoutingDecision.Unroutable,
            InputRouter(emptyList()).route("你好", "A"),
        )
    }

    @Test
    fun `unroutable when lastAddressed left the stage`() {
        assertEquals(RoutingDecision.Unroutable, route("你好", last = "C"))
    }

    @Test
    fun `mention parsing tolerates cjk and stops at whitespace`() {
        assertEquals(listOf("小柔"), InputRouter.mentionTokens("@小柔 你好"))
        assertEquals(listOf("abc"), InputRouter.mentionTokens("前缀@abc。后缀"))
        assertTrue(InputRouter.mentionTokens("没有 token").isEmpty())
    }

    @Test
    fun `strip keeps text when mention is the whole message`() {
        // 整条消息只有点名 → cleanedText 为空时回退原文（send("") 无意义）
        val t = target("@A")
        assertEquals("@A", t.cleanedText)
    }
}
