package com.neethu.orchestrator.roundtable

import com.neethu.corelib.AvatarActor
import com.neethu.corelib.CameraShot
import com.neethu.corelib.WalkFinished
import com.neethu.corelib.WalkSnapshot
import com.neethu.corelib.WalkStartResult
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
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P3 站位编排（docs/multi-avatar-feasibility.md §4.7/§7 P3）：开题先走对谈位
 * （锚点优先）、首轮批扣住、全员到位互相对着后交导演、多段腿到达过滤、
 * 插话排队、超时原地开题、走动中断放弃对位、打断/停题取消编排。
 *
 * 走动事件由 fake controller 的 walkFinished 流驱动（走 AvatarSession 的
 * 真实转出路径），floor 用 fake 记录指令。时序纪律同 RoundtableSessionTest：
 * 全部 dispatcher 绑 runTest 的 testScheduler，动作后 advanceUntilIdle。
 * 导演默认 maxRounds=1——ScriptedLlm 即答会让对话一路级联到上限，收敛在
 * 首轮（收场白走 speak 不打 LLM）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundtableSessionFormationTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    private class FakeTts : TtsAdapter {
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

    /** 可控 walkFinished 的演员 fake（会话接线只消费这一条流与 getWalkState）。 */
    private class FakeActor : AvatarActor {
        val walkFlow = MutableSharedFlow<WalkFinished>(extraBufferCapacity = 8)

        override val walkFinished: SharedFlow<WalkFinished> = walkFlow
        override fun getWalkState(): WalkSnapshot? = null
        override fun startWalk(targetX: Float, targetZ: Float, speed: Float) = WalkStartResult.STARTED
        override fun stopWalk() = Unit
        override fun getActiveCameraShot(): CameraShot? = null
        override fun setCameraShot(shot: CameraShot) = Unit
        override fun getCameraLookAt() = null
        override fun setExpression(name: String, weight: Float) = Unit
        override fun clearAllExpressions() = Unit
        override fun getAvailableExpressions(): List<String> = emptyList()
        override fun setExpressionTransitionDuration(durationMs: Long) = Unit
        override fun loadVrmaAnimation(assetPath: String) = true
        override fun loadVrmaAnimationFromFile(path: String) = true
        override fun playVrmaAnimation(loop: Boolean, speed: Float, fadeSeconds: Float) = Unit
        override fun stopVrmaAnimation(fadeSeconds: Float) = Unit
        override fun setVrmaIdleAnimation(assetPath: String) = true
        override fun setVrmaIdleAnimationFromFile(path: String) = true
        override fun clearVrmaIdleAnimation() = Unit
        override fun setLookAtTarget(x: Float, y: Float, z: Float) = Unit
        override fun clearLookAtTarget() = Unit
        override fun setBreathEnabled(enabled: Boolean) = Unit
        override fun setBreathAmplitudeScale(scale: Float) = Unit
        override fun setBreathRateBpm(bpm: Float) = Unit
        override fun setBreathSpeaking(speaking: Boolean) = Unit

        fun arrive() {
            walkFlow.tryEmit(WalkFinished(arrived = true, x = 0f, z = 0f, avatarId = "fake"))
        }

        fun abort() {
            walkFlow.tryEmit(WalkFinished(arrived = false, x = 0f, z = 0f, avatarId = "fake"))
        }
    }

    private class FakeFloor : StageFloorRelay {
        val anchorWalks = mutableListOf<Pair<String, String>>() // slot to anchor
    val stopped = mutableListOf<String>()
        val pointWalks = mutableListOf<Triple<String, Float, Float>>()
        val faces = mutableListOf<Triple<String, Float, Float>>()
        var enRoute = false
        var acceptWalks = true
        /** 列出的槽位首次 walkToAnchor 拒绝（模拟走动中防抖），重试放行。 */
        var rejectOnce: Set<String> = emptySet()
        private val rejectedOnce = mutableSetOf<String>()
        /** null = 该槽无锚点（走推导落点）。 */
        var anchorFor: (String) -> Pair<Float, Float>? = { slotId ->
            if (slotId == "A") -0.8f to -4f else 0.8f to -4f
        }

        override fun conversationAnchor(slotId: String): Pair<String, Pair<Float, Float>>? {
            val xz = anchorFor(slotId) ?: return null
            return "stage_${slotId.lowercase()}" to xz
        }

        override fun walkToAnchor(slotId: String, anchorName: String): Boolean {
            anchorWalks += slotId to anchorName
            if (slotId in rejectOnce && slotId !in rejectedOnce) {
                rejectedOnce += slotId
                return false
            }
            return acceptWalks
        }

        override fun walkToPoint(slotId: String, x: Float, z: Float): Boolean {
            pointWalks += Triple(slotId, x, z)
            return acceptWalks
        }

        override fun faceTowards(slotId: String, x: Float, z: Float): Boolean {
            faces += Triple(slotId, x, z)
            return true
        }

        override fun stopWalking(slotId: String) {
            stopped += slotId
        }

        override fun positionOf(slotId: String): Pair<Float, Float>? =
            if (slotId == "A") -0.5f to -3.8f else 0.5f to -4.2f

        override fun cameraXZ(): Pair<Float, Float> = 0f to 0f

        override fun isEnRoute(slotId: String): Boolean = enRoute
    }

    private class Fixture(
        val stage: RoundtableSession,
        val floor: FakeFloor,
        val actorA: FakeActor,
        val actorB: FakeActor,
        val llmA: ScriptedLlm,
        val llmB: ScriptedLlm,
    )

    /** A/B 双槽 + FakeFloor；导演默认 1 轮上限（ScriptedLlm 即答会级联到上限）。 */
    private fun newFixture(
        testScope: kotlinx.coroutines.test.TestScope,
        maxRounds: Int = 1,
    ): Fixture {
        val dispatcher = UnconfinedTestDispatcher(testScope.testScheduler)
        val floor = FakeFloor()
        val actorA = FakeActor()
        val actorB = FakeActor()
        val llmA = ScriptedLlm("我觉得去山里徒步呀")
        val llmB = ScriptedLlm("海边散步也不错啊")
        val specs = listOf(StageSlotSpec("A", "小柔"), StageSlotSpec("B", "小刚"))
        val sessions = specs.map { spec ->
            spec to AvatarSession(
                scope = CoroutineScope(dispatcher),
                llm = if (spec.id == "A") llmA else llmB,
                tts = FakeTts(),
                controller = if (spec.id == "A") actorA else actorB,
                playbackQueue = RecordingQueue(),
                heavyWorkDispatcher = dispatcher,
            ).also {
                it.llmConfig = LlmConfig("https://fake", "key", model = "fake-model")
                it.ttsConfig = TtsConfig(model = "fake-tts", voice = "v")
            }
        }
        val stage = RoundtableSession(
            scope = CoroutineScope(dispatcher),
            slots = sessions,
            floor = floor,
            director = DirectorPolicy.Rule(maxRounds = maxRounds),
        )
        return Fixture(stage, floor, actorA, actorB, llmA, llmB)
    }

    private fun lastUserOf(llm: ScriptedLlm): ChatMessage =
        llm.requests.last().last { it.role == ChatRole.USER }

    @Test
    fun `开题先走对谈位 到齐互相对着才开轮`() = runTest {
        val f = newFixture(this)

        assertTrue(f.stage.startRoundtable("周末去哪玩"))
        // ⚠站位超时是挂起的 delay：只能 runCurrent（跑当前时刻任务），不能
        // advanceUntilIdle——那会把虚拟时间推过 10s，编排在断言前就超时收队
        runCurrent()
        // 首轮批扣住：LLM 还没被请求；两人都拿到锚点指令
        assertEquals(0, f.llmA.requests.size)
        assertEquals(listOf("A" to "stage_a", "B" to "stage_b"), f.floor.anchorWalks)
        assertTrue(f.stage.snapshot().contains("forming=A/B"))

        // A 到（B 未到）——不开轮
        f.actorA.arrive()
        runCurrent()
        assertEquals(0, f.llmA.requests.size)

        // B 到 → 互相对着 + 交导演（开题轮：声明序 A 优先）
        f.actorB.arrive()
        runCurrent()
        assertEquals(
            listOf(Triple("A", 0.8f, -4f), Triple("B", -0.8f, -4f)),
            f.floor.faces,
        )
        assertEquals(1, f.llmA.requests.size)
        assertTrue(f.stage.snapshot().contains("forming=").not())
    }

    @Test
    fun `多段腿中途到达被过滤 isEnRoute 才收`() = runTest {
        val f = newFixture(this)
        f.floor.enRoute = true
        assertTrue(f.stage.startRoundtable("聊吃啥"))
        runCurrent()
        f.actorA.arrive()
        f.actorB.arrive()
        runCurrent()
        // 还在续腿：不开轮、不发对视
        assertEquals(0, f.llmA.requests.size)
        assertTrue(f.floor.faces.isEmpty())

        f.floor.enRoute = false
        f.actorA.arrive()
        f.actorB.arrive()
        runCurrent()
        assertEquals(1, f.llmA.requests.size)
    }

    @Test
    fun `站位期间插话排队 到位后随轮次回应`() = runTest {
        val f = newFixture(this, maxRounds = 2)
        f.stage.lastAddressedId = "A"
        assertTrue(f.stage.startRoundtable("聊周末"))
        runCurrent()
        assertEquals("A", f.stage.sendFromUser("我想去爬山"))
        runCurrent()
        // 排队：还没人被请求
        assertEquals(0, f.llmA.requests.size)

        f.actorA.arrive()
        f.actorB.arrive()
        runCurrent()
        // 首轮 A（完成即答）→ 第二轮 B 带上插话（级联在 2 轮上限收敛）
        assertEquals(1, f.llmA.requests.size)
        assertEquals(1, f.llmB.requests.size)
        assertTrue(lastUserOf(f.llmB).content.contains("【用户】我想去爬山"))
    }

    @Test
    fun `超时原地开题 不发对视`() = runTest {
        val f = newFixture(this)
        assertTrue(f.stage.startRoundtable("聊天气"))
        runCurrent()
        assertEquals(0, f.llmA.requests.size)

        advanceTimeBy(RoundtableSession.FORMATION_TIMEOUT_MS + 1)
        runCurrent()

        assertTrue(f.floor.faces.isEmpty())
        assertEquals(1, f.llmA.requests.size)
    }

    @Test
    fun `走动中断的槽放弃对位 其余到齐照常收队`() = runTest {
        val f = newFixture(this)
        assertTrue(f.stage.startRoundtable("聊电影"))
        runCurrent()
        f.actorA.abort() // A 半路停步
        runCurrent()
        assertEquals(0, f.llmA.requests.size)
        f.actorB.arrive() // B 到 → pending 空 → 收队（对视名单 A 已随中断丢弃，只剩 B 对着 A 的席位）
        runCurrent()
        assertEquals(1, f.llmA.requests.size)
        assertEquals(listOf("B"), f.floor.faces.map { it.first })
        assertEquals(-0.8f, f.floor.faces[0].second, 1e-4f)
    }

    @Test
    fun `打断取消编排 扣住的批丢弃`() = runTest {
        val f = newFixture(this)
        assertTrue(f.stage.startRoundtable("聊音乐"))
        runCurrent()
        f.stage.interrupt()
        advanceUntilIdle()
        assertTrue(f.stage.snapshot().contains("forming=").not())
        f.actorA.arrive()
        f.actorB.arrive()
        advanceUntilIdle()
        // 编排已取消：到达不再触发开轮
        assertEquals(0, f.llmA.requests.size)
        assertTrue(f.floor.faces.isEmpty())
    }

    @Test
    fun `无锚点槽走推导落点 互指按最终落点`() = runTest {
        val f = newFixture(this)
        // B 无锚点 → 走推导点；face 仍按「锚点(A) + 推导(B)」的最终落点互指
        f.floor.anchorFor = { if (it == "A") -0.8f to -4f else null }
        assertTrue(f.stage.startRoundtable("聊书"))
        runCurrent()
        assertEquals(listOf("A" to "stage_a"), f.floor.anchorWalks)
        assertEquals(1, f.floor.pointWalks.size)
        assertEquals("B", f.floor.pointWalks[0].first)

        f.actorA.arrive()
        f.actorB.arrive()
        runCurrent()
        // A 的 face = B 的推导落点；B 的 face = A 的锚点落点
        assertTrue(f.floor.faces.any { it.first == "B" && it.second == -0.8f })
        assertTrue(f.floor.faces.any { it.first == "A" && it.second != -0.8f })
        assertEquals(1, f.llmA.requests.size)
    }

    @Test
    fun `走动中的槽被编排让路接管 停步重试`() = runTest {
        val f = newFixture(this)
        f.floor.rejectOnce = setOf("A") // A 首次拒绝（走动中），stop 后重试放行
        assertTrue(f.stage.startRoundtable("聊零食"))
        runCurrent()
        // A 被停步让路并重试成功：两次 walkToAnchor + 一次 stopWalking
        assertEquals(2, f.floor.anchorWalks.count { it.first == "A" })
        assertEquals(listOf("A"), f.floor.stopped)
        assertTrue(f.stage.snapshot().contains("forming="))
        f.actorA.arrive()
        f.actorB.arrive()
        runCurrent()
        assertEquals(1, f.llmA.requests.size)
    }

    @Test
    fun `全员没起步直接开轮 行为与P2一致`() = runTest {
        val f = newFixture(this)
        f.floor.acceptWalks = false
        assertTrue(f.stage.startRoundtable("聊咖啡"))
        advanceUntilIdle()
        // 无编排：立即开轮（规则档开题=最后对话对象优先，此处空→A）
        assertEquals(1, f.llmA.requests.size)
        assertTrue(f.floor.faces.isEmpty())
    }
}
