package com.neethu.corelib

import com.neethu.corelib.internal.ContactPoseMath
import com.neethu.corelib.internal.OcclusionMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 场景交互/防遮挡的纯数学层单测（OcclusionMath + ContactPoseMath）。
 * Filament 胶水（SceneOcclusionController/ContactPoseController）真机验。
 */
class SceneInteractionMathTest {

    // ── OcclusionMath.segmentIntersectsAabb ──────────────────────────────

    private val wallMin = floatArrayOf(-3.9f, -1f, -7.6f)
    private val wallMax = floatArrayOf(3.9f, 2.2f, -7.4f) // 北墙薄片 (sdk 世界)

    @Test
    fun `segment crossing a wall slab hits`() {
        val eye = floatArrayOf(0f, 0f, 0f)          // 房内
        val target = floatArrayOf(0f, 0f, -9f)      // 穿墙到墙外
        assertTrue(OcclusionMath.segmentIntersectsAabb(eye, target, wallMin, wallMax))
    }

    @Test
    fun `segment inside the room misses the wall`() {
        val eye = floatArrayOf(0f, 0f, 0f)
        val head = floatArrayOf(0f, 0f, -3.3f)      // 墙内侧（未穿墙）
        assertFalse(OcclusionMath.segmentIntersectsAabb(eye, head, wallMin, wallMax))
    }

    @Test
    fun `segment parallel outside the slab misses`() {
        val eye = floatArrayOf(0f, 0f, 0f)
        val target = floatArrayOf(0f, 10f, 0f)      // 竖直向上，够不到北墙
        assertFalse(OcclusionMath.segmentIntersectsAabb(eye, target, wallMin, wallMax))
    }

    @Test
    fun `segment ending short of the wall misses`() {
        val eye = floatArrayOf(0f, 0f, -6f)         // 墙外
        val target = floatArrayOf(0f, 0f, -7.0f)    // 仍在墙前 0.4m
        assertFalse(OcclusionMath.segmentIntersectsAabb(eye, target, wallMin, wallMax))
    }

    @Test
    fun `camera outside pointing back through the wall hits`() {
        val eye = floatArrayOf(0f, 0f, -9f)         // 相机在北墙外
        val head = floatArrayOf(0f, 0f, -3.35f)     // 房内角色
        assertTrue(OcclusionMath.segmentIntersectsAabb(eye, head, wallMin, wallMax))
    }

    @Test
    fun `point inside the box counts as hit`() {
        val p = floatArrayOf(0f, 0f, -7.5f)
        assertTrue(OcclusionMath.segmentIntersectsAabb(p, p, wallMin, wallMax))
    }

    // ── OcclusionMath.transformAabb / expand ─────────────────────────────

