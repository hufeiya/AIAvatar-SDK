package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.TagCue
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineTagHistoryProjectorTest {
    private fun project(text: String): String = InlineTagHistoryProjector.project(text) {
        if (it is TagCue.Emotion && it.name == "happy") "<emo:happy:${it.intensity}>" else null
    }

    @Test
    fun `malformed controls recover without deleting following dialogue`() {
        assertEquals(
            "前<emo:happy:1.0>你好\n5 < 3（真的）",
            project("前<act:broken<emo:happy>你好<act:bad\n5 < 3（真的）"),
        )
        assertEquals("前\n你好", project("前<mood:" + "x".repeat(100) + "\n你好"))
    }

    @Test
    fun `positions and ordinary bracket text are preserved`() {
        assertEquals(
            "5 < 3，<b>你好</b><emo:happy:0.5>（真的）呀",
            project("5 < 3，<b>你好</b><emo:happy:0.5>（真的）<cam:full_body>呀<act:"),
        )
    }
}
