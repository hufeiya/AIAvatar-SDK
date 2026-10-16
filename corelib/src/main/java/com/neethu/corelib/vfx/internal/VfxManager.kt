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
 * 逐批按创建序把各特效顶点顺序写入批缓冲 → flush（动态索引数）。
 *
 * 零 GC 纪律：基向量/锚点/scratch 全部预分配；update 路径仅剩finished 时
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
        val anchor: FloatArray,
        var age: Float,
    )

    private val active = ArrayList<Active>()
    private val idCounter = AtomicInteger(0)

    private var materialFactory: VfxMaterialFactory? = null
    private var softDotTexture: Texture? = null
    private var batches: Map<VfxBlending, VfxBatchRenderer>? = null
    private var destroyed = false

    /** 上帧时间戳（dt 来源；0 = 首帧）。 */
    private var lastFrameNanos = 0L

    // 每帧 scratch（预分配零 GC）
    private val eye = FloatArray(3)
    private val target = FloatArray(3)
    private val camUpIn = FloatArray(3)
    private val forward = FloatArray(3)
    private val right = FloatArray(3)
    private val camUp = FloatArray(3)

    /** 状态列表变化（play/stop/播完自停）→ 渲染器中继到 AvatarController。 */
    var onChange: (() -> Unit)? = null

    private val _effects = MutableStateFlow<List<VfxInfo>>(emptyList())
    val effects: StateFlow<List<VfxInfo>> = _effects.asStateFlow()

    /**
     * 播放一个特效。
     * @return 句柄；null = 渲染未就绪（材质编译失败）或对应混合档批容量已满。
     */
    fun play(spec: VfxSpec, anchor: VfxAnchor): VfxHandle? {
        if (destroyed) return null
        val batchMap = ensureInit() ?: return null
        val batch = batchMap[spec.blending] ?: return null
        val used = active.fold(0) { acc, a -> if (a.batch === batch) acc + a.spec.maxParticles else acc }
        val budget = spec.maxParticles.coerceIn(1, batch.capacityParticles)
        if (used + budget > batch.capacityParticles) {
            Log.w(TAG, "batch ${spec.blending} full ($used/${batch.capacityParticles}); rejecting '${spec.id}'")
            return null
        }
        val handle = VfxHandle("fx${idCounter.incrementAndGet()}")
        val anchorPos = when (anchor) {
            is VfxAnchor.World -> floatArrayOf(anchor.x, anchor.y, anchor.z)
        }
        active.add(
            Active(
                handle, spec, ParticleSystem(spec.copy(maxParticles = budget), SEEDED_RANDOM),
                batch, anchorPos, 0f,
            ),
        )
        publish()
        Log.i(TAG, "play '${spec.id}' as ${handle.id} (${spec.blending}, $budget particles @ ${anchorPos.contentToString()})")
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
        )
    }

    /**
     * 每帧泵（choreoCallback 内、modelViewer.render 之前）。未初始化（从未
     * 播放过特效）时直接返回，主循环零开销。
     */
    fun update(frameTimeNanos: Long) {
        val batchMap = batches ?: return
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
        for ((_, batch) in batchMap) {
            var cursor = 0
            var i = 0
            while (i < active.size) {
                val a = active[i]
                if (a.batch !== batch) {
                    i++
                    continue
                }
                val count = a.system.update(
                    dt, a.anchor[0], a.anchor[1], a.anchor[2],
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
     * 清绘制引用/摘 renderable（含 VB/IB/MI）→ 毁材质 → 毁纹理。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        active.clear()
        batches?.values?.forEach { it.destroy() }
        materialFactory?.destroy()
        softDotTexture?.let { host.vfxEngine.destroyTexture(it) }
        batches = null
        materialFactory = null
        softDotTexture = null
        if (_effects.value.isNotEmpty()) {
            _effects.value = emptyList()
        }
    }

    private fun ensureInit(): Map<VfxBlending, VfxBatchRenderer>? {
        batches?.let { return it }
        val factory = VfxMaterialFactory.create(host.vfxEngine) ?: return null
        val tex = VfxTextureFactory.create(host.vfxEngine, VfxTexture.SoftDot)
        val map = mapOf(
            VfxBlending.ADDITIVE to VfxBatchRenderer(
                host.vfxEngine, host.vfxScene, factory.additive, tex, BATCH_CAPACITY,
            ),
            VfxBlending.TRANSPARENT to VfxBatchRenderer(
                host.vfxEngine, host.vfxScene, factory.transparent, tex, BATCH_CAPACITY,
            ),
        )
        materialFactory = factory
        softDotTexture = tex
        batches = map
        Log.i(TAG, "vfx renderer initialized (batches=${map.size}, capacity=$BATCH_CAPACITY each)")
        return map
    }

    private fun publish() {
        _effects.value = snapshot()
        onChange?.invoke()
    }

    companion object {
        private const val TAG = "VfxManager"

        /** 每混合档批容量（粒子数；全屏两档合计 2 个 draw call）。 */
        const val BATCH_CAPACITY = 512

        private const val DEFAULT_DT = 0.016f

        /**
         * 确定性种子：特效是场景级观感元素（无 gameplay 语义），固定种子让
         * 每次播放的可复现性优先于统计随机性；真机差分验收可对拍。
         */
        private val SEEDED_RANDOM = kotlin.random.Random(20261016)

    }
}
