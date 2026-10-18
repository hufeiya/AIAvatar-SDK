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
    fun `sidecar 解析出 12 配方且 id 齐全`() {
        val (presets, errors) = parseWithErrors(sidecarText())
        assertEquals("解析诊断: $errors", 12, presets.size)
        assertEquals(
            setOf(
                "spark_burst", "snow", "ring_pulse", "rain", "fire", "confetti",
                "petals", "lightstick_spark", "stage_lights", "spotlight",
                "magic_circle", "shield",
            ),
            presets.map { it.id }.toSet(),
        )
    }

    @Test
    fun `贴图引用全部落在 assets 树内（坑25 对照目录树，含复合层）`() {
        val presets = VfxCatalog.parse(sidecarText())
        val onDisk = File("src/main/assets/vfx/textures").listFiles()!!.map { it.name }.toSet()
        val referenced = mutableSetOf<String>()
        for (p in presets) {
            val all = listOf(p) + p.layers.orEmpty()
            for (spec in all) {
                val tex = spec.texture as? com.neethu.corelib.vfx.VfxTexture.Asset
                assertNotNull("preset ${spec.id} 应引用 assets 贴图", tex)
                val rel = tex!!.path.removePrefix("vfx/")
                assertTrue("贴图存在: ${tex.path}", assetFile("vfx/$rel").isFile)
                referenced += File(rel).name
            }
        }
        assertEquals("生成器产出的贴图全部被引用（含旧 6 张中的存活者 + 3 张新贴图）", onDisk, referenced)
    }

    @Test
    fun `配方语义字段映射正确（画质升级版）`() {
        val byId = VfxCatalog.parse(sidecarText()).associateBy { it.id }

        // fire：复合层（火苗/辉光/火星/烟）+ 闪烁点灯；火苗层 8×8 flipbook
        val fire = byId.getValue("fire")
        assertEquals(VfxBlending.ADDITIVE, fire.blending)
        assertEquals(4, fire.layers!!.size)
        assertEquals(1, fire.lights.size)
        assertEquals(com.neethu.corelib.vfx.StageLightType.POINT, fire.lights[0].type)
        assertTrue(fire.lights[0].flickerStrength > 0f)
        val flame = fire.layers.orEmpty().first { it.id == "fire_flame" }
        assertEquals(EmitterShape.CONE, flame.emitter.shape)
        assertEquals(0.3f, flame.emitter.radialFraction)
        assertEquals(8, flame.flipbook!!.columns)
        assertEquals(8, flame.flipbook!!.rows)
        assertEquals(64, flame.flipbook!!.totalFrames)

        // rain：落地碰撞行为
        val rain = byId.getValue("rain")
        assertEquals(VfxBlending.TRANSPARENT, rain.blending)
        assertEquals(Overflow.RECYCLE_OLDEST, rain.overflow)
        assertEquals(7f, rain.stretchByVelocity)
        val ground = rain.behaviors.filterIsInstance<BehaviorParams.GroundCollision>()
        assertEquals(1, ground.size)
        assertEquals(0f, ground[0].groundY)

        // snow：复合层（远/近），近景 CROSS 翻滚 + 双层风场
        val snow = byId.getValue("snow")
        assertEquals(2, snow.layers!!.size)
        val near = snow.layers.orEmpty().first { it.id == "snow_near" }
        assertEquals(com.neethu.corelib.vfx.VfxRenderMode.CROSS, near.renderMode)
        assertTrue(near.tumbleMax > 0f)
        assertNotNull(snow.layers.orEmpty()[0].wind)
        assertNotNull(near.wind)

        // petals：CROSS + 风场 + 落地
        val petals = byId.getValue("petals")
        assertEquals(com.neethu.corelib.vfx.VfxRenderMode.CROSS, petals.renderMode)
        assertNotNull(petals.wind)
        assertTrue(petals.behaviors.any { it is BehaviorParams.GroundCollision })

        // confetti：湍流已改加速度量纲（帧率无关），palette/spin 保留
        val confetti = byId.getValue("confetti")
        assertEquals(6, confetti.palette!!.size)
        assertTrue(confetti.spinMax != 0f)
        assertTrue(confetti.behaviors.filterIsInstance<BehaviorParams.Turbulence>()[0].strength > 1f)

        // 舞台灯组：3 束扫摆 SPOT / 单束追光
        val stage = byId.getValue("stage_lights")
        assertEquals(3, stage.lights.size)
        assertTrue(stage.lights.all { it.type == com.neethu.corelib.vfx.StageLightType.SPOT })
        assertTrue(stage.lights[0].sweepSpeedDegPerSec != 0f)
        val spot = byId.getValue("spotlight")
        assertEquals(1, spot.lights.size)
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
    fun `内置常量表与 sidecar 同源（10 配方防漂移）`() {
        assertEquals(
            "内置 VfxPresets 应与 sidecar 配方集一致（drift = 两侧都改）",
            VfxCatalog.parse(sidecarText()).map { it.id }.toSet(),
            VfxPresets.all.keys,
        )
    }
    // ── P2 贴花 ──────────────────────────────────────────────────────────

    @Test
    fun `decal 条目解析成 DecalParams 且脏形状丢弃`() {
        val text = """
            {"presets":[
              {"id":"a","decals":[{"shape":"ground_circle","texture":"textures/magic_circle.png",
                "radius":1.4,"spinSpeed":42.0,"color":[0.1,0.2,0.3,1.0],"rim":2.0}]},
              {"id":"b","decals":[{"shape":"dome","radius":0.8,"rim":3.0}]},
              {"id":"c","decals":[{"shape":"banana"}]}
            ]}
        """.trimIndent()
        val (presets, errors) = parseWithErrors(text)
        // 脏形状 decal 被丢弃（id=c 降级 decals 空表，与 lights 软钳一致）
        assertEquals("解析诊断: $errors", 3, presets.size)
        assertTrue("脏形状丢弃", presets.first { it.id == "c" }.decals.isEmpty())
        val ground = presets.first { it.id == "a" }.decals.single()
        assertEquals(com.neethu.corelib.vfx.DecalShape.GROUND_CIRCLE, ground.shape)
        assertEquals(1.4f, ground.radiusMeters, 1e-6f)
        assertEquals(42f, ground.spinSpeedDegPerSec, 1e-4f)
        assertEquals(0.1f, ground.color.r, 1e-6f)
        val dome = presets.first { it.id == "b" }.decals.single()
        assertEquals(com.neethu.corelib.vfx.DecalShape.DOME, dome.shape)
        assertEquals(3.0f, dome.rimStrength, 1e-6f)
    }

    @Test
    fun `decal 超上限降级空表且 radius 钳制`() {
        val many = (1..3).joinToString(",") {
            """{"id":"d$it","decals":[{"shape":"dome"},{"shape":"dome"},{"shape":"dome"}]}"""
        }
        val presets = VfxCatalog.parse("""{"presets":[$many]}""")
        // 与 lights 同款软钳：条目保留、decals 清空（MAX_DECALS=2）
        assertEquals(3, presets.size)
        assertTrue(presets.all { it.decals.isEmpty() })
        val clamped = VfxCatalog.parse(
            """{"presets":[{"id":"big","decals":[{"shape":"dome","radius":99.0}]}]}""",
        ).single().decals.single()
        assertTrue(clamped.radiusMeters <= 6f)
    }
}
