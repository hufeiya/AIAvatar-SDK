package com.neethu.aiavatar_sdk

import com.neethu.orchestrator.activity.ActivityCatalog
import com.neethu.orchestrator.activity.PropRef
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活动目录资产树一致性锁（P1 场景层，docs/prop-system-feasibility.md §5/§6.2）：
 * activities.json 的每条 P1 活动——动画落在内置库、props 落在配饰目录、
 * anchor（含世界摆件锚点）落在某个场景的交互点表、世界摆件 mount 解析
 * 正确;FSM 作息表 activityWeights 覆盖全部出现的 category。工作目录 = app/
 * （同 [multiavatar.AccessoryCatalogAssetsTest] 惯例）。
 */
class ActivitiesAssetsTest {

    private fun assetFile(rel: String) = File("src/main/assets/$rel")

    private fun catalog(): ActivityCatalog =
        ActivityCatalog.parse(assetFile("activities/activities.json").readText(Charsets.UTF_8))

    /** 全部场景交互点表（scene 目录下各 `<场景>.interactions.json`）的锚点 id 集。 */
    private fun sceneAnchorIds(): Set<String> {
        val dir = File("src/main/assets/scene")
        val ids = mutableSetOf<String>()
        for (f in dir.listFiles { _, n -> n.endsWith(".interactions.json") }.orEmpty()) {
            val root = Json.parseToJsonElement(f.readText(Charsets.UTF_8)).jsonObject
            root["anchors"]?.jsonArray?.forEach { el ->
                val o = el.jsonObject
                ids += o["id"]?.jsonPrimitive?.content?.lowercase() ?: return@forEach
            }
        }
        return ids
    }

