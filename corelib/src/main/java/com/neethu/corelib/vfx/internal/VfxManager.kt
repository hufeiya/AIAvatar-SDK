package com.neethu.corelib.vfx.internal

import android.util.Log
import com.google.android.filament.Texture
import com.google.android.filament.filamat.MaterialBuilder
import com.neethu.corelib.vfx.VfxAnchor
import com.neethu.corelib.vfx.VfxBlending
import com.neethu.corelib.vfx.VfxHandle
import com.neethu.corelib.vfx.VfxInfo
import com.neethu.corelib.vfx.VfxMath
import com.neethu.corelib.vfx.VfxSpec
import com.neethu.corelib.vfx.VfxTexture
import com.neethu.corelib.vfx.sim.ParticleSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * 多特效编排器（docs/vfx-feasibility.md §4.2）：play/stop 生命周期、每帧泵
 * （各 ParticleSystem update → 各批 flush）、幂等 destroy。
 *
 * 每帧时序（[update]，挂在 updateCameraShot 之后、render 之前——同帧相机
 * 基向量无一帧滞后，骨骼 commit 已跑过）：dt 钳制 → 取相机三元组导基向量 →
 * Bone 锚点逐帧解析（发射器跟随、已存活粒子留世界系；解析失败自停，§4.8）→
 * 逐批按创建序把各特效顶点顺序写入批缓冲 → flush（动态索引数）。
 *
 * 批按（混合档 × 贴图）键控（P1：材质实例一张 mainTexture，不同贴图的特效
 * 不能共批；全屏 draw call 数 = 在用贴图组合数，个位数）。贴图缓存按
 * [VfxTexture] 惰性加载（同贴图多预设共享一张 GPU 纹理）。
 *
 * 零 GC 纪律：基向量/锚点/scratch 全部预分配；update 路径仅剩 finished 时
 * 一次 removeAt，无其他分配（发布 StateFlow 的 map 只在结构变化时发生）。
 *
 * 线程约定：全部方法主线程调用（choreoCallback/broadcast 同线程）。
 */
internal class VfxManager(private val host: VfxHost) {

    private class Active(
        val handle: VfxHandle,
        val spec: VfxSpec,
        val system: ParticleSystem,
        val batch: VfxBatchRenderer,
        val anchor: VfxAnchor,
        /** 世界坐标锚点缓存（World = 一次写入；Bone = 每帧覆写）。 */
        val anchorPos: FloatArray,
        val anchorText: String,
        var age: Float,
    )

    private val active = ArrayList<Active>()
    private val idCounter = AtomicInteger(0)

    private var materialFactory: VfxMaterialFactory? = null
    private var batches = HashMap<BatchKey, VfxBatchRenderer>()
    private var textures = HashMap<VfxTexture, Texture>()
    private var destroyed = false

    /** 批键：混合档 × 实际贴图实例（材质实例一张 mainTexture，异贴图不能共批）。 */
    private data class BatchKey(val blending: VfxBlending, val texture: Texture)

    /** 上帧时间戳（dt 来源；0 = 首帧）。 */
    private var lastFrameNanos = 0L

    // 每帧 scratch（预分配零 GC）
    private val eye = FloatArray(3)
    private val target = FloatArray(3)
    private val camUpIn = FloatArray(3)
    private val forward = FloatArray(3)
    private val right = FloatArray(3)
    private val camUp = FloatArray(3)
    private val bonePos = FloatArray(3)

    /** 状态列表变化（play/stop/播完自停）→ 渲染器中继到 AvatarController。 */
    var onChange: (() -> Unit)? = null

    private val _effects = MutableStateFlow<List<VfxInfo>>(emptyList())
    val effects: StateFlow<List<VfxInfo>> = _effects.asStateFlow()

