package com.neethu.corelib.accessory

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.filament.gltfio.FilamentAsset
import com.neethu.corelib.RenderMode
import com.neethu.corelib.stage.internal.ModelPreprocessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AccessoryController] 的公共实现（每条渲染路径的每个虚拟人一份：团播 =
 * Stage/AvatarRuntime，单人 = SoulLinkRenderer，经 [AccessoryHost] 解耦）。
 * 挂载原理（docs/accessory-feasibility.md §5.2）：配饰 GLB 作为独立
 * FilamentAsset 加载后 `TransformManager.setParent` 到骨骼实体——VRMA 每帧
 * 只写骨骼局部变换，配饰经场景图父链自动跟随动画，**零每帧代码**。
 *
 * `setParent` 为全仓库首用（P0 退出条件预案：若层级异常，fallback =
 * 每帧世界矩阵对齐，预算 +0.5 人日——真机验证见 P0 清单）。
 *
 * 线程约定：全部方法主线程调用（broadcast/命令路由都在主线程）。
 *
 * @param resolveBone 语义骨骼名 → 实体（调用方注入 bind 时解析好的全语义
 *   表；0 = 未解析）。
 * @param resolveSpec 配饰 ID → 挂载规格（动画联动自动挂载用；app 层注入
 *   sidecar 默认+per-model 校准的合并结果；null/查不到 = 该配饰的绑定
 *   静默无效）。
 * @param springCollider 外部碰撞体缝（P2-3 帽子压头发实验）：规格带
 *   [AccessorySpec.springColliderRadius] > 0 时随挂载/卸载调用，register
 *   =true 注册、=false 注销；boneEntity 为挂点骨骼实体。null = 该路径不
 *   支持碰撞体（实验只在接了弹簧骨骼的路径生效）。
 * @param ownerTag 日志前缀（演员 ID）。
 */
