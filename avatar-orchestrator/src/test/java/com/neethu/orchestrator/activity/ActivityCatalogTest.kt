package com.neethu.orchestrator.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动目录解析（P0 道具系统，docs/prop-system-feasibility.md §2.1）：
 * 全字段解析、必填缺失跳条、脏 JSON 回落 EMPTY、id 唯一化（首条胜出）、
 * hourRange 派生（from 含 to 舍）。纯 JVM。
 */
class ActivityCatalogTest {

    /** 文档 §2.1 的示例行（补 loop/minDwellMs/hourRange/says 全字段）。 */
    private val sample = """
        {
          "schemaVersion": 1,
          "activities": [
            {
              "id": "desk_typing",
              "name": "用电脑",
              "nameEn": "typing at the desk",
              "desc": "坐到书桌前用笔记本电脑打字干活",
              "category": "work",
              "anchor": "desk_seat",
              "animation": "07_日常互动/Sitting At A Computer And Typing.vrma",
              "props": ["laptop"],
              "bindingOffset": { "position": [0, -0.25, 0.05], "note": "坐姿放腿上" },
              "vfx": null,
              "loop": true,
              "minDwellMs": 180000,
              "hourRange": [9, 23],
              "says": ["这个需求今天能写完。", "嗯…这里得重构一下。"]
            },
            {
              "id": "stretch",
              "name": "拉伸",
              "nameEn": "stretch",
              "desc": "站起来伸个懒腰",
              "category": "sport",
              "animation": "07_日常互动/Stretching Arms By Pushing The Elbows.vrma",
              "loop": false
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `全字段解析——schema 示例逐字段对拍`() {
        val catalog = ActivityCatalog.parse(sample)
        assertEquals(2, catalog.entries.size)
        val typing = catalog.find("desk_typing")!!
        assertEquals("用电脑", typing.name)
        assertEquals("typing at the desk", typing.nameEn)
        assertEquals("work", typing.category)
        assertEquals("desk_seat", typing.anchor)
        assertEquals(listOf(PropRef("laptop")), typing.props)
        assertEquals(listOf(0f, -0.25f, 0.05f), typing.bindingOffset?.position)
        assertEquals("坐姿放腿上", typing.bindingOffset?.note)
        assertTrue(typing.loop)
        assertEquals(180_000L, typing.minDwellMs)
        assertEquals(9..22, typing.hourRange)
        assertEquals(2, typing.says.size)
        assertNull(typing.vfx)
        // 缺省字段：loop 默认 true、says/props 空表、anchor/hourRange null
        val stretch = catalog.find("stretch")!!
        assertFalse(stretch.loop)
        assertNull(stretch.anchor)
        assertNull(stretch.hourRange)
        assertTrue(stretch.says.isEmpty())
    }

    @Test
    fun `必填缺失的条目跳过——不炸整表`() {
        val json = """
            { "activities": [
                { "name": "没id" },
                { "id": "no_animation" },
                { "id": "ok", "name": "看书", "animation": "06_坐姿/X.vrma" },
                "not-an-object"
            ] }
        """.trimIndent()
        val catalog = ActivityCatalog.parse(json)
        assertEquals(listOf("ok"), catalog.ids)
    }

    @Test
    fun `重复 id 首条胜出`() {
        val json = """
            { "activities": [
                { "id": "dup", "name": "第一条", "animation": "A.vrma", "category": "work" },
                { "id": "dup", "name": "第二条", "animation": "B.vrma", "category": "play" }
            ] }
        """.trimIndent()
        val catalog = ActivityCatalog.parse(json)
        assertEquals(1, catalog.entries.size)
        assertEquals("第一条", catalog.entries[0].name)
    }

    @Test
    fun `脏 JSON 与缺数组回落 EMPTY`() {
        assertEquals(ActivityCatalog.EMPTY.entries.size, ActivityCatalog.parse("not json {").entries.size)
        assertEquals(0, ActivityCatalog.parse("""{"schemaVersion":1}""").entries.size)
    }

    @Test
    fun `id 查询大小写不敏感（对齐 wearAccessory 白名单口径）`() {
        val catalog = ActivityCatalog.parse(sample)
        assertEquals("用电脑", catalog.find("DESK_TYPING")?.name)
        assertNull(catalog.find("不存在的活动"))
    }

    @Test
    fun `缺省字段回填——nameEn 与 desc 与 category 兜底`() {
        val json = """
            { "activities": [ { "id": "bare", "name": "裸条目", "animation": "A.vrma" } ] }
        """.trimIndent()
        val bare = ActivityCatalog.parse(json).find("bare")!!
        assertEquals("裸条目", bare.nameEn)
        assertEquals("裸条目", bare.desc)
        assertEquals("daily", bare.category)
        assertTrue(bare.loop)
    }

    // ── P1 §6.2：props 对象形态（世界摆件 {id, mount:"world", anchor}）──────

    @Test
    fun `props 混排——字符串与对象两种写法并存`() {
        val json = """
            { "activities": [ {
                "id": "yoga", "name": "瑜伽", "animation": "A.vrma",
                "props": ["kettle", { "id": "yogaMat", "mount": "world", "anchor": "YOGA_SPOT" }]
            } ] }
        """.trimIndent()
        val props = ActivityCatalog.parse(json).find("yoga")!!.props
        assertEquals(2, props.size)
        assertEquals(PropRef("kettle"), props[0])
        assertFalse(props[0].isWorld)
        assertEquals(PropRef("yogaMat", "world", "yoga_spot"), props[1])
        assertTrue(props[1].isWorld)
    }

    @Test
    fun `mount 大小写不敏感且 anchor 归一——非 world 挂载不算世界摆件`() {
        val json = """
            { "activities": [ {
                "id": "x", "name": "x", "animation": "A.vrma",
                "props": [
                    { "id": "mat", "mount": "World", "anchor": "Yoga_Spot" },
                    { "id": "mug", "mount": "bone" }
                ]
            } ] }
        """.trimIndent()
        val props = ActivityCatalog.parse(json).find("x")!!.props
        assertTrue(props[0].isWorld)
        assertEquals("yoga_spot", props[0].anchor)
        assertFalse(props[1].isWorld)
    }

    @Test
    fun `props 脏元素跳过——对象缺 id 或非原始类型不炸条目`() {
        val json = """
            { "activities": [ {
                "id": "x", "name": "x", "animation": "A.vrma",
                "props": [ "ok", { "mount": "world" }, { "id": "" }, 42, true, null ]
            } ] }
        """.trimIndent()
        val props = ActivityCatalog.parse(json).find("x")!!.props
        assertEquals(listOf(PropRef("ok")), props)
    }
}
