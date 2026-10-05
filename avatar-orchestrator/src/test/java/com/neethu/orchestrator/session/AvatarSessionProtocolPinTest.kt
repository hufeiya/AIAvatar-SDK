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

/**
 * system 提示词的发送策略（用户需求 5 条，2026-10-05）：
 *  1. **前言每轮恒带**（身份与任务=3D 虚拟人/台词纪律=只说口语、动作表情用标签/
 *     协议遵循提醒），实例内逐字节稳定；
 *  2. **人设全文+协议目录（完整身份块）只随上下文第一轮发送**——之后历史里
 *     留着模型自己发的标签做自我示范；
 *  3. **没送达不算发送**：LLM 流失败/被打断的回合不提交「已发送」标记，
 *     下一轮自动重发完整身份块；
 *  4. 人设或目录指纹变化（重载模型/换动作来源）→ 重发；会话重建后新实例首轮
 *     必重发（自愈：换 VRM 模型不轮换上下文）；clearHistory/切上下文=新首轮。
 * 协议必须与人设/前言合并为**单条** system——实测硅基流动对多条 system 直接
 * 400（"System message must be at the beginning"，A.1 第 34 条）。
 */
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

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            return if (failNext) {
                failNext = false
                flowOf(LlmStreamEvent.Error(RuntimeException("simulated network failure")))
            } else {
                flowOf(LlmStreamEvent.TextDelta("好的。"))
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

    private fun newSession(store: InMemoryConversationStore = InMemoryConversationStore()): AvatarSession {
        llm = RecordingLlm()
        return AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = AvatarController(),
            AvatarSession.Options(protocolInstructions = true),
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
    fun `later turns carry only the preamble`() = runTest {
        val session = newSession()
        session.systemPrompt = "你是测试人设。"
        session.actionCatalog = listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma"))
        session.sendAndAwait("一")
        session.sendAndAwait("二")
        session.sendAndAwait("三")

        assertEquals(3, llm.requests.size)
        assertTrue(hasPersona(llm.requests[0], "你是测试人设。"))
        // 后续轮：人设与协议都省略，只剩前言（身份/任务/纪律+协议遵循提醒）。
        // 注意断言用目录专属串（"基础动作: wave"/"[多模态输出协议"）而不是
        // "<act:"——协议遵循提醒文本里合法地包含标签家族写法 <emo:/<act:/<cam:
        for (i in 1..2) {
            val system = singleSystemOf(llm.requests[i])
            assertTrue(system.content.startsWith("【身份与任务】"))
            assertFalse("persona must not repeat every turn", system.content.contains("你是测试人设。"))
            assertFalse("catalog must not repeat every turn", system.content.contains("基础动作: wave"))
            assertFalse(system.content.contains("[多模态输出协议"))
            assertTrue("protocol adherence reminder must stay", system.content.contains("【协议遵循】"))
        }
        // 前言逐字节稳定（前缀缓存命中的前提）
        assertEquals(singleSystemOf(llm.requests[1]), singleSystemOf(llm.requests[2]))
    }

    @Test
    fun `failed identity turn does not commit delivery - next turn resends full identity`() = runTest {
        val session = newSession()
        session.systemPrompt = "你是测试人设。"

        // 首轮（携带完整身份块）失败：流中途报错，人设/协议没算送达
        llm.failNext = true
        session.sendAndAwait("一")
        assertTrue(hasPersona(llm.requests[0], "你是测试人设。")) // 请求里带了，但没算数

        // 下一轮必须重发（用户需求：第一次没发送成功，人设/协议下一次不能省略）
        session.sendAndAwait("二")
        assertTrue(hasPersona(llm.requests[1], "你是测试人设。"))
        assertTrue(hasProtocol(llm.requests[1]))

        // 重发成功后才恢复正常省略
        session.sendAndAwait("三")
        assertFalse(hasPersona(llm.requests[2], "你是测试人设。"))
        assertFalse(hasProtocol(llm.requests[2]))
    }

    @Test
    fun `interrupted turn does not commit delivery - next turn resends full identity`() = runTest {
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

        // 第一轮：请求在途时被用户打断（barge-in）——流没走完，不提交送达
        session.send("一")
        session.interrupt()
        // 第二轮正常完成：必须重发完整身份块
        session.sendAndAwait("二")
        assertTrue(hasPersona(hangLlm.requests[1], "你是测试人设。"))
        assertTrue(hasProtocol(hangLlm.requests[1]))
        // 第三轮：重发已成功提交 → 恢复精简（只带前言）
        session.sendAndAwait("三")
        assertFalse(hasPersona(hangLlm.requests[2], "你是测试人设。"))
        assertFalse(hasProtocol(hangLlm.requests[2]))
    }

    @Test
    fun `catalog change resends the full identity once and keeps history`() = runTest {
        val session = newSession()
        session.systemPrompt = "人设。"
        session.actionCatalog = listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma"))
        session.sendAndAwait("一")

        session.actionCatalog = listOf(ActionEntry("dance", "跳舞", assetPath = "animations/dance.vrma"))
        session.sendAndAwait("二")
        // 第二轮本该精简——但目录变了，完整身份块（人设+新目录）必须重发
        val system = singleSystemOf(llm.requests.last())
        assertTrue(system.content.contains("[多模态输出协议"))
        assertTrue(system.content.contains("dance"))
        assertFalse("stale catalog must be replaced", system.content.contains("wave"))
        assertTrue(system.content.contains("人设。"))
        // 历史不受重发影响：turn1 的 user/assistant 原样在；末尾 user 带视角行
        val nonSystem = llm.requests.last().filter { it.role != ChatRole.SYSTEM }.map { it.content }
        assertEquals(3, nonSystem.size)
        assertEquals(listOf("一", "好的。"), nonSystem.take(2))
        assertTrue(nonSystem.last().endsWith("二"))
    }

    @Test
    fun `session rebuild resends the full identity once to self-heal catalog drift`() = runTest {
        val store = InMemoryConversationStore()
        val firstInstance = newSession(store)
        val firstLlm = llm // newSession 每次都换新的 RecordingLlm
        firstInstance.systemPrompt = "你是测试人设。"
        firstInstance.sendAndAwait("一")
        firstInstance.sendAndAwait("二")
        assertFalse(hasPersona(firstLlm.requests[1], "你是测试人设。"))
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
        assertFalse(hasProtocol(llm.requests[1]))
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
