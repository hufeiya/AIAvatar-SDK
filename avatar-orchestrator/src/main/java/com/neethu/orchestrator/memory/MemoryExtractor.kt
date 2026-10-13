package com.neethu.orchestrator.memory

import android.util.Log
import com.neethu.aiadapter.api.EmbeddingAdapter
import com.neethu.aiadapter.api.EmbeddingConfig
import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import kotlinx.coroutines.CancellationException
import java.util.UUID

/**
 * 自动提炼（通道 B 的执行体，docs/memory-feasibility.md §4.3）：窗口内多回合
 * user+assistant 干净文本 + 现有记忆清单 → 跟随主模型的**独立非流式请求**
 * （纯 JSON 文本输出，刻意不走 tools 通道——Qwen3.8 关思考下工具调用不可靠
 * 是矩阵定案，普通 JSON 输出没有这个坑）→ 宽松解析 → 按 delete→update→add
 * 应用。成功返回 true（窗口可清）；请求失败抛异常由协调器兜（窗口保留重试）。
 *
 * add 打包一次 embed 调用（≤5 条一个网络往返）；embed 失败 ≠ 记忆丢失
 * ——content 照存 embedding=null，检索走关键词。
 */
class MemoryExtractor(
    private val llm: LlmAdapter,
    private val store: MemoryStore,
    private val texts: MemoryTexts,
    private val embedder: EmbeddingAdapter? = null,
    private val embeddingConfig: () -> EmbeddingConfig? = { null },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private companion object {
        const val TAG = "MemoryExtractor"

        /** 记忆清单进提炼请求的条数上限（清单是请求的常驻大头，封顶防成本随库增长）。 */
        const val MANIFEST_MAX = 60

        /** 提炼要稳：temperature 覆盖主配置（关思考等 extraBody 照旧跟随）。 */
        const val EXTRACT_TEMPERATURE = 0.2f
    }

    /**
     * 跑一次提炼。[baseConfig] 是当前主回合的 LlmConfig（model/baseUrl/key/
     * extraBody 整体复用，temperature 在此覆盖）；失败（网络/HTTP）抛出。
     *
     * @return true = 请求成功且 ops 已应用（含解析成功但 0 条的情况——
     *   空清单+空 ops 的闲聊窗口是模型可合理得出的正常结论，窗口照清）；
     *   false = 输出无法解析为 ops（出格 JSON）——窗口保留下次重试。
     */
    suspend fun extract(turns: List<MemoryTurn>, charName: String, baseConfig: LlmConfig): Boolean {
        if (turns.isEmpty()) return true
        val manifest = store.all()
            .sortedByDescending { it.updatedAt }
            .take(MANIFEST_MAX)
            .joinToString("\n") { texts.listLine(it.id, texts.categoryLabel(it.category), it.content) }
            .ifEmpty { texts.listEmpty }
        val dialog = turns.joinToString("\n\n") { turn ->
            "用户: ${turn.user}\n$charName: ${turn.assistant}"
        }
        val messages = listOf(
            ChatMessage(ChatRole.SYSTEM, texts.extractSystem),
            ChatMessage(ChatRole.USER, texts.extractUser(manifest, dialog)),
        )
        val reply = StringBuilder()
        llm.streamChat(messages, baseConfig.copy(temperature = EXTRACT_TEMPERATURE)).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> reply.append(event.text)
                is LlmStreamEvent.Error -> throw event.throwable
                else -> Unit
            }
        }
        val ops = MemoryParser.parse(reply.toString())
        if (ops == null) {
            Log.i(TAG, "extraction output is not a parsable ops JSON — keeping window")
            return false
        }
        apply(ops) // 空 ops 也是成功结论（模型判定无可记），窗口照清
        Log.i(TAG, "extraction applied ${ops.size} op(s): ${ops.groupingBy { it.action }.eachCount()}")
        return true
    }

    /** delete → update → add（add 批打包一次 embed）。 */
    internal suspend fun apply(ops: List<MemoryParser.Op>) {
        val now = nowMs()
        ops.filter { it.action == "delete" }.forEach { op ->
            store.delete(op.id!!) // parse 层保证 delete 必带 id
        }
        ops.filter { it.action == "update" }.forEach { op ->
            store.updateContent(op.id!!, op.content)
        }
        val adds = ops.filter { it.action == "add" }
        if (adds.isEmpty()) return
        val vectors = embedBatch(adds.map { it.content })
        adds.forEachIndexed { index, op ->
            store.add(
                AgentMemory(
                    id = newId(),
                    content = op.content,
                    category = op.category,
                    importance = op.importance,
                    source = MemorySources.AUTO,
                    createdAt = now,
                    updatedAt = now,
                    lastAccessedAt = now,
                    embedding = vectors.getOrNull(index),
                ),
            )
        }
    }

    /** 批量嵌入：一次网络往返；任何失败返回若干 null（对应条目 embedding=null 落库）。 */
    private suspend fun embedBatch(contents: List<String>): List<FloatArray?> {
        val adapter = embedder ?: return List(contents.size) { null }
        val cfg = embeddingConfig() ?: return List(contents.size) { null }
        return try {
            val vectors = adapter.embed(contents, cfg)
            require(vectors.size == contents.size) { "embedding count mismatch" }
            vectors
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "batch embed failed, storing without vectors: ${t.message}")
            List(contents.size) { null }
        }
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}
