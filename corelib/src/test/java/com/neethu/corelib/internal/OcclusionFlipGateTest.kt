package com.neethu.corelib.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防遮挡翻转闸门（P2 真机验收 3/3 Filament 崩溃修复）状态机单测：
 * 身体/相机运动窗口内冻结翻转，停止后稳定 [OcclusionFlipGate.DEFAULT_SETTLE_SECONDS]
 * 才放行；冷启动静态机位立即可用。纯 JVM。
 */
class OcclusionFlipGateTest {

    @Test
    fun `cold start static pose allows flips immediately`() {
        val gate = OcclusionFlipGate()
        gate.update(bodyMoving = false, cameraGliding = false, dtSeconds = 0.016f)
        assertTrue(gate.flipsAllowed)
    }

    @Test
    fun `walking or gliding freezes flips`() {
        val gate = OcclusionFlipGate()
        gate.update(bodyMoving = true, cameraGliding = false, dtSeconds = 0.016f)
        assertFalse("走动中不放行", gate.flipsAllowed)
        gate.update(bodyMoving = false, cameraGliding = true, dtSeconds = 0.016f)
        assertFalse("运镜在飞不放行", gate.flipsAllowed)
        gate.update(bodyMoving = true, cameraGliding = true, dtSeconds = 0.016f)
        assertFalse(gate.flipsAllowed)
    }

    @Test
    fun `flips resume after settle window`() {
        val gate = OcclusionFlipGate()
        gate.update(bodyMoving = true, cameraGliding = false, dtSeconds = 0.016f)
        // 静止 0.2s(< 0.25s 稳定窗)仍不放行
        repeat(12) { gate.update(false, false, 0.016f) } // 0.192s
        assertFalse("稳定窗未满不放行", gate.flipsAllowed)
        // 累计超过 0.25s 后放行
        gate.update(false, false, 0.1f)
        assertTrue(gate.flipsAllowed)
    }

    @Test
    fun `any motion resets the settle timer`() {
        val gate = OcclusionFlipGate()
        gate.update(false, false, 0.2f) // 已稳定 0.2s
        gate.update(bodyMoving = true, cameraGliding = false, dtSeconds = 0.016f) // 运动打断
        gate.update(false, false, 0.1f) // 只静止 0.1s
        assertFalse("运动打断后稳定窗重新计时", gate.flipsAllowed)
        gate.update(false, false, 0.2f)
        assertTrue(gate.flipsAllowed)
    }

    @Test
    fun `settle timer is clamped - long idle does not overflow`() {
        val gate = OcclusionFlipGate()
        repeat(1000) { gate.update(false, false, 10f) }
        assertTrue(gate.flipsAllowed)
        gate.update(bodyMoving = true, cameraGliding = false, dtSeconds = 0.016f)
        gate.update(false, false, 0.1f)
        assertFalse(gate.flipsAllowed)
    }

    @Test
    fun `reset returns to unfrozen state`() {
        val gate = OcclusionFlipGate()
        gate.update(false, false, 0.016f)
        assertTrue(gate.flipsAllowed)
        gate.reset()
        assertFalse(gate.flipsAllowed)
    }
}
