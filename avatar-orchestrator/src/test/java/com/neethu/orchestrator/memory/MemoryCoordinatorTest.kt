package com.neethu.orchestrator.memory

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 攒批触发（docs/memory-feasibility.md §2.1/§4.3/§6）：双阈值或关系、未达
 * 阈值不发请求、静默门、窗口持久化与重启恢复、失败保留、硬顶 20 轮、
 * 空 ops 成功也清窗、总开关清窗、快照语义（提炼期间新增轮次不丢）。
 */
class MemoryCoordinatorTest {

    private class ScriptedLlm(var reply: String = """{"ops":[]}""") : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages.toList()
            return flowOf(LlmStreamEvent.TextDelta(reply))
        }
    }

    private fun newCoordinator(
        llm: LlmAdapter = ScriptedLlm(),
        store: MemoryStore = InMemoryMemoryStore(),
        llmConfig: LlmConfig? = LlmConfig("http://x", "k", "m"),
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler? = null,
    ): MemoryCoordinator {
        val dispatcher = scheduler?.let { UnconfinedTestDispatcher(it) } ?: UnconfinedTestDispatcher()
        val coordinator = MemoryCoordinator(
            store = store,
            scope = CoroutineScope(dispatcher),
            texts = MemoryTextsZh,
            retriever = MemoryRetriever(store, null, { null }),
            llmProvider = { llm },
            llmConfigProvider = { llmConfig },
            charName = { "小雪" },
        )
        // 默认 2 轮即触发（或关系的一侧）；minChars 默认拉满 = 只由轮次阈值
        // 触发，字符阈值路径由各用例显式覆盖
        coordinator.everyTurns = 2
        coordinator.minChars = 999_999
        return coordinator
    }

    // ── 触发判定 ──────────────────────────────────────────────────────────

    @Test
    fun `turns threshold fires an extraction and drains the window`() = runTest {
        val llm = ScriptedLlm()
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.onTurnCompleted("今天上班路上有点堵车", "辛苦啦,慢慢来")
        assertEquals(0, llm.requests.size) // 未达阈值，不发请求
        coordinator.onTurnCompleted("晚上想吃火锅解压", "好呀,任务完成就奖励自己")
        assertEquals(1, llm.requests.size) // 轮次达标 → 一次提炼
        assertTrue(coordinator.windowLine().startsWith("window=0 turns/")) // 成功后清空
    }

    @Test
    fun `chars threshold alone fires even with a single turn`() = runTest {
        val llm = ScriptedLlm()
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.everyTurns = 99
        coordinator.minChars = 20
        coordinator.onTurnCompleted("这一句很长很长很长很长呢", "助手也长长长长长长长长长的")
        assertEquals(1, llm.requests.size) // 字符阈值先满足
    }

    @Test
    fun `below both thresholds never fires`() = runTest {
        val llm = ScriptedLlm()
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.everyTurns = 5
        coordinator.minChars = 500
        repeat(3) { coordinator.onTurnCompleted("今天聊了些日常琐事", "嗯嗯我在听") }
        assertEquals(0, llm.requests.size)
        assertTrue(coordinator.windowLine().contains("window=3 turns"))
    }

    @Test
    fun `silence gate drops an all-fillers window without a request`() = runTest {
        val llm = ScriptedLlm()
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.onTurnCompleted("嗯嗯", "哈哈")
        coordinator.onTurnCompleted("好", "哦")
        assertEquals(0, llm.requests.size)
        assertTrue(coordinator.windowLine().startsWith("window=0 turns/")) // 清空不烧请求
    }

    @Test
    fun `disabled coordinator neither injects nor extracts`() = runTest {
        val llm = ScriptedLlm()
        val store = InMemoryMemoryStore()
        store.add(memoryRow("m1", "用户叫小明"))
        val coordinator = newCoordinator(llm, store, scheduler = testScheduler)
        coordinator.setEnabled(false)
        coordinator.onTurnCompleted("u", "a")
        assertEquals(0, llm.requests.size)
        assertNull(coordinator.lineFor("小明"))
    }

    @Test
    fun `autoExtract off keeps the tools path but stops channel B`() = runTest {
        val llm = ScriptedLlm()
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.autoExtract = false
        coordinator.onTurnCompleted("u", "a")
        assertEquals(0, llm.requests.size)
    }

    // ── 提炼结果路径 ──────────────────────────────────────────────────────

    @Test
    fun `successful extraction with ops lands in the store`() = runTest {
        val llm = ScriptedLlm(
            """{"ops":[{"action":"add","content":"用户在准备考试","category":"event","importance":6}]}""",
        )
        val store = InMemoryMemoryStore()
        val coordinator = newCoordinator(llm, store, scheduler = testScheduler)
        coordinator.everyTurns = 1
        coordinator.onTurnCompleted("我下周三要考驾照科目二了", "加油,肯定一次过的")
        assertEquals(1, store.count())
    }

    @Test
    fun `empty ops success still drains the window`() = runTest {
        val llm = ScriptedLlm("""{"ops":[]}""")
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.everyTurns = 1
        coordinator.onTurnCompleted("今天随便聊点什么呢", "聊啥都行呀陪你")
        assertTrue(coordinator.windowLine().startsWith("window=0 turns/"))
    }

    @Test
    fun `failed extraction keeps the window for retry`() = runTest {
        val llm = ScriptedLlm("这不是 JSON") // 解析失败 → extract 返回 false
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.everyTurns = 1
        coordinator.onTurnCompleted("今天有一句有内容的话要说", "好的我在听你说")
        assertTrue(coordinator.windowLine().startsWith("window=1 turns/"))
    }

    @Test
    fun `window caps at twenty turns dropping the oldest`() = runTest {
        val llm = ScriptedLlm("坏输出") // 恒失败 → 窗口只增
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.everyTurns = 999
        coordinator.minChars = 999_999
        repeat(25) { coordinator.onTurnCompleted("u$it", "a") }
        assertTrue(coordinator.windowLine().startsWith("window=20 turns/"))
    }

    // ── 持久化与恢复 ──────────────────────────────────────────────────────

    @Test
    fun `window persists across coordinator restarts and triggers immediately when over threshold`() = runTest {
        val llm = ScriptedLlm()
        val store = InMemoryMemoryStore() // 同时是 PendingWindowStore
        val first = newCoordinator(llm, store, scheduler = testScheduler)
        first.everyTurns = 99
        first.minChars = 999_999
        first.onTurnCompleted("重启前第一轮的内容", "好的记住啦")
        first.onTurnCompleted("重启前第二轮的内容", "嗯嗯继续说")

        // 「重启」：同一 store 上新建协调器，首个回合收尾即恢复+判定
        val second = newCoordinator(llm, store, scheduler = testScheduler)
        second.everyTurns = 3
        second.minChars = 0
        second.onTurnCompleted("重启后的第一个回合内容", "我在的")
        assertEquals(1, llm.requests.size)
        val dialog = llm.requests.single().last().content
        assertTrue(dialog.contains("重启前第一轮") && dialog.contains("重启后的第一个回合")) // 恢复的轮次进了提炼输入
    }

    @Test
    fun `clearAll wipes both the store and the window`() = runTest {
        val llm = ScriptedLlm()
        val store = InMemoryMemoryStore()
        store.add(memoryRow("m1", "旧记忆"))
        val coordinator = newCoordinator(llm, store, scheduler = testScheduler)
        coordinator.everyTurns = 99
        coordinator.minChars = 999_999
        coordinator.onTurnCompleted("随便聊一句有内容的话", "好的呀")
        coordinator.clearAll()
        assertEquals(0, store.count())
        assertTrue(coordinator.windowLine().startsWith("window=0 turns/"))
    }

    // ── 快照语义 ──────────────────────────────────────────────────────────

    @Test
    fun `turns appended during extraction survive the drain`() = runTest {
        // 慢 LLM：提炼请求挂起在 gate 上；LLM 调用在锁外，主回合可继续追加
        val gate = CompletableDeferred<Unit>()
        val llm = object : LlmAdapter {
            val requests = mutableListOf<List<ChatMessage>>()
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> =
                flow {
                    requests += messages.toList()
                    gate.await()
                    emit(LlmStreamEvent.TextDelta("""{"ops":[]}"""))
                }
        }
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.everyTurns = 1
        coordinator.onTurnCompleted("这是第一轮有内容的对话", "好的我在听") // 触发提炼（挂起在 gate）
        coordinator.onTurnCompleted("这是提炼期间新增的第二轮", "嗯嗯继续") // extracting 位拦掉重复触发
        gate.complete(Unit)
        testScheduler.advanceUntilIdle() // 放行提炼协程收尾（Unconfined 恢复走调度器）
        // 快照（第一轮）被移除；第二轮保留等下一轮触发
        val line = coordinator.windowLine()
        assertTrue("expected the second turn to remain: $line", line.startsWith("window=1 turns/"))
    }

    @Test
    fun `configure adjusts thresholds at runtime`() = runTest {
        val llm = ScriptedLlm()
        val coordinator = newCoordinator(llm, scheduler = testScheduler)
        coordinator.configure(1, 0)
        coordinator.onTurnCompleted("配置完阈值的第一轮对话", "好的")
        assertEquals(1, llm.requests.size)
    }

    private fun memoryRow(id: String, content: String) = AgentMemory(
        id, content, MemoryCategories.PROFILE, 5, "auto", 0, 0, 0,
    )
}
