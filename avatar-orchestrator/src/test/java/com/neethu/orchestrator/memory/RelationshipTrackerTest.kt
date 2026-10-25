package com.neethu.orchestrator.memory

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.corelib.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关系状态（酒馆 P3-1）：LLM 判定增量（JSON 容错/越界钳制）、分片 scoped、
 * 提示行内容、开关与重置（数据主权）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelationshipTrackerTest {

    private class JsonLlm(private vararg val replies: String) : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()
        private var index = 0

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            val reply = replies[index.coerceAtMost(replies.size - 1)]
            index++
            return flowOf(LlmStreamEvent.TextDelta(reply))
        }
    }

    private class ErrorLlm : LlmAdapter {
        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> = flow {
            emit(LlmStreamEvent.TextDelta("{"))
            throw java.io.IOException("boom")
        }
    }

    private fun newTracker(
        store: RelationshipStore = InMemoryRelationshipStore(),
        llm: LlmAdapter? = null,
        subjectId: String = "",
        today: Long = 1000L,
        lang: Lang = Lang.ZH,
    ): RelationshipTracker = RelationshipTracker(
        store = store,
        scope = CoroutineScope(UnconfinedTestDispatcher()),
        llmProvider = { llm },
        llmConfigProvider = { LlmConfig("http://t", "k", model = "llm") },
        subjectIdProvider = { subjectId },
        lang = lang,
        todayEpochDay = { today },
    )

    @Test
    fun `refresh initializes record with today as anniversary`() = runTest {
        val tracker = newTracker()
        val snapshot = tracker.refresh()
        assertNotNull(snapshot)
        assertEquals(20, snapshot!!.record.favorability)
        assertEquals(1, snapshot.daysTogether)
        assertEquals(RelationshipLevel.ACQUAINTANCE, snapshot.level)
        assertFalse(snapshot.isAnniversaryToday)
        assertEquals(1000L, snapshot.record.anniversaryEpochDay)
    }

    @Test
    fun `prompt line carries stage days and nickname`() = runTest {
        val tracker = newTracker(lang = Lang.ZH)
        tracker.refresh()
        tracker.judgeNow(
            JsonLlm("""{"delta": 3, "nickname": "阿岚"}"""),
            LlmConfig("http://t", "k", model = "llm"),
            "以后我叫你阿岚吧,你就这么称呼我",
            "好呀,阿岚!",
        )
        val line = tracker.promptLine
        assertNotNull(line)
        assertTrue(line!!.contains("熟识"))
        assertTrue(line.contains("阿岚"))
        assertTrue(line.contains("相识"))
    }

    @Test
    fun `judge applies clamped delta and persists`() = runTest {
        val store = InMemoryRelationshipStore()
        val tracker = newTracker(store = store)
        tracker.refresh()
        tracker.judgeNow(
            JsonLlm("""{"delta": 99, "nickname": null}"""),
            LlmConfig("http://t", "k", model = "llm"),
            "你今天真好看",
            "谢谢夸奖",
        )
        // 越界 delta 按无效处理：不落库不动快照
        assertEquals(20, tracker.snapshots.value!!.record.favorability)
        tracker.judgeNow(
            JsonLlm("""{"delta": 2, "nickname": null}"""),
            LlmConfig("http://t", "k", model = "llm"),
            "你今天真好看",
            "谢谢夸奖",
        )
        assertEquals(22, tracker.snapshots.value!!.record.favorability)
        assertEquals(22, store.load("")!!.favorability)
    }

    @Test
    fun `judge survives llm failure and non-json reply`() = runTest {
        val tracker = newTracker()
        tracker.refresh()
        tracker.judgeNow(ErrorLlm(), LlmConfig("http://t", "k", model = "llm"), "你好呀", "你好")
        assertEquals(20, tracker.snapshots.value!!.record.favorability)
        tracker.judgeNow(JsonLlm("我觉得今天聊得不错呢"), LlmConfig("http://t", "k", model = "llm"), "你好呀", "你好")
        assertEquals(20, tracker.snapshots.value!!.record.favorability)
    }

    @Test
    fun `parse helpers tolerate fences and prose`() {
        assertEquals(2, RelationshipTracker.parseDelta("```json\n{\"delta\": 2, \"nickname\": null}\n```"))
        assertEquals(-3, RelationshipTracker.parseDelta("好的{\"delta\": -3}完成"))
        assertNull(RelationshipTracker.parseDelta("没有 json"))
        assertNull(RelationshipTracker.parseDelta("{\"delta\": \"x\"}"))
        assertNull(RelationshipTracker.parseDelta("{\"delta\": 50}")) // 越界无效
        assertEquals("小九", RelationshipTracker.parseNickname("""{"delta": 1, "nickname": " 小九 "}"""))
        assertNull(RelationshipTracker.parseNickname("""{"delta": 1, "nickname": ""}"""))
        assertNull(RelationshipTracker.parseNickname("""{"delta": 1, "nickname": null}"""))
    }

    @Test
    fun `scoped store isolates cards`() = runTest {
        val upstream = InMemoryRelationshipStore()
        val cardA = upstream.scoped("cardA.vrm.png")
        val cardB = upstream.scoped("cardB.png")
        cardA.save(RelationshipRecord(favorability = 55, anniversaryEpochDay = 1, updatedAt = 0))
        assertNull(cardB.load(""))
        assertEquals(55, cardA.load("")!!.favorability)
        // scoped 写入 upstream 时 key 带前缀
        assertNotNull(upstream.load("cardA.vrm.png/"))
        // 清A不影响B
        cardA.save(RelationshipRecord(favorability = 56, anniversaryEpochDay = 1, updatedAt = 0))
        assertTrue(cardA.clear(""))
        assertNull(cardA.load(""))
        cardB.save(RelationshipRecord(favorability = 30, anniversaryEpochDay = 2, updatedAt = 0))
        assertEquals(30, cardB.load("")!!.favorability)
    }

    @Test
    fun `tracker subject switch re-scopes without rebuild`() = runTest {
        var subject = "card1"
        val store = InMemoryRelationshipStore()
        val tracker = newTracker(store = store, subjectId = "card1")
        tracker.refresh()
        tracker.judgeNow(JsonLlm("""{"delta": 3}"""), LlmConfig("http://t", "k", model = "llm"), "第一张卡聊得多", "好")
        assertEquals(23, tracker.snapshots.value!!.record.favorability)

        subject = "card2"
        val tracker2 = RelationshipTracker(
            store = store,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llmProvider = { JsonLlm() },
            llmConfigProvider = { LlmConfig("http://t", "k", model = "llm") },
            subjectIdProvider = { subject },
            todayEpochDay = { 1000L },
        )
        val snapshot = tracker2.refresh()
        assertEquals(20, snapshot!!.record.favorability) // 新卡 = 初识
        assertEquals(23, store.load("card1")!!.favorability) // 旧卡不受扰
    }

    @Test
    fun `min chars gate and disabled flag skip judging`() = runTest {
        val llm = JsonLlm("""{"delta": 2}""")
        val tracker = newTracker(llm = llm)
        tracker.refresh()
        assertNull(tracker.onTurnCompleted("嗯", "哦")) // 太短不判定
        tracker.enabled = false
        assertNull(tracker.onTurnCompleted("这句话足够长了肯定可以触发判定了吧", "够长了"))
        tracker.enabled = true
        assertNotNull(tracker.onTurnCompleted("这句话足够长了肯定可以触发判定了吧", "够长了"))
    }

    @Test
    fun `reset clears record and line`() = runTest {
        val store = InMemoryRelationshipStore()
        val tracker = newTracker(store = store)
        tracker.refresh()
        tracker.judgeNow(JsonLlm("""{"delta": 2}"""), LlmConfig("http://t", "k", model = "llm"), "有点内容的一句话呀", "嗯呢")
        assertNotNull(tracker.promptLine)
        tracker.reset()
        assertNull(tracker.snapshots.value)
        assertNull(tracker.promptLine)
        assertNull(store.load(""))
    }

    @Test
    fun `anniversary detection at 365 multiples`() = runTest {
        val store = InMemoryRelationshipStore()
        store.save(RelationshipRecord(anniversaryEpochDay = 1000L - 730L, updatedAt = 0)) // 恰两周年
        val tracker = newTracker(store = store, today = 1000L)
        val snapshot = tracker.refresh()
        assertEquals(731, snapshot!!.daysTogether)
        assertTrue(snapshot.isAnniversaryToday)
        assertTrue(tracker.promptLine!!.contains("纪念日"))
    }

    @Test
    fun `english prompt line for en locale`() = runTest {
        val tracker = newTracker(lang = Lang.EN)
        tracker.refresh()
        assertTrue(tracker.promptLine!!.startsWith("[Relationship]"))
        assertFalse(tracker.promptLine!!.contains("中文"))
    }
}
