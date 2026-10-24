package com.neethu.corelib.internal

import com.neethu.corelib.DriveMath
import com.neethu.corelib.WalkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 摇杆连续驱动（docs/gamepad-control-feasibility.md §4）下的状态机行为：
 * 逐帧 retarget（目标恒在 lookahead 处）期间不得误报到达、制动不得压低
 * 指令速度、松手停步干净。模拟渲染器同构循环（LocomotionEngineTest 先例）。
 */
class LocomotionJoystickDriveTest {

    private val dt = 1f / 60f

    /** 一次连续驱动的帧级请求（速度档位）。 */
    private fun speedAt(frame: Int): Float = when {
        frame < 30 -> 0.4f + frame * 0.02f     // 缓推起步 0.4→1.0
        frame < 150 -> 1.0f                    // 恒速
        else -> 0.6f                           // 收杆半速
    }

    private fun driveFrames(): Int = 210

    private fun assertDriveSession(externalGait: Boolean) {
        val engine = LocomotionEngine()
        var x = 0f
        var z = 0f
        var easedFrames = 0
        for (frame in 0 until driveFrames()) {
            val requested = speedAt(frame)
            val d = DriveMath.lookaheadMeters(requested)
            // 渲染器每帧做的事：目标钉在「当前位置 + 前向 × lookahead」
            engine.startWalk(x, z + d, requested, bounds = null)
            val motion = engine.update(dt, x, 0f, z, externalGait)
            assertFalse("frame=$frame 摇杆驱动中误报到达", engine.consumeArrived())
            assertTrue("frame=$frame 状态机不应空闲", engine.snapshot().phase != WalkPhase.IDLE)
            when {
                motion == null -> {} // 对齐门内（不会发生：目标正前）
                externalGait -> {
                    // 外部步态：程序位移为 0，位移由提取回灌——测试按指令速度
                    // 积分近似；校验指令速度本身未被制动压低
                    assertEquals(0f, motion.dx, 1e-6f)
                    assertEquals(0f, motion.dz, 1e-6f)
                    if (easedFrames > 25) { // ease 0.35s ≈ 21 帧后恒速
                        assertEquals(
                            "frame=$frame 制动介入压速（external）",
                            requested, motion.commandedSpeedMps, 1e-3f,
                        )
                    }
                    z += motion.commandedSpeedMps * dt
                }
                else -> {
                    if (easedFrames > 25) {
                        val stepSpeed = sqrt2(motion.dx, motion.dz) / dt
                        assertEquals(
                            "frame=$frame 制动介入压速（program）",
                            requested, stepSpeed, 1e-2f,
                        )
                    }
                    x += motion.dx
                    z += motion.dz
                }
            }
            easedFrames++
        }
        // 松手：渲染器走 stopWalk → 干净回 IDLE，无到达事件
        engine.stopWalk()
        assertEquals(WalkPhase.IDLE, engine.snapshot().phase)
        assertFalse(engine.consumeArrived())
    }

    private fun sqrt2(dx: Float, dz: Float): Float = kotlin.math.sqrt(dx * dx + dz * dz)

    @Test
    fun `连续 retarget 程序位移模式 不误到达 不压速`() = assertDriveSession(externalGait = false)

    @Test
    fun `连续 retarget 外部步态模式 不误到达 不压速`() = assertDriveSession(externalGait = true)

    @Test
    fun `跑步档位 lookahead 同样在制动半径外`() {
        val v = DriveMath.speedMps(1f, run = true)
        val engine = LocomotionEngine()
        val d = DriveMath.lookaheadMeters(v)
        engine.startWalk(0f, d, v, bounds = null)
        var z = 0f
        // ease 之后指令速度应 == 请求速度（制动 cap = √(2·a·d) ≥ v）
        repeat(40) {
            engine.startWalk(0f, z + d, v, bounds = null)
            val m = engine.update(dt, 0f, 0f, z, externalGait = true)!!
            z += m.commandedSpeedMps * dt
        }
        assertEquals("制动介入压速", v, engine.update(dt, 0f, 0f, z, externalGait = true)!!.commandedSpeedMps, 1e-3f)
    }

    @Test
    fun `retarget 中途变向 不炸不误到达`() {
        val engine = LocomotionEngine()
        var x = 0f
        var z = 0f
        // 先前进，再急转侧向（摇杆快速回杆场景）
        repeat(30) {
            engine.startWalk(x, z + 0.35f, 1.0f, bounds = null)
            val m = engine.update(dt, x, 0f, z, externalGait = true)!!
            z += m.commandedSpeedMps * dt
        }
        repeat(30) {
            engine.startWalk(x + 0.35f, z, 1.0f, bounds = null)
            val m = engine.update(dt, x, 0f, z, externalGait = true)!!
            x += m.commandedSpeedMps * dt
        }
        assertFalse(engine.consumeArrived())
        assertTrue(engine.snapshot().phase != WalkPhase.IDLE)
    }
}
