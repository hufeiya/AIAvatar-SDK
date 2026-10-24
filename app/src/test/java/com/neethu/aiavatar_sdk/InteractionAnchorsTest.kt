package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 场景交互点表 + 场景 sidecar 资产名约定（坑 25 教训：assets 路径拼接类
 * 函数的断言必须写全 `scene/xxx` 形态，否则 FileNotFound 被 runCatching 吞
 * 掉、meta 静默失效一整个周期）。
 */
class InteractionAnchorsTest {

    private val sampleJson = """
    {
      "schemaVersion": 1,
      "scene": "dream_bedroom.glb",
      "anchors": [
        {
          "id": "bed_sit", "label": "床沿坐姿接触点", "labelEn": "bed sit",
          "kind": "sit", "x": -1.4, "y": -0.30235, "z": -4.8, "yawDeg": 90,
          "approach": "bed_approach", "hipsLift": 0.09,
          "sitInClip": "animations/06_坐姿/Standing To Sitting Transition.vrma",
          "sitLoopClip": "animations/06_坐姿/Sit With Fidgeting Feet.vrma",
          "standUpClip": "animations/06_坐姿/Standing Up From Seated Position.vrma"
        },
        {
          "id": "window_view", "label": "窗前观景", "labelEn": "window view",
          "kind": "stand", "x": 3.35, "y": -1.0, "z": -5.25, "yawDeg": 90,
          "look": [5.5, 1.2, -5.25]
        },
        {
          "broken": true
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `parses contact and stand anchors with clips`() {
        val anchors = parseInteractionAnchors(sampleJson)
        assertEquals(2, anchors.size) // 坏条目丢弃
        val bedSit = anchors[0]
        assertEquals("bed_sit", bedSit.id)
        assertEquals("sit", bedSit.kind)
        assertTrue(bedSit.isContactPose)
        assertEquals("bed_approach", bedSit.approach)
        assertEquals(-0.30235f, bedSit.y, 1e-5f)
        assertEquals(90f, bedSit.yawDeg)
        assertEquals(0.09f, bedSit.hipsLift!!, 1e-5f)
        assertEquals("animations/06_坐姿/Standing To Sitting Transition.vrma", bedSit.sitInClip)
        val window = anchors[1]
        assertEquals("window_view", window.id)
        assertFalse(window.isContactPose)
        assertEquals(listOf(5.5f, 1.2f, -5.25f), window.look)
    }

    @Test
    fun `id is forced lowercase for the tool whitelist`() {
        val upper = sampleJson.replace("\"bed_sit\"", "\"BED_SIT\"")
        val anchors = parseInteractionAnchors(upper)
        assertEquals("bed_sit", anchors[0].id)
    }

    @Test
    fun `garbage json yields an empty table not a crash`() {
        assertTrue(parseInteractionAnchors("not json at all").isEmpty())
        assertTrue(parseInteractionAnchors("""{"anchors": null}""").isEmpty())
    }

    @Test
    fun `sidecar asset names keep the scene directory prefix`() {
        assertEquals("scene/dream_bedroom.meta.json", sceneMetaAssetName("dream_bedroom.glb"))
        assertEquals("scene/dream_bedroom.anchors.json", sceneAnchorsAssetName("dream_bedroom.glb"))
        assertEquals("scene/dream_bedroom.interactions.json", sceneInteractionsAssetName("dream_bedroom.glb"))
        assertEquals("scene/home.meta.json", sceneMetaAssetName("home.glb"))
    }

    @Test
    fun `spawn parses from meta json`() {
        val spawn = parseSceneSpawn(
            """
            {"offsetX": 0, "offsetY": -1, "offsetZ": -4, "yawDeg": 0,
             "spawn": {"x": -0.1, "y": -0.97, "z": -3.35, "yawDeg": 180}}
            """.trimIndent(),
        )
        assertNotNull(spawn)
        assertEquals(-0.1f, spawn!!.x, 1e-5f)
        assertEquals(-0.97f, spawn.y, 1e-5f)
        assertEquals(-3.35f, spawn.z, 1e-5f)
        assertEquals(180f, spawn.yawDeg)
    }

    @Test
    fun `meta without spawn yields null not zero`() {
        val noSpawn = parseSceneSpawn("""{"offsetX": 0}""")
        assertEquals(null, noSpawn)
        // 真机 home.meta.json 无 spawn 字段 → 不落出生点（历史行为）
        val homeMeta = parseSceneSpawn(
            """{"offsetX": 0.0, "offsetY": 0.0, "offsetZ": 0.0, "yawDeg": 0.0,
                "bounds": {"minX": -2.9, "maxX": 2.3, "minZ": -5.8, "maxZ": -2.1}}""",
        )
        assertEquals(null, homeMeta)
    }

    private fun assertFalse(b: Boolean) = org.junit.Assert.assertFalse(b)
}
