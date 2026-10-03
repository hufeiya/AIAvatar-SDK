package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
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
 * [AvatarSession.speak] — the no-LLM speech path used for card greetings:
 * sentences reach the playback queue through the normal pipeline, the LLM is
 * never touched, and the phase round-trips IDLE→SPEAKING→IDLE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionSpeakTest {

    @Before
    fun setUp() {
        // AvatarSession's playback callbacks hop via Dispatchers.Main.immediate
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingQueue : PlaybackQueue {
        override var listener: PlaybackQueue.Listener? = null
        val played = mutableListOf<PlaybackItem>()
        var stopCalls = 0
            private set

        override fun active(): ActivePlayback? = null

        override fun enqueue(item: PlaybackItem) {
            played += item
            listener?.onPlaybackStarted(item)
            listener?.onPlaybackEnded(item)
        }

        override fun stopAll(reason: String) {
            stopCalls++
        }

        override fun release() = Unit
    }

    private class NeverCalledLlm : LlmAdapter {
        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            error("speak must not touch the LLM")
        }
    }

    private class FakeTts : TtsAdapter {
        val synthesized = mutableListOf<String>()
        override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
            synthesized += text
            return TtsResult(ByteArray(64), TtsAudioFormat.RAW_PCM_16LE, 16_000)
        }
    }

    private fun newSession(queue: RecordingQueue, tts: TtsAdapter): AvatarSession =
        AvatarSession(
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            llm = NeverCalledLlm(),
            tts = tts,
            controller = null,
            playbackQueue = queue,
            lipSyncProcessor = null,
        )

    @Test
    fun `speak chunk sentences and plays them without any LLM call`() = runTest {
        val queue = RecordingQueue()
        val tts = FakeTts()
        val session = newSession(queue, tts)
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")

        session.speak("晚上好，你。我是星野，今晚想听什么故事？")

        // 硬标点切成两句，按序入队
        assertEquals(listOf("晚上好，你。", "我是星野，今晚想听什么故事？"), tts.synthesized)
        assertEquals(listOf("晚上好，你。", "我是星野，今晚想听什么故事？"), queue.played.map { it.text })
        assertEquals(ConversationPhase.IDLE, session.phase.value)
    }

    @Test
    fun `speak emits SentenceQueued and TurnCompleted events`() = runTest {
        val session = newSession(RecordingQueue(), FakeTts())
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.events.collect { events += it }
        }

        session.speak("第一句。第二句！")

        // 泵一次调度器让事件收集协程跑完（SharedFlow 的挂起恢复用 runCurrent 才可靠，
        // advanceUntilIdle 实测不投递，见 docs/ai-layer-handoff.md 附录A）
        testScheduler.runCurrent()

        val queued = events.filterIsInstance<AvatarEvent.SentenceQueued>()
        assertEquals(listOf("第一句。", "第二句！"), queued.map { it.text })
        assertEquals(2, events.filterIsInstance<AvatarEvent.SentenceStarted>().size)
        assertEquals(1, events.filterIsInstance<AvatarEvent.TurnCompleted>().size)
        assertTrue(events.none { it is AvatarEvent.EmotionChanged })
    }

    @Test
    fun `speak without ttsConfig emits TurnFailed and stays idle`() = runTest {
        val session = newSession(RecordingQueue(), FakeTts())
        val failures = mutableListOf<AvatarEvent.TurnFailed>()
        backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.events.collect { if (it is AvatarEvent.TurnFailed) failures += it }
        }

        session.speak("没人听得到我。")

        testScheduler.runCurrent()

        assertEquals(1, failures.size)
        assertEquals(ConversationPhase.IDLE, session.phase.value)
    }

    @Test
    fun `speak with blank text is a no-op`() = runTest {
        val queue = RecordingQueue()
        val session = newSession(queue, FakeTts())
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")

        session.speak("   \n ")

        assertEquals(0, queue.played.size)
        assertEquals(ConversationPhase.IDLE, session.phase.value)
    }
}
