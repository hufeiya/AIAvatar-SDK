package com.neethu.corelib.vfx.internal

import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.VertexBuffer
import com.neethu.corelib.vfx.DecalGeometry
import com.neethu.corelib.vfx.DecalShape
import com.neethu.corelib.vfx.VfxMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

/**
 * 贴花架（P2，docs/vfx-feasibility.md §六 P2）：法阵/护盾类静态网格贴花的
 * 灯架同构件——每张贴花 = 独立实体（OBJECT 域静态网格 + vfx_decal 材质实例）
 * + 每帧实体变换（跟随锚点 + 绕竖轴自转）+ 强度呼吸脉动（MI 热改，灯架同款）；
 * 穹顶另做 CPU Fresnel：逐顶点明度写进交错顶点色后整缓冲重传（穹顶顶点数
 * 百级，~7KB/帧，远低于粒子批）。
 *
 * 几何/明度数学全部在 [DecalGeometry]（JVM 单测）；本类只做 Filament 上传、
 * 变换与销毁。销毁幂等（坑 13 顺序纪律：摘 renderable → 毁 VB/IB → 毁 MI；
 * 材质与贴图归 VfxManager 统一后置）。线程约定：主线程（与 VfxManager 同）。
 */
internal class VfxDecalRig(
    private val engine: Engine,
    private val scene: Scene,
    private val decalMaterial: Material,
    bindings: List<Binding>,
) {

    /** 一张贴花的参数 + 已解析 GPU 贴图（VfxManager 经 ensureTexture 缓存提供）。 */
    class Binding(val params: com.neethu.corelib.vfx.DecalParams, val texture: Texture)

    private class Decal(b: Binding) {
        val p = b.params
        val texture = b.texture
        var entity = 0
        var instance = 0
        var vertexBuffer: VertexBuffer? = null
        var indexBuffer: IndexBuffer? = null
        var materialInstance: MaterialInstance? = null
        /** 穹顶专用：顶点暂存（Fresnel 每帧覆写明度列后整帧重传）。 */
        var workVerts: FloatArray? = null
        var directBuffer: ByteBuffer? = null
    }

    private val decals: List<Decal> = bindings.map { Decal(it) }
    private var destroyed = false

    // 每帧 scratch（零 GC）
    private val mat = FloatArray(16)

    init {
        for (d in decals) {
            try {
                buildDecal(d)
            } catch (e: Exception) {
                // 单张贴花构建失败降级（不拖垮整特效）；已建实体由 destroy 收口
                Log.w(TAG, "decal build failed (${d.p.shape}): ${e.message}")
            }
        }
    }

    private fun buildDecal(d: Decal) {
        val mesh = when (d.p.shape) {
            DecalShape.GROUND_CIRCLE -> DecalGeometry.groundCircle(d.p.radiusMeters, d.p.color)
            DecalShape.DOME -> DecalGeometry.dome(d.p.radiusMeters, DOME_RINGS, DOME_SEGMENTS, DOME_MAX_ELEVATION_DEG, d.p.color)
        }

        val vb = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(mesh.vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, VfxMath.STRIDE_BYTES)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, VfxMath.UV_OFFSET * 4, VfxMath.STRIDE_BYTES)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, VfxMath.COLOR_OFFSET * 4, VfxMath.STRIDE_BYTES)
            .build(engine)
        val fb = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        fb.asFloatBuffer().put(mesh.vertices)
        vb.setBufferAt(engine, 0, fb)

        val ib = IndexBuffer.Builder()
            .indexCount(mesh.indexCount)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        val bb = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(mesh.indices)
        ib.setBuffer(engine, bb)

        val mi = decalMaterial.createInstance()
        mi.setParameter("mainTexture", d.texture, DECAL_SAMPLER)
        mi.setParameter("intensity", d.p.intensity)
        // 地面只画朝上、穹顶只画外表面（BACK 剔除；材质本体 NONE，新坑 2：
        // 剔除档位在 MI 上不在 renderable 上）
        mi.setCullingMode(Material.CullingMode.BACK)

        val entity = EntityManager.get().create()
        RenderableManager.Builder(1)
            // builder 不从顶点数据算 AABB（空盒 PreconditionPanic，新坑 1）——关剔除+大盒
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .boundingBox(Box(0f, 0f, 0f, 1e4f, 1e4f, 1e4f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vb, ib, 0, mesh.indexCount)
            .material(0, mi)
            .build(engine, entity)
        engine.transformManager.create(entity)
        scene.addEntity(entity)
        d.entity = entity
        d.instance = engine.transformManager.getInstance(entity)
        d.vertexBuffer = vb
        d.indexBuffer = ib
        d.materialInstance = mi
        if (d.p.shape == DecalShape.DOME) {
            d.workVerts = mesh.vertices.copyOf()
            d.directBuffer = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        }
    }

    /**
     * 推进一帧：锚点跟随 + 自转 + 脉动；穹顶重算 Fresnel 顶点色并重传。
     * [eye] 为相机位（3 float，VfxManager 每帧已取）。
     */
    fun update(tSec: Float, anchorX: Float, anchorY: Float, anchorZ: Float, eye: FloatArray) {
        if (destroyed) return
        val tm = engine.transformManager
        for (d in decals) {
            if (d.entity == 0) continue
            val p = d.p
            // 实体变换 = 平移(锚点+高度偏移) · 绕竖轴自转（spin 由 age 直接导出，
            // 无累积漂移）
            val spinRad = Math.toRadians((p.spinSpeedDegPerSec * tSec).toDouble())
            val c = cos(spinRad).toFloat(); val s = sin(spinRad).toFloat()
            for (k in 0..15) mat[k] = 0f
            mat[0] = c; mat[2] = -s
            mat[8] = s; mat[10] = c
            mat[5] = 1f; mat[15] = 1f
            mat[12] = anchorX; mat[13] = anchorY + p.heightOffsetMeters; mat[14] = anchorZ
            tm.setTransform(d.instance, mat)

            // 呼吸脉动（强度热改；灯架 flicker 同款路径）
            var intensity = p.intensity
            if (p.pulseStrength > 0f) {
                intensity = p.intensity * (1f - p.pulseStrength * 0.5f * (1f + sin(tSec * p.pulseFrequency * 2f * Math.PI.toFloat())))
            }
            d.materialInstance?.setParameter("intensity", intensity)

            // 穹顶：CPU Fresnel 明度 → 顶点色整帧重传
            val work = d.workVerts
            val direct = d.directBuffer
            if (work != null && direct != null) {
                val cx = anchorX; val cy = anchorY + p.heightOffsetMeters; val cz = anchorZ
                DecalGeometry.fresnelShades(
                    work, cx, cy, cz, eye[0], eye[1], eye[2],
                    FRESNEL_BASE, p.rimStrength, FRESNEL_POWER,
                )
                direct.position(0)
                direct.asFloatBuffer().put(work)
                direct.position(0)
                direct.limit(work.size * 4)
                d.vertexBuffer?.setBufferAt(engine, 0, direct)
            }
        }
    }

    /** 幂等销毁：摘实体/缓冲/材质实例（材质与贴图归 VfxManager 统一后置）。 */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        for (d in decals) {
            if (d.entity != 0) {
                scene.removeEntity(d.entity)
                engine.destroyEntity(d.entity)
                EntityManager.get().destroy(d.entity)
                d.entity = 0
            }
            d.vertexBuffer?.let { engine.destroyVertexBuffer(it) }
            d.indexBuffer?.let { engine.destroyIndexBuffer(it) }
            d.materialInstance?.let { engine.destroyMaterialInstance(it) }
            d.vertexBuffer = null
            d.indexBuffer = null
            d.materialInstance = null
        }
    }

    companion object {
        private const val TAG = "VfxDecalRig"

        /** 穹顶网格精度（192 顶点级：Fresnel 插值够滑，重传 ~7KB/帧）。 */
        private const val DOME_RINGS = 6
        private const val DOME_SEGMENTS = 32
        private const val DOME_MAX_ELEVATION_DEG = 78f

        /** Fresnel 底亮度（无边缘处的可见度）与幂（轮廓缘锐度）。 */
        private const val FRESNEL_BASE = 0.35f
        private const val FRESNEL_POWER = 2.2f

        // REPEAT：穹顶 u=方位角 0..1 环绕，接缝要求贴图横向可平铺（shield 纹样
        // 按此设计）；地面贴花 UV 在 0..1 内 REPEAT 无副作用
        private val DECAL_SAMPLER = TextureSampler(
            TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.REPEAT,
        )
    }
}
