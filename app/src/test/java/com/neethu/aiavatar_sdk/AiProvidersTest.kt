package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双服务商目录的解析纯函数：模型/音色默认值、音色引用拼装、旧 prefs 迁移
 * 推断、配置完整性判定。清单与默认值是设置页全部下拉框的数据源。
 */
class AiProvidersTest {

    // ── 目录内容与需求对齐（默认值是产品决策，锁住防手滑改动）──────────
    @Test
    fun `catalog matches the product spec`() {
        val sf = AiProvider.SILICONFLOW
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
        assertEquals(listOf("fnlp/MOSS-TTSD-v0.5", "FunAudioLLM/CosyVoice2-0.5B"), sf.ttsModels)
        assertEquals("fnlp/MOSS-TTSD-v0.5", sf.defaultTtsModel)
        assertEquals("anna", sf.defaultVoice)
        assertEquals(listOf("alex", "anna", "bella", "benjamin", "charles", "claire"), sf.voices)

        val volc = AiProvider.VOLCANO
        assertEquals("doubao-seed-2-0-mini-260428", volc.defaultLlmModel)
        assertEquals(6, volc.llmModels.size)
        assertEquals(listOf("seed-tts-2.0"), volc.ttsModels)
        assertEquals("zh_female_vv_uranus_bigtts", volc.defaultVoice)
        assertEquals(6, volc.voices.size)
        assertTrue(volc.voicePrefix == null)
    }

    @Test
    fun `tts and llm labels strip org prefixes`() {
        assertEquals("MOSS-TTSD-v0.5", AiProvider.SILICONFLOW.ttsModelLabel("fnlp/MOSS-TTSD-v0.5"))
        assertEquals("CosyVoice2-0.5B", AiProvider.SILICONFLOW.ttsModelLabel("FunAudioLLM/CosyVoice2-0.5B"))
        assertEquals("anna", AiProvider.SILICONFLOW.voiceLabel("FunAudioLLM/CosyVoice2-0.5B:anna"))
        assertEquals("zh_female_vv_uranus_bigtts", AiProvider.VOLCANO.voiceLabel("zh_female_vv_uranus_bigtts"))
    }

