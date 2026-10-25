package com.neethu.aiavatar_sdk.multiavatar

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配饰/道具资产树一致性锁（P1 道具系统 §6.1/§6.4，坑 25 教训：资产引用测试
 * 必须落到真实文件）：accessory.json 目录 ↔ accessories/ 文件 ↔ 模板表、
 * bindings.json ↔ animations/ 库 ↔ 目录 id，四向对拍。工作目录 = app/
 * （Gradle JVM 测试惯例，同 [VfxCatalogTest]）。
 */
class AccessoryCatalogAssetsTest {

    private fun assetFile(rel: String) = File("src/main/assets/$rel")

    private val json = Json { ignoreUnknownKeys = true }

    private fun sidecarEntries(): List<AccessoryCatalog.SidecarEntry> =
        json.decodeFromString<SidecarFile>(
            assetFile("accessories/accessory.json").readText(Charsets.UTF_8)
        ).accessories

    @Serializable
    private data class SidecarFile(val accessories: List<AccessoryCatalog.SidecarEntry>)

    private fun bindings(): List<AccessoryCatalog.BindingEntry> =
        json.decodeFromString<List<AccessoryCatalog.BindingEntry>>(
            assetFile("accessories/bindings.json").readText(Charsets.UTF_8)
        )

    @Test
    fun `catalog has 31 entries - 11 legacy + 20 P1 props`() {
        val entries = sidecarEntries()
        assertEquals(31, entries.size)
        val withProfile = entries.filter { it.offsetProfile != null }
        assertEquals("P1 道具批量入库 20 件", 20, withProfile.size)
    }

    @Test
    fun `ids unique and glb files exist`() {
        val entries = sidecarEntries()
        val ids = entries.map { it.id }
        assertEquals("目录 id 重复", ids.size, ids.toSet().size)
        entries.forEach { e ->
            assertTrue("accessories/${e.file} 不存在", assetFile("accessories/${e.file}").isFile)
        }
    }

    @Test
    fun `legacy 11 keep explicit offsets - P1 entries delegate to profiles`() {
        val entries = sidecarEntries()
        val legacyIds = setOf(
            "phone", "radio", "bear", "books", "laptop", "pillow",
            "speaker", "trashcan", "box", "microphone", "lightstick",
        )
        entries.filter { it.id in legacyIds }.forEach { e ->
            assertEquals("旧条目不应动（改动最小化）: ${e.id}", null, e.offsetProfile)
            assertTrue("旧条目保留显式偏移: ${e.id}", e.position != null)
        }
        entries.filter { it.id !in legacyIds }.forEach { e ->
            assertTrue("P1 新条目必须引用模板: ${e.id}", !e.offsetProfile.isNullOrBlank())
        }
    }

    @Test
    fun `every offsetProfile references a known template`() {
        sidecarEntries().forEach { e ->
            e.offsetProfile?.let { p ->
                assertTrue(
                    "sidecar 引用未知模板 '$p' (${e.id});合法值: ${AccessoryOffsetProfiles.byName.keys}",
                    AccessoryOffsetProfiles.byName.containsKey(p),
                )
            }
        }
    }

    @Test
    fun `all 8 templates are referenced by at least one prop`() {
        val used = sidecarEntries().mapNotNull { it.offsetProfile }.toSet()
        assertEquals(
            "8 模板应全部被引用（~8 模板×2 模型=16 次校准的基数）",
            AccessoryOffsetProfiles.byName.keys,
            used,
        )
    }

    @Test
    fun `bindings animations are unique and exist in the animation library`() {
        val all = bindings()
        val keys = all.map { it.animation }
        assertEquals(
            "同一动画被绑定到多个道具（animation→accessory 为 1:1）: " +
                keys.groupBy { it }.filterValues { it.size > 1 }.keys,
            keys.size,
            keys.toSet().size,
        )
        all.forEach { b ->
            assertTrue(
                "绑定动画不存在于内置库: ${b.animation}",
                assetFile("animations/${b.animation}").isFile,
            )
        }
    }

    @Test
    fun `bindings accessories all resolve to catalog ids`() {
        val ids = sidecarEntries().map { it.id }.toSet()
        bindings().forEach { b ->
            assertTrue(
                "绑定引用了目录外的道具 id '${b.accessory}'",
                ids.contains(b.accessory),
            )
        }
    }

    @Test
    fun `p1 fallback bindings cover the doc-named scenes`() {
        val byAnimation = bindings().associate { it.animation.substringAfterLast('/') to it.accessory }
        // 场景表点名/文档兜底的第一波道具绑定（缺动画的按万能手持近似）
        assertEquals("book", byAnimation["Elbows On Legs Leaning Forward.vrma"])
        assertEquals("phone", byAnimation["Laying On A Bed Idle.vrma"])
        assertEquals("pen", byAnimation["Male Writing While Seated.vrma"])
        assertEquals("flowers", byAnimation["Taking An Item And Examining It.vrma"])
        assertEquals("giftBox", byAnimation["Pick Up Object And Examine.vrma"])
        assertEquals("guitar", byAnimation["Playing A Guitar.vrma"])
        assertEquals("staff", byAnimation["Pointing With Arm Bent.vrma"])
        assertEquals("gamepad", byAnimation["Sitting To Typing.vrma"])
    }
}
