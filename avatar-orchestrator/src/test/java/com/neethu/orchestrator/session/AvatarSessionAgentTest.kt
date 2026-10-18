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
import com.neethu.aiadapter.model.ToolCallRequest
import com.neethu.aiadapter.model.ToolSpec
import com.neethu.corelib.AvatarController
import com.neethu.orchestrator.agent.ToolExecutor
import com.neethu.orchestrator.agent.ToolResult
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.history.ConversationStore
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.skill.AvatarSkill
import com.neethu.orchestrator.skill.SkillContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P0 工具调用循环（docs/agent-feasibility.md §4.5）：模型请求工具 → 顺序执行
 * → assistant(tool_calls)+tool 结果回灌 → 续流；文本流水跨轮连续、store 只存
 * 说出口的话、工具失败/坏参数/超限/打断都不炸回合。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionAgentTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingQueue : PlaybackQueue {
        override var listener: PlaybackQueue.Listener? = null
        val played = mutableListOf<PlaybackItem>()

        override fun active(): ActivePlayback? = null

        override fun enqueue(item: PlaybackItem) {
            played += item
            listener?.onPlaybackStarted(item)
            listener?.onPlaybackEnded(item)
        }

        override fun stopAll(reason: String) = Unit

        override fun release() = Unit
    }

    private class FakeTts : TtsAdapter {
        val synthesized = mutableListOf<String>()
        override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
            synthesized += text
            return TtsResult(ByteArray(64), TtsAudioFormat.RAW_PCM_16LE, 16_000)
        }
    }

    /**
     * 剧本 LLM：每次 streamChat 弹一份剧本（真 adapter 以 Finish 收尾后完成
     * flow，fake 同构），并记录每次请求的 (messages, config) 供断言。
     */
    private class ScriptedLlm : LlmAdapter {
        val scripts = ArrayDeque<List<LlmStreamEvent>>()
        val requests = mutableListOf<Pair<List<ChatMessage>, LlmConfig>>()

        fun text(delta: String) = LlmStreamEvent.TextDelta(delta)
        fun finish(vararg calls: ToolCallRequest) = LlmStreamEvent.Finish("stop", null, calls.toList())

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            // 快照：工具循环就地扩展 messages（assistant/tool 追加），必须存副本
            // 否则已记录的请求会被后续轮次改写
            requests += messages.toList() to config
            val script = scripts.removeFirstOrNull() ?: error("no script for request #${requests.size}")
            return flow { script.forEach { emit(it) } }
        }
    }

    private fun toolCall(id: String, name: String = "get_time", args: String = "{}") =
        ToolCallRequest(id, name, args)

    private fun newSession(
        llm: ScriptedLlm,
        options: AvatarSession.Options = AvatarSession.Options(),
        store: ConversationStore = InMemoryConversationStore(),
        tts: FakeTts = FakeTts(),
    ): AvatarSession =
        AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = tts,
            controller = AvatarController(),
            options = options,
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = store,
            heavyWorkDispatcher = UnconfinedTestDispatcher(),
        )

    private fun TestScope.collectInto(session: AvatarSession, events: MutableList<AvatarEvent>) {
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { events += it }
        }
    }

    // ── 循环主干 ─────────────────────────────────────────────────────────────

    @Test
    fun `tool continuation retains system and normalizes assistant tags without rewriting audit`() = runTest {
        val llm = ScriptedLlm()
        val store = InMemoryConversationStore()
        val session = newSession(llm, store = store)
        session.systemPrompt = "测试人设"
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("结果") }
        val raw = "<cam:full_body><act:aj_bowing_head><emo:喜悦:0.8>查询中。"
        llm.scripts += listOf(llm.text(raw), llm.finish(toolCall("c")))
        llm.scripts += listOf(llm.text("查到了。"), llm.finish())
        session.sendAndAwait("查一下")
        assertEquals(2, llm.requests.size)
        val first = llm.requests[0].first.first()
        val continuation = llm.requests[1].first
        assertEquals(first, continuation.first())
        assertEquals(ChatRole.SYSTEM, first.role)
        assertTrue(first.content.contains("测试人设"))
        assertTrue(first.content.contains("[多模态输出协议"))
        val assistant = continuation.single { it.role == ChatRole.ASSISTANT }
        assertEquals("<emo:happy:0.8>查询中。", assistant.content)
        assertEquals("c", assistant.toolCalls.single().id)
        assertEquals(raw + "查到了。", store.messages().last().content)
    }

    @Test
    fun `tool round continues the turn and feeds results back in protocol shape`() = runTest {
        val llm = ScriptedLlm()
        val store = InMemoryConversationStore()
        val session = newSession(llm, store = store)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("2026-10-11 21:34 周日 (UTC+8)") }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        // 第 1 轮：说半句 + 请求工具（含 adapter 的 Started 事件流）；第 2 轮：接着说完
        llm.scripts += listOf(
            llm.text("让我看看。"),
            LlmStreamEvent.ToolCallStarted(0, "call_1", "get_time"),
            llm.finish(toolCall("call_1")),
        )
        llm.scripts += listOf(llm.text("现在是周日晚上九点多了。"), llm.finish())
        session.send("现在几点?")
        runCurrent()

        // 请求形态：第 1 轮带全量注册清单（2 内置 + 1 注册）；第 2 轮 messages
        // 追加 assistant(tool_calls) + tool
        assertEquals(2, llm.requests.size)
        val (firstMessages, firstCfg) = llm.requests[0]
        assertEquals(
            listOf("get_device_time", "get_current_view", "get_time"),
            firstCfg.tools.map { it.name },
        )
        assertEquals(firstMessages.size, 2) // system + user
        val (secondMessages, secondCfg) = llm.requests[1]
        assertEquals(firstCfg.tools, secondCfg.tools)
        val assistant = secondMessages.lastOrNull { it.role == ChatRole.ASSISTANT }!!
        assertEquals("让我看看。", assistant.content)
        assertEquals(1, assistant.toolCalls.size)
        assertEquals("call_1", assistant.toolCalls[0].id)
        val toolMsg = secondMessages.last()
        assertEquals(ChatRole.TOOL, toolMsg.role)
        assertEquals("call_1", toolMsg.toolCallId)
        assertEquals("2026-10-11 21:34 周日 (UTC+8)", toolMsg.content)

        // 事件序列：Started → Issued → Finished → TurnCompleted
        val names = events.mapNotNull {
            when (it) {
                is AvatarEvent.ToolCallStarted -> "started:${it.name}"
                is AvatarEvent.ToolCallsIssued -> "issued:${it.names}"
                is AvatarEvent.ToolCallFinished -> "finished:${it.name}/${it.isError}"
                is AvatarEvent.TurnCompleted -> "completed"
                else -> null
            }
        }
        assertEquals(
            listOf("started:get_time", "issued:[get_time]", "finished:get_time/false", "completed"),
            names,
        )
        // store 只存说出口的话：user + 跨轮拼接的全量 raw（工具消息不落库）
        assertEquals(2, store.messages().size)
        assertEquals("现在几点?", store.messages()[0].content)
        assertEquals("让我看看。现在是周日晚上九点多了。", store.messages()[1].content)
        assertTrue(session.agentSnapshot().contains("lastToolCalls=[get_time]"))
    }

    @Test
    fun `unregistered-registry session sends a request with no tools keys`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        // 清空内置注册 → 注册表为空 = 请求体不带 tools（严格端点 400 防线）
        session.tools.unregister("get_device_time")
        session.tools.unregister("get_current_view")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        llm.scripts += listOf(llm.text("你好呀。"), llm.finish())
        session.send("你好")
        runCurrent()

        assertEquals(1, llm.requests.size)
        val (_, cfg) = llm.requests[0]
        assertTrue(cfg.tools.isEmpty())
        assertNull(cfg.toolChoice)
        assertTrue(events.any { it is AvatarEvent.TurnCompleted })
    }

    @Test
    fun `agentToolsEnabled false keeps the registry empty and never sends tools`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm, options = AvatarSession.Options(agentToolsEnabled = false))
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")

        llm.scripts += listOf(llm.finish(toolCall("call_x")))
        session.send("hi")
        runCurrent()

        // 总开关关闭：内置工具不注册，模型即便违规请求工具也不执行、不回灌
        assertTrue(session.tools.list().isEmpty())
        assertEquals(1, llm.requests.size)
        assertTrue(llm.requests[0].second.tools.isEmpty())
    }

    @Test
    fun `tool error result is fed back and the turn completes`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { error("device exploded") }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        llm.scripts += listOf(llm.finish(toolCall("call_1")))
        llm.scripts += listOf(llm.text("抱歉，我看不了。"), llm.finish())
        session.send("看什么")
        runCurrent()

        val toolMsg = llm.requests[1].first.last()
        assertEquals(ChatRole.TOOL, toolMsg.role)
        assertTrue(toolMsg.content.contains("device exploded"))
        assertTrue(events.filterIsInstance<AvatarEvent.ToolCallFinished>().single().isError)
        assertTrue(events.any { it is AvatarEvent.TurnCompleted && !it.interrupted })
    }

    @Test
    fun `unknown tool and bad arguments feed back error results`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("ok") }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        // 一轮两个调用：未知名 + 坏 JSON（顺序执行、各自 isError、id 对位）
        llm.scripts += listOf(llm.finish(toolCall("call_a", name = "nope"), toolCall("call_b", args = "{bad")))
        llm.scripts += listOf(llm.text("好吧。"), llm.finish())
        session.send("x")
        runCurrent()

        val toolMessages = llm.requests[1].first.filter { it.role == ChatRole.TOOL }
        assertEquals(2, toolMessages.size)
        assertTrue(toolMessages[0].content.contains("unknown tool: nope"))
        assertTrue(toolMessages[1].content.contains("invalid arguments"))
        assertEquals(listOf("call_a", "call_b"), toolMessages.map { it.toolCallId })
        assertTrue(events.filterIsInstance<AvatarEvent.ToolCallFinished>().all { it.isError })
    }

    @Test
    fun `tool round limit stops the loop and finishes with text so far`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm, options = AvatarSession.Options(agentMaxToolRounds = 2))
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("tick") }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        // 模型无休止要工具：初始轮 + 上限 2 轮执行；第 3 份工具请求不发生
        llm.scripts += listOf(llm.finish(toolCall("c1")))
        llm.scripts += listOf(llm.finish(toolCall("c2")))
        llm.scripts += listOf(llm.finish(toolCall("c3")))
        session.send("loop")
        runCurrent()

        assertEquals(3, llm.requests.size)
        assertTrue(
            "expected TurnCompleted, got ${events.filter { it is AvatarEvent.TurnCompleted || it is AvatarEvent.TurnFailed }}",
            events.any { it is AvatarEvent.TurnCompleted && !it.interrupted },
        )
        assertTrue(
            "agentSnapshot=${session.agentSnapshot()}",
            session.agentSnapshot().contains("lastToolCalls=[get_time]"),
        )
    }

    @Test
    fun `interrupt during tool execution ends the turn like any interrupt`() = runTest {
        val llm = ScriptedLlm()
        val store = InMemoryConversationStore()
        val session = newSession(llm, store = store)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        var cancelled = false
        session.tools.register(toolSpec) {
            try {
                delay(60_000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            ToolResult("never")
        }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        llm.scripts += listOf(llm.finish(toolCall("call_1")))
        session.send("hang")
        runCurrent()
        // 工具执行中打断：协作取消传播、无 TurnCompleted、阶段回 IDLE
        session.interrupt()
        runCurrent()

        assertTrue(cancelled)
        assertFalse(events.any { it is AvatarEvent.TurnCompleted })
        assertEquals(ConversationPhase.IDLE, session.phase.value)
        // 已 appendUser 的话保留在历史（与既有打断语义一致），无 assistant 落库
        assertEquals(1, store.messages().size)
    }

    @Test
    fun `tool-related 400 disables tools and retries once without them`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("ok") }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        // 第一次请求带 tools → 端点 400「不支持工具」→ 禁用 + 无 tools 重发
        llm.scripts += listOf(LlmStreamEvent.Error(IllegalStateException("LLM HTTP 400: functions are not supported")))
        llm.scripts += listOf(llm.text("回答照常。"), llm.finish())
        session.send("hi")
        runCurrent()

        assertEquals(2, llm.requests.size)
        assertFalse(llm.requests[0].second.tools.isEmpty())
        assertTrue(llm.requests[1].second.tools.isEmpty())
        assertTrue(events.any { it is AvatarEvent.TurnCompleted && !it.interrupted })
        // 本会话内禁用缓存生效：下一回合的请求直接不带 tools
        assertTrue(session.agentSnapshot().contains("disabledModels=[http://x|m]"))
        llm.scripts += listOf(llm.text("again"), llm.finish())
        session.send("hi again")
        runCurrent()
        assertTrue(llm.requests[2].second.tools.isEmpty())
    }

    @Test
    fun `non-tool llm errors still fail the turn`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("ok") }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        llm.scripts += listOf(LlmStreamEvent.Error(IllegalStateException("LLM HTTP 500: internal")))
        session.send("hi")
        runCurrent()

        assertTrue(events.any { it is AvatarEvent.TurnFailed })
        // 未被误判为工具相关 → 未写入禁用缓存
        assertFalse(session.agentSnapshot().contains("disabledModels=[http://x|m]"))
    }

    // ── 提示词与注册表面 ─────────────────────────────────────────────────────

    @Test
    fun `toolset hints pin into the protocol block and repin on registry change`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")

        val protocol0 = session.protocolBlock()
        assertTrue(protocol0.contains("get_device_time"))
        assertTrue(protocol0.contains("工具使用说明"))
        assertTrue(protocol0.contains("每回合最多 3 轮"))

        // 注册新工具 → 说明块重钉（协议文本变化，identityBlock 指纹随之变化）
        session.tools.register(toolSpec) { ToolResult("ok") }
        val protocol1 = session.protocolBlock()
        assertTrue(protocol1.contains("get_time"))
        assertTrue(protocol1.contains("需要时间时调用"))
        assertTrue(protocol1 != protocol0)

        // 反注册 → 回到内置集合的说明块
        session.tools.unregister("get_time")
        assertEquals(protocol0, session.protocolBlock())
    }

    @Test
    fun `same-name registration overrides the executor`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("first") }
        session.tools.register(toolSpec) { ToolResult("second") }

        llm.scripts += listOf(llm.finish(toolCall("c1")))
        llm.scripts += listOf(llm.text("done"), llm.finish())
        session.send("hi")
        runCurrent()

        val toolMsg = llm.requests[1].first.last { it.role == ChatRole.TOOL }
        assertEquals("second", toolMsg.content)
    }

    // ── 悬空衔接句补救（Qwen3.8 关思考实测驱动，docs/agent-provider-matrix.md）──

    @Test
    fun `dangling bridge triggers required retry with native call`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("结果数据") }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        // 第 1 次请求：只说了衔接句没发起调用 → required 重试（第 2 次请求）→ 原生调用 → 续答
        llm.scripts += listOf(llm.text("让我看看……"), llm.finish())
        llm.scripts += listOf(llm.finish(toolCall("call_r")))
        llm.scripts += listOf(llm.text("好了，是 X。"), llm.finish())
        session.send("现在几点?")
        runCurrent()

        assertEquals(3, llm.requests.size)
        assertEquals("required", llm.requests[1].second.toolChoice)
        val toolMsg = llm.requests[2].first.last { it.role == ChatRole.TOOL }
        assertEquals("结果数据", toolMsg.content)
        assertTrue(events.any { it is AvatarEvent.TurnCompleted && !it.interrupted })
    }

    @Test
    fun `retry recovers qwen template tool call text when native is absent`() = runTest {
        val llm = ScriptedLlm()
        val tts = FakeTts()
        val store = InMemoryConversationStore()
        val session = newSession(llm, store = store, tts = tts)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        var executedArgs: String? = null
        session.tools.register(toolSpec) { executedArgs = it; ToolResult("结果数据") }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        // required 重试轮返回的是 Qwen 模板文本形态（服务端没解析成原生 tool_calls）
        llm.scripts += listOf(llm.text("让我看看……"), llm.finish())
        llm.scripts += listOf(
            llm.text("<tool_call>\n<function=get_time>\n<parameter=tz>UTC+8</parameter>\n</function>\n</tool_call>"),
            llm.finish(),
        )
        llm.scripts += listOf(llm.text("好了。"), llm.finish())
        session.send("现在几点?")
        runCurrent()

        // 模板文本被解析成调用并执行（参数带 tz）
        assertTrue("executor got args with tz: $executedArgs", executedArgs!!.contains("tz"))
        val toolMsg = llm.requests[2].first.last { it.role == ChatRole.TOOL }
        assertEquals("textcall_1", toolMsg.toolCallId)
        // **静默轮的模板文本绝不进 TTS/历史**（只有衔接句被朗读）
        assertTrue(tts.synthesized.none { it.contains("<tool_call>") })
        assertTrue(store.messages().none { it.content.contains("<tool_call>") })
        assertTrue(events.any { it is AvatarEvent.TurnCompleted && !it.interrupted })
    }

    @Test
    fun `short non-bridge reply does not trigger retry`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("结果数据") }

        llm.scripts += listOf(llm.text("哈哈哈。"), llm.finish())
        session.send("讲个笑话")
        runCurrent()

        assertEquals(1, llm.requests.size)
    }

    @Test
    fun `retry with still no call finishes with the original reply`() = runTest {
        val llm = ScriptedLlm()
        val store = InMemoryConversationStore()
        val session = newSession(llm, store = store)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        session.tools.register(toolSpec) { ToolResult("结果数据") }
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        llm.scripts += listOf(llm.text("让我看看……"), llm.finish())
        llm.scripts += listOf(llm.text(""), llm.finish()) // required 重试仍无调用（服务端异常形态）
        session.send("现在几点?")
        runCurrent()

        assertEquals(2, llm.requests.size)
        assertTrue(events.any { it is AvatarEvent.TurnCompleted && !it.interrupted })
        // 只有 user 落库（round0 的衔接句没成句不落 assistant? —— 落库的是全量 raw）
        assertTrue(store.messages().none { it.content.contains("结果数据") })
    }

    @Test
    fun `empty provider reply retries once and completes`() = runTest {
        val llm = ScriptedLlm()
        val store = InMemoryConversationStore()
        val session = newSession(llm, store = store)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        // 第 1 次请求 200 但零字（completion 全进思考通道的服务商故障形态）→ 重发
        llm.scripts += listOf(llm.finish())
        llm.scripts += listOf(llm.text("回答来了。"), llm.finish())
        session.send("你好")
        runCurrent()

        assertEquals(2, llm.requests.size)
        assertTrue(events.any { it is AvatarEvent.TurnCompleted && !it.interrupted })
        // store 只有 user + 重试后的 assistant
        assertEquals(2, store.messages().size)
    }

    @Test
    fun `empty reply twice still completes without crash`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        llm.scripts += listOf(llm.finish())
        llm.scripts += listOf(llm.finish())
        session.send("你好")
        runCurrent()

        assertEquals(2, llm.requests.size)
        assertTrue(events.any { it is AvatarEvent.TurnCompleted && !it.interrupted })
    }

    // ── Skill.tools() 缝（docs/agent-feasibility.md §4.7）────────────────────

    private class ToolSkill : AvatarSkill {
        override val id = "toolskill"
        var active = false
        override val isActive: Boolean get() = active
        // 真实用法：激活发生在 onUtterance 广播期间（激活词翻转状态），
        // registry 靠 before/after 差分感知「转激活」并挂载工具
        override fun onUtterance(text: String, ctx: SkillContext): Boolean {
            active = true
            return false
        }
        override fun tools(): List<Pair<ToolSpec, ToolExecutor>> =
            if (active) listOf(toolSpec to ToolExecutor { ToolResult("from-skill") }) else emptyList()
    }

    @Test
    fun `skill tools hook on activation and unhook on exit`() = runTest {
        val llm = ScriptedLlm()
        val session = newSession(llm)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "t", voice = "v")
        val skill = ToolSkill()
        session.skills.register(skill)
        // 未激活：不占广告位（只有内置两个）
        assertEquals(
            setOf("get_device_time", "get_current_view"),
            session.tools.list().map { it.name }.toSet(),
        )

        // 广播期间技能自己翻转激活态（模拟激活词），registry 差分感知后挂工具
        session.skills.onUtterance("开始")
        assertEquals(
            setOf("get_device_time", "get_current_view", "get_time"),
            session.tools.list().map { it.name }.toSet(),
        )

        // 退场摘除（工具广告位跟随激活态）
        session.skills.deactivate()
        skill.active = false
        assertEquals(
            setOf("get_device_time", "get_current_view"),
            session.tools.list().map { it.name }.toSet(),
        )
    }

    companion object {
        /** 共享给嵌套类（ToolSkill.tools()）与各用例的工具规格。 */
        private val toolSpec = ToolSpec(
            "get_time",
            "获取设备当前时间",
            kotlinx.serialization.json.buildJsonObject {
                put("type", "object")
            },
            "需要时间时调用",
        )
    }
}
