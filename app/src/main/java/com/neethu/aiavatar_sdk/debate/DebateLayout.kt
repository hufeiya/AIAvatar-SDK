package com.neethu.aiavatar_sdk.debate

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 辩论专用布局（docs/debate-feasibility.md §7）：座位锚点、椅子道具、动作
 * 映射、麦克风逐模型校准。与规则包分离；`scene.layout` 引用文件名
 * （应用辩论目录内相对路径，或 APK assets 内建布局）。
 */
@Serializable
data class DebateLayoutDto(
    val schemaVersion: Int = 1,
    val layoutId: String,
    val scene: SceneRef = SceneRef(),
    val seats: Map<String, Seat> = emptyMap(),
    val chair: Chair = Chair(),
    val animations: Animations = Animations(),
    val mic: Mic = Mic(),
) {
    @Serializable
    data class SceneRef(val asset: String = "scene/home.glb")

    @Serializable
    data class Seat(
        val x: Float = 0f,
        val y: Float = 0f,
        val z: Float = 0f,
        val yawDeg: Float = 0f,
        /** 座面相对椅子道具的微调（米；坐姿髋高逐模型校准的落点）。 */
        val seatHeightOverride: Float? = null,
    )

    @Serializable
    data class Chair(
        val asset: String = "debate/chair.glb",
        val enabled: Boolean = false,
        val offsetY: Float = 0f,
        val offsetZ: Float = 0f,
        val yawDeg: Float = 0f,
        val scale: Float = 1f,
    )

    @Serializable
    data class Animations(
        val seatedIdle: String = "animations/06_坐姿/Seated Idle With Hands On A Table.vrma",
        val seatedTalk: String = "animations/04_交流手势/Sitting And Talking To Another Person.vrma",
        val standingIdle: String = "animations/01_待机与站姿/Arms Down.vrma",
        val standingTalk: String = "animations/04_交流手势/General Conversation.vrma",
        val standUp: String = "animations/06_坐姿/Standing Up From Seated Position.vrma",
        val sitDown: String = "animations/06_坐姿/Standing To Sitting Transition.vrma",
    )

    @Serializable
    data class Mic(
        val asset: String = "accessories/microphone.glb",
        val bone: String = "rightHand",
        val position: List<Float> = listOf(0f, 0.05f, 0.02f),
        val rotationEulerYXZ: List<Float> = listOf(90f, 0f, 0f),
        val scale: Float = 1f,
        /** 逐模型校准（key = 配置的 model 字段原文），覆盖默认 position/rotation。 */
        val perModel: Map<String, PerModel> = emptyMap(),
    ) {
        @Serializable
        data class PerModel(
            val position: List<Float>? = null,
            val rotationEulerYXZ: List<Float>? = null,
            val scale: Float? = null,
        )
    }
}

/** 辩论文件 IO：全部限定在 filesDir/debate 根内，拒绝绝对路径与路径穿越。 */
object DebateFiles {
    const val TAG = "DebateControl"
    const val ROOT_DIR = "debate"
    const val CONTROL_DIR = "control"
    const val EXPORT_DIR = "export"
    private const val REQUEST_ID_REGEX = "^[A-Za-z0-9._-]{1,64}$"

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun root(context: Context): File = File(context.filesDir, ROOT_DIR).apply { mkdirs() }

    fun controlDir(context: Context): File = File(root(context), CONTROL_DIR).apply { mkdirs() }

    fun exportDir(context: Context): File = File(root(context), EXPORT_DIR).apply { mkdirs() }

    /** 校验并解析辩论目录内的相对路径；null = 非法（穿越/绝对路径/空）。 */
    fun safeRelative(context: Context, relative: String): File? {
        val trimmed = relative.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/") || trimmed.contains("\\\\")) return null
        if (trimmed.split('/').any { it == ".." || it.isBlank() && trimmed.contains("//") }) return null
        val file = File(root(context), trimmed)
        val rootPath = root(context).canonicalPath + File.separator
        if (!file.canonicalPath.startsWith(rootPath)) return null
        return file
    }

    /** request_id 白名单校验；非法时生成替代 id（结果文件名安全第一）。 */
    fun sanitizeRequestId(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (Regex(REQUEST_ID_REGEX).matches(trimmed)) trimmed else "req-${System.currentTimeMillis()}"
    }

    /**
     * 读取辩论配置/布局文件：优先应用辩论目录（run-as 可写、可覆盖），缺失时
     * 回退 APK assets 内建文件（并把内建拷入目录，便于后续 run-as 覆盖调参）。
     */
    fun readConfigText(context: Context, relative: String): String? {
        val file = safeRelative(context, relative)
        if (file != null && file.isFile) return file.readText()
        return runCatching {
            val text = context.assets.open("$ROOT_DIR/$relative").use { it.readBytes().decodeToString() }
            if (file != null) {
                file.parentFile?.mkdirs()
                file.writeText(text)
                Log.i(TAG, "内建配置已引导复制到应用目录: $relative")
            }
            text
        }.onFailure { Log.w(TAG, "读取配置失败: $relative (${it.message})") }.getOrNull()
    }

    fun parseLayout(text: String): DebateLayoutDto = json.decodeFromString(DebateLayoutDto.serializer(), text)

    /** 写结果文件（失败只记日志，不阻塞命令流）。 */
    fun writeResult(context: Context, requestId: String, payload: String) {
        runCatching {
            File(controlDir(context), "$requestId.json").writeText(payload)
        }.onFailure { Log.w(TAG, "结果文件写入失败: $requestId (${it.message})") }
    }
}
