package com.neethu.orchestrator.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * avatarId 分片视图（一屏多虚拟人 P1，docs/multi-avatar-feasibility.md §3.2）：
 * 分片写入自动盖章、跨分片互不可见、去重收敛在分片内、清空只动本分片、
 * 待提炼窗口分人。InMemory 与 Room 同构实现，这里锁语义正本。
 */
class MemoryStoreScopeTest {

    private fun memory(id: String, content: String) = AgentMemory(
        id = id,
        content = content,
        category = MemoryCategories.OTHER,
        importance = 5,
        source = MemorySources.AUTO,
        createdAt = 1_000L,
        updatedAt = 1_000L,
        lastAccessedAt = 1_000L,
    )

    @Test
    fun `scoped add stamps avatarId and global view sees all slices`() = runBlocking {
        val global = InMemoryMemoryStore()
        val a = global.scoped("stage_a")
        val b = global.scoped("stage_b")

        a.add(memory("m1", "用户喜欢甜品"))
        b.add(memory("m2", "用户在写编译器"))

        assertEquals("stage_a", global.all().first { it.id == "m1" }.avatarId)
        assertEquals("stage_b", global.all().first { it.id == "m2" }.avatarId)
        assertEquals(2, global.count())
    }

    @Test
    fun `slices are isolated from each other`() = runBlocking {
        val global = InMemoryMemoryStore()
        val a = global.scoped("stage_a")
        val b = global.scoped("stage_b")
        a.add(memory("m1", "用户喜欢甜品"))

        assertTrue(a.all().any { it.id == "m1" })
        assertFalse(b.all().any { it.id == "m1" })
        assertEquals(1, a.count())
        assertEquals(0, b.count())
    }

    @Test
    fun `dedup stays inside a slice`() = runBlocking {
        val global = InMemoryMemoryStore()
        val a = global.scoped("stage_a")
        val b = global.scoped("stage_b")

        val first = a.add(memory("m1", "周五要开会"))
        val bRow = b.add(memory("m2", "周五要开会")) // 同文，但 B 分片无重复
        val merged = a.add(memory("m3", "周五要开会")) // A 分片内重复 → 覆盖不新增

        assertEquals("m1", merged.id)
        assertEquals("stage_a", merged.avatarId)
        assertEquals("stage_b", bRow.avatarId)
        assertTrue(a.all().map { it.id }.toSet() == setOf("m1"))
    }

    @Test
    fun `clear only touches own slice`() = runBlocking {
        val global = InMemoryMemoryStore()
        val a = global.scoped("stage_a")
        val b = global.scoped("stage_b")
        a.add(memory("m1", "A 的事"))
        b.add(memory("m2", "B 的事"))

        a.clear()

        assertEquals(0, a.count())
        assertEquals(1, b.count())
        assertEquals(1, global.count())
    }

    @Test
    fun `pending window is per slice`() = runBlocking {
        val global = InMemoryMemoryStore()
        val a = global.scoped("stage_a") as PendingWindowStore
        val b = global.scoped("stage_b") as PendingWindowStore

        a.saveWindow(listOf(MemoryTurn("对 A 说", "A 答")))
        b.saveWindow(listOf(MemoryTurn("对 B 说", "B 答")))

        assertEquals(listOf(MemoryTurn("对 A 说", "A 答")), a.loadWindow())
        assertEquals(listOf(MemoryTurn("对 B 说", "B 答")), b.loadWindow())

        (a as MemoryStore).clear()
        assertTrue(a.loadWindow().isEmpty())
        assertEquals(1, b.loadWindow().size)
    }

    @Test
    fun `default scoped returns self (stores without the dimension)`() {
        // 未实现维度的裸 store（接口默认实现）：scoped 恒返回自身，语义零变化
        val bare = object : MemoryStore {
            override suspend fun add(memory: AgentMemory) = memory
            override suspend fun updateContent(id: String, content: String) = Unit
            override suspend fun delete(id: String) = false
            override suspend fun all() = emptyList<AgentMemory>()
            override suspend fun count() = 0
            override suspend fun clear() = Unit
            override suspend fun touch(ids: List<String>) = Unit
        }
        assertTrue(bare.scoped("x") === bare)
    }
}
