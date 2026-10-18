package com.neethu.orchestrator.facade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", sf.llmModels.first())
        // 2026-10-18 默认模型=视觉优先（视觉清单首位，VL-32B 刻意排在 Qwen3.8-27B 前）
        assertEquals("Qwen/Qwen3-VL-32B-Instruct", sf.defaultLlmModel)
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", sf.defaultTtsModel)
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

        val ds = ChatProvider.DEEPSEEK
        // 2026-10-18 真 key 核验：/models 在册两模型；flash 输入含 image=真视觉
        assertEquals("https://api.deepseek.com/v1", ds.baseUrl)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), ds.llmModels)
        assertEquals("deepseek-flash", ds.defaultLlmModel)
        assertEquals(listOf("deepseek-flash"), ds.visionLlmModels)
        // 官方无 TTS（/audio/speech 404）：清单为空，选它时 ttsEngineResolved 回落 Edge
        assertEquals(0, ds.ttsModels.size)
        assertEquals("", ds.defaultTtsModel)

        val bl = ChatProvider.BAILIAN
        // 2026-10-18 真 key 核验：企业专属端点 compatible-mode，chat/视觉/工具全通
        assertEquals(
            "https://ws-xej6bspqc7ct974u.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
            bl.baseUrl,
        )
        assertTrue("qwen3.8-flash" in bl.llmModels)
        // 默认模型=视觉优先：qwen3.8-flash 64px 红/蓝图实测真视觉（usage 计 image_tokens）
        assertEquals("qwen3.8-flash", bl.defaultLlmModel)
        assertEquals(listOf("qwen3.8-flash", "qwen3-vl-plus"), bl.visionLlmModels)
        // TTS 走 DashScope 原生协议（/audio/speech 404）；六音色逐一实测可合成
        assertEquals(listOf("qwen3-tts-flash"), bl.ttsModels)
        assertEquals("qwen3-tts-flash", bl.defaultTtsModel)
        assertEquals("Cherry", bl.defaultVoice)
        assertNull(bl.voicePrefix)
    }

    @Test
    fun `model resolution falls back to vendor defaults`() {
        // 默认=视觉优先（2026-10-18 需求：默认即可看图）
        assertEquals("Qwen/Qwen3-VL-32B-Instruct", resolveLlmModel(ChatProvider.SILICONFLOW, ""))
        assertEquals(
            "deepseek-ai/DeepSeek-V3",
            resolveLlmModel(ChatProvider.SILICONFLOW, "deepseek-ai/DeepSeek-V3"),
        )
        assertEquals("doubao-seed-2-0-mini-260428", resolveLlmModel(ChatProvider.VOLCANO, "  "))
        assertEquals("seed-tts-2.0", resolveTtsModel(ChatProvider.VOLCANO, ""))
        assertEquals("deepseek-flash", resolveLlmModel(ChatProvider.DEEPSEEK, ""))
        assertEquals("qwen3.8-flash", resolveLlmModel(ChatProvider.BAILIAN, ""))
        // TTS 清单为空 → 恒空（引擎已被 ttsEngineResolved 改道 Edge，不真请求）
        assertEquals("", resolveTtsModel(ChatProvider.DEEPSEEK, "seed-tts-2.0"))
    }

    @Test
    fun `live catalog ids are accepted through extraAllowed`() {
        // /models 实时目录里的模型不在静态清单：extraAllowed 放行，缺省仍落默认
        assertEquals(
            "qwen3.9-max-future",
            resolveLlmModel(ChatProvider.BAILIAN, "qwen3.9-max-future", setOf("qwen3.9-max-future")),
        )
        assertEquals(
            "qwen3.8-flash",
            resolveLlmModel(ChatProvider.BAILIAN, "qwen3.9-max-future", emptySet()),
        )
        // extraAllowed 语义=「本服务商」的实时目录（调用方按 provider 传入，
        // 见 demo ModelCatalogCache.idsFor）：火山自己的实时目录放行火山的新模型
        assertEquals(
            "doubao-seed-3-0-flash",
            resolveLlmModel(ChatProvider.VOLCANO, "doubao-seed-3-0-flash", setOf("doubao-seed-3-0-flash")),
        )
        // 跨服务商防泄漏仍在：火山缓存里的 id 救不了火山清单里的残留检查——
        // 留空/清单外且目录也没有 → 照落火山默认
        assertEquals(
            "doubao-seed-2-0-mini-260428",
            resolveLlmModel(ChatProvider.VOLCANO, "qwen3.9-max-future", setOf("doubao-seed-3-0-flash")),
        )
    }

    @Test
    fun `vision heuristic tags live catalog models conservatively`() {
        // 命名启发式：vl/vision/omni 词元 → 视觉
        assertTrue(looksLikeVisionModel(ChatProvider.BAILIAN, "qwen3.5-vl-plus"))
        assertTrue(looksLikeVisionModel(ChatProvider.BAILIAN, "qwen3.8-omni-flash"))
        assertFalse(looksLikeVisionModel(ChatProvider.BAILIAN, "qwen3.8-flash"))
        // 火山 doubao-seed 系对话模型全族带图（Ark domain=VLM 实证）；生成系排除
        assertTrue(looksLikeVisionModel(ChatProvider.VOLCANO, "doubao-seed-2-2-pro-261228"))
        assertTrue(looksLikeVisionModel(ChatProvider.VOLCANO, "doubao-seed-character-261228"))
        assertFalse(looksLikeVisionModel(ChatProvider.VOLCANO, "doubao-seedream-6-0-pro"))
        assertFalse(looksLikeVisionModel(ChatProvider.VOLCANO, "doubao-seedance-2-0"))
        assertFalse(looksLikeVisionModel(ChatProvider.VOLCANO, "doubao-seed-translation-250915"))
        // 假视觉先例（deepseek-v4-flash-ga 收图答 incomplete）不被启发式误标
        assertFalse(looksLikeVisionModel(ChatProvider.VOLCANO, "deepseek-v4-flash-ga-260731"))
        // 门控入口：静态清单 + 启发式双路
        assertTrue(isVisionLlm(ChatProvider.BAILIAN, ""))
        assertTrue(isVisionLlm(ChatProvider.BAILIAN, "qwen3-vl-plus"))
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
        // DeepSeek 默认开思考但走独立 reasoning_content（不污染 delta.content），
        // effort 三档实测无感——不发任何额外参数
        assertNull(llmExtraBody(ChatProvider.DEEPSEEK, "deepseek-flash"))
        assertNull(llmExtraBody(ChatProvider.DEEPSEEK, "deepseek-v4-pro"))
        // 百炼：qwen 全系发 enable_thinking=false（qwen3.8 混合思考默认开的
        // 首句延迟治理）；glm/kimi/deepseek 系绝不发（glm-5.3-prime 实测
        // 「restricted to True」400）
        val bl = llmExtraBody(ChatProvider.BAILIAN, "qwen3.8-flash")!!
        assertEquals(
            "false",
            (bl["enable_thinking"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertNotNull(llmExtraBody(ChatProvider.BAILIAN, "qwen-flash-character"))
        assertNull(llmExtraBody(ChatProvider.BAILIAN, "glm-5.3-prime"))
        assertNull(llmExtraBody(ChatProvider.BAILIAN, "kimi-k3"))
        assertNull(llmExtraBody(ChatProvider.BAILIAN, "deepseek-v4.1-flash"))
    }

    @Test
    fun `vision gating resolves through the catalog first`() {
        // 默认模型=视觉优先（2026-10-18）：空配置在硅基流动/百炼也归位到视觉模型
        assertTrue(isVisionLlm(ChatProvider.SILICONFLOW, ""))
        assertTrue(isVisionLlm(ChatProvider.SILICONFLOW, "Qwen/Qwen3.8-27B"))
        assertTrue(isVisionLlm(ChatProvider.VOLCANO, ""))
        // 跨服务商残留值先归位再判定，不把残留值误判成清单内视觉模型
        assertTrue(isVisionLlm(ChatProvider.VOLCANO, "deepseek-ai/DeepSeek-V3"))
        // 火山模型残留到硅基流动 → 落 SF 默认（视觉优先）→ 视觉
        assertTrue(isVisionLlm(ChatProvider.SILICONFLOW, "doubao-seed-2-0-mini-260428"))
        // DeepSeek：flash 真视觉（红/蓝图实测），v4-pro 喂图明说看不到
        assertTrue(isVisionLlm(ChatProvider.DEEPSEEK, ""))
        assertTrue(isVisionLlm(ChatProvider.DEEPSEEK, "deepseek-flash"))
        assertFalse(isVisionLlm(ChatProvider.DEEPSEEK, "deepseek-v4-pro"))
    }

    @Test
    fun `asr provider follows llm except volcano and deepseek fall back to siliconflow`() {
        assertEquals(ChatProvider.SILICONFLOW, asrProviderFor(ChatProvider.SILICONFLOW))
        assertEquals(ChatProvider.OPENROUTER, asrProviderFor(ChatProvider.OPENROUTER))
        assertEquals(ChatProvider.SILICONFLOW, asrProviderFor(ChatProvider.VOLCANO))
        assertEquals(ChatProvider.SILICONFLOW, asrProviderFor(ChatProvider.DEEPSEEK))
        // 百炼 ASR 同商直连（chat+input_audio 定制形态，qwen3-asr-flash）
        assertEquals(ChatProvider.BAILIAN, asrProviderFor(ChatProvider.BAILIAN))
        assertEquals(listOf("Qwen/Qwen3-ASR-1.7B"), asrExplicitAsrModels(ChatProvider.SILICONFLOW))
        assertEquals(listOf("openai/whisper-large-v3"), asrExplicitAsrModels(ChatProvider.OPENROUTER))
        assertEquals(listOf("qwen3-asr-flash"), asrExplicitAsrModels(ChatProvider.BAILIAN))
        assertEquals(0, asrExplicitAsrModels(ChatProvider.VOLCANO).size)
        assertEquals(0, asrExplicitAsrModels(ChatProvider.DEEPSEEK).size)
    }

    @Test
    fun `asr model inferred from base url when blank`() {
        assertEquals("Qwen/Qwen3-ASR-1.7B", resolveAsrModel("https://api.siliconflow.cn/v1", ""))
        assertEquals("openai/whisper-large-v3", resolveAsrModel("https://openrouter.ai/api/v1", ""))
        assertEquals("qwen3-asr-flash", resolveAsrModel(ChatProvider.BAILIAN.baseUrl, ""))
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
