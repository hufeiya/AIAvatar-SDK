package com.neethu.aiavatar_sdk

import com.neethu.corelib.Lang
import com.neethu.orchestrator.card.CharacterCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预置人物卡英文人设的不变量（多语言支持）：13 张中文原创卡在英文模式下必须
 * 有全字段英文翻译（否则英文用户的 system 提示词里混入中文人设，模型跟着用
 * 中文回复）；翻译字段本身不得含中文；中文模式下不替换（返回 null 用卡片原文）。
 */
class PresetCardsEnTest {

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    /** 中文原创预置卡清单（assets/cards 里的 13 张；官方英文卡不需要翻译）。 */
    private val zhPresetAssets = listOf(
        "preset_azao", "preset_baitang", "preset_guqingxian", "preset_jiangli",
        "preset_luzhiyao", "preset_shenpeilan", "preset_suisui", "preset_suqing",
        "preset_tangyiyi", "preset_wenwan", "preset_xiaoman", "preset_xiazhi",
        "preset_zhouyumian",
    )

    private val zhCard = CharacterCard(
        name = "阿枣",
        description = "中文描述",
        personality = "中文性格",
        scenario = "中文场景",
        firstMessage = "中文开场白，{{user}} 宏",
    )

    @Test
    fun `every zh preset card has an en translation`() {
        for (asset in zhPresetAssets) {
            val translated = PresetCardsEn.translate(zhCard, "$asset.png", Lang.EN)
            assertNotNull("缺英文翻译: $asset", translated)
            translated!!
            assertFalse("$asset name 未翻译", cjk.containsMatchIn(translated.name))
            assertFalse("$asset description 未翻译", cjk.containsMatchIn(translated.description))
            assertFalse("$asset personality 未翻译", cjk.containsMatchIn(translated.personality))
            assertFalse("$asset scenario 未翻译", cjk.containsMatchIn(translated.scenario))
            assertFalse("$asset firstMessage 未翻译", cjk.containsMatchIn(translated.firstMessage))
        }
    }

    @Test
    fun `translation keeps non-localized fields and user macro`() {
        val translated = PresetCardsEn.translate(zhCard, "preset_azao.png", Lang.EN)!!
        // {{user}} 宏原样保留在场景字段里（spokenGreeting 之后才替换成 alias）
        assertTrue(translated.scenario.contains("{{user}}"))
        // 其余字段（人设五项之外的元数据）不被动
        assertEquals(zhCard.systemPrompt, translated.systemPrompt)
        assertEquals(zhCard.spec, translated.spec)
    }

    @Test
    fun `non-en lang or unknown asset returns null to keep card original`() {
        assertNull(PresetCardsEn.translate(zhCard, "preset_azao.png", Lang.ZH))
        assertNull(PresetCardsEn.translate(zhCard, "preset_seraphina.png", Lang.EN)) // 官方英文卡
        assertNull(PresetCardsEn.translate(zhCard, "user_imported.png", Lang.EN))     // 用户导入卡
        assertNull(PresetCardsEn.translate(zhCard, null, Lang.EN))
    }

    @Test
    fun `official en cards are not in the translation map`() {
        for (asset in listOf("preset_seraphina", "preset_gloria", "preset_sakana", "preset_amy", "preset_codingsensei")) {
            assertFalse(asset, PresetCardsEn.has("$asset.png"))
        }
    }
}
