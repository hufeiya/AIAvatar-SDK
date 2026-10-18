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
import com.neethu.corelib.AvatarController
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.gesture.ActionEntry
import com.neethu.orchestrator.gesture.GestureDriver
import com.neethu.orchestrator.history.InMemoryConversationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

/** Every stateless request carries one complete system; history is a sanitized copy. */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionProtocolPinTest {

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
        /** 置 true 后下一轮流直接报错（模拟网络失败/400），用于锁「失败不提交送达」。 */
        var failNext = false
        var reply = "好的。"

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            return if (failNext) {
                failNext = false
                flowOf(LlmStreamEvent.Error(RuntimeException("simulated network failure")))
            } else {
                flowOf(LlmStreamEvent.TextDelta(reply))
            }
        }
    }

    /** 第一次调用挂起不返回（模拟请求在途被用户打断），之后正常。 */
    private class HangFirstLlm : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()
        private var calls = 0

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            return if (calls++ == 0) flow { awaitCancellation() }
            else flowOf(LlmStreamEvent.TextDelta("好的。"))
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

    private lateinit var llm: RecordingLlm

    private fun newSession(
        store: InMemoryConversationStore = InMemoryConversationStore(),
        options: AvatarSession.Options = AvatarSession.Options(protocolInstructions = true),
    ): AvatarSession {
        llm = RecordingLlm()
        return AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = AvatarController(),
            options,
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            gestureDriver = GestureDriver(AvatarController()),
            store = store,
        ).apply {
            llmConfig = LlmConfig("http://t", "k", model = "llm")
            ttsConfig = TtsConfig(model = "tts", voice = "v")
        }
    }

    /** 人设+协议+前言合并后的那条 system；断言全请求只有这一条 system。 */
    private fun singleSystemOf(request: List<ChatMessage>): ChatMessage {
        val systems = request.filter { it.role == ChatRole.SYSTEM }
        assertEquals("expected exactly ONE system message", 1, systems.size)
        return systems.single()
    }

    /** system 里是否带了协议目录（首轮/重发轮的判据）。 */
    private fun hasProtocol(request: List<ChatMessage>): Boolean =
        request.any { it.role == ChatRole.SYSTEM && it.content.contains("[多模态输出协议") }

    /** system 里是否带了人设全文（与协议同进同出=完整身份块）。 */
    private fun hasPersona(request: List<ChatMessage>, persona: String): Boolean =
        request.any { it.role == ChatRole.SYSTEM && it.content.contains(persona) }

    @Test
    fun `first turn carries preamble persona and protocol in one leading system`() = runTest {
        val session = newSession()
        session.systemPrompt = "你是测试人设。"
        session.actionCatalog = listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma"))
        session.sendAndAwait("一")

        val req = llm.requests.last()
        // 单条 system，且是请求的第一条消息（硅基流动对多条 system 400）
        val system = singleSystemOf(req)
        assertEquals(0, req.indexOf(system))
        // 前言在最前（身份/任务/纪律），人设居中，协议（few-shot 收尾）在后
        assertTrue(system.content.startsWith("【身份与任务】"))
        assertTrue(system.content.contains("你是测试人设。"))
        assertTrue(system.content.contains("<act:"))
        assertTrue(system.content.contains("基础动作: wave"))
        assertTrue(system.content.contains("【协议遵循】"))
    }

    @Test
    fun `all thirty turns keep complete stable system after history trimming`() = runTest {
        val session = newSession(options = AvatarSession.Options(recentTurnLimit = 3))
        session.systemPrompt = "你是测试人设。"
        session.actionCatalog = listOf(
            ActionEntry("wave", "挥手", assetPath = "animations/wave.vrma"),
            ActionEntry("nod", "点头", assetPath = "animations/nod.vrma"),
        )
        repeat(30) { session.sendAndAwait("第${it + 1}轮") }
        val initial = singleSystemOf(llm.requests.first())
        for (req in llm.requests) {
            assertEquals(initial, singleSystemOf(req))
            assertEquals(ChatRole.SYSTEM, req.first().role)
            assertTrue(req.first().content.contains("基础动作: wave nod"))
            assertTrue(req.first().content.contains("你是测试人设。"))
            assertTrue(req.size <= 4)
        }
    }

    @Test
    fun `history is canonicalized against current capabilities without changing raw store`() = runTest {
        val store = InMemoryConversationStore()
        val session = newSession(store)
        session.actionCatalog = listOf(ActionEntry("wave", "挥手", assetPath = "wave.vrma"))
        val raw = "<cam:full_body><act:aj_bowing_head>好<act:wave>呀<emo:喜悦:0.8>！" +
            "<emo:unicorn><act:><mood:happy><move:missing>5 < 3（真的）"
        llm.reply = raw
        session.sendAndAwait("一")
        llm.reply = "好的。"
        session.sendAndAwait("二")
        val projected = llm.requests.last().first { it.role == ChatRole.ASSISTANT }.content
        assertEquals("好<act:wave>呀<emo:happy:0.8>！5 < 3（真的）", projected)
        assertEquals(raw, store.messages().first { it.role == ChatRole.ASSISTANT }.content)
        // Catalog and camera switches affect old tags immediately.
        session.actionCatalog = emptyList()
        session.sendAndAwait("三")
        assertFalse(llm.requests.last().first { it.role == ChatRole.ASSISTANT }.content.contains("<act:"))
    }

    @Test
    fun `disabled camera and gesture tags are removed from old history`() = runTest {
        val store = InMemoryConversationStore()
        store.appendAssistant("<cam:full_shot><act:wave><emo:happy>你好")
        val session = newSession(store, AvatarSession.Options(enableLlmCamera = false, enableLlmGestures = false))
        session.actionCatalog = listOf(ActionEntry("wave", "挥手", assetPath = "wave.vrma"))
        session.sendAndAwait("一")
        assertEquals("<emo:happy:1.0>你好", llm.requests.single().first { it.role == ChatRole.ASSISTANT }.content)
    }

    @Test
    fun `failed request does not remove identity from later requests`() = runTest {
        val session = newSession()
        session.systemPrompt = "你是测试人设。"

        // 首轮网络失败，后续请求仍须完整
        llm.failNext = true
        session.sendAndAwait("一")
        assertTrue(hasPersona(llm.requests[0], "你是测试人设。")) // 请求里仍有完整人设

        // 下一轮同样携带完整协议
        session.sendAndAwait("二")
        assertTrue(hasPersona(llm.requests[1], "你是测试人设。"))
        assertTrue(hasProtocol(llm.requests[1]))

        // 后续仍须携带完整身份块
        session.sendAndAwait("三")
        assertTrue(hasPersona(llm.requests[2], "你是测试人设。"))
        assertTrue(hasProtocol(llm.requests[2]))
    }

    @Test
    fun `interrupted request does not remove identity from later requests`() = runTest {
        val hangLlm = HangFirstLlm()
        val session = AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = hangLlm,
            tts = SilentTts(),
            controller = AvatarController(),
            AvatarSession.Options(protocolInstructions = true),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            gestureDriver = GestureDriver(AvatarController()),
            store = InMemoryConversationStore(),
        ).apply {
            llmConfig = LlmConfig("http://t", "k", model = "llm")
            ttsConfig = TtsConfig(model = "tts", voice = "v")
        }
        session.systemPrompt = "你是测试人设。"

        // 第一轮请求在途时被用户打断
        session.send("一")
        session.interrupt()
        // 第二轮正常完成：必须重发完整身份块
        session.sendAndAwait("二")
        assertTrue(hasPersona(hangLlm.requests[1], "你是测试人设。"))
        assertTrue(hasProtocol(hangLlm.requests[1]))
        // 第三轮同样携带完整身份块
        session.sendAndAwait("三")
        assertTrue(hasPersona(hangLlm.requests[2], "你是测试人设。"))
        assertTrue(hasProtocol(hangLlm.requests[2]))
    }

    @Test
    fun `catalog change updates full system and keeps history`() = runTest {
        val session = newSession()
        session.systemPrompt = "人设。"
        session.actionCatalog = listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma"))
        session.sendAndAwait("一")

        session.actionCatalog = listOf(ActionEntry("dance", "跳舞", assetPath = "animations/dance.vrma"))
        session.sendAndAwait("二")
        // 目录变化应立即反映在下一请求的完整身份块中
        val system = singleSystemOf(llm.requests.last())
        assertTrue(system.content.contains("[多模态输出协议"))
        assertTrue(system.content.contains("dance"))
        assertFalse("stale catalog must be replaced", system.content.contains("wave"))
        assertTrue(system.content.contains("人设。"))
        // 保留历史对白；末尾 user 带视角行
        val nonSystem = llm.requests.last().filter { it.role != ChatRole.SYSTEM }.map { it.content }
        assertEquals(3, nonSystem.size)
        assertEquals(listOf("一", "好的。"), nonSystem.take(2))
        assertTrue(nonSystem.last().endsWith("二"))
    }

    @Test
    fun `session rebuild carries current identity with existing history`() = runTest {
        val store = InMemoryConversationStore()
        val firstInstance = newSession(store)
        val firstLlm = llm // newSession 每次都换新的 RecordingLlm
        firstInstance.systemPrompt = "你是测试人设。"
        firstInstance.sendAndAwait("一")
        firstInstance.sendAndAwait("二")
        assertTrue(hasPersona(firstLlm.requests[1], "你是测试人设。"))
        // 会话重建（换 VRM 模型/改设置都会重建，且不轮换上下文）：同一份历史，
        // 新实例首轮重发完整身份块——换模型后的新表情目录靠它到达模型
        val secondInstance = newSession(store)
        secondInstance.systemPrompt = "你是测试人设。"
        secondInstance.actionCatalog = listOf(ActionEntry("dance", "跳舞", assetPath = "animations/dance.vrma"))
        secondInstance.sendAndAwait("三")
        assertEquals(1, llm.requests.size) // 新实例自己的请求流
        assertTrue(hasProtocol(llm.requests[0]))
        assertTrue(hasPersona(llm.requests[0], "你是测试人设。"))
        assertTrue(singleSystemOf(llm.requests[0]).content.contains("dance"))
    }

    @Test
    fun `clear history resends the full identity on the next turn`() = runTest {
        val session = newSession()
        session.systemPrompt = "人设。"
        session.sendAndAwait("一")
        session.sendAndAwait("二")
        assertTrue(hasProtocol(llm.requests[1]))
        // 换卡/清历史 = 新上下文：store 空，下一轮重新带上完整身份块
        session.clearHistory()
        session.sendAndAwait("新开始")
        assertTrue(hasProtocol(llm.requests[2]))
        assertTrue(hasPersona(llm.requests[2], "人设。"))
    }

    @Test
    fun `persona change resends the full identity without stale text`() = runTest {
        val session = newSession()
        session.systemPrompt = "人设A。"
        session.sendAndAwait("一")
        session.systemPrompt = "人设B。"
        session.sendAndAwait("二")

        val system = singleSystemOf(llm.requests.last())
        assertTrue(system.content.contains("人设B。"))
        assertFalse("stale persona must be replaced", system.content.contains("人设A。"))
    }

    @Test
    fun `protocolInstructions=false omits catalog and the adherence reminder`() = runTest {
        llm = RecordingLlm()
        val session = AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = AvatarController(),
            AvatarSession.Options(protocolInstructions = false),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            gestureDriver = GestureDriver(AvatarController()),
            store = InMemoryConversationStore(),
        )
        session.llmConfig = LlmConfig("http://t", "k", model = "llm")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        session.sendAndAwait("你好")

        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertFalse(system.content.contains("<act:"))
        assertTrue(system.content.contains("【身份与任务】"))
        assertFalse("no adherence reminder without protocol", system.content.contains("【协议遵循】"))
    }
}
