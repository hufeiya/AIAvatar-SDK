package com.neethu.corelib.internal

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure quaternion/direction math for the gaze (look-at) overlay. Free of
 * Filament types so it unit-tests on the JVM; the quaternion helpers mirror
 * VrmaAnimationEngine's private ones ([x, y, z, w], Hamilton product,
 * column-major 4x4 matrices).
 */
internal object GazeMath {

    fun quatMultiply(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
        return floatArrayOf(
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw,
            aw * bw - ax * bx - ay * by - az * bz,
        )
    }

    fun quatInverse(q: FloatArray): FloatArray = floatArrayOf(-q[0], -q[1], -q[2], q[3])

    /** Rotate [v] by [q] (v' = q·v·q⁻¹, expanded via the two-cross trick). */
    fun rotateVector(q: FloatArray, v: FloatArray): FloatArray {
        val qx = q[0]; val qy = q[1]; val qz = q[2]; val qw = q[3]
        val tx = 2f * (qy * v[2] - qz * v[1])
        val ty = 2f * (qz * v[0] - qx * v[2])
        val tz = 2f * (qx * v[1] - qy * v[0])
        return floatArrayOf(
            v[0] + qw * tx + (qy * tz - qz * ty),
            v[1] + qw * ty + (qz * tx - qx * tz),
            v[2] + qw * tz + (qx * ty - qy * tx),
        )
    }

    /** Quaternion [x,y,z,w] from a column-major 4x4 rotation matrix (Shepperd's method). */
    fun matToQuat(m: FloatArray): FloatArray {
        val m00 = m[0]; val m01 = m[4]; val m02 = m[8]
        val m10 = m[1]; val m11 = m[5]; val m12 = m[9]
        val m20 = m[2]; val m21 = m[6]; val m22 = m[10]
        val trace = m00 + m11 + m22
        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt((trace + 1f).toDouble()).toFloat() * 2f
            q[3] = s * 0.25f; q[0] = (m21 - m12) / s; q[1] = (m02 - m20) / s; q[2] = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt((1f + m00 - m11 - m22).toDouble()).toFloat() * 2f
            q[3] = (m21 - m12) / s; q[0] = s * 0.25f; q[1] = (m01 + m10) / s; q[2] = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt((1f + m11 - m00 - m22).toDouble()).toFloat() * 2f
            q[3] = (m02 - m20) / s; q[0] = (m01 + m10) / s; q[1] = s * 0.25f; q[2] = (m12 + m21) / s
        } else {
            val s = sqrt((1f + m22 - m00 - m11).toDouble()).toFloat() * 2f
            q[3] = (m10 - m01) / s; q[0] = (m02 + m20) / s; q[1] = (m12 + m21) / s; q[2] = s * 0.25f
        }
        val len = sqrt((q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).toDouble()).toFloat()
        if (len > 0f) { q[0] /= len; q[1] /= len; q[2] /= len; q[3] /= len }
        return q
    }

    fun normalize3(v: FloatArray): FloatArray {
        val len = len3(v)
        return if (len > 1e-8f) floatArrayOf(v[0] / len, v[1] / len, v[2] / len) else floatArrayOf(0f, 0f, 1f)
    }

    /**
     * Signed yaw (about world +Y) and pitch (elevation difference) taking
     * [forward] onto [toTarget]; both inputs unit vectors. Yaw is 0 when
     * either horizontal projection degenerates (target straight above/below).
     */
    fun signedYawPitch(forward: FloatArray, toTarget: FloatArray): FloatArray {
        val fh = horLen(forward)
        val dh = horLen(toTarget)
        val yaw = if (fh < 1e-6f || dh < 1e-6f) 0f
        else atan2(
            forward[2] * toTarget[0] - forward[0] * toTarget[2],
            forward[0] * toTarget[0] + forward[2] * toTarget[2],
        )
        val pitch = asinSafe(toTarget[1]) - asinSafe(forward[1])
        return floatArrayOf(yaw, pitch)
    }

    /**
     * Unit direction reached from [forward] by rotating [yaw] about +Y and then
     * rising by [pitch] — the exact inverse of [signedYawPitch] (up to float
     * noise), so a (yaw, pitch) pair round-trips through both.
     */
    fun dirFromYawPitch(forward: FloatArray, yaw: Float, pitch: Float): FloatArray {
        val fh = horLen(forward)
        val fx = if (fh > 1e-6f) forward[0] / fh else 0f
        val fz = if (fh > 1e-6f) forward[2] / fh else 1f
        val c = cos(yaw); val s = sin(yaw)
        val dx = fx * c + fz * s
        val dz = -fx * s + fz * c
        val cp = cos(pitch)
        return normalize3(floatArrayOf(dx * cp, sin(pitch), dz * cp))
    }

    /**
     * Shortest-arc rotation taking unit vector [from] onto [to]. At the 180°
     * antiparallel edge case the axis is any perpendicular (up if possible).
     */
    fun quatFromTo(from: FloatArray, to: FloatArray): FloatArray {
        val d = from[0] * to[0] + from[1] * to[1] + from[2] * to[2]
        if (d > 0.99999f) return floatArrayOf(0f, 0f, 0f, 1f)
        if (d < -0.99999f) {
            var axis = floatArrayOf(0f, 1f, 0f).let { up -> cross3(from, up) }
            if (len3(axis) < 1e-6f) axis = floatArrayOf(1f, 0f, 0f)
            val a = normalize3(axis)
            return floatArrayOf(a[0], a[1], a[2], 0f)
        }
        val q = floatArrayOf(
            from[1] * to[2] - from[2] * to[1],
            from[2] * to[0] - from[0] * to[2],
            from[0] * to[1] - from[1] * to[0],
            1f + d,
        )
        val len = sqrt((q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).toDouble()).toFloat()
        q[0] /= len; q[1] /= len; q[2] /= len; q[3] /= len
        return q
    }

    private fun cross3(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun len3(a: FloatArray): Float =
        sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])

    private fun horLen(a: FloatArray): Float = sqrt(a[0] * a[0] + a[2] * a[2])

    private fun asinSafe(x: Float): Float = kotlin.math.asin(x.coerceIn(-1f, 1f))

    /** Rotation angle between two unit quaternions (rad, in [0, π]; q and −q are equal). */
    fun quatAngle(a: FloatArray, b: FloatArray): Float {
        val d = kotlin.math.abs(a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3])
        // acos 在 1 附近对浮点噪声极敏感（dot=1-2.4e-7 → 6.9e-4），近 1 直接归零
        if (d >= 1f - 1e-6f) return 0f
        return 2f * kotlin.math.acos(d.coerceIn(0f, 1f))
    }
}
