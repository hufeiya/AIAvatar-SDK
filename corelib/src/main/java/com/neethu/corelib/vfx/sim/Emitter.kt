package com.neethu.corelib.vfx.sim

import com.neethu.corelib.vfx.EmitterParams
import com.neethu.corelib.vfx.EmitterShape
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 发射器采样（three.quarks `shape/` 子集移植，docs/vfx-feasibility.md §4.2）：
 * 采样出初始 pos+vel 写进调用方给的 out 数组——零分配、可注入种子。
 *
 * 坐标口径：世界单位=米；[anchor] 为特效锚点世界坐标，[params].center 为
 * 相对锚点的偏移。
 */
object Emitter {

    /**
     * 采样一个粒子的出生位置与初速。
     * out 与 outVel 各 3 float（调用方复用，热路径零分配）。
     */
    fun sample(
        params: EmitterParams,
        anchorX: Float, anchorY: Float, anchorZ: Float,
        random: Random,
        out: FloatArray,
        outVel: FloatArray,
    ) {
        val cx = anchorX + params.center.x
        val cy = anchorY + params.center.y
        val cz = anchorZ + params.center.z
        val speed = sampleRange(random, params.speedMin, params.speedMax)
        when (params.shape) {
            EmitterShape.POINT -> {
                out[0] = cx; out[1] = cy; out[2] = cz
                velocityAlongDirection(params, random, speed, outVel)
            }

            EmitterShape.SPHERE -> {
                // 球体积均匀采样：方向球面均匀（z=2u−1 纬度法），半径按体积 cbrt
                val z = 2f * random.nextFloat() - 1f
                val phi = 2f * PI.toFloat() * random.nextFloat()
                val rxy = sqrt((1f - z * z).coerceIn(0f, 1f))
                val dirX = rxy * cos(phi)
                val dirY = z
                val dirZ = rxy * sin(phi)
                val rad = params.radius * Math.cbrt(random.nextDouble()).toFloat()
                out[0] = cx + dirX * rad
                out[1] = cy + dirY * rad
                out[2] = cz + dirZ * rad
                // 初速沿「中心→粒子」径向放射；速度域为 0 时退方向参数
                if (speed > 0f) {
                    outVel[0] = dirX * speed
                    outVel[1] = dirY * speed
                    outVel[2] = dirZ * speed
                } else {
                    velocityAlongDirection(params, random, speed, outVel)
                }
            }

            EmitterShape.BOX -> {
                out[0] = cx + sampleRange(random, -params.halfExtents.x, params.halfExtents.x)
                out[1] = cy + sampleRange(random, -params.halfExtents.y, params.halfExtents.y)
                out[2] = cz + sampleRange(random, -params.halfExtents.z, params.halfExtents.z)
                velocityAlongDirection(params, random, speed, outVel)
            }

            EmitterShape.RING -> {
                // XZ 平面圆周：位置在圆上，初速沿径向水平放射
                val theta = 2f * PI.toFloat() * random.nextFloat()
                val dirX = cos(theta)
                val dirZ = sin(theta)
                out[0] = cx + dirX * params.radius
                out[1] = cy
                out[2] = cz + dirZ * params.radius
                outVel[0] = dirX * speed
                outVel[1] = 0f
                outVel[2] = dirZ * speed
            }
        }
    }

    private fun velocityAlongDirection(
        params: EmitterParams,
        random: Random,
        speed: Float,
        outVel: FloatArray,
    ) {
        val d = params.direction
        val len = sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
        if (len < 1e-8f) {
            outVel[0] = 0f; outVel[1] = 0f; outVel[2] = 0f
            return
        }
        outVel[0] = d.x / len * speed
        outVel[1] = d.y / len * speed
        outVel[2] = d.z / len * speed
    }

    private fun sampleRange(random: Random, min: Float, max: Float): Float =
        if (max > min) min + random.nextFloat() * (max - min) else min

    /** 球面均匀方向的自检辅助（测试用）：方向与 +Y 夹角的反余弦。 */
    internal fun angleFromY(x: Float, y: Float, z: Float): Float =
        acos((y / sqrt(x * x + y * y + z * z).coerceAtLeast(1e-8f)).coerceIn(-1f, 1f))
}
