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
