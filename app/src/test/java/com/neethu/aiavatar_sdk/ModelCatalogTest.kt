package com.neethu.aiavatar_sdk

import com.neethu.aiadapter.openai.ModelCatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * /models 实时目录 → 下拉选项的过滤与视觉标注（纯函数；目录样本取自
 * 2026-10-18 百炼 176 模型 / 火山 Ark 135 条 / 硅基流动 99 条真响应）。
 */
class ModelCatalogTest {

    private fun entry(
        id: String,
        domain: String? = null,
        status: String? = null,
        input: List<String>? = null,
        output: List<String>? = null,
    ) = ModelCatalogEntry(id, domain, status, input, output)

    // ── 非对话模型过滤（名字兜底）───────────────────────────────────────────
    @Test
    fun `bailian non-chat families are filtered out`() {
        val junk = listOf(
            "qwen3.7-text-embedding", // 向量
            "qwen3.7-text-embedding-flash",
            "qwen3.7-text-rerank", // 排序
            "qwen3-tts-flash", // 合成
            "qwen-audio-3.0-asr-flash", // 识别
            "fun-asr-flash-2026-06-15",
            "qwen3-asr-flash-realtime",
            "MiniMax/speech-2.8-hd", // 第三方合成
            "qwen-image-2.1-pro", // 图像生成
            "z-image-turbo",
            "wan2.7-image-pro",
            "doubao-seedance-2-0-fast-260128",
            "qwen-vl-ocr", // OCR 专项（非通用对话）
            "qwen3.5-ocr",
            "qwen-mt-uni", // 翻译
            "qwen3.5-livetranslate-flash-realtime",
            "qwen-deep-research-2025-12-15", // 研究代理
            "qwen-deep-search-planning",
            "qwen3.8-omni-flash-realtime", // 实时音频专线
            "qwen3-s2s-flash-realtime-2025-09-22",
            "test-sre-gpu-auto-handle", // 内部测试桩
            "sre-gpu-auto-handle",
            "cmaas-qwen3.8-flash", // 企业变体（实测 400）
            "qwen1.5-7b-chat", // 古董代次（工作区 403）
            "qwen-7b-chat",
            "qwen2-0.5b-instruct",
        )
        val leaked = junk.filter { isChatCandidate(AiProvider.BAILIAN, entry(it)) }
        assertTrue("应全部剔除，漏网: $leaked", leaked.isEmpty())
    }

    @Test
    fun `bailian chat models survive the filter`() {
        val chat = listOf(
            "qwen3.8-flash",
            "qwen3-vl-plus",
            "qwen3.8-max",
            "qwen-flash-character", // 角色扮演（实测 200）
            "gui-plus", // chat 实测 200
            "kimi-k3",
            "kimi/kimi-k2.8-preview",
            "deepseek-v4.1-flash",
            "vanchin/deepseek-v4-pro",
            "glm-5.3",
            "ZHIPU/GLM-5.3-Flash",
            "MiniMax-M2.5",
            "stepfun/step-3.7-flash",
            "qwen3-coder-flash",
            "qwen-plus",
            "qwen3.8-omni-flash", // omni 非 realtime = 可对话
        )
        val dropped = chat.filterNot { isChatCandidate(AiProvider.BAILIAN, entry(it)) }
        assertTrue("误杀: $dropped", dropped.isEmpty())
    }

    // ── 火山元数据权威过滤 ──────────────────────────────────────────────────
    @Test
    fun `volcano domain and status are authoritative`() {
        fun v(id: String, domain: String?, status: String?) =
            isChatCandidate(AiProvider.VOLCANO, entry(id, domain, status))
        assertTrue(v("doubao-seed-2-1-pro-260915", "VLM", null))
        assertTrue(v("deepseek-v4-flash-ga-260731", "LLM", null))
        assertTrue(v("qwen3-32b-20250429", "", null))
        // domain 空但 status=Shutdown（已下架老 doubao）→ 剔除
        assertFalse(v("doubao-lite-128k-240428", "", "Shutdown"))
        // 非 LLM/VLM 域全部剔除（Retiring 也一样，只是尚未关停）
        assertFalse(v("doubao-embedding-text-240515", "Embedding", "Retiring"))
        assertFalse(v("doubao-seedance-1-0-pro-250528", "VideoGeneration", "Retiring"))
        assertFalse(v("doubao-seedream-4-0-250828", "ImageGeneration", "Retiring"))
        assertFalse(v("doubao-smart-router-250928", "Router", null))
        assertFalse(v("doubao-seed3d-2-0-260328", "3DGeneration", null))
    }

