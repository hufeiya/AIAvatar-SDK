package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 大模型身份签名（换服务商/模型 → 轮换对话上下文的判定依据）：只有影响模型
 * 能力的字段参与签名；语音、镜头开关、API Key 变化必须保持签名稳定（历史延续）。
 */
class LlmIdentitySignatureTest {

    private val base = AiChatPrefs(
        provider = AiProvider.SILICONFLOW,
        llmModel = "deepseek-ai/DeepSeek-V3",
        apiKeySiliconflow = "sk-1",
    )

    @Test
    fun `same identity - same signature`() {
        assertEquals(llmIdentitySignature(base), llmIdentitySignature(base.copy()))
    }

    @Test
    fun `llm model change changes the signature`() {
        val other = base.copy(llmModel = "Qwen/Qwen3.8-27B")
        assertNotEquals(llmIdentitySignature(base), llmIdentitySignature(other))
    }

    @Test
    fun `provider change changes the signature even with blank model`() {
        // 模型留空 = 各落各的服务商默认（DeepSeek-V4-Flash vs doubao-seed-2-0-mini），
        // 签名按解析后的有效模型算，服务商不同即不同
        val blank = base.copy(llmModel = "")
        val volcano = base.copy(provider = AiProvider.VOLCANO, llmModel = "")
        assertNotEquals(llmIdentitySignature(blank), llmIdentitySignature(volcano))
    }

    @Test
    fun `out-of-list model falls back to provider default - no spurious rotation`() {
        // 切服务商后残留的旧清单值会被 resolveLlmModel 归位成默认：签名与"留空"
        // 一致，不会把同一次配置往返误判成两次换模型
        val stale = base.copy(llmModel = "doubao-seed-2-0-mini-260428")
        assertEquals(llmIdentitySignature(base.copy(llmModel = "")), llmIdentitySignature(stale))
    }

    @Test
    fun `tts and key and camera changes keep the signature`() {
        val ttsOnly = base.copy(
            ttsSameProvider = false,
            ttsProvider = AiProvider.VOLCANO,
            ttsModel = "seed-tts-2.0",
            voice = "zh_female_vv_uranus_bigtts",
            apiKeyVolcanoTts = "tts-key",
        )
        assertEquals(llmIdentitySignature(base), llmIdentitySignature(ttsOnly))

        val keyOnly = base.copy(apiKeySiliconflow = "sk-2")
        assertEquals(llmIdentitySignature(base), llmIdentitySignature(keyOnly))

        val cameraOff = base.copy(llmCamera = false)
        assertEquals(llmIdentitySignature(base), llmIdentitySignature(cameraOff))
    }
}
