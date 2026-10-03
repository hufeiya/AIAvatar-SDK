package com.neethu.orchestrator.face

import com.neethu.aiadapter.api.EmotionCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * §7.10 direct-expression lane: emotion cues whose name is not one of the
 * canonical seven become single-morph expressions (the loaded model's own
 * ARKit morphs / presets like blink_l), riding the same easing + auto-reset
 * machinery as canonical emotions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmotionBlenderTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `unknown emotion name becomes a direct single-morph expression`() = runTest {
        val blender = EmotionBlender(CoroutineScope(dispatcher), autoResetDelayMs = 60_000)
        blender.apply(EmotionCue("blink_l", 1f))
        // 0.25s blend done
        assertEquals(1f, blender.tick(0.5f)["blink_l"]!!, 0.05f)
    }

    @Test
    fun `unknown name honors its intensity`() = runTest {
        val blender = EmotionBlender(CoroutineScope(dispatcher), autoResetDelayMs = 60_000)
        blender.apply(EmotionCue("brow_inner_up", 0.4f))
        assertEquals(0.4f, blender.tick(0.5f)["brow_inner_up"]!!, 0.05f)
    }

    @Test
    fun `direct expression auto-resets to neutral after the delay`() = runTest {
        val blender = EmotionBlender(CoroutineScope(dispatcher), autoResetDelayMs = 100)
        blender.apply(EmotionCue("blink_l", 1f))
        assertEquals(1f, blender.tick(0.5f)["blink_l"]!!, 0.05f)
        advanceTimeBy(200)
        runCurrent()
        // neutral 过渡（0.6s）结束后应归 0
        assertEquals(0f, blender.tick(1.0f)["blink_l"]!!, 0.05f)
    }

    @Test
    fun `canonical emotions still use their combo defs`() = runTest {
        val blender = EmotionBlender(CoroutineScope(dispatcher), autoResetDelayMs = 60_000)
        blender.apply(EmotionCue("happy", 1f))
        val values = blender.tick(0.6f)
        assertEquals(0.7f, values["happy"]!!, 0.05f)
        assertEquals(0.2f, values["aa"]!!, 0.05f)
    }
}
