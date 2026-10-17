package com.neethu.corelib.vfx.sim

import com.neethu.corelib.vfx.WindParams
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 环境风场（画质升级 2026-10-17，docs/vfx-quality-upgrade.md §3.4）：主风向
 * × 慢阵风包络 + 位置相关伪流场扰动，`sample(worldPos, t)` 全局时钟采样。
 *
 * 确定性纯函数（同输入同输出，JVM 单测锁界与确定性）：阵风 = 基频 + 两个
 * 失谐正弦叠加（有机起伏、无查表零分配）；扰动 = 沿三轴异相正弦（非真
 * curl，与 Behavior.Turbulence 同源的伪流场哲学）。输出只含水平分量——
 * 竖向落速归重力管，风不抬雪。
 *
 * 全局时钟由 [com.neethu.corelib.vfx.internal.VfxManager] 持有：同一管理器
 * 下所有带风特效同相采样（「雪与花瓣受同一阵风」），头发弹簧骨骼外力馈送
 * 亦取同一采样点。
 */
class WindField(params: WindParams) {

    /** 主风向（水平投影归一；零向量退 +X）。 */
    private val dirX: Float
    private val dirZ: Float
    private val strength = params.strength.coerceAtLeast(0f)
    private val gust = params.gust.coerceIn(0f, 1f)
    private val omega = 2.0 * PI * params.gustFrequency.coerceAtLeast(0f)
    private val swirl = params.swirl.coerceAtLeast(0f)
    private val k = (2.0 * PI / params.swirlScale.coerceAtLeast(0.05f)).toFloat()
    val responsiveness = params.responsiveness.coerceAtLeast(0f)

    init {
        val dx = params.direction.x
        val dz = params.direction.z
        val len = sqrt(dx * dx + dz * dz)
        if (len < 1e-8f) {
            dirX = 1f; dirZ = 0f
        } else {
            dirX = dx / len; dirZ = dz / len
        }
    }

    /**
     * 采样 (x,z,t) 处的水平风速（米/秒）写进 [out]（out 2 float = x,z）。
     * [t] 为全局风时钟秒（VfxManager 单调推进，所有特效同源）。
     */
    fun sample(x: Float, z: Float, t: Double, out: FloatArray) {
        val g = 1f + gust * (0.55f * sin(omega * t).toFloat() +
            0.30f * sin(omega * 1.7 * t + 1.3).toFloat() +
            0.15f * sin(omega * 0.61 * t + 4.1).toFloat())
        val base = strength * g
        // 局部扰动：两轴异相正弦（伪流场，与 Turbulence 同源）
        val sx = if (swirl > 0f) swirl * sin(z * k + (t * 1.9).toFloat()).toFloat() else 0f
        val sz = if (swirl > 0f) swirl * sin(x * k + (t * 1.3).toFloat() + 2.1f).toFloat() else 0f
        out[0] = dirX * base + sx
        out[1] = dirZ * base + sz
    }
}
