package com.neethu.orchestrator.autonomy

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM 自发决策增强缝（P2 §7-P2.3,docs/prop-system-feasibility.md §3.2;
 * autonomy-feasibility.md §4.2）：白名单双向校验（清单外=失败回落/空=保持）、
 * prompt 装载上下文与清单、低频门、统计、ask 全链（假 LLM/超时/错误输出）。
 * 缝默认关,这里只锁缝本体的行为契约。
 */
class AutonomyDecisionEnhancerTest {

    private val choices = listOf(
        AutonomyDecisionChoice("reading", "窝在椅上看书", "坐阅读椅看书", "daily"),
        AutonomyDecisionChoice("desk_typing", "书桌打字", "坐书桌打字", "work"),
        AutonomyDecisionChoice("tea_time", "对坐喝茶", "端杯茶闲聊", "social"),
    )

    private fun ctx(
        state: String = "SITTING",
        anchor: String? = "reading_seat",
        current: String? = null,
        memory: String? = "用户最近在写小说",
        recent: String? = null,
    ) = AutonomyDecisionContext(
        state = state, anchorId = anchor, currentActivityId = current, hour = 14,
        activities = choices, memoryLine = memory, recentLine = recent,
    )

    /** 假 LLM：按脚本回一段文本（收集请求备查）。 */
    private class FakeLlm(var reply: String, var error: Throwable? = null) : LlmAdapter {
        var lastMessages: List<ChatMessage> = emptyList()
        var calls = 0

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            calls++
            lastMessages = messages
            val err = error
            return if (err != null) flow { throw err } else flowOf(LlmStreamEvent.TextDelta(reply))
        }
    }

    private fun enhancer(llm: FakeLlm, now: () -> Long = { 0L }) =
        AutonomyDecisionEnhancer(
            llm = { llm },
            config = { LlmConfig(baseUrl = "http://x", apiKey = "k", model = "m") },
            nowMs = now,
        )

    // ── parseAnswer（白名单双向校验）─────────────────────────────────────

    @Test
    fun `parseAnswer 命中白名单大小写不敏感`() {
        val e = enhancer(FakeLlm(""))
        val d = e.parseAnswer("""{"activity":"Tea_Time","reason":"想喝茶"}""", choices.map { it.id })!!
        assertEquals("tea_time", d.activityId)
        assertEquals("想喝茶", d.reason)
    }

    @Test
    fun `parseAnswer 空串为保持现状`() {
        val e = enhancer(FakeLlm(""))
        val d = e.parseAnswer("""{"activity":"","reason":"保持现状"}""", choices.map { it.id })!!
        assertNull(d.activityId)
    }

    @Test
    fun `parseAnswer 清单外id与非JSON都返回null`() {
        val e = enhancer(FakeLlm(""))
        assertNull(e.parseAnswer("""{"activity":"fly_to_moon","reason":"x"}""", choices.map { it.id }))
        assertNull(e.parseAnswer("好的,她想去读书", choices.map { it.id }))
        assertNull(e.parseAnswer("""{"activity":123}""", choices.map { it.id }))
    }

    @Test
    fun `parseAnswer 容忍JSON围栏与客套话`() {
        val e = enhancer(FakeLlm(""))
        val d = e.parseAnswer(
            "好的,裁决如下：\n```json\n{\"activity\":\"reading\",\"reason\":\"午后合适\"}\n```",
            choices.map { it.id },
        )!!
        assertEquals("reading", d.activityId)
    }

    // ── buildMessages（上下文与清单装载）────────────────────────────────

    @Test
    fun `buildMessages 装载状态时段记忆与活动清单`() {
        val llm = FakeLlm("")
        val e = enhancer(llm)
        val messages = e.buildMessages(ctx(recent = "刚聊过写作"))
        assertEquals(2, messages.size)
        assertEquals(ChatRole.SYSTEM, messages[0].role)
        assertTrue(messages[0].content.contains("JSON"))
        val user = messages[1].content
        assertTrue(user.contains("SITTING"))
        assertTrue(user.contains("reading_seat"))
        assertTrue(user.contains("14 点"))
        assertTrue(user.contains("用户最近在写小说"))
        assertTrue(user.contains("刚聊过写作"))
        assertTrue(user.contains("- reading 窝在椅上看书[daily]"))
        assertTrue(user.contains("tea_time"))
    }

    @Test
    fun `buildMessages 无记忆无对话不出现空槽位`() {
        val llm = FakeLlm("")
        val user = enhancer(llm).buildMessages(ctx(memory = null, recent = null))[1].content
        assertFalse(user.contains("记忆:"))
        assertFalse(user.contains("刚聊过:"))
    }

    // ── 低频门与统计 ─────────────────────────────────────────────────────

    @Test
    fun `低频门按注入时钟判定`() = runTest {
        var now = 0L
        val llm = FakeLlm("""{"activity":"","reason":"ok"}""")
        val e = enhancer(llm, now = { now })
        assertTrue("初始可问", e.dueToAsk())
        // ask 落地记 lastAskAtMs=now=0,门限内不再可问
        e.ask(ctx())
        now = AutonomyDecisionEnhancer.DEFAULT_MIN_INTERVAL_MS - 1
        assertFalse("门限内不可问", e.dueToAsk())
        now = AutonomyDecisionEnhancer.DEFAULT_MIN_INTERVAL_MS.toLong()
        assertTrue("到点可问", e.dueToAsk())
        e.forceDue()
        assertTrue("forceDue 立即可问", e.dueToAsk())
    }

    // ── ask 全链 ─────────────────────────────────────────────────────────

    @Test
    fun `ask 换活动计入overrides且返回带时延`() = runTest {
        val llm = FakeLlm("""{"activity":"tea_time","reason":"想喝茶"}""")
        val e = enhancer(llm)
        val d = e.ask(ctx())!!
        assertEquals("tea_time", d.activityId)
        assertEquals(1, e.overrides)
        assertEquals(0, e.keeps)
        assertEquals(1, e.asks)
        assertEquals(1, llm.calls)
        assertNotNull(d.reason, d.reason)
    }

    @Test
    fun `ask 保持现状计入keeps`() = runTest {
        val e = enhancer(FakeLlm("""{"activity":"","reason":"现在挺好"}"""))
        val d = e.ask(ctx(current = "reading"))!!
        assertNull(d.activityId)
        assertEquals(1, e.keeps)
    }

    @Test
    fun `ask 垃圾输出计失败返回null`() = runTest {
        val e = enhancer(FakeLlm("我不想回答"))
        assertNull(e.ask(ctx()))
        assertEquals(1, e.fails)
    }

    @Test
    fun `ask LLM抛错计失败返回null不外泄异常`() = runTest {
        val llm = FakeLlm("", error = IllegalStateException("network down"))
        val e = enhancer(llm)
        assertNull(e.ask(ctx()))
        assertEquals(1, e.fails)
    }

    @Test
    fun `ask 缺Key计失败返回null`() = runTest {
        val e = AutonomyDecisionEnhancer(
            llm = { FakeLlm("") },
            config = { null },
        )
        assertNull(e.ask(ctx()))
        assertEquals(1, e.fails)
    }

    @Test
    fun `ask 空清单直接拒绝不发请求`() = runTest {
        val llm = FakeLlm("")
        val e = enhancer(llm)
        assertNull(e.ask(ctx().copy(activities = emptyList())))
        assertEquals(0, llm.calls)
        assertEquals(0, e.asks)
    }

    // ── lastError 失败归因（验收 r1:401 被吞进 fails,归因全靠猜）─────────

    @Test
    fun `成功后lastError清空_失败后带白名单拒绝与原文`() = runTest {
        val e = enhancer(FakeLlm("""{"activity":"tea_time"}"""))
        assertNotNull(e.ask(ctx()))
        assertNull("成功路径不留错误", e.lastError)
        e.ask(ctx().copy(activities = listOf(choices[0])))
        assertTrue(
            "白名单拒绝带原文: ${e.lastError}",
            e.lastError!!.startsWith("输出未过白名单") && e.lastError!!.contains("tea_time"),
        )
    }

    @Test
    fun `LLM异常消息进lastError（HTTP 401 形态）`() = runTest {
        val llm = FakeLlm("", error = IllegalStateException("LLM HTTP 401: 无效令牌"))
        val e = enhancer(llm)
        assertNull(e.ask(ctx()))
        assertTrue("lastError=${e.lastError}", e.lastError!!.contains("HTTP 401"))
        assertTrue("stateLine 带原因: ${e.stateLine()}", e.stateLine().contains("HTTP 401"))
    }

    @Test
    fun `超时记入lastError且与请求内异常可辨`() = runTest {
        val hanging = object : LlmAdapter {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig) =
                flow<LlmStreamEvent> { awaitCancellation() }
        }
        val e = AutonomyDecisionEnhancer(
            llm = { hanging },
            config = { LlmConfig(baseUrl = "http://x", apiKey = "k", model = "m") },
        )
        e.timeoutMs = 5L
        assertNull(e.ask(ctx()))
        assertTrue("lastError=${e.lastError}", e.lastError!!.startsWith("超时>"))
    }

    @Test
    fun `缺Key的lastError可辨`() = runTest {
        val e = AutonomyDecisionEnhancer(llm = { FakeLlm("") }, config = { null })
        assertNull(e.ask(ctx()))
        assertTrue(e.lastError!!.contains("缺Key"))
    }
}