    // ── 音色引用拼装（MOSS 与 CosyVoice 共用 CosyVoice 引用，真机实测）──
    @Test
    fun `voice ref composition for siliconflow`() {
        // 短名 → 补 CosyVoice 引用前缀（MOSS-TTSD 也收这个格式）
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:anna",
            composeVoiceRef(AiProvider.SILICONFLOW, "anna"),
        )
        // 完整引用（旧 prefs 的存储形态）原样
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:alex",
            composeVoiceRef(AiProvider.SILICONFLOW, "FunAudioLLM/CosyVoice2-0.5B:alex"),
        )
        // 自定义克隆音色 URI（speech:…）原样
        assertEquals("speech:my-clone", composeVoiceRef(AiProvider.SILICONFLOW, "speech:my-clone"))
        assertEquals("", composeVoiceRef(AiProvider.SILICONFLOW, " "))
    }

    @Test
    fun `voice resolution falls back to vendor default`() {
        assertEquals(
            "FunAudioLLM/CosyVoice2-0.5B:anna",
            resolveVoice(AiProvider.SILICONFLOW, ""),
        )
        assertEquals(
            "zh_female_vv_uranus_bigtts",
            resolveVoice(AiProvider.VOLCANO, ""),
        )
        assertEquals(
            "zh_male_m191_uranus_bigtts",
            resolveVoice(AiProvider.VOLCANO, "zh_male_m191_uranus_bigtts"),
        )
    }

    // ── 模型默认值解析 ────────────────────────────────────────────────────
    @Test
    fun `model resolution falls back to vendor defaults`() {
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", resolveLlmModel(AiProvider.SILICONFLOW, ""))
        assertEquals("deepseek-ai/DeepSeek-V3", resolveLlmModel(AiProvider.SILICONFLOW, "deepseek-ai/DeepSeek-V3"))
        assertEquals("doubao-seed-2-0-mini-260428", resolveLlmModel(AiProvider.VOLCANO, "  "))
        assertEquals("seed-tts-2.0", resolveTtsModel(AiProvider.VOLCANO, ""))
        assertEquals("FunAudioLLM/CosyVoice2-0.5B", resolveTtsModel(AiProvider.SILICONFLOW, "FunAudioLLM/CosyVoice2-0.5B"))
    }

    // ── 跨服务商防泄漏（真机实测切火山后 DeepSeek-V3 残留被原样发给 Ark）──
    @Test
    fun `out-of-catalog stored values fall back to the new vendor defaults`() {
        // 切火山：硅基流动的模型/音色不再透传，落火山默认
        assertEquals("doubao-seed-2-0-mini-260428", resolveLlmModel(AiProvider.VOLCANO, "deepseek-ai/DeepSeek-V3"))
        assertEquals("seed-tts-2.0", resolveTtsModel(AiProvider.VOLCANO, "FunAudioLLM/CosyVoice2-0.5B"))
        assertEquals(
            "zh_female_vv_uranus_bigtts",
            resolveVoice(AiProvider.VOLCANO, "FunAudioLLM/CosyVoice2-0.5B:alex"),
        )
        // 反向：火山的值落到硅基流动默认
        assertEquals("deepseek-ai/DeepSeek-V4-Flash", resolveLlmModel(AiProvider.SILICONFLOW, "doubao-seed-2-1-pro-260915"))
        assertEquals("fnlp/MOSS-TTSD-v0.5", resolveTtsModel(AiProvider.SILICONFLOW, "seed-tts-2.0"))
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", resolveVoice(AiProvider.SILICONFLOW, "zh_male_m191_uranus_bigtts"))
    }

    @Test
    fun `custom clone voice uri survives resolution`() {
        assertEquals("speech:my-clone", resolveVoice(AiProvider.SILICONFLOW, "speech:my-clone"))
        assertEquals("FunAudioLLM/CosyVoice2-0.5B:anna", resolveVoice(AiProvider.SILICONFLOW, "anna"))
    }

    // ── 旧 prefs 迁移推断（单服务商时代的 baseUrl → 服务商）───────────────
    @Test
    fun `legacy base url infers provider`() {
        assertEquals(
            AiProvider.SILICONFLOW,
            inferProviderFromBaseUrl("https://api.siliconflow.cn/v1"),
        )
        assertEquals(
            AiProvider.VOLCANO,
            inferProviderFromBaseUrl("https://ark.cn-beijing.volces.com/api/v3"),
        )
        assertEquals(AiProvider.SILICONFLOW, inferProviderFromBaseUrl(""))
    }

    // ── 配置完整性：两个 key 都在才算配置好；模型/音色留空走默认 ──────────
    @Test
    fun `isConfigured requires llm and tts keys`() {
        val base = AiChatPrefs(provider = AiProvider.SILICONFLOW, apiKeySiliconflow = "sk-1")
        assertTrue(base.isConfigured) // 模型/音色留空 = 默认值，仍算配置完成

        // TTS 独立到火山但豆包语音 key 缺失 → 不算配置完成（会话装配会缺 TTS 凭据）
        val independentNoVolcanoTtsKey = base.copy(ttsSameProvider = false, ttsProvider = AiProvider.VOLCANO)
        assertFalse(independentNoVolcanoTtsKey.isConfigured)
        assertTrue(
            independentNoVolcanoTtsKey.copy(apiKeyVolcanoTts = "volc-tts-1").isConfigured,
        )

        // 大模型切火山缺方舟 key 同理（同服务商勾选时语音链路还要豆包语音 key）
        val volcanoLlm = base.copy(provider = AiProvider.VOLCANO)
        assertFalse(volcanoLlm.isConfigured)
        assertFalse(volcanoLlm.copy(apiKeyVolcano = "ark-1").isConfigured) // 还缺豆包语音 key
        assertTrue(
            volcanoLlm.copy(apiKeyVolcano = "ark-1", apiKeyVolcanoTts = "volc-tts-1").isConfigured,
        )
    }

    @Test
    fun `tts provider resolution and key routing`() {
        // 火山语音与火山大模型是两把互不通用的 key（豆包语音 vs 方舟 Ark）
        val prefs = AiChatPrefs(
            provider = AiProvider.VOLCANO,
            apiKeySiliconflow = "sk-1",
            apiKeyVolcano = "ark-1",
            apiKeyVolcanoTts = "volc-tts-1",
            ttsSameProvider = true,
        )
        assertEquals(AiProvider.VOLCANO, prefs.ttsProviderResolved)
        assertEquals("ark-1", prefs.apiKeyFor(prefs.provider)) // 大模型链路 = 方舟 key
        assertEquals("volc-tts-1", prefs.apiKeyForTts()) // 语音链路 = 豆包语音 key

        val sfTts = prefs.copy(ttsSameProvider = false, ttsProvider = AiProvider.SILICONFLOW)
        assertEquals(AiProvider.SILICONFLOW, sfTts.ttsProviderResolved)
        assertEquals("sk-1", sfTts.apiKeyForTts()) // 硅基流动一份 key 三服务共用
    }

    // ── 火山单字段 key 的迁移拆分（ark- 前缀=方舟，UUID 形=豆包语音）──────
    @Test
    fun `legacy volcano key splits by format`() {
        assertEquals("ark-abc" to "", splitLegacyVolcanoKey("ark-abc"))
        assertEquals("" to "020b5acf-808a", splitLegacyVolcanoKey("020b5acf-808a"))
        assertEquals("" to "", splitLegacyVolcanoKey("  "))
        // 大小写不敏感、去空白
        assertEquals("ARK-xyz" to "", splitLegacyVolcanoKey("  ARK-xyz "))
    }

    @Test
    fun `key field labels follow the service`() {
        assertEquals("硅基流动 API Key", AiProvider.SILICONFLOW.llmKeyLabel)
        assertEquals("火山方舟 API Key（大模型用）", AiProvider.VOLCANO.llmKeyLabel)
        assertEquals("豆包语音 API Key（语音合成用）", AiProvider.VOLCANO.ttsKeyLabel)
        // 硅基流动一家一把 key：TTS 字段只在与大模型不同服务商时出现，标签带用途后缀
        assertEquals("硅基流动 API Key（语音合成用）", AiProvider.SILICONFLOW.ttsKeyLabel)
    }

    // ── 视觉模型清单（视频模式准入门控；2026-10-03 真实请求核验）──────────
    @Test
    fun `vision catalog matches measured model capabilities`() {
        val sf = AiProvider.SILICONFLOW
        // DeepSeek 系真机实测明确拒图（"The model is not a VLM"）；Qwen 两款收图
        assertTrue("Qwen/Qwen3.8-27B" in sf.visionLlmModels)
        assertTrue("Qwen/Qwen3-VL-32B-Instruct" in sf.visionLlmModels)
        assertFalse("deepseek-ai/DeepSeek-V4-Flash" in sf.visionLlmModels)
        assertFalse("deepseek-ai/DeepSeek-V3" in sf.visionLlmModels)
        // 清单里的每个视觉模型都必须在大模型清单内（门控经 resolveLlmModel 归位）
        assertTrue(sf.visionLlmModels.all { it in sf.llmModels })

        val volc = AiProvider.VOLCANO
        // 火山 doubao 系 chat 模型全部真看图（同图描述正确）
        assertEquals(
            setOf(
                "doubao-seed-2-0-mini-260428",
                "doubao-seed-2-1-turbo-260628",
                "doubao-seed-2-1-pro-260915",
                "doubao-seed-character-260628",
            ),
            volc.visionLlmModels.toSet(),
        )
        // deepseek-v4-flash-ga 是假视觉（收 image_url 但答 image data incomplete）、
        // seedream 是图像生成模型——都不得进视觉清单
        assertFalse("deepseek-v4-flash-ga-260731" in volc.visionLlmModels)
        assertFalse("doubao-seedream-5-0-pro-260628" in volc.visionLlmModels)
        assertTrue(volc.visionLlmModels.all { it in volc.llmModels })
    }

    @Test
    fun `isVisionLlm resolves through resolveLlmModel first`() {
        // 留空 = 默认：硅基流动默认 DeepSeek-V4-Flash 不支持视觉（视频模式要手动切 Qwen）
        assertFalse(isVisionLlm(AiProvider.SILICONFLOW, ""))
        assertTrue(isVisionLlm(AiProvider.SILICONFLOW, "Qwen/Qwen3.8-27B"))
        // 火山默认就是视觉模型（视频模式开箱即用）
        assertTrue(isVisionLlm(AiProvider.VOLCANO, ""))
        // 跨服务商残留值先归位再判定：SF 的 DeepSeek 残留到火山 → 火山默认（视觉），
        // 但 SF 收到火山的值 → 落 SF 默认（非视觉）——不会把残留值误判成清单内视觉模型
        assertTrue(isVisionLlm(AiProvider.VOLCANO, "deepseek-ai/DeepSeek-V3"))
        assertFalse(isVisionLlm(AiProvider.SILICONFLOW, "doubao-seed-2-0-mini-260428"))
        // 大小写敏感：不在清单内就是不支持，不模糊匹配
        assertFalse(isVisionLlm(AiProvider.SILICONFLOW, "qwen/qwen3.8-27b"))
    }

    // ── 请求体专属参数（关深度思考 = 首句延迟治理，2026-10-04）────────────
    @Test
    fun `volcano doubao-seed models get thinking disabled`() {
        val body = llmExtraBody(AiProvider.VOLCANO, "doubao-seed-2-0-mini-260428")!!
        val thinking = body["thinking"] as kotlinx.serialization.json.JsonObject
        assertEquals("disabled", (thinking["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        // character 模型同族同样关
        val character = llmExtraBody(AiProvider.VOLCANO, "doubao-seed-character-260628")!!
        assertEquals(
            "disabled",
            ((character["thinking"] as kotlinx.serialization.json.JsonObject)["type"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        // 火山 deepseek 系不认识 thinking 参数：不发，避免严格端点 400
        assertEquals(null, llmExtraBody(AiProvider.VOLCANO, "deepseek-v4-flash-ga-260731"))
    }

    @Test
    fun `siliconflow qwen3 models get enable_thinking false`() {
        // Qwen3 混合推理模型默认开思考，必须显式 false（用户实测千问慢的根因）
        val body = llmExtraBody(AiProvider.SILICONFLOW, "Qwen/Qwen3.8-27B")!!
        assertEquals("false", (body["enable_thinking"] as kotlinx.serialization.json.JsonPrimitive).content)
        // Qwen3-VL-Instruct 本身非思考模型：参数无害 no-op，一并带上
        val vl = llmExtraBody(AiProvider.SILICONFLOW, "Qwen/Qwen3-VL-32B-Instruct")!!
        assertEquals("false", (vl["enable_thinking"] as kotlinx.serialization.json.JsonPrimitive).content)
        // SF 的 deepseek 系不发（DeepSeek-V3 是纯 chat 模型，参数不认识）
        assertEquals(null, llmExtraBody(AiProvider.SILICONFLOW, "deepseek-ai/DeepSeek-V3"))
        assertEquals(null, llmExtraBody(AiProvider.SILICONFLOW, "deepseek-ai/DeepSeek-V4-Flash"))
    }

    @Test
    fun `thinking params never leak across providers`() {
        // 火山的 thinking 参数不发给硅基流动，反之亦然
        assertEquals(null, llmExtraBody(AiProvider.SILICONFLOW, "doubao-seed-2-0-mini-260428"))
        assertEquals(null, llmExtraBody(AiProvider.VOLCANO, "Qwen/Qwen3.8-27B"))
        assertEquals(null, llmExtraBody(AiProvider.SILICONFLOW, ""))
    }
}

// ── TTS 引擎与 Edge-TTS 目录（任务 6）────────────────────────────────────

class EdgeTtsCatalogTest {

    @Test
    fun `edge voice resolution validates against catalog and falls back to default`() {
        assertEquals("zh-CN-XiaoxiaoNeural", resolveEdgeVoice(""))
        assertEquals("zh-CN-XiaoxiaoNeural", resolveEdgeVoice("  "))
        // 目录内音色原样放行
        assertEquals("zh-CN-YunxiNeural", resolveEdgeVoice("zh-CN-YunxiNeural"))
        // OpenAI 兼容引擎残留的音色引用不在目录 → 落默认（跨引擎防泄漏）
        assertEquals(
            "zh-CN-XiaoxiaoNeural",
            resolveEdgeVoice("FunAudioLLM/CosyVoice2-0.5B:anna"),
        )
        assertEquals("zh-CN-XiaoxiaoNeural", resolveEdgeVoice("zh_female_vv_uranus_bigtts"))
    }

    @Test
    fun `edge catalog is verified and unique`() {
        assertTrue("音色目录不应为空", EdgeTtsCatalog.voices.isNotEmpty())
        assertEquals(
            "voice id 有重复",
            EdgeTtsCatalog.voices.size,
            EdgeTtsCatalog.voices.map { it.first }.toSet().size,
        )
        // 默认音色必须在目录里（isConfigured 与合成可用性的前提）
        assertTrue(
            "默认音色必须在目录里",
            EdgeTtsCatalog.voices.any { it.first == EdgeTtsCatalog.DEFAULT_VOICE },
        )
    }

    @Test
    fun `edge tts engine needs no key and provider engine does`() {
        // Edge-TTS：不填任何 Key 也算 TTS 就绪（开源友好，任务 6 验收前提）
        val edge = AiChatPrefs(ttsEngine = TtsEngine.EDGE)
        assertTrue(edge.ttsReady)

        // OpenAI 兼容引擎：无 Key 不就绪；填了 Key 就绪
        val openAi = AiChatPrefs(ttsEngine = TtsEngine.OPENAI_COMPATIBLE)
        assertFalse(openAi.ttsReady)
        assertTrue(openAi.copy(apiKeySiliconflow = "sk-x").ttsReady)

        // TTS 引擎不影响大模型侧的 Key 判定
        assertFalse(edge.isConfigured) // 只有 TTS 就绪，LLM 还没 Key
        assertTrue(
            edge.copy(apiKeyVolcano = "ark-x", provider = AiProvider.VOLCANO).isConfigured,
        )
    }
}

// ── OpenRouter（面向海外用户的第三服务商，2026-10-05）────────────────────

class OpenRouterProviderTest {

    private val or = AiProvider.OPENROUTER

    @Test
    fun `openrouter catalog is verified and locked`() {
        assertEquals("OpenRouter", or.label)
        assertEquals("https://openrouter.ai/api/v1", or.baseUrl)
        // 清单首位 = 默认模型（四款 host 实测：64px 红图逐个过=真视觉）
        assertEquals(
            listOf(
                "google/gemini-3.8-flash",
                "openai/gpt-6-luna",
                "qwen/qwen3.7-flash",
                "anthropic/claude-sonnet-4.6",
            ),
            or.llmModels,
        )
        assertEquals("google/gemini-3.8-flash", or.defaultLlmModel)
        // 全部实测真视觉（视频模式开箱即用）
        assertEquals(or.llmModels.toSet(), or.visionLlmModels.toSet())
        // /audio/speech 只认 mp3/pcm：单 TTS 模型防音色跨模型泄漏（同火山模式）
        assertEquals(listOf("mistralai/voxtral-mini-tts-2603"), or.ttsModels)
        assertEquals("mistralai/voxtral-mini-tts-2603", or.defaultTtsModel)
        assertEquals("en_paul_neutral", or.defaultVoice)
        // 音色 = Voxtral supported_voices（API 在册）精选子集，无引用前缀
        assertTrue(or.voicePrefix == null)
        assertTrue(or.voices.isNotEmpty())
        assertEquals(or.voices.size, or.voices.toSet().size)
        assertTrue(or.defaultVoice in or.voices)
    }

    @Test
    fun `openrouter voice resolution falls back to default on cross-engine residue`() {
        assertEquals("en_paul_neutral", resolveVoice(or, ""))
        assertEquals("gb_jane_curious", resolveVoice(or, "gb_jane_curious"))
        // 硅基流动/火山的音色残留一律落默认（composeVoiceRef 无前缀原样返回→不在清单）
        assertEquals("en_paul_neutral", resolveVoice(or, "FunAudioLLM/CosyVoice2-0.5B:anna"))
        assertEquals("en_paul_neutral", resolveVoice(or, "zh_female_vv_uranus_bigtts"))
    }

    @Test
    fun `openrouter base url infers provider for migration`() {
        assertEquals(or, inferProviderFromBaseUrl("https://openrouter.ai/api/v1"))
        assertEquals(or, inferProviderFromBaseUrl("https://OPENROUTER.AI/api/v1"))
    }

    @Test
    fun `openrouter qwen models get unified reasoning disabled`() {
        // qwen3.7-flash 混合推理默认开思考：OpenRouter 统一参数实测有效
        // （一句话 235 思考 token → 2）
        val body = llmExtraBody(or, "qwen/qwen3.7-flash")!!
        val reasoning = body["reasoning"] as kotlinx.serialization.json.JsonObject
        assertEquals("false", (reasoning["enabled"] as kotlinx.serialization.json.JsonPrimitive).content)
        // gemini-3.8-flash / gpt-5-nano 系 reasoning 强制开启，发关思考直接
        // 400 "Reasoning is mandatory"（2026-10-05 实测）——绝不发参数；
        // claude / gpt-6-luna 默认不思考，也不发
        assertEquals(null, llmExtraBody(or, "google/gemini-3.8-flash"))
        assertEquals(null, llmExtraBody(or, "openai/gpt-6-luna"))
        assertEquals(null, llmExtraBody(or, "anthropic/claude-sonnet-4.6"))
        assertEquals(null, llmExtraBody(or, ""))
        // 关思考方言不跨服务商泄漏：OpenRouter 的 qwen 小写前缀不命中硅基流动
        // 的 "Qwen/Qwen3" 分支，反之亦然
        assertEquals(null, llmExtraBody(AiProvider.SILICONFLOW, "qwen/qwen3.7-flash"))
        assertEquals(null, llmExtraBody(or, "Qwen/Qwen3.8-27B"))
    }

    @Test
    fun `asr provider follows llm provider except volcano falls back to siliconflow`() {
        assertEquals(AiProvider.SILICONFLOW, asrProviderFor(AiProvider.SILICONFLOW))
        assertEquals(or, asrProviderFor(or))
        assertEquals(AiProvider.SILICONFLOW, asrProviderFor(AiProvider.VOLCANO))
    }

    @Test
    fun `asr explicit model options per provider`() {
        assertEquals(listOf("Qwen/Qwen3-ASR-1.7B"), asrExplicitAsrModels(AiProvider.SILICONFLOW))
        assertEquals(listOf("openai/whisper-large-v3"), asrExplicitAsrModels(or))
        assertEquals(emptyList<String>(), asrExplicitAsrModels(AiProvider.VOLCANO))
    }

    @Test
    fun `openrouter is configured with its single key`() {
        val base = AiChatPrefs(provider = or)
        assertFalse(base.isConfigured)
        assertTrue(base.copy(apiKeyOpenrouter = "sk-or-v1-x").isConfigured)
        // TTS 同服务商共用这把 key；key 路由互不串
        assertEquals("sk-or-v1-x", base.copy(apiKeyOpenrouter = "sk-or-v1-x").apiKeyForTts())
        assertEquals("sk-or-v1-x", base.copy(apiKeyOpenrouter = "sk-or-v1-x").apiKeyFor(or))
    }

    @Test
    fun `openrouter key field labels follow the service`() {
        assertEquals("OpenRouter API Key", or.llmKeyLabel)
        assertEquals("sk-or-v1-...", or.llmKeyHint)
        assertEquals("OpenRouter API Key（语音合成用）", or.ttsKeyLabel)
    }
}
