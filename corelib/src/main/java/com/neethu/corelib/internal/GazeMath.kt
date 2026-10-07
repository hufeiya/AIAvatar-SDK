package com.neethu.corelib.internal

import kotlin.math.atan2
import kotlin.math.abs
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

    /**
     * Unit-quaternion for a rotation of [angleRad] about [axis]（轴内部归一化；
     * 模仿引擎的躯干欧拉分量绕世界轴分解用）。
     */
    fun axisAngleQuat(axis: FloatArray, angleRad: Float): FloatArray {
        val len = len3(axis)
        if (len < 1e-8f || abs(angleRad) < 1e-9f) return floatArrayOf(0f, 0f, 0f, 1f)
        val s = sin(angleRad / 2f) / len
        return floatArrayOf(axis[0] * s, axis[1] * s, axis[2] * s, cos(angleRad / 2f))
    }

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
    /**
     * Quaternion [x,y,z,w] from a column-major 4x4 rotation matrix (Shepperd's method).
     *
     * **先剥离缩放**：世界矩阵常含 transformToUnitCube 的根缩放（SK_Sun≈0.699），
     * 缩放会进入 Shepperd 的 `sqrt(trace+1)` 使提取角随真实角非线性偏移
     * （实测 θ=30° 差 ~4°）——该偏差曾经视线反馈环把呼吸的平滑小幅摆动放大成
     * 可见的头部抽搐。三列基向量各自归一化后再提取，对任意缩放精确。
     */
    fun matToQuat(m: FloatArray): FloatArray {
        val c0 = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        val c1 = sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
        val c2 = sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
        if (c0 < 1e-9f || c1 < 1e-9f || c2 < 1e-9f) {
            return floatArrayOf(0f, 0f, 0f, 1f) // 退化矩阵（不应出现），返回单位旋转
        }
        val r00 = m[0] / c0; val r10 = m[1] / c0; val r20 = m[2] / c0
        val r01 = m[4] / c1; val r11 = m[5] / c1; val r21 = m[6] / c1
        val r02 = m[8] / c2; val r12 = m[9] / c2; val r22 = m[10] / c2
        val trace = r00 + r11 + r22
        val q = FloatArray(4)
        if (trace > 0f) {
            val s = sqrt((trace + 1f).toDouble()).toFloat() * 2f
            q[3] = s * 0.25f; q[0] = (r21 - r12) / s; q[1] = (r02 - r20) / s; q[2] = (r10 - r01) / s
        } else if (r00 > r11 && r00 > r22) {
            val s = sqrt((1f + r00 - r11 - r22).toDouble()).toFloat() * 2f
            q[3] = (r21 - r12) / s; q[0] = s * 0.25f; q[1] = (r01 + r10) / s; q[2] = (r02 + r20) / s
        } else if (r11 > r22) {
            val s = sqrt((1f + r11 - r00 - r22).toDouble()).toFloat() * 2f
            q[3] = (r02 - r20) / s; q[0] = (r01 + r10) / s; q[1] = s * 0.25f; q[2] = (r12 + r21) / s
        } else {
            val s = sqrt((1f + r22 - r00 - r11).toDouble()).toFloat() * 2f
            q[3] = (r10 - r01) / s; q[0] = (r02 + r20) / s; q[1] = (r12 + r21) / s; q[2] = s * 0.25f
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
     * **rising by [pitch] from [forward]'s own elevation** — the exact inverse
     * of [signedYawPitch] for ANY forward, not just horizontal ones.
     *
     * **历史坑（SimpleDemo 头部疯转根因）**：旧实现把结果高度直接写成
     * `sin(pitch)`，等价于把相对俯仰角当绝对仰角——signedYawPitch 返回的是
     * `φt − φf`（相对），这里却建出仰角 `pitch`（丢了 forward 自身的 φf）。
     * forward 一旦离开水平（模型抬头/低头、rest 姿态带俯仰），写盘的修正量
     * 就多转 φf，视线闭环增益 >1 自激成 ±90° 周期振荡（走马灯式缓慢疯转）。
     * 主 App 里 IDLE 动画每帧重写头/颈掩掉了它；无动画场景（SimpleDemo、
     * idle_off）即触发。修法 = 结果仰角取 `φf + pitch`，任意 forward 下与
     * signedYawPitch 精确互逆。
     */
    fun dirFromYawPitch(forward: FloatArray, yaw: Float, pitch: Float): FloatArray {
        val fh = horLen(forward)
        val fx = if (fh > 1e-6f) forward[0] / fh else 0f
        val fz = if (fh > 1e-6f) forward[2] / fh else 1f
        val c = cos(yaw); val s = sin(yaw)
        val dx = fx * c + fz * s
        val dz = -fx * s + fz * c
        val elev = asinSafe(forward[1].coerceIn(-1f, 1f)) + pitch
        val cp = cos(elev)
        return normalize3(floatArrayOf(dx * cp, sin(elev), dz * cp))
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

    /**
     * Rotation part of [q] written into column-major [mat]; indices 12..14
     * (translation) are left untouched, [15] set to 1 — callers pass the
     * bone's current local matrix to swap only the rotation.
     */
    fun quatToMat(q: FloatArray, mat: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val x2 = x + x; val y2 = y + y; val z2 = z + z
        val xx = x * x2; val xy = x * y2; val xz = x * z2
        val yy = y * y2; val yz = y * z2; val zz = z * z2
        val wx = w * x2; val wy = w * y2; val wz = w * z2
        mat[0] = 1f - (yy + zz); mat[1] = xy + wz; mat[2] = xz - wy; mat[3] = 0f
        mat[4] = xy - wz; mat[5] = 1f - (xx + zz); mat[6] = yz + wx; mat[7] = 0f
        mat[8] = xz + wy; mat[9] = yz - wx; mat[10] = 1f - (xx + yy); mat[11] = 0f
        mat[15] = 1f
    }

    /**
     * Rotation angle between two unit quaternions (rad, in [0, π]; q and −q are equal).
     *
     * 实现 = 相对四元数 r = a⁻¹·b 的 θ = 2·atan2(‖r.vec‖, |r.w|)：atan2 全域
     * 数值稳定，小角度可精确解析。旧 acos 实现带 `dot ≥ 1−1e-6 → 返回 0`
     * 保护（acos 近 1 对 float32 噪声敏感），形成 **≈0.16° 测量死区**——
     * 呼吸层的 strip/动画重写快照判定全依赖小角度解析，死区内判定失灵使
     * 叠加层锁死+周期性突跳（顿挫），见 BreathBaseResolver 注释。
     */
    fun quatAngle(a: FloatArray, b: FloatArray): Float {
        val r = quatMultiply(quatInverse(a), b)
        val v = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
        return 2f * atan2(v, abs(r[3]))
    }

    /**
     * Normalized linear interpolation between unit quaternions [a]→[b], weight
     * [t]∈[0,1]（模仿引擎退场缓动用：相邻帧姿态差极小，nlerp 与 slerp 不可分
     * 辨且免三角函数）。走最短弧：dot<0 时翻转 [b] 的符号（q 与 −q 同一旋转）。
     * 端点保证精确：t=0 返回 [a]、t=1 返回 [b]（符号归一后）。
     */
    fun quatNlerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
        var bx = b[0]; var by = b[1]; var bz = b[2]; var bw = b[3]
        if (a[0] * bx + a[1] * by + a[2] * bz + a[3] * bw < 0f) {
            bx = -bx; by = -by; bz = -bz; bw = -bw
        }
        val x = a[0] + (bx - a[0]) * t
        val y = a[1] + (by - a[1]) * t
        val z = a[2] + (bz - a[2]) * t
        val w = a[3] + (bw - a[3]) * t
        val len = sqrt((x * x + y * y + z * z + w * w).toDouble()).toFloat()
        if (len < 1e-9f) return floatArrayOf(a[0], a[1], a[2], a[3])
        return floatArrayOf(x / len, y / len, z / len, w / len)
    }
}
