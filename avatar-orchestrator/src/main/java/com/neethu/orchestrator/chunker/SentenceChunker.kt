package com.neethu.orchestrator.chunker

/**
 * Streams LLM deltas and cuts them into TTS-ready sentences using AIRI's
 * `tts-chunker` rules (packages/pipelines-audio/src/processors/tts-chunker.ts):
 *
 *  - hard punctuation (`.。?？!！…⋯～~` + newlines) cuts immediately
 *  - soft punctuation (`,，、–—:：;；《》「」`) cuts early only within the
 *    first [Options.boost] sentences and only after [Options.minimumWords] words,
 *    and always once the buffer exceeds [Options.maximumWords] (AIRI's "limit")
 *  - a cut never happens at a position without punctuation — an unpunctuated
 *    run waits for the next punctuation mark (or [flush]); cutting mid-run is
 *    exactly what makes TTS prosody sound broken
 *  - decimal points (`3.14`) never cut; `...` runs are treated as one ellipsis
 *  - punctuation-only fragments (the stray `！！` after a cut) are dropped
 *    instead of becoming empty TTS requests and spending the boost budget
 *  - `flush()` emits whatever remains (end of the LLM turn)
 *
 * Word counting uses a pluggable [WordCounter]; the default rides on
 * `java.text.BreakIterator` (ICU-backed on Android, CJK-friendly).
 *
 * Additionally, paired `*...*` narrative segments are stripped from speech
 * output (roleplay actions), with cross-delta buffering of unclosed segments.
 */
class SentenceChunker(
    private val options: Options = Options(),
    private val wordCounter: WordCounter = BreakIteratorWordCounter(),
) {

    data class Options(
        val boost: Int = 2,
        val minimumWords: Int = 4,
        /** Soft punctuation becomes a cut point once the buffer exceeds this many words. */
        val maximumWords: Int = 12,
        val stripNarrativeActions: Boolean = true,
    )

    fun interface WordCounter {
        fun count(text: CharSequence): Int
    }

    /** ICU-based word counter (default). */
    class BreakIteratorWordCounter : WordCounter {
        private val iterator = java.text.BreakIterator.getWordInstance()

        override fun count(text: CharSequence): Int {
            iterator.setText(text.toString())
            var count = 0
            var prev = iterator.first()
            var boundary = iterator.next()
            while (boundary != java.text.BreakIterator.DONE) {
                if (isWord(text, prev, boundary)) count++
                prev = boundary
                boundary = iterator.next()
            }
            return count
        }

        private fun isWord(text: CharSequence, start: Int, end: Int): Boolean {
            for (i in start until end) {
                if (Character.isLetterOrDigit(text[i])) return true
            }
            return false
        }
    }

    private val pending = StringBuilder()
    private var chunksEmitted = 0

    /** Feed one LLM delta; returns all sentences completed by it. */
    fun feed(delta: String): List<String> {
        if (delta.isNotEmpty()) pending.append(delta)
        return drain(final = false)
    }

    /** End of the LLM turn: emit any remainder. */
    fun flush(): List<String> = drain(final = true)

    /** Clear all state (start of a new turn). */
    fun reset() {
        pending.setLength(0)
        chunksEmitted = 0
    }

    private fun drain(final: Boolean): List<String> {
        val out = mutableListOf<String>()
        var scanEnd = speechEndIndex()
        var i = 0
        while (i < scanEnd) {
            val ch = pending[i]

            if (ch in HARD_PUNCTUATION) {
                if (ch == '.' && isDecimalPoint(i)) {
                    i++
                    continue
                }
                if (ch == '.' && i + 1 < scanEnd && pending[i + 1] == '.') {
                    // Dot run: skip to its final dot, then cut the whole run.
                    var runEnd = i
                    while (runEnd + 1 < scanEnd && pending[runEnd + 1] == '.') runEnd++
                    emit(out, cut(runEnd + 1))
                    scanEnd = speechEndIndex()
                    i = 0
                    continue
                }
                emit(out, cut(i + 1))
                scanEnd = speechEndIndex()
                i = 0
                continue
            }

            if (ch in SOFT_PUNCTUATION) {
                val words = wordCounter.count(pending.subSequence(0, i + 1))
                val boostCut = chunksEmitted < options.boost && words >= options.minimumWords
                val limitCut = words > options.maximumWords
                if (boostCut || limitCut) {
                    emit(out, cut(i + 1))
                    scanEnd = speechEndIndex()
                    i = 0
                } else {
                    i++
                }
                continue
            }

            i++
        }

        if (final && pending.isNotEmpty()) {
            emit(out, cut(pending.length))
        }
        return out
    }

    /**
     * Add a cut sentence to the output unless nothing speakable survived
     * cleaning (punctuation-only or fully-stripped fragments) — those would
     * only waste a TTS request and spend the boost budget.
     */
    private fun emit(out: MutableList<String>, sentence: String) {
        if (sentence.none { it.isLetterOrDigit() }) return
        chunksEmitted++
        out += sentence
    }

    /**
     * Cut `pending[0, end)` as one sentence and remove it from the buffer.
     * Returns the cleaned sentence.
     */
    private fun cut(end: Int): String {
        var text = pending.substring(0, end)
        pending.delete(0, end)
        if (options.stripNarrativeActions) {
            text = NARRATIVE_SPAN.replace(text, "")
        }
        return text.trim()
    }

    /**
     * Index up to which speech cutting is allowed: the first unpaired `*`
     * (its segment is still being streamed), or the buffer end.
     */
    private fun speechEndIndex(): Int {
        if (!options.stripNarrativeActions) return pending.length
        var lastStar = -1
        var starCount = 0
        for (i in pending.indices) {
            if (pending[i] == '*') {
                starCount++
                lastStar = i
            }
        }
        return if (starCount % 2 == 1) lastStar else pending.length
    }

    private fun isDecimalPoint(index: Int): Boolean {
        if (index == 0 || index + 1 >= pending.length) return false
        return pending[index - 1].isDigit() && pending[index + 1].isDigit()
    }

    companion object {
        /** Punctuation that always terminates a sentence (AIRI hard set + kept `?！`). */
        val HARD_PUNCTUATION = ".。?？!！…⋯～~\n\r\t".toSet()

        /** Punctuation that terminates a sentence only under the boost condition. */
        val SOFT_PUNCTUATION = ",，、–—:：;；《》「」".toSet()

        private val NARRATIVE_SPAN = Regex("""\*[^*\n]*\*""")
    }
}
