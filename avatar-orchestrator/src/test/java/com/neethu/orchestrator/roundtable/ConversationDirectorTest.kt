package com.neethu.orchestrator.roundtable

import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.api.LlmAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 圆桌导演（一屏多虚拟人 P2，docs/multi-avatar-feasibility.md §4.3）：规则档
 * 全分支（开题/轮转/插话点名优先/单槽连续上限）、LLM 档解析（垃圾输出→回退
 * 规则档、连续授予禁止）、复读检测纯函数。纯 JVM。
 */
class ConversationDirectorTest {

    private val slots = listOf(
        StageSlotSpec("A", "小柔", intro = "活泼可爱的女孩"),
        StageSlotSpec("B", "小刚", intro = "沉稳踏实的工程师"),
        StageSlotSpec("C", "小暖", intro = "温柔的医生"),
    )

    private fun ctx(
        lastSpeaker: String? = null,
        lastReply: String = "",
        round: Int = 0,
        pending: String? = null,
        lastAddressed: String? = null,
        consecutive: Int = 1,
        recent: List<RoundtableLine> = emptyList(),
        slotList: List<StageSlotSpec> = slots,
    ) = RoundtableContext(
        topic = "周末去哪玩",
        round = round,
        maxRounds = 20,
        slots = slotList,
        lastSpeakerId = lastSpeaker,
        lastReply = lastReply,
        recentLines = recent,
        consecutiveCount = consecutive,
        pendingUserLine = pending,
        lastAddressedId = lastAddressed,
    )

    // ── 规则档 ────────────────────────────────────────────────────────────

    @Test
    fun `opening grants lastAddressed first`() = runTest {
        val d = RuleBasedDirector(DirectorPolicy.Rule())
        val decision = d.decide(ctx(lastAddressed = "B"))
        assertEquals(FloorDecision.Grant("B", "开题：最后对话对象优先"), decision)
    }

    @Test
    fun `opening without lastAddressed falls to first slot`() = runTest {
        val d = RuleBasedDirector(DirectorPolicy.Rule())
        assertEquals(
            FloorDecision.Grant("A", "开题：最后对话对象优先"),
            d.decide(ctx()),
        )
    }

    @Test
    fun `opening on empty stage ends`() = runTest {
        val d = RuleBasedDirector(DirectorPolicy.Rule())
        assertEquals(FloorDecision.End::class, d.decide(ctx(slotList = emptyList()))::class)
    }

    @Test
    fun `regular turns rotate excluding the last speaker`() = runTest {
        val d = RuleBasedDirector(DirectorPolicy.Rule())
        assertEquals(FloorDecision.Grant("B", "轮流发言"), d.decide(ctx(lastSpeaker = "A")))
        assertEquals(FloorDecision.Grant("C", "轮流发言"), d.decide(ctx(lastSpeaker = "B")))
        // 3 人轮转回到 A（声明序第一个非上一发言者）
        assertEquals(FloorDecision.Grant("A", "轮流发言"), d.decide(ctx(lastSpeaker = "C")))
    }

    @Test
    fun `pending interjection prefers named slot that is not the last speaker`() = runTest {
        val d = RuleBasedDirector(DirectorPolicy.Rule())
        assertEquals(
            FloorDecision.Grant("C", "回应用户插话"),
            d.decide(ctx(lastSpeaker = "A", pending = "小暖怎么看", lastAddressed = "C")),
        )
        // 未点名 → 轮换到下一位
        assertEquals(
            FloorDecision.Grant("B", "回应用户插话"),
            d.decide(ctx(lastSpeaker = "A", pending = "你们觉得呢")),
        )
    }

    @Test
    fun `single slot continues until consecutive limit then ends`() = runTest {
        val d = RuleBasedDirector(DirectorPolicy.Rule(maxConsecutive = 2))
        val solo = listOf(StageSlotSpec("A", "小柔"))
        assertEquals(
            FloorDecision.Grant("A", "唯一发言者继续"),
            d.decide(ctx(lastSpeaker = "A", consecutive = 1, slotList = solo)),
        )
        assertEquals(
            FloorDecision.End::class,
            d.decide(ctx(lastSpeaker = "A", consecutive = 2, slotList = solo))::class,
        )
    }

