package com.neethu.orchestrator.agent

import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.promptTextsOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 配饰工具（docs/accessory-feasibility.md §5.6）：id 白名单校验（未知名
 * 回错误文案附合法值）、缝失败归因到文案的映射、schema 形态锁。全部纯 JVM
 * ——挂载能力是注入的假缝（与 AgentDeviceToolsCatalogTest 同构）。
 */
class AgentAccessoryToolsCatalogTest {

    private val texts = promptTextsOf(Lang.ZH)

    // ── wear_accessory ──────────────────────────────────────────────────────

    @Test
    fun `wear with known id invokes seam and confirms`() = runTest {
        val worn = mutableListOf<String>()
        val (spec, executor) = AgentToolCatalog.wearAccessory(
            texts,
            catalogIds = { listOf("phone", "hat") },
            wear = { worn += it; null },
        )
        assertEquals("wear_accessory", spec.name)
        assertEquals(texts.toolAccessoryWorn("phone"), executor.invoke("""{"id":"phone"}""").content)
        assertEquals(listOf("phone"), worn)
    }

    @Test
    fun `wear unknown or missing id rejected with valid list`() = runTest {
        val worn = mutableListOf<String>()
        val (_, executor) = AgentToolCatalog.wearAccessory(
            texts,
            catalogIds = { listOf("phone", "hat") },
            wear = { worn += it; null },
        )
        val bad = executor.invoke("""{"id":"crown"}""")
        val missing = executor.invoke("{}")
        assertTrue(bad.isError && missing.isError)
        assertTrue(bad.content.contains("phone"))
        assertTrue(bad.content.contains("hat"))
        assertTrue(missing.content.contains("缺少必填参数"))
        assertTrue(worn.isEmpty())
    }

    @Test
    fun `wear seam failures map to dedicated texts`() = runTest {
        fun executorWith(failure: AccessoryWearFailure?) =
            AgentToolCatalog.wearAccessory(texts, { listOf("phone") }, { failure }).second

        assertFalse(executorWith(null).invoke("""{"id":"phone"}""").isError)
        val already = executorWith(AccessoryWearFailure.ALREADY_WEARING).invoke("""{"id":"phone"}""")
        assertEquals(texts.toolAccessoryAlreadyWearing, already.content)
        val noModel = executorWith(AccessoryWearFailure.NO_MODEL).invoke("""{"id":"phone"}""")
        assertTrue(noModel.isError)
        assertEquals(texts.toolAccessoryNoModel, noModel.content)
        val failed = executorWith(AccessoryWearFailure.FAILED).invoke("""{"id":"phone"}""")
        assertTrue(failed.isError)
        assertEquals(texts.toolAccessoryFailed, failed.content)
    }

    // ── remove_accessory ────────────────────────────────────────────────────

    @Test
    fun `remove with worn id succeeds`() = runTest {
        val removed = mutableListOf<String>()
        val (spec, executor) = AgentToolCatalog.removeAccessory(
            texts,
            wornIds = { listOf("phone") },
            remove = { removed += it; null },
        )
        assertEquals("remove_accessory", spec.name)
        assertEquals(texts.toolAccessoryRemoved("phone"), executor.invoke("""{"id":"phone"}""").content)
        assertEquals(listOf("phone"), removed)
    }

    @Test
    fun `remove not-worn id rejected without invoking seam`() = runTest {
        val removed = mutableListOf<String>()
        val (_, executor) = AgentToolCatalog.removeAccessory(
            texts,
            wornIds = { listOf("hat") },
            remove = { removed += it; null },
        )
        val result = executor.invoke("""{"id":"phone"}""")
        assertTrue(result.isError)
        assertEquals(texts.toolAccessoryNotWorn, result.content)
        assertTrue(removed.isEmpty())
    }
}
