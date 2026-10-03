package com.neethu.aiavatar_sdk

import android.content.Context
import com.neethu.orchestrator.gesture.ActionEntry
import java.io.File

/**
 * LLM 动作目录（docs/ai-layer-handoff.md §7.2）。
 *
 * 内置 = APK assets 里的策展对话手势（上半身手势、首尾接近 rest pose——
 * 非循环播完是瞬时归位，别把结尾姿态夸张的舞蹈/运动动画加进来）。
 * 外置动画库开启时按文件名关键词匹配外置 .vrma，无匹配回落内置
 * （assets 始终在包内，手动面板隐藏它们不影响加载）。
 */
private data class CuratedGesture(
    val tag: String,
    val label: String,
    val assetFile: String,
    val keywords: List<String>,
)

private val CURATED_GESTURES = listOf(
    CuratedGesture("wave", "挥手问候", "Greeting While Standing.vrma", listOf("greeting", "wave", "hello")),
    CuratedGesture("nod", "点头认可", "Acknowledging Gesture.vrma", listOf("acknowledg", "nod")),
    CuratedGesture("thank", "致谢", "Being Thankful While Standing.vrma", listOf("thank")),
    CuratedGesture("celebrate", "庆祝", "Celebrating After A Win.vrma", listOf("celebrat")),
    CuratedGesture("dismiss", "摆手否定", "Dismissing With Back Hand.vrma", listOf("dismiss", "refuse", "reject")),
    CuratedGesture("salute", "敬礼", "Formal Military Salute.vrma", listOf("salute")),
)

fun buildLlmActionCatalog(context: Context, useExternal: Boolean): List<ActionEntry> {
    val root = context.getExternalFilesDir(null)
    val externalFiles: List<String> =
        if (useExternal && root != null) {
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("vrma", ignoreCase = true) }
                .map { it.relativeTo(root).path }
                .sorted()
                .toList()
        } else {
            emptyList()
        }
    return CURATED_GESTURES.map { gesture ->
        val match = externalFiles.firstOrNull { file ->
            val name = file.lowercase()
            gesture.keywords.any { name.contains(it) }
        }
        if (match != null && root != null) {
            ActionEntry(gesture.tag, gesture.label, filePath = File(root, match).path)
        } else {
            ActionEntry(gesture.tag, gesture.label, assetPath = "animations/${gesture.assetFile}")
        }
    }
}