internal class AccessoryManager(
    private val host: AccessoryHost,
    private val resolveBone: (String) -> Int,
    private val ownerTag: String,
    private val resolveSpec: ((String) -> AccessorySpec?)? = null,
    private val springCollider: ((spec: AccessorySpec, boneEntity: Int, register: Boolean) -> Unit)? = null,
) : AccessoryController {

    private class Instance(
        val spec: AccessorySpec,
        val asset: FilamentAsset,
        val boneEntity: Int,
        var visible: Boolean = true,
    )

    private val instances = LinkedHashMap<String, Instance>()

    private val handler = Handler(Looper.getMainLooper())

    /** 动画联动状态机（纯逻辑，调度走主线程 Handler；show/hide 回调本类）。 */
    private val binder = AccessoryAnimationBinder(
        scheduler = { delayMs, action ->
            val token = Runnable { action() }
            handler.postDelayed(token, delayMs)
            AccessoryAnimationBinder.Cancelable { handler.removeCallbacks(token) }
        },
        show = ::ensurePresent,
        hide = { id -> if (instances.containsKey(id)) setVisible(id, false) },
    )

    private val _accessories = MutableStateFlow<List<AccessoryState>>(emptyList())
    override val accessories: StateFlow<List<AccessoryState>> = _accessories.asStateFlow()

    /**
     * 状态发布回调（publish 时同步触发）。管理器随模型重建、StateFlow 实例
     * 不稳定，跨重建持有稳定流的调用方（AvatarController）经此转发。
     */
    var onChange: (() -> Unit)? = null

    override fun attach(spec: AccessorySpec): Result<Unit> {
        if (instances.containsKey(spec.id)) {
            return Result.failure(IllegalStateException("$ownerTag accessory '${spec.id}' already attached"))
        }
        val loader = host.accAssetLoader
            ?: return Result.failure(IllegalStateException("render host not attached"))
        val resources = host.accResourceLoader
            ?: return Result.failure(IllegalStateException("render host not attached"))

        // ①② 字节读取 + 预处理（白嫖 unlit 剥除 / morph 注入；非 VRM → PBR 兜底）
        val sourcePath = when (val s = spec.source) {
            is AccessorySource.Asset -> s.path
            is AccessorySource.File -> s.path
        }
        val prepared = try {
            ModelPreprocessor.process(
                host.accReadSourceBytes(sourcePath),
                requestedMode = RenderMode.PBR,
                autoRenderMode = false,
            )
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("accessory bytes failed: $sourcePath", e))
        }

        // ③ createAsset + 同步资源（小文件毫秒级）
        val asset = loader.createAsset(prepared.finalBuffer)
            ?: return Result.failure(IllegalStateException("createAsset failed: $sourcePath"))
        resources.loadResources(asset)
        asset.releaseSourceData()

        // ④⑤ 挂点解析 + re-parent（骨骼局部系挂在人体层级内）；世界挂载
        //     （§6.2 地面/场景道具）不挂骨骼——无父即世界系，直接写世界 TRS
        val tm = host.accEngine.transformManager
        val propInstance = tm.getInstance(asset.root)
        if (propInstance == 0) {
            loader.destroyAsset(asset)
            return Result.failure(IllegalStateException("$ownerTag transform missing (prop=$propInstance)"))
        }
        val world = spec.world
        val boneEntity: Int
        if (world != null) {
            boneEntity = 0
            tm.setTransform(
                propInstance,
                AccessoryMath.worldTransform(world, spec.position, spec.rotationEulerYXZ, spec.scale),
            )
        } else {
            boneEntity = resolveBone(spec.bone)
            if (boneEntity == 0) {
                loader.destroyAsset(asset)
                return Result.failure(IllegalArgumentException("$ownerTag bone '${spec.bone}' not resolved"))
            }
            val boneInstance = tm.getInstance(boneEntity)
            if (boneInstance == 0) {
                loader.destroyAsset(asset)
                return Result.failure(IllegalStateException("$ownerTag transform missing (prop=$propInstance bone=$boneInstance)"))
            }
            tm.setParent(propInstance, boneInstance)

            // ⑥ 局部 TRS（骨骼局部单位；unit-cube 根缩放经父链传播，不再乘 renderScale）
            tm.setTransform(
                propInstance,
                AccessoryMath.localTransform(spec.position, spec.rotationEulerYXZ, spec.scale),
            )
        }

        // 阴影开关沿 spec（PCSS Adreno 悬崖默认关）；剔除沿 avatar 惯例关
        val rm = host.accEngine.renderableManager
        for (entity in asset.renderableEntities) {
            val inst = rm.getInstance(entity)
            if (inst == 0) continue
            rm.setCastShadows(inst, spec.castShadows)
            rm.setCulling(inst, false)
        }

        // ⑦ 入场景 + 登记（先落位再进场，避免首帧闪现在原点）
        host.accScene.addEntities(asset.entities)
        instances[spec.id] = Instance(spec, asset, boneEntity)
        publish()
        if (world != null) {
            Log.i(TAG, "$ownerTag attach '${spec.id}' → world(${world.x},${world.y},${world.z} yaw=${world.yawDeg}) ($sourcePath, scale=${spec.scale})")
        } else {
            Log.i(TAG, "$ownerTag attach '${spec.id}' → ${spec.bone} ($sourcePath, pos=${spec.position.contentToString()}, rot=${spec.rotationEulerYXZ.contentToString()}, scale=${spec.scale})")
        }

        // P2-3 外部碰撞体（帽子压头发实验）：随挂载注册，随卸载注销（世界摆件
        // 无骨骼挂点，碰撞体缝不适用）
        if (spec.springColliderRadius > 0f && world == null) {
            springCollider?.invoke(spec, boneEntity, true)
        }
        return Result.success(Unit)
    }

    override fun detach(id: String): Boolean {
        val instance = instances.remove(id) ?: return false
        if (instance.spec.springColliderRadius > 0f && instance.spec.world == null) {
            springCollider?.invoke(instance.spec, instance.boneEntity, false)
        }
        // SIGABRT 纪律（对齐 AvatarRuntime.destroyAsset）：先摘 scene 再毁 asset
        host.accScene.removeEntities(instance.asset.entities)
        host.accAssetLoader?.destroyAsset(instance.asset)
            ?: Log.w(TAG, "$ownerTag accessory '$id': asset loader gone; leaking entities")
        publish()
        Log.i(TAG, "$ownerTag detach '$id'")
        return true
    }

    override fun setVisible(id: String, visible: Boolean): Boolean {
        val instance = instances[id] ?: return false
        if (instance.visible == visible) return true
        instance.visible = visible
        if (visible) {
            host.accScene.addEntities(instance.asset.entities)
        } else {
            host.accScene.removeEntities(instance.asset.entities)
        }
        publish()
        return true
    }

    /** 演员 asset 重建/销毁前调用：骨骼实体即将失效，配饰必须先走。 */
    fun destroyAll() {
        val ids = instances.keys.toList()
        for (id in ids) detach(id)
    }

    // ── 动画联动（AvatarRuntime 在播放/收尾点同步通知；白名单见 Binder）──

    /** VRMA 起播（含走动步态/overlay 内部播放）：状态机按绑定表显隐。 */
    fun onVrmaStarted(clipPath: String) {
        binder.onStarted(clipPath)
    }

    /** VRMA 收尾（播完/停播/被顶替翻沿）：状态机延迟隐藏命中的配饰。 */
    fun onVrmaFinished(clipPath: String) {
        binder.onFinished(clipPath)
    }

    override fun bindToAnimation(accessoryId: String, animationClipPath: String): Boolean =
        binder.bind(animationClipPath, accessoryId)

    override fun unbindFromAnimation(accessoryId: String, animationClipPath: String): Boolean =
        binder.unbind(animationClipPath, accessoryId)

    override val animationBindings: Map<String, String>
        get() = binder.snapshot()

    /**
     * 绑定命中的在场保证：未挂载则经解析器自动挂载（sidecar+校准规格），
     * 已挂载则只显。解析失败（未知配饰 ID）静默降级——绑定表是显式白名单，
     * 缺资产不该在动画播放路径上抛错。
     */
    private fun ensurePresent(id: String) {
        if (instances.containsKey(id)) {
            setVisible(id, true)
            return
        }
        val spec = resolveSpec?.invoke(id)
        if (spec == null) {
            Log.w(TAG, "$ownerTag bind hit '$id' but no spec resolver/entry; skipped")
            return
        }
        attach(spec).onFailure { Log.w(TAG, "$ownerTag auto-attach '$id' failed: ${it.message}") }
    }

    private fun publish() {
        _accessories.value = instances.map { (id, inst) ->
            // 世界摆件（§6.2）bone 字段无意义，观测面统一报 world
            AccessoryState(
                id = id,
                bone = if (inst.spec.world != null) "world" else inst.spec.bone,
                visible = inst.visible,
            )
        }
        onChange?.invoke()
    }

    companion object {
        private const val TAG = "AccessoryManager"
    }
}
