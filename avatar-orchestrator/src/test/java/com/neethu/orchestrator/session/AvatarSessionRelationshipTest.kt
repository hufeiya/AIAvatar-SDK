package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.memory.InMemoryRelationshipStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 关系状态会话级接线（酒馆 P3-1）：tracker 判定行进 system 动态段、手动
 * relationshipLine 兜底、subject 切卡换分片。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionRelationshipTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class ScriptedLlm(vararg val replies: String) : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()
        private var i = 0

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            val reply = replies[i.coerceAtMost(replies.size - 1)]
            i++
            return flowOf(LlmStreamEvent.TextDelta(reply))
        }
    }

    private class SilentTts : TtsAdapter {
        override suspend fun synthesize(text: String, config: TtsConfig): TtsResult =
            TtsResult(ByteArray(64), TtsAudioFormat.RAW_PCM_16LE, 16_000)
    }

    private class RecordingQueue : PlaybackQueue {
        override var listener: PlaybackQueue.Listener? = null
        override fun active(): ActivePlayback? = null
        override fun enqueue(item: PlaybackItem) {
            listener?.onPlaybackStarted(item)
            listener?.onPlaybackEnded(item)
        }
        override fun stopAll(reason: String) = Unit
        override fun release() = Unit
    }

    private fun newSession(llm: LlmAdapter, relationshipStore: InMemoryRelationshipStore? = null): AvatarSession {
        val session = AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = null,
            AvatarSession.Options(protocolInstructions = false),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = InMemoryConversationStore(),
            relationshipStore = relationshipStore,
        )
        session.llmConfig = LlmConfig("http://t", "k", model = "llm")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        return session
    }

    @Test
    fun `manual relationship line rides the system message`() = runTest {
        val llm = ScriptedLlm("好的。")
        val session = newSession(llm)
        session.relationshipLine = "【关系状态】你与用户相识 99 天。"
        session.sendAndAwait("你好")
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("相识 99 天"))
    }

    @Test
    fun `no relationship line by default`() = runTest {
        val llm = ScriptedLlm("好的。")
        val session = newSession(llm)
        session.sendAndAwait("你好")
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertFalse(system.content.contains("关系状态"))
        assertFalse(system.content.contains("Relationship]"))
    }

    @Test
    fun `tracker judged line lands in next request`() = runTest {
        // 首轮回复即带判定结果（同一线程 unconfined,收尾钩子在 TurnCompleted 前跑完）
        val llm = ScriptedLlm("第一答", """{"delta": 2, "nickname": "阿岚"}""")
        val session = newSession(llm, InMemoryRelationshipStore())
        session.sendAndAwait("以后叫你阿岚好不好,这句话够长了")
        val tracker = session.relationship!!
        tracker.awaitJudgeForTest()
        assertEquals(22, tracker.snapshots.value!!.record.favorability)
        session.sendAndAwait("第二问,继续聊天")
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("【关系状态】"))
        assertTrue(system.content.contains("阿岚"))
    }

    @Test
    fun `subject switch re-scopes relationship without rebuild`() = runTest {
        val llm = ScriptedLlm("答", """{"delta": 2}""", "答", """{"delta": 0}""")
        val store = InMemoryRelationshipStore()
        val session = newSession(llm, store)
        session.relationshipSubjectId = "cardA"
        session.refreshRelationship()
        session.sendAndAwait("cardA 聊一句,这句话足够长")
        session.relationship!!.awaitJudgeForTest()
        assertEquals(22, store.load("cardA")!!.favorability)

        session.relationshipSubjectId = "cardB"
        session.refreshRelationship()
        assertEquals(20, session.relationship!!.snapshots.value!!.record.favorability)
        assertEquals(22, store.load("cardA")!!.favorability)
    }

    @Test
    fun `disable stops judging but keeps snapshot`() = runTest {
        val llm = ScriptedLlm("答", """{"delta": 2}""", "答", """{"delta": 2}""")
        val session = newSession(llm, InMemoryRelationshipStore())
        session.sendAndAwait("第一句,这句话足够长了肯定过了门槛")
        session.relationship!!.awaitJudgeForTest()
        assertEquals(22, session.relationship!!.snapshots.value!!.record.favorability)
        session.setRelationshipEnabled(false)
        session.sendAndAwait("第二句,这句话也足够长了肯定过了门槛")
        session.relationship!!.awaitJudgeForTest()
        assertEquals(22, session.relationship!!.snapshots.value!!.record.favorability)
    }
}
