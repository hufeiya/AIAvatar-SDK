package com.neethu.orchestrator.memory

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储与机械去重（docs/memory-feasibility.md §2.2/§6）：CRUD、余弦 >0.92
 * 覆盖不新增（保 accessCount）、无嵌入逐字去重、clear、touch 语义。
 * InMemory 与 RoomMemoryStore 共用同一套去重逻辑（MemoryDedup 单点），
 * Room 侧由 Fake DAO 单独锁 entity↔domain 往返。
 */
class MemoryStoreTest {

    private fun row(
        id: String,
        content: String,
        embedding: FloatArray? = null,
        accessCount: Int = 1,
    ) = AgentMemory(
        id = id,
        content = content,
        category = MemoryCategories.PREFERENCE,
        importance = 5,
        source = MemorySources.AUTO,
        createdAt = 100L,
        updatedAt = 100L,
        lastAccessedAt = 100L,
        accessCount = accessCount,
        embedding = embedding,
    )

    @Test
    fun `crud round trip`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(row("m1", "用户喜欢猫"))
        assertEquals(1, store.count())
        assertEquals("用户喜欢猫", store.all().single().content)

        store.updateContent("m1", "用户养了一只猫")
        assertEquals("用户养了一只猫", store.all().single().content)
        assertNull(store.all().single().embedding) // update 清嵌入

        assertTrue(store.delete("m1"))
        assertFalse(store.delete("m1"))
        assertEquals(0, store.count())
    }

    @Test
    fun `near-duplicate vectors overwrite instead of adding and keep accessCount`() = runTest {
        val store = InMemoryMemoryStore()
        val v1 = floatArrayOf(1f, 0f, 0f)
        val v2 = floatArrayOf(0.99f, 0.1f, 0f) // cosine ≈ 0.995 > 0.92
        store.add(row("m1", "用户喜欢猫", embedding = v1))
        store.touch(listOf("m1"))

        val stored = store.add(row("m2", "用户特别喜欢猫", embedding = v2))

        assertEquals("m1", stored.id) // 覆盖既有条，不新增
        assertEquals(1, store.count())
        val after = store.all().single()
        assertEquals("用户特别喜欢猫", after.content)
        assertEquals(2, after.accessCount) // accessCount 保留
        assertEquals(100L, after.createdAt) // createdAt 保留
    }

    @Test
    fun `dissimilar vectors both land`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(row("m1", "用户喜欢猫", embedding = floatArrayOf(1f, 0f)))
        store.add(row("m2", "用户要考试", embedding = floatArrayOf(0f, 1f)))
        assertEquals(2, store.count())
    }

    @Test
    fun `exact content match dedupes when no embeddings exist`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(row("m1", "用户不喜欢香菜"))
        val stored = store.add(row("m2", "用户不喜欢香菜"))
        assertEquals("m1", stored.id)
        assertEquals(1, store.count())
    }

    @Test
    fun `clear wipes everything`() = runTest {
        val store = InMemoryMemoryStore()
        store.add(row("m1", "a"))
        store.add(row("m2", "b"))
        store.clear()
        assertEquals(0, store.count())
    }

    @Test
    fun `touch bumps accessCount and lastAccessedAt only for known ids`() = runTest {
        val store = InMemoryMemoryStore(nowMs = { 999L })
        store.add(row("m1", "a"))
        store.touch(listOf("m1", "missing"))
        val after = store.all().single()
        assertEquals(2, after.accessCount)
        assertEquals(999L, after.lastAccessedAt)
    }

    // ── MemoryDedup 边界 ───────────────────────────────────────────────────

    @Test
    fun `dedup cosine boundary at threshold`() {
        val existing = listOf(row("m", "内容", embedding = floatArrayOf(1f, 0f)))
        val atOrAbove = MemoryDedup.findDuplicate(
            row("c", "变体", embedding = floatArrayOf(0.93f, 0.36f)), // cos≈0.93
            existing,
        )
        assertNotNull(atOrAbove)
        val below = MemoryDedup.findDuplicate(
            row("c", "变体", embedding = floatArrayOf(0.9f, 0.4f)), // cos≈0.914 < 0.92
            existing,
        )
        assertNull(below)
    }
}
