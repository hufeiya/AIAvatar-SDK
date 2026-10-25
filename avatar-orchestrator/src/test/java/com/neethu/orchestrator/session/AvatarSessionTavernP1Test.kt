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
import com.neethu.orchestrator.card.CharacterCard
import com.neethu.orchestrator.card.Lorebook
import com.neethu.orchestrator.card.LorebookEntry
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
 * 酒馆 P1 会话级注入（docs/tavern-companion-feasibility.md）：世界书命中
 * 进 system 末尾（P1-1）、mes_example few-shot 在 system 之后历史之前、
 * post_history_instructions 挂末条 user 前缀（P1-3）、importHistory 整体
 * 替换历史（P1-4）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionTavernP1Test {

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

    private fun newSession(): Pair<AvatarSession, RecordingLlm> {
        val llm = RecordingLlm()
        val session = AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = null,
            AvatarSession.Options(protocolInstructions = false),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = InMemoryConversationStore(),
        )
        session.llmConfig = LlmConfig("http://t", "k", model = "llm")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        return session to llm
    }

    private val book = Lorebook(
        name = "test",
        entries = listOf(
            LorebookEntry(keys = listOf("王冠"), content = "王冠是王国的至宝。"),
            LorebookEntry(keys = listOf("不可语之"), content = "常驻背景。", constant = true),
        ),
    )

    @Test
    fun `keyword hit injects lore into system`() = runTest {
        val (session, llm) = newSession()
        session.lorebook = book
        session.sendAndAwait("王冠在哪里？")
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("【世界设定】"))
        assertTrue(system.content.contains("王冠是王国的至宝。"))
        // 常驻条目即使无关键词也恒注入
        assertTrue(system.content.contains("常驻背景。"))
    }

    @Test
    fun `no keyword - only constant entry rides along`() = runTest {
        val (session, llm) = newSession()
        session.lorebook = book
        session.sendAndAwait("今天天气如何？")
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("常驻背景。"))
        assertFalse(system.content.contains("王冠是王国的至宝。"))
    }

    @Test
    fun `lorebookEnabled=false suppresses everything`() = runTest {
        val (session, llm) = newSession()
        session.lorebook = book
        session.lorebookEnabled = false
        session.sendAndAwait("王冠在哪里？")
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertFalse(system.content.contains("世界设定"))
    }

    @Test
    fun `mes_example becomes few-shot between system and history`() = runTest {
        val (session, llm) = newSession()
        session.setCharacterCard(
            CharacterCard(
                name = "Aria",
                description = "mage",
                messageExample = "<START>\n{{char}}: Hello there.\n{{user}}: Hi!",
            ),
        )
        session.sendAndAwait("第一问")
        val req = llm.requests.last()
        assertEquals(ChatRole.SYSTEM, req[0].role)
        // few-shot 恰好卡在 system 与历史之间
        assertEquals(ChatRole.ASSISTANT, req[1].role)
        assertEquals("Hello there.", req[1].content)
        assertEquals(ChatRole.USER, req[2].role)
        assertEquals("Hi!", req[2].content)
        assertEquals(ChatRole.USER, req[3].role) // 历史（当前输入）
        assertEquals("第一问", req[3].content)
        // few-shot 不进 store：第二轮请求里历史只有两轮对话
        session.sendAndAwait("第二问")
        val second = llm.requests.last()
        val historyTurns = second.dropWhile { it.role == ChatRole.SYSTEM }.drop(2)
        assertEquals(3, historyTurns.size) // 第一问 + 第一答 + 第二问
    }

    @Test
    fun `post_history_instructions rides the trailing user message`() = runTest {
        val (session, llm) = newSession()
        session.setCharacterCard(
            CharacterCard(
                name = "Aria",
                postHistoryInstructions = "Stay in character as {{char}}.",
            ),
        )
        session.sendAndAwait("你好")
        val last = llm.requests.last().last()
        assertEquals(ChatRole.USER, last.role)
        assertTrue(last.content.startsWith("Stay in character as Aria."))
        assertTrue(last.content.endsWith("你好"))
    }

    @Test
    fun `importHistory replaces the whole context`() = runTest {
        val (session, llm) = newSession()
        session.sendAndAwait("旧对话")
        assertEquals(2, session.historyMessages().size)
        session.importHistory(
            listOf(
                ChatMessage(ChatRole.USER, "很久以前的问"),
                ChatMessage(ChatRole.ASSISTANT, "很久以前的答"),
                ChatMessage(ChatRole.USER, "接上：我们刚才说到哪了？"),
            ),
        )
        assertEquals(3, session.historyMessages().size)
        session.sendAndAwait("继续")
        val req = llm.requests.last()
        val users = req.filter { it.role == ChatRole.USER && !it.content.contains("视角") }
        assertTrue(users.any { it.content.contains("很久以前的问") })
        assertTrue(users.any { it.content.endsWith("继续") })
        assertFalse(req.any { it.content.contains("旧对话") })
    }

    @Test
    fun `setCharacterCard resets lorebook to embedded - switch leak-free`() = runTest {
        val (session, _) = newSession()
        session.lorebook = Lorebook(entries = listOf(LorebookEntry(keys = listOf("旧"), content = "旧书")))
        session.setCharacterCard(
            CharacterCard(name = "B", characterBook = Lorebook(entries = listOf(LorebookEntry(keys = listOf("新"), content = "新书")))),
        )
        assertEquals("新书", session.lorebook!!.entries.single().content)
        // 无内嵌书的卡 = 清书（app 的全局绑定随后由库覆盖）
        session.setCharacterCard(CharacterCard(name = "C"))
        assertEquals(null, session.lorebook)
    }
}
