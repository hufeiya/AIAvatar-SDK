package com.neethu.corelib.vfx.sim

import com.neethu.corelib.vfx.BehaviorParams
import com.neethu.corelib.vfx.VfxMath
import kotlin.math.exp

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

    /** 生命进度 → 颜色曲线（覆盖逐粒子起始色）。 */
    internal class ColorOverLife(keys: List<com.neethu.corelib.vfx.ColorKey>) : Behavior() {
        private val flat = VfxMath.flattenColorKeys(keys)
        private val scratch = FloatArray(4)
        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            VfxMath.sampleColorCurve(flat, pool.lifeFraction(i), scratch)
            pool.cr[i] = scratch[0]
            pool.cg[i] = scratch[1]
            pool.cb[i] = scratch[2]
            pool.ca[i] = scratch[3]
        }
    }

    /** 生命进度 → 尺寸倍率曲线（乘在逐粒子基础尺寸上）。 */
    internal class SizeOverLife(keys: List<com.neethu.corelib.vfx.ScalarKey>) : Behavior() {
        private val flat = VfxMath.flattenScalarKeys(keys)
        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            pool.curSize[i] = pool.baseSize[i] * VfxMath.sampleCurve(flat, pool.lifeFraction(i))
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
            }
        }
    }
}
