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
 * fragments into speech. Oversized control candidates are discarded until
 * '>', a newline or a new '<'; ordinary comparisons remain plain text.
 */
class InlineTagExtractor : TagExtractor {

    private val buffer = StringBuilder()
    private var discardingControl = false
    private val stageDirections = StageDirectionFilter()

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

    // Text-form tool calls: `<move_to sofa>`, `<move_to anchor="desk">`,
    // `<stop_moving>` — snake_case name (underscore required, so prose like
    // "<see this>" can never match) with optional free-form args. Extracted
    // and reported, never spoken and never executed.
    private val pseudoToolTag = Regex(
        """<\s*([a-z][a-z_]{1,24})\s*(?:\s[^<>]{1,48})?\s*>""",
        RegexOption.IGNORE_CASE,
    )

    override fun feed(delta: String): ExtractionResult {
        val cues = ArrayList<TagCue>()
        val clean = StringBuilder()
        // Consume characters identically for every chunk partition, including the cap.
        for (char in delta) {
            if (discardingControl) {
                when (char) {
                    '>' -> discardingControl = false
                    '\n', '\r' -> { discardingControl = false; clean.append(char) }
                    '<' -> { discardingControl = false; buffer.append(char) }
                }
                continue
            }
            if (buffer.isEmpty()) {
                if (char == '<') buffer.append(char) else clean.append(char)
                continue
            }
            if (char == '<' || char == '\n' || char == '\r') {
                // A fresh tag or line boundary recovers from a missing closing bracket.
                if (isControlPrefix(buffer.toString())) cues += TagCue.InvalidControl(buffer.toString())
                else clean.append(buffer)
                buffer.setLength(0)
                if (char == '<') buffer.append(char) else clean.append(char)
                continue
            }
            buffer.append(char)
            if (buffer.length > MAX_PENDING_TAG_LENGTH) {
                if (isControlPrefix(buffer.toString())) {
                    cues += TagCue.InvalidControl(buffer.toString())
                    discardingControl = char != '>'
                } else clean.append(buffer)
                buffer.setLength(0)
            } else if (char == '>') {
                val segment = buffer.toString()
                val cue = matchTag(segment)
                if (cue != null) cues += cue
                else if (CONTROL_CANDIDATE.containsMatchIn(segment)) cues += TagCue.InvalidControl(segment)
                else clean.append(segment)
                buffer.setLength(0)
            }
        }
        return ExtractionResult(stageDirections.feed(clean.toString()), cues)
    }

    override fun flush(): ExtractionResult {
        val tail = buffer.toString()
        val control = isControlPrefix(tail)
        val clean = stageDirections.feed(if (control) "" else tail) + stageDirections.flush()
        reset()
        return ExtractionResult(clean, if (control) listOf(TagCue.InvalidControl(tail)) else emptyList())
    }

    override fun reset() {
        buffer.setLength(0)
        discardingControl = false
        stageDirections.reset()
    }

    private fun isControlPrefix(text: String): Boolean {
        if (CONTROL_CANDIDATE.containsMatchIn(text)) return true
        val prefix = text.removePrefix("<").trim().lowercase()
        return prefix.isNotEmpty() && (
            listOf("emo", "act", "cam", "move", "|emotion").any { it.startsWith(prefix) } ||
                TAG_PREFIX.containsMatchIn(text) || TOOL_PREFIX.containsMatchIn(text)
            )
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
        // 文本形态工具调用（<move_to sofa> / <stop_moving>）：抑制+上报，不执行
        pseudoToolTag.matchEntire(segment)?.let {
            if ('_' in it.groupValues[1]) return TagCue.ToolCallText(segment)
        }
        return null
    }

    private fun intensity(raw: String): Float = raw.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f

    companion object {
        private const val MAX_PENDING_TAG_LENGTH = 64
        private val CONTROL_CANDIDATE = Regex("""^<\s*(?:\|\s*)?[\p{L}_][\p{L}\p{N}_]*\s*:""")
        private val TOOL_PREFIX = Regex("""^<\s*[a-z][a-z_]*_[a-z_]*(?:\s|$)""", RegexOption.IGNORE_CASE)

        /**
         * Prefix of a possibly-incomplete tag at flush time. `(:|_|$)` accepts
         * a stream cut right after the tag name (before the colon, or into a
         * text-form tool call like `<move_to …`) while still rejecting
         * ordinary text like "<emotional" or "5 < 3".
         */
        private val TAG_PREFIX = Regex("""<\s*(emo|act|cam|move)\s*(:|_|$)""", RegexOption.IGNORE_CASE)
    }
}
