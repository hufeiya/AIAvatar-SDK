package com.neethu.aiadapter.emotion

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkerEmotionExtractorTest {

    @Test
    fun `single marker extracted and stripped`() {
        val ex = MarkerEmotionExtractor()
        val result = ex.feed("<|emotion:happy|>你好呀")
        assertEquals("你好呀", result.cleanText)
        assertEquals(1, result.cues.size)
        assertEquals("happy", result.cues[0].name)
        assertEquals(1.0f, result.cues[0].intensity, 0.001f)
    }

    @Test
    fun `intensity parsed and clamped`() {
        val ex = MarkerEmotionExtractor()
        val r1 = ex.feed("<|emotion:sad:0.4|>嗯")
        assertEquals(0.4f, r1.cues[0].intensity, 0.001f)
        ex.reset()
        val r2 = ex.feed("<|emotion:sad:1.7|>嗯")
        assertEquals(1.0f, r2.cues[0].intensity, 0.001f)
        ex.reset()
        val r3 = ex.feed("<|emotion:sad:-2|>嗯")
        assertEquals(0.0f, r3.cues[0].intensity, 0.001f)
    }

    @Test
    fun `marker split across deltas is buffered`() {
        val ex = MarkerEmotionExtractor()
        val r1 = ex.feed("好的<|emo")
        assertEquals("好的", r1.cleanText)
        assertEquals(0, r1.cues.size)
        val r2 = ex.feed("tion:angry|>怎么了")
        assertEquals("怎么了", r2.cleanText)
        assertEquals(1, r2.cues.size)
        assertEquals("angry", r2.cues[0].name)
    }

    @Test
    fun `incomplete tail is held back then completed`() {
        val ex = MarkerEmotionExtractor()
        val r1 = ex.feed("文本<|emotion:")
        assertEquals("文本", r1.cleanText)
        assertEquals(0, r1.cues.size)
        val r2 = ex.feed("happy|>结束")
        assertEquals("结束", r2.cleanText)
        assertEquals("happy", r2.cues[0].name)
    }

    @Test
    fun `unknown angle-bracket tokens pass through`() {
        val ex = MarkerEmotionExtractor()
        val result = ex.feed("<|foo|>bar")
        assertEquals("<|foo|>bar", result.cleanText)
        assertEquals(0, result.cues.size)
    }

    @Test
    fun `flush emits pending text and drops dangling marker`() {
        val ex = MarkerEmotionExtractor()
        val fed = ex.feed("普通文本<|emotion:")
        assertEquals("普通文本", fed.cleanText)
        val result = ex.flush()
        assertEquals("", result.cleanText)
        assertEquals(0, result.cues.size)
        // next feed starts fresh
        assertEquals("abc", ex.feed("abc").cleanText)
    }

    @Test
    fun `flush after a complete marker emits nothing new`() {
        val ex = MarkerEmotionExtractor()
        val fed = ex.feed("<|emotion:relaxed:0.5|>")
        assertEquals(1, fed.cues.size)
        val result = ex.flush()
        assertEquals("", result.cleanText)
        assertEquals(0, result.cues.size)
    }

    @Test
    fun `multiple markers and text interleave in order`() {
        val ex = MarkerEmotionExtractor()
        val r1 = ex.feed("<|emotion:happy|>哈")
        val r2 = ex.feed("哈<|emotion:neutral|>好")
        assertEquals("哈哈好", r1.cleanText + r2.cleanText)
        assertEquals(2, r1.cues.size + r2.cues.size)
        assertEquals("happy", r1.cues[0].name)
        assertEquals("neutral", r2.cues[0].name)
    }

    @Test
    fun `reset clears buffer`() {
        val ex = MarkerEmotionExtractor()
        ex.feed("<|emo")
        ex.reset()
        assertEquals("", ex.flush().cleanText)
    }
}
