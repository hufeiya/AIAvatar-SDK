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
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * 历史摘要压缩（酒馆 P2-4，docs/tavern-companion-feasibility.md §5 P2-4）：
 * 超出 recentTurnLimit 的早期历史滚动摘要进 system（摘要管「剧情」），触发
 * 阈值=新增溢出消息数；失败保留旧摘要；clearHistory/importHistory 重置。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionHistorySummaryTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingLlm(var failSummary: Boolean = false) : LlmAdapter {
        // 后台摘要任务与本线程并发追加（unconfined 重入），读侧一律快照
        val requests = java.util.concurrent.CopyOnWriteArrayList<List<ChatMessage>>()

        /** 摘要调用签名：system 是记录员指令、且请求只有两条消息（无历史）。 */
        private fun isSummary(messages: List<ChatMessage>): Boolean =
            messages.size == 2 && messages[0].content.contains("记录员")

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            if (failSummary && isSummary(messages)) {
                return flowOf(LlmStreamEvent.Error(IllegalStateException("summary boom")))
            }
            val text = if (isSummary(messages)) "合并后的摘要内容。" else "好的。"
            return flowOf(LlmStreamEvent.TextDelta(text))
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

    private fun newSession(llm: RecordingLlm, scheduler: TestCoroutineScheduler): AvatarSession = AvatarSession(
        // 与 runTest 同一调度器：unconfined 排队的后台摘要任务可被 advanceUntilIdle 确定性冲刷
        scope = CoroutineScope(UnconfinedTestDispatcher(scheduler)),
        llm = llm,
        tts = SilentTts(),
        controller = null,
        AvatarSession.Options(
            protocolInstructions = false,
            recentTurnLimit = 4,
            historySummaryEveryMessages = 4,
        ),
        playbackQueue = RecordingQueue(),
        lipSyncProcessor = null,
        store = InMemoryConversationStore(),
    ).apply {
        this.llmConfig = LlmConfig("http://t", "k", model = "llm")
        ttsConfig = TtsConfig(model = "tts", voice = "v")
    }

    private suspend fun AvatarSession.chat(i: Int) = sendAndAwait("消息$i")

    @Test
    fun `overflow below threshold - no summary and no summary call`() = runTest {
        val llm = RecordingLlm()
        val session = newSession(llm, testScheduler)
        // 3 交换 = 6 条消息，overflow = 2 < 4：不触发摘要
        repeat(3) { session.chat(it) }
        val summaryCalls = llm.requests.toList().count { it.size == 2 && it[0].content.contains("记录员") }
        org.junit.Assert.assertEquals(0, summaryCalls)
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertFalse(system.content.contains("前情摘要"))
    }

    @Test
    fun `overflow past threshold - summary call then block rides system`() = runTest {
        val llm = RecordingLlm()
        val session = newSession(llm, testScheduler)
        // 3 交换 = 6 条：第 4 交换开头 overflow=6、新增 6 ≥ 4 → 触发摘要
        repeat(3) { session.chat(it) }
        session.chat(3)
        // 摘要任务可能在 Default 线程上异步跑（与断言赛跑=偶发翻车），join 定界
        session.awaitHistorySummaryForTest()
        val summaryCalls = llm.requests.toList().filter { it.size == 2 && it[0].content.contains("记录员") }
        org.junit.Assert.assertEquals(1, summaryCalls.size)
        // 摘要输入带历史原文（用户:/角色: 前缀）
        val instruction = summaryCalls[0][0].content
        assertTrue(instruction.contains("[user] 消息0"))
        assertTrue(instruction.contains("[assistant] 好的。"))
        // 摘要块只进请求，不进 store
        assertTrue(session.historyMessages().all { !it.content.contains("前情摘要") })
        // 下一轮请求的 system 带摘要块
        session.chat(4)
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("【前情摘要】"))
        assertTrue(system.content.contains("合并后的摘要内容。"))
    }

    @Test
    fun `rolling update merges previous summary`() = runTest {
        val llm = RecordingLlm()
        val session = newSession(llm, testScheduler)
        // 后台摘要在下一回合边界才真正执行（unconfined 排队），触发时的
        // overflow 按执行时刻计：chat(3) 触发、chat(4) 执行（covers=6）、
        // chat(6) 二次滚动（overflow=10，新增 4 ≥ 4）
        repeat(4) { session.chat(it) }
        session.chat(4)
        session.chat(5)
        session.chat(6)
        val summaryCalls = llm.requests.toList().filter { it.size == 2 && it[0].content.contains("记录员") }
        org.junit.Assert.assertEquals(2, summaryCalls.size)
        // 第二次调用把旧摘要合并进来
        assertTrue(summaryCalls[1][0].content.contains("已有旧摘要"))
        assertTrue(summaryCalls[1][0].content.contains("合并后的摘要内容。"))
    }

    @Test
    fun `summary failure keeps old and later turn still succeeds`() = runTest {
        val llm = RecordingLlm(failSummary = true)
        val session = newSession(llm, testScheduler)
        repeat(4) { session.chat(it) } // chat(3) 触发摘要（失败，covers 不动）
        session.chat(4) // 失败后 covers=0，新增溢出 6 ≥ 4：再次尝试仍失败，旧摘要保持
        session.awaitHistorySummaryForTest()
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertFalse(system.content.contains("前情摘要"))
        // 回合本身不受影响（失败重试的都是摘要调用）
        assertTrue(llm.requests.toList().any { row -> row.any { it.content == "消息4" } })
    }

    @Test
    fun `clearHistory resets summary`() = runTest {
        val llm = RecordingLlm()
        val session = newSession(llm, testScheduler)
        repeat(4) { session.chat(it) }
        session.awaitHistorySummaryForTest()
        session.clearHistory()
        session.chat(9)
        val system = llm.requests.last().first { it.role == ChatRole.SYSTEM }
        assertFalse(system.content.contains("前情摘要"))
    }
}
