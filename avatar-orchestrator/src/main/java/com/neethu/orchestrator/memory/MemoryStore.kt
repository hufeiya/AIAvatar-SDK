package com.neethu.orchestrator.memory

/**
 * 长期记忆的存储缝（docs/memory-feasibility.md §4.1）：orchestrator 纯 JVM
 * 可测，Room 实现（[RoomMemoryStore]）由集成方注入。记忆跨上下文全局共享
 * ——不挂 contextId，用户是同一个人，换上下文/换人设卡共享记忆。
 */
interface MemoryStore {
    /**
     * 落库一条记忆，含去重判定：与库内已有条目构成重复（余弦 >0.92 或无嵌入
     * 时逐字相等）→ 覆盖那条的 content/embedding（保 accessCount/createdAt），
     * 不新增。返回实际落库条（新增的或被覆盖更新的既有条）。
     */
    suspend fun add(memory: AgentMemory): AgentMemory

    /** 覆盖内容（提炼的 update op）；嵌入随内容失效清空（检索暂走关键词）。 */
    suspend fun updateContent(id: String, content: String)

    /** @return 库里确实存在并已删除。 */
    suspend fun delete(id: String): Boolean

    suspend fun all(): List<AgentMemory>

    suspend fun count(): Int

    suspend fun clear()

    /** 检索命中回写：accessCount+1 / lastAccessedAt=now（未来衰减/整理的数据底座）。 */
    suspend fun touch(ids: List<String>)
}

/**
 * 待提炼窗口的持久化缝（§2.1）：窗口存 `agent_memory.db` 单行表，force-stop
 * 不丢、跨会话连续计数。可选能力——[MemoryCoordinator] 按实例是否实现本接口
 * 决定持久化或仅内存。
 */
interface PendingWindowStore {
    suspend fun loadWindow(): List<MemoryTurn>
    suspend fun saveWindow(turns: List<MemoryTurn>)
}

/** 机械去重（§2.2 双层去重的机械层）：余弦阈值 + 无嵌入退化逐字相等。 */
object MemoryDedup {
    const val COSINE_THRESHOLD = 0.92f

    /**
     * 在 [existing] 里找 [candidate] 的重复条：有嵌入比嵌入（先到先得），
     * 候选无嵌入退化为 content 逐字相等。命中返回既有条，否则 null。
     */
    fun findDuplicate(candidate: AgentMemory, existing: List<AgentMemory>): AgentMemory? {
        val candVec = candidate.embedding
        if (candVec == null) {
            return existing.firstOrNull { it.content == candidate.content }
        }
        return existing.firstOrNull { memo ->
            memo.embedding?.let { cosine(candVec, it) > COSINE_THRESHOLD } ?: false
        }
    }

    /**
     * 标准余弦：零向量/维度不一致/空输入按 0 处理（不抛——检索是主回合的
     * 前置路径，任何数学边界都不能变成回合失败）。
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).coerceIn(-1f, 1f)
    }
}

/**
 * 纯内存实现：单测 fake + 不需要持久化的最小接入。去重语义与 Room 实现共用
 * [MemoryDedup]，两实现的 add 行为由同一组单测锁定。
 */
class InMemoryMemoryStore(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : MemoryStore, PendingWindowStore {

    private val rows = LinkedHashMap<String, AgentMemory>()
    private var window: List<MemoryTurn> = emptyList()

    override suspend fun add(memory: AgentMemory): AgentMemory {
        val duplicate = MemoryDedup.findDuplicate(memory, rows.values.toList())
        if (duplicate == null) {
            rows[memory.id] = memory
            return memory
        }
        val merged = duplicate.copy(
            content = memory.content,
            category = memory.category,
            importance = memory.importance,
            embedding = memory.embedding ?: duplicate.embedding,
            updatedAt = nowMs(),
        )
        rows[duplicate.id] = merged
        return merged
    }

    override suspend fun updateContent(id: String, content: String) {
        val row = rows[id] ?: return
        rows[id] = row.copy(content = content, embedding = null, updatedAt = nowMs())
    }

    override suspend fun delete(id: String): Boolean = rows.remove(id) != null

    override suspend fun all(): List<AgentMemory> = rows.values.toList()

    override suspend fun count(): Int = rows.size

    override suspend fun clear() = rows.clear()

    override suspend fun touch(ids: List<String>) {
        ids.forEach { id ->
            rows[id]?.let { rows[id] = it.copy(accessCount = it.accessCount + 1, lastAccessedAt = nowMs()) }
        }
    }

    override suspend fun loadWindow(): List<MemoryTurn> = window

    override suspend fun saveWindow(turns: List<MemoryTurn>) {
        window = turns.toList()
    }
}
