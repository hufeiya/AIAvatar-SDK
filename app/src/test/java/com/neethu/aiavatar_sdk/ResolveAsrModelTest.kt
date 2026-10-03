package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 任务 4：ASR 模型留空时的端点推断——硅基流动默认 Qwen3-ASR，其他默认
 * whisper-1；用户显式配置永远优先。
 */
class ResolveAsrModelTest {

    @Test
    fun `siliconflow endpoint defaults to Qwen3-ASR`() {
        assertEquals(
            "Qwen/Qwen3-ASR-1.7B",
            resolveAsrModel("https://api.siliconflow.cn/v1", ""),
        )
    }

    @Test
    fun `siliconflow match is case-insensitive`() {
        assertEquals(
            "Qwen/Qwen3-ASR-1.7B",
            resolveAsrModel("https://API.SiliconFlow.com/v1", "  "),
        )
    }

    @Test
    fun `other endpoints default to whisper-1`() {
        assertEquals("whisper-1", resolveAsrModel("https://api.openai.com/v1", ""))
        assertEquals("whisper-1", resolveAsrModel("", ""))
    }

    @Test
    fun `explicitly configured model wins over the endpoint default`() {
        assertEquals(
            "FunAudioLLM/SenseVoiceSmall",
            resolveAsrModel("https://api.siliconflow.cn/v1", "FunAudioLLM/SenseVoiceSmall"),
        )
        assertEquals("my/model", resolveAsrModel("", " my/model "))
    }
}
