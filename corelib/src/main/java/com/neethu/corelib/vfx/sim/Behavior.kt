package com.neethu.corelib.vfx.sim

import com.neethu.corelib.vfx.BehaviorParams
import com.neethu.corelib.vfx.VfxMath
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 粒子行为（docs/vfx-feasibility.md §4.2）：`apply(pool, i, dt)` 逐粒子原地
 * 改写池内状态——纯 Kotlin 零引擎依赖，JVM 单测锁死。
 */
internal sealed class Behavior {
    abstract fun apply(pool: ParticlePool, i: Int, dt: Float)

    internal class Gravity(ax: Float, ay: Float, az: Float) : Behavior() {
        private val gx = ax; private val gy = ay; private val gz = az
        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            pool.vx[i] += gx * dt
            pool.vy[i] += gy * dt
            pool.vz[i] += gz * dt
        }
    }

    /** 速度指数衰减：v ← v·e^(−k·dt)（帧率无关的指数刹停）。 */
    internal class Drag(coefficient: Float) : Behavior() {
        private val k = coefficient
        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            if (k <= 0f) return
            val f = exp(-k * dt)
            pool.vx[i] *= f
            pool.vy[i] *= f
            pool.vz[i] *= f
        }
    }

    /** 生命进度 → 颜色曲线（输出 = 曲线 × 起始色：起始白 = 纯曲线渐变，
     *  palette 配色（confetti）与白色 alpha 包络相乘保留本色）。 */
    internal class ColorOverLife(keys: List<com.neethu.corelib.vfx.ColorKey>) : Behavior() {
        private val flat = VfxMath.flattenColorKeys(keys)
        private val scratch = FloatArray(4)
        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            VfxMath.sampleColorCurve(flat, pool.lifeFraction(i), scratch)
            pool.cr[i] = scratch[0] * pool.baseR[i]
            pool.cg[i] = scratch[1] * pool.baseG[i]
            pool.cb[i] = scratch[2] * pool.baseB[i]
            pool.ca[i] = scratch[3] * pool.baseA[i]
        }
    }

    /** 生命进度 → 尺寸倍率曲线（乘在逐粒子基础尺寸上）。 */
    internal class SizeOverLife(keys: List<com.neethu.corelib.vfx.ScalarKey>) : Behavior() {
        private val flat = VfxMath.flattenScalarKeys(keys)
        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            pool.curSize[i] = pool.baseSize[i] * VfxMath.sampleCurve(flat, pool.lifeFraction(i))
        }
    }

    /**
     * 横摆（雪）：沿轴向速度改写为正弦摆动。相位取「粒子已活时长」（各粒子
     * 寿命不同→相位天然错落）；速度目标式（非加速度），落速不被抬走。
     */
    internal class Sway(axis: String, private val amplitude: Float, frequency: Float, private val phase: Float) : Behavior() {
        private val axisIndex = when (axis.lowercase()) {
            "x" -> 0; "y" -> 1; else -> 2
        }
        private val omega = 2.0 * PI * frequency.coerceAtLeast(0f)
        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            val elapsed = pool.maxLife[i] - pool.life[i]
            val v = (amplitude * sin(omega * elapsed + phase)).toFloat()
            when (axisIndex) {
                0 -> pool.vx[i] = v
                1 -> pool.vy[i] = v
                else -> pool.vz[i] = v
            }
        }
    }

    /**
     * 湍流（简化三角伪流场，花瓣/烟扰动）：力沿三轴各取「下一轴坐标」的
     * 正弦——同轴取异轴坐标避免力与坐标同相；演化项随已活时长推进。
     */
    internal class Turbulence(private val strength: Float, scale: Float, evolution: Float) : Behavior() {
        private val k = scale.coerceAtLeast(1e-4f)
        private val w = 2.0 * PI * evolution.coerceAtLeast(0f)
        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            if (strength == 0f) return
            val t = (pool.maxLife[i] - pool.life[i]).toDouble()
            pool.vx[i] += strength * sin(pool.py[i] * k + t * w).toFloat()
            pool.vy[i] += strength * sin(pool.pz[i] * k + t * w * 0.83).toFloat()
            pool.vz[i] += strength * sin(pool.px[i] * k + t * w * 0.71).toFloat()
        }
    }

    companion object {
        /** BehaviorParams → 可执行 Behavior（配置期一次）。 */
        fun from(params: List<BehaviorParams>): List<Behavior> = params.map { p ->
            when (p) {
                is BehaviorParams.Gravity ->
                    Gravity(p.value.x, p.value.y, p.value.z)

                is BehaviorParams.Drag -> Drag(p.coefficient)

                is BehaviorParams.ColorOverLife -> ColorOverLife(p.keys)

                is BehaviorParams.SizeOverLife -> SizeOverLife(p.keys)

                is BehaviorParams.Sway -> Sway(p.axis, p.amplitude, p.frequency, p.phase)

                is BehaviorParams.Turbulence -> Turbulence(p.strength, p.scale, p.evolution)
            }
        }
    }
}
