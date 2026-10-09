package com.neethu.orchestrator.face

import org.junit.Assert.assertEquals
import org.junit.Test

class EmotionAliasesTest {

    @Test
    fun `chinese aliases resolve to canonical names`() {
        // 真机踩过的两个：<emo:轻笑>/<emo:大笑> 漏进 TTS 后，即使提取器拦下，
        // 没有别名表也只会在 dispatchCues 被静默丢弃（意图白白损失）。
        assertEquals("happy", EmotionAliases.resolve("轻笑"))
        assertEquals("happy", EmotionAliases.resolve("大笑"))
        assertEquals("happy", EmotionAliases.resolve("微笑"))
        assertEquals("smug", EmotionAliases.resolve("坏笑"))
        assertEquals("shy", EmotionAliases.resolve("害羞"))
        assertEquals("neutral", EmotionAliases.resolve("面无表情"))
    }

    @Test
    fun `canonical and unknown names pass through lowercased`() {
        assertEquals("happy", EmotionAliases.resolve("happy"))
        assertEquals("relaxed", EmotionAliases.resolve("ReLaXeD"))
        assertEquals("excited", EmotionAliases.resolve("excited"))
    }
}
