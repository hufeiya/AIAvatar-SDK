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
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.memory.AgentMemory
import com.neethu.orchestrator.memory.InMemoryMemoryStore
import com.neethu.orchestrator.memory.MemoryCategories
import com.neethu.orchestrator.memory.MemoryTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 长期记忆的会话级集成（docs/memory-feasibility.md §4.5/§4.6/§6）：空库请求
 * 与无记忆会话逐字节一致、[记忆参考] 块挂在末尾 user 消息（视角行之后/用户
 * 原文之前）、回合收尾触发提炼、失败路径不进窗口、开关关=零记忆痕迹（工具
 * 退场+零注入+可恢复）、纯 TTS 会话不建协调器。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionMemoryTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 提炼回复可脚本化的 LLM（主回合恒回「好的。」；提炼请求按形态识别）。 */
    private class ScriptedLlm(var extractReply: String = """{"ops":[]}""") : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages.toList()
            // 提炼请求以 system 的记忆指令为标记（主回合首条 system 是身份前言）
            val isExtraction = messages.firstOrNull()
                ?.content?.contains("长期记忆库") == true
            return flowOf(
                LlmStreamEvent.TextDelta(if (isExtraction) extractReply else "好的。"),
            )
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

    /**
     * 会话 scope 用共享 testScheduler 的 StandardTestDispatcher：回合内
     * scope.launch 的提炼任务是嵌套调度，unconfined 线程局部事件循环在断言前
     * 不会跑它——显式 advanceUntilIdle 才确定性收尾（见 [settle]）。
     */
    private fun newSession(
        llm: ScriptedLlm,
        memoryStore: InMemoryMemoryStore?,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        options: AvatarSession.Options = AvatarSession.Options(protocolInstructions = false),
    ): AvatarSession {
        val session = AvatarSession(
            scope = CoroutineScope(StandardTestDispatcher(scheduler)),
            llm = llm,
            tts = SilentTts(),
            controller = AvatarController(),
            options = options,
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = InMemoryConversationStore(),
            memoryStore = memoryStore,
        )
        session.llmConfig = LlmConfig("http://x", "k", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        return session
    }

    private suspend fun seededStore(): InMemoryMemoryStore {
        val store = InMemoryMemoryStore()
        store.add(
            AgentMemory(
                id = "m1",
                content = "用户叫小明,是 Android 工程师",
                category = MemoryCategories.PROFILE,
                importance = 9,
                source = "auto",
                createdAt = 0,
                updatedAt = 0,
                lastAccessedAt = 0,
            ),
        )
        return store
    }

    private suspend fun kotlinx.coroutines.test.TestScope.settle(session: AvatarSession, text: String) {
        session.sendAndAwait(text)
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `empty memory store keeps requests identical to a session without memory`() = runTest {
        val withMemory = newSession(ScriptedLlm(), InMemoryMemoryStore(), testScheduler)
        val without = newSession(ScriptedLlm(), null, testScheduler)
        settle(withMemory, "你好")
        settle(without, "你好")
        val reqWith = withMemoryScripted(withMemory).requests.last()
        val reqWithout = withoutScripted(without).requests.last()
        // 空库 = 零注入：末尾 user 与 system 都逐字节一致
        assertEquals(reqWithout.last().content, reqWith.last().content)
        assertEquals(
            reqWithout.first { it.role == ChatRole.SYSTEM }.content,
            reqWith.first { it.role == ChatRole.SYSTEM }.content,
        )
    }

    private fun withMemoryScripted(session: AvatarSession): ScriptedLlm = sessionScripted(session)
    private fun withoutScripted(session: AvatarSession): ScriptedLlm = sessionScripted(session)

    private fun sessionScripted(session: AvatarSession): ScriptedLlm {
        val field = AvatarSession::class.java.getDeclaredField("llm")
        field.isAccessible = true
        return field.get(session) as ScriptedLlm
    }

    @Test
    fun `memory line rides the trailing user message after the view line`() = runTest {
        val session = newSession(ScriptedLlm(), seededStore(), testScheduler)
        settle(session, "我叫什么名字")
        val last = sessionScripted(session).requests.last().last()
        assertEquals(ChatRole.USER, last.role)
        val idxView = last.content.indexOf("【当前镜头视角】")
        val idxMemory = last.content.indexOf("[记忆参考]")
        val idxUser = last.content.indexOf("我叫什么名字")
        assertTrue(idxView in 0 until idxMemory)
        assertTrue(idxMemory < idxUser)
        assertTrue(last.content.contains("- (身份) 用户叫小明,是 Android 工程师"))
    }

    @Test
    fun `turn completions feed the window and extraction lands in the store`() = runTest {
        val store = InMemoryMemoryStore()
        val llm = ScriptedLlm(
            """{"ops":[{"action":"add","content":"用户在准备考试","category":"event","importance":6}]}""",
        )
        val session = newSession(
            llm, store, testScheduler,
            AvatarSession.Options(protocolInstructions = false, memoryExtractEveryTurns = 2),
        )
        settle(session, "我下周要考试")
        assertEquals(0, store.count()) // 未达阈值只攒窗
        settle(session, "最近有点焦虑")
        assertEquals("用户在准备考试", store.all().single().content) // 第 2 轮收尾触发提炼
    }

    @Test
    fun `failed turn never reaches the window`() = runTest {
        val store = InMemoryMemoryStore()
        val session = newSession(ScriptedLlm(), store, testScheduler)
        // 失败路径（缺 ttsConfig）在 store.appendAssistant 之前抛出 → 窗口零追加
        session.ttsConfig = null
        settle(session, "这轮会失败")
        assertEquals(0, store.count())
        assertTrue(session.memoryCoordinator!!.windowLine().startsWith("window=0 turns/"))
    }

    @Test
    fun `memory toggle off removes tools and injection and recovers`() = runTest {
        val session = newSession(ScriptedLlm(), seededStore(), testScheduler)
        assertTrue(session.tools.find(MemoryTools.NAME_REMEMBER) != null)
        assertTrue(session.tools.find(MemoryTools.NAME_RECALL) != null)
        assertTrue(session.tools.find(MemoryTools.NAME_FORGET) != null)
        settle(session, "第一句")

        session.setMemoryEnabled(false)
        assertNull(session.tools.find(MemoryTools.NAME_REMEMBER))
        assertNull(session.tools.find(MemoryTools.NAME_RECALL))
        assertNull(session.tools.find(MemoryTools.NAME_FORGET))
        settle(session, "第二句")
        assertFalse(sessionScripted(session).requests.last().last().content.contains("[记忆参考]"))

        session.setMemoryEnabled(true)
        assertTrue(session.tools.find(MemoryTools.NAME_REMEMBER) != null)
        settle(session, "第三句")
        assertTrue(sessionScripted(session).requests.last().last().content.contains("[记忆参考]"))
    }

    @Test
    fun `speak-only session builds no coordinator`() {
        val session = AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = null,
            tts = SilentTts(),
            controller = AvatarController(),
            options = AvatarSession.Options(),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = InMemoryConversationStore(),
            memoryStore = InMemoryMemoryStore(),
        )
        assertNull(session.memoryCoordinator)
    }
}
