package com.neethu.aiavatar_sdk

import com.neethu.aiavatar_sdk.i18n.AppLang
import com.neethu.aiavatar_sdk.i18n.Strings
import com.neethu.aiavatar_sdk.i18n.resolveAppLang
import com.neethu.corelib.Lang
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI 文案双语表的不变量（多语言支持）：[Strings] 的 EN 实例**反射扫全部
 * String 属性都不得含中文字符**（用户需求「英文模式下不要出现中文」）；
 * ZH 实例保留关键锚点。防漂移：新增文案成员忘翻 EN 时这里立刻红。
 */
class StringsI18nTest {

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    private fun assertNoCjk(what: String, text: String) {
        val hit = cjk.findAll(text).take(5).joinToString("")
        assertFalse("EN $what 含中文: $hit", cjk.containsMatchIn(text))
    }

    @Test
    fun `en strings table has no chinese in any string property`() {
        val en = Strings(Lang.EN)
        val props = Strings::class.memberProperties.filter {
            it.visibility == KVisibility.PUBLIC && it.returnType.classifier == String::class
        }
        assertTrue("反射没扫到成员（结构变了？）props=${props.size}", props.size > 100)
        // 语言选择器的两个成员刻意双语标注（语言名两种语言都写，找不到设置的
        // 用户才认得出自己的语言；语言选择器的通行惯例），豁免无中文不变量
        val bilingualAllowed = setOf("sectionLanguage", "appLangLabel")
        for (p in props) {
            if (p.name in bilingualAllowed) continue
            assertNoCjk(p.name, p.getter.call(en) as String)
        }
    }

    @Test
    fun `en parameterized strings have no chinese`() {
        val s = Strings(Lang.EN)
        for (m in com.neethu.aiavatar_sdk.InputMode.entries) assertNoCjk("inputMode", s.inputMode(m))
        for (e in AsrEngine.entries) assertNoCjk("asrEngine", s.asrEngine(e))
        for (e in TtsEngine.entries) assertNoCjk("ttsEngine", s.ttsEngine(e))
        for (p in AiProvider.entries) {
            assertNoCjk("provider", s.provider(p))
            assertNoCjk("llmKeyLabel", s.llmKeyLabel(p))
            assertNoCjk("ttsKeyLabel", s.ttsKeyLabel(p))
        }
        for (id in EdgeTtsCatalog.voices.map { it.first }) assertNoCjk("edgeVoiceLabel", s.edgeVoiceLabel(id))
        assertNoCjk("sentenceFailed", s.sentenceFailed(0, "boom"))
        assertNoCjk("videoNeedsVision", s.videoNeedsVision("gpt-x"))
        assertNoCjk("asrNeedsKey", s.asrNeedsKey(AiProvider.SILICONFLOW))
        assertNoCjk("freeTalkEnabled", s.freeTalkEnabled(AsrEngine.SYSTEM))
        assertNoCjk("presetCardUnreadable", s.presetCardUnreadable("cards/preset_azao.png"))
        assertNoCjk("animExternalDir", s.animExternalDir("/data"))
        assertNoCjk("systemAsrStopped", s.systemAsrStopped("busy"))
        assertNoCjk("protocolIntro", s.protocolIntro(123))
        assertNoCjk("chooseA11y", s.chooseA11y("LLM"))
        // appLangLabel 刻意双语（见上方豁免注释），不在 EN 无中文断言内
        for (code in intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 99)) {
            assertNoCjk("systemAsrErrorMessage($code)", s.systemAsrErrorMessage(code))
        }
    }

    @Test
    fun `zh anchors survive migration`() {
        val s = Strings(Lang.ZH)
        assertEquals("思考中", s.phaseThinking)
        assertTrue(s.transcriptUserPrefix.startsWith("我："))
        assertEquals("手动点击", s.inputMode(com.neethu.aiavatar_sdk.InputMode.MANUAL))
        // 错误码映射与 SystemAsrTest 的既有断言同源
        assertEquals("没有听到说话", s.systemAsrErrorMessage(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals("缺少麦克风权限", s.systemAsrErrorMessage(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals("识别错误(code=99)", s.systemAsrErrorMessage(99))
    }

    @Test
    fun `app lang resolution follows system language`() {
        // 系统中文（简体/繁体/地区一律算）→ 简体；其余 → 英文；显式偏好优先
        assertEquals(Lang.ZH, resolveAppLang(AppLang.SYSTEM, "zh"))
        assertEquals(Lang.ZH, resolveAppLang(AppLang.SYSTEM, "zh-TW"))
        assertEquals(Lang.ZH, resolveAppLang(AppLang.SYSTEM, "ZH"))
        assertEquals(Lang.EN, resolveAppLang(AppLang.SYSTEM, "en"))
        assertEquals(Lang.EN, resolveAppLang(AppLang.SYSTEM, "ja"))
        assertEquals(Lang.ZH, resolveAppLang(AppLang.ZH, "en"))
        assertEquals(Lang.EN, resolveAppLang(AppLang.EN, "zh"))
    }
}
