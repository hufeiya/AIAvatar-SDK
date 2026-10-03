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
import com.neethu.orchestrator.history.ConversationStore
import com.neethu.orchestrator.history.InMemoryConversationStore
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 任务 3：历史裁剪（Options.recentTurnLimit）与 store 注入。
 * system 消息每轮现拼、恒置顶且不受裁剪影响；store 里只有 user/assistant。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionTrimTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 记录每次请求的 messages，回复固定一句。 */
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
            // 立即走完 started→ended，让 pipeline 的本轮等待立即收敛
            listener?.onPlaybackStarted(item)
            listener?.onPlaybackEnded(item)
        }

        override fun stopAll(reason: String) = Unit
        override fun release() = Unit
    }

    /** store 预置 1..N 共 N 条 user/assistant 交替历史。 */
    private fun seededStore(count: Int): ConversationStore =
        InMemoryConversationStore(maxMessages = 1000).apply {
            repeat(count) { i ->
                if (i % 2 == 0) appendUser("u$i") else appendAssistant("a$i")
            }
        }

    private lateinit var llm: RecordingLlm

    private fun newSession(store: ConversationStore, recentTurnLimit: Int?): AvatarSession {
        llm = RecordingLlm()
        return AvatarSession(
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = null,
            AvatarSession.Options(recentTurnLimit = recentTurnLimit),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = store,
        )
    }

    private suspend fun AvatarSession.sendAndGetRequest(text: String): List<ChatMessage> {
        llmConfig = LlmConfig("http://t", "k", model = "llm")
        ttsConfig = TtsConfig(model = "tts", voice = "v")
        sendAndAwait(text)
        return llm.requests.last()
    }

    @Test
    fun `recentTurnLimit keeps only the last N history messages`() = runTest {
        // send 先把本轮 user 入 store，再组装请求：6 条预置 + "hello" = 7 条
        val session = newSession(seededStore(6), recentTurnLimit = 4)
        val sent = session.sendAndGetRequest("hello")

        val nonSystem = sent.filter { it.role != ChatRole.SYSTEM }
        assertEquals(listOf("a3", "u4", "a5", "hello"), nonSystem.map { it.content })
        // 本轮 user 消息在队尾
        assertEquals("hello", nonSystem.last().content)
        assertEquals(ChatRole.USER, nonSystem.last().role)
    }

    @Test
    fun `system message is always first and unaffected by trimming`() = runTest {
        val session = newSession(seededStore(6), recentTurnLimit = 2)
        val sent = session.sendAndGetRequest("hello")

        assertEquals(ChatRole.SYSTEM, sent.first().role)
        assertTrue(sent.first().content.isNotEmpty())
        val nonSystem = sent.filter { it.role != ChatRole.SYSTEM }
        assertEquals(listOf("a5", "hello"), nonSystem.map { it.content })
    }

    @Test
    fun `history shorter than the limit is sent in full`() = runTest {
        val session = newSession(seededStore(4), recentTurnLimit = 8)
        val sent = session.sendAndGetRequest("hello")

        val nonSystem = sent.filter { it.role != ChatRole.SYSTEM }
        assertEquals(listOf("u0", "a1", "u2", "a3", "hello"), nonSystem.map { it.content })
    }

    @Test
    fun `null limit sends the full history`() = runTest {
        val session = newSession(seededStore(6), recentTurnLimit = null)
        val sent = session.sendAndGetRequest("hello")

        // 6 条历史 + 本轮 1 条
        assertEquals(7, sent.size - 1)
        assertEquals("u0", sent.first { it.role != ChatRole.SYSTEM }.content)
    }

    @Test
    fun `no history and only the system message`() = runTest {
        val session = newSession(seededStore(0), recentTurnLimit = 4)
        val sent = session.sendAndGetRequest("hello")

        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), sent.map { it.role })
    }

    @Test
    fun `injected store records the turn after completion`() = runTest {
        val store = InMemoryConversationStore()
        val session = newSession(store, recentTurnLimit = null)

        session.sendAndGetRequest("你好")

        val recorded = store.messages()
        assertEquals(
            listOf(ChatRole.USER to "你好", ChatRole.ASSISTANT to "好的。"),
            recorded.map { it.role to it.content },
        )
    }
}
