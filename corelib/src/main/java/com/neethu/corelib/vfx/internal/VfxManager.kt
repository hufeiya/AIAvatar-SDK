package com.neethu.corelib.vfx.internal

import android.util.Log
import com.google.android.filament.Texture
import com.neethu.corelib.vfx.VfxAnchor
import com.neethu.corelib.vfx.VfxBlending
import com.neethu.corelib.vfx.VfxHandle
import com.neethu.corelib.vfx.VfxInfo
import com.neethu.corelib.vfx.VfxMath
import com.neethu.corelib.vfx.VfxRenderMode
import com.neethu.corelib.vfx.VfxSpec
import com.neethu.corelib.vfx.VfxTexture
import com.neethu.corelib.vfx.sim.ParticleSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * 多特效编排器（docs/vfx-feasibility.md §4.2 + 画质升级 2026-10-17）：play/
 * stop 生命周期、每帧泵、幂等 destroy。
 *
 * 每帧时序（[update]，挂在 updateCameraShot 之后、render 之前——同帧相机
 * 基向量无一帧滞后，骨骼 commit 已跑过）：dt 钳制 → 全局风时钟推进 → 取
 * 相机三元组导基向量 → Bone 锚点逐帧解析（发射器跟随、已存活粒子留世界系；
 * 解析失败自停，§4.8）→ 逐特效逐层把顶点顺序写入各自批缓冲 → flush（动态
 * 索引数）→ 灯架推进 → MToon featured 灯推送。
 *
 * 复合层（VfxSpec.layers）：一个预设 = 多个子发射器（火焰 = 火苗+辉光+火星+
 * 烟），共享锚点与生命周期；全层播完才自停。舞台灯组（VfxSpec.lights）挂
 * [VfxStageLightRig]（真灯+光束+地面光斑），纯灯特效永不自停（stop 才停）。
 * 贴花组（VfxSpec.decals，P2 法阵/护盾）挂 [VfxDecalRig]，纯贴花同款常驻；
 * 移除路径（stop/stopAll/锚失效/播完/destroy）全部同步销毁 decal 架（新坑 4
 * 纪律：新资源入生命周期，全移除路径必须收口）。CameraFront 锚点每帧用
 * eye+forward 解析（app 层零相机依赖）。
 *
 * 批按（混合档 × 贴图 × 渲染模式）键控（材质实例一张 mainTexture，异贴图/
 * 异顶点布局不能共批；全屏 draw call 数 = 在用组合数，个位数）。贴图缓存
 * 按 [VfxTexture] 惰性加载（同贴图多预设共享一张 GPU 纹理）。
 *
 * 全局风时钟 [windSeconds] 单调推进：所有带风特效同相采样（阵风一致），
 * 环境侧（头发弹簧骨骼）经 [ambientWind] 取同源采样。
 *
 * 零 GC 纪律：基向量/锚点/scratch/游标表全部预分配；update 路径仅剩 finished
 * 时一次 removeAt，无其他分配。
 *
 * 线程约定：全部方法主线程调用（choreoCallback/broadcast 同线程）。
 */
internal class VfxManager(private val host: VfxHost) {

    /** 复合层内的一路子发射器（自己的系统 + 落进的批）。 */
    private class Layer(
        val spec: VfxSpec,
        val system: ParticleSystem,
        val batch: VfxBatchRenderer,
    )