    /** 配饰目录 id 集（sidecar 正本）。 */
    private fun accessoryIds(): Set<String> {
        val text = assetFile("accessories/accessory.json").readText(Charsets.UTF_8)
        val root = Json.parseToJsonElement(text).jsonObject
        return root["accessories"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
    }

    /** P1 场景层上线的新活动 id（§5 全部 P1 行;reading/tablet_read 是 P0 条目升级）。 */
    private val p1Ids = setOf(
        "bed_phone", "bed_drink", "drink_water", "window_hot_drink", "sleep_mask",
        "milk_tea", "pour_drinks", "toast_cheer", "file_check", "bag_search",
        "guitar_sing", "headphones_music", "yoga", "dumbbell_lift", "give_flowers",
        "give_gift", "sparkler_wave", "balloon_hold", "umbrella_rain", "gourd_sip",
        "cast_spell", "meditate", "game_time",
    )

    /** P2 节日活动包（§5-H 五个）+ 古风包（§5-G P2 行四个;饮酒葫芦 P1 已有）。 */
    private val p2Ids = setOf(
        "christmas_gift", "halloween_pumpkin", "newyear_lantern", "birthday_cake", "mooncake_midautumn",
        "sword_play", "guzheng_play", "fan_play", "oil_umbrella_walk",
    )

    /** 节日包标签取值域（HolidayCalendar 常量的镜像;解析侧只认小写）。 */
    private val knownHolidays = setOf(
        "christmas", "halloween", "spring_festival", "mid_autumn", "birthday",
    )

    @Test
    fun `catalog carries p0 16 plus p1 23 plus p2 9 activities`() {
        val entries = catalog().entries
        assertEquals("16 P0 + 23 P1 场景层 + 9 P2 节日/古风", 48, entries.size)
        val ids = entries.map { it.id }.toSet()
        assertTrue("缺 P1 活动: ${p1Ids - ids}", ids.containsAll(p1Ids))
        assertTrue("缺 P2 活动: ${p2Ids - ids}", ids.containsAll(p2Ids))
        // P0 旗舰升级:reading 换单本书 + 坐姿阅读剪辑;tablet_read 换真平板
        val reading = catalog().find("reading")!!
        assertEquals("06_坐姿/Elbows On Legs Leaning Forward.vrma", reading.animation)
        assertEquals(listOf(PropRef("book")), reading.props)
        val tablet = catalog().find("tablet_read")!!
        assertEquals(listOf(PropRef("tablet")), tablet.props)
    }

    @Test
    fun `every activity animation exists in the built-in library`() {
        catalog().entries.forEach { e ->
            assertTrue(
                "活动 '${e.id}' 动画不存在于内置库: ${e.animation}",
                assetFile("animations/${e.animation}").isFile,
            )
        }
    }

    @Test
    fun `every prop id resolves to the accessory catalog`() {
        val ids = accessoryIds()
        catalog().entries.forEach { e ->
            e.props.forEach { p ->
                assertTrue("活动 '${e.id}' 引用目录外道具 '${p.id}'", ids.contains(p.id))
            }
        }
    }

    @Test
    fun `every anchor - activity and world prop - exists in some scene interactions table`() {
        val anchors = sceneAnchorIds()
        catalog().entries.forEach { e ->
            e.anchor?.let {
                assertTrue("活动 '${e.id}' 锚点 '$it' 不在任何场景交互点表", anchors.contains(it))
            }
            e.props.filter { it.isWorld }.forEach { p ->
                val a = p.anchor
                assertTrue("世界摆件 '${p.id}' 缺锚点（§6.2 mount:world 必须带 anchor）", !a.isNullOrBlank())
                assertTrue("世界摆件 '${p.id}' 锚点 '$a' 不在任何场景交互点表", anchors.contains(a))
            }
        }
    }

    @Test
    fun `world props parse as mount world - yoga mat on floor and tea set on desk`() {
        val yoga = catalog().find("yoga")!!
        assertEquals(listOf(PropRef("yogaMat", "world", "yoga_spot")), yoga.props)
        assertTrue(yoga.props.single().isWorld)
        val pour = catalog().find("pour_drinks")!!
        val world = pour.props.filter { it.isWorld }
        assertEquals(listOf(PropRef("teaSet", "world", "desk_top")), world)
        // 骨骼道具与世界摆件混排:倒饮料待客 = 水壶在手 + 茶具组上桌
        assertEquals(2, pour.props.size)
        assertTrue(pour.props.any { !it.isWorld && it.id == "kettle" })
    }

    @Test
    fun `p1 entries carry hourRange and says - loop entries carry minDwellMs`() {
        catalog().entries.filter { it.id in p1Ids }.forEach { e ->
            assertNotNull("P1 活动 '${e.id}' 缺 hourRange", e.hourRange)
            assertTrue("P1 活动 '${e.id}' 缺 says", e.says.isNotEmpty())
            if (e.loop) {
                assertTrue("P1 loop 活动 '${e.id}' 缺 minDwellMs", (e.minDwellMs ?: 0) > 0)
            }
        }
        catalog().entries.filter { it.id in p2Ids }.forEach { e ->
            assertNotNull("P2 活动 '${e.id}' 缺 hourRange", e.hourRange)
            assertTrue("P2 活动 '${e.id}' 缺 says", e.says.isNotEmpty())
            if (e.loop) {
                assertTrue("P2 loop 活动 '${e.id}' 缺 minDwellMs", (e.minDwellMs ?: 0) > 0)
            }
        }
    }

    // ── P2 节日活动包 + 古风包（docs/prop-system-feasibility.md §5-G/H）────────

    @Test
    fun `festival pack entries carry known holiday tags and preset vfx`() {
        val expected = mapOf(
            "christmas_gift" to "christmas",
            "halloween_pumpkin" to "halloween",
            "newyear_lantern" to "spring_festival",
            "birthday_cake" to "birthday",
            "mooncake_midautumn" to "mid_autumn",
        )
        expected.forEach { (id, holiday) ->
            val e = catalog().find(id)!!
            assertEquals("活动 '$id' holiday 标签不符", holiday, e.holiday)
            assertTrue("活动 '$id' 缺 VFX 组合（§5-H 台词池×VFX 预设）", !e.vfx.isNullOrBlank())
            assertTrue("活动 '$id' 缺台词池", e.says.isNotEmpty())
        }
        // 古风四条不带 holiday（全年可选,不参与日历加权）
        p2Ids.filter { it !in expected }.forEach { id ->
            assertNull("古风活动 '$id' 不应带 holiday 标签", catalog().find(id)!!.holiday)
        }
        catalog().entries.mapNotNull { it.holiday }.toSet().forEach { tag ->
            assertTrue("未知节日标签 '$tag'（HolidayCalendar 取值域外）", tag in knownHolidays)
        }
    }

    @Test
    fun `p2 two-prop activities use wear mounts - festival anchors stay in walkable scenes`() {
        // 双道具/第二道具活动走 {"mount":"wear"}（绑定表 animation→accessory 1:1 装不下）
        assertEquals(
            listOf(PropRef("giftBox"), PropRef("santaHat", "wear", null)),
            catalog().find("christmas_gift")!!.props,
        )
        val sword = catalog().find("sword_play")!!.props
        assertTrue(sword.all { it.isWear })
        assertEquals(setOf("sword", "scabbard"), sword.map { it.id }.toSet())
        assertTrue(catalog().find("guzheng_play")!!.props.single().isWear)
        // 节日五条的锚点落在交互点表（FSM join 的前提）;古风双锚点适配仙居
        val anchors = sceneAnchorIds()
        p2Ids.forEach { id ->
            val e = catalog().find(id)!!
            if (id != "fan_play" && id != "oil_umbrella_walk") {
                assertNotNull("P2 活动 '$id' 缺锚点（FSM 节日加权的前提）", e.anchor)
                assertTrue("P2 活动 '$id' 锚点不在任何场景表", e.anchor in anchors)
            }
        }
    }

    @Test
    fun `new categories are covered by fsm schedule activityWeights`() {
        val used = catalog().entries.map { it.category }.toSet()
        val newOnes = setOf("sport", "social", "fantasy", "festival")
        assertTrue("P1 场景层应引入新 category: ${newOnes - used}", used.containsAll(newOnes))
        val text = assetFile("autonomy/autonomy.schedule.json").readText(Charsets.UTF_8)
        val root = Json.parseToJsonElement(text).jsonObject
        // 收集基线 + 全部小时规则的 activityWeights 键
        val keys = buildSet {
            addAll(root["activityWeights"]?.jsonObject?.keys ?: emptySet())
            root["hours"]!!.jsonArray.forEach { rule ->
                rule.jsonObject["activityWeights"]?.jsonObject?.keys?.let { addAll(it) }
            }
        }
        assertTrue("作息表 activityWeights 未覆盖新 category: ${newOnes - keys}", keys.containsAll(newOnes))
    }
}
