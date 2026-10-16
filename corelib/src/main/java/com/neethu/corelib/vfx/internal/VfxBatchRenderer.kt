package com.neethu.corelib.vfx.internal

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
import com.neethu.corelib.vfx.VfxMath
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 粒子批渲染桥（docs/vfx-feasibility.md §4.5，全仓库第一块程序化网格——
 * P0 真机首验对象）：静态 IndexBuffer + 动态 VertexBuffer + 单 primitive
 * Renderable。同混合档的全部特效共享一个批，任意多特效合计 1 个 draw call。
 *
 * 顶点交错布局（9 float = 36 字节）：POSITION FLOAT3 @0 + UV0 FLOAT2 @12 +
 * COLOR FLOAT4 @20。索引静态 (0,1,2, 2,3,0) 循环一次建终身不改；顶点每帧
 * 部分上传活跃前缀（五参 setBufferAt）+ 动态索引数（setGeometryAt）。
 *
 * 零 GC 纪律（弹簧骨骼 FloatArray 前科）：[vertices]/[directBuffer]/[floatView]
 * 全部预分配，flush 路径零 new。
 *
 * 销毁顺序（坑 13 SIGABRT 前科，幂等）：清绘制引用 → 摘 renderable →
 * 毁 VB/IB → 毁 MaterialInstance（材质/纹理归 VfxManager 统一后置）。
 */
internal class VfxBatchRenderer(
    private val engine: Engine,
    private val scene: Scene,
    material: Material,
    texture: Texture,
    /** 批容量（粒子数上限；全批次共享）。 */
    val capacityParticles: Int,
) {
    /** 模拟层直写的顶点暂存（写满批容量）。 */
    val vertices = FloatArray(capacityParticles * VfxMath.FLOATS_PER_PARTICLE)

    private val vertexBuffer: VertexBuffer
    private val indexBuffer: IndexBuffer
    private val materialInstance: MaterialInstance
    private val entity: Int
    private val renderableInstance: Int

    private val directBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    /** directBuffer 的 float 视图（init 建一次；flush 路径零分配的关键）。 */
    private val floatView: java.nio.FloatBuffer = directBuffer.asFloatBuffer()

    /** 上帧索引数（0→0 跳过 JNI 空转）。 */
    private var lastCount = -1
    private var destroyed = false

    init {
        val vertexCount = capacityParticles * VfxMath.VERTICES_PER_PARTICLE
        vertexBuffer = VertexBuffer.Builder()
            .bufferCount(1)
            .vertexCount(vertexCount)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, VfxMath.STRIDE_BYTES)
            .attribute(VertexBuffer.VertexAttribute.UV0, 0, VertexBuffer.AttributeType.FLOAT2, VfxMath.UV_OFFSET * 4, VfxMath.STRIDE_BYTES)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, VfxMath.COLOR_OFFSET * 4, VfxMath.STRIDE_BYTES)
            .build(engine)

        indexBuffer = IndexBuffer.Builder()
            .indexCount(capacityParticles * INDICES_PER_PARTICLE)
            .bufferType(IndexBuffer.Builder.IndexType.USHORT)
            .build(engine)
        indexBuffer.setBuffer(engine, buildStaticIndices(capacityParticles))

        materialInstance = material.createInstance()
        materialInstance.setParameter("mainTexture", texture, TEXTURE_SAMPLER)
        materialInstance.setParameter("intensity", 1.0f)

        entity = EntityManager.get().create()
        val builder = RenderableManager.Builder(1)
            // 世界包围盒逐帧变化，剔除盒难便宜地保守：直接关剔除（配饰/角色同款）
            .culling(false)
            .castShadows(false)   // 不进阴影管线（PCSS Adreno 悬崖零牵连）
            .receiveShadows(false)
            .boundingBox(Box(0f, 0f, 0f, 1e4f, 1e4f, 1e4f))
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, vertexBuffer, indexBuffer, 0, 0)
            .material(0, materialInstance)
        builder.build(engine, entity)
        // renderable 显式单位变换（不依赖 filament 自动创建行为）
        engine.transformManager.create(entity)
        scene.addEntity(entity)
        renderableInstance = requireNotNull(engine.renderableManager.getInstance(entity))

        // 开局即零绘制（builder 里的全量索引指向未初始化顶点数据）
        flush(0)
    }

    /**
     * 本帧 flush：[particleCount] 个粒子（[vertices] 前缀已由模拟层写好）。
     * 部分上传 + 动态索引数；粒子 0 且上帧也是 0 时跳过全部 JNI 调用。
     *
     * P0 首验注：NIO position 指针与五参 byteOffset 的精确语义（JNI 读
     * position+offset 还是裸 offset）以真机首个版本为准记录结论——本实现
     * 在调用前 position(0)/limit(byteCount)，两个候选读法此时等价。
     */
    fun flush(particleCount: Int) {
        if (destroyed) return
        val clamped = particleCount.coerceIn(0, capacityParticles)
        if (clamped == 0 && lastCount == 0) return
        val rm = engine.renderableManager
        rm.setGeometryAt(
            renderableInstance, 0, RenderableManager.PrimitiveType.TRIANGLES,
            vertexBuffer, indexBuffer, 0, clamped * INDICES_PER_PARTICLE,
        )
        if (clamped > 0) {
            // 五参部分上传活跃前缀（§4.5；真机 A/B 实证与三参全量等价，
            // 2026-10-17）。JNI 从 buffer position 起读 sizeBytes 字节——
            // position(0)+limit(byteCount) 下两种读法一致。
            val floatCount = clamped * VfxMath.FLOATS_PER_PARTICLE
            val byteCount = floatCount * 4
            floatView.clear()
            floatView.put(vertices, 0, floatCount)
            directBuffer.position(0)
            directBuffer.limit(byteCount)
            vertexBuffer.setBufferAt(engine, 0, directBuffer, 0, byteCount)
        }
        lastCount = clamped
    }

    /**
     * 幂等销毁（可重入）：先摘 renderable 对材质实例的持有，再毁 GPU 资源。
     * 材质与纹理由 [VfxManager.destroy] 统一后置（实例先于纹理，坑 13 尾注）。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        val rm = engine.renderableManager
        // ① 清绘制引用 + ② 摘 renderable（对 MaterialInstance 的持有随实体销毁）
        if (lastCount != 0) {
            rm.setGeometryAt(
                renderableInstance, 0, RenderableManager.PrimitiveType.TRIANGLES,
                vertexBuffer, indexBuffer, 0, 0,
            )
        }
        scene.removeEntity(entity)
        engine.destroyEntity(entity)
        EntityManager.get().destroy(entity)
        // ③ 缓冲
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        // ④ 材质实例（材质本体后置）
        engine.destroyMaterialInstance(materialInstance)
    }

    private fun buildStaticIndices(particles: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(particles * INDICES_PER_PARTICLE * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
        val view = buffer.asShortBuffer()
        for (i in 0 until particles) {
            val b = (i * 4).toShort()
            view.put(b); view.put((b + 1).toShort()); view.put((b + 2).toShort())
            view.put((b + 2).toShort()); view.put((b + 3).toShort()); view.put(b)
        }
        buffer.rewind()
        return buffer
    }

    companion object {
        private const val INDICES_PER_PARTICLE = 6

        /** 线性过滤 + repeat 寻址（flipbook 子矩形 UV 的边缘安全兜底）。 */
        private val TEXTURE_SAMPLER = TextureSampler(
            TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.REPEAT,
        )
    }
}
