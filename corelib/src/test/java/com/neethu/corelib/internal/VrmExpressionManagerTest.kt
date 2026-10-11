package com.neethu.corelib.internal

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VrmExpressionManagerTest {

    private fun bind(mesh: Int, morph: Int, weight: Float) =
        VrmExpressionManager.MorphTargetBind(mesh, morph, weight)

    // ── GLB-JSON builders（parseExpressions 的输入形状） ─────────────────

    private fun vrm0Group(
        preset: String,
        name: String,
        mesh: Int = 0,
        morph: Int = 0,
        weight: Float = 100f,
    ): JsonObject {
        val bind = JsonObject().apply {
            addProperty("mesh", mesh)
            addProperty("index", morph)
            addProperty("weight", weight)
        }
        return JsonObject().apply {
            addProperty("presetName", preset)
            addProperty("name", name)
            add("binds", JsonArray().apply { add(bind) })
        }
    }

    private fun vrm0Json(vararg groups: JsonObject): JsonObject {
        val vrm = JsonObject().apply {
            add("blendShapeMaster", JsonObject().apply {
                add("blendShapeGroups", JsonArray().apply { groups.forEach { add(it) } })
            })
        }
        return JsonObject().apply {
            add("extensions", JsonObject().apply { add("VRM", vrm) })
        }
    }

    private fun vrm1Json(preset: Map<String, Int> = emptyMap(), custom: Map<String, Int> = emptyMap()): JsonObject {
        fun bindsJsonObject(morph: Int) = JsonObject().apply {
            add("morphTargetBinds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("node", 0)
                    addProperty("index", morph)
                    addProperty("weight", 1f)
                })
            })
        }
        val expressions = JsonObject()
        if (preset.isNotEmpty()) {
            expressions.add("preset", JsonObject().apply {
                preset.forEach { (name, morph) -> add(name, bindsJsonObject(morph)) }
            })
        }
        if (custom.isNotEmpty()) {
            expressions.add("custom", JsonObject().apply {
                custom.forEach { (name, morph) -> add(name, bindsJsonObject(morph)) }
            })
        }
        val vrmc = JsonObject().apply { add("expressions", expressions) }
        return JsonObject().apply {
            add("nodes", JsonArray().apply {
                add(JsonObject().apply { addProperty("name", "Face"); addProperty("mesh", 2) })
            })
            add("extensions", JsonObject().apply { add("VRMC_vrm", vrmc) })
        }
    }

    // ── normalizeBindWeights（原有行为回归） ─────────────────────────────

    @Test
    fun `weak binds scale up so the strongest reaches 1`() {
        // SK_Sun 类转换模型：aa 只有 0.5、sad 只有 0.25 的绑定
        val out = VrmExpressionManager.normalizeBindWeights(listOf(bind(0, 49, 0.5f)))
        assertEquals(listOf(bind(0, 49, 1f)), out)

        val out2 = VrmExpressionManager.normalizeBindWeights(
            listOf(bind(0, 49, 0.25f), bind(1, 30, 0.125f)),
        )
        assertEquals(listOf(bind(0, 49, 1f), bind(1, 30, 0.5f)), out2)
    }

    @Test
    fun `relative ratios between binds are preserved`() {
        val out = VrmExpressionManager.normalizeBindWeights(
            listOf(bind(0, 49, 0.3f), bind(0, 27, 0.2f)),
        )
        assertEquals(1f, out[0].weight, 1e-6f)
        assertEquals(0.2f / 0.3f, out[1].weight, 1e-6f)
    }

    @Test
    fun `full weight binds are untouched`() {
        val binds = listOf(bind(0, 39, 1f), bind(1, 5, 0.4f))
        assertEquals(binds, VrmExpressionManager.normalizeBindWeights(binds))
    }

    @Test
    fun `empty and all-zero binds pass through`() {
        assertEquals(0, VrmExpressionManager.normalizeBindWeights(emptyList()).size)
        val zeros = listOf(bind(0, 1, 0f), bind(0, 2, 0f))
        assertEquals(zeros, VrmExpressionManager.normalizeBindWeights(zeros))
    }

    // ── VRM 0.x preset 名归一（4705834820915235427.vrm 说话不动嘴的根因） ──

    @Test
    fun `vrm0 single-letter vowel presets are renamed to vrm1 viseme names`() {
        val json = vrm0Json(
            vrm0Group("a", "A", mesh = 3, morph = 39),
            vrm0Group("i", "I", mesh = 3, morph = 40),
            vrm0Group("u", "U", mesh = 3, morph = 41),
            vrm0Group("e", "E", mesh = 3, morph = 42),
            vrm0Group("o", "O", mesh = 3, morph = 43),
        )
        val parsed = VrmExpressionManager.parseExpressions(json)

        assertEquals(setOf("aa", "ih", "ou", "ee", "oh"), parsed.keys)
        assertEquals(listOf(bind(3, 39, 1f)), parsed.getValue("aa").binds)
        assertEquals(listOf(bind(3, 43, 1f)), parsed.getValue("oh").binds)
    }

    @Test
    fun `vrm0 half-eye presets rename to blinkLeft and blinkRight`() {
        val json = vrm0Json(
            vrm0Group("blink", "Blink", morph = 13),
            vrm0Group("blink_l", "Blink_L", morph = 15),
            vrm0Group("blink_r", "Blink_R", morph = 14),
        )
        val parsed = VrmExpressionManager.parseExpressions(json)

        assertEquals(setOf("blink", "blinkLeft", "blinkRight"), parsed.keys)
        assertEquals(listOf(bind(0, 15, 1f)), parsed.getValue("blinkLeft").binds)
        assertEquals(listOf(bind(0, 14, 1f)), parsed.getValue("blinkRight").binds)
    }

    @Test
    fun `vrm0 presets identical in both specs keep their names`() {
        val json = vrm0Json(
            vrm0Group("neutral", "Neutral", morph = 0),
            vrm0Group("angry", "Angry", morph = 1),
            vrm0Group("fun", "Fun", morph = 2),
            vrm0Group("joy", "Joy", morph = 3),
            vrm0Group("sorrow", "Sorrow", morph = 4),
        )
        val parsed = VrmExpressionManager.parseExpressions(json)

        assertTrue("neutral" in parsed)
        assertTrue("angry" in parsed)
        assertTrue("fun" in parsed)
        assertTrue("joy" in parsed)
        assertTrue("sorrow" in parsed)
    }

    @Test
    fun `vrm0 unknown preset falls back to lowercased group name`() {
        // 4705834820915235427.vrm 的惊讶组：presetName=unknown, name=Surprised
        val json = vrm0Json(vrm0Group("unknown", "Surprised", morph = 5))
        val parsed = VrmExpressionManager.parseExpressions(json)

        assertEquals(setOf("surprised"), parsed.keys)
        assertEquals(listOf(bind(0, 5, 1f)), parsed.getValue("surprised").binds)
    }

    @Test
    fun `vrm0 bind weights are normalized through parsing`() {
        val json = vrm0Json(vrm0Group("a", "A", morph = 39, weight = 50f))
        val parsed = VrmExpressionManager.parseExpressions(json)

        assertEquals(listOf(bind(0, 39, 1f)), parsed.getValue("aa").binds)
    }

    // ── legacy 情绪 preset → canonical 情绪名别名（模型自适应补缺） ────────

    @Test
    fun `legacy emotion presets alias to canonical names when missing`() {
        // 0.x 预设模型：有 joy/sorrow/fun,没有 happy/sad/relaxed
        val json = vrm0Json(
            vrm0Group("joy", "Joy", morph = 3),
            vrm0Group("sorrow", "Sorrow", morph = 4),
            vrm0Group("fun", "Fun", morph = 2),
        )
        val parsed = VrmExpressionManager.parseExpressions(json)

        // canonical 补缺 + 原名保留（表情面板两个名字都可用,绑同一 morph）
        assertEquals(setOf("joy", "happy", "sorrow", "sad", "fun", "relaxed"), parsed.keys)
        assertSame(parsed.getValue("joy"), parsed.getValue("happy"))
        assertSame(parsed.getValue("sorrow"), parsed.getValue("sad"))
        assertSame(parsed.getValue("fun"), parsed.getValue("relaxed"))
    }

    @Test
    fun `canonical emotion already present is never overwritten by the alias`() {
        // 自带名为 happy 的自定义组(preset=unknown) + 标准 joy preset：
        // happy 必须保持自定义组的 binds,joy→happy 别名不得生效
        val json = vrm0Json(
            vrm0Group("unknown", "happy", mesh = 1, morph = 7),
            vrm0Group("joy", "Joy", morph = 3),
        )
        val parsed = VrmExpressionManager.parseExpressions(json)

        assertEquals(listOf(bind(1, 7, 1f)), parsed.getValue("happy").binds)
        assertEquals(listOf(bind(0, 3, 1f)), parsed.getValue("joy").binds)
    }

    // ── VRM 1.0 路径回归（名字一律原样保留） ─────────────────────────────

    @Test
    fun `vrm1 expression names are kept verbatim`() {
        val json = vrm1Json(preset = mapOf("aa" to 5, "blink" to 6), custom = mapOf("jawOpen" to 9))
        val parsed = VrmExpressionManager.parseExpressions(json)

        assertEquals(setOf("aa", "blink", "jawOpen"), parsed.keys)
        // node 0 → nodes[0].mesh = 2
        assertEquals(listOf(bind(2, 5, 1f)), parsed.getValue("aa").binds)
    }

    @Test
    fun `vrm1 branch wins when both extensions are present`() {
        val vrm1 = vrm1Json(preset = mapOf("aa" to 5))
        // 把 0.x 组塞进同一份 JSON 的 extensions 里
        vrm1.getAsJsonObject("extensions").add(
            "VRM",
            JsonObject().apply {
                add("blendShapeMaster", JsonObject().apply {
                    add("blendShapeGroups", JsonArray().apply { add(vrm0Group("a", "A", morph = 39)) })
                })
            },
        )
        val parsed = VrmExpressionManager.parseExpressions(vrm1)

        assertEquals(setOf("aa"), parsed.keys)
        assertEquals(listOf(bind(2, 5, 1f)), parsed.getValue("aa").binds)
        assertFalse(39 in parsed.getValue("aa").binds.map { it.morphTargetIndex })
    }
}
