package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.corelib.AvatarController
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
 * 圆桌 P2 的会话缝（docs/multi-avatar-feasibility.md §4.4）：roundtableLine
 * 挂末尾 user 前缀槽（viewLine 之后的 roundtableLine 位——离生成最近），不进
 * store；injectUserMessage 只入历史不发起生成。null 时与既有形态逐字节一致。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionRoundtableLineTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingLlm : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            return flowOf(LlmStreamEvent.TextDelta("好的。"))
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

    private fun newSession(
        controller: AvatarController?,
        store: InMemoryConversationStore = InMemoryConversationStore(),
    ): Pair<AvatarSession, RecordingLlm> {
        val llm = RecordingLlm()
        val session = AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = controller,
            AvatarSession.Options(protocolInstructions = false),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = store,
        )
        session.llmConfig = LlmConfig("http://t", "k", model = "llm")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        return session to llm
    }

    @Test
    fun `roundtable line rides the trailing user after the view line`() = runTest {
        val store = InMemoryConversationStore()
        val (session, llm) = newSession(AvatarController(), store)
        session.roundtableLine = "【圆桌】轮到你发言了"
        session.sendAndAwait("接话")
        val last = llm.requests.last().last()
        assertEquals(ChatRole.USER, last.role)
        // 排序 viewLine → roundtableLine → 用户原文（§4.4：越靠后离生成越近）
        val viewIdx = last.content.indexOf("【当前镜头视角】")
        val rtIdx = last.content.indexOf("【圆桌】轮到你发言了")
        assertTrue(viewIdx in 0 until rtIdx)
        assertTrue(last.content.endsWith("接话"))
        // 前缀槽不进 store：历史里只有干净原文（末条 user 是本轮原文）
        assertTrue(store.messages().none { it.content.contains("【圆桌】") })
        assertEquals(
            "接话",
            store.messages().last { it.role == ChatRole.USER }.content,
        )
    }

    @Test
    fun `no roundtable line by default - request shape unchanged`() = runTest {
        val (session, llm) = newSession(AvatarController())
        session.sendAndAwait("你好")
        val last = llm.requests.last().last()
        assertFalse(last.content.contains("【圆桌】"))
        assertTrue(last.content.startsWith("【当前镜头视角】"))
    }

    @Test
    fun `injectUserMessage appends to history without a turn`() = runTest {
        val store = InMemoryConversationStore()
        val (session, llm) = newSession(null, store)
        session.injectUserMessage("【小刚】海边也不错")
        session.injectUserMessage("  ")
        // 只入历史（空白行丢弃），零请求、阶段不动
        assertEquals(1, store.messages().size)
        assertEquals(ChatRole.USER, store.messages().first().role)
        assertEquals("【小刚】海边也不错", store.messages().first().content)
        assertTrue(llm.requests.isEmpty())
        assertEquals(ConversationPhase.IDLE, session.phase.value)
        // 注入的历史进入下一轮请求
        session.sendAndAwait("你怎么看")
        val historyUsers = llm.requests.last().filter { it.role == ChatRole.USER }
        assertTrue(historyUsers.any { it.content == "【小刚】海边也不错" })
    }
}
