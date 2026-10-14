package com.neethu.corelib.accessory

import android.util.Log
import com.google.android.filament.gltfio.FilamentAsset
import com.neethu.corelib.RenderMode
import com.neethu.corelib.stage.internal.ModelPreprocessor
import com.neethu.corelib.stage.internal.Stage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AccessoryController] 的 Stage 路径实现（每个 [com.neethu.corelib.stage.
 * AvatarRuntime] 一份）。挂载原理（docs/accessory-feasibility.md §5.2）：
 * 配饰 GLB 作为独立 FilamentAsset 加载后 `TransformManager.setParent` 到
 * 骨骼实体——VRMA 每帧只写骨骼局部变换，配饰经场景图父链自动跟随，
 * **零每帧代码**。
 *
 * `setParent` 为全仓库首用（P0 退出条件预案：若层级异常，fallback =
 * 每帧世界矩阵对齐，预算 +0.5 人日——真机验证见 P0 清单）。
 *
 * 线程约定：全部方法主线程调用（broadcast/命令路由都在主线程）。
 *
 * @param resolveBone 语义骨骼名 → 实体（AvatarRuntime 注入 bind 时解析好
 *   的全语义表；0 = 未解析）。
 * @param ownerTag 日志前缀（演员 ID）。
 */
internal class AccessoryManager(
    private val stage: Stage,
    private val resolveBone: (String) -> Int,
    private val ownerTag: String,
) : AccessoryController {

    private class Instance(
        val spec: AccessorySpec,
        val asset: FilamentAsset,
        var visible: Boolean = true,
    )

    private val instances = LinkedHashMap<String, Instance>()

    private val _accessories = MutableStateFlow<List<AccessoryState>>(emptyList())
    override val accessories: StateFlow<List<AccessoryState>> = _accessories.asStateFlow()

    override fun attach(spec: AccessorySpec): Result<Unit> {
        if (instances.containsKey(spec.id)) {
            return Result.failure(IllegalStateException("$ownerTag accessory '${spec.id}' already attached"))
        }
        val loader = stage.avatarAssetLoader
            ?: return Result.failure(IllegalStateException("stage not attached"))
        val resources = stage.accessoryResourceLoader
            ?: return Result.failure(IllegalStateException("stage not attached"))

        // ①② 字节读取 + 预处理（白嫖 unlit 剥除 / morph 注入；非 VRM → PBR 兜底）
        val sourcePath = when (val s = spec.source) {
            is AccessorySource.Asset -> s.path
            is AccessorySource.File -> s.path
        }
        val prepared = try {
            ModelPreprocessor.process(
                stage.readSourceBytes(sourcePath),
                requestedMode = RenderMode.PBR,
                autoRenderMode = false,
            )
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("accessory bytes failed: $sourcePath", e))
        }

        // ③ createAsset + 同步资源（小文件毫秒级；异步化排 P1）
        val asset = loader.createAsset(prepared.finalBuffer)
            ?: return Result.failure(IllegalStateException("createAsset failed: $sourcePath"))
        resources.loadResources(asset)
        asset.releaseSourceData()

        // ④⑤ 挂点解析 + re-parent（骨骼局部系挂在人体层级内）
        val boneEntity = resolveBone(spec.bone)
        if (boneEntity == 0) {
            loader.destroyAsset(asset)
            return Result.failure(IllegalArgumentException("$ownerTag bone '${spec.bone}' not resolved"))
        }
        val tm = stage.engine.transformManager
        val propInstance = tm.getInstance(asset.root)
        val boneInstance = tm.getInstance(boneEntity)
        if (propInstance == 0 || boneInstance == 0) {
            loader.destroyAsset(asset)
            return Result.failure(IllegalStateException("$ownerTag transform missing (prop=$propInstance bone=$boneInstance)"))
        }
        tm.setParent(propInstance, boneInstance)

        // ⑥ 局部 TRS（骨骼局部单位；unit-cube 根缩放经父链传播，不再乘 renderScale）
        tm.setTransform(
            propInstance,
            AccessoryMath.localTransform(spec.position, spec.rotationEulerYXZ, spec.scale),
        )

        // 阴影开关沿 spec（PCSS Adreno 悬崖默认关）；剔除沿 avatar 惯例关
        val rm = stage.engine.renderableManager
        for (entity in asset.renderableEntities) {
            val inst = rm.getInstance(entity)
            if (inst == 0) continue
            rm.setCastShadows(inst, spec.castShadows)
            rm.setCulling(inst, false)
        }

        // ⑦ 入场景 + 登记（先落位再进场，避免首帧闪现在原点）
        stage.scene.addEntities(asset.entities)
        instances[spec.id] = Instance(spec, asset)
        publish()
        Log.i(TAG, "$ownerTag attach '${spec.id}' → ${spec.bone} ($sourcePath, pos=${spec.position.contentToString()}, rot=${spec.rotationEulerYXZ.contentToString()}, scale=${spec.scale})")
        return Result.success(Unit)
    }

    override fun detach(id: String): Boolean {
        val instance = instances.remove(id) ?: return false
        // SIGABRT 纪律（对齐 AvatarRuntime.destroyAsset）：先摘 scene 再毁 asset
        stage.scene.removeEntities(instance.asset.entities)
        val loader = stage.avatarAssetLoader
        if (loader != null) {
            loader.destroyAsset(instance.asset)
        } else {
            Log.w(TAG, "$ownerTag accessory '$id': asset loader gone; leaking entities")
        }
        publish()
        Log.i(TAG, "$ownerTag detach '$id'")
        return true
    }

    override fun setVisible(id: String, visible: Boolean): Boolean {
        val instance = instances[id] ?: return false
        if (instance.visible == visible) return true
        instance.visible = visible
        if (visible) {
            stage.scene.addEntities(instance.asset.entities)
        } else {
            stage.scene.removeEntities(instance.asset.entities)
        }
        publish()
        return true
    }

    /** 演员 asset 重建/销毁前调用：骨骼实体即将失效，配饰必须先走。 */
    fun destroyAll() {
        val ids = instances.keys.toList()
        for (id in ids) detach(id)
    }

    private fun publish() {
        _accessories.value = instances.map { (id, inst) ->
            AccessoryState(id = id, bone = inst.spec.bone, visible = inst.visible)
        }
    }

    companion object {
        private const val TAG = "AccessoryManager"
    }
}
