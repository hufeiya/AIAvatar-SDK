package com.neethu.orchestrator.chunker

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceChunkerTest {

    /** Deterministic counter: each CJK char = 1 word; latin runs = 1 word. */
    private val cjkCounter = SentenceChunker.WordCounter { text ->
        var count = 0
        var inLatin = false
        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FFF || ch.code in 0x3000..0x30FF -> { count++; inLatin = false }
                Character.isLetterOrDigit(ch) -> if (!inLatin) { count++; inLatin = true }
                else -> inLatin = false
            }
        }
        count
    }

    private fun chunker(boost: Int = 2, minWords: Int = 4, maxWords: Int = 12) =
        SentenceChunker(SentenceChunker.Options(boost, minWords, maxWords), cjkCounter)

    @Test
    fun `hard punctuation cuts immediately`() {
        val c = chunker()
        val out = c.feed("你好世界。今天很好！")
        assertEquals(listOf("你好世界。", "今天很好！"), out)
        assertEquals(emptyList<String>(), c.flush())
    }

    @Test
    fun `soft punctuation cuts only within boost budget and min words`() {
        val c = chunker()
        // chunk #0 and #1: soft cut allowed (boost=2)
        // chunk #2: boost exhausted → the third and fourth groups merge into one sentence
        val out = c.feed("一二三四，一二三四，一二三四，一二三四。")
        assertEquals(listOf("一二三四，", "一二三四，", "一二三四，一二三四。"), out)
    }

    @Test
    fun `soft punctuation below min words does not cut`() {
        val c = chunker(minWords = 4)
        val out = c.feed("一二，三四五。")
        // "一二，" only 2 words → no soft cut; hard '。' ends the sentence
        assertEquals(listOf("一二，三四五。"), out)
    }

    @Test
    fun `decimal point is not a boundary`() {
        val c = chunker()
        val out = c.feed("圆周率三点一四，好。")
        assertEquals("圆周率三点一四，", out[0])
    }

    @Test
    fun `decimal point real digits`() {
        val c = chunker()
        val out = c.feed("答案是3.14没错。对。")
        assertEquals("答案是3.14没错。", out[0])
    }

    @Test
    fun `over limit never cuts without punctuation`() {
        val fourteen = "甲乙丙丁戊己庚辛壬癸子丑寅卯" // 14 countable chars, no punct
        assertEquals(14, cjkCounter.count(fourteen))
        val c = chunker(maxWords = 12)
        // Over the limit but there is no punctuation to cut at — the run waits
        // (cutting mid-run is what made TTS prosody sound broken).
        assertEquals(emptyList<String>(), c.feed(fourteen))
        assertEquals(listOf(fourteen), c.flush())
    }

    @Test
    fun `over limit cuts at the next soft punctuation even after boost`() {
        val c = chunker(maxWords = 12)
        val out = c.feed("一二三四，一二三四，一二三四五六七八九十甲乙丙，丁戊。")
        assertEquals(
            listOf("一二三四，", "一二三四，", "一二三四五六七八九十甲乙丙，", "丁戊。"),
            out,
        )
    }

    @Test
    fun `punctuation only fragments are dropped`() {
        val c = chunker()
        val out = c.feed("什么？？？真的。")
        assertEquals(listOf("什么？", "真的。"), out)
        assertEquals(emptyList<String>(), c.flush())
    }

    @Test
    fun `dropped fragments do not spend the boost budget`() {
        val c = chunker()
        // "好！" emitted; the two stray "！" dropped instead of becoming TTS requests
        c.feed("好！！！")
        val out = c.feed("一二三四，五六。")
        // comma still boost-cuts: only 1 of the 2 boost slots was spent
        assertEquals(listOf("一二三四，", "五六。"), out)
    }

    @Test
    fun `narrative spans are stripped and unclosed span holds the cut`() {
        val c = chunker()
        val out1 = c.feed("你好*挥了挥手*世界。再见")
        assertEquals(listOf("你好世界。"), out1)
        val out2 = c.flush()
        assertEquals(listOf("再见"), out2)
    }

    @Test
    fun `unclosed narrative blocks cutting until closed`() {
        val c = chunker()
        assertEquals(emptyList<String>(), c.feed("你好*抬起"))
        val out = c.feed("手*好的。") // closes the span mid-stream
        assertEquals(listOf("你好好的。"), out)
    }

    @Test
    fun `narrative stripping coexists with the word limit`() {
        val c = chunker(maxWords = 12)
        // 12 countable CJK chars after stripping → no forced cut inside
        val out = c.feed("甲*旁白*乙*旁白*丙*旁白*丁。")
        assertEquals(listOf("甲乙丙丁。"), out)
    }

    @Test
    fun `flush emits remainder`() {
        val c = chunker()
        assertEquals(emptyList<String>(), c.feed("好的"))
        assertEquals(listOf("好的"), c.flush())
        assertEquals(emptyList<String>(), c.flush())
    }

    @Test
    fun `dot run cuts once`() {
        val c = chunker()
        val out = c.feed("真的吗...好的。")
        assertEquals(listOf("真的吗...", "好的。"), out)
    }

    @Test
    fun `reset clears state`() {
        val c = chunker()
        c.feed("一二三四，")
        c.reset()
        assertEquals(emptyList<String>(), c.flush())
        // boost budget is back: soft cut works again
        val out = c.feed("五六七八，九。")
        assertEquals(listOf("五六七八，", "九。"), out)
    }
}
