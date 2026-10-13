package com.neethu.orchestrator.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [MemoryStore] 的 Room 适配（docs/memory-feasibility.md §4.1）：镜像读 + 异步
 * 落库，照 RoomConversationStore 同款惯例——内存镜像为读路径事实来源（追加先
 * 动镜像再经单线程 IO scope 写库，进程存活期读写有序；仅被杀时最后一笔有
 * 毫秒级丢失窗口），首次访问 runBlocking(IO) 懒加载一次。待提炼窗口同库
 * 持久化（[PendingWindowStore]），语义同上。
 *
 * avatarId 维度（一屏多虚拟人 P1，docs/multi-avatar-feasibility.md §3.2）：
 * [avatarId]=null 是全局库（demo 管理面板/ai_cmd 用，看全部分片）；
 * [scoped] 产出按虚拟人切片的视图（独立镜像、共享 DAO 与 IO 线程）——写入
 * 自动盖章 avatarId、去重/列举/清空/待提炼窗口都收敛在分片内，注入各 slot
 * 的 AvatarSession 即得「记忆分人记」。
 *
 * 懒加载在构造时即向 IO scope 排一个任务（不 runBlocking 阻塞构造线程）——
 * 首个使用点前若尚未加载完成，[ensureLoaded] 再同步兜一次。
 */
