package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预置卡导入去重映射与人物卡提示词覆盖：两者都是纯 JSON 逻辑（SharedPreferences
 * 只在 DemoUiState 薄封装一层），点选预置卡/编辑提示词的用户路径靠这里的约定。
 */
class PresetCardImportTest {

    @Test
    fun `unmapped asset - needs import and keeps map empty`() {
        val (existing, map) = PresetCardImport.resolve(null, "cards/preset_xiaoman.png", setOf())
        assertNull(existing)
        assertEquals("{}", map)
    }

    @Test
    fun `mapped and still on disk - reuse without reimport`() {
        val map = PresetCardImport.record(null, "cards/preset_xiaoman.png", "林小满_ab12cd34.png")
        val (existing, newMap) = PresetCardImport.resolve(map, "cards/preset_xiaoman.png", setOf("林小满_ab12cd34.png"))
        assertEquals("林小满_ab12cd34.png", existing)
        assertEquals(map, newMap)
    }

    @Test
    fun `mapped but deleted from disk - stale entry dropped and reimport requested`() {
        val map = PresetCardImport.record(null, "cards/preset_xiaoman.png", "林小满_ab12cd34.png")
        val (existing, newMap) = PresetCardImport.resolve(map, "cards/preset_xiaoman.png", setOf("别的卡.png"))
        assertNull(existing)
        assertEquals("{}", newMap)
    }

    @Test
    fun `record and reverse lookup round trip`() {
        val map = PresetCardImport.record(null, "cards/preset_suisui.png", "岁岁_99aa99aa.png")
        assertEquals("cards/preset_suisui.png", PresetCardImport.reverseGet(map, "岁岁_99aa99aa.png"))
        assertNull(PresetCardImport.reverseGet(map, "手工导入.png"))
        assertNull(PresetCardImport.reverseGet(map, null))
    }

    @Test
    fun `corrupt map json - treated as empty, resolve still works`() {
        val (existing, map) = PresetCardImport.resolve("not-json{", "cards/a.png", setOf())
        assertNull(existing)
        assertEquals("{}", map)
    }
}

class CardPromptOverridesTest {

    @Test
    fun `no override json - get returns null`() {
        assertNull(CardPromptOverrides.get(null, "a.png"))
        assertNull(CardPromptOverrides.get("{}", "a.png"))
        assertNull(CardPromptOverrides.get("corrupt{", "a.png"))
    }

    @Test
    fun `set then get round trip`() {
        val json = CardPromptOverrides.set(null, "a.png", "自定义人设文本")
        assertEquals("自定义人设文本", CardPromptOverrides.get(json, "a.png"))
        assertNull(CardPromptOverrides.get(json, "b.png"))
    }

    @Test
    fun `per-card overrides stay independent`() {
        var json = CardPromptOverrides.set(null, "a.png", "A 的人设")
        json = CardPromptOverrides.set(json, "b.png", "B 的人设")
        assertEquals("A 的人设", CardPromptOverrides.get(json, "a.png"))
        assertEquals("B 的人设", CardPromptOverrides.get(json, "b.png"))
    }

    @Test
    fun `set blank clears the override - reset to default`() {
        var json = CardPromptOverrides.set(null, "a.png", "旧编辑")
        json = CardPromptOverrides.set(json, "a.png", null)
        assertNull(CardPromptOverrides.get(json, "a.png"))
        json = CardPromptOverrides.set(json, "a.png", "  ")
        assertNull(CardPromptOverrides.get(json, "a.png"))
    }

    @Test
    fun `corrupt existing json - set rebuilds a valid map`() {
        val json = CardPromptOverrides.set("corrupt{", "a.png", "新文本")
        assertEquals("新文本", CardPromptOverrides.get(json, "a.png"))
        assertFalse(json.contains("corrupt"))
        assertTrue(json.contains("a.png"))
    }
}
