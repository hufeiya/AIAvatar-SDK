package com.neethu.corelib.vfx.sim

import com.neethu.corelib.vfx.EmitterParams
import com.neethu.corelib.vfx.Overflow
import com.neethu.corelib.vfx.VfxMath
import com.neethu.corelib.vfx.VfxRenderMode
import com.neethu.corelib.vfx.VfxSpec
import kotlin.math.PI
import kotlin.math.exp
import kotlin.random.Random

/**
 * 粒子系统编排器（docs/vfx-feasibility.md §4.2 第一层 + 画质升级 2026-10-17）：
 * 发射预算累积 → 积分 → 行为 → 风 → 直接写出顶点字节到调用方的 FloatArray——
 * 无任何引擎类型，确定性种子下逐帧结果可断言（JVM 单测）。
 *
 * 每帧时序（[update]）：dt 钳制 → 发射（burst 一次性 / rate 预算累积）→
 * 老化回收（swap-with-last）→ 行为（重力/拖拽/曲线/地面碰撞）→ 风场速度
 * 趋向（水平）→ 积分 → 顶点写出（Billboard 四顶点 / CROSS 八顶点）。
 * 全程零分配（out 与 scratch 全部预分配复用）。
 *
 * [windT] 为全局风时钟秒（VfxManager 单调推进传进来）：同管理器下所有带风
 * 特效阵风同相，头发弹簧骨骼亦取同一采样。
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
    private val tumbleMin = spec.tumbleMin * DEG_TO_RAD
    private val tumbleMax = spec.tumbleMax * DEG_TO_RAD
    private val startColor = spec.startColor
    private val stretch = spec.stretchByVelocity
    private val rate = spec.rate.coerceAtLeast(0f)
    private val burst = spec.burst.coerceAtLeast(0)
    private val overflow = spec.overflow
    private val renderMode = spec.renderMode
    private val windField: WindField? = spec.wind?.let { WindField(it) }

    /** 每粒子顶点数（BILLBOARD=4 / CROSS=8）与每粒子 float 步长。 */
    val verticesPerParticle: Int get() = if (renderMode == VfxRenderMode.CROSS) 8 else 4
    val floatsPerParticle: Int get() = VfxMath.FLOATS_PER_VERTEX * verticesPerParticle

    private var emitAccumulator = 0f
    private var burstDone = burst <= 0

    // flipbook 子矩形步长（配置期一次；null = 整图）
    private val flipDu: Float = spec.flipbook?.let { 1f / it.columns } ?: 1f
    private val flipDv: Float = spec.flipbook?.let { 1f / it.rows } ?: 1f
    private val flipColumns: Int = spec.flipbook?.columns ?: 1
    private val flipTotal: Int = spec.flipbook?.totalFrames ?: 1

    // 随机取色表（confetti；null = 恒 startColor）
    private val palette: List<com.neethu.corelib.vfx.VfxColor>? = spec.palette?.takeIf { it.isNotEmpty() }

    /** 一次性特效是否已播完（burst 已放且池空）。持续型恒 false，直到 stop。 */
    val isFinished: Boolean
        get() = burstDone && rate <= 0f && pool.activeCount == 0

    private val samplePos = FloatArray(3)
    private val sampleVel = FloatArray(3)
    private val rotRight = FloatArray(3)
    private val rotUp = FloatArray(3)
    private val longAxis = FloatArray(3)
    private val widthAxis = FloatArray(3)
    private val basisX = FloatArray(3)
    private val basisY = FloatArray(3)
    private val basisZ = FloatArray(3)
    private val windScratch = FloatArray(2)

    /**
     * 推进一帧并把活跃粒子写成顶点（含颜色），写入 [out] 的 [outFloatIndex]
     * 起（调用方保证容量 ≥ activeCount × [floatsPerParticle]）。
     * @return 活跃粒子数（0 = 本帧无粒子）。
     */
    fun update(
        dtRaw: Float,
        windT: Double,
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
        applyWind(dt, windT)
        integrate(dt)
        return writeVertices(right, up, forward, out, outFloatIndex)
    }

    /** 立即清空全部粒子（stopVfx「当帧清空」）。 */
    fun clear() {
        pool.clear()
        emitAccumulator = 0f
    }

    /** 环境风场访问（VfxManager.ambientWind 挑「最强带风特效」用；null=无风）。 */
    internal fun windFieldOrNull(): WindField? = windField

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

    /** 发射 [count] 个；@return 实际成功入池数（REJECT 满员提前止）。 */
    private fun spawn(count: Int, anchorX: Float, anchorY: Float, anchorZ: Float): Int {
        val pal = palette
        val cross = renderMode == VfxRenderMode.CROSS
        var spawned = 0
        for (k in 0 until count) {
            Emitter.sample(emitterParams, anchorX, anchorY, anchorZ, random, samplePos, sampleVel)
            val lifeSec = sampleRange(random, lifetimeMin, lifetimeMax)
            val size = sampleRange(random, sizeMin, sizeMax)
            val spin = sampleRange(random, spinMin, spinMax)
            val color = if (pal != null) pal[random.nextInt(pal.size)] else startColor
            val slot = pool.append(
                x = samplePos[0], y = samplePos[1], z = samplePos[2],
                velX = sampleVel[0], velY = sampleVel[1], velZ = sampleVel[2],
                lifeSec = lifeSec, size = size,
                angleRad = 0f, angVelRad = spin,
                r = color.r, g = color.g, b = color.b, a = color.a,
            )
            var idx = slot
            if (slot < 0) {
                when (overflow) {
                    Overflow.REJECT -> return spawned
                    Overflow.RECYCLE_OLDEST -> {
                        idx = pool.findShortestLife()
                        pool.overwrite(
                            i = idx,
                            x = samplePos[0], y = samplePos[1], z = samplePos[2],
                            velX = sampleVel[0], velY = sampleVel[1], velZ = sampleVel[2],
                            lifeSec = lifeSec, size = size,
                            angleRad = 0f, angVelRad = spin,
                            r = color.r, g = color.g, b = color.b, a = color.a,
                        )
                    }
                }
            }
            if (cross) {
                // 三维翻滚：逐轴幅值采样 + 随机正负，初始姿态全随机
                pool.rotYaw[idx] = random.nextFloat() * TWO_PI
                pool.rotPitch[idx] = random.nextFloat() * TWO_PI
                pool.rotRoll[idx] = random.nextFloat() * TWO_PI
                pool.angYaw[idx] = signedSample(tumbleMin, tumbleMax)
                pool.angPitch[idx] = signedSample(tumbleMin, tumbleMax)
                pool.angRoll[idx] = signedSample(tumbleMin, tumbleMax)
            }
            spawned++
        }
        return spawned
    }

    private fun ageAndKill(dt: Float) {
        var i = pool.activeCount - 1
        while (i >= 0) {
            pool.life[i] -= dt
            if (pool.life[i] <= 0f) pool.killAt(i)
            i--
        }
    }

    /** 风场：水平速度按响应率逐帧趋向风采样（竖向不归风管，落速保真）。 */
    private fun applyWind(dt: Float, windT: Double) {
        val wind = windField ?: return
        val f = 1f - exp(-wind.responsiveness * dt)
        val n = pool.activeCount
        for (i in 0 until n) {
            wind.sample(pool.px[i], pool.pz[i], windT, windScratch)
            pool.vx[i] += (windScratch[0] - pool.vx[i]) * f
            pool.vz[i] += (windScratch[1] - pool.vz[i]) * f
        }
    }

    private fun integrate(dt: Float) {
        val n = pool.activeCount
        val cross = renderMode == VfxRenderMode.CROSS
        for (i in 0 until n) {
            pool.px[i] += pool.vx[i] * dt
            pool.py[i] += pool.vy[i] * dt
            pool.pz[i] += pool.vz[i] * dt
            if (pool.angularVel[i] != 0f) pool.angle[i] += pool.angularVel[i] * dt
            if (cross) {
                if (pool.angYaw[i] != 0f) pool.rotYaw[i] += pool.angYaw[i] * dt
                if (pool.angPitch[i] != 0f) pool.rotPitch[i] += pool.angPitch[i] * dt
                if (pool.angRoll[i] != 0f) pool.rotRoll[i] += pool.angRoll[i] * dt
            }
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
        val hasFlip = flipTotal > 1
        val cross = renderMode == VfxRenderMode.CROSS
        val vertsPerParticle = verticesPerParticle
        val stride = floatsPerParticle
        var o = outFloatIndex
        for (i in 0 until n) {
            val s = pool.curSize[i]
            var u0 = 0f
            var v0 = 0f
            var du = 1f
            var dv = 1f
            if (hasFlip) {
                // 序列帧：帧号随生命进度推进（出生=第 0 帧，消亡=末帧），整图
                // 均匀网格左→右、上→下取子矩形
                val t = pool.lifeFraction(i)
                val frame = (t * flipTotal).toInt().coerceIn(0, flipTotal - 1)
                val col = frame % flipColumns
                val row = frame / flipColumns
                du = flipDu
                dv = flipDv
                u0 = col * du
                v0 = row * dv
            }
            if (cross) {
                // CROSS：两片正交世界系 quad（基 (X,Y) 与 (Z,Y)），空间翻滚
                VfxMath.eulerBasis(pool.rotYaw[i], pool.rotPitch[i], pool.rotRoll[i], basisX, basisY, basisZ)
                VfxMath.writeParticle(out, o, pool.px[i], pool.py[i], pool.pz[i], basisX, basisY, s, s, u0, v0, du, dv)
                VfxMath.writeParticle(out, o + 4 * VfxMath.FLOATS_PER_VERTEX, pool.px[i], pool.py[i], pool.pz[i], basisZ, basisY, s, s, u0, v0, du, dv)
            } else if (stretch > 0f) {
                // 速度拉伸（雨丝）：长轴沿速度，短轴垂直于速度×视线
                VfxMath.stretchAxes(
                    pool.vx[i], pool.vy[i], pool.vz[i], forward, right, up,
                    longAxis, widthAxis,
                )
                VfxMath.writeParticle(
                    out, o,
                    pool.px[i], pool.py[i], pool.pz[i],
                    widthAxis, longAxis, s, s * stretch,
                    u0, v0, du, dv,
                )
            } else if (pool.angle[i] != 0f) {
                VfxMath.rotateBasis(right, up, pool.angle[i], rotRight, rotUp)
                VfxMath.writeParticle(out, o, pool.px[i], pool.py[i], pool.pz[i], rotRight, rotUp, s, s, u0, v0, du, dv)
            } else {
                VfxMath.writeParticle(out, o, pool.px[i], pool.py[i], pool.pz[i], right, up, s, s, u0, v0, du, dv)
            }
            val r = pool.cr[i]; val g = pool.cg[i]; val b = pool.cb[i]; val a = pool.ca[i]
            var vo = o + VfxMath.COLOR_OFFSET
            for (v in 0 until vertsPerParticle) {
                out[vo] = r; out[vo + 1] = g; out[vo + 2] = b; out[vo + 3] = a
                vo += VfxMath.FLOATS_PER_VERTEX
            }
            o += stride
        }
        return n
    }

    private fun signedSample(min: Float, max: Float): Float {
        val mag = sampleRange(random, min, max)
        return if (random.nextBoolean()) mag else -mag
    }

    private fun sampleRange(random: Random, min: Float, max: Float): Float =
        if (max > min) min + random.nextFloat() * (max - min) else min

    companion object {
        const val DT_MIN = 0.001f
        const val DT_MAX = 0.05f
        private const val DEG_TO_RAD = (PI.toFloat() / 180f)
        private const val TWO_PI = (2.0 * PI).toFloat()
    }
}
