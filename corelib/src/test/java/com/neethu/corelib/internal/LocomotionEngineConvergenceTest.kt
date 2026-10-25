package com.neethu.corelib.internal

import com.neethu.corelib.WalkPhase
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 方案 C 提取模式（externalGait）的收敛回归（P0 道具验收②r2）：渲染层把
 * engine 返回的 commandedSpeedMps 设为步态播放速，root 实际位移来自素材
 * 提取——执行速率与 commanded 的比值 K（标定偏差/执行器增益）在 r1≈0.37、
 * r2≈1.25。r2 实测：短距（0.52m）+大航向误差（97°）的起步腿（就近
 * waypoint 接力）在 K≥1 时围目标振荡永不收敛（12min 卡死,FSM pending
 * 拖死）。TURN_RADIUS_K 封顶后必须任意 K∈[0.5,1.5] 都收敛。
 *
 * 测试侧模拟渲染器循环：root 沿 heading 前进 K·commanded·dt,朝向施加
 * dHeading（与 SoulLinkRenderer.updateLocomotion 的提取回灌同构）。
 */
class LocomotionEngineConvergenceTest {

    private data class Outcome(val arrived: Boolean, val seconds: Float, val minDist: Float)

    /**
     * 跑到到达/超时。heading 单调由 engine 驱动（初始 headingDeg 给大误差）,
     * 位移 = K·commanded·dt 沿当前 heading(提取执行器)。
     */
    private fun run(
        startX: Float,
        startZ: Float,
        headingDeg: Float,
        targetX: Float,
        targetZ: Float,
        actuatorK: Float,
        maxSeconds: Float = 20f,
        dt: Float = 1f / 60f,
    ): Outcome {
        val engine = LocomotionEngine()
        engine.startWalk(targetX, targetZ, speed = 1.0f, bounds = null)
        var x = startX
        var z = startZ
        var heading = Math.toRadians(headingDeg.toDouble())
        var t = 0f
        var minDist = Float.MAX_VALUE
        var arrived = false
        while (t < maxSeconds) {
            val m = engine.update(dt, x, 0f, z, externalGait = true, rootHeadingRad = heading.toFloat())
            heading += (m?.dHeadingRad ?: 0f).toDouble()
            val v = (m?.commandedSpeedMps ?: 0f) * actuatorK
            x += (sin(heading) * v * dt).toFloat()
            z += (cos(heading) * v * dt).toFloat()
            t += dt
            minDist = minOf(minDist.toDouble(), hypot((targetX - x).toDouble(), (targetZ - z).toDouble())).toFloat()
            if (engine.snapshot().phase == WalkPhase.IDLE) {
                arrived = true
                break
            }
        }
        return Outcome(arrived, t, minDist)
    }

    @Test
    fun `r2 卡死现场——短距大航向误差起步腿在提取增益 1_25 下必须收敛`() {
        // r2 实测坐标:起点(1.85,-5.21) heading -103°,目标 aisle_east_lower(1.80,-4.70)
        val outcome = run(1.85f, -5.21f, -103f, 1.80f, -4.70f, actuatorK = 1.25f)
        assertTrue("未到达(${outcome.seconds}s, min=${outcome.minDist})", outcome.arrived)
        assertTrue("收敛过慢 ${outcome.seconds}s", outcome.seconds < 8f)
    }

    @Test
    fun `同一现场在增益 1_0 与 0_5 下也收敛（标定无关性）`() {
        for (k in floatArrayOf(0.5f, 1.0f)) {
            val outcome = run(1.85f, -5.21f, -103f, 1.80f, -4.70f, actuatorK = k)
            assertTrue("K=$k 未到达", outcome.arrived)
            assertTrue("K=$k 收敛过慢 ${outcome.seconds}s", outcome.seconds < 8f)
        }
    }

    @Test
    fun `远距正常腿（3m_90° 误差）全增益收敛且不显著变慢`() {
        for (k in floatArrayOf(0.5f, 1.0f, 1.25f, 1.5f)) {
            val outcome = run(-1.0f, -3.3f, -175f, 1.80f, -4.70f, actuatorK = k)
            assertTrue("K=$k 未到达", outcome.arrived)
            assertTrue("K=$k 过慢 ${outcome.seconds}s", outcome.seconds < 10f)
        }
    }

    @Test
    fun `反向起步（1m_180° 误差）收敛`() {
        val outcome = run(2.8f, -4.7f, -84f, 1.80f, -4.70f, actuatorK = 1.25f)
        assertTrue(outcome.arrived)
        assertTrue(outcome.seconds < 8f)
    }

    @Test
    fun `程序位移模式（无提取）回归——原语义到达`() {
        // externalGait=false:引擎自带对齐门+程序步,执行器增益 1（位移=dx,dz）
        val engine = LocomotionEngine()
        engine.startWalk(1.80f, -4.70f, speed = 1.0f, bounds = null)
        var x = 1.85f
        var z = -5.21f
        var t = 0f
        while (t < 10f) {
            val m = engine.update(1f / 60f, x, 0f, z)
            x += m?.dx ?: 0f
            z += m?.dz ?: 0f
            t += 1f / 60f
            if (engine.snapshot().phase == WalkPhase.IDLE) break
        }
        assertTrue("程序位移未到达 t=$t", engine.snapshot().phase == WalkPhase.IDLE)
        assertTrue(
            "落点偏差 ${hypot((1.80 - x).toDouble(), (-4.70 - z).toDouble())}",
            hypot((1.80 - x).toDouble(), (-4.70 - z).toDouble()) < 0.1,
        )
    }
}
