package com.neethu.corelib.vfx.sim

/**
 * 粒子池（SoA 扁平数组，docs/vfx-feasibility.md §4.2）：预分配零 GC，
 * swap-with-last 回收。纯 Kotlin 零引擎依赖，JVM 单测锁死。
 *
 * 布局：pos/vel 各 3×capacity，其余逐粒子一槽；[activeCount] 之前的槽位活跃。
 */
class ParticlePool(val capacity: Int) {

    val px = FloatArray(capacity)
    val py = FloatArray(capacity)
    val pz = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val vz = FloatArray(capacity)
    val life = FloatArray(capacity)
    val maxLife = FloatArray(capacity)
    /** 逐粒子基础尺寸（出生时采样，米）。 */
    val baseSize = FloatArray(capacity)
    /** 当前尺寸（baseSize × SizeOverLife 倍率；无该行为时恒等于 baseSize）。 */
    val curSize = FloatArray(capacity)
    /** 自转角（弧度）与角速度（弧度/秒）。 */
    val angle = FloatArray(capacity)
    val angularVel = FloatArray(capacity)
    val cr = FloatArray(capacity)
    val cg = FloatArray(capacity)
    val cb = FloatArray(capacity)
    val ca = FloatArray(capacity)
    /** 起始色（spawn 采样定格；ColorOverLife 输出 = 曲线 × 起始色——palette
     *  配色不被白色曲线覆写，2026-10-17 真机 confetti 目验教训）。 */
    val baseR = FloatArray(capacity)
    val baseG = FloatArray(capacity)
    val baseB = FloatArray(capacity)
    val baseA = FloatArray(capacity)

    var activeCount = 0
        private set

    /** 满员判定。 */
    val isFull: Boolean get() = activeCount >= capacity

    /** 第 [i] 个粒子的生命进度 t ∈ [0,1]（0=出生，1=消亡）。 */
    fun lifeFraction(i: Int): Float {
        val m = maxLife[i]
        return if (m > 1e-8f) ((m - life[i]) / m).coerceIn(0f, 1f) else 1f
    }

    /**
     * 追加一个粒子（写入 [activeCount] 槽位后计数 +1）。
     * @return 槽位号；满员返回 -1（溢出策略由调用方决定）。
     */
    fun append(
        x: Float, y: Float, z: Float,
        velX: Float, velY: Float, velZ: Float,
        lifeSec: Float,
        size: Float,
        angleRad: Float,
        angVelRad: Float,
        r: Float, g: Float, b: Float, a: Float,
    ): Int {
        if (isFull) return -1
        val i = activeCount++
        px[i] = x; py[i] = y; pz[i] = z
        vx[i] = velX; vy[i] = velY; vz[i] = velZ
        life[i] = lifeSec; maxLife[i] = lifeSec
        baseSize[i] = size; curSize[i] = size
        angle[i] = angleRad; angularVel[i] = angVelRad
        cr[i] = r; cg[i] = g; cb[i] = b; ca[i] = a
        baseR[i] = r; baseG[i] = g; baseB[i] = b; baseA[i] = a
        return i
    }

    /**
     * 覆盖第 [i] 个粒子（RECYCLE_OLDEST：满员时由调用方先找槽位再覆盖）。
     * 语义与 [append] 逐字段一致，只是写入既有槽位、计数不变。
     */
    fun overwrite(
        i: Int,
        x: Float, y: Float, z: Float,
        velX: Float, velY: Float, velZ: Float,
        lifeSec: Float,
        size: Float,
        angleRad: Float,
        angVelRad: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        px[i] = x; py[i] = y; pz[i] = z
        vx[i] = velX; vy[i] = velY; vz[i] = velZ
        life[i] = lifeSec; maxLife[i] = lifeSec
        baseSize[i] = size; curSize[i] = size
        angle[i] = angleRad; angularVel[i] = angVelRad
        cr[i] = r; cg[i] = g; cb[i] = b; ca[i] = a
        baseR[i] = r; baseG[i] = g; baseB[i] = b; baseA[i] = a
    }

    /**
     * 剩余寿命最短的活跃粒子槽位（RECYCLE_OLDEST 的「最老」判据：等寿命
     * 稳态下剩余最少 = 出生最早）。空池返回 -1。线性扫 ≤capacity，仅满员
     * 发射时触发，预算可忽略。
     */
    fun findShortestLife(): Int {
        if (activeCount == 0) return -1
        var best = 0
        for (i in 1 until activeCount) {
            if (life[i] < life[best]) best = i
        }
        return best
    }

    /** swap-with-last 回收第 [i] 个粒子。 */
    fun killAt(i: Int) {
        val last = activeCount - 1
        if (i < 0 || i > last) return
        if (i != last) {
            px[i] = px[last]; py[i] = py[last]; pz[i] = pz[last]
            vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last]
            life[i] = life[last]; maxLife[i] = maxLife[last]
            baseSize[i] = baseSize[last]; curSize[i] = curSize[last]
            angle[i] = angle[last]; angularVel[i] = angularVel[last]
            cr[i] = cr[last]; cg[i] = cg[last]; cb[i] = cb[last]; ca[i] = ca[last]
            baseR[i] = baseR[last]; baseG[i] = baseG[last]
            baseB[i] = baseB[last]; baseA[i] = baseA[last]
        }
        activeCount--
    }

    /** 当帧清空（stopVfx 的「粒子当帧清空」验收口径）。 */
    fun clear() {
        activeCount = 0
    }
}
