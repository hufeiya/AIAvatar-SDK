package com.neethu.orchestrator.memory

import com.neethu.aiadapter.api.EmbeddingAdapter
import com.neethu.aiadapter.api.EmbeddingConfig
import com.neethu.corelib.Lang
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆检索公式（docs/memory-feasibility.md §4.5/§6）：airi 打分公式 + 两处修正
 * （recency 豁免表 / 常驻槽）、minCosine 剔除、topK 截断、两槽 id 去重、
 * 关键词 2-gram 降级、embed 超时降级、空库短路、touch 回写。
 */
class MemoryRetrieverTest {

    private val texts = MemoryTextsZh

    /** 检索器的 nowMs 基准（第 100 天）。 */
    private val nowMs = 86_400_000L * 100

    private fun memory(
        id: String,
        content: String,
        category: String = MemoryCategories.EVENT,
        importance: Int = 5,
        ageDays: Float = 0f,
        embedding: FloatArray? = null,
    ) = AgentMemory(
        id = id,
        content = content,
        category = category,
        importance = importance,
        source = "auto",
        createdAt = 0L,
        updatedAt = nowMs - (ageDays * 86_400_000L).toLong(),
        lastAccessedAt = 0L,
        embedding = embedding,
    )

    /**
     * 8 维向量：query 向量恒 [1,0,…]，与它的余弦恰为 [similarity]；[axis]
     * 给每条记忆一个独立方向，避免多条记忆向量彼此过近触发 store.add 的
     * 机械去重（>0.92 合并）。
     */
    private fun vec(similarity: Float, axis: Int = 1): FloatArray {
        val s = similarity.coerceIn(0f, 1f)
        val v = FloatArray(8)
        v[0] = s
        v[axis.coerceIn(1, 7)] = kotlin.math.sqrt((1 - s * s).coerceAtLeast(0f))
        return v
    }

    private class FakeEmbedder(
        var result: List<FloatArray>? = listOf(floatArrayOf(1f)),
        var fail: Boolean = false,
        var hang: Boolean = false,
    ) : EmbeddingAdapter {
        var calls = 0
        override suspend fun embed(texts: List<String>, config: EmbeddingConfig): List<FloatArray> {
            calls++
            when {
                fail -> throw java.io.IOException("boom")
                hang -> delay(10_000)
            }
            return result ?: error("no result")
        }
    }

    private fun newRetriever(
        store: MemoryStore,
        embedder: EmbeddingAdapter? = null,
        config: MemoryRetriever.Config = MemoryRetriever.Config(),
        nowMs: () -> Long = { this@MemoryRetrieverTest.nowMs },
    ): MemoryRetriever = MemoryRetriever(
        store,
        embedder,
        { if (embedder == null) null else EmbeddingConfig("http://e", "k", "bge") },
        config,
        nowMs,
    )

    // ── 余弦与公式边界 ─────────────────────────────────────────────────────

