package com.neethu.aiavatar_sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neethu.aiadapter.api.TagCue
import com.neethu.aiadapter.emotion.InlineTagExtractor
import com.neethu.orchestrator.tag.ActionCandidateSelector
import com.neethu.orchestrator.tag.TagCapabilityRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-runtime checks; these deliberately do not call an LLM or claim visual/TTS validation. */
@RunWith(AndroidJUnit4::class)
class InlineTagDeviceAcceptanceTest {
    private fun catalog(): List<com.neethu.orchestrator.gesture.ActionEntry> {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        fun paths(path: String): List<String> = assets.list(path).orEmpty().flatMap { name ->
            val child = "$path/$name"
            if (name.endsWith(".vrma")) listOf(child.removePrefix("animations/")) else paths(child)
        }
        return buildLlmActionCatalog(paths("animations"))
    }

    private fun registry(input: String = "你好", enabled: Boolean = true): TagCapabilityRegistry {
        val assets = catalog()
        return TagCapabilityRegistry(
            emotions = setOf("happy", "sad", "neutral"), directExpressions = listOf("blinkLeft"),
            selection = ActionCandidateSelector.select(assets, input), allActions = assets,
            cameras = if (enabled) listOf("close_up" to "特写") else emptyList(), moves = emptyList(),
            gesturesEnabled = enabled, cameraEnabled = enabled, movesEnabled = false,
        )
    }

    @Test fun malformedControlsNeverBecomeSpeechAtAnySplit() {
        val controls = listOf("<mood:happy>", "<act:>", "<emo:happy:bad>", "<act:wave:1:extra>",
            "<move:window→carpet>", "<act:" + "x".repeat(90) + ">", "<|emotion:" + "x".repeat(90) + "|>")
        for (control in controls) {
            val input = "前${control}后，5 < 3（真的）"
            for (split in 0..input.length) {
                val parser = InlineTagExtractor()
                val a = parser.feed(input.take(split))
                val b = parser.feed(input.drop(split))
                val c = parser.flush()
                assertEquals("$control split=$split", "前后，5 < 3（真的）", a.cleanText + b.cleanText + c.cleanText)
                assertTrue((a.cues + b.cues + c.cues).all { it is TagCue.InvalidControl })
            }
        }
    }

    @Test fun finiteNarrationFilterPreservesSpokenParentheses() {
        val input = "（低头）你好*挥手*（开玩笑的）(nods)"
        for (split in 0..input.length) {
            val parser = InlineTagExtractor()
            assertEquals("你好（开玩笑的）", parser.feed(input.take(split)).cleanText +
                parser.feed(input.drop(split)).cleanText + parser.flush().cleanText)
        }
    }

    @Test fun realApkAssetsHaveShortMappingsAndBoundedCandidates() {
        val r = registry("跳舞")
        assertEquals(24, r.selection.common.size)
        assertTrue(r.selection.candidates.size in 1..12)
        val excluded = r.allActions.first { asset -> r.actions.values.none { it.tag == asset.tag } }
        assertEquals(TagCapabilityRegistry.Reason.NOT_ALLOWED_THIS_TURN, r.resolve(TagCue.Action(excluded.tag)).reason)
        for (entry in r.actions.values) {
            InstrumentationRegistry.getInstrumentation().targetContext.assets.open(entry.assetPath!!).use {
                assertTrue(it.read() >= 0)
            }
        }
    }

    @Test fun aliasesDisabledAndUnknownHaveDistinctOutcomes() {
        val r = registry()
        assertEquals("<act:wave>", r.canonicalTag(TagCue.Action("aj_waving_gesture")))
        assertEquals("<cam:close_up>", r.canonicalTag(TagCue.Camera("closeup")))
        assertEquals("<emo:happy:1.0>", r.canonicalTag(TagCue.Emotion("joy")))
        assertEquals(TagCapabilityRegistry.Reason.DISABLED, registry(enabled = false).resolve(TagCue.Action("wave")).reason)
        assertEquals(TagCapabilityRegistry.Reason.UNKNOWN_NAME, r.resolve(TagCue.Camera("full_body")).reason)
        assertEquals(TagCapabilityRegistry.Reason.UNKNOWN_NAME, r.resolve(TagCue.Emotion("invented")).reason)
    }

    @Test fun discardStateRecoversAndResetStartsFreshTurn() {
        val parser = InlineTagExtractor()
        val oversized = "<act:" + "x".repeat(100)
        assertEquals("", parser.feed(oversized).cleanText)
        assertEquals("\n你好", parser.feed("\n你好").cleanText)
        parser.feed(oversized)
        parser.reset()
        assertEquals("下一轮", parser.feed("下一轮").cleanText + parser.flush().cleanText)
    }
}