    @Test
    fun `transformAabb translates the box`() {
        val m = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            10f, -2f, 5f, 1f,
        )
        val (mn, mx) = OcclusionMath.transformAabb(
            floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 2f, 3f), m,
        )
        assertTrue(mn.contentEquals(floatArrayOf(10f, -2f, 5f)))
        assertTrue(mx.contentEquals(floatArrayOf(11f, 0f, 8f)))
    }

    @Test
    fun `transformAabb with a Y-180 flip covers both corners`() {
        // VRM0 风格翻转矩阵：AABB 变换必须对齐角点并集而不是只变 min
        val m = floatArrayOf(
            -1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 0f, 0f, 1f,
        )
        val (mn, mx) = OcclusionMath.transformAabb(
            floatArrayOf(1f, 0f, 2f), floatArrayOf(2f, 1f, 3f), m,
        )
        assertEquals(-2f, mn[0], 1e-5f)
        assertEquals(-1f, mx[0], 1e-5f)
        assertEquals(0f, mn[1], 1e-5f)
        assertEquals(1f, mx[1], 1e-5f)
        assertEquals(-3f, mn[2], 1e-5f)
        assertEquals(-2f, mx[2], 1e-5f)
    }

    @Test
    fun `expand grows every axis`() {
        val (mn, mx) = OcclusionMath.expand(
            floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f), 0.5f,
        )
        assertTrue(mn.contentEquals(floatArrayOf(0.5f, 1.5f, 2.5f)))
        assertTrue(mx.contentEquals(floatArrayOf(4.5f, 5.5f, 6.5f)))
    }

    // ── ContactPoseMath ──────────────────────────────────────────────────

    private fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    @Test
    fun `heading reads forward off column 2`() {
        val m = identity()
        assertEquals(0f, ContactPoseMath.headingRad(m), 1e-5f)
        val turned = identity()
        ContactPoseMath.rotateAroundY(turned, Math.PI.toFloat() / 2) // 90° → +X
        assertEquals(Math.PI.toFloat() / 2, ContactPoseMath.headingRad(turned), 1e-4f)
    }

    @Test
    fun `rotateAroundY preserves translation and scale`() {
        val m = identity()
        m[0] = 2f; m[5] = 2f; m[10] = 2f // uniform scale 2
        m[12] = 7f; m[13] = -1f; m[14] = 3f
        ContactPoseMath.rotateAroundY(m, 1.234f)
        assertEquals(7f, m[12], 1e-5f)
        assertEquals(-1f, m[13], 1e-5f)
        assertEquals(3f, m[14], 1e-5f)
        assertEquals(2f, ContactPoseMath.uniformScale(m), 1e-5f)
    }

    @Test
    fun `lie target points the head along yaw forward with the belly up`() {
        // yaw=180（BED_LIE）：head 应指向 -Z，脸朝 +Y
        val lie = ContactPoseMath.lieTargetMatrix(identity(), 180f)
        val q = ContactPoseMath.toQuaternion(lie)
        val headAxis = applyQuat(q, floatArrayOf(0f, 1f, 0f))
        assertEquals(0f, headAxis[0], 1e-4f)
        assertEquals(0f, headAxis[1], 1e-4f)
        assertEquals(-1f, headAxis[2], 1e-4f)
        val belly = applyQuat(q, floatArrayOf(0f, 0f, 1f))
        assertEquals(0f, belly[0], 1e-4f)
        assertEquals(1f, belly[1], 1e-4f)
        assertEquals(0f, belly[2], 1e-4f)
    }

    @Test
    fun `lie target yaw 0 points the head toward plus Z`() {
        val lie = ContactPoseMath.lieTargetMatrix(identity(), 0f)
        val q = ContactPoseMath.toQuaternion(lie)
        val headAxis = applyQuat(q, floatArrayOf(0f, 1f, 0f))
        assertEquals(0f, headAxis[0], 1e-4f)
        assertEquals(0f, headAxis[1], 1e-4f)
        assertEquals(1f, headAxis[2], 1e-4f) // forward(0) = +Z
    }

    @Test
    fun `withRotation keeps scale and translation`() {
        val m = identity()
        m[0] = 3f; m[5] = 3f; m[10] = 3f
        m[12] = 1f; m[13] = 2f; m[14] = 3f
        ContactPoseMath.rotateAroundY(m, 0.7f)
        val out = ContactPoseMath.withRotation(m, ContactPoseMath.toQuaternion(identity()))
        assertEquals(3f, ContactPoseMath.uniformScale(out), 1e-5f)
        assertEquals(1f, out[12], 1e-5f)
        assertEquals(2f, out[13], 1e-5f)
        assertEquals(3f, out[14], 1e-5f)
        // 旋转被替换回 identity：列 2 恢复 +Z 方向（带缩放）
        assertEquals(0f, out[8], 1e-5f)
        assertEquals(3f, out[10], 1e-5f)
    }

    @Test
    fun `slerp endpoints and midpoint are exact`() {
        val qa = ContactPoseMath.toQuaternion(identity())
        val half = identity()
        ContactPoseMath.rotateAroundY(half, Math.PI.toFloat() / 2)
        val qb = ContactPoseMath.toQuaternion(half)
        val mid = ContactPoseMath.slerp(qa, qb, 0.5f)
        val midAngle = 2 * kotlin.math.acos(mid[3].toDouble())
        assertEquals(Math.PI / 4, midAngle, 1e-4)
        val slerpedBack = ContactPoseMath.slerp(qa, qb, 0f)
        assertTrue(qa.contentEquals(slerpedBack))
    }

    @Test
    fun `slerp handles the antipodal case without flipping`() {
        val qa = ContactPoseMath.toQuaternion(identity())
        val flipped = identity()
        ContactPoseMath.rotateAroundY(flipped, Math.PI.toFloat())
        val qb = ContactPoseMath.toQuaternion(flipped)
        // 相对两象限： halfway 仍是有效单位四元数（不 NaN）
        val mid = ContactPoseMath.slerp(qa, qb, 0.5f)
        val len = kotlin.math.sqrt(
            mid[0] * mid[0] + mid[1] * mid[1] + mid[2] * mid[2] + mid[3] * mid[3],
        )
        assertEquals(1f, len, 1e-4f)
    }

    /** Rotate v by quaternion (x, y, z, w). */
    private fun applyQuat(q: FloatArray, v: FloatArray): FloatArray {
        val (x, y, z, w) = listOf(q[0], q[1], q[2], q[3])
        val (vx, vy, vz) = listOf(v[0], v[1], v[2])
        // t = 2 q_cross × v; v' = v + w t + q_cross × t
        val tx = 2f * (y * vz - z * vy)
        val ty = 2f * (z * vx - x * vz)
        val tz = 2f * (x * vy - y * vx)
        return floatArrayOf(
            vx + w * tx + (y * tz - z * ty),
            vy + w * ty + (z * tx - x * tz),
            vz + w * tz + (x * ty - y * tx),
        )
    }

    // sanity: forward formula matches the scene asset convention
    @Test
    fun `forward convention matches asset README`() {
        // forward = (sin yaw, 0, cos yaw); yaw=90 → +X
        val yaw = Math.toRadians(90.0)
        assertEquals(1f, sin(yaw).toFloat().roundToInt().toFloat())
        assertEquals(0f, cos(yaw).toFloat().roundToInt().toFloat())
    }
}
