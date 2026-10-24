package com.neethu.corelib.internal

import com.neethu.corelib.WalkBounds
import com.neethu.corelib.WalkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 自由走动状态机（docs/locomotion-feasibility.md P0）的纯数学回归测试。
 * 模拟渲染器的每帧循环：update 输入当前 root 坐标，返回的 FrameMotion
 * 由测试侧施加到坐标上（与 SoulLinkRenderer.updateLocomotion 同构）。
 */
class LocomotionEngineTest {

    /** 跑到到达或超时，返回最终 (x, z)。 */
    private fun runToArrival(
        engine: LocomotionEngine,
        dt: Float,
        maxSeconds: Float = 30f,
    ): Pair<Float, Float> {
        var x = 0f
        var z = 0f
        var t = 0f
        while (t < maxSeconds) {
            val m = engine.update(dt, x, 0f, z)
            x += m?.dx ?: 0f
            z += m?.dz ?: 0f
            t += dt
            if (engine.snapshot().phase == WalkPhase.IDLE) break
        }
        assertTrue("walk did not arrive within ${maxSeconds}s", t < maxSeconds)
        return x to z
    }

    @Test
    fun `背向目标先原地转身 到位后沿新朝向前进`() {
        val engine = LocomotionEngine()
        // 面朝 +Z（heading 0），目标在 -Z（身后）：必须先转 180°
        engine.startWalk(0f, -5f, 1.0f, null)

        var x = 0f
        var z = 0f
        var turned = false
        var walked = false
        var t = 0f
        val dt = 1f / 60f
        while (t < 30f) {
            val m = engine.update(dt, x, 0f, z)
            x += m?.dx ?: 0f
            z += m?.dz ?: 0f
            when (engine.snapshot().phase) {
                WalkPhase.TURNING -> {
                    // 原地转身帧：不产生位移，只转朝向
                    assertEquals(0f, m?.dx ?: 0f, 1e-6f)
                    assertEquals(0f, m?.dz ?: 0f, 1e-6f)
                    turned = true
                }
                WalkPhase.WALKING -> {
                    walked = true
                    // 面向 π 后 forward = (0,-1)，必须往 -Z 走
                    assertTrue(m!!.dz < 0f)
                }
                WalkPhase.IDLE -> break
            }
            t += dt
        }
        assertTrue("never turned in place", turned)
        assertTrue("never walked", walked)
        assertEquals(0f, x, LocomotionEngine.ARRIVE_EPS)
        assertEquals(-5f, z, LocomotionEngine.ARRIVE_EPS)
    }

