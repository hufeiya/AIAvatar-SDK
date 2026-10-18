package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.TagCue
import com.neethu.aiadapter.emotion.InlineTagExtractor

/** Request-only projection: keep dialogue and tag positions, never mutate audit history. */
internal object InlineTagHistoryProjector {
    // A nested '<' starts a fresh segment, preserving comparisons before real tags.
    private val segments = Regex("<[^<>\r\n]*(?:>|(?=[<\r\n])|$)")

    fun project(text: String, canonicalTag: (TagCue) -> String?): String =
        segments.replace(text) { match ->
            val extractor = InlineTagExtractor()
            val result = extractor.feed(match.value)
            if (result.cues.isNotEmpty()) {
                result.cues.joinToString("") { canonicalTag(it).orEmpty() }
            } else {
                result.cleanText + extractor.flush().cleanText
            }
        }
}
