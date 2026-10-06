package com.neethu.aiavatar_sdk

import com.neethu.aiavatar_sdk.ui.GUIDE_PAGE_COUNT
import com.neethu.aiavatar_sdk.ui.GuideVariant
import com.neethu.aiavatar_sdk.ui.guideStepAssets
import com.neethu.aiavatar_sdk.ui.onboardingGuideVariant
import com.neethu.aiavatar_sdk.ui.withOnboardingAsr
import com.neethu.aiavatar_sdk.ui.withOnboardingKey
import com.neethu.corelib.Lang
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新手引导（ui/OnboardingGuide.kt）的纯函数不变量，双受众（国内=硅基流动 /
 * 海外=OpenRouter）：触发判定、「确认」一键配置（免费+带视觉的大模型、海外
 * TTS/ASR 走免费通道）与轮播资源契约。
 */
class OnboardingGuideTest {

    // ── 触发判定：未配置才弹；中文（简繁都算）→国内版，其余语言→海外版 ──────
    @Test
    fun `guide variant follows language while unconfigured`() {
        assertEquals(GuideVariant.CN, onboardingGuideVariant(Lang.ZH, aiConfigured = false))
        assertEquals(GuideVariant.INTL, onboardingGuideVariant(Lang.EN, aiConfigured = false))
        assertEquals("非中英语言也归海外版", GuideVariant.INTL, onboardingGuideVariant(Lang.EN, false))
        assertNull("已配置后不再打扰", onboardingGuideVariant(Lang.ZH, aiConfigured = true))
        assertNull(onboardingGuideVariant(Lang.EN, aiConfigured = true))
    }

    // ── 国内版一键配置：硅基流动一把 Key 通吃；大模型=多模态 Qwen3.8-27B ─────
    @Test
    fun `cn confirm applies siliconflow key with vision llm and default tts`() {
        // 残留配置（火山服务商+模型名+Edge 引擎）也要被引导的确认键归位
        val legacy = AiChatPrefs(
            provider = AiProvider.VOLCANO,
            llmModel = "doubao-seed-2-0-mini-260428",
            apiKeyVolcano = "ark-legacy",
            ttsEngine = TtsEngine.EDGE,
        )
        val p = withOnboardingKey(legacy, "  sk-test-123  ", GuideVariant.CN)
        assertEquals(AiProvider.SILICONFLOW, p.provider)
        assertEquals("sk-test-123", p.apiKeySiliconflow)
        // 大模型钉住视觉多模态（用户点名的 Qwen3.8-27B）：视频模式开箱即用
        assertEquals("Qwen/Qwen3.8-27B", p.llmModel)
        assertTrue(isVisionLlm(p.provider, p.llmModel))
        assertTrue(p.isConfigured)
        assertEquals(TtsEngine.OPENAI_COMPATIBLE, p.ttsEngine)
        assertTrue(p.ttsSameProvider)
        assertEquals(AiProvider.SILICONFLOW, p.ttsProviderResolved)
        assertEquals(p.provider.defaultTtsModel, resolveTtsModel(p.provider, p.ttsModel))
        assertEquals(
            composeVoiceRef(p.provider, p.provider.defaultVoice),
            resolveVoice(p.provider, p.voice),
        )
        // 其它服务商的旧 Key 原样保留（不清库，只是不再用）
        assertEquals("ark-legacy", p.apiKeyVolcano)
    }

    // ── 海外版一键配置：OpenRouter 免费视觉路由；TTS/ASR 走免费通道 ──────────
    @Test
    fun `intl confirm applies openrouter free vision llm with edge tts and system asr`() {
        val legacy = AiChatPrefs(
            provider = AiProvider.VOLCANO,
            llmModel = "doubao-seed-2-0-mini-260428",
            apiKeyVolcano = "ark-legacy",
            ttsEngine = TtsEngine.OPENAI_COMPATIBLE,
        )
        val p = withOnboardingKey(legacy, "  sk-or-v1-test  ", GuideVariant.INTL)
        assertEquals(AiProvider.OPENROUTER, p.provider)
        assertEquals("sk-or-v1-test", p.apiKeyOpenrouter)
        // 用户点名规则「默认选名字中有 free 的带视觉模型」：目录里第一个含
        // free 的条目，且过了视觉门控（视频模式开箱即用）
        val freeModel = AiProvider.OPENROUTER.llmModels.first { it.contains("free", ignoreCase = true) }
        assertEquals(freeModel, p.llmModel)
        assertTrue(isVisionLlm(p.provider, p.llmModel))
        // OpenRouter 音频端点要 ≥$0.5 余额，新号不可用 → TTS 落 Edge-TTS（免费
        // 无 Key，ttsReady 恒真，isConfigured 不被语音侧卡住）
        assertEquals(TtsEngine.EDGE, p.ttsEngine)
        assertTrue(p.isConfigured)
        // 旧 Key 原样保留；硅基流动侧未被误写
        assertEquals("ark-legacy", p.apiKeyVolcano)
        assertEquals("", p.apiKeySiliconflow)

        // ASR 同理走系统内置（免费无 Key）；国内版对照=云端识别
        assertEquals(AsrEngine.SYSTEM, withOnboardingAsr(VoicePrefs(), GuideVariant.INTL).asrEngine)
        assertEquals(AsrEngine.CLOUD, withOnboardingAsr(VoicePrefs(), GuideVariant.CN).asrEngine)
        assertEquals("", withOnboardingAsr(VoicePrefs(asrModel = "x"), GuideVariant.INTL).asrModel)
    }

    /** 空白 Key 不能把 isConfigured 撑成 true（按钮已挡，这里是兜底语义）。 */
    @Test
    fun `blank key leaves session unconfigured`() {
        val p = withOnboardingKey(AiChatPrefs(), "   ", GuideVariant.CN)
        assertFalse(p.isConfigured)
        val intl = withOnboardingKey(AiChatPrefs(), "   ", GuideVariant.INTL)
        assertFalse(intl.isConfigured)
    }

    // ── 轮播资源契约：两受众各 3 图 + 第 4 页；assets 文件真实在库 ───────────
    @Test
    fun `carousel assets exist for both variants with correct urls`() {
        assertEquals(4, GUIDE_PAGE_COUNT)
        for (variant in GuideVariant.entries) {
            val steps = guideStepAssets(variant)
            assertEquals("$variant 应有 3 张步骤截图", 3, steps.size)
            for (path in steps) {
                assertTrue("asset 缺失: $path", File("src/main/assets/$path").isFile)
            }
        }
        // 邀请链接：国内=硅基流动返利链，海外=OpenRouter 官网
        assertEquals("https://cloud.siliconflow.cn/i/iPLguD02", GuideVariant.CN.registerUrl)
        assertEquals("https://openrouter.ai/", GuideVariant.INTL.registerUrl)
        assertEquals(GuideVariant.CN.provider, AiProvider.SILICONFLOW)
        assertEquals(GuideVariant.INTL.provider, AiProvider.OPENROUTER)
    }
}
