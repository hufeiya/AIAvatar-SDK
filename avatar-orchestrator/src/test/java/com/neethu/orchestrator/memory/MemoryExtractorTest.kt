package com.neethu.orchestrator.memory

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动提炼（docs/memory-feasibility.md §4.3/§6）：宽松解析的出格输入边界、
 * 字段清洗（category/importance/content/ops 数量）、delete→update→add 应用
 * 顺序、清单进 prompt、温度覆盖、embed 打包与失败降级。
 */
class MemoryExtractorTest {

    private val texts = MemoryTextsZh

    private class ScriptedLlm(private val reply: String) : LlmAdapter {
        val requests = mutableListOf<Pair<List<ChatMessage>, LlmConfig>>()

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages.toList() to config
            return flowOf(LlmStreamEvent.TextDelta(reply))
        }
    }

    private fun newExtractor(
        llm: LlmAdapter,
        store: MemoryStore = InMemoryMemoryStore(),
        embedder: com.neethu.aiadapter.api.EmbeddingAdapter? = null,
    ): MemoryExtractor = MemoryExtractor(
        llm, store, texts,
        embedder = embedder,
        embeddingConfig = { if (embedder == null) null else com.neethu.aiadapter.api.EmbeddingConfig("http://e", "k", "bge") },
        nowMs = { 1000L },
    )

    private fun cfg() = LlmConfig("http://x", "k", "main-model", extraBody = null)

    // ── MemoryParser 宽松解析 ──────────────────────────────────────────────

    @Test
    fun `parse reads a clean ops object`() {
        val ops = MemoryParser.parse(
            """{"ops":[{"action":"add","content":"用户叫小明","category":"profile","importance":8}]}""",
        )!!
        assertEquals(1, ops.size)
        assertEquals("add", ops[0].action)
        assertEquals("用户叫小明", ops[0].content)
        assertEquals(MemoryCategories.PROFILE, ops[0].category)
        assertEquals(8, ops[0].importance)
    }

    @Test
    fun `parse tolerates prose and markdown fences around the json`() {
        val raw = "好的,以下是我要做的变更:\n```json\n" +
            """{"ops":[{"action":"add","content":"用户喜欢猫"}]}""" +
            "\n```希望有帮助"
        val ops = MemoryParser.parse(raw)!!
        assertEquals(1, ops.size)
        assertEquals("用户喜欢猫", ops[0].content)
    }

    @Test
    fun `garbage output parses to null while valid empty ops parse to an empty list`() {
        assertNull(MemoryParser.parse("模型今天不想输出 JSON"))
        assertNull(MemoryParser.parse("""{"ops":"不是一个数组"}"""))
        assertNull(MemoryParser.parse(""))
        // 空 ops = 模型判定无可记，是成功结论（协调器据此清窗）
        assertEquals(emptyList<MemoryParser.Op>(), MemoryParser.parse("""{"ops":[]}"""))
        // 前后杂语里的合法空 ops 同样识别
        assertEquals(emptyList<MemoryParser.Op>(), MemoryParser.parse("好的。{\"ops\":[]} 完毕"))
    }

    @Test
    fun `braces inside strings do not break fragment extraction`() {
        val ops = MemoryParser.parse(
            """{"ops":[{"action":"add","content":"内容带花括号 { 和 }"}]}""",
        )
        assertEquals(1, ops!!.size)
    }

    @Test
    fun `unknown category normalizes to other and importance clamps`() {
        val ops = MemoryParser.parse(
            """{"ops":[{"action":"add","content":"A","category":"mood","importance":99},
                {"action":"add","content":"B","importance":-3}]}""",
        )
        assertEquals(MemoryCategories.OTHER, ops!![0].category)
        assertEquals(10, ops[0].importance)
        assertEquals(1, ops[1].importance)
    }

    @Test
    fun `content is truncated and blank add ops are dropped`() {
        val long = "长".repeat(200)
        val ops = MemoryParser.parse(
            """{"ops":[{"action":"add","content":"$long"},
                {"action":"add","content":"   "},
                {"action":"add","content":"好的"}]}""",
        )
        assertEquals(2, ops!!.size)
        assertEquals(120, ops[0].content.length)
    }

    @Test
    fun `more than five ops are capped`() {
        val items = (1..8).joinToString(",") { """{"action":"add","content":"条目$it"}""" }
        assertEquals(MemoryParser.MAX_OPS, MemoryParser.parse("""{"ops":[$items]}""")!!.size)
    }

    @Test
    fun `update and delete ops require id and content respectively`() {
        val ops = MemoryParser.parse(
            """{"ops":[{"action":"update","id":"m1","content":"新内容"},
                {"action":"update","content":"没有 id"},
                {"action":"delete","id":"m2"},
                {"action":"delete"},
                {"action":"frobnicate","content":"未知动作"}]}""",
        )
        assertEquals(2, ops!!.size)
        assertEquals("update", ops[0].action)
        assertEquals("delete", ops[1].action)
    }

    // ── MemoryExtractor 全链 ───────────────────────────────────────────────

    @Test
    fun `full chain applies ops to the store in delete-update-add order`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(
            AgentMemory(
                "m1", "用户喜欢香菜", MemoryCategories.PREFERENCE, 5, "auto", 0, 0, 0,
            ),
        )
        val llm = ScriptedLlm(
            """{"ops":[{"action":"delete","id":"m1"},
                {"action":"update","id":"missing","content":"更新不存在的"},
                {"action":"add","content":"用户讨厌香菜","category":"preference","importance":6}]}""",
        )
        val extractor = newExtractor(llm, store)
        val ok = extractor.extract(
            listOf(MemoryTurn("我不喜欢香菜了", "好的,记住了")),
            "小雪", cfg(),
        )
        assertTrue(ok)
        val rows = store.all()
        assertEquals(1, rows.size)
        assertEquals("用户讨厌香菜", rows[0].content)
        assertEquals(MemorySources.AUTO, rows[0].source)
        // 清单与对话都进了 user 消息;温度覆盖为 0.2;模型名跟随主配置
        val (_, requestCfg) = llm.requests.single()
        assertEquals("main-model", requestCfg.model)
        assertEquals(0.2f, requestCfg.temperature, 1e-6f)
        assertTrue(requestCfg.tools.isEmpty())
        val user = llm.requests.single().first.last().content
        assertTrue(user.contains("m1 [偏好] 用户喜欢香菜")) // 清单里有被删条目
        assertTrue(user.contains("用户: 我不喜欢香菜了"))
        assertTrue(user.contains("小雪: 好的,记住了"))
    }

    @Test
    fun `extraction with empty window short-circuits true`() = runTest {
        val ok = newExtractor(ScriptedLlm("{}")).extract(emptyList(), "x", cfg())
        assertTrue(ok)
    }

    @Test
    fun `unparsable reply returns false and the caller keeps the window`() = runTest {
        val ok = newExtractor(ScriptedLlm("今天聊得很开心呀")).extract(
            listOf(MemoryTurn("嗨", "嗨")), "x", cfg(),
        )
        assertFalse(ok)
    }

    @Test
    fun `batch add embeds once and stores vectors`() = runTest {
        var calls = 0
        val embedder = object : com.neethu.aiadapter.api.EmbeddingAdapter {
            override suspend fun embed(
                texts: List<String>,
                config: com.neethu.aiadapter.api.EmbeddingConfig,
            ): List<FloatArray> {
                calls++
                // 各自落在不同坐标轴上（同向会被 store.add 的 0.92 去重合并）
                return texts.mapIndexed { i, _ ->
                    FloatArray(texts.size).also { it[i] = 1f }
                }
            }
        }
        val store = InMemoryMemoryStore()
        val llm = ScriptedLlm(
            """{"ops":[{"action":"add","content":"事实一"},{"action":"add","content":"事实二"}]}""",
        )
        assertTrue(newExtractor(llm, store, embedder).extract(listOf(MemoryTurn("u", "a")), "x", cfg()))
        assertEquals(1, calls)
        assertEquals(listOf("事实一", "事实二"), store.all().map { it.content })
        assertTrue(store.all().all { it.embedding != null })
    }

    @Test
    fun `embed failure stores memories without vectors`() = runTest {
        val embedder = object : com.neethu.aiadapter.api.EmbeddingAdapter {
            override suspend fun embed(
                texts: List<String>,
                config: com.neethu.aiadapter.api.EmbeddingConfig,
            ): List<FloatArray> = throw java.io.IOException("HTTP 429: slow down")
        }
        val store = InMemoryMemoryStore()
        val llm = ScriptedLlm("""{"ops":[{"action":"add","content":"没向量也照存"}]}""")
        assertTrue(newExtractor(llm, store, embedder).extract(listOf(MemoryTurn("u", "a")), "x", cfg()))
        val row = store.all().single()
        assertEquals("没向量也照存", row.content)
        assertEquals(null, row.embedding)
    }

    @Test
    fun `http failure propagates to the coordinator for window retry`() = runTest {
        val llm = object : LlmAdapter {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> =
                flowOf(LlmStreamEvent.Error(java.io.IOException("HTTP 500")))
        }
        val extractor = newExtractor(llm)
        val result = runCatching {
            extractor.extract(listOf(MemoryTurn("u", "a")), "x", cfg())
        }
        assertTrue(result.isFailure)
    }
}
