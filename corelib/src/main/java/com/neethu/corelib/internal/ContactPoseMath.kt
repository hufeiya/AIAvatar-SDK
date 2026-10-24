package com.neethu.corelib.internal

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure matrix/quaternion math for contact pose alignment (sit/lie). Column-
 * major 4×4, matching Filament's TransformManager layout. No Filament/android
 * dependencies — JVM testable.
 *
 * The avatar root matrix carries a uniform scale (transformToUnitCube) and —
 * for VRM 0.x — a baked 180° Y flip inside its 3×3 block; every helper here
 * preserves both by operating on the actual matrix contents (never rebuilding
 * from assumed-clean rotations).
 */
internal object ContactPoseMath {

    /** World yaw (radians) of a root matrix: forward = (sin h, 0, cos h) reads off column 2. */
    fun headingRad(m: FloatArray): Float = atan2(m[8], m[10])

    /**
     * Left-multiply the 3×3 block by Ry(θ) in place (world-Y turn about the
     * body axis; translation column untouched, baked scale/flip preserved) —
     * the same primitive the locomotion layer uses for turns.
     */
    fun rotateAroundY(m: FloatArray, theta: Float) {
        val c = cos(theta)
        val s = sin(theta)
        for (col in 0..2) {
            val i = col * 4
            val x = m[i]
            val z = m[i + 2]
            m[i] = c * x + s * z
            m[i + 2] = -s * x + c * z
        }
    }

    /** Column-major 4×4 multiply: out = a·b. */
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (row in 0..3) {
            for (col in 0..3) {
                var sum = 0f
                for (k in 0..3) sum += a[row + k * 4] * b[k + col * 4]
                out[row + col * 4] = sum
            }
        }
        return out
    }

    /** World-Y rotation matrix Ry(θ), column-major 4×4. */
    fun rotationY(theta: Float): FloatArray = floatArrayOf(
        cos(theta), 0f, -sin(theta), 0f,
        0f, 1f, 0f, 0f,
        sin(theta), 0f, cos(theta), 0f,
        0f, 0f, 0f, 1f,
    )

    /** World-X rotation matrix Rx(θ), column-major 4×4. */
    fun rotationX(theta: Float): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, cos(theta), sin(theta), 0f,
        0f, -sin(theta), cos(theta), 0f,
        0f, 0f, 0f, 1f,
    )

    /**
     * Uniform scale of a root matrix (average column length of the 3×3 block).
     */
    fun uniformScale(m: FloatArray): Float {
        val l0 = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        val l1 = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
        val l2 = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
        return (l0 + l1 + l2) / 3f
    }

    /**
     * Rotation-only matrix with the same uniform scale and translation as
     * [source], but the rotation taken from quaternion [q] (x, y, z, w).
     */
    fun withRotation(source: FloatArray, q: FloatArray): FloatArray {
        val s = uniformScale(source)
        val (x, y, z, w) = listOf(q[0], q[1], q[2], q[3])
        // Column-major rotation from unit quaternion, pre-scaled.
        val out = FloatArray(16)
        out[0] = s * (1f - 2f * (y * y + z * z))
        out[1] = s * (2f * (x * y + z * w))
        out[2] = s * (2f * (x * z - y * w))
        out[4] = s * (2f * (x * y - z * w))
        out[5] = s * (1f - 2f * (x * x + z * z))
        out[6] = s * (2f * (y * z + x * w))
        out[8] = s * (2f * (x * z + y * w))
        out[9] = s * (2f * (y * z - x * w))
        out[10] = s * (1f - 2f * (x * x + y * y))
        out[12] = source[12]
        out[13] = source[13]
        out[14] = source[14]
        out[15] = 1f
        return out
    }

    /** Rotation quaternion (x, y, z, w) from a column-major matrix's 3×3 block. */
    fun toQuaternion(m: FloatArray): FloatArray {
        // Orthonormalize the basis columns first — the root matrix carries a
        // uniform scale; strip it so the quaternion stays unit length.
        val s = uniformScale(m).coerceAtLeast(1e-9f)
        val m00 = m[0] / s
        val m10 = m[1] / s
        val m20 = m[2] / s
        val m01 = m[4] / s
        val m11 = m[5] / s
        val m21 = m[6] / s
        val m02 = m[8] / s
        val m12 = m[9] / s
        val m22 = m[10] / s
        val tr = m00 + m11 + m22
        return if (tr > 0f) {
            val sq = sqrt(tr + 1f) * 2f
            val w = 0.25f * sq
            floatArrayOf(
                (m21 - m12) / sq,
                (m02 - m20) / sq,
                (m10 - m01) / sq,
                w,
            )
        } else if (m00 > m11 && m00 > m22) {
            val sq = sqrt(1f + m00 - m11 - m22) * 2f
            floatArrayOf(
                0.25f * sq,
                (m01 + m10) / sq,
                (m02 + m20) / sq,
                (m21 - m12) / sq,
            )
        } else if (m11 > m22) {
            val sq = sqrt(1f + m11 - m00 - m22) * 2f
            floatArrayOf(
                (m01 + m10) / sq,
                0.25f * sq,
                (m12 + m21) / sq,
                (m02 - m20) / sq,
            )
        } else {
            val sq = sqrt(1f + m22 - m00 - m11) * 2f
            floatArrayOf(
                (m02 + m20) / sq,
                (m12 + m21) / sq,
                0.25f * sq,
                (m10 - m01) / sq,
            )
        }
    }

    /** Slerp between unit quaternions [a] and [b]; t ∈ [0, 1]. */
    fun slerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        var dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        var bx = b[0]
        var by = b[1]
        var bz = b[2]
        var bw = b[3]
        if (dot < 0f) {
            dot = -dot
            bx = -bx
            by = -by
            bz = -bz
            bw = -bw
        }
        if (dot > 0.9995f) {
            // Nearly identical: lerp + normalize is numerically stable here.
            val x = a[0] + t * (bx - a[0])
            val y = a[1] + t * (by - a[1])
            val z = a[2] + t * (bz - a[2])
            val w = a[3] + t * (bw - a[3])
            val len = sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(1e-9f)
            return floatArrayOf(x / len, y / len, z / len, w / len)
        }
        val theta = kotlin.math.acos(dot.coerceIn(-1f, 1f))
        val sinTheta = sin(theta)
        val wa = sin((1f - t) * theta) / sinTheta
        val wb = sin(t * theta) / sinTheta
        return floatArrayOf(
            a[0] * wa + bx * wb,
            a[1] * wa + by * wb,
            a[2] * wa + bz * wb,
            a[3] * wa + bw * wb,
        )
    }

    /** Smooth 0→1 ease (smoothstep) for pose transitions. */
    fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /**
     * Target root rotation for a supine lie: head along `forward(yawDeg)` (the
     * feet→head body axis from the scene asset), belly up. Composed in world
     * space on top of the captured stand matrix so the baked VRM0 flip and
     * unit-cube scale survive.
     */
    fun lieTargetMatrix(standMatrix: FloatArray, yawDeg: Float): FloatArray {
        val yawRad = Math.toRadians(yawDeg.toDouble()).toFloat()
        // Rx(−90) tips the standing body flat with the face up (head toward −Z,
        // the yawDeg=180 direction); Ry(yawDeg + 180) then points the head
        // along forward(yawDeg). Applied left-to-world on the stand matrix.
        val tip = rotationX(-F_PI_2)
        val turn = rotationY(yawRad + Math.PI.toFloat())
        return mul(turn, mul(tip, standMatrix))
    }

    private val F_PI_2 = (Math.PI / 2).toFloat()
}
