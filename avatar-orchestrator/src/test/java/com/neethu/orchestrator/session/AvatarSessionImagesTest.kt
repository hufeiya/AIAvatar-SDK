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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 视频模式的多模态请求（AvatarSession.send 带 images）：图片只挂到本轮请求
 * 的末尾 user 消息上；store 历史始终只存文字——历史带图会让 vision-token
 * 成本随轮数平方增长（设计决策，见 send 的 doc）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionImagesTest {

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
            return flowOf(LlmStreamEvent.TextDelta("看到了。"))
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
            controller = null,
            AvatarSession.Options(),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = InMemoryConversationStore(),
        )
    }

    private fun configure(s: AvatarSession) {
        s.llmConfig = LlmConfig("http://t", "k", model = "llm")
        s.ttsConfig = TtsConfig(model = "tts", voice = "v")
    }

    private val dataUrl = "data:image/jpeg;base64,QUJD"

    @Test
    fun `images attach to the trailing user message of the current request`() = runTest {
        val s = newSession()
        configure(s)
        s.sendAndAwait("你看我身后有什么?", images = listOf(dataUrl))

        val req = llm.requests.last()
        val userMessages = req.filter { it.role == ChatRole.USER }
        assertEquals(1, userMessages.size)
        assertEquals(listOf(dataUrl), userMessages.single().images)
        assertEquals("你看我身后有什么?", userMessages.single().content)
        // system/assistant 通道永不带图
        assertTrue(req.filter { it.role != ChatRole.USER }.all { it.images.isEmpty() })
    }

    @Test
    fun `history keeps text only - the next request carries no images`() = runTest {
        val s = newSession()
        configure(s)
        s.sendAndAwait("这是什么", images = listOf(dataUrl))
        s.sendAndAwait("谢谢")

        val second = llm.requests.last()
        assertTrue("images leaked into history", second.all { it.images.isEmpty() })
        val nonSystem = second.filter { it.role != ChatRole.SYSTEM }
        assertEquals(listOf("这是什么", "看到了。", "谢谢"), nonSystem.map { it.content })
    }

    @Test
    fun `send without images is unchanged`() = runTest {
        val s = newSession()
        configure(s)
        s.sendAndAwait("你好")
        assertTrue(llm.requests.last().all { it.images.isEmpty() })
    }
}
