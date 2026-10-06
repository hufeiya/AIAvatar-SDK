package com.neethu.aiavatar_sdk

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Demo 侧的导入模型库：外部 VRM/GLB 经 SAF（或 adb 字节入口）导入后落在
 * 应用 data 文件夹 `filesDir/vrms`，与 APK 内置模型（assets/vrms）同列表、
 * 同加载体验——渲染层 `loadModelFromFile` 与 `loadModel` 共用同一字节管线，
 * 导入模型除来源外与内置模型完全一致。
 *
 * 约定（与 [CardLibrary] 的差异）：目录扫描即列表来源，**不建 prefs 索引**——
 * 文件被手动删掉也只是从列表消失，不会卡死 UI；选中态归 DemoUiState 的
 * `selected_model` 持久化，失效时回落默认模型。
 *
 * 导入防撞：新落盘的名字与已导入文件、内置 assets/vrms 都不重名（重名追加
 * `_1`/`_2` 序号），导入模型绝不遮蔽内置模型。
 */
internal object ImportedModelLibrary {

    /** 导入模型的落盘目录（应用 data 文件夹）。 */
    fun dir(context: Context): File = File(context.filesDir, "vrms")

    /** 已导入模型文件名列表（排序）。 */
    fun list(context: Context): List<String> = listNames(dir(context))

    /** 按文件名取已导入模型文件；null = 该名字不是已导入模型。 */
    fun modelFile(context: Context, name: String): File? =
        File(dir(context), name).takeIf { it.isFile }

    /** 从 SAF Uri 导入：读 bytes → [importBytes]。null = 不可读或不是 GLB。 */
    fun import(context: Context, uri: Uri): String? = try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return null
        importBytes(context, bytes, displayName(context, uri))
    } catch (_: Exception) {
        null
    }

    /**
     * 字节级导入（UI 与 adb 共用同一条落盘路径）：GLB 校验 → 文件名净化 →
     * 防撞 → 落盘。返回落盘文件名；null = 空文件 / 不是 GLB（VRM 即 GLB）。
     */
    fun importBytes(context: Context, bytes: ByteArray, suggestedName: String?): String? {
        if (!isValidGlb(bytes)) return null
        val base = sanitizeModelName(suggestedName) ?: return null
        val name = resolveUniqueName(dir(context), builtinModelNames(context), base)
        return try {
            dir(context).mkdirs()
            File(dir(context), name).writeBytes(bytes)
            name
        } catch (_: Exception) {
            null
        }
    }

    // ── 纯函数（JVM 单测直测，不触 Android）──────────────────────────────

    /**
     * GLB magic 校验：前 4 字节 "glTF"（VRM 1.0/0.x 都是 glTF-binary）。
     * 这里只拦明显不是模型的文件；真正的解析错误由加载期 AvatarState.Error 兜底。
     */
    fun isValidGlb(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 0x67.toByte() && bytes[1] == 0x6C.toByte() &&
            bytes[2] == 0x54.toByte() && bytes[3] == 0x46.toByte()

    /**
     * 净化导入文件名：剥路径取末段、文件系统非法字符（`/\:*?"<>|`）替换为
     * 下划线、保证 .vrm/.glb 后缀（无后缀补 .vrm，列表与加载路径都按扩展名
     * 识别）。中文等合法字符保留原名。null = 剥完为空。
     */
    fun sanitizeModelName(raw: String?): String? {
        val stripped = (raw ?: "")
            .substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .trim()
        if (stripped.isEmpty() || stripped == "." || stripped == "..") return null
        val lower = stripped.lowercase()
        return if (lower.endsWith(".vrm") || lower.endsWith(".glb")) stripped else "$stripped.vrm"
    }

    /**
     * 防撞名：[desired] 已被 [takenNames]（内置模型）或 [dir] 现有文件占用时，
     * 依次尝试 `stem_1.ext`/`stem_2.ext`…。返回原名（未被占用）或首个可用序号名。
     * [desired] 必带扩展名（[sanitizeModelName] 保证）。
     */
    fun resolveUniqueName(dir: File, takenNames: Set<String>, desired: String): String {
        fun free(n: String) = n !in takenNames && !File(dir, n).exists()
        if (free(desired)) return desired
        val stem = desired.substringBeforeLast('.')
        val ext = desired.substringAfterLast('.')
        var i = 1
        while (true) {
            val candidate = "${stem}_$i.$ext"
            if (free(candidate)) return candidate
            i++
        }
    }

    private fun listNames(dir: File): List<String> =
        dir.listFiles { f -> f.isFile }
            ?.map { it.name }
            ?.filter { it.lowercase().endsWith(".vrm") || it.lowercase().endsWith(".glb") }
            ?.sorted()
            ?: emptyList()

    private fun builtinModelNames(context: Context): Set<String> = try {
        (context.assets.list("vrms") ?: emptyArray()).toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /** SAF 文件的原名（content resolver 的 DISPLAY_NAME）；取不到返回 null。 */
    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }
}
