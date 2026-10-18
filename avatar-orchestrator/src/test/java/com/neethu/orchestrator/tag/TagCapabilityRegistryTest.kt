package com.neethu.orchestrator.tag

import com.neethu.aiadapter.api.TagCue
import com.neethu.orchestrator.gesture.ActionEntry
import com.neethu.orchestrator.tag.TagCapabilityRegistry.Reason
import org.junit.Assert.*
import org.junit.Test

class TagCapabilityRegistryTest {
    private fun entry(tag: String, category: String = "test") = ActionEntry(tag, tag, category, assetPath = "$tag.vrma")
    private val catalog = listOf(entry("aj_waving_gesture"), entry("nodding_head_yes"), entry("quick_informal_bow")) +
        (0..60).map { entry("dance_$it", "舞蹈") }
    private fun registry(input: String = "hello", previous: String? = null, gestures: Boolean = true,
                         camera: Boolean = true, expressions: List<String> = listOf("eyeBlinkLeft"),
                         assets: List<ActionEntry> = catalog) = TagCapabilityRegistry(
        setOf("happy", "sad", "neutral"), expressions,
        ActionCandidateSelector.select(assets, input, previous), assets,
        if (camera) listOf("close_up" to "特写") else emptyList(), listOf("sofa" to "沙发"),
        gestures, camera, true,
    )

    @Test fun `semantic names resolve to exact live assets`() {
        val r = registry()
        assertEquals("aj_waving_gesture", r.actions.getValue("wave").tag)
        assertEquals("nodding_head_yes", r.actions.getValue("nod").tag)
        assertEquals("quick_informal_bow", r.actions.getValue("bow").tag)
        assertNull(r.resolve(TagCue.Action("aj_bowing_head")).cue)
        assertFalse(registry(assets = emptyList()).actions.containsKey("wave"))
    }

    @Test fun `candidate range is enforced even for real assets and aliases`() {
        val r = registry("跳舞")
        assertEquals(12, r.selection.candidates.size)
        val allowed = r.selection.candidates.keys.first()
        val excluded = catalog.first { it.tag.startsWith("dance") && it.tag !in r.actions }.tag
        assertEquals("<act:$allowed>", r.canonicalTag(TagCue.Action(allowed)))
        assertEquals(Reason.NOT_ALLOWED_THIS_TURN, r.resolve(TagCue.Action(excluded)).reason)
        assertEquals(Reason.UNKNOWN_NAME, r.resolve(TagCue.Action("dancing_new")).reason)
        assertEquals(Reason.NOT_ALLOWED_THIS_TURN, registry().resolve(TagCue.Action(allowed)).reason)
    }

    @Test fun `finite aliases are observable and canonicalized`() {
        val r = registry()
        assertEquals("<act:wave>", r.canonicalTag(TagCue.Action("aj_waving_gesture")))
        assertTrue(r.resolve(TagCue.Action("aj_waving_gesture")).aliased)
        assertEquals("<cam:close_up>", r.canonicalTag(TagCue.Camera("closeup")))
        assertEquals("<emo:happy:1.0>", r.canonicalTag(TagCue.Emotion("joy")))
        assertEquals("<emo:eyeBlinkLeft:0.0>", r.canonicalTag(TagCue.Emotion("eyeblinkleft", 0f)))
        assertEquals("<emo:happy:1.0>", r.canonicalTag(TagCue.Action("laugh")))
    }

    @Test fun `disabled capability differs from unknown name`() {
        assertEquals(Reason.DISABLED, registry(gestures = false).resolve(TagCue.Action("wave")).reason)
        assertEquals(Reason.DISABLED, registry(camera = false).resolve(TagCue.Camera("closeup")).reason)
        assertEquals(Reason.UNKNOWN_NAME, registry(camera = false).resolve(TagCue.Camera("full_body")).reason)
        assertEquals(Reason.MALFORMED, registry().resolve(TagCue.InvalidControl("<act:>")).reason)
    }

    @Test fun `model expressions and registry version update together`() {
        val before = registry()
        val after = registry(expressions = emptyList())
        assertNotEquals(before.version, after.version)
        assertNull(after.canonicalTag(TagCue.Emotion("eyeblinkleft")))
        assertNotNull(after.canonicalTag(TagCue.Emotion("happy")))
        assertNotEquals(before.version, registry("跳舞").version)
    }

    @Test fun `repeat carries previous legal asset without admitting removed assets`() {
        assertTrue(registry("再做一次", "dance_50").actions.containsKey("dance_50"))
        assertFalse(registry("再做一次", "removed").actions.containsKey("removed"))
        assertEquals(registry("跳舞").actions.keys, registry("跳舞", assets = catalog.reversed()).actions.keys)
    }

    @Test fun `small custom catalogs keep their explicit names`() {
        val r = registry(assets = listOf(entry("custom_greeting")))
        assertEquals(setOf("custom_greeting"), r.actions.keys)
        assertNotNull(r.canonicalTag(TagCue.Action("custom_greeting")))
    }

    @Test fun `english and Chinese intents retrieve the same category deterministically`() {
        assertEquals(registry("please dance").selection.candidates, registry("请跳舞").selection.candidates)
        assertTrue(registry().selection.candidates.isEmpty())
        assertEquals("dance_50", registry("dance_50").selection.candidates.keys.first())
    }
    @Test fun `headless sessions validate known emotions before availability is checked`() {
        val r = registry()
        assertEquals(Reason.UNKNOWN_NAME, r.resolve(TagCue.Emotion("invented_emotion")).reason)
        assertNotNull(r.resolve(TagCue.Emotion("happy")).cue)
        assertTrue(r.resolve(TagCue.Action("wave", "<act:wave:0.5>")).aliased)
    }

    @Test fun `Chinese requests retrieve exact labels and named dance styles`() {
        val assets = catalog + listOf(entry("dodge_step").copy(label = "闪身步"),
            entry("bboy_hip_hop_variation_one"), entry("female_rumba_dancing"), entry("reclined_facepalm"))
        assertTrue(registry("请表演闪身步。只说一句中文", assets = assets).actions.containsKey("dodge_step"))
        assertTrue(registry("请跳一小段街舞", assets = assets).actions.containsKey("bboy_hip_hop_variation_one"))
        assertTrue(registry("请跳伦巴", assets = assets).actions.containsKey("female_rumba_dancing"))
        assertTrue(registry("请躺下休息", assets = assets).actions.containsKey("reclined_facepalm"))
    }

    @Test fun `correct native spelling is not counted as an alias after extractor normalization`() {
        val result = com.neethu.aiadapter.emotion.InlineTagExtractor().feed("<emo:eyeBlinkLeft:1>")
        assertFalse(registry().resolve(result.cues.single()).aliased)
        assertTrue(registry().resolve(TagCue.Emotion("eyeblinkleft", raw = "<emo:eyeblinkleft:1>")).aliased)
    }

}
