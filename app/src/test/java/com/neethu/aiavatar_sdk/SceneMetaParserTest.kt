package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 场景对位 meta 解析（自由走动 P0，docs/locomotion-feasibility.md §7.1）：
 * ai_cmd scene_offset/scene_rot 现场标定 → 回显 JSON 抄进
 * assets/scene/<场景名>.meta.json → 加载时自动套用。
 */
class SceneMetaParserTest {

    @Test
    fun `全字段解析`() {
        val p = parseSceneMeta(
            """
            {"offsetX": 0.5, "offsetY": -1.02, "offsetZ": -0.25, "yawDeg": 180,
             "bounds": {"minX": -3, "maxX": 3, "minZ": -6, "maxZ": -1}}
            """.trimIndent()
        )!!
        assertEquals(0.5f, p.offsetX, 1e-5f)
        assertEquals(-1.02f, p.offsetY, 1e-5f)
        assertEquals(-0.25f, p.offsetZ, 1e-5f)
        assertEquals(180f, p.yawDeg, 1e-5f)
        val b = p.bounds!!
        assertEquals(-3f, b.minX, 1e-5f)
        assertEquals(3f, b.maxX, 1e-5f)
        assertEquals(-6f, b.minZ, 1e-5f)
        assertEquals(-1f, b.maxZ, 1e-5f)
    }

    @Test
    fun `缺省字段落默认值_无 bounds 则不 clamp`() {
        val p = parseSceneMeta("{}")!!
        assertEquals(0f, p.offsetX, 1e-5f)
        assertEquals(0f, p.offsetY, 1e-5f)
        assertEquals(0f, p.offsetZ, 1e-5f)
        assertEquals(0f, p.yawDeg, 1e-5f)
        assertNull(p.bounds)
    }

    @Test
    fun `坏 json 返回 null_加载侧按无 meta 处理`() {
        assertNull(parseSceneMeta("not json {"))
        assertNull(parseSceneMeta(""))
    }

    @Test
    fun `meta 文件名约定_scene 目录下同名换后缀`() {
        assertEquals("scene/home.meta.json", sceneMetaAssetName("home.glb"))
        assertEquals("scene/office.meta.json", sceneMetaAssetName("office.glb"))
    }
}
