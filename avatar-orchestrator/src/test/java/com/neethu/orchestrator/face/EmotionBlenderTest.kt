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
import org.junit.Assert.assertFalse
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
        assertEquals(0.12f, values["aa"]!!, 0.05f)
        // ARKit 微表情组合：Duchenne 笑（眼轮匝肌眯眼）
        assertEquals(0.35f, values["eyeSquintLeft"]!!, 0.05f)
        assertEquals(0.2f, values["cheekSquintRight"]!!, 0.05f)
    }

    @Test
    fun `think is a brow-eye-mouth micro combo not a single missing preset`() = runTest {
        // 真机默认模型没有 think 预设：旧 def(think→0.7) 整条被表达式门控丢弃，
        // <emo:think> 完全无效。现在由 ARKit 微表情拼装，每个 morph 都真实存在。
        val blender = EmotionBlender(CoroutineScope(dispatcher), autoResetDelayMs = 60_000)
        blender.apply(EmotionCue("think", 1f))
        val values = blender.tick(0.6f)
        assertEquals(0.4f, values["browDownLeft"]!!, 0.05f)
        assertEquals(0.15f, values["browDownRight"]!!, 0.05f)
        assertEquals(0.3f, values["mouthPressLeft"]!!, 0.05f)
        assertFalse(values.containsKey("think"))
    }

    @Test
    fun `hold override delays the auto reset`() = runTest {
        val blender = EmotionBlender(CoroutineScope(dispatcher), autoResetDelayMs = 60_000)
        // 句子挂接场景：hold = clip 时长 + 余量，长句期间不被归零切断
        blender.apply(EmotionCue("happy", 1f), holdMs = 100)
        blender.tick(0.6f)
        advanceTimeBy(300)
        runCurrent()
        // hold 到期 → neutral 过渡（0.6s）结束后归 0
        assertEquals(0f, blender.tick(1.0f)["happy"]!!, 0.05f)
    }

    @Test
    fun `HOLD_NO_RESET keeps the expression until replaced`() = runTest {
        val blender = EmotionBlender(CoroutineScope(dispatcher), autoResetDelayMs = 100)
        // 手动表情通道（面板/ai_cmd）：不自动归零，保持到下一次 apply
        blender.apply(EmotionCue("brow_down", 0.8f), holdMs = EmotionBlender.HOLD_NO_RESET)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0.8f, blender.tick(0.5f)["brow_down"]!!, 0.05f)
    }
}
