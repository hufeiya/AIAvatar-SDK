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
    fun `exceeding maximum words forces a cut`() {
        val twelve = "甲乙丙丁戊己庚辛壬癸子丑" // exactly 12 countable chars
        assertEquals(12, cjkCounter.count(twelve))
        val c = chunker(maxWords = 12)
        assertEquals(emptyList<String>(), c.feed(twelve)) // 12 words, no cut yet
        val out = c.feed("寅卯。") // 13th word forces a cut at 12
        assertEquals(listOf(twelve, "寅卯。"), out)
        assertEquals(emptyList<String>(), c.flush())
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
