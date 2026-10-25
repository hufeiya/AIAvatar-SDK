package com.neethu.orchestrator.agent

import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.promptTextsOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 活动工具（docs/prop-system-feasibility.md §2.3）：id 白名单校验（未知名
 * 回错误文案附合法值，schema/执行双保险）、活动目录摘要随 systemHint 注入、
 * stop 幂等语义（不活动中 = 非错误）、缝失败归因到文案的映射。全部纯 JVM
 * ——编排能力是注入的假缝（与 AgentAccessoryToolsCatalogTest 同构）。
 */
class AgentActivityToolsCatalogTest {

    private val texts = promptTextsOf(Lang.ZH)

    private fun choices() = listOf(
        AgentToolCatalog.ActivityChoice("desk_typing", "用电脑", "坐到书桌前打字", "work"),
        AgentToolCatalog.ActivityChoice("reading", "看书", "窝在椅子上看书", "daily"),
    )

    // ── start_activity ──────────────────────────────────────────────────────

    @Test
    fun `start with known id invokes seam and confirms`() = runTest {
        val started = mutableListOf<String>()
        val (spec, executor) = AgentToolCatalog.startActivity(
            texts,
            activities = { choices() },
            start = { started += it; null },
        )
        assertEquals("start_activity", spec.name)
        assertEquals(texts.toolActivityStarted("用电脑"), executor.invoke("""{"id":"desk_typing"}""").content)
        assertEquals(listOf("desk_typing"), started)
    }

    @Test
    fun `schema enum 锁目录 id 且摘要注入 systemHint`() {
        val (spec, _) = AgentToolCatalog.startActivity(texts, { choices() }, { null })
        val enum = spec.parametersSchema["properties"]!!
            .toString()
        assertTrue(enum.contains("desk_typing"))
        assertTrue(enum.contains("reading"))
        // 目录摘要（id 名称[类别]: 描述）随 hint 注入——LLM 的决策依据
        assertTrue(spec.systemHint.orEmpty().contains("desk_typing 用电脑[work]: 坐到书桌前打字"))
        assertTrue(spec.systemHint.orEmpty().contains("reading 看书[daily]: 窝在椅子上看书"))
    }

    @Test
    fun `unknown or missing id rejected with valid list`() = runTest {
        val started = mutableListOf<String>()
        val (_, executor) = AgentToolCatalog.startActivity(
            texts,
            activities = { choices() },
            start = { started += it; null },
        )
        val bad = executor.invoke("""{"id":"go_to_sleep"}""")
        val missing = executor.invoke("{}")
        assertTrue(bad.isError && missing.isError)
        assertTrue(bad.content.contains("desk_typing"))
        assertTrue(bad.content.contains("reading"))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `执行时目录重验——schema 之后的目录漂移也被拦截`() = runTest {
        // 注册时目录有两条,执行时目录只剩一条（漂移场景）——双保险在 executor 里重验
        var live = choices()
        val (_, executor) = AgentToolCatalog.startActivity(texts, { live }, { null })
        live = listOf(choices()[0])
        val drifted = executor.invoke("""{"id":"reading"}""")
        assertTrue(drifted.isError)
        assertTrue(drifted.content.contains("desk_typing"))
    }

    @Test
    fun `start seam failures map to dedicated texts`() = runTest {
        fun executorWith(failure: ActivityFailure?) =
            AgentToolCatalog.startActivity(texts, { choices() }, { failure }).second

        val busy = executorWith(ActivityFailure.BUSY).invoke("""{"id":"reading"}""")
        assertEquals(texts.toolActivityBusy, busy.content)
        assertFalse(busy.isError) // 防抖忽略非错误（对齐 interact_with BUSY 惯例）
        val noModel = executorWith(ActivityFailure.NO_MODEL).invoke("""{"id":"reading"}""")
        assertTrue(noModel.isError)
        assertEquals(texts.toolActivityNoModel, noModel.content)
        val failed = executorWith(ActivityFailure.FAILED).invoke("""{"id":"reading"}""")
        assertTrue(failed.isError)
        assertEquals(texts.toolActivityFailed, failed.content)
        val unknown = executorWith(ActivityFailure.UNKNOWN_ACTIVITY).invoke("""{"id":"reading"}""")
        assertTrue(unknown.isError)
    }

    // ── stop_activity ───────────────────────────────────────────────────────

    @Test
    fun `stop while active confirms with activity label`() = runTest {
        var stopped = false
        val (spec, executor) = AgentToolCatalog.stopActivity(
            texts,
            current = { choices()[1] },
            stop = { stopped = true; null },
        )
        assertEquals("stop_activity", spec.name)
        val result = executor.invoke("{}")
        assertEquals(texts.toolActivityStopped("看书"), result.content)
        assertFalse(result.isError)
        assertTrue(stopped)
    }

    @Test
    fun `stop while idle is a non-error no-op`() = runTest {
        var seamCalled = false
        val (_, executor) = AgentToolCatalog.stopActivity(
            texts,
            current = { null },
            stop = { seamCalled = true; null },
        )
        val result = executor.invoke("{}")
        assertEquals(texts.toolActivityNotActive, result.content)
        assertFalse(result.isError)
        assertFalse(seamCalled)
    }

    @Test
    fun `stop seam failure maps to error text`() = runTest {
        val (_, executor) = AgentToolCatalog.stopActivity(
            texts,
            current = { choices()[0] },
            stop = { ActivityFailure.FAILED },
        )
        val result = executor.invoke("{}")
        assertTrue(result.isError)
        assertEquals(texts.toolActivityFailed, result.content)
    }
}
