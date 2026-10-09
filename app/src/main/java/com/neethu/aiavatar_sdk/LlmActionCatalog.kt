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
 * 子文件夹；外置库文件追加在后。全中文名等转不出 tag 的文件跳过——除非在
 * [CHINESE_NAME_TAGS] 里登记了英文别名。
 * 协议块按分类分组列出——模型只见过真实存在的 tag，未知名客户端静默丢弃。
 */
fun sanitizeActionTag(name: String): String =
    name.lowercase()
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

/**
 * 纯中文文件名 → 英文 tag 别名：sanitizeActionTag 的正则只留 a-z0-9，全中文名
 * 转出空 tag 会被目录跳过；在此登记后文件照常进 LLM 动作目录。tag 必须是
 * 语义化英文（协议约定「动作英文名即其含义」，且 <act:> 提取正则只认 ASCII）；
 * 面板显示名不受影响（仍用原文件名）。未登记的中文名照旧跳过。
 */
private val CHINESE_NAME_TAGS = mapOf(
    "闪身步" to "dodge_step",
    "浪子踢球" to "ball_kick",
)

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
        val base = fileName.removeSuffix(".vrma")
        val tag = sanitizeActionTag(base).ifEmpty { CHINESE_NAME_TAGS[base] ?: "" }
        if (tag.isEmpty() || !seen.add(tag)) return
        entries += ActionEntry(tag, base, category, assetPath, filePath)
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

/**
 * 内置待机优先级（§7.10）：`Arms Down` 是单帧静态站姿（0.042s 单帧，循环即
 * 恒定垂臂站立），最接近"数字人安静站着"的预期故排最前；其余为带微动作的
 * 中性 idle。用户长按/ai_cmd 另选时持久化值优先于此表。
 */
private val IDLE_PREFERENCE = listOf(
    "Arms Down",
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
 * 否则按 [IDLE_PREFERENCE] 在内置全量库里按文件名精确挑第一个命中的
 * （注意 "Arms Down" 文件名不含 "idle"，不能先按名字过滤候选集），
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
    val chosen = IDLE_PREFERENCE.firstNotNullOfOrNull { key ->
        assetPaths.firstOrNull {
            it.substringAfterLast('/').removeSuffix(".vrma").equals(key, ignoreCase = true)
        }
    } ?: assetPaths.firstOrNull {
        it.substringAfterLast('/').contains("idle", ignoreCase = true)
    } ?: return null
    return idleEntryFor(chosen, external = false, externalRoot = null)
}