    @Test
    fun `cosine handles zero vector and dimension mismatch as zero`() {
        assertEquals(0f, MemoryDedup.cosine(FloatArray(4), FloatArray(4)), 1e-6f)
        assertEquals(0f, MemoryDedup.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f)), 1e-6f)
        assertEquals(0f, MemoryDedup.cosine(FloatArray(0), FloatArray(0)), 1e-6f)
    }

    @Test
    fun `identical vectors score one and orthogonal score zero`() {
        assertEquals(1f, MemoryDedup.cosine(floatArrayOf(0.3f, 0.4f), floatArrayOf(0.3f, 0.4f)), 1e-6f)
        assertEquals(0f, MemoryDedup.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
    }

    @Test
    fun `minCosine filters weak matches before scoring`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(memory("m1", "用户下周三考科目二", embedding = vec(0.2f, axis = 1))) // cos 0.2 < 0.35
        store.add(memory("m2", "用户养了只猫", embedding = vec(0.8f, axis = 2)))
        val retriever = newRetriever(store, FakeEmbedder(listOf(vec(1f))))
        val hits = retriever.search("下周三")
        assertEquals(listOf("m2"), hits.map { it.id })
    }

    @Test
    fun `topK truncates the ranked list`() = runTest {
        val store = InMemoryMemoryStore()
        repeat(5) { i -> store.add(memory("m$i", "记忆条目$i", embedding = vec(0.5f + i * 0.1f, axis = i + 1))) }
        val retriever = newRetriever(store, FakeEmbedder(listOf(vec(1f))), MemoryRetriever.Config(topK = 2))
        val hits = retriever.search("记忆")
        assertEquals(2, hits.size)
        assertEquals("m4", hits[0].id) // 相似度最高者第一
    }

    // ── recency 豁免表（airi 公式的坑修正）────────────────────────────────

    @Test
    fun `event memories decay to zero after the recency window`() {
        val retriever = newRetriever(InMemoryMemoryStore())
        val fresh = memory("fresh", "考驾照", ageDays = 0f)
        val stale = memory("stale", "考驾照", ageDays = 30f)
        assertEquals(1f, retriever.recencyFactor(fresh), 1e-6f)
        assertEquals(0f, retriever.recencyFactor(stale), 1e-6f)
    }

    @Test
    fun `profile and relationship and preference are recency-exempt`() {
        val retriever = newRetriever(InMemoryMemoryStore())
        val old = memory("old", "用户叫小明", category = MemoryCategories.PROFILE, ageDays = 90f)
        assertEquals(1f, retriever.recencyFactor(old), 1e-6f)
        val oldRel = memory("oldRel", "用户养了煤球", category = MemoryCategories.RELATIONSHIP, ageDays = 90f)
        assertEquals(1f, retriever.recencyFactor(oldRel), 1e-6f)
        val oldPref = memory("oldPref", "不喜欢香菜", category = MemoryCategories.PREFERENCE, ageDays = 90f)
        assertEquals(1f, retriever.recencyFactor(oldPref), 1e-6f)
        // event/commitment 正常衰减（30 天线性）
        val mid = memory("mid", "要开会", category = MemoryCategories.EVENT, ageDays = 15f)
        assertEquals(0.5f, retriever.recencyFactor(mid), 1e-3f)
    }

    @Test
    fun `exempt identity outranks a fresher event with the same cosine`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(memory("evt", "用户下周三考科目二", category = MemoryCategories.EVENT, ageDays = 30f, embedding = vec(0.8f, axis = 1)))
        store.add(memory("idt", "用户叫小明", category = MemoryCategories.PROFILE, ageDays = 30f, embedding = vec(0.8f, axis = 2)))
        val retriever = newRetriever(store, FakeEmbedder(listOf(vec(1f))))
        val hits = retriever.search("小明")
        assertEquals("idt", hits[0].id) // 同余弦下：身份豁免衰减 1.2+0.2 vs 事件 1.2+0
    }

    // ── 常驻槽 ─────────────────────────────────────────────────────────────

    @Test
    fun `pinned slot adds high-importance identity facts and dedupes by id`() = runTest {
        val store = InMemoryMemoryStore()
        // 检索槽命中 pinnedA（importance 9），常驻槽去重后不再重复
        store.add(memory("pinnedA", "用户叫小明", category = MemoryCategories.PROFILE, importance = 9, embedding = vec(0.9f, axis = 1)))
        // 常驻槽补位：检索不中（cos 低）但身份+高重要度
        store.add(memory("pinnedB", "用户养了煤球", category = MemoryCategories.RELATIONSHIP, importance = 8, embedding = vec(0.1f, axis = 2)))
        // importance 7 不进常驻槽
        store.add(memory("lowImp", "用户住北京", category = MemoryCategories.PROFILE, importance = 7, embedding = vec(0.1f, axis = 3)))
        // event 高重要度也不进常驻槽
        store.add(memory("evtImp", "用户要搬家", category = MemoryCategories.EVENT, importance = 10, embedding = vec(0.1f, axis = 4)))
        val retriever = newRetriever(store, FakeEmbedder(listOf(vec(1f))))
        val hits = retriever.search("小明")
        assertEquals(listOf("pinnedA", "pinnedB"), hits.map { it.id })
    }

    @Test
    fun `pinned slot respects its cap ordered by importance`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(memory("p1", "身份一", category = MemoryCategories.PROFILE, importance = 8, embedding = vec(0.1f, axis = 1)))
        store.add(memory("p2", "身份二", category = MemoryCategories.PROFILE, importance = 10, embedding = vec(0.1f, axis = 2)))
        store.add(memory("p3", "身份三", category = MemoryCategories.PROFILE, importance = 9, embedding = vec(0.1f, axis = 3)))
        store.add(memory("p4", "身份四", category = MemoryCategories.RELATIONSHIP, importance = 10, embedding = vec(0.1f, axis = 4)))
        val retriever = newRetriever(store, FakeEmbedder(listOf(vec(1f))), MemoryRetriever.Config(pinnedMax = 2))
        val hits = retriever.search("完全查不中的词")
        // 检索槽空（cos 太低），常驻槽按重要度取 2：p2(10)、p4(10 同分取 updatedAt 高者不稳定 → 断言集合)
        assertEquals(setOf("p2", "p4"), hits.map { it.id }.toSet())
    }

    // ── 关键词降级 ─────────────────────────────────────────────────────────

    @Test
    fun `gram ratio counts CJK bigrams`() {
        assertEquals(1f, MemoryRetriever.gramHitRatio("香菜", "用户不喜欢香菜"), 1e-6f)
        assertEquals(0.5f, MemoryRetriever.gramHitRatio("香菜拉", "用户不喜欢香菜"), 1e-6f)
        assertEquals(0f, MemoryRetriever.gramHitRatio("火锅", "用户不喜欢香菜"), 1e-6f)
        assertEquals(1f, MemoryRetriever.gramHitRatio("香", "用户不喜欢香菜"), 1e-6f) // 单字退化
        assertEquals(0f, MemoryRetriever.gramHitRatio("", "内容"), 1e-6f)
    }

    @Test
    fun `keyword path works without any embedder configured`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(memory("k1", "用户不喜欢香菜", category = MemoryCategories.PREFERENCE, importance = 6))
        store.add(memory("k2", "用户下周三考科目二", importance = 6))
        val retriever = newRetriever(store, embedder = null)
        val hits = retriever.search("香菜")
        assertEquals(listOf("k1"), hits.map { it.id })
        assertNull(retriever.lineFor("完全无关的查询内容", texts))
    }

    @Test
    fun `embed timeout falls back to keywords`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(memory("k1", "用户不喜欢香菜", category = MemoryCategories.PREFERENCE, importance = 6))
        val embedder = FakeEmbedder(hang = true)
        val retriever = newRetriever(
            store, embedder,
            MemoryRetriever.Config(embedTimeoutMs = 50),
        )
        val hits = retriever.search("香菜")
        assertEquals(listOf("k1"), hits.map { it.id })
        assertEquals(1, embedder.calls)
    }

    @Test
    fun `embed failure falls back to keywords`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(memory("k1", "用户不喜欢香菜", category = MemoryCategories.PREFERENCE, importance = 6))
        val retriever = newRetriever(store, FakeEmbedder(fail = true))
        assertEquals(listOf("k1"), retriever.search("香菜").map { it.id })
    }

    // ── 空库短路 / 注入块 / touch ─────────────────────────────────────────

    @Test
    fun `empty store short-circuits without calling the embedder`() = runTest {
        val embedder = FakeEmbedder()
        val retriever = newRetriever(InMemoryMemoryStore(), embedder)
        assertNull(retriever.lineFor("你好", texts))
        assertEquals(0, embedder.calls)
    }

    @Test
    fun `lineFor formats the reference block and touches hits`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(memory("m1", "用户叫小明,是 Android 工程师", category = MemoryCategories.PROFILE, importance = 9, embedding = vec(0.9f, axis = 1)))
        val embedder = FakeEmbedder(listOf(vec(1f)))
        val retriever = newRetriever(store, embedder)
        val line = retriever.lineFor("小明是谁", texts)
        assertTrue(line!!.startsWith("[记忆参考]\n"))
        assertTrue(line.contains("- (身份) 用户叫小明,是 Android 工程师"))
        val after = store.all().first { it.id == "m1" }
        assertEquals(2, after.accessCount)
        assertTrue(after.lastAccessedAt > 0)
    }

    @Test
    fun `lineFor returns null when nothing scores`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(memory("m1", "用户下周三考科目二", embedding = vec(0.1f, axis = 2)))
        val retriever = newRetriever(store, FakeEmbedder(listOf(vec(1f))))
        assertNull(retriever.lineFor("今天天气", texts))
        assertTrue(retriever.search("今天天气").isEmpty())
    }
}