class RoomMemoryStore(
    private val memoryDao: AgentMemoryDao,
    private val windowDao: PendingWindowDao,
    private val ioScope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor { r -> Thread(r, "memory-store-io") }
            .asCoroutineDispatcher(),
    ),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /** null = 全局库（全部分片可见）；非空 = 只见该虚拟人的分片。 */
    private val avatarId: String? = null,
) : MemoryStore, PendingWindowStore {

    companion object {
        private const val TAG = "RoomMemoryStore"

        /** demo/集成方便捷入口：应用级单例库 + 默认 IO scope。 */
        fun from(context: Context): RoomMemoryStore {
            val db = MemoryDatabase.getInstance(context)
            return RoomMemoryStore(db.memoryDao(), db.pendingWindowDao())
        }
    }

    /** 分片视图（P1）：共享 DAO 与 IO scope，独立镜像只装本虚拟人的行。 */
    override fun scoped(avatarId: String): MemoryStore =
        RoomMemoryStore(memoryDao, windowDao, ioScope, nowMs, avatarId)

    private val json = Json { ignoreUnknownKeys = true }
    private val windowSerializer = ListSerializer(MemoryTurnDto.serializer())

    @Serializable
    private data class MemoryTurnDto(val u: String, val a: String)

    private val rows = LinkedHashMap<String, AgentMemory>()
    private var window: List<MemoryTurn> = emptyList()
    private val loaded = AtomicBoolean(false)
    private val lock = Any()

    init {
        ioScope.launch { loadOnce() }
    }

    private fun loadOnce() {
        if (loaded.get()) return
        synchronized(lock) {
            if (loaded.get()) return
            try {
                runBlocking {
                    val entities = if (avatarId != null) memoryDao.allFor(avatarId) else memoryDao.all()
                    entities.forEach { e -> rows[e.id] = e.toDomain() }
                    window = parseWindow(windowDao.getFor(avatarId ?: "")?.turnsJson)
                }
                loaded.set(true)
            } catch (t: Throwable) {
                // 启动期读库失败不炸会话：本轮记忆降级为内存态，下次再试
                Log.w(TAG, "memory db initial load failed: ${t.message}")
            }
        }
    }

    private fun ensureLoaded() {
        if (!loaded.get()) loadOnce()
    }

    // ── MemoryStore ──────────────────────────────────────────────────────

    override suspend fun add(memory: AgentMemory): AgentMemory {
        ensureLoaded()
        // 分片视图写入自动盖章（MemoryTools/提炼不感知维度）
        val incoming = if (avatarId != null && memory.avatarId != avatarId) {
            memory.copy(avatarId = avatarId)
        } else {
            memory
        }
        val stored: AgentMemory = synchronized(lock) {
            val duplicate = MemoryDedup.findDuplicate(incoming, rows.values.toList())
            if (duplicate == null) {
                rows[incoming.id] = incoming
                incoming
            } else {
                // 覆盖 content 保 accessCount/createdAt 不新增（§2.2）
                val merged = duplicate.copy(
                    content = incoming.content,
                    category = incoming.category,
                    importance = incoming.importance,
                    embedding = incoming.embedding ?: duplicate.embedding,
                    updatedAt = nowMs(),
                )
                rows[duplicate.id] = merged
                merged
            }
        }
        writeAsync { memoryDao.upsert(stored.toEntity()) }
        return stored
    }

    override suspend fun updateContent(id: String, content: String) {
        ensureLoaded()
        synchronized(lock) {
            rows[id]?.let { rows[id] = it.copy(content = content, embedding = null, updatedAt = nowMs()) }
        }
        writeAsync { memoryDao.updateContent(id, content, nowMs()) }
    }

    override suspend fun delete(id: String): Boolean {
        ensureLoaded()
        val existed = synchronized(lock) { rows.remove(id) != null }
        if (existed) writeAsync { memoryDao.delete(id) }
        return existed
    }

    override suspend fun all(): List<AgentMemory> {
        ensureLoaded()
        return synchronized(lock) { rows.values.toList() }
    }

    override suspend fun count(): Int {
        ensureLoaded()
        return synchronized(lock) { rows.size }
    }

    override suspend fun clear() {
        ensureLoaded()
        synchronized(lock) {
            rows.clear()
            window = emptyList()
        }
        writeAsync {
            if (avatarId != null) {
                memoryDao.clearFor(avatarId)
                windowDao.clearFor(avatarId)
            } else {
                memoryDao.clear()
                windowDao.clear()
            }
        }
    }

    override suspend fun touch(ids: List<String>) {
        if (ids.isEmpty()) return
        ensureLoaded()
        val now = nowMs()
        synchronized(lock) {
            ids.forEach { id ->
                rows[id]?.let { rows[id] = it.copy(accessCount = it.accessCount + 1, lastAccessedAt = now) }
            }
        }
        writeAsync { memoryDao.touch(ids, now) }
    }

    // ── PendingWindowStore ───────────────────────────────────────────────

    override suspend fun loadWindow(): List<MemoryTurn> {
        ensureLoaded()
        return synchronized(lock) { window }
    }

    override suspend fun saveWindow(turns: List<MemoryTurn>) {
        ensureLoaded()
        synchronized(lock) { window = turns.toList() }
        writeAsync {
            windowDao.upsert(
                PendingWindowEntity(
                    avatarId = avatarId ?: "",
                    turnsJson = json.encodeToString(
                        windowSerializer,
                        turns.map { MemoryTurnDto(u = it.user, a = it.assistant) },
                    ),
                    updatedAt = nowMs(),
                ),
            )
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun parseWindow(raw: String?): List<MemoryTurn> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(windowSerializer, raw).map { MemoryTurn(it.u, it.a) }
        }.getOrElse {
            Log.w(TAG, "pending window json corrupt, dropping: ${it.message}")
            emptyList()
        }
    }

    private fun writeAsync(block: () -> Unit) {
        ioScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "memory db write failed (mirror keeps the value): ${t.message}")
            }
        }
    }
}

/** Entity ↔ Domain 转换：embedding 走 ByteBuffer LE（§4.1 schema 注释的形态）。 */
internal fun AgentMemoryEntity.toDomain(): AgentMemory = AgentMemory(
    id = id,
    content = content,
    category = category,
    importance = importance,
    source = source,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessedAt = lastAccessedAt,
    accessCount = accessCount,
    embedding = embedding?.let { bytes ->
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray(bytes.size / 4) { buffer.float }
    },
)

internal fun AgentMemory.toEntity(): AgentMemoryEntity = AgentMemoryEntity(
    id = id,
    content = content,
    category = category,
    importance = importance,
    source = source,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessedAt = lastAccessedAt,
    accessCount = accessCount,
    avatarId = avatarId,
    embedding = embedding?.let { vec ->
        ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            vec.forEach { putFloat(it) }
        }.array()
    },
)
