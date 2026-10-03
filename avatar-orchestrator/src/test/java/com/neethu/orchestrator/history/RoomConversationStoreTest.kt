package com.neethu.orchestrator.history

import com.neethu.aiadapter.model.ChatRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoomConversationStore] 逻辑（fake DAO，不起真 Room/Robolectric）：
 * 懒加载、镜像即读事实来源、写库排队、会话行 touch、清空、跨实例持久化往返。
 */
class RoomConversationStoreTest {

    private class FakeSessionDao : ConversationSessionDao {
        val rows = LinkedHashMap<String, SessionEntity>()
        var upserts = 0
        override fun upsert(session: SessionEntity) {
            rows[session.id] = session
            upserts++
        }

        override fun sessions(): List<SessionEntity> =
            rows.values.sortedByDescending { it.updatedAt }

        override fun delete(id: String) {
            rows.remove(id)
        }
    }

    private class FakeMessageDao : ConversationMessageDao {
        val rows = mutableListOf<MessageEntity>()
        private var nextId = 0L
        val deletedFor = mutableListOf<String>()

        override fun insert(message: MessageEntity) {
            rows += message.copy(id = ++nextId)
        }

        override fun messagesFor(sessionId: String): List<MessageEntity> =
            rows.filter { it.sessionId == sessionId }.sortedBy { it.id }

        override fun deleteFor(sessionId: String) {
            deletedFor += sessionId
            rows.removeAll { it.sessionId == sessionId }
        }

        override fun countFor(sessionId: String): Int = rows.count { it.sessionId == sessionId }
    }

    private fun newStore(
        sessionDao: FakeSessionDao,
        messageDao: FakeMessageDao,
        sessionId: String = "ctx-1",
        characterId: String? = "cardA.png",
    ): RoomConversationStore = RoomConversationStore(
        sessionDao, messageDao, sessionId, characterId,
        ioScope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `lazy loads existing history from the dao in insertion order`() {
        val messageDao = FakeMessageDao()
        messageDao.insert(MessageEntity(sessionId = "ctx-1", role = "USER", content = "u0", createdAt = 1))
        messageDao.insert(MessageEntity(sessionId = "ctx-1", role = "ASSISTANT", content = "a0", createdAt = 2))
        messageDao.insert(MessageEntity(sessionId = "ctx-1", role = "GARBAGE", content = "跳过", createdAt = 3))
        messageDao.insert(MessageEntity(sessionId = "ctx-2", role = "USER", content = "别的上下文", createdAt = 4))

        val store = newStore(FakeSessionDao(), messageDao)

        assertEquals(
            listOf("u0" to ChatRole.USER, "a0" to ChatRole.ASSISTANT),
            store.messages().map { it.content to it.role },
        )
    }

    @Test
    fun `append updates the mirror immediately and persists rows plus the session touch`() {
        val sessionDao = FakeSessionDao()
        val messageDao = FakeMessageDao()
        val store = newStore(sessionDao, messageDao)

        store.appendUser("你好")
        store.appendAssistant("嗨！")

        // 镜像立即可读（不等落库）
        assertEquals(
            listOf(ChatRole.USER to "你好", ChatRole.ASSISTANT to "嗨！"),
            store.messages().map { it.role to it.content },
        )
        // Unconfined ioScope：launch 同步执行，DAO 状态可直接断言
        assertEquals(2, messageDao.rows.size)
        assertEquals("ctx-1", messageDao.rows[0].sessionId)
        assertEquals("USER", messageDao.rows[0].role)
        assertEquals(2, sessionDao.upserts) // 每次追加 touch 一次会话行
        val sessionRow = sessionDao.rows["ctx-1"]!!
        assertEquals("cardA.png", sessionRow.characterId)
        assertTrue(sessionRow.updatedAt > 0)
    }

    @Test
    fun `clear wipes the mirror and the dao rows of this session`() {
        val sessionDao = FakeSessionDao()
        val messageDao = FakeMessageDao()
        val store = newStore(sessionDao, messageDao)
        store.appendUser("u0")
        messageDao.insert(MessageEntity(sessionId = "ctx-2", role = "USER", content = "别动我", createdAt = 9))

        store.clear()

        assertEquals(0, store.messages().size)
        assertEquals(0, messageDao.countFor("ctx-1"))
        assertEquals(1, messageDao.countFor("ctx-2"))
    }

    @Test
    fun `a fresh store instance over the same dao restores the persisted history`() {
        val sessionDao = FakeSessionDao()
        val messageDao = FakeMessageDao()
        newStore(sessionDao, messageDao).apply {
            appendUser("u0")
            appendAssistant("a0")
        }

        // 模拟进程重启：同一 sessionId 新建 store，历史应从 DAO 恢复
        val reopened = newStore(sessionDao, messageDao)
        assertEquals(
            listOf("u0" to ChatRole.USER, "a0" to ChatRole.ASSISTANT),
            reopened.messages().map { it.content to it.role },
        )
    }

    @Test
    fun `session dao lists contexts by most recent use`() {
        val sessionDao = FakeSessionDao()
        val messageDao = FakeMessageDao()
        newStore(sessionDao, messageDao, sessionId = "old", characterId = null).apply { appendUser("旧") }
        // updatedAt 取墙钟毫秒，两次追加可能同毫秒；隔开保证排序断言确定
        Thread.sleep(5)
        newStore(sessionDao, messageDao, sessionId = "new", characterId = "b.png").apply { appendUser("新") }

        val listed = sessionDao.sessions()
        assertEquals(listOf("new", "old"), listed.map { it.id })
        assertEquals("b.png", listed[0].characterId)
    }
}
