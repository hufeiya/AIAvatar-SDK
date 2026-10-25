package com.neethu.aiavatar_sdk.multiavatar

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * per-model 配饰校准 preset（docs/accessory-feasibility.md §5.3 P1）：
 * `filesDir/accessories/calibration/<modelId>/<accessoryId>.json`，
 * 真机 `acc cal` 落盘（「真机标定→落盘」方法论，同 home.anchors.json /
 * walk-calib）。加载时 [com.neethu.aiavatar_sdk.multiavatar.AccessoryCatalog]
 * 把 preset 合并进 sidecar 默认值。
 *
 * modelId = 模型文件名（AvatarRuntime.modelKey，如 `10.vrm`）；同款配饰在
 * VRM0.x 与 VRM1 模型的骨骼局部系朝向可能差 180° Y（P0 实测 4705 vs 10），
 * preset 逐模型一份。
 *
 * 所有字段可空：只覆盖标定过的维度，其余沿用 sidecar 默认。
 */
internal object AccessoryCalibrationStore {

    @Serializable
    data class Preset(
        val bone: String? = null,
        val position: List<Float>? = null,
        val rotationEulerYXZ: List<Float>? = null,
        val scale: Float? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun dir(context: Context): File = File(context.filesDir, "accessories/calibration")

    fun file(context: Context, modelId: String, accessoryId: String): File =
        File(File(dir(context), sanitizeModelId(modelId)), "${sanitizeId(accessoryId)}.json")

    fun save(context: Context, modelId: String, accessoryId: String, preset: Preset): Result<Unit> =
        runCatching {
            val f = file(context, modelId, accessoryId)
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(Preset.serializer(), preset))
        }

    fun load(context: Context, modelId: String, accessoryId: String): Preset? = try {
        val f = file(context, modelId, accessoryId)
        if (f.isFile) json.decodeFromString(Preset.serializer(), f.readText()) else null
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context, modelId: String, accessoryId: String): Boolean =
        file(context, modelId, accessoryId).delete()

    // ── 模板级 preset（P1 §6.4：每模板×每模型只校准一次）──────────────────
    // 单文件集中存（<modelId>/offsetProfiles.json），与条目级 <accessoryId>.json
    // 文件名空间天然隔离；schema 同 [Preset]，键 = 模板名（holdCup 等）。

    @Serializable
    data class ProfilePresets(val profiles: Map<String, Preset> = emptyMap())

    private fun profilesFile(context: Context, modelId: String): File =
        File(File(dir(context), sanitizeModelId(modelId)), "offsetProfiles.json")

    fun loadProfiles(context: Context, modelId: String): Map<String, Preset> = try {
        val f = profilesFile(context, modelId)
        if (f.isFile) {
            json.decodeFromString(ProfilePresets.serializer(), f.readText()).profiles
        } else {
            emptyMap()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    fun saveProfile(
        context: Context,
        modelId: String,
        profileName: String,
        preset: Preset,
    ): Result<Unit> = runCatching {
        val f = profilesFile(context, modelId)
        f.parentFile?.mkdirs()
        val merged = loadProfiles(context, modelId) + (profileName to preset)
        f.writeText(json.encodeToString(ProfilePresets.serializer(), ProfilePresets(merged)))
    }

    fun clearProfile(context: Context, modelId: String, profileName: String): Boolean =
        runCatching {
            val f = profilesFile(context, modelId)
            if (!f.isFile) return@runCatching false
            val merged = loadProfiles(context, modelId) - profileName
            if (merged.isEmpty()) f.delete() else f.writeText(
                json.encodeToString(ProfilePresets.serializer(), ProfilePresets(merged))
            )
            true
        }.getOrDefault(false)

    // ── 纯函数（JVM 单测直测）────────────────────────────────────────────

    /** modelId 是路径段：模型文件名里的非法字符（空格/中文/斜杠）压成下划线。 */
    fun sanitizeModelId(raw: String): String =
        sanitizeId(raw).ifEmpty { "model" }

    /** accessoryId 同规则（导入文件名可能带任意字符）。 */
    fun sanitizeId(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').take(64)
}
