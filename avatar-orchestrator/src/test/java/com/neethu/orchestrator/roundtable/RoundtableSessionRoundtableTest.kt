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
import com.neethu.orchestrator.history.ConversationStore
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.session.ConversationPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.flow
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
 * 圆桌对话流程（一屏多虚拟人 P2，docs/multi-avatar-feasibility.md §4.3/§4.4）：
 * 开题授予与【用户】前缀、多人消息 store 形态（受命者 send 追加/听众注入、
 * 自己的话不重复注入）、严格轮转、轮数上限收场白、复读检测收场、插话排队
 * 与回应、点名直授、打断暂停、导演热切换（LLM 档生效）。
 * 会话 headless（无 controller），store 可注入以便断言历史形态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoundtableSessionRoundtableTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 即答 LLM：send 后整个回合同步跑完；多回复按轮次顺序供给（耗尽后重复末条）。 */
    private class ScriptedLlm(vararg replies: String) : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()
        private val queue = ArrayDeque(replies.toList())
        private val fallback = replies.lastOrNull() ?: ""

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages.toList()
            val reply = queue.removeFirstOrNull() ?: fallback
            return flow {
                emit(LlmStreamEvent.TextDelta(reply))
                emit(LlmStreamEvent.Finish(null, null, emptyList()))
            }
        }
    }

    /**
     * 受控 LLM：挂在 THINKING，测试里手动放行。单一共享流 + transformWhile
     * （AvatarSessionMoveTest 同款——无参 UnconfinedTestDispatcher 下恢复内联，
     * 绑 testScheduler 的每回合流恢复不会被执行）。
     */
    private class GateLlm : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()
        val stream = MutableSharedFlow<LlmStreamEvent>(extraBufferCapacity = 64)

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages.toList()
            return stream.transformWhile {
                emit(it)
                it !is LlmStreamEvent.Finish
            }
        }

        fun reply(text: String) {
            stream.tryEmit(LlmStreamEvent.TextDelta(text))
            stream.tryEmit(LlmStreamEvent.Finish(null, null, emptyList()))
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

    private class Slot(
        val session: AvatarSession,
        val store: InMemoryConversationStore,
        val llm: LlmAdapter,
    )

    private class Fixture(
        val stage: RoundtableSession,
        val a: Slot,
        val b: Slot,
    ) {
        fun slot(id: String): Slot = if (id == "A") a else b
    }

    /** A/B 双槽，LLM 与 store 可各自注入（断言历史形态用）。 */
    private fun newFixture(
        testScope: kotlinx.coroutines.test.TestScope,
        llmA: LlmAdapter = ScriptedLlm("我觉得去山里徒步呀"),
        llmB: LlmAdapter = ScriptedLlm("我去哪儿都行啊", "海边散步也可以啊"),
        director: DirectorPolicy = DirectorPolicy.Rule(maxRounds = 6),
        storeA: InMemoryConversationStore = InMemoryConversationStore(),
        storeB: InMemoryConversationStore = InMemoryConversationStore(),
    ): Fixture {
        // 无参 UnconfinedTestDispatcher（MoveTest 同款）：恢复内联执行，整轮
        // 对话在调用点同步完成；绑 testScheduler 的版本 SharedFlow 恢复不执行
        val dispatcher = UnconfinedTestDispatcher()
        val specs = listOf(StageSlotSpec("A", "小柔"), StageSlotSpec("B", "小刚"))
        val slotA = Slot(newSession(dispatcher, llmA, storeA), storeA, llmA)
        val slotB = Slot(newSession(dispatcher, llmB, storeB), storeB, llmB)
        val stage = RoundtableSession(
            scope = CoroutineScope(dispatcher),
            slots = listOf(specs[0] to slotA.session, specs[1] to slotB.session),
            director = director,
        )
        return Fixture(stage, slotA, slotB)
    }

    private fun newSession(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        llm: LlmAdapter,
        store: ConversationStore,
    ): AvatarSession =
        AvatarSession(
            scope = CoroutineScope(dispatcher),
            llm = llm,
            tts = FakeTts(),
            playbackQueue = RecordingQueue(),
            store = store,
            heavyWorkDispatcher = dispatcher,
        ).also {
            it.llmConfig = LlmConfig("https://fake", "key", model = "fake-model")
            it.ttsConfig = TtsConfig(model = "fake-tts", voice = "v")
        }

    private fun lastUserRequest(llm: LlmAdapter): ChatMessage {
        val requests = when (llm) {
            is ScriptedLlm -> llm.requests
            is GateLlm -> llm.requests
            else -> error("unexpected llm")
        }
        return requests.last().last { it.role == ChatRole.USER }
    }

    @Test
    fun `opening grants lastAddressed with user-prefixed line and injects the listener`() = runTest {
        val llmB = ScriptedLlm("去山里怎么样")
        // A 挂起：开题轮结束后话题停在 ACTIVE（同步时序下不会一路跑到收场）
        val fixture = newFixture(this, llmA = GateLlm(), llmB = llmB)
        fixture.stage.lastAddressedId = "B"

        assertTrue(fixture.stage.startRoundtable("周末去哪玩"))
        testScheduler.advanceUntilIdle()

        assertEquals(RoundtableState.ACTIVE, fixture.stage.state.value)
        // B 受命：请求里的末尾 user = 前缀槽（轮到你）+【用户】前缀话题
        val bLastUser = lastUserRequest(llmB)
        assertTrue(bLastUser.content.contains("轮到你发言"))
        assertTrue(bLastUser.content.endsWith("【用户】周末去哪玩"))
        // 受命者与听众的历史都拿到同一行（send vs injectUserMessage）
        assertTrue(fixture.b.store.messages().any {
            it.role == ChatRole.USER && it.content == "【用户】周末去哪玩"
        })
        assertTrue(fixture.a.store.messages().any {
            it.role == ChatRole.USER && it.content == "【用户】周末去哪玩"
        })
    }

    @Test
    fun `turns alternate with prefixed fanout and close on round limit`() = runTest {
        val llmA = ScriptedLlm("山里空气好", "夜里还能看星星啊")
        val llmB = ScriptedLlm("海边乘凉也不错", "沙滩散步也好啊")
        val fixture = newFixture(
            this,
            llmA = llmA,
            llmB = llmB,
            director = DirectorPolicy.Rule(maxRounds = 4),
        )
        fixture.stage.lastAddressedId = "B"

        fixture.stage.startRoundtable("周末去哪玩")
        testScheduler.advanceUntilIdle()

        // 严格轮转 B→A→B→A，4 轮后收场
        assertEquals(2, llmB.requests.size)
        assertEquals(2, llmA.requests.size)
        // A 第一轮的输入 = 前缀槽 + B 的回复带【小刚】前缀
        val aFirstUser = llmA.requests.first().last { it.role == ChatRole.USER }
        assertTrue(aFirstUser.content.contains("轮到你发言"))
        assertTrue(aFirstUser.content.endsWith("【小刚】海边乘凉也不错"))
        // 收场：状态回 IDLE、收场原因进导演日志
        assertEquals(RoundtableState.IDLE, fixture.stage.state.value)
        assertTrue(fixture.stage.directorLog.value.last().contains("4 轮上限"))
        // B 的历史：话题(send) → 自己 r1(assistant) → A 的 r2(user 注入)
        //           → 自己 r3(send) → 自己 r3 之后 A 的 r4 经 END 批注入
        val bLines = fixture.b.store.messages().map { it.role to it.content }
        assertEquals(ChatRole.USER to "【用户】周末去哪玩", bLines[0])
        assertEquals(ChatRole.ASSISTANT to "海边乘凉也不错", bLines[1])
        assertEquals(ChatRole.USER to "【小柔】山里空气好", bLines[2])
        assertEquals(ChatRole.ASSISTANT to "沙滩散步也好啊", bLines[3])
        assertEquals(ChatRole.USER to "【小柔】夜里还能看星星啊", bLines[4])
        // 前缀槽全部清空
        assertNull(fixture.stage.sessionOf("A")!!.roundtableLine)
        assertNull(fixture.stage.sessionOf("B")!!.roundtableLine)
    }

    @Test
    fun `consecutive repeats end the topic`() = runTest {
        val same = "今天天气真不错啊"
        val fixture = newFixture(
            this,
            llmA = ScriptedLlm(same),
            llmB = ScriptedLlm(same),
            director = DirectorPolicy.Rule(maxRounds = 20),
        )
        fixture.stage.lastAddressedId = "B"

        fixture.stage.startRoundtable("周末去哪玩")
        testScheduler.advanceUntilIdle()

        // t1 无前序不判复读；t2 命中 strikes=1；t3 strikes=2 → 收场
        assertTrue(fixture.stage.directorLog.value.last().contains("复读"))
        assertEquals(RoundtableState.IDLE, fixture.stage.state.value)
    }

    @Test
    fun `interjection during a speaking turn is queued then answered`() = runTest {
        val gate = GateLlm()
        val llmB = ScriptedLlm("那就去山里吧")
        val fixture = newFixture(this, llmA = gate, llmB = llmB)
        fixture.stage.lastAddressedId = "A"

        fixture.stage.startRoundtable("周末去哪玩")
        testScheduler.advanceUntilIdle()
        assertEquals(ConversationPhase.THINKING, fixture.a.session.phase.value)

        // 无点名的插话 → 排队（路由仍回显受理对象 A）
        assertEquals("A", fixture.stage.sendFromUser("那你们觉得呢"))
        gate.reply("我觉得山里好玩呀")
        testScheduler.advanceUntilIdle()

        // B 受命回应：批 = A 的发言 + 用户插话，两行前缀拼接
        val bLastUser = lastUserRequest(llmB)
        assertTrue(bLastUser.content.endsWith("【小柔】我觉得山里好玩呀\n【用户】那你们觉得呢"))
        assertTrue(bLastUser.content.contains("轮到你发言"))
    }

    @Test
    fun `interrupt pauses and explicit mention grants directly`() = runTest {
        val gate = GateLlm()
        val llmB = ScriptedLlm("我听你的")
        val fixture = newFixture(this, llmA = gate, llmB = llmB)
        fixture.stage.lastAddressedId = "A"

        fixture.stage.startRoundtable("周末去哪玩")
        testScheduler.advanceUntilIdle()

        fixture.stage.interrupt()
        testScheduler.advanceUntilIdle()
        // 话题仍在（ACTIVE），但授予已清空——等下一次插话重新走导演
        assertEquals(RoundtableState.ACTIVE, fixture.stage.state.value)

        // 点名直授（§4.6 规则 1 优先于导演）
        assertEquals("B", fixture.stage.sendFromUser("@B 你觉得呢"))
        testScheduler.advanceUntilIdle()
        assertTrue(lastUserRequest(llmB).content.endsWith("【用户】你觉得呢"))
        assertEquals(RoundtableState.ACTIVE, fixture.stage.state.value)
    }

    @Test
    fun `hot-swap to llm director takes over the next decision`() = runTest {
        val gate = GateLlm()
        val llmB = ScriptedLlm("我都可以呀")
        val jsonLlm = ScriptedLlm("{\"next\":\"B\",\"reason\":\"B 还没说话\"}")
        val fixture = newFixture(this, llmA = gate, llmB = llmB)
        fixture.stage.lastAddressedId = "A"

        fixture.stage.startRoundtable("周末去哪玩")
        testScheduler.advanceUntilIdle()

        // A 说完前切到 LLM 档
        fixture.stage.setDirector(
            DirectorPolicy.Llm(jsonLlm, config = { LlmConfig("http://d", "k", model = "m") }),
        )
        gate.reply("山里凉快")
        testScheduler.advanceUntilIdle()

        // LLM 档的 reason 进了导演日志（策略生效的观测位）
        assertTrue(fixture.stage.directorLog.value.any { it.contains("B 还没说话") })
        assertTrue(llmB.requests.isNotEmpty())
    }

    @Test
    fun `interjection without mention or lastAddressed still reaches the director`() = runTest {
        val gate = GateLlm()
        val llmB = ScriptedLlm("开销确实要考虑啊")
        val fixture = newFixture(this, llmA = gate, llmB = llmB, director = DirectorPolicy.Rule(maxRounds = 9))
        // lastAddressed 为空（没人被点过名）→ 开题轮授予声明序第一个槽位
        fixture.stage.startRoundtable("聊聊养宠物")
        testScheduler.advanceUntilIdle()
        assertEquals(ConversationPhase.THINKING, fixture.a.session.phase.value)

        // 无点名插话：不依赖 lastAddressed，排队等本轮结束
        assertNull(fixture.stage.sendFromUser("那开销大吗"))
        gate.reply("我觉得还好呀")
        testScheduler.advanceUntilIdle()

        // 下一位受命回应：批 = A 的发言 + 用户插话
        assertTrue(lastUserRequest(llmB).content.endsWith("【小柔】我觉得还好呀\n【用户】那开销大吗"))
    }

    @Test
    fun `stop clears state and lines`() = runTest {
        val gate = GateLlm()
        val llmB = ScriptedLlm("嗯")
        val fixture = newFixture(this, llmA = gate, llmB = llmB)
        fixture.stage.lastAddressedId = "A"

        fixture.stage.startRoundtable("周末去哪玩")
        testScheduler.advanceUntilIdle()
        assertEquals(RoundtableState.ACTIVE, fixture.stage.state.value)

        fixture.stage.stopRoundtable()
        testScheduler.advanceUntilIdle()
        assertEquals(RoundtableState.IDLE, fixture.stage.state.value)
        assertEquals(ConversationPhase.IDLE, fixture.a.session.phase.value)
        assertNull(fixture.a.session.roundtableLine)
        assertNull(fixture.b.session.roundtableLine)
        // 停题后 sendFromUser 回到 P1 路由语义
        fixture.stage.lastAddressedId = "B"
        assertEquals("B", fixture.stage.sendFromUser("在吗"))
    }

    @Test
    fun `listener store receives injected prefix lines in order`() = runTest {
        val llmA = ScriptedLlm("山里好")
        val llmB = ScriptedLlm("海边好")
        val fixture = newFixture(
            this,
            llmA = llmA,
            llmB = llmB,
            director = DirectorPolicy.Rule(maxRounds = 2),
        )
        fixture.stage.lastAddressedId = "B"

        fixture.stage.startRoundtable("周末去哪玩")
        testScheduler.advanceUntilIdle()

        // A（听众 → 第二轮受命）：store 依次为 话题(inject) → B 的 r1(send) → 自己回复(assistant)
        val aLines = fixture.a.store.messages().map { it.role to it.content }
        assertEquals(ChatRole.USER to "【用户】周末去哪玩", aLines[0])
        assertEquals(ChatRole.USER to "【小刚】海边好", aLines[1])
        assertEquals(ChatRole.ASSISTANT to "山里好", aLines[2])
        // 收场白 speak 不进历史（2 轮上限到，第 2 轮完成后 END）
        assertEquals(3, aLines.size)
    }
}