    @Test
    fun `openrouter keeps only text-output models`() {
        assertTrue(
            isChatCandidate(
                AiProvider.OPENROUTER,
                entry("google/gemini-3.8-flash", input = listOf("text", "image"), output = listOf("text")),
            ),
        )
        // 只出图（image-only）不进对话下拉
        assertFalse(
            isChatCandidate(
                AiProvider.OPENROUTER,
                entry("some/image-model", input = listOf("text"), output = listOf("image")),
            ),
        )
        // 无元数据条目靠名字过滤兜底
        assertTrue(isChatCandidate(AiProvider.OPENROUTER, entry("openrouter/free")))
    }

    // ── 视觉标注判定链 ──────────────────────────────────────────────────────
    @Test
    fun `vision tagging follows metadata then static then heuristic`() {
        // 火山 domain=VLM 官方口径 → 视觉；domain=LLM → 官方说纯文本，启发式不翻案
        assertTrue(isVisionCandidate(AiProvider.VOLCANO, entry("doubao-seed-2-1-pro-260915", "VLM", null)))
        assertFalse(isVisionCandidate(AiProvider.VOLCANO, entry("deepseek-v4-flash-ga-260731", "LLM", null)))
        // domain 空串非 null：官方没说 → 走后续判定链（qwen3-32b 无词元 → 非视觉）
        assertFalse(isVisionCandidate(AiProvider.VOLCANO, entry("qwen3-32b-20250429", "", null)))
        // OpenRouter input_modalities 含 image → 视觉
        assertTrue(
            isVisionCandidate(
                AiProvider.OPENROUTER,
                entry("google/gemini-3.8-flash", input = listOf("text", "image")),
            ),
        )
        assertFalse(
            isVisionCandidate(
                AiProvider.OPENROUTER,
                entry("some/text-model", input = listOf("text")),
            ),
        )
        // 百炼：静态实测清单优先，清单外走命名启发式（omni/vl 词元）
        assertTrue(isVisionCandidate(AiProvider.BAILIAN, entry("qwen3.8-flash")))
        assertTrue(isVisionCandidate(AiProvider.BAILIAN, entry("qwen3-vl-plus")))
        assertTrue(isVisionCandidate(AiProvider.BAILIAN, entry("qwen3.8-omni-flash")))
        assertFalse(isVisionCandidate(AiProvider.BAILIAN, entry("kimi-k3")))
        // 硅基流动静态清单（DeepSeek 系拒图不标）
        assertTrue(isVisionCandidate(AiProvider.SILICONFLOW, entry("Qwen/Qwen3-VL-32B-Instruct")))
        assertFalse(isVisionCandidate(AiProvider.SILICONFLOW, entry("deepseek-ai/DeepSeek-V4-Flash")))
    }

    // ── 选项合成：目录序在前 + 静态清单并集兜底 ─────────────────────────────
    @Test
    fun `options merge fetched entries with the static fallback list`() {
        val options = chatModelOptions(
            AiProvider.BAILIAN,
            listOf(
                entry("glm-5.3"),
                entry("qwen3.7-text-embedding"), // 过滤掉
                entry("qwen3.8-flash"),
                entry("qwen3.8-flash"), // 去重
            ),
        )
        // 目录序在前（qwen3.8-flash 在百炼静态清单里，不重复出现）；
        // 静态清单兜底并集排在其后（目录缺项时仍可见手工验证过的模型）
        assertEquals(
            listOf("glm-5.3", "qwen3.8-flash") + AiProvider.BAILIAN.llmModels.filter { it != "qwen3.8-flash" },
            options.map { it.id },
        )
        assertTrue(options.first { it.id == "qwen3.8-flash" }.vision)
        assertTrue("kimi-k3" in options.map { it.id })
    }

    @Test
    fun `empty fetched list degrades to the static catalog`() {
        val options = chatModelOptions(AiProvider.BAILIAN, emptyList())
        assertEquals(AiProvider.BAILIAN.llmModels, options.map { it.id })
        assertTrue(options.first { it.id == "qwen3.8-flash" }.vision)
    }

    // ── 缓存语义：内存缓存（重启失效）+ 空 key 不发请求 ─────────────────────
    @Test
    fun `cache stores per provider and clears across processes by design`() {
        val provider = AiProvider.DEEPSEEK
        ModelCatalogCache.put(provider, listOf(entry("deepseek-flash"), entry("deepseek-v99")))
        assertTrue(ModelCatalogCache.has(provider))
        assertEquals(setOf("deepseek-flash", "deepseek-v99"), ModelCatalogCache.idsFor(provider))
        // 每服务商独立
        assertFalse(ModelCatalogCache.has(AiProvider.BAILIAN))
        assertTrue(ModelCatalogCache.idsFor(AiProvider.BAILIAN).isEmpty())
        ModelCatalogCache.put(provider, emptyList())
        assertEquals(emptySet<String>(), ModelCatalogCache.idsFor(provider))
    }
}
