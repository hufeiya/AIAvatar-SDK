package com.neethu.orchestrator.memory

import com.neethu.orchestrator.agent.ToolResult
import com.neethu.corelib.Lang
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆三工具（docs/memory-feasibility.md §4.4/§6）：remember 参数校验/缺省
 * 重要度 8、recall 空结果=非 isError、forget 未命中 isError、三 spec 快照锁
 * （description/hint 防漂移）。
 */
class MemoryToolsTest {

    private val texts = MemoryTextsZh

    private suspend fun invoke(
        pair: Pair<com.neethu.aiadapter.model.ToolSpec, com.neethu.orchestrator.agent.ToolExecutor>,
        args: String,
    ): ToolResult = pair.second.invoke(args)

    // ── remember ──────────────────────────────────────────────────────────

    @Test
    fun `remember stores with default importance eight and tool source`() = runTest {
        val store = InMemoryMemoryStore()
        val remember = MemoryTools.remember(store, null, { null }, texts)
        val result = invoke(remember, """{"content":"周五要开会","category":"event"}""")
        assertFalse(result.isError)
        assertTrue(result.content.contains("周五要开会"))
        val row = store.all().single()
        assertEquals(8, row.importance)
        assertEquals(MemorySources.TOOL, row.source)
        assertEquals(MemoryCategories.EVENT, row.category)
    }

    @Test
    fun `remember rejects missing content and unknown category falls back to other`() = runTest {
        val store = InMemoryMemoryStore()
        val remember = MemoryTools.remember(store, null, { null }, texts)
        val missing = invoke(remember, """{"category":"profile"}""")
        assertTrue(missing.isError)
        val fallback = invoke(remember, """{"content":"随口一句","category":"nonsense"}""")
        assertFalse(fallback.isError)
        assertEquals(MemoryCategories.OTHER, store.all().last().category)
    }

    // ── recall ────────────────────────────────────────────────────────────

    @Test
    fun `recall empty result is a normal non-error answer`() = runTest {
        val store = InMemoryMemoryStore()
        val recall = MemoryTools.recall(store, MemoryRetriever(store, null, { null }), texts)
        val result = invoke(recall, """{"query":"完全不相关的问题"}""")
        assertFalse(result.isError)
        assertEquals(texts.toolRecallEmpty, result.content)
    }

    @Test
    fun `recall returns id lines and touches hits`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(
            AgentMemory("abc123", "用户不喜欢香菜", MemoryCategories.PREFERENCE, 6, "auto", 0, 0, 0),
        )
        val recall = MemoryTools.recall(store, MemoryRetriever(store, null, { null }), texts)
        val result = invoke(recall, """{"query":"香菜"}""")
        assertFalse(result.isError)
        assertTrue(result.content.startsWith("abc123 [偏好] 用户不喜欢香菜"))
        assertEquals(2, store.all().single().accessCount)
    }

    // ── forget ────────────────────────────────────────────────────────────

    @Test
    fun `forget misses are errors that teach the recall-first flow`() = runTest {
        val store = InMemoryMemoryStore()
        val forget = MemoryTools.forget(store, texts)
        val miss = invoke(forget, """{"id":"nope"}""")
        assertTrue(miss.isError)
        assertEquals(texts.toolForgetMiss, miss.content)
    }

    @Test
    fun `forget deletes by exact id`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(
            AgentMemory("abc123", "旧事", MemoryCategories.EVENT, 5, "auto", 0, 0, 0),
        )
        val forget = MemoryTools.forget(store, texts)
        val result = invoke(forget, """{"id":"abc123"}""")
        assertFalse(result.isError)
        assertEquals(0, store.count())
    }

    // ── spec 快照锁（提示词防漂移）────────────────────────────────────────

    @Test
    fun `tool specs snapshot lock`() {
        val store = InMemoryMemoryStore()
        val texts = MemoryTextsZh
        val specs = listOf(
            MemoryTools.remember(store, null, { null }, texts).first,
            MemoryTools.recall(store, MemoryRetriever(store, null, { null }), texts).first,
            MemoryTools.forget(store, texts).first,
        )
        assertEquals("remember", specs[0].name)
        assertEquals("把用户明确要求记住的事写进长期记忆(一次一条)。", specs[0].description)
        assertTrue(specs[0].systemHint!!.contains("用户明确请你记住"))
        assertEquals("recall", specs[1].name)
        assertTrue(specs[1].systemHint!!.contains("先调用本工具再回答"))
        assertEquals("forget", specs[2].name)
        assertTrue(specs[2].systemHint!!.contains("先用 recall"))
        // schema 必填面：remember/recall/forget 各自的 required
        assertTrue(specs[0].parametersSchema.toString().contains("\"content\""))
        assertTrue(specs[1].parametersSchema.toString().contains("\"query\""))
        assertTrue(specs[2].parametersSchema.toString().contains("\"id\""))
    }

    // ── i18n 防漂移 ───────────────────────────────────────────────────────

    @Test
    fun `en texts contain no chinese characters`() {
        val en = MemoryTexts.of(Lang.EN)
        val zh = MemoryTexts.of(Lang.ZH)
        val members = listOf(
            en.defaultCharName, en.extractSystem, en.listEmpty, en.recallBlockHeader,
            en.categoryProfile, en.categoryRelationship, en.categoryPreference,
            en.categoryEvent, en.categoryCommitment, en.categoryOther,
            en.toolRememberDescription, en.toolRememberHint, en.toolRememberArgError,
            en.toolRecallDescription, en.toolRecallHint, en.toolRecallEmpty,
            en.toolForgetDescription, en.toolForgetHint, en.toolForgetMiss, en.toolForgetArgError,
            en.extractUser(en.listEmpty, "x"), en.recallBlockLine(en.categoryProfile, "y"),
            en.toolRememberDone("z"), en.toolRecallLine("i", en.categoryEvent, "c"),
            en.toolForgetDone("i"),
        )
        val zhCjk = Regex("[\\p{Script=Han}]")
        members.forEach { member ->
            assertFalse("EN text contains Chinese: $member", zhCjk.containsMatchIn(member))
        }
        // 中英成对存在（结构对齐的烟雾测试）：同函数在 zh 侧可调用且非空
        assertTrue(zh.extractSystem.isNotEmpty())
        assertTrue(zh.recallBlockHeader.isNotEmpty())
    }
}
