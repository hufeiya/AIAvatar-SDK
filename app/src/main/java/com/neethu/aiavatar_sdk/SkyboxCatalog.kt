package com.neethu.aiavatar_sdk

import android.content.Context

/**
 * assets/skybox 全景壁纸目录（360° equirect，JPG/PNG/WebP，宽高比 ≈ 2:1）。
 *
 * 三方共用：ai_cmd `skybox`（AiDebugExecutor）、DemoUiState.skyboxFiles、
 * LLM `set_skybox` 工具（AgentSkyboxTools）。id = 去扩展名的文件名（如
 * `pexels-amar-35483494`）；[resolve] 支持带/不带扩展名、大小写不敏感。
 */
object SkyboxCatalog {

    private val IMAGE_EXTS = listOf(".jpg", ".jpeg", ".png", ".webp")

    /** 目录内全部全景文件名（排序稳定，ai_cmd list / DemoUiState 用）。 */
    fun files(context: Context): List<String> = try {
        context.assets.list("skybox")
            ?.filter { name -> IMAGE_EXTS.any { name.endsWith(it, ignoreCase = true) } }
            ?.sorted()
            ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** 工具枚举用的 id 列表（文件名去扩展名，与 LLM schema 对齐）。 */
    fun ids(context: Context): List<String> = files(context).map { it.substringBeforeLast('.') }

    /** id 或文件名（大小写不敏感、扩展名可省）→ 目录内文件名；查无返回 null。 */
    fun resolve(context: Context, idOrFile: String): String? {
        val files = files(context)
        return files.firstOrNull { it.equals(idOrFile, ignoreCase = true) }
            ?: files.firstOrNull {
                it.substringBeforeLast('.').equals(idOrFile, ignoreCase = true)
            }
    }
}
