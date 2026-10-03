package com.neethu.orchestrator.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §7.10 direct-expression name resolution: the tag extractor lowercases cue
 * names, but model morph names are case-sensitive (blinkLeft ≠ blinkleft).
 */
class FaceDriverResolveExpressionTest {

    private val supported = setOf("blinkLeft", "blinkRight", "aa", "jawOpen")

    @Test
    fun `mis-cased names resolve to the model's actual casing`() {
        assertEquals("blinkLeft", FaceDriver.resolveExpressionName(supported, "blinkleft"))
        assertEquals("blinkLeft", FaceDriver.resolveExpressionName(supported, "BLINKLEFT"))
        assertEquals("aa", FaceDriver.resolveExpressionName(supported, "aa"))
    }

    @Test
    fun `exact names pass through and unknown names return null`() {
        assertEquals("jawOpen", FaceDriver.resolveExpressionName(supported, "jawOpen"))
        assertNull(FaceDriver.resolveExpressionName(supported, "unicorn"))
        assertNull(FaceDriver.resolveExpressionName(emptySet(), "aa"))
    }
}