    @Test
    fun `到达只上报一次并回到 IDLE`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 2f, 1.0f, null)
        runToArrival(engine, 1f / 60f)

        assertTrue(engine.consumeArrived())
        assertFalse(engine.consumeArrived())
        assertEquals(WalkPhase.IDLE, engine.snapshot().phase)
        assertNull(engine.update(1f / 60f, 0f, 0f, 2f))
    }

    @Test
    fun `帧率无关性_60fps 与 10fps 终点一致`() {
        val a = LocomotionEngine()
        a.startWalk(2f, 0f, 1.2f, null)
        val (ax, az) = runToArrival(a, 1f / 60f)

        val b = LocomotionEngine()
        b.startWalk(2f, 0f, 1.2f, null)
        val (bx, bz) = runToArrival(b, 1f / 10f)

        assertEquals(ax, bx, 0.05f)
        assertEquals(az, bz, 0.05f)
        // 目标在 +X：desired heading = π/2，forward = (1, 0)
        assertEquals(2f, ax, LocomotionEngine.ARRIVE_EPS)
        assertEquals(0f, az, LocomotionEngine.ARRIVE_EPS)
    }

    @Test
    fun `越界目标被房间矩形 clamp`() {
        val engine = LocomotionEngine()
        val bounds = WalkBounds(minX = -3f, maxX = 3f, minZ = -6f, maxZ = -1f)
        engine.startWalk(100f, -100f, 1.0f, bounds)
        val s = engine.snapshot()
        assertEquals(3f, s.targetX, 1e-4f)
        assertEquals(-6f, s.targetZ, 1e-4f)
    }

    @Test
    fun `stopWalk 立即停且不算到达`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 5f, 1.0f, null)
        engine.update(1f / 60f, 0f, 0f, 0f)
        engine.stopWalk()

        assertEquals(WalkPhase.IDLE, engine.snapshot().phase)
        assertNull(engine.update(1f / 60f, 0f, 0f, 0f))
        assertFalse(engine.consumeArrived())
    }

    @Test
    fun `转向方向符号_右转与左转`() {
        // 目标在 +X → desired = π/2，heading 从 0 增加（右转，dHeading > 0）
        val right = LocomotionEngine()
        right.startWalk(5f, 0f, 1.0f, null)
        val mRight = right.update(1f / 60f, 0f, 0f, 0f)!!
        assertTrue(mRight.dHeadingRad > 0f)

        // 目标在 -X → desired = -π/2，dHeading < 0
        val left = LocomotionEngine()
        left.startWalk(-5f, 0f, 1.0f, null)
        val mLeft = left.update(1f / 60f, 0f, 0f, 0f)!!
        assertTrue(mLeft.dHeadingRad < 0f)
    }

    @Test
    fun `起步 ease 爬升随后满速`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 10f, 1.0f, null)
        val dt = 1f / 60f
        val first = engine.update(dt, 0f, 0f, 0f)!!
        // 起步帧远慢于满速步长（0.35s 爬升）
        assertTrue(first.dz < 1.0f * dt * 0.2f)

        // 快进越过 ease 窗口
        var z = first.dz
        var t = dt
        while (t < 1.0f) {
            val m = engine.update(dt, 0f, 0f, z)
            z += m?.dz ?: 0f
            t += dt
        }
        val full = engine.update(dt, 0f, 0f, z)!!
        assertEquals(1.0f * dt, full.dz, 1e-4f)
    }

    @Test
    fun `行走中 retarget 更新目标并继续`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 5f, 1.0f, null)
        engine.update(1f / 60f, 0f, 0f, 0f)

        engine.startWalk(3f, 0f, 1.0f, null)
        val s = engine.snapshot()
        assertEquals(3f, s.targetX, 1e-4f)
        assertEquals(0f, s.targetZ, 1e-4f)
        assertEquals(WalkPhase.TURNING, s.phase)

        // 走到新目标
        var x = 0f
        var z = 0f
        var t = 0f
        val dt = 1f / 60f
        while (t < 30f && engine.snapshot().phase != WalkPhase.IDLE) {
            val m = engine.update(dt, x, 0f, z)
            x += m?.dx ?: 0f
            z += m?.dz ?: 0f
            t += dt
        }
        assertEquals(3f, x, LocomotionEngine.ARRIVE_EPS)
        assertEquals(0f, z, LocomotionEngine.ARRIVE_EPS)
    }

    @Test
    fun `速度被夹紧到安全区间`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 5f, 100f, null)
        assertEquals(3.0f, engine.snapshot().speed, 1e-4f)

        val slow = LocomotionEngine()
        slow.startWalk(0f, 5f, 0.01f, null)
        assertEquals(0.2f, slow.snapshot().speed, 1e-4f)
    }

    @Test
    fun `接近正负 π 的朝向差取最短弧`() {
        val engine = LocomotionEngine()
        // 目标在正后方 (-0.001, -5)：desired ≈ -π，err 取最短弧不应绕远路
        engine.startWalk(-0.001f, -5f, 1.0f, null)
        val m = engine.update(1f / 60f, 0f, 0f, 0f)!!
        assertTrue("turn took the long arc", abs(m.dHeadingRad) <= 2.6f / 60f + 1e-4f)
    }

    // ── P1：提前制动（§5.1 制动距离 = v²/2a）与起步 hold（过渡剪辑窗口）──

    @Test
    fun `近距离目标提前减速_步长低于满速`() {
        val engine = LocomotionEngine()
        // 目标 0.2m、速度 3 m/s：允许速度 = sqrt(2·2.2·0.2) ≈ 0.94 m/s 被钳住
        engine.startWalk(0f, 0.2f, 3.0f, null)
        val dt = 1f / 60f
        val step = engine.update(dt, 0f, 0f, 0f)!!
        assertTrue("near target should brake", step.dz < 0.94f * dt)
        assertTrue("brake speed should still advance", step.dz > 0f)
    }

    @Test
    fun `远距离目标不制动_满速前进`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 5f, 1.0f, null)
        val dt = 1f / 60f
        var z = 0f
        // 快进越过 ease 窗口
        while (z < 1f) {
            z += engine.update(dt, 0f, 0f, z)?.dz ?: 0f
        }
        val m = engine.update(dt, 0f, 0f, z)!!
        // dist ≈ 4m，允许速度 sqrt(2·2.2·4) ≈ 4.2 > 1.0 → 不钳
        assertEquals(1.0f * dt, m.dz, 1e-4f)
    }

    @Test
    fun `制动不影响到达_终点仍在阈值内`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 0.5f, 3.0f, null)
        val (x, z) = runToArrival(engine, 1f / 60f)
        assertEquals(0f, x, LocomotionEngine.ARRIVE_EPS)
        assertEquals(0.5f, z, LocomotionEngine.ARRIVE_EPS)
        assertTrue(engine.consumeArrived())
    }

    @Test
    fun `起步 hold 冻结位移_到期后缓动重启`() {
        val engine = LocomotionEngine()
        // 目标正前方（已对齐）：无 hold 时第一帧就 WALKING；有 hold 时先冻结
        engine.startWalk(0f, 2f, 1.0f, null, startHoldSeconds = 0.5f)
        val dt = 1f / 60f
        var t = 0f
        while (t < 0.4f) {
            val m = engine.update(dt, 0f, 0f, 0f)!!
            assertEquals("hold must freeze translation", 0f, m.dz, 1e-6f)
            assertEquals(WalkPhase.TURNING, engine.snapshot().phase)
            t += dt
        }
        // 推进到 hold 到期
        while (engine.snapshot().phase == WalkPhase.TURNING) {
            val m = engine.update(dt, 0f, 0f, 0f)
            t += dt
            if (m != null && m.dz != 0f) break
        }
        assertTrue("hold never expired", t < 1f)
        // hold 到期后 ease 从 0 重启：第一帧位移远小于满速步长
        val first = engine.update(dt, 0f, 0f, 0f)!!
        assertTrue("ease should restart after hold", first.dz < 1.0f * dt * 0.2f)
    }

    @Test
    fun `起步 hold 期间仍修正朝向`() {
        val engine = LocomotionEngine()
        // 目标在 +X：hold 冻结位移但 heading 应照常转
        engine.startWalk(5f, 0f, 1.0f, null, startHoldSeconds = 0.5f)
        val m = engine.update(1f / 60f, 0f, 0f, 0f)!!
        assertEquals(0f, m.dx, 1e-6f)
        assertEquals(0f, m.dz, 1e-6f)
        assertTrue("heading correction should continue during hold", m.dHeadingRad > 0f)
    }

    @Test
    fun `stopWalk 清掉未到期的起步 hold`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 2f, 1.0f, null, startHoldSeconds = 0.5f)
        engine.update(1f / 60f, 0f, 0f, 0f)
        engine.stopWalk()
        engine.startWalk(0f, 2f, 1.0f, null)
        // 重启后无 hold：对齐目标直接 WALKING（第一帧就有位移）
        val m = engine.update(1f / 60f, 0f, 0f, 0f)!!
        assertTrue(m.dz > 0f)
    }

    // ── P2：headingLocked（侧移）与 externalGait（方案 C）──────────────

    @Test
    fun `headingLocked 不转身直指目标`() {
        val engine = LocomotionEngine()
        // heading 0（面朝 +Z），目标在 +X 侧方：侧移模式不转朝向、直接横挪
        engine.startWalk(3f, 0f, 1.0f, null, headingLocked = true)
        val m = engine.update(1f / 60f, 0f, 0f, 0f)!!
        assertEquals("locked heading must not rotate", 0f, m.dHeadingRad, 1e-6f)
        assertEquals("strafe moves straight toward target", 1f, m.dx / (m.dx + m.dz), 1e-4f)
        assertTrue(m.dx > 0f)
        assertEquals(engine.snapshot().phase, WalkPhase.WALKING)
    }

    @Test
    fun `headingLocked 背后目标也不转身直走`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, -3f, 1.0f, null, headingLocked = true)
        var z = 0f
        var t = 0f
        val dt = 1f / 60f
        while (t < 10f && engine.snapshot().phase != WalkPhase.IDLE) {
            val m = engine.update(dt, 0f, 0f, z) ?: break
            assertEquals(0f, m.dHeadingRad, 1e-6f)
            z += m.dz
            t += dt
        }
        assertTrue("never arrived", t < 10f)
        assertEquals(-3f, z, LocomotionEngine.ARRIVE_EPS)
        assertEquals("heading untouched", 0f, engine.snapshot().headingDeg, 1e-3f)
    }

    @Test
    fun `externalGait 平移归零 速度策略经 commandedSpeed 输出`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 4f, 2.0f, null)
        var z = 0f
        val dt = 1f / 60f
        var t = 0f
        var sawCommand = false
        while (t < 30f && engine.snapshot().phase != WalkPhase.IDLE) {
            val m = engine.update(dt, 0f, 0f, z, externalGait = true) ?: break
            assertEquals("external gait must not translate programmatically", 0f, m.dz, 1e-6f)
            if (m.commandedSpeedMps > 0f) sawCommand = true
            // 模拟渲染层：提取位移 = commanded × dt（速度与位移同步）
            z += m.commandedSpeedMps * dt
            t += dt
        }
        assertTrue("commanded speed never reported", sawCommand)
        assertTrue("walk did not arrive", t < 30f)
        assertEquals(4f, z, 0.2f)
    }

    @Test
    fun `externalGait 制动在接近段压低 commandedSpeed`() {
        val engine = LocomotionEngine()
        val full = 2.0f
        engine.startWalk(0f, 10f, full, null)
        var z = 0f
        val dt = 1f / 60f
        var t = 0f
        var nearCmd = Float.MAX_VALUE
        while (t < 30f && engine.snapshot().phase != WalkPhase.IDLE) {
            val m = engine.update(dt, 0f, 0f, z, externalGait = true) ?: break
            if (10f - z < 0.3f && m.commandedSpeedMps > 0f) {
                nearCmd = minOf(nearCmd, m.commandedSpeedMps)
            }
            z += m.commandedSpeedMps * dt
            t += dt
        }
        assertTrue("brake never kicked in near target", nearCmd < full * 0.7f)
    }

    @Test
    fun `stopWalk 清 headingLocked`() {
        val engine = LocomotionEngine()
        engine.startWalk(3f, 0f, 1.0f, null, headingLocked = true)
        engine.stopWalk()
        // 重启为普通走动：应恢复转身/对齐语义（第一帧 TURNING）
        engine.startWalk(0f, -3f, 1.0f, null)
        engine.update(1f / 60f, 0f, 0f, 0f)
        assertEquals(WalkPhase.TURNING, engine.snapshot().phase)
    }

    /**
     * 朝向权威读数：外部旋转（turnTo/定姿恢复/出生点）之后无需 resync，
     * 每帧传入 root 矩阵读出的 belly heading 即自动对齐——程序位移模式
     * 从背对目标起步也能直线收敛（路径长 ≈ 直线距离，无蛇形绕行）。
     * 2026-10-24 真机蛇形/永不到达（waypoint 多段腿冻结 0.8m）的回归锁。
     */
    @Test
    fun `root朝向读数是权威 外部旋转后自动对齐直线收敛`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 5f, 1.0f, null)
        // 外部把 root 转到背对目标（facing π），引擎不知情也无 resync
        var facing = Math.PI.toFloat()
        var x = 0f
        var z = 0f
        var path = 0f
        val dt = 1f / 60f
        var t = 0f
        while (t < 30f && engine.snapshot().phase != WalkPhase.IDLE) {
            val m = engine.update(dt, x, 0f, z, externalGait = false, rootHeadingRad = facing)
            if (m != null) {
                facing += m.dHeadingRad
                val step = kotlin.math.hypot(m.dx.toDouble(), m.dz.toDouble()).toFloat()
                x += kotlin.math.sin(facing) * step
                z += kotlin.math.cos(facing) * step
                path += step
            }
            t += dt
        }
        assertTrue("walk did not arrive", t < 30f)
        assertEquals(0f, x, LocomotionEngine.ARRIVE_EPS)
        assertEquals(5f, z, LocomotionEngine.ARRIVE_EPS)
        assertTrue(
            "path serpentine: $path for 5m straight (external rotation must resync via readback)",
            path <= 5f * 1.5f + 0.5f,
        )
    }

    /**
     * 方案 C 同款回归：位移由提取回灌沿真实朝向走（引擎平移恒 0），从
     * 背对目标起步——朝向读数同步后追 bearing 单调收敛，不再逐帧反向
     * 过冲（真机实测路径 3~5 倍的根因形态）。
     */
    @Test
    fun `方案C背对目标起步 朝向同步后收敛不蛇形`() {
        val engine = LocomotionEngine()
        engine.startWalk(0f, 5f, 1.0f, null)
        var facing = Math.PI.toFloat()
        var x = 0f
        var z = 0f
        var path = 0f
        val dt = 1f / 60f
        var t = 0f
        while (t < 30f && engine.snapshot().phase != WalkPhase.IDLE) {
            val m = engine.update(dt, x, 0f, z, externalGait = true, rootHeadingRad = facing)
                ?: break
            facing += m.dHeadingRad
            val step = m.commandedSpeedMps * dt
            x += kotlin.math.sin(facing) * step
            z += kotlin.math.cos(facing) * step
            path += step
            t += dt
        }
        assertTrue("walk did not arrive", t < 30f)
        assertEquals(0f, x, LocomotionEngine.ARRIVE_EPS)
        assertEquals(5f, z, LocomotionEngine.ARRIVE_EPS)
        assertTrue(
            "path serpentine: $path for 5m straight (方案C extraction must follow synced heading)",
            path <= 5f * 1.6f + 0.5f,
        )
    }
}
