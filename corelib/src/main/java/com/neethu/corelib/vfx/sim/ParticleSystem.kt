package com.neethu.corelib.vfx.sim

import com.neethu.corelib.vfx.EmitterParams
import com.neethu.corelib.vfx.Overflow
import com.neethu.corelib.vfx.VfxMath
import com.neethu.corelib.vfx.VfxSpec
import kotlin.math.PI
import kotlin.random.Random

/**
 * 粒子系统编排器（docs/vfx-feasibility.md §4.2 第一层）：发射预算累积 →
 * 积分 → 行为 → 直接写出 Billboard 顶点字节到调用方的 FloatArray——
 * 无任何引擎类型，确定性种子下逐帧结果可断言（JVM 单测）。
 *
 * 每帧时序（[update]）：dt 钳制 → 发射（burst 一次性 / rate 预算累积）→
 * 老化回收（swap-with-last）→ 行为（重力/拖拽/曲线）→ 积分 → 顶点写出。
 * 全程零分配（out 与 scratch 全部预分配复用）。
 */
internal class ParticleSystem(
    spec: VfxSpec,
    private val random: Random,
) {
    val pool = ParticlePool(spec.maxParticles.coerceAtLeast(1))

    private val emitterParams: EmitterParams = spec.emitter
    private val behaviors: List<Behavior> = Behavior.from(spec.behaviors)
    private val lifetimeMin = spec.lifetimeMin.coerceAtLeast(0.01f)
    private val lifetimeMax = spec.lifetimeMax.coerceAtLeast(lifetimeMin)
    private val sizeMin = spec.sizeMin.coerceAtLeast(0.001f)
    private val sizeMax = spec.sizeMax.coerceAtLeast(sizeMin)
    private val spinMin = spec.spinMin * DEG_TO_RAD
    private val spinMax = spec.spinMax * DEG_TO_RAD
    private val startColor = spec.startColor
    private val stretch = spec.stretchByVelocity
    private val rate = spec.rate.coerceAtLeast(0f)
    private val burst = spec.burst.coerceAtLeast(0)
    private val overflow = spec.overflow

    private var emitAccumulator = 0f
    private var burstDone = burst <= 0

    /** 一次性特效是否已播完（burst 已放且池空）。持续型恒 false，直到 stop。 */
    val isFinished: Boolean
        get() = burstDone && rate <= 0f && pool.activeCount == 0

    private val samplePos = FloatArray(3)
    private val sampleVel = FloatArray(3)
    private val rotRight = FloatArray(3)
    private val rotUp = FloatArray(3)
    private val longAxis = FloatArray(3)
    private val widthAxis = FloatArray(3)

    /**
     * 推进一帧并把活跃粒子写成 Billboard 顶点（含颜色），写入 [out] 的
     * [outFloatIndex] 起（调用方保证容量 ≥ activeCount × [VfxMath.FLOATS_PER_PARTICLE]）。
     * @return 活跃粒子数（0 = 本帧无粒子）。
     */
    fun update(
        dtRaw: Float,
        anchorX: Float, anchorY: Float, anchorZ: Float,
        right: FloatArray, up: FloatArray, forward: FloatArray,
        out: FloatArray,
        outFloatIndex: Int,
    ): Int {
        val dt = dtRaw.coerceIn(DT_MIN, DT_MAX)
        emit(dt, anchorX, anchorY, anchorZ)
        ageAndKill(dt)
        for (b in behaviors) {
            var i = 0
            val n = pool.activeCount
            while (i < n) {
                b.apply(pool, i, dt)
                i++
            }
        }
        integrate(dt)
        return writeVertices(right, up, forward, out, outFloatIndex)
    }

    /** 立即清空全部粒子（stopVfx「当帧清空」）。 */
    fun clear() {
        pool.clear()
        emitAccumulator = 0f
    }

    // ── 内部时序 ─────────────────────────────────────────────────────

    private fun emit(dt: Float, anchorX: Float, anchorY: Float, anchorZ: Float) {
        if (!burstDone) {
            burstDone = true
            spawn(burst, anchorX, anchorY, anchorZ)
        }
        if (rate > 0f) {
            emitAccumulator += rate * dt
            var n = emitAccumulator.toInt()
            emitAccumulator -= n
            // dt 钳制下预算不会爆，但防御无意义大数字
            if (n > pool.capacity) n = pool.capacity
            if (n > 0) spawn(n, anchorX, anchorY, anchorZ)
        }
    }

    private fun spawn(count: Int, anchorX: Float, anchorY: Float, anchorZ: Float) {
        for (k in 0 until count) {
            Emitter.sample(emitterParams, anchorX, anchorY, anchorZ, random, samplePos, sampleVel)
            val lifeSec = sampleRange(random, lifetimeMin, lifetimeMax)
            val size = sampleRange(random, sizeMin, sizeMax)
            val spin = sampleRange(random, spinMin, spinMax)
            val slot = pool.append(
                x = samplePos[0], y = samplePos[1], z = samplePos[2],
                velX = sampleVel[0], velY = sampleVel[1], velZ = sampleVel[2],
                lifeSec = lifeSec, size = size,
                angleRad = 0f, angVelRad = spin,
                r = startColor.r, g = startColor.g, b = startColor.b, a = startColor.a,
            )
            if (slot >= 0) continue
            when (overflow) {
                Overflow.REJECT -> return
                Overflow.RECYCLE_OLDEST -> pool.overwrite(
                    i = pool.findShortestLife(),
                    x = samplePos[0], y = samplePos[1], z = samplePos[2],
                    velX = sampleVel[0], velY = sampleVel[1], velZ = sampleVel[2],
                    lifeSec = lifeSec, size = size,
                    angleRad = 0f, angVelRad = spin,
                    r = startColor.r, g = startColor.g, b = startColor.b, a = startColor.a,
                )
            }
        }
    }

    private fun ageAndKill(dt: Float) {
        var i = pool.activeCount - 1
        while (i >= 0) {
            pool.life[i] -= dt
            if (pool.life[i] <= 0f) pool.killAt(i)
            i--
        }
    }

    private fun integrate(dt: Float) {
        val n = pool.activeCount
        for (i in 0 until n) {
            pool.px[i] += pool.vx[i] * dt
            pool.py[i] += pool.vy[i] * dt
            pool.pz[i] += pool.vz[i] * dt
            if (pool.angularVel[i] != 0f) pool.angle[i] += pool.angularVel[i] * dt
        }
    }

    private fun writeVertices(
        right: FloatArray,
        up: FloatArray,
        forward: FloatArray,
        out: FloatArray,
        outFloatIndex: Int,
    ): Int {
        val n = pool.activeCount
        var o = outFloatIndex
        for (i in 0 until n) {
            val s = pool.curSize[i]
            if (stretch > 0f) {
                // 速度拉伸（雨丝）：长轴沿速度，短轴垂直于速度×视线
                VfxMath.stretchAxes(
                    pool.vx[i], pool.vy[i], pool.vz[i], forward, right, up,
                    longAxis, widthAxis,
                )
                VfxMath.writeParticle(
                    out, o,
                    pool.px[i], pool.py[i], pool.pz[i],
                    widthAxis, longAxis, s, s * stretch,
                )
            } else if (pool.angle[i] != 0f) {
                VfxMath.rotateBasis(right, up, pool.angle[i], rotRight, rotUp)
                VfxMath.writeParticle(out, o, pool.px[i], pool.py[i], pool.pz[i], rotRight, rotUp, s, s)
            } else {
                VfxMath.writeParticle(out, o, pool.px[i], pool.py[i], pool.pz[i], right, up, s, s)
            }
            val r = pool.cr[i]; val g = pool.cg[i]; val b = pool.cb[i]; val a = pool.ca[i]
            var vo = o + VfxMath.COLOR_OFFSET
            for (v in 0 until 4) {
                out[vo] = r; out[vo + 1] = g; out[vo + 2] = b; out[vo + 3] = a
                vo += VfxMath.FLOATS_PER_VERTEX
            }
            o += VfxMath.FLOATS_PER_PARTICLE
        }
        return n
    }

    private fun sampleRange(random: Random, min: Float, max: Float): Float =
        if (max > min) min + random.nextFloat() * (max - min) else min

    companion object {
        const val DT_MIN = 0.001f
        const val DT_MAX = 0.05f
        private const val DEG_TO_RAD = (PI.toFloat() / 180f)
    }
}
