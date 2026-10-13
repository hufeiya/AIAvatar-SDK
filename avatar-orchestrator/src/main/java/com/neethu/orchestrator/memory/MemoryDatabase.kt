package com.neethu.orchestrator.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * 长期记忆库（docs/memory-feasibility.md §4.1）：独立 Room 库
 * `agent_memory.db`，与对话历史（avatar_conversations.db）分开——记忆跨上下文
 * 全局共享，生命周期与任何一段对话无关。同步 DAO，照 ConversationMessageDao
 * 惯例（单线程 IO scope 写入在 [RoomMemoryStore] 内聚）。
 */
@Entity(tableName = "agent_memories")
data class AgentMemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val category: String,
    val importance: Int,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int,
    /** FloatArray→ByteBuffer LE（bge-m3 1024 维）；null = 未嵌入。 */
    val embedding: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is AgentMemoryEntity && other.id == id && other.embedding.contentEquals(embedding)

    override fun hashCode(): Int = id.hashCode() * 31 + embedding.contentHashCode()
}

/** 待提炼窗口（§2.1）：单行表，id 恒 1。turnsJson = `[{"u":"…","a":"…"}]`。 */
@Entity(tableName = "pending_extract_window")
data class PendingWindowEntity(
    @PrimaryKey val id: Int,
    val turnsJson: String,
    val updatedAt: Long,
)

@Dao
interface AgentMemoryDao {
    @Upsert
    fun upsert(memory: AgentMemoryEntity)

    @Query("SELECT * FROM agent_memories")
    fun all(): List<AgentMemoryEntity>

    @Query("SELECT COUNT(*) FROM agent_memories")
    fun count(): Int

    @Query("DELETE FROM agent_memories WHERE id = :id")
    fun delete(id: String): Int

    @Query("DELETE FROM agent_memories")
    fun clear()

    @Query("UPDATE agent_memories SET content = :content, embedding = NULL, updatedAt = :now WHERE id = :id")
    fun updateContent(id: String, content: String, now: Long)

    @Query("UPDATE agent_memories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id IN (:ids)")
    fun touch(ids: List<String>, now: Long)
}

@Dao
interface PendingWindowDao {
    @Upsert
    fun upsert(entity: PendingWindowEntity)

    @Query("SELECT * FROM pending_extract_window WHERE id = 1")
    fun get(): PendingWindowEntity?

    @Query("DELETE FROM pending_extract_window")
    fun clear()
}

@Database(
    entities = [AgentMemoryEntity::class, PendingWindowEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): AgentMemoryDao
    abstract fun pendingWindowDao(): PendingWindowDao

    companion object {
        @Volatile
        private var instance: MemoryDatabase? = null

        fun getInstance(context: Context): MemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "agent_memory.db",
                ).build().also { instance = it }
            }
    }
}
