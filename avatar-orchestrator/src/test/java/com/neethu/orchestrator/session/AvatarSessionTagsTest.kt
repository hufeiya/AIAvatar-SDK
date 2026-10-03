package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.EmotionCue
import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import com.neethu.corelib.AvatarController
import com.neethu.corelib.CameraShot
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.gesture.ActionEntry
import com.neethu.orchestrator.gesture.GestureDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
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
 * Multimodal inline-tag dispatch (docs/ai-layer-handoff.md §7): `<cam:…>`,
 * `<act:…>`, `<emo:…>` cues are extracted from the LLM stream (and from
 * speak() greetings), routed to their drivers, stripped from speech, and
 * reflected as [AvatarEvent]s. Unknown names are silently dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionTagsTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingQueue : PlaybackQueue {
        override var listener: PlaybackQueue.Listener? = null
        val played = mutableListOf<PlaybackItem>()

        override fun active(): ActivePlayback? = null

        override fun enqueue(item: PlaybackItem) {
            played += item
            listener?.onPlaybackStarted(item)
            listener?.onPlaybackEnded(item)
        }

        override fun stopAll(reason: String) = Unit

        override fun release() = Unit
    }

    /** LLM whose stream is a SharedFlow the test drives with tryEmit. */
    private class SharedFlowLlm : LlmAdapter {
        val stream = MutableSharedFlow<LlmStreamEvent>(extraBufferCapacity = 64)
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            return stream
        }
    }

    private class FakeTts : TtsAdapter {
        val synthesized = mutableListOf<String>()
        override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
            synthesized += text
            return TtsResult(ByteArray(64), TtsAudioFormat.RAW_PCM_16LE, 16_000)
        }
    }

    private class FakeGestureDriver(private val accepted: Set<String> = setOf("wave")) :
        GestureDriver(AvatarController()) {
        val played = mutableListOf<String>()
        val idlesSet = mutableListOf<String>()
        var stopCalls = 0
        var clearIdleCalls = 0
        override fun play(tag: String): Boolean {
            if (tag !in accepted) return false
            played += tag
            return true
        }

        override fun setIdle(entry: ActionEntry): Boolean {
            idlesSet += entry.tag
            return true
        }

        override fun clearIdle() {
            clearIdleCalls++
        }

        override fun stop() {
            stopCalls++
        }
    }

    private fun newSession(
        llm: SharedFlowLlm,
        tts: FakeTts,
        queue: RecordingQueue,
        gestures: FakeGestureDriver,
        options: AvatarSession.Options = AvatarSession.Options(),
    ): AvatarSession =
        AvatarSession(
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = tts,
            controller = AvatarController(),
            options = options,
            playbackQueue = queue,
            lipSyncProcessor = null,
            gestureDriver = gestures,
        )

    private fun kotlinx.coroutines.test.TestScope.collectInto(
        session: AvatarSession,
        events: MutableList<AvatarEvent>,
    ) {
        backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.events.collect { events += it }
        }
    }

    @Test
    fun `send dispatches camera emotion action cues and strips tags from speech`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val queue = RecordingQueue()
        val gestures = FakeGestureDriver()
        val session = newSession(llm, tts, queue, gestures)
        session.actionCatalog = listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma"))
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<cam:medium_shot><emo:happy:0.8>你好"))
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:wave>呀。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(listOf("wave"), gestures.played)
        // 按发射顺序断言：cue 在流里出现即触发（§7.5 即发即执行语义）
        val tagEvents = events.filter {
            it is AvatarEvent.CameraChanged || it is AvatarEvent.EmotionChanged || it is AvatarEvent.ActionStarted
        }
        assertEquals(
            listOf(
                AvatarEvent.CameraChanged(CameraShot.MEDIUM_SHOT),
                AvatarEvent.EmotionChanged(EmotionCue("happy", 0.8f)),
                AvatarEvent.ActionStarted("wave", "挥手问候"),
            ),
            tagEvents,
        )
        // 标签不进语音，文字完整送 TTS
        assertEquals(listOf("你好呀。"), tts.synthesized)
        // 协议块出现在系统提示词，动作按分类分组列出
        val system = llm.requests[0].first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("<act:动作>"))
        assertTrue(system.content.contains("基础动作: wave"))
        assertTrue(system.content.contains("<cam:机位>"))
    }

    @Test
    fun `unknown action and camera names are silently dropped`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val gestures = FakeGestureDriver()
        val session = newSession(llm, tts, RecordingQueue(), gestures)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:backflip><cam:drone_shot>好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(0, gestures.played.size)
        assertEquals(0, events.filterIsInstance<AvatarEvent.ActionStarted>().size)
        assertEquals(0, events.filterIsInstance<AvatarEvent.CameraChanged>().size)
        assertEquals(listOf("好。"), tts.synthesized)
    }

    @Test
    fun `camera cue respects enableLlmCamera=false and omits camera section from prompt`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val session = newSession(
            llm, tts, RecordingQueue(), FakeGestureDriver(),
            options = AvatarSession.Options(enableLlmCamera = false),
        )
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<cam:close_up>好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(0, events.filterIsInstance<AvatarEvent.CameraChanged>().size)
        val system = llm.requests[0].first { it.role == ChatRole.SYSTEM }
        assertFalse(system.content.contains("<cam:"))
        assertTrue(system.content.contains("<emo:"))
    }

    @Test
    fun `speak interprets tags in greetings`() = runTest {
        val tts = FakeTts()
        val session = newSession(SharedFlowLlm(), tts, RecordingQueue(), FakeGestureDriver())
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.speak("<emo:happy>晚上好！")
        testScheduler.runCurrent()

        assertEquals(1, events.filterIsInstance<AvatarEvent.EmotionChanged>().size)
        assertEquals(listOf("晚上好！"), tts.synthesized)
    }

    @Test
    fun `interrupt stops the gesture started by the turn`() = runTest {
        val llm = SharedFlowLlm()
        val gestures = FakeGestureDriver()
        val session = newSession(llm, FakeTts(), RecordingQueue(), gestures)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:wave>嗨"))
        testScheduler.runCurrent()
        assertEquals(listOf("wave"), gestures.played)

        session.interrupt()
        assertEquals(1, gestures.stopCalls)
    }

    @Test
    fun `unknown emotion names are dropped while canonical ones pass`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val session = newSession(llm, tts, RecordingQueue(), FakeGestureDriver())
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        // unicorn 不是规范情绪、也查不到模型支持 → 静默；happy 是规范情绪 → 通过
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:unicorn:1><emo:happy:0.5>好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        val emotions = events.filterIsInstance<AvatarEvent.EmotionChanged>()
        assertEquals(listOf("happy"), emotions.map { it.cue.name })
        assertEquals(listOf("好。"), tts.synthesized)
    }

    @Test
    fun `idleAction routes to the gesture driver`() = runTest {
        val gestures = FakeGestureDriver()
        val session = newSession(SharedFlowLlm(), FakeTts(), RecordingQueue(), gestures)

        session.idleAction = ActionEntry("idle_stand", "Idle Stand Looking Around", assetPath = "animations/i.vrma")
        assertEquals(listOf("idle_stand"), gestures.idlesSet)
        session.idleAction = null
        assertEquals(1, gestures.clearIdleCalls)
    }

    @Test
    fun `gesture driver rejects unknown tags without touching the controller`() {
        val driver = GestureDriver(AvatarController())
        driver.setCatalog(listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma")))
        assertFalse(driver.play("backflip"))
        assertEquals(null, driver.currentTag)
    }
}