    // ── LLM 档解析 ────────────────────────────────────────────────────────

    private fun director(reply: String): LlmDirector {
        val llm = object : LlmAdapter {
            override fun streamChat(
                messages: List<ChatMessage>,
                config: LlmConfig,
            ): Flow<LlmStreamEvent> = flow {
                emit(LlmStreamEvent.TextDelta(reply))
                emit(LlmStreamEvent.Finish(null, null, emptyList()))
            }
        }
        return LlmDirector(llm, { LlmConfig("http://d", "k", model = "m") }, DirectorPolicy.Llm(llm, { null }))
    }

    @Test
    fun `llm parse grants named slot`() = runTest {
        val d = director("好的。{\"next\":\"B\",\"reason\":\"B 更懂徒步\"} 完毕")
        assertEquals(
            FloorDecision.Grant("B", "B 更懂徒步"),
            d.decide(ctx(lastSpeaker = "A")),
        )
    }

    @Test
    fun `llm parse accepts END and USER`() = runTest {
        val d = director("{\"next\":\"END\",\"reason\":\"聊尽了\"}")
        assertEquals(FloorDecision.End("聊尽了"), d.decide(ctx(lastSpeaker = "A")))
        assertEquals(FloorDecision.ToUser, director("{\"next\":\"USER\"}").decide(ctx(lastSpeaker = "A")))
    }

    @Test
    fun `llm parse rejects garbage unknown slot and consecutive grant`() = runTest {
        val c = ctx(lastSpeaker = "A")
        assertNull(director("我觉得该小刚接话").parse("我觉得该小刚接话", c))
        assertNull(director("{\"next\":\"D\"}").parse("{\"next\":\"D\"}", c))
        assertNull(director("{\"next\":\"A\"}").parse("{\"next\":\"A\"}", c)) // 连续授予被禁
        assertNull(director("{\"next\": 42}").parse("{\"next\": 42}", c))
    }

    @Test
    fun `llm failure falls back to rule rotation`() = runTest {
        // 无配置（config 返回 null）→ 回退规则档：A 说完轮 B
        val llm = object : LlmAdapter {
            override fun streamChat(
                messages: List<ChatMessage>,
                config: LlmConfig,
            ): Flow<LlmStreamEvent> = flow { emit(LlmStreamEvent.Finish(null, null, emptyList())) }
        }
        val d = LlmDirector(llm, { null }, DirectorPolicy.Llm(llm, { null }))
        assertEquals(FloorDecision.Grant("B", "轮流发言"), d.decide(ctx(lastSpeaker = "A")))
    }

    @Test
    fun `llm garbage output falls back to rule rotation`() = runTest {
        val d = director("（想不出来）")
        assertEquals(FloorDecision.Grant("B", "轮流发言"), d.decide(ctx(lastSpeaker = "A")))
    }

    // ── 复读检测 ──────────────────────────────────────────────────────────

    @Test
    fun `repeat detection matches exact and near-duplicate text`() {
        assertTrue(DialogueGuards.isRepeat("今天天气真不错啊", listOf("今天天气真不错啊")))
        // bigram 高相似（微调两个字）
        assertTrue(DialogueGuards.isRepeat("今天天气真不错啊", listOf("今天天气真心不错啊")))
        assertFalse(DialogueGuards.isRepeat("我觉得去山里徒步挺好玩", listOf("今天天气真不错啊")))
        // 过短不判复读（客套话不算失控）
        assertFalse(DialogueGuards.isRepeat("嗯嗯", listOf("嗯嗯")))
        assertFalse(DialogueGuards.isRepeat("我也是这么想的", emptyList()))
    }

    @Test
    fun `bigram jaccard sanity`() {
        val a = DialogueGuards.bigrams(DialogueGuards.normalized("去山里徒步"))
        assertEquals(1f, DialogueGuards.jaccard(a, a))
        assertTrue(DialogueGuards.jaccard(a, emptySet()) < 0.5f)
    }
}
