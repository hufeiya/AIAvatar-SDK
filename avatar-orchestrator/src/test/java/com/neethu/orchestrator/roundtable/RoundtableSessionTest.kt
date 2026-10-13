package com.neethu.orchestrator.roundtable

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
import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.session.ConversationPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RoundtableSession 编排（一屏多虚拟人 P1）：路由只点亮被点名的会话、事件合流
 * 带 slotId、倾听注视接力（发言中其他人看发言者，回合完回看镜头）、打断命中
 * 非空闲会话、TTS 预算均分、重建会话后重挂收集（坑 41）。会话 headless
 * （无 controller），LLM 用脚本 fake。
 *
 * 时序纪律（AvatarSessionMemoryTest 同款）：全部 dispatcher 绑 runTest 的
 * testScheduler，TTS 重活缝注入同款 unconfined（构造参数文档的单测钩子），
 * 断言前 advanceUntilIdle。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundtableSessionTest {

    @Before
    fun setUp() {
        // 播放事件走 Dispatchers.Main.immediate（AvatarSession 内聚），测试里换 test Main
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 即答 LLM：send 后整个回合同步跑完。 */
    private class ScriptedLlm(private val reply: String) : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages.toList()
            return flow {
                emit(LlmStreamEvent.TextDelta(reply))
                emit(LlmStreamEvent.Finish(null, null, emptyList()))
            }
        }
    }

    /** 不结束的流（打断测试用：send 后停在 THINKING）。 */
    private class HangingLlm : LlmAdapter {
        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> =
            MutableSharedFlow()
    }

    private class FakeTts : TtsAdapter {
        override suspend fun synthesize(text: String, config: TtsConfig): TtsResult =
            TtsResult(ByteArray(64), TtsAudioFormat.RAW_PCM_16LE, 16_000)
    }

    /** 入队即开播即收尾（AvatarSessionMoveTest 同款）。 */
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

    private class FakeGaze : StageGazeRelay {
        val points = mutableListOf<Pair<String, FloatArray>>() // (观察者, 目标点)
        val cameras = mutableListOf<String>()

        override fun headPosition(slotId: String): FloatArray? =
            if (slotId == "A") floatArrayOf(1f, 2f, 3f) else floatArrayOf(9f, 2f, 9f)

        override fun lookAtPoint(slotId: String, x: Float, y: Float, z: Float) {
            points += slotId to floatArrayOf(x, y, z)
        }

        override fun lookAtCamera(slotId: String) {
            cameras += slotId
        }
    }

    private class Fixture(
        val stage: RoundtableSession,
        val gaze: FakeGaze?,
        val sink: MutableList<StageEvent>,
    )

    /** A/B 双槽（可各自换 LLM），可选注视 fake 与事件汇收集。 */
    private fun newFixture(
        testScope: kotlinx.coroutines.test.TestScope,
        llmA: LlmAdapter = ScriptedLlm("我是小柔"),
        llmB: LlmAdapter = ScriptedLlm("我是小刚"),
        gaze: StageGazeRelay? = FakeGaze(),
    ): Fixture {
        val dispatcher = UnconfinedTestDispatcher(testScope.testScheduler)
        val specs = listOf(
            StageSlotSpec("A", "小柔"),
            StageSlotSpec("B", "小刚"),
        )
        val stage = RoundtableSession(
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
            slots = specs.map { spec ->
                spec to newSession(dispatcher, if (spec.id == "A") llmA else llmB)
            },
            gaze = gaze,
        )
        val sink = mutableListOf<StageEvent>()
        testScope.backgroundScope.launch(UnconfinedTestDispatcher(testScope.testScheduler)) {
            stage.events.collect { sink += it }
        }
        return Fixture(stage, gaze as? FakeGaze, sink)
    }

    private fun newSession(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        llm: LlmAdapter,
    ): AvatarSession =
        AvatarSession(
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
            llm = llm,
            tts = FakeTts(),
            playbackQueue = RecordingQueue(),
            // TTS 解码/口型分析缝：与 scope 同款 unconfined，回合时序全同步
            heavyWorkDispatcher = dispatcher,
        ).also {
            it.llmConfig = LlmConfig("https://fake", "key", model = "fake-model")
            it.ttsConfig = TtsConfig(model = "fake-tts", voice = "v")
        }

    @Test
    fun `routes to named slot only and strips the at-token`() = runTest {
        val llmA = ScriptedLlm("我是小柔")
        val llmB = ScriptedLlm("我是小刚")
        val fixture = newFixture(this, llmA = llmA, llmB = llmB)

        val hit = fixture.stage.sendFromUser("@A 你好")
        testScheduler.advanceUntilIdle()

        assertEquals("A", hit)
        assertEquals(1, llmA.requests.size)
        assertTrue(llmB.requests.isEmpty())
        // 进历史的是剥掉 @token 的干净文本
        val userMsg = llmA.requests.last().last { it.role == ChatRole.USER }
        assertEquals("你好", userMsg.content)
    }

    @Test
    fun `default routes to lastAddressed`() = runTest {
        val llmA = ScriptedLlm("嗯")
        val llmB = ScriptedLlm("嗯")
        val fixture = newFixture(this, llmA = llmA, llmB = llmB)
        fixture.stage.lastAddressedId = "B"

        assertEquals("B", fixture.stage.sendFromUser("在吗"))
        testScheduler.advanceUntilIdle()

        assertTrue(llmB.requests.isNotEmpty())
        assertTrue(llmA.requests.isEmpty())
    }

    @Test
    fun `events are merged with slotId`() = runTest {
        val fixture = newFixture(this)

        fixture.stage.sendFromUser("@B 你好")
        testScheduler.advanceUntilIdle()

        assertEquals(setOf("B"), fixture.sink.map { it.slotId }.toSet())
        assertTrue(fixture.sink.any { it.event is AvatarEvent.TurnCompleted })
        assertTrue(fixture.sink.any { it.event is AvatarEvent.SentenceStarted })
    }

    @Test
    fun `gaze relay - listeners watch the speaker then everyone back to camera`() = runTest {
        val gaze = FakeGaze()
        val fixture = newFixture(this, gaze = gaze)

        fixture.stage.sendFromUser("@A 自我介绍一下")
        testScheduler.advanceUntilIdle()

        // A 开口 → B 看 A 的头 (1,2,3)
        assertTrue(
            gaze.points.any { it.first == "B" && it.second.contentEquals(floatArrayOf(1f, 2f, 3f)) },
        )
        // 回合完成 → 全员（含发言者）回看镜头，发言令牌释放
        assertEquals(listOf("A", "B"), gaze.cameras)
        assertNull(fixture.stage.currentSpeakerId)
    }

    @Test
    fun `interrupt hits only non-idle sessions`() = runTest {
        val fixture = newFixture(this, llmA = HangingLlm())

        fixture.stage.sendFromUser("@A 讲个故事")
        testScheduler.advanceUntilIdle()
        val sessionA = fixture.stage.sessionOf("A")!!
        assertEquals(ConversationPhase.THINKING, sessionA.phase.value)

        fixture.stage.interrupt()
        testScheduler.advanceUntilIdle()

        assertEquals(ConversationPhase.IDLE, sessionA.phase.value)
    }

    @Test
    fun `unroutable message returns null and hits nobody`() = runTest {
        val fixture = newFixture(this)
        assertNull(fixture.stage.sendFromUser("   "))
    }

    @Test
    fun `say speaks directly on the requested slot`() = runTest {
        val llmA = ScriptedLlm("x")
        val fixture = newFixture(this, llmA = llmA, llmB = HangingLlm())

        assertTrue(fixture.stage.say("A", "开场白"))
        testScheduler.advanceUntilIdle()
        assertEquals("A", fixture.stage.lastAddressedId)
    }

    @Test
    fun `updateSession rebinds event collection to the new instance`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val oldLlm = ScriptedLlm("旧会话")
        val newLlm = ScriptedLlm("新会话")
        val oldSession = newSession(dispatcher, oldLlm)
        val stage = RoundtableSession(
            scope = kotlinx.coroutines.CoroutineScope(dispatcher),
            slots = listOf(StageSlotSpec("A", "小柔") to oldSession),
        )
        val sink = mutableListOf<StageEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stage.events.collect { sink += it }
        }

        // 配置变化：facade 重建了 session 实例 → 重挂（close 由 facade 语义负责）
        oldSession.close()
        stage.updateSession("A", newSession(dispatcher, newLlm))
        stage.lastAddressedId = "A" // 默认路由目标：此前一直在和 A 说
        stage.sendFromUser("在吗")
        testScheduler.advanceUntilIdle()

        assertEquals(1, newLlm.requests.size)
        assertTrue(oldLlm.requests.isEmpty())
        // 合流里只有新实例的排队事件（旧收集者已 cancel，无重复）
        assertEquals(1, sink.count { it.event is AvatarEvent.SentenceQueued })
    }

    @Test
    fun `tts budget splits across slots with a floor of one`() {
        assertEquals(2, RoundtableSession.perSlotTtsBudget(4, 2))
        assertEquals(1, RoundtableSession.perSlotTtsBudget(4, 3))
        assertEquals(1, RoundtableSession.perSlotTtsBudget(1, 3))
        assertEquals(4, RoundtableSession.perSlotTtsBudget(4, 0))
    }
}
