package com.neethu.corelib.vfx

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 特效纯数学（docs/vfx-feasibility.md §4.7）：相机基向量、Billboard 四角、
 * 速度拉伸轴、曲线采样——全静态纯函数，零引擎类型，JVM 单测锁死。
 */
object VfxMath {

    /** 交错顶点布局每顶点 float 数：POSITION(3) + UV0(2) + COLOR(4)。 */
    const val FLOATS_PER_VERTEX = 9

    /** 每 4 顶点 2 三角。 */
    const val VERTICES_PER_PARTICLE = 4
    const val FLOATS_PER_PARTICLE = FLOATS_PER_VERTEX * VERTICES_PER_PARTICLE

    /** 顶点内 UV 的 float 偏移（POSITION 3 之后）。 */
    const val UV_OFFSET = 3
    /** 顶点内颜色的 float 偏移（POSITION 3 + UV 2 之后）。 */
    const val COLOR_OFFSET = 5
    /** 交错布局字节步长（9 float × 4 字节）——VertexBuffer attribute stride。 */
    const val STRIDE_BYTES = FLOATS_PER_VERTEX * 4

    /**
     * 从 Manipulator `getLookAt(eye, target, up)` 三元组导出 Billboard 基向量
     * （§4.7）：forward = normalize(target−eye)，right = normalize(forward×up)，
     * camUp = right×forward。写入 [outRight]/[outUp]（各 3 float），零分配。
     */
    fun basisFromLookAt(
        eye: FloatArray,
        target: FloatArray,
        up: FloatArray,
        outRight: FloatArray,
        outUp: FloatArray,
    ) {
        var fx = target[0] - eye[0]
        var fy = target[1] - eye[1]
        var fz = target[2] - eye[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl < 1e-8f) {
            // eye==target 退化：任取世界 -Z 当 forward，避免 NaN 传播
            fx = 0f; fy = 0f; fz = -1f
        } else {
            fx /= fl; fy /= fl; fz /= fl
        }
        // right = normalize(forward × up)——相机俯仰时叉积长度 = sin(俯角) < 1
        var rx = fy * up[2] - fz * up[1]
        var ry = fz * up[0] - fx * up[2]
        var rz = fx * up[1] - fy * up[0]
        val rl = sqrt(rx * rx + ry * ry + rz * rz)
        if (rl < 1e-8f) {
            // forward 与 up 共线（俯仰到天顶）：借世界 X 兜底
            rx = 1f; ry = 0f; rz = 0f
        } else {
            rx /= rl; ry /= rl; rz /= rl
        }
        // camUp = right × forward
        outRight[0] = rx; outRight[1] = ry; outRight[2] = rz
        outUp[0] = ry * fz - rz * fy
        outUp[1] = rz * fx - rx * fz
        outUp[2] = rx * fy - ry * fx
    }

    /**
     * 把 Billboard 平面内的基向量绕视线轴自转 [angleRad]（粒子自转）：
     * r' = r·cosθ + u·sinθ，u' = u·cosθ − r·sinθ。写入 [outRight]/[outUp]。
     */
    fun rotateBasis(
        right: FloatArray,
        up: FloatArray,
        angleRad: Float,
        outRight: FloatArray,
        outUp: FloatArray,
    ) {
        val c = cos(angleRad)
        val s = sin(angleRad)
        for (k in 0..2) {
            outRight[k] = right[k] * c + up[k] * s
            outUp[k] = up[k] * c - right[k] * s
        }
    }

    /**
     * 欧拉角 → 世界系正交基（CROSS 模式的三维翻滚，画质升级 2026-10-17）：
     * R = Ry(yaw)·Rx(pitch)·Rz(roll)，写出三个列向量 R·X/R·Y/R·Z（各 3
     * float，outX/outY/outZ）。两片正交 quad 分别取 (outX,outY) 与
     * (outZ,outY) 平面。纯函数零分配，JVM 单测锁正交性/单位长度/角序。
     */
    fun eulerBasis(
        yawRad: Float,
        pitchRad: Float,
        rollRad: Float,
        outX: FloatArray,
        outY: FloatArray,
        outZ: FloatArray,
    ) {
        val cy = cos(yawRad); val sy = sin(yawRad)
        val cx = cos(pitchRad); val sx = sin(pitchRad)
        val cz = cos(rollRad); val sz = sin(rollRad)
        // R = Ry·Rx·Rz 展开列：
        outX[0] = cy * cz + sy * sx * sz
        outX[1] = cx * sz
        outX[2] = -sy * cz + cy * sx * sz
        outY[0] = -cy * sz + sy * sx * cz
        outY[1] = cx * cz
        outY[2] = sy * sz + cy * sx * cz
        outZ[0] = sy * cx
        outZ[1] = -sx
        outZ[2] = cy * cx
    }

