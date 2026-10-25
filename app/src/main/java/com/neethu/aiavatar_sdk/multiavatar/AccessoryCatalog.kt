package com.neethu.aiavatar_sdk.multiavatar

import android.content.Context
import com.neethu.corelib.accessory.AccessorySource
import com.neethu.corelib.accessory.AccessorySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 内置配饰目录（P1 docs/accessory-feasibility.md §7）：`assets/accessories/
 * accessory.json` sidecar 目录为清单（id/挂点/出厂默认偏移/许可证），
 * [AccessoryCalibrationStore] 的 per-model preset 叠加在其上（只覆盖标定过
 * 的维度），命令行显式参数再叠加在 preset 上——三层合并，越靠外优先级越高。
 *
 * P1 道具系统 §6.4 插入**模板层**：sidecar 条目可带 `offsetProfile` 引用
 * 「挂点×道具形状类别」模板（[AccessoryOffsetProfiles]），此时条目出厂值由
 * 模板出厂默认接管，其上再叠**模板级** per-model 校准（每模板×每模型只校准
 * 一次，90 次→16 次），然后才是条目级 preset 与显式参数——五层见
 * [AccessoryOffsetProfiles]。
 *
 * 外置导入（[ImportedAccessoryLibrary]，filesDir/accessories 下的 .glb）以
 * 文件名 stem 为 id 自动进目录，默认挂 rightHand/scale=1（外置资产没有
 * sidecar，走 `acc attach` 参数链路微调 + `acc cal` 落 preset）。
 *
 * 只收 CC0（许可证记录见 assets/accessories/LICENSES.md）。
 */
internal object AccessoryCatalog {

    @Serializable
    data class SidecarEntry(
        val id: String,
        val name: String = id,
        val file: String,
        val license: String = "CC0",
        val defaultBone: String = "rightHand",
        val position: List<Float>? = null,
        val rotationEulerYXZ: List<Float>? = null,
        val scale: Float = 1f,
        /** 「挂点×道具形状类别」模板（§6.4）：引用后出厂偏移由模板接管。 */
        val offsetProfile: String? = null,
    )

    @Serializable
    private data class SidecarFile(val accessories: List<SidecarEntry>)

    @Serializable
    data class BindingEntry(val animation: String, val accessory: String, val note: String? = null)

    /** 目录条目：sidecar 默认或导入资产的静态面（不含 per-model 校准）。 */
    data class Entry(
        val id: String,
        val name: String,
        val license: String,
        val source: AccessorySource,
        val defaultBone: String,
        val position: FloatArray,
        val rotationEulerYXZ: FloatArray,
        val scale: Float,
        /** 「挂点×道具形状类别」模板引用（§6.4）；null = 既有逐件默认路径。 */
        val offsetProfile: String? = null,
        /** 来自 per-model 校准 preset（attach 时合并，list 里标注）。 */
        val calibratedFor: Set<String> = emptySet(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** sidecar 全量（每次现读：assets 只读小文件，且测试/导入后即时可见）。 */
    fun builtin(context: Context): List<Entry> = try {
        val text = context.assets.open("accessories/accessory.json")
            .use { it.readBytes().toString(Charsets.UTF_8) }
        json.decodeFromString<SidecarFile>(text).accessories.map { e ->
            Entry(
                id = e.id,
                name = e.name,
                license = e.license,
                source = AccessorySource.Asset("accessories/${e.file}"),
                defaultBone = e.defaultBone,
                position = (e.position ?: listOf(0f, 0f, 0f)).toFloatArray(),
                rotationEulerYXZ = (e.rotationEulerYXZ ?: listOf(0f, 0f, 0f)).toFloatArray(),
                scale = e.scale,
                offsetProfile = e.offsetProfile,
            )
        }
    } catch (e: Exception) {
        android.util.Log.w("AccessoryCatalog", "sidecar parse failed: ${e.message}")
        emptyList()
    }

    /** 全目录 = 内置 + 外置导入（导入无 sidecar，rightHand 出厂默认）。 */
    fun all(context: Context): List<Entry> =
        builtin(context) + ImportedAccessoryLibrary.list(context).map { fileName ->
            val id = fileName.removeSuffix(".glb")
            Entry(
                id = id,
                name = id,
                license = "外置导入(许可证自担)",
                source = AccessorySource.File(
                    java.io.File(ImportedAccessoryLibrary.dir(context), fileName).absolutePath,
                ),
                defaultBone = "rightHand",
                position = floatArrayOf(0f, 0.02f, 0.03f),
                rotationEulerYXZ = floatArrayOf(0f, 0f, 0f),
                scale = 1f,
            )
        }

    fun find(context: Context, id: String): Entry? =
        all(context).firstOrNull { it.id.equals(id, ignoreCase = true) }

    /**
     * 三层合并出挂载规格（§6.4 后为「条目默认 → 模板层 → 校准 preset → 显式
     * 参数」）：sidecar/导入默认（引用模板时由模板出厂值接管）→ 模板级
     * per-model 校准 → 条目级 per-model 校准（只覆盖标定维度）→ 命令行显式
     * 参数（boneOverride/偏移非空才生效）。无模板条目与既有行为逐位一致。
     * [modelId] = 模型文件名（[com.neethu.corelib.stage.AvatarSpec.displayName]）。
     */
    fun specFor(
        context: Context,
        modelId: String,
        entry: Entry,
        boneOverride: String? = null,
        positionOverride: FloatArray? = null,
        rotationOverride: FloatArray? = null,
        scaleOverride: Float? = null,
    ): AccessorySpec {
        val preset = AccessoryCalibrationStore.load(context, modelId, entry.id)
        val profilePreset = entry.offsetProfile
            ?.let { AccessoryCalibrationStore.loadProfiles(context, modelId)[it] }
        val merged = AccessoryOffsetProfiles.resolve(
            entryDefault = AccessoryOffsetProfiles.Mount(
                bone = entry.defaultBone,
                position = entry.position,
                rotationEulerYXZ = entry.rotationEulerYXZ,
                scale = entry.scale,
            ),
            profileName = entry.offsetProfile,
            profilePreset = profilePreset,
            accessoryPreset = preset,
        )
        val bone = boneOverride ?: merged.bone
        val pos = positionOverride ?: merged.position
        val rot = rotationOverride ?: merged.rotationEulerYXZ
        val scale = scaleOverride ?: merged.scale
        return AccessorySpec(
            id = entry.id,
            source = entry.source,
            bone = bone,
            position = pos,
            rotationEulerYXZ = rot,
            scale = scale,
        )
    }

    /** 动画绑定表（assets/accessories/bindings.json；解析失败 = 空表不崩）。 */
    fun loadBindings(context: Context): List<BindingEntry> = try {
        val text = context.assets.open("accessories/bindings.json")
            .use { it.readBytes().toString(Charsets.UTF_8) }
        json.decodeFromString<List<BindingEntry>>(text)
    } catch (e: Exception) {
        android.util.Log.w("AccessoryCatalog", "bindings parse failed: ${e.message}")
        emptyList()
    }
}
