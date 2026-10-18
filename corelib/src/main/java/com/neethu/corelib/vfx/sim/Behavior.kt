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
     * 湍流（花瓣/烟/光尘扰动），力 = 加速度（米/秒²），apply 内乘 dt（画质
     * 升级 2026-10-17 修复：曾漏乘 dt，扰动力随帧率漂移——60fps 标定的强度
     * 在 30fps 下翻倍；修复后预设强度按加速度量纲全量重标定）。
     *
     * - 伪流场（curl=false）：力沿三轴各取「下一轴坐标」的正弦——同轴取异轴
     *   坐标避免力与坐标同相。
     * - 旋度场（curl=true，P2 完整版）：向量势 ψ = (sin(k1·p+w1t),
     *   sin(k2·p+w2t), sin(k3·p+w3t))，力 = ∇×ψ 解析展开（三个波向量互异
     *   避免退化）——数学上散度恒零（div·curl≡0），涡旋不会聚团；幅值除以
     *   基波数 k0 让 strength 在不同 [scale] 下保持同量纲标度。
     */
    internal class Turbulence(
        private val strength: Float,
        scale: Float,
        evolution: Float,
        curl: Boolean,
    ) : Behavior() {
        private val k = scale.coerceAtLeast(1e-4f)
        private val w = 2.0 * PI * evolution.coerceAtLeast(0f)
        private val curlMode = curl

        // 旋度场波向量（×k0；三个互异准随机方向）与频率失谐比
        private val k0 = 2.0 * PI / k
        private val k1x = 1.00 * k0; private val k1y = 0.55 * k0; private val k1z = 1.30 * k0; private val w1 = w * 1.00
        private val k2x = 0.80 * k0; private val k2y = 1.20 * k0; private val k2z = 0.50 * k0; private val w2 = w * 0.83
        private val k3x = 1.40 * k0; private val k3y = 0.75 * k0; private val k3z = 1.05 * k0; private val w3 = w * 0.71

        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            if (strength == 0f) return
            val t = pool.maxLife[i] - pool.life[i]
            if (curlMode) {
                forceCurl(pool.px[i], pool.py[i], pool.pz[i], t.toDouble(), scratch)
                pool.vx[i] += scratch[0] * dt
                pool.vy[i] += scratch[1] * dt
                pool.vz[i] += scratch[2] * dt
            } else {
                pool.vx[i] += (strength * sin(pool.py[i] * k + t * w) * dt).toFloat()
                pool.vy[i] += (strength * sin(pool.pz[i] * k + t * w * 0.83) * dt).toFloat()
                pool.vz[i] += (strength * sin(pool.px[i] * k + t * w * 0.71) * dt).toFloat()
            }
        }

        /** 旋度力（curl=true 的力场求值；单测散度/帧率无关性断言共用）。 */
        internal fun forceCurl(x: Float, y: Float, z: Float, t: Double, out: FloatArray) {
            val inv = strength / k0
            val c1 = kotlin.math.cos(k1x * x + k1y * y + k1z * z + w1 * t).toFloat()
            val c2 = kotlin.math.cos(k2x * x + k2y * y + k2z * z + w2 * t).toFloat()
            val c3 = kotlin.math.cos(k3x * x + k3y * y + k3z * z + w3 * t).toFloat()
            // F = ∇×ψ：Fx = ∂ψz/∂y − ∂ψy/∂z，以此类推
            out[0] = ((k3y * c3 - k2z * c2) * inv).toFloat()
            out[1] = ((k1z * c1 - k3x * c3) * inv).toFloat()
            out[2] = ((k2x * c2 - k1y * c1) * inv).toFloat()
        }

        private val scratch = FloatArray(3)
    }

    /**
     * 地面碰撞（雪落地消融/雨丝到地即逝）：y 低于地面且下落时钉回地面、
     * 竖向速度清零、水平速度指数衰减（摩擦），剩余寿命钳到 fade（落地后
     * 短促淡出；fade=0 = 立即消亡）。粒子从地面下出生（y<groundY）不触发
     * （只拦「下落穿越」），避免发射域低于地面时全体钉死。
     */
    internal class GroundCollision(
        private val groundY: Float,
        private val fadeSeconds: Float,
        private val friction: Float,
    ) : Behavior() {
        override fun apply(pool: ParticlePool, i: Int, dt: Float) {
            if (pool.py[i] > groundY || pool.vy[i] >= 0f) return
            pool.py[i] = groundY
            pool.vy[i] = 0f
            if (friction > 0f) {
                val f = exp(-friction * dt)
                pool.vx[i] *= f
                pool.vz[i] *= f
            }
            if (pool.life[i] > fadeSeconds) pool.life[i] = fadeSeconds
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

                is BehaviorParams.Turbulence ->
                    Turbulence(p.strength, p.scale, p.evolution, p.curl)

                is BehaviorParams.GroundCollision ->
                    GroundCollision(p.groundY, p.fadeSeconds.coerceAtLeast(0f), p.friction)
            }
        }
    }
}
