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
 *
 * v2（一屏多虚拟人 P1，docs/multi-avatar-feasibility.md §3.2）：加 avatarId
 * 维度——记忆分人记，迁移把存量行归入全局分片（avatarId=''）；待提炼窗口
 * 表从「单行表 id=1」改为按 avatarId 一行（互踩问题），窗口是攒批缓存、
 * 最坏损失=一批重新提炼，迁移直接重建。
 *
 * v3（酒馆 P3-1，docs/tavern-companion-feasibility.md §5）：新增
 * relationship_state 表（卡级好感度/纪念日/专属称呼），纯增量迁移。
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
    /** 所属虚拟人分片（MemoryStore.scoped 写入盖章）；'' = 全局共享。 */
    val avatarId: String,
) {
    override fun equals(other: Any?): Boolean =
        other is AgentMemoryEntity && other.id == id && other.embedding.contentEquals(embedding)

    override fun hashCode(): Int = id.hashCode() * 31 + embedding.contentHashCode()
}

/** 待提炼窗口（§2.1）：v2 起按 avatarId 一行（P1 前是单行表 id=1）。turnsJson = `[{"u":"…","a":"…"}]`。 */
@Entity(tableName = "pending_extract_window")
data class PendingWindowEntity(
    @PrimaryKey val avatarId: String,
    val turnsJson: String,
    val updatedAt: Long,
)

@Dao
interface AgentMemoryDao {
    @Upsert
    fun upsert(memory: AgentMemoryEntity)

    @Query("SELECT * FROM agent_memories")
    fun all(): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memories WHERE avatarId = :avatarId")
    fun allFor(avatarId: String): List<AgentMemoryEntity>

    @Query("SELECT COUNT(*) FROM agent_memories")
    fun count(): Int

    @Query("DELETE FROM agent_memories WHERE id = :id")
    fun delete(id: String): Int

    @Query("DELETE FROM agent_memories")
    fun clear()

    @Query("DELETE FROM agent_memories WHERE avatarId = :avatarId")
    fun clearFor(avatarId: String)

    @Query("UPDATE agent_memories SET content = :content, embedding = NULL, updatedAt = :now WHERE id = :id")
    fun updateContent(id: String, content: String, now: Long)

    @Query("UPDATE agent_memories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id IN (:ids)")
    fun touch(ids: List<String>, now: Long)
}

@Dao
interface PendingWindowDao {
    @Upsert
    fun upsert(entity: PendingWindowEntity)

    @Query("SELECT * FROM pending_extract_window WHERE avatarId = :avatarId")
    fun getFor(avatarId: String): PendingWindowEntity?

    @Query("DELETE FROM pending_extract_window")
    fun clear()

    @Query("DELETE FROM pending_extract_window WHERE avatarId = :avatarId")
    fun clearFor(avatarId: String)
}

/**
 * 关系状态（酒馆 P3-1，docs/tavern-companion-feasibility.md §5）：卡级一行
 * KV（好感度/相识纪念日/专属称呼），与记忆同库同生命周期；派生视图
 * （阶段/天数/纪念日）由 [RelationshipTracker] 现算不落盘。
 */
@Entity(tableName = "relationship_state")
data class RelationshipStateEntity(
    @PrimaryKey val avatarId: String,
    val favorability: Int,
    val anniversaryEpochDay: Long,
    val nickname: String?,
    val updatedAt: Long,
)

@Dao
interface RelationshipStateDao {
    /** suspend 形态:Room 自带查询执行器调度,主线程调用不炸(真机 2026-10-25 实锤过)。 */
    @Query("SELECT * FROM relationship_state WHERE avatarId = :avatarId")
    suspend fun getFor(avatarId: String): RelationshipStateEntity?

    @Upsert
    suspend fun upsert(entity: RelationshipStateEntity)

    @Query("DELETE FROM relationship_state WHERE avatarId = :avatarId")
    suspend fun clearFor(avatarId: String): Int
}

@Database(
    entities = [AgentMemoryEntity::class, PendingWindowEntity::class, RelationshipStateEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun memoryDao(): AgentMemoryDao
    abstract fun pendingWindowDao(): PendingWindowDao
    abstract fun relationshipDao(): RelationshipStateDao

    companion object {
        @Volatile
        private var instance: MemoryDatabase? = null

        /** v1→v2（一屏多虚拟人 P1）：存量记忆归全局分片；窗口表重建（攒批缓存，可弃）。 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_memories ADD COLUMN avatarId TEXT NOT NULL DEFAULT ''")
                db.execSQL("DROP TABLE pending_extract_window")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_extract_window` (" +
                        "`avatarId` TEXT NOT NULL, `turnsJson` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`avatarId`))"
                )
            }
        }

        /** v2→v3（酒馆 P3-1）：新增关系状态表（纯增量，存量数据不动）。 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `relationship_state` (" +
                        "`avatarId` TEXT NOT NULL, `favorability` INTEGER NOT NULL, " +
                        "`anniversaryEpochDay` INTEGER NOT NULL, `nickname` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`avatarId`))"
                )
            }
        }

        fun getInstance(context: Context): MemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "agent_memory.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
