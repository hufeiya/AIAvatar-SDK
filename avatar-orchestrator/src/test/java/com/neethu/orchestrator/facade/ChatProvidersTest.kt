package com.neethu.orchestrator.facade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatProvider] 目录解析纯函数（单一事实源在 SDK，demo 的 AiProvidersTest
 * 是同一批值经 AiProvider 包装后的投影，两测互为锁）。
 */
class ChatProvidersTest {

    @Test
    fun `catalog matches the verified provider facts`() {
        val sf = ChatProvider.SILICONFLOW
        assertEquals("https://api.siliconflow.cn/v1", sf.baseUrl)
        assertEquals(
            listOf(
                "deepseek-ai/DeepSeek-V4-Flash",
                "deepseek-ai/DeepSeek-V3",
                "Qwen/Qwen3.8-27B",
                "Qwen/Qwen3-VL-32B-Instruct",
            ),
            sf.llmModels,
        )
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", sf.defaultLlmModel)
        assertEquals("fnlp/MOSS-TTSD-v0.5", sf.defaultTtsModel)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", sf.voicePrefix)
        assertEquals("anna", sf.defaultVoice)

        val volc = ChatProvider.VOLCANO
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", volc.baseUrl)
        assertEquals("doubao-seed-2-0-mini-260428", volc.defaultLlmModel)
        assertEquals(listOf("seed-tts-2.0"), volc.ttsModels)
        assertEquals("zh_female_vv_uranus_bigtts", volc.defaultVoice)
        assertNull(volc.voicePrefix)

        val or = ChatProvider.OPENROUTER
        assertEquals("https://openrouter.ai/api/v1", or.baseUrl)
        assertEquals("google/gemini-3.8-flash", or.defaultLlmModel)
        // 全部 host 实测真视觉（64px 红/蓝图）
        assertEquals(or.llmModels.toSet(), or.visionLlmModels.toSet())
        assertEquals("mistralai/voxtral-mini-tts-2603", or.defaultTtsModel)
        assertEquals("en_paul_neutral", or.defaultVoice)
    }

    @Test
    fun `model resolution falls back to vendor defaults`() {
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", resolveLlmModel(ChatProvider.SILICONFLOW, ""))
        assertEquals(
            "deepseek-ai/DeepSeek-V3",
            resolveLlmModel(ChatProvider.SILICONFLOW, "deepseek-ai/DeepSeek-V3"),
        )
        assertEquals("doubao-seed-2-0-mini-260428", resolveLlmModel(ChatProvider.VOLCANO, "  "))
        assertEquals("seed-tts-2.0", resolveTtsModel(ChatProvider.VOLCANO, ""))
    }