    /**
     * 播放一个特效。
     * @return 句柄；null = 渲染未就绪（材质编译失败）或对应批容量已满。
     */
    fun play(spec: VfxSpec, anchor: VfxAnchor): VfxHandle? {
        if (destroyed) return null
        if (!ensureInit()) return null
        val texture = ensureTexture(spec.texture)
        val key = BatchKey(spec.blending, texture)
        val batch = batches.getOrPut(key) {
            val material = when (spec.blending) {
                VfxBlending.ADDITIVE -> materialFactory!!.additive
                VfxBlending.TRANSPARENT -> materialFactory!!.transparent
            }
            VfxBatchRenderer(host.vfxEngine, host.vfxScene, material, texture, BATCH_CAPACITY)
        }
        val used = active.fold(0) { acc, a -> if (a.batch === batch) acc + a.spec.maxParticles else acc }
        val budget = spec.maxParticles.coerceIn(1, batch.capacityParticles)
        if (used + budget > batch.capacityParticles) {
            Log.w(TAG, "batch ${spec.blending}/${key.texture} full ($used/${batch.capacityParticles}); rejecting '${spec.id}'")
            return null
        }
        // Bone 锚点播放瞬间先解析一次：起点即骨骼当前位置（解析失败照样播，
        // 播放期间失败才自停——模型重载竞态不拦播放）
        val pos = FloatArray(3)
        val text: String
        when (anchor) {
            is VfxAnchor.World -> {
                pos[0] = anchor.x; pos[1] = anchor.y; pos[2] = anchor.z
                text = "world(%.1f,%.1f,%.1f)".format(anchor.x, anchor.y, anchor.z)
            }

            is VfxAnchor.Bone -> {
                val ok = host.vfxResolveBone(anchor.avatarId, anchor.boneName, pos)
                if (!ok) pos[1] = 1f // 兜底抬到腰部量级，可见性优先；随后每帧重解析
                text = "bone:${anchor.boneName}${anchor.avatarId?.let { "@$it" } ?: ""}"
            }
        }
        val handle = VfxHandle("fx${idCounter.incrementAndGet()}")
        active.add(
            Active(
                handle, spec, ParticleSystem(spec.copy(maxParticles = budget), SEEDED_RANDOM),
                batch, anchor, pos, text, 0f,
            ),
        )
        publish()
        Log.i(TAG, "play '${spec.id}' as ${handle.id} (${spec.blending}, $budget particles @ $text)")
        return handle
    }

    /** 停止一个特效（粒子当帧不再写出）。 */
    fun stop(id: String): Boolean {
        val removed = active.removeAll { it.handle.id == id }
        if (removed) publish()
        return removed
    }

    /** 停止全部特效。 */
    fun stopAll() {
        if (active.isEmpty()) return
        active.clear()
        publish()
    }

    /** 活跃特效实时快照（ai_cmd `fx state` 数据源；调试路径，允许分配）。 */
    fun snapshot(): List<VfxInfo> = active.map { a ->
        VfxInfo(
            id = a.handle.id,
            presetId = a.spec.id,
            blending = a.spec.blending,
            activeParticles = a.system.pool.activeCount,
            ageSeconds = a.age,
            anchor = a.anchorText,
        )
    }

