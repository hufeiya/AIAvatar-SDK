package com.neethu.orchestrator.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/**
 * Room 持久化（任务 3）：一次对话上下文 = 一条 [SessionEntity] + 若干
 * [MessageEntity]。上下文 id 由集成方生成并持久化（demo 写 demo_prefs 的
 * `ai_context_id`），进程重启后用同一 id 重建 [RoomConversationStore] 即可
 * 恢复整段历史。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    /** 信息性字段：创建该上下文时的卡片文件名/人设标识，可空。 */
    val characterId: String? = null,
    val updatedAt: Long = 0L,
)

@Entity(
    tableName = "messages",
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: String,
    /** [com.neethu.aiadapter.model.ChatRole] 的 name()，损坏值读取时跳过。 */
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Dao
interface ConversationSessionDao {
    @Upsert
    fun upsert(session: SessionEntity)

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun sessions(): List<SessionEntity>

    @Query("DELETE FROM sessions WHERE id = :id")
    fun delete(id: String)
}

@Dao
interface ConversationMessageDao {
    @Insert
    fun insert(message: MessageEntity)

    /** 按 id 升序 = 写入顺序（同一毫秒的多条消息也保序）。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY id ASC")
    fun messagesFor(sessionId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    fun deleteFor(sessionId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    fun countFor(sessionId: String): Int
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ConversationDatabase : RoomDatabase() {

    abstract fun sessionDao(): ConversationSessionDao
    abstract fun messageDao(): ConversationMessageDao

    companion object {
        @Volatile
        private var instance: ConversationDatabase? = null

        fun getInstance(context: Context): ConversationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ConversationDatabase::class.java,
                    "avatar_conversations.db",
                ).build().also { instance = it }
            }
    }
}