    // ── 跨服务商防泄漏（真机实测切火山后 DeepSeek-V3 残留被原样发给 Ark）──
    @Test
    fun `out-of-catalog stored values fall back to the new vendor defaults`() {
        assertEquals(
            "doubao-seed-2-0-mini-260428",
            resolveLlmModel(ChatProvider.VOLCANO, "deepseek-ai/DeepSeek-V3"),
        )
        assertEquals(
            "zh_female_vv_uranus_bigtts",
            resolveVoice(ChatProvider.VOLCANO, "FunAudioLLM/CosyVoice2-0.5B:alex"),
        )
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:anna",
            resolveVoice(ChatProvider.SILICONFLOW, "zh_male_m191_uranus_bigtts"),
        )
    }

    @Test
    fun `voice ref composition`() {
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:anna",
            composeVoiceRef(ChatProvider.SILICONFLOW, "anna"),
        )
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:alex",
            composeVoiceRef(ChatProvider.SILICONFLOW, "FunAudioLLM/CosyVoice2-0.5B:alex"),
        )
        assertEquals("", composeVoiceRef(ChatProvider.SILICONFLOW, " "))
        assertEquals("zh_female_vv_uranus_bigtts", composeVoiceRef(ChatProvider.VOLCANO, "zh_female_vv_uranus_bigtts"))
    }

    @Test
    fun `thinking params follow provider and model family`() {
        // 火山 doubao-seed 系关思考（首句延迟治理，2026-10-04）
        val volc = llmExtraBody(ChatProvider.VOLCANO, "doubao-seed-2-0-mini-260428")!!
        assertEquals(
            "disabled",
            ((volc["thinking"] as kotlinx.serialization.json.JsonObject)["type"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        // 硅基流动 Qwen3 系 enable_thinking=false（千问慢的根因）
        val sf = llmExtraBody(ChatProvider.SILICONFLOW, "Qwen/Qwen3.8-27B")!!
        assertEquals(
            "false",
            (sf["enable_thinking"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        // OpenRouter qwen 系统一 reasoning 参数；gemini/claude 系绝不发（400 风险）
        val or = llmExtraBody(ChatProvider.OPENROUTER, "qwen/qwen3.7-flash")!!
        assertEquals(
            "false",
            ((or["reasoning"] as kotlinx.serialization.json.JsonObject)["enabled"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertNull(llmExtraBody(ChatProvider.OPENROUTER, "google/gemini-3.8-flash"))
        // 关思考方言不跨服务商泄漏
        assertNull(llmExtraBody(ChatProvider.SILICONFLOW, "qwen/qwen3.7-flash"))
        assertNull(llmExtraBody(ChatProvider.VOLCANO, "Qwen/Qwen3.8-27B"))
    }

    @Test
    fun `vision gating resolves through the catalog first`() {
        assertFalse(isVisionLlm(ChatProvider.SILICONFLOW, ""))
        assertTrue(isVisionLlm(ChatProvider.SILICONFLOW, "Qwen/Qwen3.8-27B"))
        assertTrue(isVisionLlm(ChatProvider.VOLCANO, ""))
        // 跨服务商残留值先归位再判定，不把残留值误判成清单内视觉模型
        assertTrue(isVisionLlm(ChatProvider.VOLCANO, "deepseek-ai/DeepSeek-V3"))
        assertFalse(isVisionLlm(ChatProvider.SILICONFLOW, "doubao-seed-2-0-mini-260428"))
    }

    @Test
    fun `asr provider follows llm except volcano falls back to siliconflow`() {
        assertEquals(ChatProvider.SILICONFLOW, asrProviderFor(ChatProvider.SILICONFLOW))
        assertEquals(ChatProvider.OPENROUTER, asrProviderFor(ChatProvider.OPENROUTER))
        assertEquals(ChatProvider.SILICONFLOW, asrProviderFor(ChatProvider.VOLCANO))
        assertEquals(listOf("Qwen/Qwen3-ASR-1.7B"), asrExplicitAsrModels(ChatProvider.SILICONFLOW))
        assertEquals(listOf("openai/whisper-large-v3"), asrExplicitAsrModels(ChatProvider.OPENROUTER))
        assertEquals(0, asrExplicitAsrModels(ChatProvider.VOLCANO).size)
    }

    @Test
    fun `asr model inferred from base url when blank`() {
        assertEquals("Qwen/Qwen3-ASR-1.7B", resolveAsrModel("https://api.siliconflow.cn/v1", ""))
        assertEquals("openai/whisper-large-v3", resolveAsrModel("https://openrouter.ai/api/v1", ""))
        assertEquals("whisper-1", resolveAsrModel("https://api.openai.com/v1", ""))
        assertEquals("my-asr", resolveAsrModel("https://api.siliconflow.cn/v1", " my-asr "))
    }

    @Test
    fun `edge voice resolution validates against catalog`() {
        assertEquals("zh-CN-XiaoxiaoNeural", resolveEdgeVoice(""))
        assertEquals("zh-CN-YunxiNeural", resolveEdgeVoice("zh-CN-YunxiNeural"))
        // OpenAI 兼容引擎残留的音色引用不在目录 → 落默认（跨引擎防泄漏）
        assertEquals(
            "zh-CN-XiaoxiaoNeural",
            resolveEdgeVoice("FunAudioLLM/CosyVoice2-0.5B:anna"),
        )
        // 英文模式落英文多语种默认音色
        assertEquals(
            "en-US-EmmaMultilingualNeural",
            resolveEdgeVoice("", com.neethu.corelib.Lang.EN),
        )
        // 目录实测在册且无重复
        assertTrue(EdgeTtsCatalog.voices.isNotEmpty())
        assertEquals(EdgeTtsCatalog.voices.size, EdgeTtsCatalog.voices.map { it.first }.toSet().size)
        assertTrue(EdgeTtsCatalog.voices.any { it.first == EdgeTtsCatalog.DEFAULT_VOICE })
    }
}