    /**
     * 写一个粒子的 4 个 Billboard 角点 + UV 到 [out]（交错布局）。
     * [r]/[u] 为（可自转后的）Billboard 平面基向量，[halfW]/[halfH] 为半宽/半高
     * （米）；速度拉伸时调用方直接传拉伸后的轴。UV 默认整图四角；flipbook 传
     * 子矩形（[u0]/[v0] 左下原点 + [du]/[dv] 步长）。
     *
     * 角序 V0 左下 → V1 右下 → V2 右上 → V3 左上；UV = (u0,v0)(u0+du,v0)
     * (u0+du,v0+dv)(u0,v0+dv)——V 方向若真机目验上下颠倒，翻这里的 V 一行修
     * （§八首验项 3，P1 非对称贴图实测）。
     */
    fun writeParticle(
        out: FloatArray,
        outFloatIndex: Int,
        px: Float, py: Float, pz: Float,
        r: FloatArray,
        u: FloatArray,
        halfW: Float,
        halfH: Float,
        u0: Float = 0f,
        v0: Float = 0f,
        du: Float = 1f,
        dv: Float = 1f,
    ) {
        val u1 = u0 + du
        val v1 = v0 + dv
        var o = outFloatIndex
        // V0 = P − r·hw − u·hh（左下, uv u0,v0）
        out[o] = px - r[0] * halfW - u[0] * halfH
        out[o + 1] = py - r[1] * halfW - u[1] * halfH
        out[o + 2] = pz - r[2] * halfW - u[2] * halfH
        out[o + UV_OFFSET] = u0; out[o + UV_OFFSET + 1] = v0
        o += FLOATS_PER_VERTEX
        // V1 = P + r·hw − u·hh（右下, uv u1,v0）
        out[o] = px + r[0] * halfW - u[0] * halfH
        out[o + 1] = py + r[1] * halfW - u[1] * halfH
        out[o + 2] = pz + r[2] * halfW - u[2] * halfH
        out[o + UV_OFFSET] = u1; out[o + UV_OFFSET + 1] = v0
        o += FLOATS_PER_VERTEX
        // V2 = P + r·hw + u·hh（右上, uv u1,v1）
        out[o] = px + r[0] * halfW + u[0] * halfH
        out[o + 1] = py + r[1] * halfW + u[1] * halfH
        out[o + 2] = pz + r[2] * halfW + u[2] * halfH
        out[o + UV_OFFSET] = u1; out[o + UV_OFFSET + 1] = v1
        o += FLOATS_PER_VERTEX
        // V3 = P − r·hw + u·hh（左上, uv u0,v1）
        out[o] = px - r[0] * halfW + u[0] * halfH
        out[o + 1] = py - r[1] * halfW + u[1] * halfH
        out[o + 2] = pz - r[2] * halfW + u[2] * halfH
        out[o + UV_OFFSET] = u0; out[o + UV_OFFSET + 1] = v1
        // 颜色由 [ParticleSystem] 顶点写出时统一填（逐粒子 RGBA）
    }

