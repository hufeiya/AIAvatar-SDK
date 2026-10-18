package com.neethu.corelib.vfx

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * decal 贴花纯数学（P2，docs/vfx-feasibility.md §六 P2；画质升级 §7.4 把
 * 「同一 decal 通道」拆成地面圆盘/穹顶两种几何后的网格构建与 Fresnel 求值）。
 * 零引擎类型——网格构建配置期一次、Fresnel 逐帧求值，全部 JVM 单测锁死；
 * [VfxDecalRig] 只做 Filament 上传与实体变换。
 */
object DecalGeometry {

    /** 顶点布局与粒子批一致（VfxMath.FLOATS_PER_VERTEX=9：pos3+uv2+color4）。 */
    const val FLOATS_PER_VERTEX = VfxMath.FLOATS_PER_VERTEX

    /** 构建出的静态网格：交错顶点 + USHORT 索引。 */
    class Mesh(val vertices: FloatArray, val indices: ShortArray) {
        val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX
        val indexCount: Int get() = indices.size
    }

    /**
     * 地面圆盘：锚点局部 XZ 平面（y=0）2 三角 quad，半径 [radius]，UV 整图
     * 0..1。顶点色 = [color]（无 Fresnel）。索引顺时针从上看（与舞台灯架
     * 地面光斑同款绕序——+Y 俯视为正面，BACK 剔除只画朝上面）。
     */
    fun groundCircle(radius: Float, color: VfxColor): Mesh {
        val r = radius.coerceAtLeast(0.01f)
        val verts = floatArrayOf(
            -r, 0f, -r, 0f, 0f, color.r, color.g, color.b, color.a,
            r, 0f, -r, 1f, 0f, color.r, color.g, color.b, color.a,
            r, 0f, r, 1f, 1f, color.r, color.g, color.b, color.a,
            -r, 0f, r, 0f, 1f, color.r, color.g, color.b, color.a,
        )
        return Mesh(verts, shortArrayOf(0, 2, 1, 1, 2, 3))
    }

    /**
     * 穹顶：锚点上方部分球壳（仰角 0°→[maxElevationDeg]°，[rings]×[segments]），
     * UV 等距柱状（u=方位/2π 周期贴合、v=仰角进度）。顶点色暂填 [color]
     * （Fresnel 明度由 [fresnelShades] 每帧覆写）。
     */
    fun dome(
        radius: Float,
        rings: Int,
        segments: Int,
        maxElevationDeg: Float,
        color: VfxColor,
    ): Mesh {
        val r = radius.coerceAtLeast(0.01f)
        val ringCount = rings.coerceIn(2, 32)
        val segCount = segments.coerceIn(3, 96)
        val maxEl = Math.toRadians(maxElevationDeg.coerceIn(10f, 89f).toDouble())
        val verts = FloatArray((ringCount + 1) * (segCount + 1) * FLOATS_PER_VERTEX)
        var f = 0
        for (i in 0..ringCount) {
            val el = maxEl * i / ringCount
            val ce = cos(el).toFloat()
            val y = (r * sin(el)).toFloat()
            val v = i.toFloat() / ringCount
            for (s in 0..segCount) {
                val az = 2.0 * Math.PI * s / segCount
                verts[f++] = r * ce * cos(az).toFloat()
                verts[f++] = y
                verts[f++] = r * ce * sin(az).toFloat()
                verts[f++] = s.toFloat() / segCount
                verts[f++] = v
                verts[f++] = color.r; verts[f++] = color.g; verts[f++] = color.b; verts[f++] = color.a
            }
        }
        val indices = ShortArray(ringCount * segCount * 6)
        var ii = 0
        for (i in 0 until ringCount) {
            for (s in 0 until segCount) {
                val a = (i * (segCount + 1) + s).toShort()
                val b = (a + 1).toShort()
                val c = (a + segCount + 1).toShort()
                val d = (c + 1).toShort()
                // 球壳外表面为正面（从外部看逆时针），BACK 剔除保留外表面
                indices[ii++] = a; indices[ii++] = c; indices[ii++] = b
                indices[ii++] = b; indices[ii++] = c; indices[ii++] = d
            }
        }
        return Mesh(verts, indices)
    }

    /**
     * 穹顶逐顶点 Fresnel 明度（每帧 CPU 求值，画质升级 §7.4 语境：真 UNLIT
     * fragment 里取世界坐标在本仓库未验证，CPU 顶点色插值是零 shader 风险的
     * 等效路）：rim = pow(1−|N·V|, [rimPower])（轮廓缘 |N·V|→0 处 rim→1），
     * 明度 = [base] + rim·[rimStrength] 钳 0..1。
     *
     * [vertices] 为 [dome] 产物（局部坐标，球心在原点），[center*] 为穹顶世界
     * 位置，[eye*] 为相机位。灰度写进每顶点 RGB（alpha 不动），返回活跃顶点数。
     */
    fun fresnelShades(
        vertices: FloatArray,
        centerX: Float, centerY: Float, centerZ: Float,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        base: Float,
        rimStrength: Float,
        rimPower: Float,
    ): Int {
        val vertexCount = vertices.size / FLOATS_PER_VERTEX
        for (v in 0 until vertexCount) {
            val o = v * FLOATS_PER_VERTEX
            val lx = vertices[o]; val ly = vertices[o + 1]; val lz = vertices[o + 2]
            val wx = centerX + lx; val wy = centerY + ly; val wz = centerZ + lz
            // N = 局部位置归一（球法线）；V = eye−world 归一
            val nl = sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(1e-6f)
            var vx = eyeX - wx; var vy = eyeY - wy; var vz = eyeZ - wz
            val vl = sqrt(vx * vx + vy * vy + vz * vz).coerceAtLeast(1e-6f)
            vx /= vl; vy /= vl; vz /= vl
            val ndotv = ((lx / nl) * vx + (ly / nl) * vy + (lz / nl) * vz).coerceIn(-1f, 1f)
            val rim = (1f - kotlin.math.abs(ndotv)).coerceIn(0f, 1f).pow(rimPower)
            val shade = (base + rim * rimStrength).coerceIn(0f, 1f)
            vertices[o + 5] = shade; vertices[o + 6] = shade; vertices[o + 7] = shade
        }
        return vertexCount
    }
}
