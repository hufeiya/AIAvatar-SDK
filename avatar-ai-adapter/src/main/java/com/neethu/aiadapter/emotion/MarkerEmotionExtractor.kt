package com.neethu.aiadapter.emotion

import com.neethu.aiadapter.api.EmotionCue
import com.neethu.aiadapter.api.EmotionExtractor
import com.neethu.aiadapter.api.ExtractionResult

/**
 * Streaming emotion-marker filter for the SDK's expression protocol.
 *
 * Recognized markers (intensity optional, clamped to 0..1):
 * ```
 * <|emotion:happy|>        <|emotion:happy:0.8|>
 * ```
 * The extractor is incremental: a marker split across deltas is buffered
 * until complete; a trailing partial marker holds text back instead of
 * leaking `<|` fragments into speech.
 */
class MarkerEmotionExtractor : EmotionExtractor {

    private val buffer = StringBuilder()
    private val pattern = Regex("""<\|emotion:\s*([A-Za-z_]+)(?::\s*(-?[0-9]*\.?[0-9]+))?\s*\|>""")

    override fun feed(delta: String): ExtractionResult {
        buffer.append(delta)
        val cues = ArrayList<EmotionCue>()
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
                // Possibly an incomplete marker at the tail — hold everything
                // from the last '<' onward. Cap the holdback so pathological
                // input (a bare '<' never followed by '>') still drains.
                if (buffer.length - open > MAX_PENDING_MARKER_LENGTH) {
                    clean.append(buffer, open, buffer.length)
                    cursor = buffer.length
                } else {
                    cursor = open
                }
                break
            }

            val segment = buffer.substring(open, close + 1)
            val match = pattern.matchEntire(segment)
            if (match != null) {
                val name = match.groupValues[1].lowercase()
                val intensity = match.groupValues[2].toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
                cues += EmotionCue(name, intensity)
                cursor = close + 1
            } else {
                // Not one of our markers — pass through verbatim.
                clean.append(segment)
                cursor = close + 1
            }
        }

        val tail = buffer.substring(cursor.coerceAtMost(buffer.length))
        val resultText = clean.toString()
        buffer.setLength(0)
        buffer.append(tail)
        return ExtractionResult(resultText, cues)
    }

    override fun flush(): ExtractionResult {
        // A trailing incomplete marker is dropped; everything else is speech.
        val open = buffer.lastIndexOf("<|")
        if (open >= 0 && !buffer.substring(open).contains('>')) {
            buffer.setLength(open)
        }
        val text = buffer.toString()
        buffer.setLength(0)
        return ExtractionResult(text, emptyList())
    }

    override fun reset() {
        buffer.setLength(0)
    }

    companion object {
        private const val MAX_PENDING_MARKER_LENGTH = 64
    }
}
