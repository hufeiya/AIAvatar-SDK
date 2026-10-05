package com.neethu.orchestrator.i18n

import com.neethu.corelib.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词双语目录的不变量（多语言支持）：EN 版**任何成员都不得出现中文字符**
 * （用户需求「英文模式下不要出现中文」——身份前言/协议块/视角行/猜拳指令全算）；
 * ZH 版保留既有锚点字符串（SystemPromptAssemblerTest/AvatarSessionViewLineTest
 * 等依赖）。防漂移：新增成员忘翻 EN 时这里立刻红。
 */
class PromptTextsI18nTest {

    private val cjk = Regex("[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]")

    private fun assertNoCjk(what: String, text: String) {
        val hit = cjk.findAll(text).map { it.value }.distinct().take(5).joinToString("")
        assertTrue("EN $what 含中文字符: $hit", !cjk.containsMatchIn(text))
    }

    // ── PromptTexts：EN 全成员无中文 ──────────────────────────────────────────

    @Test
    fun `en identity preamble and protocol reminder have no chinese`() {
        val en = PromptTextsEn
        assertNoCjk("identityPreamble", en.identityPreamble)
        assertNoCjk("protocolReminder", en.protocolReminder)
    }

    @Test
    fun `en view lines have no chinese`() {
        val en = PromptTextsEn
        assertNoCjk("viewLine", en.viewLine("Face Macro MC", "MACRO"))
        assertNoCjk("viewLineFree", en.viewLineFree)
    }

    @Test
    fun `en protocol block has no chinese even with raw zh catalogs`() {
        val en = PromptTextsEn
        // 分类名是 assets 文件夹原名（中文）——EN 目录必须全部翻译掉
        val rawCategories = listOf(
            "03_打招呼与礼仪", "04_交流手势", "05_情绪表达", "01_待机与站姿",
            "07_日常互动", "09_状态过渡", "10_跳跃", "08_舞蹈与表演", "06_坐姿",
            "基础动作", "外置库", "待机",
        )
        val groups = rawCategories.map { it to listOf("wave", "nod") }
        val block = en.protocolBlock(
            cameras = en.cameraTags,
            actionGroups = groups,
            directExpressions = listOf("blinkLeft", "blinkRight", "aa"),
            winkName = "blinkLeft",
        )
        assertNoCjk("protocolBlock", block)
        // 分类确实被翻译（不是原样透传中文）
        assertTrue(block.contains("Greetings & Etiquette"))
        assertTrue(block.contains("External Library"))
        // 中文协议块锚点保持
        val zh = PromptTextsZh.protocolBlock(
            cameras = PromptTextsZh.cameraTags,
            actionGroups = groups,
            directExpressions = listOf("blinkLeft"),
            winkName = "blinkLeft",
        )
        assertTrue(zh.contains("[多模态输出协议"))
    }

    @Test
    fun `en action category falls back to raw for unknown names`() {
        assertEquals("CustomFolder", PromptTextsEn.actionCategory("CustomFolder"))
        assertEquals("基础动作", PromptTextsZh.actionCategory("基础动作"))
    }

    @Test
    fun `zh anchors survive migration`() {
        val zh = PromptTextsZh
        assertTrue(zh.identityPreamble.contains("【身份与任务】"))
        assertTrue(zh.protocolReminder.contains("【协议遵循】"))
        assertTrue(zh.viewLine("面部特写 CU", "CLOSE_UP").startsWith("【当前镜头视角】"))
        assertEquals("你", zh.userAlias)
        assertEquals("you", PromptTextsEn.userAlias)
    }

    @Test
    fun `camera tags stay in sync with assembler companion`() {
        assertEquals(
            com.neethu.orchestrator.card.SystemPromptAssembler.DEFAULT_CAMERA_TAGS,
            PromptTextsZh.cameraTags,
        )
    }

    @Test
    fun `emotion wordlists match in size`() {
        // 词表与 EmotionBlender.defs 键一一对应的不变量由 SystemPromptAssemblerTest
        // 锁定；这里只锁中英词表本身不漂移（同一批情绪、不同释义）
        assertEquals(PromptTextsZh.emotionNames.size, PromptTextsEn.emotionNames.size)
        assertEquals("happy(开心)", PromptTextsZh.emotionNames.first())
        assertEquals("happy", PromptTextsEn.emotionNames.first())
    }

    // ── RpsTexts：EN 全成员无中文 ─────────────────────────────────────────────

    @Test
    fun `en rps directives have no chinese`() {
        val en = RpsTextsEn
        val announced = en.announced("rock")
        assertNoCjk("announced", announced)
        assertNoCjk("invited", en.invitedDirective())
        assertNoCjk("armed", en.armedDirective(3))
        assertNoCjk("judged", en.judgedDirective(4, "scissors", "the user won", announced))
        assertNoCjk("frameJudge", en.frameJudgeDirective(5, "rock", announced))
        assertNoCjk("noFrameJudge", en.noFrameJudgeDirective(6, "paper", announced))
        for (v in RpsVerdict.entries) {
            assertNoCjk("verdictTail odd $v", en.verdictTail(v, oddRound = true))
            assertNoCjk("verdictTail even $v", en.verdictTail(v, oddRound = false))
        }
        assertNoCjk("handsLine", en.handsLine("rock", "scissors"))
        assertNoCjk("throwPlaceholder", en.throwPlaceholder)
    }

    @Test
    fun `zh rps verdict lines keep legacy punctuation shape`() {
        // 宣判词直通 TTS：两个轮换变体按局数奇偶区分（RpsSkillTest 锁行为）
        assertEquals("——这局你赢了!", RpsTextsZh.verdictTail(RpsVerdict.USER_WIN, oddRound = true))
        assertEquals(",你赢了,再来!", RpsTextsZh.verdictTail(RpsVerdict.USER_WIN, oddRound = false))
        assertEquals("（出拳）", RpsTextsZh.throwPlaceholder)
    }

    // ── of(lang) 分发 ─────────────────────────────────────────────────────────

    @Test
    fun `of returns the right language variant`() {
        assertEquals(PromptTextsZh, PromptTexts.of(Lang.ZH))
        assertEquals(PromptTextsEn, PromptTexts.of(Lang.EN))
        assertEquals(RpsTextsZh, RpsTexts.of(Lang.ZH))
        assertEquals(RpsTextsEn, RpsTexts.of(Lang.EN))
    }
}
