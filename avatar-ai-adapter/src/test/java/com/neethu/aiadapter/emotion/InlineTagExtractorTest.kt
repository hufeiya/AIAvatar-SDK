package com.neethu.aiadapter.emotion

import com.neethu.aiadapter.api.TagCue
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineTagExtractorTest {

    // ── new-family tags ───────────────────────────────────────────────────

    @Test
    fun `emotion tag extracted and stripped`() {
        val ex = InlineTagExtractor()
        val result = ex.feed("<emo:happy>你好呀")
        assertEquals("你好呀", result.cleanText)
        assertEquals(listOf(TagCue.Emotion("happy", 1.0f)), result.cues)
    }

    @Test
    fun `emotion intensity parsed and clamped`() {
        val ex = InlineTagExtractor()
        assertEquals(0.4f, ex.feed("<emo:sad:0.4>嗯").cues[0].let { (it as TagCue.Emotion).intensity }, 0.001f)
        ex.reset()
        assertEquals(1.0f, ex.feed("<emo:sad:1.7>嗯").cues[0].let { (it as TagCue.Emotion).intensity }, 0.001f)
        ex.reset()
        assertEquals(0.0f, ex.feed("<emo:sad:-2>嗯").cues[0].let { (it as TagCue.Emotion).intensity }, 0.001f)
    }

    @Test
    fun `action and camera tags extracted`() {
        val ex = InlineTagExtractor()
        val r1 = ex.feed("<act:wave>来，握个手！")
        assertEquals("来，握个手！", r1.cleanText)
        assertEquals(listOf(TagCue.Action("wave")), r1.cues)

        ex.reset()
        val r2 = ex.feed("<cam:close_up>看这里")
        assertEquals("看这里", r2.cleanText)
        assertEquals(listOf(TagCue.Camera("close_up")), r2.cues)
    }

    @Test
    fun `tag names are lowercased`() {
        val ex = InlineTagExtractor()
        val r = ex.feed("<ACT:Wave><CAM:Close_Up><EMO:Happy>")
        assertEquals(listOf(TagCue.Action("wave"), TagCue.Camera("close_up"), TagCue.Emotion("happy", 1.0f)), r.cues)
        assertEquals("", r.cleanText)
    }

    // ── CJK tag names（中文模型自造仿写，真机踩过 <emo:轻笑> 漏进 TTS） ──

    @Test
    fun `chinese emotion names extracted instead of leaking into speech`() {
        val ex = InlineTagExtractor()
        val r = ex.feed("你猜<emo:轻笑>怎么着<emo:大笑:0.8>，嘿嘿")
        assertEquals("你猜怎么着，嘿嘿", r.cleanText)
        assertEquals(
            listOf(TagCue.Emotion("轻笑", 1.0f), TagCue.Emotion("大笑", 0.8f)),
            r.cues,
        )
    }

    @Test
    fun `chinese action and camera names extracted`() {
        val ex = InlineTagExtractor()
        val r = ex.feed("<act:挥手><cam:特写>看这里")
        assertEquals("看这里", r.cleanText)
        assertEquals(listOf(TagCue.Action("挥手"), TagCue.Camera("特写")), r.cues)
    }

    @Test
    fun `chinese tag split across deltas is buffered`() {
        val ex = InlineTagExtractor()
        val r1 = ex.feed("好<emo")
        assertEquals("好", r1.cleanText)
        assertEquals(0, r1.cues.size)
        val r2 = ex.feed(":轻笑>呀")
        assertEquals("呀", r2.cleanText)
        assertEquals(TagCue.Emotion("轻笑", 1.0f), r2.cues[0])
    }

    @Test
    fun `off-vocabulary ascii emotion name still extracted as cue`() {
        // 提取器只管形状：词表外的名字（unicorn/excited）必须成 cue 交给下游
        // 裁决（morph 直驱/静默丢弃），绝不能当普通文字漏给 TTS。
        val ex = InlineTagExtractor()
        val r = ex.feed("<emo:excited>哇")
        assertEquals("哇", r.cleanText)
        assertEquals(listOf(TagCue.Emotion("excited", 1.0f)), r.cues)
    }

    // ── legacy protocol stays recognized ─────────────────────────────────

    @Test
    fun `legacy emotion marker still works`() {
        val ex = InlineTagExtractor()
        val r1 = ex.feed("<|emotion:happy|>你好")
        assertEquals("你好", r1.cleanText)
        assertEquals(TagCue.Emotion("happy", 1.0f), r1.cues[0])
        ex.reset()
        val r2 = ex.feed("<|emotion:sad:-2|>嗯")
        assertEquals(0.0f, (r2.cues[0] as TagCue.Emotion).intensity, 0.001f)
    }

    // ── streaming buffering semantics ─────────────────────────────────────

    @Test
    fun `tag split across deltas is buffered`() {
        val ex = InlineTagExtractor()
        val r1 = ex.feed("好的<emo")
        assertEquals("好的", r1.cleanText)
        assertEquals(0, r1.cues.size)
        val r2 = ex.feed(":angry:0.5>怎么了")
        assertEquals("怎么了", r2.cleanText)
        assertEquals(TagCue.Emotion("angry", 0.5f), r2.cues[0])
    }

    @Test
    fun `action tag split mid-name is buffered`() {
        val ex = InlineTagExtractor()
        val r1 = ex.feed("你看<act:wave")
        assertEquals("你看", r1.cleanText)
        val r2 = ex.feed("><cam:full_shot>好")
        assertEquals("好", r2.cleanText)
        assertEquals(listOf(TagCue.Action("wave"), TagCue.Camera("full_shot")), r1.cues + r2.cues)
    }

    @Test
    fun `unknown angle-bracket tokens pass through`() {
        val ex = InlineTagExtractor()
        val result = ex.feed("<|foo|>bar <b>bold</b>")
        assertEquals("<|foo|>bar <b>bold</b>", result.cleanText)
        assertEquals(0, result.cues.size)
    }

    @Test
    fun `stray angle bracket before a real tag does not swallow the tag`() {
        val ex = InlineTagExtractor()
        // "a < b" arrives first (held back, no '>'), then a real tag — the
        // real tag must still be extracted instead of being passed verbatim.
        val r1 = ex.feed("a < b ")
        val r2 = ex.feed("<emo:happy>c")
        assertEquals("a < b c", r1.cleanText + r2.cleanText)
        assertEquals(1, (r1.cues + r2.cues).count { it is TagCue.Emotion })
    }

    @Test
    fun `flush emits pending text and drops dangling tag`() {
        val ex = InlineTagExtractor()
        val fed = ex.feed("普通文本<emo:hap")
        assertEquals("普通文本", fed.cleanText)
        val result = ex.flush()
        assertEquals("", result.cleanText)
        assertEquals(0, result.cues.size)
        // next feed starts fresh
        assertEquals("abc", ex.feed("abc").cleanText)
    }

    @Test
    fun `flush keeps a plain unclosed angle bracket`() {
        val ex = InlineTagExtractor()
        // feed holds "< 3 " back (waiting for a '>'); flush releases it as speech.
        val fed = ex.feed("5 < 3 ")
        val result = ex.flush()
        assertEquals("5 < 3 ", fed.cleanText + result.cleanText)
    }

    @Test
    fun `flush after complete tags emits nothing new`() {
        val ex = InlineTagExtractor()
        val fed = ex.feed("<emo:relaxed:0.5><act:wave>")
        assertEquals(2, fed.cues.size)
        val result = ex.flush()
        assertEquals("", result.cleanText)
        assertEquals(0, result.cues.size)
    }

    @Test
    fun `multiple tags and text interleave in order`() {
        val ex = InlineTagExtractor()
        val r1 = ex.feed("<cam:medium_shot><emo:happy>哈")
        val r2 = ex.feed("哈<act:nod>好")
        assertEquals("哈哈好", r1.cleanText + r2.cleanText)
        assertEquals(3, r1.cues.size + r2.cues.size)
        assertEquals(TagCue.Camera("medium_shot"), r1.cues[0])
        assertEquals(TagCue.Action("nod"), r2.cues[0])
    }

    @Test
    fun `reset clears buffer`() {
        val ex = InlineTagExtractor()
        ex.feed("<emo")
        ex.reset()
        assertEquals("", ex.flush().cleanText)
    }

    @Test
    fun `pathological unclosed bracket drains after cap`() {
        val ex = InlineTagExtractor()
        val long = "<" + "x".repeat(80) + " tail"
        val r = ex.feed(long)
        assertEquals(long, r.cleanText)
    }
}
