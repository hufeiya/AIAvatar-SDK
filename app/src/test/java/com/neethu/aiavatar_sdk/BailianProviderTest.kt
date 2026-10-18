package com.neethu.aiavatar_sdk

import com.neethu.orchestrator.facade.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阿里云百炼（第五服务商，2026-10-18 真 key 全链核验）：chat 走企业专属端点
 * compatible-mode（/models 在册 176 模型），TTS 走 DashScope 原生协议
 * （/audio/speech 实测 404），ASR 走 chat+input_audio（/audio/transcriptions
 * 实测 404）——一把 key 三端通用；qwen3.8 系混合思考默认开、enable_thinking:
 * false 有效，glm 系例外（restricted to True 400）不发。
 */
class BailianProviderTest {

    private val bl = AiProvider.BAILIAN

    @Test
    fun `bailian catalog is verified and locked`() {
        assertEquals("阿里云百炼", bl.label)
        assertEquals(
            "https://ws-xej6bspqc7ct974u.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            bl.baseUrl,
        )
        // 静态清单只收 chat 实测过的模型；默认=视觉优先（qwen3.8-flash
        // 64px 红/蓝图实测真视觉，usage 计 image_tokens）
        assertTrue("qwen3.8-flash" in bl.llmModels)
        assertEquals("qwen3.8-flash", bl.defaultLlmModel)
        assertEquals(listOf("qwen3.8-flash", "qwen3-vl-plus"), bl.visionLlmModels)
        assertTrue(bl.visionLlmModels.all { it in bl.llmModels })
        // TTS 走 DashScope 原生协议（qwen3-tts-flash），六音色逐一实测可合成
        assertEquals(listOf("qwen3-tts-flash"), bl.ttsModels)
        assertEquals("Cherry", bl.defaultVoice)
        assertNull(bl.voicePrefix)
    }

    @Test
    fun `bailian one key serves llm tts and asr`() {
        val prefs = AiChatPrefs(provider = bl, apiKeyBailian = "sk-bl")
        assertTrue(prefs.isConfigured)
        assertEquals("sk-bl", prefs.apiKeyFor(bl))
        // 同商共用大模型 key（TTS 不改道 Edge，ASR 不回落硅基流动）
        assertEquals("sk-bl", prefs.apiKeyForTts())
        assertEquals(TtsEngine.OPENAI_COMPATIBLE, prefs.ttsEngineResolved)
        assertTrue(prefs.ttsReady)
        assertEquals(bl, asrProviderFor(bl))
        assertEquals("qwen3-asr-flash", resolveAsrModel(bl.baseUrl, ""))
        // 迁移推断：aliyuncs.com 端点归百炼
        assertEquals(bl, inferProviderFromBaseUrl(bl.baseUrl))
    }

    @Test
    fun `bailian voices validate against the measured catalog`() {
        // 跨引擎残留音色（CosyVoice 引用）落默认 Cherry；实测音色原样保留
        assertEquals("Cherry", resolveVoice(bl, "FunAudioLLM/CosyVoice2-0.5B:anna"))
        assertEquals("Ethan", resolveVoice(bl, "Ethan"))
        assertEquals("Cherry", resolveVoice(bl, ""))
        assertEquals("Cherry", resolveVoice(bl, "不存在的音色"))
    }

    @Test
    fun `bailian qwen models get enable_thinking false and third party gets nothing`() {
        assertEquals(
            "false",
            (llmExtraBody(bl, "qwen3.8-flash")!!["enable_thinking"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals(
            "false",
            (llmExtraBody(bl, "qwen-plus")!!["enable_thinking"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        // glm-5.3-prime 实测「enable_thinking is restricted to True」400——绝不发
        assertNull(llmExtraBody(bl, "glm-5.3-prime"))
        assertNull(llmExtraBody(bl, "kimi-k3"))
        assertNull(llmExtraBody(bl, "deepseek-v4.1-flash"))
    }

    @Test
    fun `bailian key field labels follow the service`() {
        assertEquals("阿里云百炼 API Key", bl.llmKeyLabel)
        assertEquals("sk-...", bl.llmKeyHint)
        assertEquals("阿里云百炼 API Key（语音合成用）", bl.ttsKeyLabel)
    }

    @Test
    fun `bailian appears in tts provider options and deepseek does not`() {
        val options = com.neethu.aiavatar_sdk.ui.ttsProviderOptions(
            com.neethu.aiavatar_sdk.i18n.Strings(com.neethu.corelib.Lang.ZH),
        )
        assertTrue(bl.name in options.map { it.value })
        assertFalse(AiProvider.DEEPSEEK.name in options.map { it.value })
    }
}
