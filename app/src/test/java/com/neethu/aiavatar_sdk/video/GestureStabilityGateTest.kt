package com.neethu.aiavatar_sdk.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手势确认稳定性门控的纯逻辑单测（猜拳 P2，docs/rps-skill-feasibility.md §5）：
 * 锁「连续 3 帧才确认 / 触发一次不连发 / 放手换手才重新武装 / 1.5s 重触发间隔」
 * 四条不变量。MediaPipe 的 Bitmap/原生层不在 JVM 单测面内（真机验收）。
 */
class GestureStabilityGateTest {

    private fun gate() = GestureStabilityGate()

    /** 80ms 分析节奏的帧序列（与 UserCameraTracker 的 DETECT_INTERVAL_MS 对齐）。 */
    private fun frames(vararg gestures: Int): List<Pair<Int, Long>> =
        gestures.mapIndexed { i, g -> g to (i * 80L) }

    @Test
    fun `three consecutive frames confirm and fire exactly once`() {
        val g = gate()
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.ROCK, 0))
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.ROCK, 80))
        assertEquals(PresetGestures.ROCK, g.onDetection(PresetGestures.ROCK, 160))
        // 继续握着:不再重复触发
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.ROCK, 240))
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.ROCK, 2_000))
    }

    @Test
    fun `two frames then a gap never fires`() {
        val g = gate()
        for ((gesture, at) in frames(
            PresetGestures.ROCK, PresetGestures.ROCK,
            PresetGestures.NONE, PresetGestures.ROCK, PresetGestures.ROCK,
            PresetGestures.NONE,
        )) {
            assertEquals(PresetGestures.NONE, g.onDetection(gesture, at))
        }
    }

    /** 喂满三帧完成第一次确认（0/80/160ms），返回后门控处于「已触发未武装」态。 */
    private fun fireFirstRock(g: GestureStabilityGate) {
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.ROCK, 0))
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.ROCK, 80))
        assertEquals(PresetGestures.ROCK, g.onDetection(PresetGestures.ROCK, 160))
    }

    @Test
    fun `withdraw then re-form refires the same gesture after the gap`() {
        val g = gate()
        fireFirstRock(g)
        // 收手 2 帧 → 重新武装;重出石头后要到 1.5s 间隔过完(≈1.66s 起)才触发
        val timeline = listOf(
            PresetGestures.NONE to 1_080L, PresetGestures.NONE to 1_160L,
            PresetGestures.ROCK to 1_240L, PresetGestures.ROCK to 1_320L,
            PresetGestures.ROCK to 1_400L, PresetGestures.ROCK to 1_480L,
            PresetGestures.ROCK to 1_560L, PresetGestures.ROCK to 1_640L,
            PresetGestures.ROCK to 1_720L,
        )
        var fired = PresetGestures.NONE
        for ((gesture, at) in timeline) {
            fired = g.onDetection(gesture, at)
        }
        assertEquals(PresetGestures.ROCK, fired) // 只有最后一帧触发
    }

    @Test
    fun `refire gap blocks an immediate rethrow even after re-arming`() {
        val g = gate()
        fireFirstRock(g)
        // 立刻收手再快速重出:重新武装了,但间隔不足
        var at = 240L
        var last = PresetGestures.NONE
        val seq = listOf(
            PresetGestures.NONE, PresetGestures.NONE,
            PresetGestures.ROCK, PresetGestures.ROCK, PresetGestures.ROCK,
            PresetGestures.ROCK, PresetGestures.ROCK, PresetGestures.ROCK,
            PresetGestures.ROCK, PresetGestures.ROCK, PresetGestures.ROCK,
        )
        for (gesture in seq) {
            last = g.onDetection(gesture, at)
            at += 80L
        }
        assertEquals(PresetGestures.NONE, last) // 240..1040ms 全在 1500ms 间隔内
        assertEquals(PresetGestures.ROCK, g.onDetection(PresetGestures.ROCK, 1_660L))
    }

    @Test
    fun `single noisy frame does not re-arm the fired gesture`() {
        val g = gate()
        fireFirstRock(g)
        // 握拳过程中混进一帧剪刀误检:不构成「换手」,不能再次武装——
        // 时间线拉过 1.5s 间隔之后也不触发(排除间隔因素,单锁重新武装)
        val seq = listOf(
            PresetGestures.ROCK to 240L, PresetGestures.SCISSORS to 320L,
            PresetGestures.ROCK to 400L, PresetGestures.ROCK to 480L,
            PresetGestures.ROCK to 560L, PresetGestures.ROCK to 640L,
            PresetGestures.ROCK to 1_740L, PresetGestures.ROCK to 1_820L,
        )
        for ((gesture, at) in seq) {
            assertEquals("at=$at", PresetGestures.NONE, g.onDetection(gesture, at))
        }
    }

    @Test
    fun `a different stable gesture fires the next round`() {
        val g = gate()
        fireFirstRock(g)
        // 换出剪刀:2 帧重新武装,第 3 帧本来可触发,但 1.5s 间隔压着——
        // 间隔一过,同一连续手势即刻确认
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.SCISSORS, 240))
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.SCISSORS, 320))
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.SCISSORS, 400))
        assertEquals(PresetGestures.SCISSORS, g.onDetection(PresetGestures.SCISSORS, 1_660L))
    }

    @Test
    fun `none and unmapped categories never fire`() {
        val g = gate()
        for (i in 0..9) {
            assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.NONE, i * 80L))
        }
        for (i in 0..9) {
            assertEquals(PresetGestures.NONE, g.onDetection(99, i * 80L))
        }
    }

    @Test
    fun `reset clears fired state for a fresh camera session`() {
        val g = gate()
        fireFirstRock(g)
        g.reset()
        // reset 后立即的 3 帧=立刻确认(连 1.5s 间隔也清掉:上一段的拳头与这一段无关)
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.ROCK, 200))
        assertEquals(PresetGestures.NONE, g.onDetection(PresetGestures.ROCK, 280))
        assertEquals(PresetGestures.ROCK, g.onDetection(PresetGestures.ROCK, 360))
    }

    @Test
    fun `preset category names map to rps codes`() {
        assertEquals(PresetGestures.ROCK, PresetGestures.codeFor("Closed_Fist"))
        assertEquals(PresetGestures.SCISSORS, PresetGestures.codeFor("Victory"))
        assertEquals(PresetGestures.PAPER, PresetGestures.codeFor("Open_Palm"))
        assertEquals(PresetGestures.NONE, PresetGestures.codeFor("Thumb_Up"))
        assertEquals(PresetGestures.NONE, PresetGestures.codeFor("Pointing_Up"))
        assertEquals(PresetGestures.NONE, PresetGestures.codeFor("None"))
        assertEquals(PresetGestures.NONE, PresetGestures.codeFor(""))
        assertEquals(PresetGestures.NONE, PresetGestures.codeFor(null))
    }
}
