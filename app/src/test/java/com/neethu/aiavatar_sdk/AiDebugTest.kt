package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDebugTest {

    @Test
    fun `parses full command with all extras`() {
        val cmd = parseAiDebugCommand(
            mapOf(
                EXTRA_AI_CMD to "SET_EXPRESSION",
                EXTRA_AI_ARG to "happy",
                EXTRA_AI_WEIGHT to 0.7f,
            )
        )!!
        assertEquals("set_expression", cmd.name)
        assertEquals("happy", cmd.arg)
        assertEquals(0.7f, cmd.weight)
        assertTrue(cmd.loop)
    }

    @Test
    fun `parses move command with xyz`() {
        val cmd = parseAiDebugCommand(
            mapOf(
                EXTRA_AI_CMD to "move",
                EXTRA_AI_X to 0.5f,
                EXTRA_AI_Y to -0.25f,
                EXTRA_AI_Z to 1f,
            )
        )!!
        assertTrue(cmd.hasCoordinates)
        assertEquals(0.5f, cmd.x)
        assertEquals(-0.25f, cmd.y)
        assertEquals(1f, cmd.z)
    }

    @Test
    fun `returns null without ai_cmd`() {
        assertNull(parseAiDebugCommand(emptyMap()))
        assertNull(parseAiDebugCommand(mapOf(EXTRA_AI_ARG to "happy")))
        assertNull(parseAiDebugCommand(mapOf(EXTRA_AI_CMD to "   ")))
    }

    @Test
    fun `accepts numeric extras sent as strings`() {
        val cmd = parseAiDebugCommand(
            mapOf(
                EXTRA_AI_CMD to "zoom",
                EXTRA_AI_X to "200",
            )
        )!!
        assertEquals(200f, cmd.x)
    }

    @Test
    fun `invalid numeric string is ignored`() {
        val cmd = parseAiDebugCommand(
            mapOf(
                EXTRA_AI_CMD to "zoom",
                EXTRA_AI_X to "abc",
            )
        )!!
        assertNull(cmd.x)
        assertFalse(cmd.hasCoordinates)
    }

    @Test
    fun `loop accepts on off words and booleans`() {
        fun loopOf(value: Any?) = parseAiDebugCommand(
            mapOf(EXTRA_AI_CMD to "play_animation", EXTRA_AI_LOOP to value)
        )!!.loop

        assertTrue(loopOf(null)) // default
        assertTrue(loopOf(true))
        assertTrue(loopOf("on"))
        assertTrue(loopOf("1"))
        assertFalse(loopOf(false))
        assertFalse(loopOf("off"))
        assertFalse(loopOf("0"))
    }

    @Test
    fun `weight is clamped to the 0 to 1 range`() {
        val high = parseAiDebugCommand(
            mapOf(EXTRA_AI_CMD to "set_expression", EXTRA_AI_WEIGHT to 5f)
        )!!.weight
        val low = parseAiDebugCommand(
            mapOf(EXTRA_AI_CMD to "set_expression", EXTRA_AI_WEIGHT to -1f)
        )!!.weight
        assertEquals(1f, high)
        assertEquals(0f, low)
    }

    @Test
    fun `blank arg becomes null`() {
        val cmd = parseAiDebugCommand(
            mapOf(EXTRA_AI_CMD to "load_model", EXTRA_AI_ARG to "  ")
        )!!
        assertNull(cmd.arg)
    }

    @Test
    fun `help text mentions every command`() {
        val help = aiDebugHelp()
        listOf(
            "help", "state", "list", "load_model", "load_scene", "set_expression",
            "clear_expression", "play_animation", "stop_animation", "move", "zoom",
            "pan", "orbit", "reset_camera", "set_drag_mode", "screenshot",
        ).forEach { assertTrue("help should mention '$it'", help.contains(it)) }
    }
}
