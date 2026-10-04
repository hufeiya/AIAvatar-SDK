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
 * 协议块（全量表情/动作/镜头目录）每上下文只组装一次（用户需求：目录不逐轮
 * 重发重拼）：请求 = [人设+协议合并的一条 system][历史][末尾 user]。**必须
 * 合并为单条 system**——实测硅基流动对两条 system 直接 400（"System message
 * must be at the beginning"，即使两条都在最前，A.1 第 34 条）。同一上下文内
 * 协议文本逐轮逐字节复用（服务商前缀缓存友好）；目录变化（重载模型/镜头开关）
 * 原地重钉，历史保留——"换模型即新开上下文"由集成方轮换上下文 id 实现
 * （demo 见 MainActivity.updateAiPrefs）。
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

    private lateinit var llm: RecordingLlm

    private fun newSession(): AvatarSession {
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
            store = InMemoryConversationStore(),
        ).apply {
            llmConfig = LlmConfig("http://t", "k", model = "llm")
            ttsConfig = TtsConfig(model = "tts", voice = "v")
        }
    }

    /** 人设+协议合并后的那条 system；断言全请求只有这一条 system。 */
    private fun singleSystemOf(request: List<ChatMessage>): ChatMessage {
        val systems = request.filter { it.role == ChatRole.SYSTEM }
        assertEquals("expected exactly ONE system message", 1, systems.size)
        return systems.single()
    }

    @Test
    fun `persona and protocol merge into one leading system message`() = runTest {
        val session = newSession()
        session.systemPrompt = "你是测试人设。"
        session.actionCatalog = listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma"))
        session.sendAndAwait("一")

        val req = llm.requests.last()
        // 单条 system，且是请求的第一条消息（硅基流动对多条 system 400）
        val system = singleSystemOf(req)
        assertEquals(0, req.indexOf(system))
        // 人设在前、协议在后，同一条里
        assertTrue(system.content.startsWith("你是测试人设。"))
        assertTrue(system.content.contains("<act:"))
        assertTrue(system.content.contains("基础动作: wave"))
    }

    @Test
    fun `protocol is byte-identical across turns of the same context`() = runTest {
        val session = newSession()
        session.actionCatalog = listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma"))
        session.sendAndAwait("一")
        session.sendAndAwait("二")

        assertEquals(2, llm.requests.size)
        // 人设为空时 system 即纯协议文本：逐字节同一份（前缀缓存的前提）
        assertEquals(singleSystemOf(llm.requests[0]), singleSystemOf(llm.requests[1]))
    }

    @Test
    fun `catalog change re-pins the protocol in place and keeps history`() = runTest {
        val session = newSession()
        session.actionCatalog = listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma"))
        session.sendAndAwait("一")

        session.actionCatalog = listOf(ActionEntry("dance", "跳舞", assetPath = "animations/dance.vrma"))
        session.sendAndAwait("二")

        val system = singleSystemOf(llm.requests.last())
        assertTrue(system.content.contains("dance"))
        assertFalse("stale catalog must be replaced", system.content.contains("wave"))
        // 历史不受重钉影响：turn1 的 user/assistant 原样在；末尾 user 带视角行
        // 前缀（挂 controller 时每轮注入），原文"二"在其后
        val nonSystem = llm.requests.last().filter { it.role != ChatRole.SYSTEM }.map { it.content }
        assertEquals(3, nonSystem.size)
        assertEquals(listOf("一", "好的。"), nonSystem.take(2))
        assertTrue(nonSystem.last().endsWith("二"))
    }

    @Test
    fun `persona change rewrites the merged system without stale text`() = runTest {
        val session = newSession()
        session.systemPrompt = "人设A。"
        session.sendAndAwait("一")
        session.systemPrompt = "人设B。"
        session.sendAndAwait("二")

        val system = singleSystemOf(llm.requests.last())
        assertTrue(system.content.startsWith("人设B。"))
        assertFalse("stale persona must be replaced", system.content.contains("人设A。"))
    }

    @Test
    fun `protocolInstructions=false sends no protocol content`() = runTest {
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

        assertTrue(llm.requests.last().none { it.role == ChatRole.SYSTEM && it.content.contains("<act:") })
    }
}
