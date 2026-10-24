package com.neethu.corelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 模拟量驱动通道纯数学（docs/gamepad-control-feasibility.md §4.2）：
 * 死区/速度/lookahead 不变量/相机相对方向已知答案。
 */
class DriveMathTest {

    // ── 死区 ─────────────────────────────────────────────────────────────

    @Test
    fun `死区内读零 含边界`() {
        assertEquals(0f to 0f, DriveMath.applyDeadzone(0f, 0f))
        assertEquals(0f to 0f, DriveMath.applyDeadzone(DriveMath.DEADZONE, 0f))
        assertEquals(0f to 0f, DriveMath.applyDeadzone(0.05f, 0.05f)) // 模长 0.071 < 0.12
    }

    @Test
    fun `满偏不损失增益`() {
        assertEquals(1f to 0f, DriveMath.applyDeadzone(1f, 0f))
        assertEquals(0f to -1f, DriveMath.applyDeadzone(0f, -1f))
        // 斜向满偏：模长仍为 1
        val (x, y) = DriveMath.applyDeadzone(1f, 1f)
        assertEquals(1f, sqrt(x * x + y * y), 1e-5f)
    }

    @Test
    fun `死区沿线性重标 无跳变`() {
        // 模长 0.5 → (0.5-0.12)/0.88 = 0.432
        val (x, _) = DriveMath.applyDeadzone(0.5f, 0f)
        assertEquals(0.4318f, x, 1e-3f)
        // 死区沿外一点：接近零但非零（小杆量可分辨）
        val (sx, _) = DriveMath.applyDeadzone(0.13f, 0f)
        assertTrue(sx > 0f && sx < 0.05f)
    }

    // ── 速度 ─────────────────────────────────────────────────────────────

    @Test
    fun `满杆步行与跑步速度`() {
        assertEquals(DriveMath.WALK_MAX_MPS, DriveMath.speedMps(1f, run = false), 1e-6f)
        assertEquals(
            DriveMath.WALK_MAX_MPS * DriveMath.RUN_BOOST,
            DriveMath.speedMps(1f, run = true), 1e-4f,
        )
        assertEquals(0f, DriveMath.speedMps(0f, run = true), 1e-6f)
        // 超量程钳制
        assertEquals(DriveMath.WALK_MAX_MPS, DriveMath.speedMps(1.5f, run = false), 1e-6f)
    }

    @Test
    fun `跑步满杆越过跑步步态自动选用阈值`() {
        // 渲染层 RUN_SPEED_MPS=1.9：run 增益后必须能触发跑步步态换挡
        assertTrue(DriveMath.speedMps(1f, run = true) >= 1.9f)
    }

    // ── lookahead：制动永不介入的不变量 ──────────────────────────────────

    @Test
    fun `lookahead 保证制动上限不低于指令速度`() {
        // 引擎制动 √(2·a·d)：d ≡ lookahead 时连续驱动下速度必须不被压低
        var v = 0.2f
        while (v <= 2.5f) {
            val d = DriveMath.lookaheadMeters(v)
            val brakeCap = sqrt(2f * DriveMath.BRAKE_DECEL_MPS2 * d)
            assertTrue("v=$v brakeCap=$brakeCap", brakeCap >= v - 1e-4f)
            v += 0.05f
        }
    }

    @Test
    fun `lookahead 下限兜底`() {
        assertEquals(0.35f, DriveMath.lookaheadMeters(0.1f), 1e-6f)
    }

    // ── 相机相对方向：已知答案 ────────────────────────────────────────────

    @Test
    fun `gaze 加Z 前推朝 加Z`() {
        val (x, z) = DriveMath.cameraRelativeDir(0f, 1f, axisForward = 1f, axisRight = 0f)!!
        assertEquals(0f, x, 1e-5f)
        assertEquals(1f, z, 1e-5f)
    }

    @Test
    fun `gaze 加Z 右推是 负X 右手系已知答案`() {
        // 右手系验证锚点：面向 +Z 时屏幕右 = −X（cross(gaze, up)）
        val (x, z) = DriveMath.cameraRelativeDir(0f, 1f, axisForward = 0f, axisRight = 1f)!!
        assertEquals(-1f, x, 1e-5f)
        assertEquals(0f, z, 1e-5f)
    }

    @Test
    fun `gaze 加Z 斜推归一化`() {
        val (x, z) = DriveMath.cameraRelativeDir(0f, 1f, axisForward = 1f, axisRight = 1f)!!
        assertEquals(1f, sqrt(x * x + z * z), 1e-5f)
    }

    @Test
    fun `gaze 负X 右推是 负Z`() {
        val (x, z) = DriveMath.cameraRelativeDir(-1f, 0f, axisForward = 0f, axisRight = 1f)!!
        assertEquals(0f, x, 1e-5f)
        assertEquals(-1f, z, 1e-5f)
    }

    @Test
    fun `俯视相机退化用兜底朝向`() {
        val (x, z) = DriveMath.cameraRelativeDir(
            gazeX = 0f, gazeZ = 0f, axisForward = 1f, axisRight = 0f,
            fallbackForwardX = 0f, fallbackForwardZ = 1f,
        )!!
        assertEquals(0f, x, 1e-5f)
        assertEquals(1f, z, 1e-5f)
        assertNull(
            DriveMath.cameraRelativeDir(0f, 0f, axisForward = 1f, axisRight = 0f),
        )
    }

    @Test
    fun `零轴无方向`() {
        assertNull(DriveMath.cameraRelativeDir(0f, 1f, axisForward = 0f, axisRight = 0f))
    }
}
