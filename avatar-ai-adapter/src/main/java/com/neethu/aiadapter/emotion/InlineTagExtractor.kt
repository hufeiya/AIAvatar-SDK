package com.neethu.aiadapter.emotion

import com.neethu.aiadapter.api.ExtractionResult
import com.neethu.aiadapter.api.TagCue
import com.neethu.aiadapter.api.TagExtractor

/**
 * Streaming inline-tag filter for the SDK's multimodal protocol
 * (docs/ai-layer-handoff.md §7).
 *
 * Recognized tags (case-insensitive; intensity optional, clamped to 0..1):
 * ```
 * <emo:happy>   <emo:happy:0.8>    (legacy: <|emotion:happy:0.8|>)
 * <act:wave>
 * <cam:close_up>
 * ```
 * Incremental semantics inherited from its predecessor
 * [MarkerEmotionExtractor]: a tag split across deltas is buffered until
 * complete; an unterminated '<' tail holds text back instead of leaking
 * fragments into speech; a '<' never closed within the cap drains as plain
 * text so pathological input cannot wedge the stream.
 */
class InlineTagExtractor : TagExtractor {

    private val buffer = StringBuilder()

    private val emotionTag = Regex(
        """<\s*emo\s*:\s*([A-Za-z_][A-Za-z0-9_]*)(?::\s*(-?[0-9]*\.?[0-9]+))?\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val actionTag = Regex(
        """<\s*act\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val cameraTag = Regex(
        """<\s*cam\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val legacyEmotionTag = Regex(
        """<\|emotion\s*:\s*([A-Za-z_][A-Za-z0-9_]*)(?::\s*(-?[0-9]*\.?[0-9]+))?\s*\|>""",
        RegexOption.IGNORE_CASE,
    )

    override fun feed(delta: String): ExtractionResult {
        buffer.append(delta)
        val cues = ArrayList<TagCue>()
        val clean = StringBuilder()

        var cursor = 0
        while (cursor < buffer.length) {
            val open = buffer.indexOf('<', cursor)
            if (open < 0) {
                clean.append(buffer, cursor, buffer.length)
                cursor = buffer.length
                break
            }
            clean.append(buffer, cursor, open)

            val close = buffer.indexOf('>', open)
            if (close < 0) {
                // Possibly an incomplete tag at the tail — hold everything
                // from the last '<' onward. Cap the holdback so pathological
                // input (a bare '<' never followed by '>') still drains.
                if (buffer.length - open > MAX_PENDING_TAG_LENGTH) {
                    clean.append(buffer, open, buffer.length)
                    cursor = buffer.length
                } else {
                    cursor = open
                }
                break
            }

            val segment = buffer.substring(open, close + 1)
            val cue = matchTag(segment)
            if (cue != null) {
                cues += cue
                cursor = close + 1
            } else {
                // Not one of our tags. If another '<' starts inside this
                // segment (a stray "a < b" right before a real tag), pass
                // through only up to it and re-evaluate from there — the tag
                // after it must still be extracted, not swallowed verbatim.
                val nextOpen = buffer.indexOf('<', open + 1)
                if (nextOpen in (open + 1)..close) {
                    clean.append(buffer, open, nextOpen)
                    cursor = nextOpen
                } else {
                    clean.append(segment)
                    cursor = close + 1
                }
            }
        }

        val tail = buffer.substring(cursor.coerceAtMost(buffer.length))
        val resultText = clean.toString()
        buffer.setLength(0)
        buffer.append(tail)
        return ExtractionResult(resultText, cues)
    }

    override fun flush(): ExtractionResult {
        // A trailing incomplete tag is dropped; everything else is speech.
        // Only tails that actually look like a tag prefix are dropped — a
        // plain '<' (e.g. "5 < 3") is speech, not a tag.
        val open = buffer.lastIndexOf('<')
        if (open >= 0 && !buffer.substring(open).contains('>')) {
            val tail = buffer.substring(open)
            if (tail.startsWith("<|") || TAG_PREFIX.containsMatchIn(tail)) {
                buffer.setLength(open)
            }
        }
        val text = buffer.toString()
        buffer.setLength(0)
        return ExtractionResult(text, emptyList())
    }

    override fun reset() {
        buffer.setLength(0)
    }

    private fun matchTag(segment: String): TagCue? {
        legacyEmotionTag.matchEntire(segment)?.let {
            return TagCue.Emotion(it.groupValues[1].lowercase(), intensity(it.groupValues[2]))
        }
        emotionTag.matchEntire(segment)?.let {
            return TagCue.Emotion(it.groupValues[1].lowercase(), intensity(it.groupValues[2]))
        }
        actionTag.matchEntire(segment)?.let { return TagCue.Action(it.groupValues[1].lowercase()) }
        cameraTag.matchEntire(segment)?.let { return TagCue.Camera(it.groupValues[1].lowercase()) }
        return null
    }

    private fun intensity(raw: String): Float = raw.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f

    companion object {
        private const val MAX_PENDING_TAG_LENGTH = 64

        /**
         * Prefix of a possibly-incomplete tag at flush time. `(:|$)` accepts a
         * stream cut right after the tag name (before the colon) while still
         * rejecting ordinary text like "<emotional" or "5 < 3".
         */
        private val TAG_PREFIX = Regex("""<\s*(emo|act|cam)\s*(:|$)""", RegexOption.IGNORE_CASE)
    }
}
