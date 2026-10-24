package com.neethu.orchestrator.agent

import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.promptTextsOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `set_skybox`（docs/skybox.md）：id 白名单校验（未知名回错误文案附合法值，
 * 含 off 哨兵）、off 走 null 缝、缝失败归因到文案、schema 形态锁。全部纯
 * JVM——切换能力是注入的假缝（AgentEffectToolsCatalogTest 同构）。
 */
class AgentSkyboxToolsCatalogTest {

    private val texts = promptTextsOf(Lang.ZH)

    @Test
    fun `known id invokes seam and confirms`() = runTest {
        val seen = mutableListOf<String?>()
        val (spec, executor) = executor(result = true, seen = seen)
        assertEquals("set_skybox", spec.name)
        assertEquals(
            texts.toolSkyboxSet("starlight_lake"),
            executor.invoke("""{"skybox":"starlight_lake"}""").content,
        )
        assertEquals(listOf("starlight_lake"), seen)
    }

    @Test
    fun `off sentinel maps to null seam and cleared text`() = runTest {
        val seen = mutableListOf<String?>()
        val (_, executor) = executor(result = true, seen = seen)
        assertEquals(texts.toolSkyboxCleared, executor.invoke("""{"skybox":"off"}""").content)
        assertEquals(listOf<String?>(null), seen)
    }

    @Test
    fun `unknown or missing id rejected with valid list`() = runTest {
        val seen = mutableListOf<String?>()
        val (_, executor) = executor(result = true, seen = seen)
        val bad = executor.invoke("""{"skybox":"mars"}""")
        val missing = executor.invoke("{}")
        assertTrue(bad.isError && missing.isError)
        assertTrue(bad.content.contains("starlight_lake"))
        assertTrue(bad.content.contains("off"))
        assertTrue(missing.content.contains("缺少必填参数"))
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `seam failure maps to dedicated text`() = runTest {
        val (_, executor) = executor(result = false)
        val failed = executor.invoke("""{"skybox":"starlight_lake"}""")
        assertEquals(texts.toolSkyboxFailed, failed.content)
        assertTrue(failed.isError)
        val failedOff = executor.invoke("""{"skybox":"off"}""")
        assertEquals(texts.toolSkyboxFailed, failedOff.content)
        assertTrue(failedOff.isError)
    }

    @Test
    fun `schema shape lock`() {
        val (spec, _) = executor(result = true)
        val schema = spec.parametersSchema.toString()
        assertTrue(schema.contains("\"required\":[\"skybox\"]"))
        assertTrue(schema.contains("\"enum\":[\"starlight_lake\",\"forest_dawn\",\"off\"]"))
        assertFalse(spec.systemHint.isNullOrBlank())
    }

    private fun executor(result: Boolean, seen: MutableList<String?>? = null) =
        AgentToolCatalog.setSkybox(
            texts,
            skyboxIds = { listOf("starlight_lake", "forest_dawn") },
            set = { id ->
                seen?.add(id)
                result
            },
        )
}
