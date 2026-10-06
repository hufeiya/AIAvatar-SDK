package com.neethu.aiavatar_sdk.video

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * HeadPoseMath 纯函数单测：列主序 4×4 → (yawDeg, pitchDeg) 的数学与不变量。
 * 用构造矩阵做已知答案回归——**符号约定在此锁死**（R=Ry(yaw)·Rx(pitch) 的
 * 正角分解出正角）；屏幕方向映射不在这里（真机标定收在 LookHereTuning）。
 * 缩放/平移不变性是呼吸系统 matToQuat 提取偏差同款坑的回归锁。
 */
class HeadPoseMathTest {

    /** 构造 R = Ry(yaw)·Rx(pitch)（行主序）→ 列主序 4×4，可带均匀缩放与平移。 */
    private fun ryRxMatrix(
        yawDeg: Double,
        pitchDeg: Double,
        scale: Double = 1.0,
        translation: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0),
    ): FloatArray {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        val cy = cos(y); val sy = sin(y)
        val cp = cos(p); val sp = sin(p)
        val m00 = cy; val m01 = sy * sp; val m02 = sy * cp
        val m10 = 0.0; val m11 = cp; val m12 = -sp
        val m20 = -sy; val m21 = cy * sp; val m22 = cy * cp
        fun f(d: Double) = (d * scale).toFloat()
        return floatArrayOf(
            f(m00), f(m10), f(m20), 0f,
            f(m01), f(m11), f(m21), 0f,
            f(m02), f(m12), f(m22), 0f,
            translation[0].toFloat(), translation[1].toFloat(), translation[2].toFloat(), 1f,
        )
    }

    @Test
    fun `round trips yaw and pitch`() {
        for (yaw in -40..40 step 20) {
            for (pitch in -24..24 step 12) {
                val (y, p) = HeadPoseMath.yawPitchDeg(ryRxMatrix(yaw.toDouble(), pitch.toDouble()))
                assertEquals("yaw@$yaw/$pitch", yaw.toDouble(), y.toDouble(), 1e-3)
                assertEquals("pitch@$yaw/$pitch", pitch.toDouble(), p.toDouble(), 1e-3)
            }
        }
    }

    @Test
    fun `uniform scale does not bias the angles`() {
        // 呼吸系统 matToQuat 的教训：含缩放的矩阵直接提角有几度级偏差
        val base = HeadPoseMath.yawPitchDeg(ryRxMatrix(30.0, 15.0))
        val scaled = HeadPoseMath.yawPitchDeg(ryRxMatrix(30.0, 15.0, scale = 0.699))
        assertEquals(base.first, scaled.first, 1e-4f)
        assertEquals(base.second, scaled.second, 1e-4f)
    }

    @Test
    fun `translation column is ignored`() {
        val base = HeadPoseMath.yawPitchDeg(ryRxMatrix(-25.0, 10.0))
        val moved = HeadPoseMath.yawPitchDeg(
            ryRxMatrix(-25.0, 10.0, translation = doubleArrayOf(1.2, -0.4, 3.1)),
        )
        assertEquals(base.first, moved.first, 1e-4f)
        assertEquals(base.second, moved.second, 1e-4f)
    }

    @Test
    fun `signs follow the constructed rotation`() {
        val (y, p) = HeadPoseMath.yawPitchDeg(ryRxMatrix(30.0, 15.0))
        assertEquals(30.0, y.toDouble(), 1e-3)
        assertEquals(15.0, p.toDouble(), 1e-3)
    }
}
