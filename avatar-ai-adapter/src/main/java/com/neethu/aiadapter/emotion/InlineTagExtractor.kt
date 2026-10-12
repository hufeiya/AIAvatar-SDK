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
 * <act:wave>    <act:wave:0.5>
 * <cam:close_up> <cam:close_up:0.5>
 * <move:sofa>   <move:sofa:0.5>
 * ```
 * act/cam/move tolerate a trailing intensity segment too: models habitually
 * carry the `<emo:名字:强度>` habit over (`<act:laugh:0.5>` 真机踩过) and a
 * single-segment regex let the whole segment fall through to speech. The
 * value is meaningless for gestures/cameras/moves and dropped. `<move:>` is
 * its own namespace (free-locomotion anchors, docs/locomotion-feasibility.md
 * §7.3) — parsed separately so an act clip named "sofa" can never shadow it.
 *
 * Tag names accept any Unicode letters, not just ASCII: Chinese models
 * occasionally invent CJK names off-vocabulary (真机踩过 `<emo:轻笑>`), and an
 * ASCII-only name class let those segments fall through to speech. The
 * extractor only owns the *shape* — unknown names are resolved downstream
 * (别名表回 canonical / morph 直驱 / 过滤后发 UnknownTag 事件), never leaked
 * as text; each cue carries the original segment in [TagCue.raw] for that
 * downstream reporting.
 *
 * Incremental semantics inherited from its predecessor
 * [MarkerEmotionExtractor]: a tag split across deltas is buffered until
 * complete; an unterminated '<' tail holds text back instead of leaking
 * fragments into speech; a '<' never closed within the cap drains as plain
 * text so pathological input cannot wedge the stream.
 */
class InlineTagExtractor : TagExtractor {

    private val buffer = StringBuilder()

    /** Tag name = Unicode letters/underscore lead + letters/digits/underscore (CJK included). */
    private val tagName = """[\p{L}_][\p{L}\p{N}_]*"""

    private val emotionTag = Regex(
        """<\s*emo\s*:\s*($tagName)(?::\s*(-?[0-9]*\.?[0-9]+))?\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val actionTag = Regex(
        """<\s*act\s*:\s*($tagName)(?::\s*(-?[0-9]*\.?[0-9]+))?\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val cameraTag = Regex(
        """<\s*cam\s*:\s*($tagName)(?::\s*(-?[0-9]*\.?[0-9]+))?\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val moveTag = Regex(
        """<\s*move\s*:\s*($tagName)(?::\s*(-?[0-9]*\.?[0-9]+))?\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val legacyEmotionTag = Regex(
        """<\|emotion\s*:\s*($tagName)(?::\s*(-?[0-9]*\.?[0-9]+))?\s*\|>""",
        RegexOption.IGNORE_CASE,
    )

    // Lenient fallbacks: any `<ns:…>` up to 40 chars in a known namespace.
    // Catches invented shapes like `<move:window→carpet>` (P1 真机踩过) — the
    // name lands downstream as unknown → filtered + reported, never spoken.
    private val lenientTagNames = listOf("emo", "act", "cam", "move")
    private val lenientTags = lenientTagNames.associateWith { ns ->
        Regex("""<\s*$ns\s*:\s*([^>]{1,40})\s*>""", RegexOption.IGNORE_CASE)
    }

    // Text-form tool calls: `<move_to sofa>`, `<move_to anchor="desk">`,
    // `<stop_moving>` — snake_case name (underscore required, so prose like
    // "<see this>" can never match) with optional free-form args. Extracted
    // and reported, never spoken and never executed.
    private val pseudoToolTag = Regex(
        """<\s*([a-z][a-z_]{1,24})\s*(?:\s[^<>]{1,48})?\s*>""",
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
            return TagCue.Emotion(it.groupValues[1].lowercase(), intensity(it.groupValues[2]), segment)
        }
        emotionTag.matchEntire(segment)?.let {
            return TagCue.Emotion(it.groupValues[1].lowercase(), intensity(it.groupValues[2]), segment)
        }
        // act/cam/move 的强度段（模型从 <emo:> 习惯性带过来的）无语义，提取即丢
        actionTag.matchEntire(segment)?.let { return TagCue.Action(it.groupValues[1].lowercase(), segment) }
        cameraTag.matchEntire(segment)?.let { return TagCue.Camera(it.groupValues[1].lowercase(), segment) }
        moveTag.matchEntire(segment)?.let { return TagCue.Move(it.groupValues[1].lowercase(), segment) }
        // 宽容回退：命名空间对但名字/形状离谱（<move:window→carpet>）——下游按
        // 未知名过滤+上报，绝不回语音。强度段语义随 junk 名一起丢弃。
        for (ns in lenientTagNames) {
            lenientTags.getValue(ns).matchEntire(segment)?.let {
                val name = it.groupValues[1].lowercase()
                return when (ns) {
                    "emo" -> TagCue.Emotion(name, raw = segment)
                    "act" -> TagCue.Action(name, segment)
                    "cam" -> TagCue.Camera(name, segment)
                    else -> TagCue.Move(name, segment)
                }
            }
        }
        // 文本形态工具调用（<move_to sofa> / <stop_moving>）：抑制+上报，不执行
        pseudoToolTag.matchEntire(segment)?.let {
            if ('_' in it.groupValues[1]) return TagCue.ToolCallText(segment)
        }
        return null
    }

    private fun intensity(raw: String): Float = raw.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f

    companion object {
        private const val MAX_PENDING_TAG_LENGTH = 64

        /**
         * Prefix of a possibly-incomplete tag at flush time. `(:|_|$)` accepts
         * a stream cut right after the tag name (before the colon, or into a
         * text-form tool call like `<move_to …`) while still rejecting
         * ordinary text like "<emotional" or "5 < 3".
         */
        private val TAG_PREFIX = Regex("""<\s*(emo|act|cam|move)\s*(:|_|$)""", RegexOption.IGNORE_CASE)
    }
}