    /**
     * 速度拉伸轴（雨丝，§4.7）：长轴沿速度方向，短轴垂直于速度与视线。
     * 写入 [outLong]/[outWidth]（各 3 float）；速度≈0 退回 Billboard 基向量。
     */
    fun stretchAxes(
        vx: Float, vy: Float, vz: Float,
        forward: FloatArray,
        right: FloatArray,
        up: FloatArray,
        outLong: FloatArray,
        outWidth: FloatArray,
    ) {
        val vl = sqrt(vx * vx + vy * vy + vz * vz)
        if (vl < 1e-6f) {
            outLong[0] = up[0]; outLong[1] = up[1]; outLong[2] = up[2]
            outWidth[0] = right[0]; outWidth[1] = right[1]; outWidth[2] = right[2]
            return
        }
        outLong[0] = vx / vl; outLong[1] = vy / vl; outLong[2] = vz / vl
        // width = long × forward
        val wx = outLong[1] * forward[2] - outLong[2] * forward[1]
        val wy = outLong[2] * forward[0] - outLong[0] * forward[2]
        val wz = outLong[0] * forward[1] - outLong[1] * forward[0]
        val wl = sqrt(wx * wx + wy * wy + wz * wz)
        if (wl < 1e-6f) {
            // 速度与视线共线：退回 right
            outWidth[0] = right[0]; outWidth[1] = right[1]; outWidth[2] = right[2]
        } else {
            outWidth[0] = wx / wl; outWidth[1] = wy / wl; outWidth[2] = wz / wl
        }
    }

    /**
     * 折线曲线采样（ScalarKey 升序）：t 钳 [first.t, last.t]，段内线性插值。
     * [flat] = [t0, v0, t1, v1, …]（调用方把 keys 摊平，避免热路径迭代器分配）。
     */
    fun sampleCurve(flat: FloatArray, t: Float): Float {
        val n = flat.size / 2
        if (n == 0) return 1f
        if (t <= flat[0]) return flat[1]
        if (t >= flat[(n - 1) * 2]) return flat[n * 2 - 1]
        var i = 0
        while (i < n - 1) {
            val t0 = flat[i * 2]
            val t1 = flat[i * 2 + 2]
            if (t <= t1) {
                val span = t1 - t0
                val f = if (span > 1e-8f) (t - t0) / span else 0f
                val v0 = flat[i * 2 + 1]
                return v0 + (flat[i * 2 + 3] - v0) * f
            }
            i++
        }
        return flat[n * 2 - 1]
    }

    /**
     * 颜色曲线采样（ColorKey 升序）写入 [out]（RGBA 4 float），插值规则同
     * [sampleCurve]；[flat] = [t0, r0, g0, b0, a0, t1, …]。
     */
    fun sampleColorCurve(flat: FloatArray, t: Float, out: FloatArray) {
        val n = flat.size / 5
        if (n == 0) {
            out[0] = 1f; out[1] = 1f; out[2] = 1f; out[3] = 1f
            return
        }
        if (t <= flat[0]) {
            copyColor(flat, 1, out)
            return
        }
        if (t >= flat[(n - 1) * 5]) {
            copyColor(flat, (n - 1) * 5 + 1, out)
            return
        }
        var i = 0
        while (i < n - 1) {
            val t0 = flat[i * 5]
            val t1 = flat[i * 5 + 5]
            if (t <= t1) {
                val span = t1 - t0
                val f = if (span > 1e-8f) (t - t0) / span else 0f
                for (k in 0..3) {
                    val v0 = flat[i * 5 + 1 + k]
                    out[k] = v0 + (flat[i * 5 + 6 + k] - v0) * f
                }
                return
            }
            i++
        }
        copyColor(flat, (n - 1) * 5 + 1, out)
    }

    /** 把 BehaviorParams 键表摊平成采样用的 FloatArray（配置期一次，非热路径）。 */
    fun flattenScalarKeys(keys: List<ScalarKey>): FloatArray {
        val flat = FloatArray(keys.size * 2)
        keys.forEachIndexed { i, k ->
            flat[i * 2] = k.t
            flat[i * 2 + 1] = k.value
        }
        return flat
    }

    fun flattenColorKeys(keys: List<ColorKey>): FloatArray {
        val flat = FloatArray(keys.size * 5)
        keys.forEachIndexed { i, k ->
            flat[i * 5] = k.t
            flat[i * 5 + 1] = k.color.r
            flat[i * 5 + 2] = k.color.g
            flat[i * 5 + 3] = k.color.b
            flat[i * 5 + 4] = k.color.a
        }
        return flat
    }

    private fun copyColor(flat: FloatArray, base: Int, out: FloatArray) {
        out[0] = flat[base]
        out[1] = flat[base + 1]
        out[2] = flat[base + 2]
        out[3] = flat[base + 3]
    }
}
