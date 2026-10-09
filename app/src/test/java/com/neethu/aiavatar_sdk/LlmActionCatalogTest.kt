package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LLM 动作目录生成（buildLlmActionCatalog）的纯函数单测：英文名转小写下划线
 * tag；纯中文名按 CHINESE_NAME_TAGS 别名进目录（闪身步/浪子踢球），未登记的
 * 中文名照旧跳过；别名 tag 与英文名 tag 同走去重。
 */
class LlmActionCatalogTest {

    // ── 英文名：sanitize 生成 tag（存量行为回归） ─────────────────────────

    @Test
    fun `english names get sanitized tags grouped by category rank`() {
        val entries = buildLlmActionCatalog(
            listOf(
                "08_舞蹈与表演/Breakdance Flair.vrma",
                "03_打招呼与礼仪/Waving Hand.vrma",
            ),
        )
        // 分类价值排序：03 打招呼在 08 舞蹈之前；同分类内按 tag 字典序
        assertEquals(listOf("waving_hand", "breakdance_flair"), entries.map { it.tag })
        assertEquals("Waving Hand", entries[0].label)
        assertEquals("03_打招呼与礼仪", entries[0].category)
        assertEquals("animations/03_打招呼与礼仪/Waving Hand.vrma", entries[0].assetPath)
    }

    // ── 中文名：别名表放行 ────────────────────────────────────────────────

    @Test
    fun `registered chinese names enter catalog via alias tags`() {
        val entries = buildLlmActionCatalog(
            listOf(
                "08_舞蹈与表演/闪身步.vrma",
                "08_舞蹈与表演/浪子踢球.vrma",
            ),
        )
        assertEquals(listOf("ball_kick", "dodge_step"), entries.map { it.tag })
        // 显示名保持中文原文件名（面板/日志可读），资产路径原样传给加载器
        assertEquals(listOf("浪子踢球", "闪身步"), entries.map { it.label })
        assertEquals("animations/08_舞蹈与表演/闪身步.vrma", entries[1].assetPath)
    }

    @Test
    fun `unregistered chinese names are still skipped`() {
        val entries = buildLlmActionCatalog(
            listOf("08_舞蹈与表演/无名招式.vrma", "08_舞蹈与表演/闪身步.vrma"),
        )
        assertEquals(listOf("dodge_step"), entries.map { it.tag })
    }

    @Test
    fun `external chinese file uses same alias with file path`() {
        val entries = buildLlmActionCatalog(
            emptyList(),
            externalAbsolutePaths = listOf("/sdcard/VRMA_Selected_Categorized/08_舞蹈与表演/浪子踢球.vrma"),
        )
        assertEquals(1, entries.size)
        assertEquals("ball_kick", entries[0].tag)
        assertEquals("外置库", entries[0].category)
        assertEquals("/sdcard/VRMA_Selected_Categorized/08_舞蹈与表演/浪子踢球.vrma", entries[0].filePath)
        assertTrue(entries[0].assetPath == null)
    }

    // ── 去重：别名 tag 撞上英文名 tag 时先到先得 ──────────────────────────

    @Test
    fun `alias tag deduplicates against identical english tag`() {
        val entries = buildLlmActionCatalog(
            listOf(
                "08_舞蹈与表演/Dodge Step.vrma",
                "08_舞蹈与表演/闪身步.vrma",
            ),
        )
        assertEquals(1, entries.size)
        assertEquals("Dodge Step", entries[0].label)
    }
}
