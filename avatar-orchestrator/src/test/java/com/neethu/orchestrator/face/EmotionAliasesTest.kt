package com.neethu.orchestrator.face

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `english self-invented names resolve to canonical`() {
        // 真机踩过 <act:laugh:0.5>：情绪词既不在动作目录、也不在 13 情绪词表。
        assertEquals("happy", EmotionAliases.resolveEmotion("laugh"))
        assertEquals("happy", EmotionAliases.resolveEmotion("excited"))
        assertEquals("happy", EmotionAliases.resolveEmotion("Laughter"))
        assertEquals("sad", EmotionAliases.resolveEmotion("crying"))
        assertEquals("angry", EmotionAliases.resolveEmotion("mad"))
        assertEquals("surprised", EmotionAliases.resolveEmotion("shocked"))
        assertEquals("worried", EmotionAliases.resolveEmotion("anxious"))
        assertEquals("sleepy", EmotionAliases.resolveEmotion("tired"))
    }

    @Test
    fun `resolveEmotion covers both tables and returns null off-table`() {
        // zh 表同一入口可达：跨类改写（<act:轻笑>）不用第二张表
        assertEquals("happy", EmotionAliases.resolveEmotion("轻笑"))
        // canonical 名本身不是别名：canonical 路径在消费方先走，表不参与
        assertNull(EmotionAliases.resolveEmotion("happy"))
        assertNull(EmotionAliases.resolveEmotion("unicorn"))
    }

    @Test
    fun `both alias tables map only to canonical emotion names`() {
        // 别名表值集与 EmotionBlender 组合表情键集锁死——canonical 集合变动时
        // 这里先红，防止表指向不存在的组合表情
        val canonical = EmotionBlender(CoroutineScope(Dispatchers.Unconfined)).defs.keys
        assertTrue(EmotionAliases.zhToCanonical.values.all { it in canonical })
        assertTrue(EmotionAliases.enToCanonical.values.all { it in canonical })
    }
}