    /**
     * 每帧泵（choreoCallback 内、modelViewer.render 之前）。未初始化（从未
     * 播放过特效）时直接返回，主循环零开销。
     */
    fun update(frameTimeNanos: Long) {
        if (batches.isEmpty() && active.isEmpty()) return
        val dt = if (lastFrameNanos == 0L) DEFAULT_DT
        else ((frameTimeNanos - lastFrameNanos).coerceAtLeast(0L)) / 1_000_000_000.0f
        lastFrameNanos = frameTimeNanos

        // 同帧相机基向量（updateCameraShot 刚写入 Manipulator）
        host.vfxCameraLookAt(eye, target, camUpIn)
        VfxMath.basisFromLookAt(eye, target, camUpIn, right, camUp)
        var fx = target[0] - eye[0]
        var fy = target[1] - eye[1]
        var fz = target[2] - eye[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        if (fl > 1e-8f) {
            fx /= fl; fy /= fl; fz /= fl
        } else {
            fx = 0f; fy = 0f; fz = -1f
        }
        forward[0] = fx; forward[1] = fy; forward[2] = fz

        var removedAny = false
        var i = 0
        while (i < active.size) {
            val a = active[i]
            // Bone 锚点逐帧重解析（发射器跟随；实体失效→安静自停，不持陈旧引用）
            if (a.anchor is VfxAnchor.Bone) {
                val ok = host.vfxResolveBone(a.anchor.avatarId, a.anchor.boneName, bonePos)
                if (!ok) {
                    Log.i(TAG, "anchor lost for ${a.handle.id} (${a.anchorText}); auto-stopping")
                    active.removeAt(i)
                    removedAny = true
                    continue
                }
                a.anchorPos[0] = bonePos[0]
                a.anchorPos[1] = bonePos[1]
                a.anchorPos[2] = bonePos[2]
            }
            i++
        }

        for ((_, batch) in batches) {
            var cursor = 0
            i = 0
            while (i < active.size) {
                val a = active[i]
                if (a.batch !== batch) {
                    i++
                    continue
                }
                val count = a.system.update(
                    dt, a.anchorPos[0], a.anchorPos[1], a.anchorPos[2],
                    right, camUp, forward,
                    batch.vertices, cursor,
                )
                cursor += count * VfxMath.FLOATS_PER_PARTICLE
                a.age += dt
                if (a.system.isFinished) {
                    active.removeAt(i)
                    removedAny = true
                    continue // 换位补位后原下标重查
                }
                i++
            }
            batch.flush(cursor / VfxMath.FLOATS_PER_PARTICLE)
        }
        if (removedAny) publish()
    }

    /**
     * 幂等销毁（可重入，坑 13 + 二次 onDestroy 双护栏前科）。顺序 = §4.5：
     * 清绘制引用/摘 renderable（含 VB/IB/MI）→ 毁材质 → 毁纹理（实例先于
     * 纹理，坑 13 尾注）。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        active.clear()
        batches.values.forEach { it.destroy() }
        batches.clear()
        materialFactory?.destroy()
        materialFactory = null
        textures.values.distinct().forEach { host.vfxEngine.destroyTexture(it) }
        textures.clear()
        if (_effects.value.isNotEmpty()) {
            _effects.value = emptyList()
        }
    }

    /** 材质运行时编译（首个 play 触发；失败 = 特效不可用降级，不炸主流程）。 */
    private fun ensureInit(): Boolean {
        if (materialFactory != null) return true
        val factory = VfxMaterialFactory.create(host.vfxEngine) ?: return false
        materialFactory = factory
        Log.i(TAG, "vfx renderer initialized (batch capacity=$BATCH_CAPACITY)")
        return true
    }

    /** 贴图惰性加载 + 缓存（键=VfxTexture 请求；同请求多预设共享一张 GPU 纹理；
     *  Asset 失败回退柔边圆点实例并缓存到原请求 key 下，destroy 统一收）。 */
    private fun ensureTexture(texture: VfxTexture): Texture {
        textures[texture]?.let { return it }
        if (texture is VfxTexture.SoftDot) {
            val t = VfxTextureFactory.create(host.vfxEngine, texture) { null }
            textures[texture] = t
            return t
        }
        val created = try {
            VfxTextureFactory.create(host.vfxEngine, texture) { path -> host.vfxReadAssetBytes(path) }
        } catch (e: Exception) {
            Log.w(TAG, "texture create failed for $texture: ${e.message}")
            null
        } ?: softDotFallback()
        textures[texture] = created
        return created
    }

    /** Asset 回退：复用已缓存的柔边圆点（有则免重建），否则现建并缓存。 */
    private fun softDotFallback(): Texture =
        textures.getOrPut(VfxTexture.SoftDot) {
            VfxTextureFactory.create(host.vfxEngine, VfxTexture.SoftDot) { null }
        }

    private fun publish() {
        _effects.value = snapshot()
        onChange?.invoke()
    }

    companion object {
        private const val TAG = "VfxManager"

        /** 每批容量（粒子数；按（混合档×贴图）各一份）。 */
        const val BATCH_CAPACITY = 512

        private const val DEFAULT_DT = 0.016f

        /**
         * 确定性种子：特效是场景级观感元素（无 gameplay 语义），固定种子让
         * 每次播放的可复现性优先于统计随机性；真机差分验收可对拍。
         */
        private val SEEDED_RANDOM = kotlin.random.Random(20261016)

    }
}
