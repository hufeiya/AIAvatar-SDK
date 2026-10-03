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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 需求 2：每轮请求的 system prompt 都注入【当前镜头视角】行（挂 controller
 * 时=自由视角占位或活动机位；headless 不注入）。活动机位分支由真机验证
 * （getActiveCameraShot 需要渲染器）。
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
    fun `view line injected with a bound controller - free view placeholder`() = runTest {
        val (session, llm) = newSession(AvatarController())
        session.sendAndAwait("你好")
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("【当前镜头视角】"))
        assertTrue(system.content.contains("自由视角"))
    }

    @Test
    fun `no view line when headless`() = runTest {
        val (session, llm) = newSession(null)
        session.sendAndAwait("你好")
        val system = llm.requests.last().firstOrNull { it.role == ChatRole.SYSTEM }
        // headless + 协议关闭时整个 system 为空(不伪造视角行);出现时也必须不含视角
        if (system != null) assertFalse(system.content.contains("【当前镜头视角】"))
    }

    @Test
    fun `view line sits before protocol block keeps few-shot last`() = runTest {
        val (session, llm) = newSession(AvatarController(), protocolInstructions = true)
        session.sendAndAwait("你好")
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }.content
        val viewIdx = system.indexOf("【当前镜头视角】")
        assertTrue(viewIdx >= 0)
        // 协议块的 few-shot 示例必须保持在视角行之后(提示词最末,§7.10 近因效应)
        val exampleIdx = system.lastIndexOf("输出示例")
        if (exampleIdx >= 0) assertTrue("view must precede the few-shot example", viewIdx < exampleIdx)
    }
}
