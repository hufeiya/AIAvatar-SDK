package com.neethu.orchestrator.memory

import android.util.Log
import com.neethu.aiadapter.api.EmbeddingAdapter
import com.neethu.aiadapter.api.EmbeddingConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 记忆检索与注入（docs/memory-feasibility.md §4.5）：query=本轮用户输入 →
 * embed → 与库内向量算余弦 → `score = 1.2×cosine + 0.2×新鲜度`（airi
 * chat-message.ts:111-112 同款公式 + 两处修正）→ minCosine 剔除 → top-K。
 * 修正①新鲜度项只对 event/commitment 衰减（身份/关系/偏好恒满额——修掉
 * 「用户叫小明」30 天后沉底的坑）；修正②常驻槽：profile/relationship 且
 * importance≥8 恒按重要度取 ≤3 条，与检索结果按 id 去重。
 *
 * 降级链：无 embeddings 配置 / embed 失败或超时（800ms）→ 关键词路（2-gram
 * 命中占比 + 0.05×重要度，本地零网络）；检索任何异常 → 整块省略，永不影响
 * 主回合（与「工具失败永不炸回合」同哲学）。空库短路：不发 embed 请求。
 */
class MemoryRetriever(
    private val store: MemoryStore,
    private val embedder: EmbeddingAdapter?,
    private val embeddingConfig: () -> EmbeddingConfig?,
    private val config: Config = Config(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    data class Config(
        val topK: Int = 3,
        /** 常驻槽上限（注入 token 恒定，与库大小无关）。 */
        val pinnedMax: Int = 3,
        val simWeight: Float = 1.2f,
        val recencyWeight: Float = 0.2f,
        val recencyWindowDays: Float = 30f,
        val minCosine: Float = 0.35f,
        /** embed 超时 → 关键词降级（关键词是本地计算，0ms）。 */
        val embedTimeoutMs: Long = 800L,
    )

    /**
     * 本轮请求的记忆参考块（挂末尾 user 前缀槽）；null = 本轮零注入（空库/
     * 无命中/总开关关外的任何失败路径）。命中即 [MemoryStore.touch]。
     */
    suspend fun lineFor(query: String, texts: MemoryTexts): String? {
        val hits = search(query)
        if (hits.isEmpty()) return null
        runCatching { store.touch(hits.map { it.id }) }
            .onFailure { Log.w(TAG, "memory touch failed: ${it.message}") }
        val header = texts.recallBlockHeader
        return hits.joinToString("\n", prefix = "$header\n") {
            texts.recallBlockLine(texts.categoryLabel(it.category), it.content)
        }
    }

    /**
     * 打分排序后的命中列表（检索槽 + 常驻槽，已按 id 去重），不回写 touch——
     * recall 工具与注入块各自决定是否 touch。任何内部失败返回空列表不抛。
     */
    suspend fun search(query: String): List<AgentMemory> = try {
        val all = store.all()
        if (all.isEmpty() || query.isBlank()) {
            emptyList()
        } else {
            val queryVec = embedQuery(query)
            val scored = all.mapNotNull { memo -> scored(queryVec, memo, query) }
                .sortedByDescending { it.score }
            val searchSlot = scored.take(config.topK).map { it.memory }
            val pinned = all.asSequence()
                .filter { it.category in MemoryCategories.PINNED && it.importance >= 8 }
                .sortedWith(compareByDescending<AgentMemory> { it.importance }.thenByDescending { it.updatedAt })
                .take(config.pinnedMax)
                .filter { pinned -> searchSlot.none { it.id == pinned.id } }
            (searchSlot + pinned).distinctBy { it.id }
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        Log.w(TAG, "memory search failed, injecting nothing this turn: ${t.message}")
        emptyList()
    }

    private data class Scored(val memory: AgentMemory, val score: Float)

    /** 单条打分：有查询向量且该条有嵌入走向量公式，否则关键词路；两者皆不可用为 null。 */
    private fun scored(queryVec: FloatArray?, memo: AgentMemory, query: String): Scored? {
        val recency = recencyFactor(memo)
        if (queryVec != null && memo.embedding != null) {
            val cos = MemoryDedup.cosine(queryVec, memo.embedding)
            if (cos < config.minCosine) return null
            return Scored(memo, config.simWeight * cos + config.recencyWeight * recency)
        }
        // 关键词降级：也承担「库里有未嵌入条目」时的混合打分（向量路对它们失明）
        val gramRatio = gramHitRatio(query, memo.content)
        if (gramRatio <= 0f) return null
        return Scored(memo, gramRatio + KEYWORD_IMPORTANCE_WEIGHT * memo.importance)
    }

    /** 新鲜度因子 ∈ [0,1]：豁免类恒 1.0；event/commitment 按 30 天线性衰减。 */
    internal fun recencyFactor(memo: AgentMemory): Float {
        if (memo.category in MemoryCategories.RECENCY_EXEMPT) return 1f
        val ageDays = (nowMs() - memo.updatedAt).coerceAtLeast(0) / 86_400_000f
        return (1f - ageDays / config.recencyWindowDays).coerceIn(0f, 1f)
    }

    /** embed 当前查询；无缝/失败/超时返回 null（调用方走关键词路）。 */
    private suspend fun embedQuery(query: String): FloatArray? {
        val adapter = embedder ?: return null
        val cfg = embeddingConfig() ?: return null
        return try {
            withTimeout(config.embedTimeoutMs) {
                adapter.embed(listOf(query), cfg).firstOrNull()
            }
        } catch (e: TimeoutCancellationException) {
            // 自己的超时不是调用方的取消：静默降级，首句延迟不能为检索买单
            Log.i(TAG, "query embed timed out after ${config.embedTimeoutMs}ms, keyword fallback")
            null
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // 失败静默降级
            Log.i(TAG, "query embed unavailable (${t.javaClass.simpleName}: ${t.message}), keyword fallback")
            null
        }
    }

    internal companion object {
        const val TAG = "MemoryRetriever"

        private const val KEYWORD_IMPORTANCE_WEIGHT = 0.05f

        /**
         * 2-gram 命中占比：query 的全部相邻二字组里有多少出现在 content 里。
         * 单字 query 退化为包含判断（1/0）。CJK 2-gram 对中文名/关键词足够锐；
         * 英文走词内 2-gram（大小写归一），质量略钝但作为降级路可接受。
         */
        internal fun gramHitRatio(query: String, content: String): Float {
            val q = query.trim().lowercase()
            val c = content.trim().lowercase()
            if (q.isEmpty() || c.isEmpty()) return 0f
            if (q.length == 1) return if (c.contains(q)) 1f else 0f
            val grams = q.windowed(2).distinct()
            if (grams.isEmpty()) return 0f
            val hits = grams.count { c.contains(it) }
            return hits.toFloat() / grams.size
        }
    }
}
