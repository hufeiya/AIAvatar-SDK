package com.neethu.orchestrator.memory

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room 关系存储（酒馆 P3-1）：不起真 Room/Robolectric——Fake DAO 直灌，
 * 与 RoomConversationStoreTest 同策略；覆盖「镜像读 + 异步写」语义与
 * favorability 钳制。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomRelationshipStoreTest {

    private class FakeRelationshipDao : RelationshipStateDao {
        val map = LinkedHashMap<String, RelationshipStateEntity>()
        override suspend fun getFor(avatarId: String): RelationshipStateEntity? = map[avatarId]
        override suspend fun upsert(entity: RelationshipStateEntity) {
            map[entity.avatarId] = entity
        }
        override suspend fun clearFor(avatarId: String): Int = if (map.remove(avatarId) != null) 1 else 0
    }

    @Test
    fun `save then load round-trips`() = runTest {
        val dao = FakeRelationshipDao()
        val store = RoomRelationshipStore(dao)
        store.save(RelationshipRecord(avatarId = "a", favorability = 55, anniversaryEpochDay = 900, nickname = "阿岚", updatedAt = 0))
        val loaded = store.load("a")
        assertEquals(55, loaded!!.favorability)
        assertEquals(900L, loaded.anniversaryEpochDay)
        assertEquals("阿岚", loaded.nickname)
    }

    @Test
    fun `favorability clamped on write`() = runTest {
        val dao = FakeRelationshipDao()
        val store = RoomRelationshipStore(dao)
        store.save(RelationshipRecord(avatarId = "a", favorability = 999, anniversaryEpochDay = 1, updatedAt = 0))
        assertEquals(100, dao.map["a"]!!.favorability)
        store.save(RelationshipRecord(avatarId = "a", favorability = -5, anniversaryEpochDay = 1, updatedAt = 0))
        assertEquals(0, dao.map["a"]!!.favorability)
    }

    @Test
    fun `blank nickname stored as null`() = runTest {
        val dao = FakeRelationshipDao()
        val store = RoomRelationshipStore(dao)
        store.save(RelationshipRecord(avatarId = "a", favorability = 1, anniversaryEpochDay = 1, nickname = "  ", updatedAt = 0))
        assertNull(dao.map["a"]!!.nickname)
    }

    @Test
    fun `clear reports existence`() = runTest {
        val dao = FakeRelationshipDao()
        val store = RoomRelationshipStore(dao)
        store.save(RelationshipRecord(avatarId = "a", favorability = 1, anniversaryEpochDay = 1, updatedAt = 0))
        assertTrue(store.clear("a"))
        assertFalse(store.clear("a"))
        assertNull(store.load("a"))
    }
}
