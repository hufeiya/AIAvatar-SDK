package com.neethu.aiavatar_sdk

import com.neethu.orchestrator.gesture.ActionEntry
import java.io.File

/** 待机动作持久化键（demo_settings）；值 = assets 相对路径或 "ext:<绝对路径>"。 */
const val KEY_IDLE_ANIMATION = "ai_idle_animation"

/**
 * LLM 动作目录（docs/ai-layer-handoff.md §7.2/§7.10）。
 *
 * 目录不再硬编码：assets/animations 下的全部 .vrma（内置库，含分类子文件夹，
 * ~330 个）自动生成目录；tag = 文件名转小写下划线并去重，分类名取第一级
 * 子文件夹；外置库文件追加在后。全中文名等转不出 tag 的文件跳过。
 * 协议块按分类分组列出——模型只见过真实存在的 tag，未知名客户端静默丢弃。
 */
fun sanitizeActionTag(name: String): String =
    name.lowercase()
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

/**
 * 分类在协议块里的展示顺序：对话高频类（打招呼/交流手势/情绪表达）排最前，
 * 大动作量的舞蹈/坐姿靠后，根目录遗留与外置库垫底。
 */
private val CATEGORY_ORDER = listOf(
    "03_打招呼与礼仪",
    "04_交流手势",
    "05_情绪表达",
    "01_待机与站姿",
    "07_日常互动",
    "09_状态过渡",
    "10_跳跃",
    "08_舞蹈与表演",
    "06_坐姿",
    "基础动作",
    "外置库",
)

private fun categoryRank(category: String): Int =
    CATEGORY_ORDER.indexOf(category).takeIf { it >= 0 } ?: CATEGORY_ORDER.size

fun buildLlmActionCatalog(
    assetRelativePaths: List<String>,
    externalAbsolutePaths: List<String> = emptyList(),
): List<ActionEntry> {
    val entries = ArrayList<ActionEntry>()
    val seen = HashSet<String>()
    fun add(fileName: String, category: String, assetPath: String?, filePath: String?) {
        val tag = sanitizeActionTag(fileName.removeSuffix(".vrma"))
        if (tag.isEmpty() || !seen.add(tag)) return
        entries += ActionEntry(tag, fileName.removeSuffix(".vrma"), category, assetPath, filePath)
    }
    for (rel in assetRelativePaths) {
        val category = if ('/' in rel) rel.substringBefore('/') else "基础动作"
        add(rel.substringAfterLast('/'), category, assetPath = "animations/$rel", filePath = null)
    }
    for (abs in externalAbsolutePaths) {
        add(abs.substringAfterLast('/'), "外置库", assetPath = null, filePath = abs)
    }
    // 协议块 groupBy 保持遭遇序——按分类价值排序后注入
    return entries.sortedWith(compareBy({ categoryRank(it.category) }, { it.tag }))
}

/** 内置待机优先级：中性站立 idle 优先于情绪化/倚靠姿态（§7.10）。 */
private val IDLE_PREFERENCE = listOf(
    "Idle Stand Looking Around",
    "Standing Idle",
    "Female Idle",
    "Male Idle",
    "Idle",
)

/** 由一个具体路径构造待机条目（面板长按 / ai_cmd set_idle 用）。 */
fun idleEntryFor(relativePath: String, external: Boolean, externalRoot: File?): ActionEntry? {
    val fileName = relativePath.substringAfterLast('/').removeSuffix(".vrma")
    val tag = sanitizeActionTag(fileName)
    if (tag.isEmpty()) return null
    return if (external) {
        val root = externalRoot ?: return null
        ActionEntry(tag, fileName, "待机", filePath = File(root, relativePath).path)
    } else {
        ActionEntry(tag, fileName, "待机", assetPath = "animations/$relativePath")
    }
}

/** 待机条目对应的持久化值（[KEY_IDLE_ANIMATION]）。 */
fun idlePrefValueFor(relativePath: String, external: Boolean, externalRoot: File?): String? =
    if (external) {
        externalRoot?.let { "ext:" + File(it, relativePath).path }
    } else {
        relativePath
    }

/**
 * Resolve the idle entry: persisted value wins（跨重启保持用户选择），
 * 否则按 [IDLE_PREFERENCE] 在内置库里挑第一个命中的中性 idle，
 * 再否则任选一个文件名含 "idle" 的；都没有 → null（回落 rest pose）。
 */
fun resolveIdleAction(persisted: String?, assetPaths: List<String>): ActionEntry? {
    persisted?.takeIf { it.isNotBlank() }?.let { p ->
        if (p.startsWith("ext:")) {
            // ext: 值是绝对路径（见 idlePrefValueFor），直接用
            val abs = p.removePrefix("ext:")
            val fileName = abs.substringAfterLast('/').removeSuffix(".vrma")
            val tag = sanitizeActionTag(fileName)
            if (tag.isEmpty()) return null
            return ActionEntry(tag, fileName, "待机", filePath = abs)
        }
        return idleEntryFor(p, external = false, externalRoot = null)
    }
    val idleFiles = assetPaths.filter { it.substringAfterLast('/').contains("idle", ignoreCase = true) }
    val chosen = IDLE_PREFERENCE.firstNotNullOfOrNull { key ->
        idleFiles.firstOrNull {
            it.substringAfterLast('/').removeSuffix(".vrma").equals(key, ignoreCase = true)
        }
    } ?: idleFiles.firstOrNull() ?: return null
    return idleEntryFor(chosen, external = false, externalRoot = null)
}