    private class Active(
        val handle: VfxHandle,
        val spec: VfxSpec,
        val layers: List<Layer>,
        val rig: VfxStageLightRig?,
        /** 贴花架（P2 法阵/护盾；null = 无 decals）。 */
        val decals: VfxDecalRig?,
        val anchor: VfxAnchor,
        /** 世界坐标锚点缓存（World = 一次写入；Bone/CameraFront = 每帧覆写）。 */
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

    /** 批键：混合档 × 实际贴图实例 × 渲染模式（CROSS 8 顶点与 Billboard 4 顶点不共批）。 */
    private data class BatchKey(val blending: VfxBlending, val texture: Texture, val mode: VfxRenderMode)

    /** 上帧时间戳（dt 来源；0 = 首帧）。 */
    private var lastFrameNanos = 0L

    /** 全局风时钟秒（单调推进；所有带风特效与头发外力同源采样）。 */
    var windSeconds = 0.0
        private set

    // 每帧 scratch（预分配零 GC）
    private val eye = FloatArray(3)
    private val target = FloatArray(3)
    private val camUpIn = FloatArray(3)
    private val forward = FloatArray(3)
    private val right = FloatArray(3)
    private val camUp = FloatArray(3)
    private val bonePos = FloatArray(3)
    private val batchCursors = HashMap<VfxBatchRenderer, Int>()
    private val lampOut = VfxStageLightRig.LampOut()
    private val pushPos = FloatArray(3)
    private val pushDir = FloatArray(3)
    private val pushColor = FloatArray(3)

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
        val factory = materialFactory!!

        // 层展开（无层 = 单层自身）；预算先验后提交，拒绝时不留半成品
        val layerSpecs: List<VfxSpec> = spec.layers?.takeIf { it.isNotEmpty() } ?: listOf(spec)
        val plan = ArrayList<Layer>(layerSpecs.size)
        for (ls in layerSpecs) {
            val texture = ensureTexture(ls.texture)
            val key = BatchKey(ls.blending, texture, ls.renderMode)
            val batch = batches.getOrPut(key) {
                val material = when (ls.blending) {
                    VfxBlending.ADDITIVE -> factory.additive
                    VfxBlending.TRANSPARENT -> factory.transparent
                }
                VfxBatchRenderer(
                    host.vfxEngine, host.vfxScene, material, texture, BATCH_CAPACITY,
                    if (ls.renderMode == VfxRenderMode.CROSS) 8 else 4,
                ).also { batchCursors[it] = 0 }
            }
            val budget = ls.maxParticles.coerceIn(1, batch.capacityParticles)
            var used = 0
            for (a in active) for (l in a.layers) if (l.batch === batch) used += l.spec.maxParticles
            for (planned in plan) if (planned.batch === batch) used += planned.spec.maxParticles
            if (used + budget > batch.capacityParticles) {
                Log.w(TAG, "batch ${ls.blending}/${key.texture}/${ls.renderMode} full ($used/${batch.capacityParticles}); rejecting '${spec.id}'")
                return null
            }
            plan.add(Layer(ls, ParticleSystem(ls.copy(maxParticles = budget), SEEDED_RANDOM), batch))
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

            is VfxAnchor.CameraFront -> {
                // 播放瞬间无相机三元组（update 每帧解析）：先给个取景框内的
                // 占位，首个 update 帧即被覆写（play 在帧间执行，不会渲染该值）
                pos[0] = 0f; pos[1] = 1.2f; pos[2] = -2.8f
                text = "camera_front(%.1fm)".format(anchor.distanceMeters)
            }
        }

        // 舞台灯架（纯灯特效 = layers 无粒子也常驻）
        var rig: VfxStageLightRig? = null
        if (spec.lights.isNotEmpty()) {
            rig = VfxStageLightRig(
                host.vfxEngine, host.vfxScene,
                factory.beam, factory.additive, ensureTexture(VfxTexture.SoftDot),
                spec.lights,
            )
        }

        // 贴花架（P2 法阵/护盾；构建失败的单张贴花在 rig 内部降级跳过）
        var decalRig: VfxDecalRig? = null
        if (spec.decals.isNotEmpty()) {
            decalRig = VfxDecalRig(
                host.vfxEngine, host.vfxScene, factory.decal,
                spec.decals.map { VfxDecalRig.Binding(it, ensureTexture(it.texture)) },
            )
        }

        val handle = VfxHandle("fx${idCounter.incrementAndGet()}")
        active.add(Active(handle, spec, plan, rig, decalRig, anchor, pos, text, 0f))
        publish()
        Log.i(TAG, "play '${spec.id}' as ${handle.id} (${spec.blending}, ${plan.size} layer(s), " +
            "${spec.lights.size} light(s), ${spec.decals.size} decal(s) @ $text)")
        return handle
    }

