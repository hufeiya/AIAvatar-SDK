package com.neethu.corelib.accessory

/**
 * 配饰局部变换数学（纯函数，可单测）。
 *
 * 列主序 4×4（TransformManager 约定，与 SpawnMath 同存储布局）：
 * `local = T(position) · R(YXZ 内在欧拉) · S(scale)`。
 */
internal object AccessoryMath {

    /**
     * 骨骼局部系 TRS。YXZ 内在欧拉 = `R = Ry(rx) · Rx(rx) · Rz(rz)`，
     * 即先绕局部 Z、再 X、再 Y（three.js Euler 'YXZ' 同序）。
     */
    fun localTransform(position: FloatArray, rotationEulerYXZ: FloatArray, scale: Float): FloatArray {
        val r = rotationYXZ(
            Math.toRadians(rotationEulerYXZ[0].toDouble()).toFloat(),
            Math.toRadians(rotationEulerYXZ[1].toDouble()).toFloat(),
            Math.toRadians(rotationEulerYXZ[2].toDouble()).toFloat(),
        )
        return floatArrayOf(
            r[0] * scale, r[3] * scale, r[6] * scale, 0f,
            r[1] * scale, r[4] * scale, r[7] * scale, 0f,
            r[2] * scale, r[5] * scale, r[8] * scale, 0f,
            position[0], position[1], position[2], 1f,
        )
    }

    /**
     * 世界挂载 TRS（P1 道具系统 §6.2 地面/场景道具）：
     * `world = T(锚点) · Ry(yaw) · local`——锚点取交互点表坐标（SDK 世界米，
     * yawDeg 0=+Z），local 为锚点之上的逐资产微调（出厂模板/校准 preset 同链）。
     */
    fun worldTransform(
        mount: AccessoryWorldMount,
        position: FloatArray,
        rotationEulerYXZ: FloatArray,
        scale: Float,
    ): FloatArray {
        val local = localTransform(position, rotationEulerYXZ, scale)
        val yaw = Math.toRadians(mount.yawDeg.toDouble()).toFloat()
        val c = kotlin.math.cos(yaw)
        val s = kotlin.math.sin(yaw)
        // W = T·Ry·L：Ry 作用于 L 的全部四列（三个基向量列 + 平移列，列主序
        // 逐列旋转），锚点平移最后加——局部微调偏移随 yaw 一同转向
        val out = FloatArray(16)
        for (col in 0..3) {
            val i = col * 4
            val x = local[i]
            val z = local[i + 2]
            out[i] = c * x + s * z
            out[i + 1] = local[i + 1]
            out[i + 2] = -s * x + c * z
            out[i + 3] = if (col == 3) 1f else 0f
        }
        out[12] += mount.x
        out[13] += mount.y
        out[14] += mount.z
        return out
    }

    /** `R = Ry·Rx·Rz` 的 3×3（行主序 9 元）。 */
    internal fun rotationYXZ(rx: Float, ry: Float, rz: Float): FloatArray {
        val cx = kotlin.math.cos(rx); val sx = kotlin.math.sin(rx)
        val cy = kotlin.math.cos(ry); val sy = kotlin.math.sin(ry)
        val cz = kotlin.math.cos(rz); val sz = kotlin.math.sin(rz)
        return floatArrayOf(
            cy * cz + sy * sx * sz, -cy * sz + sy * sx * cz, sy * cx,
            cx * sz, cx * cz, -sx,
            -sy * cz + cy * sx * sz, sy * sz + cy * sx * cz, cy * cx,
        )
    }
}
