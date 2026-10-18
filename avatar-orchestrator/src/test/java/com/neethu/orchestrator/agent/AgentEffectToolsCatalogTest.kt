package com.neethu.orchestrator.agent

import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.promptTextsOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 特效工具（docs/vfx-feasibility.md §六 P2）：id 白名单校验（未知名回错误
 * 文案附合法值）、at 位置枚举校验、position 数组解析、缝失败归因到文案的映射、
 * schema 形态锁。全部纯 JVM——播放能力是注入的假缝（AgentAccessoryTools
 * CatalogTest 同构）。
 */
class AgentEffectToolsCatalogTest {

    private val texts = promptTextsOf(Lang.ZH)

    private fun playExecutor(
        failure: EffectPlayFailure?,
        played: MutableList<String> = mutableListOf(),
        seenAt: Array<String?> = arrayOf(null),
        seenPos: Array<FloatArray?> = arrayOf(null),
    ) = AgentToolCatalog.playEffect(
        texts,
        effectIds = { listOf("snow", "fire", "magic_circle") },
        play = { effect, at, pos ->
            played += effect; seenAt[0] = at; seenPos[0] = pos; failure
        },
    )

    // ── play_effect ──────────────────────────────────────────────────────

    @Test
    fun `play with known id invokes seam and confirms`() = runTest {
        val played = mutableListOf<String>()
        val (spec, executor) = playExecutor(null, played = played)
        assertEquals("play_effect", spec.name)
        assertEquals(
            texts.toolEffectPlayed("snow"),
            executor.invoke("""{"effect":"snow"}""").content,
        )
        assertEquals(listOf("snow"), played)
    }

    @Test
    fun `play unknown or missing id rejected with valid list`() = runTest {
        val (_, executor) = playExecutor(null)
        val bad = executor.invoke("""{"effect":"firework"}""")
        val missing = executor.invoke("{}")
        assertTrue(bad.isError && missing.isError)
        assertTrue(bad.content.contains("snow"))
        assertTrue(bad.content.contains("magic_circle"))
        assertTrue(missing.content.contains("缺少必填参数"))
    }

    @Test
    fun `play at enum validated against whitelist`() = runTest {
        val played = mutableListOf<String>()
        val seenAt = arrayOf<String?>(null)
        val (spec, executor) = playExecutor(null, played = played, seenAt = seenAt)
        // 合法 at 透传缝
        executor.invoke("""{"effect":"fire","at":"camera_front"}""")
        assertEquals("camera_front", seenAt[0])
        // 非法 at 报错且不调缝（played 仍 1 条 = 上一行的合法调用）
        val bad = executor.invoke("""{"effect":"fire","at":"on_the_moon"}""")
        assertTrue(bad.isError)
        for (opt in AgentToolCatalog.EFFECT_AT_OPTIONS) assertTrue(bad.content.contains(opt))
        assertEquals(1, played.size)
        // schema 里 at 枚举与白名单一致
        assertTrue(spec.parametersSchema.toString().contains("\"enum\":[\"around_me\",\"stage_center\",\"camera_front\"]"))
    }

    @Test
    fun `play position array parsed into float3`() = runTest {
        val seenPos = arrayOf<FloatArray?>(null)
        val (_, executor) = playExecutor(null, seenPos = seenPos)
        executor.invoke("""{"effect":"snow","position":[1.5,0,-3.2]}""")
        assertEquals(3, seenPos[0]?.size)
        assertEquals(1.5f, seenPos[0]!![0], 1e-6f)
        assertEquals(-3.2f, seenPos[0]!![2], 1e-6f)
        // 脏 position（非数/缺失）按无 position 处理（不炸回合）
        val dirty = executor.invoke("""{"effect":"snow","position":["x","y"]}""")
        assertFalse(dirty.isError)
    }

    @Test
    fun `play seam failures map to dedicated texts`() = runTest {
        val notAvail = playExecutor(EffectPlayFailure.NOT_AVAILABLE).second
            .invoke("""{"effect":"snow"}""")
        assertEquals(texts.toolEffectNotAvailable, notAvail.content)
        assertTrue(notAvail.isError)

        val unknown = playExecutor(EffectPlayFailure.UNKNOWN_EFFECT).second
            .invoke("""{"effect":"snow"}""")
        assertEquals(texts.toolEffectUnknown, unknown.content)
        assertTrue(unknown.isError)
    }

    @Test
    fun `play schema shape lock`() {
        val (spec, _) = playExecutor(null)
        val schema = spec.parametersSchema.toString()
        assertTrue(schema.contains("\"required\":[\"effect\"]"))
        assertTrue(schema.contains("\"enum\":[\"snow\",\"fire\",\"magic_circle\"]"))
        assertFalse(spec.systemHint.isNullOrBlank())
    }

    // ── stop_effect ──────────────────────────────────────────────────────

    @Test
    fun `stop invokes seam and confirms regardless of args`() = runTest {
        var stopped = 0
        val (spec, executor) = AgentToolCatalog.stopEffect(texts) { stopped++ }
        assertEquals("stop_effect", spec.name)
        assertEquals(texts.toolEffectStopped, executor.invoke("{}").content)
        assertEquals(texts.toolEffectStopped, executor.invoke("""{"whatever":1}""").content)
        assertEquals(2, stopped)
        assertNull(spec.systemHint?.takeIf { it.isBlank() })
    }

    @Test
    fun `at options whitelist is locked`() {
        assertEquals(listOf("around_me", "stage_center", "camera_front"), AgentToolCatalog.EFFECT_AT_OPTIONS)
    }
}