    /** 停止一个特效（粒子当帧不再写出；灯架/贴花架同帧摘除——真机回归抓漏：
     *  stop 不销毁 rig 会让光束 MI/灯实体跨次播放累积，毁材质时
     *  "N instances still alive" PreconditionPanic；decal 架同理收口）。 */
    fun stop(id: String): Boolean {
        var removedAny = false
        val it = active.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (a.handle.id == id) {
                a.rig?.destroy()
                a.decals?.destroy()
                it.remove()
                removedAny = true
            }
        }
        if (removedAny) publish()
        return removedAny
    }

    /** 停止全部特效（同 [stop]：rig/decal 架先于列表清空销毁）。 */
    fun stopAll() {
        if (active.isEmpty()) return
        active.forEach {
            it.rig?.destroy()
            it.decals?.destroy()
        }
        active.clear()
        publish()
    }

    /** 活跃特效实时快照（ai_cmd `fx state` 数据源；调试路径，允许分配）。 */
    fun snapshot(): List<VfxInfo> = active.map { a ->
        VfxInfo(
            id = a.handle.id,
            presetId = a.spec.id,
            blending = a.spec.blending,
            activeParticles = a.layers.fold(0) { acc, l -> acc + l.system.pool.activeCount },
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
        windSeconds += dt

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
            // 每帧重解析锚点：Bone 跟骨骼（实体失效→安静自停，不持陈旧引用）；
            // CameraFront 跟镜头（eye+forward·距离，VfxMath.cameraFrontPoint）
            when (val anc = a.anchor) {
                is VfxAnchor.Bone -> {
                    val ok = host.vfxResolveBone(anc.avatarId, anc.boneName, bonePos)
                    if (!ok) {
                        Log.i(TAG, "anchor lost for ${a.handle.id} (${a.anchorText}); auto-stopping")
                        a.rig?.destroy()
                        a.decals?.destroy()
                        active.removeAt(i)
                        removedAny = true
                        continue
                    }
                    a.anchorPos[0] = bonePos[0]
                    a.anchorPos[1] = bonePos[1]
                    a.anchorPos[2] = bonePos[2]
                }

                is VfxAnchor.CameraFront ->
                    VfxMath.cameraFrontPoint(eye, forward, anc.distanceMeters, a.anchorPos)

                is VfxAnchor.World -> Unit
            }
            i++
        }

        // 游标清零（批集合稳定，无新分配）
        for (key in batchCursors.keys) batchCursors[key] = 0

        i = 0
        while (i < active.size) {
            val a = active[i]
            var allFinished = a.rig == null && a.decals == null
            for (layer in a.layers) {
                val count = layer.system.update(
                    dt, windSeconds, a.anchorPos[0], a.anchorPos[1], a.anchorPos[2],
                    right, camUp, forward,
                    layer.batch.vertices,
                    batchCursors[layer.batch] ?: 0,
                )
                batchCursors[layer.batch] = (batchCursors[layer.batch] ?: 0) + count * layer.batch.floatsPerParticle
                if (!layer.system.isFinished) allFinished = false
            }
            a.age += dt
            a.rig?.update(a.age, a.anchorPos[0], a.anchorPos[1], a.anchorPos[2])
            a.decals?.update(a.age, a.anchorPos[0], a.anchorPos[1], a.anchorPos[2], eye)
            if (allFinished) {
                a.rig?.destroy()
                a.decals?.destroy()
                active.removeAt(i)
                removedAny = true
                continue // 换位补位后原下标重查
            }
            i++
        }

        for ((batch, cursor) in batchCursors) {
            batch.flush(cursor / batch.floatsPerParticle)
        }
        pushFeaturedMToonLight()
        if (removedAny) publish()
    }

    /**
     * MToon featured 灯推送：全部活跃灯架中挑对角色（锚点代理）贡献最大的
     * 一盏推给 MToon 材质；无活跃灯 → 推零关闭。
     */
    private fun pushFeaturedMToonLight() {
        var best: VfxStageLightRig? = null
        // 评估点用第一个活跃特效的锚点（角色脚底代理；Bone 锚即骨骼位置）
        var ex = 0f; var ey = 0f; var ez = 0f
        var haveEval = false
        for (a in active) {
            if (a.rig != null) {
                if (!haveEval) {
                    ex = a.anchorPos[0]; ey = a.anchorPos[1]; ez = a.anchorPos[2]
                    haveEval = true
                }
                best = a.rig
            }
        }
        if (best == null) {
            if (mtoonStageLightActive) {
                host.vfxPushMToonStageLight(pushPos, pushDir, pushColor, 0f, 1f, 0f)
                mtoonStageLightActive = false
            }
            return
        }
        lampOut.evalX = ex; lampOut.evalY = ey; lampOut.evalZ = ez
        if (!best.featuredLight(lampOut)) {
            if (mtoonStageLightActive) {
                host.vfxPushMToonStageLight(pushPos, pushDir, pushColor, 0f, 1f, 0f)
                mtoonStageLightActive = false
            }
            return
        }
        pushPos[0] = lampOut.posX; pushPos[1] = lampOut.posY; pushPos[2] = lampOut.posZ
        pushDir[0] = lampOut.dirX; pushDir[1] = lampOut.dirY; pushDir[2] = lampOut.dirZ
        pushColor[0] = lampOut.colorR; pushColor[1] = lampOut.colorG; pushColor[2] = lampOut.colorB
        host.vfxPushMToonStageLight(pushPos, pushDir, pushColor, lampOut.cosInner, lampOut.cosOuter, lampOut.range)
        mtoonStageLightActive = true
    }

    private var mtoonStageLightActive = false

    /**
     * 环境风采样（头发弹簧骨骼外力馈送的数据源）：取「最强带风特效」的风场
     * 在 (x,z) 处的水平风速（米/秒）写进 [out]（2 float）。@return true =
     * 有活跃带风特效（false = 调用方应清零骨骼外力）。
     */
    fun ambientWind(x: Float, z: Float, out: FloatArray): Boolean {
        var best: com.neethu.corelib.vfx.sim.WindField? = null
        var bestStrength = -1f
        for (a in active) for (l in a.layers) {
            val w = l.system.windFieldOrNull() ?: continue
            val s = l.spec.wind?.strength ?: 0f
            if (s > bestStrength) {
                bestStrength = s
                best = w
            }
        }
        best ?: return false
        best.sample(x, z, windSeconds, out)
        return true
    }

    /**
     * 幂等销毁（可重入，坑 13 + 二次 onDestroy 双护栏前科）。顺序 = §4.5：
     * 清绘制引用/摘 renderable（含 VB/IB/MI）→ 毁材质 → 毁纹理（实例先于
     * 纹理，坑 13 尾注）；灯架先行（真灯/束/光斑实体独立于批）。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        active.forEach {
            it.rig?.destroy()
            it.decals?.destroy()
        }
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

        /** 每批容量（粒子数；按（混合档×贴图×渲染模式）各一份）。 */
        const val BATCH_CAPACITY = 512

        private const val DEFAULT_DT = 0.016f

        /**
         * 确定性种子：特效是场景级观感元素（无 gameplay 语义），固定种子让
         * 每次播放的可复现性优先于统计随机性；真机差分验收可对拍。
         */
        private val SEEDED_RANDOM = kotlin.random.Random(20261016)
    }
}
