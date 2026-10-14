package com.neethu.aiavatar_sdk.multiavatar

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 外置配饰库（P1 docs/accessory-feasibility.md §7-P1.3）：GLB 经 adb 推送
 * + `acc import <路径>` 落盘 `filesDir/accessories/`，与内置资产同列表、
 * 同挂载管线（AccessorySource.File 通道）。工作流对齐单人路径的
 * import_model（push 文件到 app 外部目录 → 命令导入），导入防撞/净化同
 * [com.neethu.aiavatar_sdk.ImportedModelLibrary] 惯例。
 *
 * 配饰 ID = 文件名去后缀（`cup.glb` → `cup`）；与内置资产重名时内置优先
 * （导入名防撞时避开 assets/accessories 的内置文件名）。
 */
internal object ImportedAccessoryLibrary {

    private const val TAG = "AccessoryCatalog"

    /** 导入落盘目录（校准 preset 的 calibration/ 子目录靠 isFile 过滤）。 */
    fun dir(context: Context): File = File(context.filesDir, "accessories")

    /** 已导入配饰文件（排序；.glb only）。 */
    fun list(context: Context): List<String> =
        dir(context).listFiles { f -> f.isFile }?.map { it.name }
            ?.filter { it.lowercase().endsWith(".glb") }
            ?.sorted() ?: emptyList()

    fun file(context: Context, id: String): File? {
        val stem = id.substringAfterLast('/')
        return File(dir(context), "$stem.glb").takeIf { it.isFile }
            ?: File(dir(context), stem).takeIf { it.isFile }
    }

    /** 字节级导入（GLB magic 校验 → 净化 → 防撞 → 落盘）；null = 不可导入。 */
    fun importBytes(context: Context, bytes: ByteArray, suggestedName: String?): String? {
        if (!isValidGlb(bytes)) return null
        val base = sanitizeName(suggestedName) ?: return null
        val name = resolveUniqueName(dir(context), builtinNames(context), base)
        return try {
            dir(context).mkdirs()
            File(dir(context), name).writeBytes(bytes)
            name
        } catch (e: Exception) {
            Log.w(TAG, "accessory import failed: ${e.message}")
            null
        }
    }

    /** 文件导入（`acc import <路径>`；路径解析归调用方）。 */
    fun importFile(context: Context, source: File): String? =
        importBytes(context, source.readBytes(), source.name)

    // ── 纯函数 ──────────────────────────────────────────────────────────

    /** GLB magic：前 4 字节 "glTF"（同 ImportedModelLibrary）。 */
    fun isValidGlb(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 0x67.toByte() && bytes[1] == 0x6C.toByte() &&
            bytes[2] == 0x54.toByte() && bytes[3] == 0x46.toByte()

    /** 净化导入名：剥路径、非法字符换下划线、保证 .glb 后缀。null = 剥完为空。 */
    fun sanitizeName(raw: String?): String? {
        val stripped = (raw ?: "")
            .substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .trim()
        if (stripped.isEmpty() || stripped == "." || stripped == "..") return null
        val lower = stripped.lowercase()
        return if (lower.endsWith(".glb")) stripped else "$stripped.glb"
    }

    /** 防撞：内置名或现有文件占用时依次 `stem_1.glb`…。 */
    fun resolveUniqueName(dir: File, takenNames: Set<String>, desired: String): String {
        fun free(n: String) = n !in takenNames && !File(dir, n).exists()
        if (free(desired)) return desired
        val stem = desired.substringBeforeLast('.')
        var i = 1
        while (true) {
            val candidate = "${stem}_$i.glb"
            if (free(candidate)) return candidate
            i++
        }
    }

    private fun builtinNames(context: Context): Set<String> = try {
        (context.assets.list("accessories") ?: emptyArray())
            .filter { it.lowercase().endsWith(".glb") }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }
}
