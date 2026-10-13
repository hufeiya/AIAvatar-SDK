package com.neethu.orchestrator.memory

import com.neethu.aiadapter.model.ToolSpec
import com.neethu.orchestrator.agent.ToolExecutor
import com.neethu.orchestrator.agent.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * 记忆三工具（通道 A，docs/memory-feasibility.md §4.4）：`remember`（用户显式
 * 「记住…」时模型主动写，即时生效、importance 缺省 8 排常驻槽候选）/
 * `recall`（回答「我们之前聊过什么」先搜记忆再回答，别编）/ `forget`（只认
 * 精确 id——id 从 recall 结果来，防小模型误删；管理面板才是删意场的唯一权威）。
 *
 * 工厂放 orchestrator（store 注入，纯 JVM 自洽）；[com.neethu.orchestrator.agent.AgentToolCatalog]
 * 是 app 缝的设备工具工厂，刻意不掺和。注册方：AvatarSession（条件 =
 * memoryStore 非空 && agentToolsEnabled && memoryEnabled）。
 */
object MemoryTools {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val NAME_REMEMBER = "remember"
    const val NAME_RECALL = "recall"
    const val NAME_FORGET = "forget"

    internal val ALL_NAMES = listOf(NAME_REMEMBER, NAME_RECALL, NAME_FORGET)

    /** `remember`:用户显式要求记住 → 走与提炼同一条 add 路径(含 embed+去重)。 */
    fun remember(
        store: MemoryStore,
        embedder: com.neethu.aiadapter.api.EmbeddingAdapter?,
        embeddingConfig: () -> com.neethu.aiadapter.api.EmbeddingConfig?,
        texts: MemoryTexts,
        nowMs: () -> Long = { System.currentTimeMillis() },
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = NAME_REMEMBER,
            description = texts.toolRememberDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "要记住的事实,一句话第三人称原子事实(含名字/日期等具体细节)")
                            },
                        )
                        put(
                            "category",
                            buildJsonObject {
                                put(
                                    "type",
                                    "string",
                                )
                                put(
                                    "enum",
                                    buildJsonArray { MemoryCategories.ALL.forEach { add(JsonPrimitive(it)) } },
                                )
                                put("description", "profile=身份 relationship=重要的人/宠物 preference=偏好 event=事件/计划 commitment=约定(可选)")
                            },
                        )
                        put(
                            "importance",
                            buildJsonObject {
                                put("type", "integer")
                                put("description", "重要度 1-10,缺省 8(可选)")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("content")) })
            },
            systemHint = texts.toolRememberHint,
        )
        val executor = ToolExecutor { argsJson ->
            val args = parseArgs(argsJson)
            val content = MemoryLimits.clampContent(args.string("content"))
            if (content.isEmpty()) return@ToolExecutor ToolResult(texts.toolRememberArgError, isError = true)
            val now = nowMs()
            val stored = store.add(
                AgentMemory(
                    id = newId(),
                    content = content,
                    category = MemoryCategories.normalize(args.string("category")),
                    importance = MemoryLimits.clampImportance(
                        args.string("importance")?.toIntOrNull() ?: MemoryLimits.IMPORTANCE_TOOL_DEFAULT,
                    ),
                    source = MemorySources.TOOL,
                    createdAt = now,
                    updatedAt = now,
                    lastAccessedAt = now,
                    embedding = embedSafely(content, embedder, embeddingConfig),
                ),
            )
            ToolResult(texts.toolRememberDone(stored.content))
        }
        return spec to executor
    }

    /** `recall`:关键词搜索(与注入同一条打分路径),空结果=正常非 isError。 */
    fun recall(
        store: MemoryStore,
        retriever: MemoryRetriever,
        texts: MemoryTexts,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = NAME_RECALL,
            description = texts.toolRecallDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "要搜索的关键词(名字/事件/偏好等)")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            },
            systemHint = texts.toolRecallHint,
        )
        val executor = ToolExecutor { argsJson ->
            val query = parseArgs(argsJson).string("query").orEmpty()
            if (query.isBlank()) {
                return@ToolExecutor ToolResult(texts.toolRecallEmpty)
            }
            val hits = retriever.search(query)
            if (hits.isEmpty()) return@ToolExecutor ToolResult(texts.toolRecallEmpty)
            runCatching { store.touch(hits.map { it.id }) }
            ToolResult(
                hits.joinToString("\n") {
                    texts.toolRecallLine(it.id, texts.categoryLabel(it.category), it.content)
                },
            )
        }
        return spec to executor
    }

    /** `forget`:只认精确 id;未命中 isError 附「先用 recall 查 id」。 */
    fun forget(
        store: MemoryStore,
        texts: MemoryTexts,
    ): Pair<ToolSpec, ToolExecutor> {
        val spec = ToolSpec(
            name = NAME_FORGET,
            description = texts.toolForgetDescription,
            parametersSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "要删除的记忆 id(recall 结果里的精确 id)")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("id")) })
            },
            systemHint = texts.toolForgetHint,
        )
        val executor = ToolExecutor { argsJson ->
            val id = parseArgs(argsJson).string("id")?.trim().orEmpty()
            if (id.isEmpty()) return@ToolExecutor ToolResult(texts.toolForgetArgError, isError = true)
            if (store.delete(id)) {
                ToolResult(texts.toolForgetDone(id))
            } else {
                ToolResult(texts.toolForgetMiss, isError = true)
            }
        }
        return spec to executor
    }

    /** 单条内容嵌入：无缝/失败 → null（内容照存，检索走关键词）。 */
    internal suspend fun embedSafely(
        content: String,
        embedder: com.neethu.aiadapter.api.EmbeddingAdapter?,
        embeddingConfig: () -> com.neethu.aiadapter.api.EmbeddingConfig?,
    ): FloatArray? = try {
        val cfg = embeddingConfig() ?: return null
        embedder?.embed(listOf(content), cfg)?.firstOrNull()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun parseArgs(argumentsJson: String): JsonObject = try {
        json.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.takeIf { it !is kotlinx.serialization.json.JsonNull }
            ?.let { runCatching { it.content }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
}
