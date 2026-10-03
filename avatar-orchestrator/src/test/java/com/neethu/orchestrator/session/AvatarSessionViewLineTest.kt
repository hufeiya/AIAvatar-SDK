package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.corelib.AvatarController
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
 * 需求 2：每轮请求都注入【当前镜头视角】行（挂 controller 时=自由视角占位或
 * 活动机位；headless 不注入）。视角行是请求里唯一逐轮变化的指令，挂在末尾
 * user 消息上（离生成最近，且不破坏 [人设][协议][历史] 前缀的稳定）；
 * 活动机位分支由真机验证（getActiveCameraShot 需要渲染器）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionViewLineTest {

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

    private fun newSession(
        controller: AvatarController?,
        protocolInstructions: Boolean = false,
    ): Pair<AvatarSession, RecordingLlm> {
        val llm = RecordingLlm()
        val session = AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = controller,
            AvatarSession.Options(protocolInstructions = protocolInstructions),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = InMemoryConversationStore(),
        )
        session.llmConfig = LlmConfig("http://t", "k", model = "llm")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        return session to llm
    }

    @Test
    fun `view line rides the trailing user message with a bound controller`() = runTest {
        val (session, llm) = newSession(AvatarController())
        session.sendAndAwait("你好")
        val req = llm.requests.last()
        // system 通道（人设/协议）不出现视角行——它是逐轮变化的，进了 system
        // 就会打断请求前缀的逐字节稳定
        assertTrue(
            req.filter { it.role == ChatRole.SYSTEM }.none { it.content.contains("【当前镜头视角】") },
        )
        val last = req.last()
        assertEquals(ChatRole.USER, last.role)
        assertTrue(last.content.contains("【当前镜头视角】"))
        assertTrue(last.content.contains("自由视角"))
        // 视角行是前缀，用户原文完整保留在其后
        assertTrue(last.content.endsWith("你好"))
    }

    @Test
    fun `no view line when headless`() = runTest {
        val (session, llm) = newSession(null)
        session.sendAndAwait("你好")
        val req = llm.requests.last()
        assertTrue(
            req.filter { it.role == ChatRole.SYSTEM }.none { it.content.contains("【当前镜头视角】") },
        )
        // headless 连视角行都不挂：末尾 user 消息就是干净的原文
        val last = req.last()
        assertEquals(ChatRole.USER, last.role)
        assertEquals("你好", last.content)
    }

    @Test
    fun `view line sits after the pinned protocol - few-shot stays in the stable prefix`() = runTest {
        val (session, llm) = newSession(AvatarController(), protocolInstructions = true)
        session.sendAndAwait("你好")
        val req = llm.requests.last()
        assertEquals(ChatRole.USER, req.last().role)
        // 协议块整条（含 few-shot 输出示例）在稳定前缀的 system 消息里，
        // 不含视角行；视角行只出现在末尾 user 消息上（离生成位置最近）
        val protocol = req.first { it.role == ChatRole.SYSTEM }.content
        assertTrue(protocol.contains("输出示例"))
        assertFalse(protocol.contains("【当前镜头视角】"))
        assertTrue(req.last().content.startsWith("【当前镜头视角】"))
    }
}
