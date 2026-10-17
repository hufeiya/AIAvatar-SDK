package com.neethu.aiavatar_sdk.multiavatar

import com.neethu.corelib.vfx.BehaviorParams
import com.neethu.corelib.vfx.EmitterShape
import com.neethu.corelib.vfx.Overflow
import com.neethu.corelib.vfx.VfxBlending
import com.neethu.corelib.vfx.VfxPresets
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VFX 目录（P1 docs/vfx-feasibility.md §5.2）：sidecar presets.json 解析/钳制/
 * 脏条目丢弃、三层合并（内置打底 sidecar 覆盖）、**对照 assets/vfx 目录树断言
 * 贴图路径**（坑 25 教训：资产引用测试必须落到真实文件）。
 */
class VfxCatalogTest {

    /** 工作目录 = app/（Gradle JVM 测试惯例）：直接读 assets 树。 */
    private fun assetFile(rel: String) = File("src/main/assets/$rel")

    private fun sidecarText(): String =
        assetFile("vfx/presets.json").readText(Charsets.UTF_8)

    // ── sidecar 解析 ─────────────────────────────────────────────────────

    /** 带诊断的解析（脏条目/解析异常信息直接进断言消息）。 */
    private fun parseWithErrors(text: String): Pair<List<com.neethu.corelib.vfx.VfxSpec>, String> {
        val errors = mutableListOf<String>()
        val presets = VfxCatalog.parse(text) { errors += it }
        return presets to errors.joinToString("; ")
    }

    @Test
    fun `sidecar 解析出 8 配方且 id 齐全`() {
        val (presets, errors) = parseWithErrors(sidecarText())
        assertEquals("解析诊断: $errors", 8, presets.size)
        assertEquals(
            setOf("spark_burst", "snow", "ring_pulse", "rain", "fire", "confetti", "petals", "lightstick_spark"),
            presets.map { it.id }.toSet(),
        )
    }

    @Test
    fun `贴图引用全部落在 assets 树内（坑25 对照目录树）`() {
        val presets = VfxCatalog.parse(sidecarText())
        val onDisk = File("src/main/assets/vfx/textures").listFiles()!!.map { it.name }.toSet()
        for (p in presets) {
            val tex = p.texture as? com.neethu.corelib.vfx.VfxTexture.Asset
            assertNotNull("preset ${p.id} 应引用 assets 贴图", tex)
            val rel = tex!!.path.removePrefix("vfx/")
            assertTrue("贴图存在: ${tex.path}", assetFile("vfx/$rel").isFile)
            assertTrue("贴图在生成器清单内: $rel", File(rel).name in onDisk)
        }
        assertEquals("生成器产出的贴图全部被引用", 6, onDisk.size)
    }

    @Test
    fun `配方语义字段映射正确`() {
        val byId = VfxCatalog.parse(sidecarText()).associateBy { it.id }
        val fire = byId.getValue("fire")
        assertEquals(VfxBlending.ADDITIVE, fire.blending)
        assertEquals(EmitterShape.CONE, fire.emitter.shape)
        assertEquals(0.25f, fire.emitter.radialFraction)
        assertEquals(4, fire.flipbook!!.columns)
        assertEquals(4, fire.flipbook!!.rows)

        val rain = byId.getValue("rain")
        assertEquals(VfxBlending.TRANSPARENT, rain.blending)
        assertEquals(Overflow.RECYCLE_OLDEST, rain.overflow)
        assertEquals(7f, rain.stretchByVelocity)

        val confetti = byId.getValue("confetti")
        assertEquals(6, confetti.palette!!.size)
        assertTrue(confetti.spinMax != 0f)

        val spark = byId.getValue("lightstick_spark")
        assertEquals("rightHand", spark.defaultBone)

        val snow = byId.getValue("snow")
        assertEquals(2, snow.behaviors.filterIsInstance<BehaviorParams.Sway>().size)

        val petals = byId.getValue("petals")
        assertTrue(petals.behaviors.any { it is BehaviorParams.Turbulence })
    }

    @Test
    fun `脏条目与脏行为丢弃不炸解析`() {
        val dirty = """
            { "presets": [
                { "id": "", "blending": "additive" },
                { "id": "bad_shape", "emitter": { "shape": "torus" } },
                { "id": "half_dirty", "rate": -5,
                  "behaviors": [ { "type": "gravity", "value": [0, -1, 0] },
                                 { "type": "sway", "amplitude": 0.2 } ] },
                { "id": "ok", "shape": "box" }
            ] }
        """.trimIndent()
        val presets = VfxCatalog.parse(dirty)
        assertEquals(listOf("half_dirty", "ok"), presets.map { it.id })
        val half = presets.first { it.id == "half_dirty" }
        assertEquals("负 rate 钳 0", 0f, half.rate)
        assertEquals("脏 sway（缺 frequency）丢弃、gravity 保留", 1, half.behaviors.size)
        assertTrue(half.behaviors[0] is BehaviorParams.Gravity)
    }

    @Test
    fun `坏 JSON 返回空表（回落内置不崩）`() {
        assertTrue(VfxCatalog.parse("{ nope").isEmpty())
    }

    // ── 三层合并 ─────────────────────────────────────────────────────────

    @Test
    fun `合并目录内置打底 sidecar 覆盖同名`() {
        val builtinA = com.neethu.corelib.vfx.VfxSpec(id = "a", maxParticles = 1)
        val builtinSnow = VfxPresets.snow
        val sidecarSnow = builtinSnow.copy(maxParticles = 999)
        val sidecarNew = com.neethu.corelib.vfx.VfxSpec(id = "new", maxParticles = 2)
        val merged = VfxCatalog.merge(
            linkedMapOf("a" to builtinA, "snow" to builtinSnow),
            listOf(sidecarSnow, sidecarNew),
        )
        assertEquals(setOf("a", "snow", "new"), merged.keys)
        assertEquals("同名内置被 sidecar 覆盖", 999, merged.getValue("snow").maxParticles)
        assertEquals(1, merged.getValue("a").maxParticles)
        // 合并键统一小写（查找方负责 lowercase，find 已如此）
        assertTrue(merged.keys.all { it == it.lowercase() })
    }

    @Test
    fun `内置常量表与 sidecar 同源（8 配方防漂移）`() {
        assertEquals(
            "内置 VfxPresets 应与 sidecar 配方集一致（drift = 两侧都改）",
            VfxCatalog.parse(sidecarText()).map { it.id }.toSet(),
            VfxPresets.all.keys,
        )
    }
}
