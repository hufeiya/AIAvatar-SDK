package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.corelib.AvatarController
import com.neethu.corelib.Lang
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
import com.neethu.orchestrator.i18n.promptTextsOf
import com.neethu.orchestrator.locomotion.LocomotionDriver
import com.neethu.orchestrator.locomotion.MoveAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
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
 * `<move:锚点>` 全链（自由走动 P1，docs/locomotion-feasibility.md §7.3/§7.4）：
 * 标签抽取 → 驱动器起步（异步语义，不阻塞说话）→ WalkStarted 事件 + 标签
 * 不进语音；未知名走 UnknownTag（过滤+上报）；锚点表进协议块移动段。
 * 到达事件（WalkArrived/Interrupted）来自 corelib 渲染流，真机验收。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionMoveTest {

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
        override fun active(): ActivePlayback? = null
        override fun enqueue(item: PlaybackItem) {
            listener?.onPlaybackStarted(item)
            listener?.onPlaybackEnded(item)
        }

        override fun stopAll(reason: String) = Unit
        override fun release() = Unit
    }

    private class SharedFlowLlm : LlmAdapter {
        val stream = MutableSharedFlow<LlmStreamEvent>(extraBufferCapacity = 64)
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            return stream.transformWhile { event ->
                emit(event)
                event !is LlmStreamEvent.Finish
            }
        }
    }

    private class FakeTts : TtsAdapter {
        val synthesized = mutableListOf<String>()
        override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
            synthesized += text
            return TtsResult(ByteArray(64), TtsAudioFormat.RAW_PCM_16LE, 16_000)
        }
    }

    private class FakeLocomotionDriver : LocomotionDriver(AvatarController()) {
        val walkedTo = mutableListOf<String>()
        var nextOutcome: Outcome? = null

        override fun find(name: String): MoveAnchor? = anchors.firstOrNull { it.name == name }

        override fun walkTo(name: String): Outcome {
            val anchor = find(name) ?: return Outcome.UNKNOWN_ANCHOR
            return walkTo(anchor)
        }

        override fun walkTo(anchor: MoveAnchor): Outcome {
            walkedTo += anchor.name
            return nextOutcome ?: Outcome.STARTED
        }
    }

    private fun newSession(
        llm: SharedFlowLlm,
        tts: FakeTts,
        driver: FakeLocomotionDriver,
        options: AvatarSession.Options = AvatarSession.Options(),
    ): AvatarSession =
        AvatarSession(
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = tts,
            controller = AvatarController(),
            options = options,
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            locomotionDriver = driver,
            heavyWorkDispatcher = UnconfinedTestDispatcher(),
        )

    private fun kotlinx.coroutines.test.TestScope.collectInto(
        session: AvatarSession,
        events: MutableList<AvatarEvent>,
    ) {
        backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.events.collect { events += it }
        }
    }

    private fun stubConfigs(session: AvatarSession) {
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
    }

    private fun sofaDriver() = FakeLocomotionDriver().apply {
        anchors = listOf(MoveAnchor("sofa", 1.6f, -1.2f, label = "沙发", labelEn = "the sofa"))
    }

    @Test
    fun `move cue starts the walk asynchronously and is stripped from speech`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val driver = sofaDriver()
        val session = newSession(llm, tts, driver)
        stubConfigs(session)
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<move:sofa>这就过去陪你坐会儿。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        // 异步语义：起步即走，标签不进语音，台词完整
        assertEquals(listOf("sofa"), driver.walkedTo)
        assertEquals(listOf("这就过去陪你坐会儿。"), tts.synthesized)
        assertEquals(
            listOf(AvatarEvent.WalkStarted("sofa", 1.6f, -1.2f)),
            events.filterIsInstance<AvatarEvent.WalkStarted>(),
        )
        assertEquals(0, events.filterIsInstance<AvatarEvent.UnknownTag>().size)
    }

    @Test
    fun `unknown move name is filtered and reported`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val driver = sofaDriver()
        val session = newSession(llm, tts, driver)
        stubConfigs(session)
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<move:balcony>我去阳台。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(0, driver.walkedTo.size)
        assertEquals(listOf("我去阳台。"), tts.synthesized)
        val unknown = events.filterIsInstance<AvatarEvent.UnknownTag>()
        assertEquals(listOf("move"), unknown.map { it.kind })
        assertEquals(listOf("<move:balcony>"), unknown.map { it.raw })
    }

    @Test
    fun `text-form tool call is filtered and reported not executed`() = runTest {
        // P1 真机失败形态：模型把工具调用当文本吐（<move_to carpet>）——
        // 不进语音、不执行（文本不是工具通道），只上报供面板/日志定位
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val driver = sofaDriver()
        val session = newSession(llm, tts, driver)
        stubConfigs(session)
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<move_to carpet>好，我这就去。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(0, driver.walkedTo.size)
        assertEquals(listOf("好，我这就去。"), tts.synthesized)
        val unknown = events.filterIsInstance<AvatarEvent.UnknownTag>()
        assertEquals(listOf("tool"), unknown.map { it.kind })
        assertEquals(listOf("<move_to carpet>"), unknown.map { it.raw })
    }

    @Test
    fun `already walking outcome emits no event and no unknown tag`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val driver = sofaDriver()
        driver.nextOutcome = LocomotionDriver.Outcome.ALREADY_WALKING
        val session = newSession(llm, tts, driver)
        stubConfigs(session)
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<move:sofa>好嘞。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(listOf("sofa"), driver.walkedTo)
        assertEquals(0, events.filterIsInstance<AvatarEvent.WalkStarted>().size)
        assertEquals(0, events.filterIsInstance<AvatarEvent.UnknownTag>().size)
        assertEquals(listOf("好嘞。"), tts.synthesized)
    }

    @Test
    fun `anchors appear in the protocol block move section`() = runTest {
        val llm = SharedFlowLlm()
        val driver = sofaDriver()
        val session = newSession(llm, FakeTts(), driver)
        stubConfigs(session)
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("嗯。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        val system = llm.requests[0].first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("<move:锚点>"))
        assertTrue(system.content.contains("sofa(沙发)"))
        assertTrue(system.content.contains("<cam:follow>"))
    }

    @Test
    fun `empty anchor table omits the move section entirely`() = runTest {
        val llm = SharedFlowLlm()
        val driver = FakeLocomotionDriver() // 无锚点
        val session = newSession(llm, FakeTts(), driver)
        stubConfigs(session)
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("嗯。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        val system = llm.requests[0].first { it.role == ChatRole.SYSTEM }
        assertFalse(system.content.contains("<move:"))
    }

    @Test
    fun `enableLlmMove=false disables the channel and the move section`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val driver = sofaDriver()
        val session = newSession(llm, tts, driver, options = AvatarSession.Options(enableLlmMove = false))
        stubConfigs(session)
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        // 开关关闭时 session 不会用注入的 driver（locomotionDriver=null），
        // dispatchMove 直接 return——不走路、不发事件
        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<move:sofa>好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(0, driver.walkedTo.size)
        assertEquals(0, events.filterIsInstance<AvatarEvent.WalkStarted>().size)
    }

    @Test
    fun `move labels localize by session language`() {
        val en = promptTextsOf(Lang.EN)
        val anchor = MoveAnchor("sofa", 1f, 2f, label = "沙发", labelEn = "the sofa")
        assertEquals("the sofa", anchor.labelFor(en.lang))
    }
}
