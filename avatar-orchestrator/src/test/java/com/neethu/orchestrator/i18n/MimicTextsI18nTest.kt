package com.neethu.orchestrator.i18n

import com.neethu.corelib.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模仿我」双语目录的不变量（同 [PromptTextsI18nTest]）：EN 版任何成员都
 * 不得出现中文字符；ZH 版锚点保留；of(lang) 分发正确。
 */
class MimicTextsI18nTest {

    private val cjk = Regex("[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]")

    private fun assertNoCjk(what: String, text: String) {
        val hit = cjk.findAll(text).map { it.value }.distinct().take(5).joinToString("")
        assertTrue("EN $what 含中文字符: $hit", !cjk.containsMatchIn(text))
    }

    @Test
    fun `en texts have no chinese characters`() {
        val en = MimicTexts.of(Lang.EN)
        assertNoCjk("introDirective", en.introDirective())
        assertNoCjk("activeDirective", en.activeDirective())
        assertNoCjk("gonePrompt", en.gonePrompt())
        assertNoCjk("goneHint", en.goneHint())
        assertNoCjk("banterDirective(true)", en.banterDirective(true))
        assertNoCjk("banterDirective(false)", en.banterDirective(false))
    }

    @Test
    fun `zh anchors survive`() {
        val zh = MimicTexts.of(Lang.ZH)
        assertTrue(zh.introDirective().contains("模仿我"))
        assertTrue(zh.introDirective().contains("镜像"))
        assertTrue(zh.gonePrompt().contains("上半身"))
        assertTrue(zh.banterDirective(true).contains("战报"))
        assertFalse(zh.introDirective().contains("[Skill"))
    }

    @Test
    fun `of dispatches by language`() {
        assertEquals(Lang.ZH, MimicTexts.of(Lang.ZH).lang)
        assertEquals(Lang.EN, MimicTexts.of(Lang.EN).lang)
    }
}
