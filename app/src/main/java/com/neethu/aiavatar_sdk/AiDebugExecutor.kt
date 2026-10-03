package com.neethu.aiavatar_sdk

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.neethu.corelib.AvatarController
import com.neethu.corelib.CameraShot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Chat-side hooks the debug executor needs, wired to [DemoScreen]'s AI chat
 * state (same code path as the chat bar's send/interrupt buttons). `send` and
 * `interrupt` return `false` when no session exists yet (AI service
 * unconfigured or the model still loading).
 */
internal class AiChatDebugHooks(
    val send: (String) -> Boolean,
    val interrupt: () -> Boolean,
    /** One-line snapshot of phase / subtitle / error for `chat_state`. */
    val snapshot: () -> String,
    /** Import card bytes through the UI's save/activate path; null = unparseable. */
    val importCard: (ByteArray) -> String? = { null },
    /** One-line snapshot of the active card + system prompt for `active_card`. */
    val cardSnapshot: () -> String = { "AI chat not wired in this screen" },
    /** ai_cmd set_idle：(相对路径, 是否外置) → 设为待机并持久化，返回结果行。 */
    val setIdle: (String, Boolean) -> String = { _, _ -> "AI chat not wired in this screen" },
    /** ai_cmd idle_off：清除待机，回 rest pose。 */
    val clearIdle: () -> String = { "AI chat not wired in this screen" },
    /** ai_cmd contexts：上下文列表快照（含当前）。 */
    val contextsSnapshot: () -> String = { "AI chat not wired in this screen" },
    /** ai_cmd new_context：新建上下文并切换。 */
    val newContext: () -> String = { "AI chat not wired in this screen" },
    /** ai_cmd select_context：按 id 前缀切换上下文；无匹配抛 IllegalStateException。 */
    val selectContext: (String) -> String = { _ ->
        throw IllegalStateException("AI chat not wired in this screen")
    },
    /** ai_cmd transcribe <file>：任意音频文件走与按住说话相同的 ASR 链路（挂起）。 */
    val transcribeFile: suspend (File) -> String = { _ ->
        "voice input not wired in this screen"
    },
    /** ai_cmd voice_record <秒>：MediaRecorder 真录音 N 秒后转写，验证录音链（挂起）。 */
    val voiceRecord: suspend (Int) -> String = { _ ->
        "voice input not wired in this screen"
    },
    /** ai_cmd set_mode manual|text|voice：切换输入模式（MIUI 禁触摸注入，用命令切）。 */
    val setInputMode: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /** ai_cmd show_buttons on|off（无参=翻转）：显隐所有悬浮按钮（切换输入模式外的话题）。 */
    val showButtons: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /** ai_cmd set_provider siliconflow|volcano：切大模型服务商（模型清单随之切换）。 */
    val setProvider: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /** ai_cmd set_tts_provider siliconflow|volcano：TTS 独立服务商切换（自动取消同服务商勾选）。 */
    val setTtsProvider: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /** ai_cmd set_llm_model <清单id>：当前服务商下的大模型切换（清单校验，视频模式验证用）。 */
    val setLlmModel: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /** ai_cmd look_at：视线控制——camera|off / 世界坐标点 / 无参查状态（挂会话内外都可用）。 */
    val lookAt: (String?, Float?, Float?, Float?) -> String = { _, _, _, _ ->
        "AI chat not wired in this screen"
    },
    /** ai_cmd video_camera front|back（无参=翻转）：前后摄切换（视频模式）。 */
    val switchLens: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /** ai_cmd video_snapshot：探测抓拍环形缓存（字节数/清晰度/年龄），不发请求。 */
    val videoSnapshot: () -> String = { "AI chat not wired in this screen" },
    /** state 命令的视频状态增量行（相机/人脸/缓存）。 */
    val videoStatusLine: () -> String? = { null },
    /** ai_cmd voice_free on|off（无参=翻转）：按住说话 ⇄ 自由说话切换。 */
    val setVoiceFree: (String?) -> String = { _ -> "AI chat not wired in this screen" },
)

/**
 * Executes [AiDebugCommand]s on the main thread for [DemoScreen], logging a
 * result line for every command under [AI_LOG_TAG] so that an adb-connected
 * agent can read the outcome with `adb logcat -d -s AIDebug`.
 */
internal suspend fun executeAiCommand(
    context: Context,
    controller: AvatarController,
    uiState: DemoUiState,
    command: AiDebugCommand,
    chat: AiChatDebugHooks? = null,
) {
    val result: String = try {
        when (command.name) {
            "help" -> {
                Log.i(AI_LOG_TAG, aiDebugHelp())
                "help printed to logcat"
            }
            "state" -> {
                val base = describeState(controller, uiState)
                val videoLine = chat?.videoStatusLine()?.takeIf { uiState.inputMode == InputMode.VIDEO }
                if (videoLine != null) "$base\nvideo: $videoLine" else base
            }
            "list" -> listAssets(controller, uiState, command.arg)
            "load_model" -> loadModelCommand(controller, uiState, command.arg)
            "load_scene" -> loadSceneCommand(controller, uiState, command.arg)
            "set_expression" -> setExpressionCommand(controller, uiState, command)
            "clear_expression" -> {
                controller.clearAllExpressions()
                uiState.selectedExpression = null
                "face reset to neutral"
            }
            "play_animation" -> playAnimationCommand(context, controller, uiState, command)
            "stop_animation" -> {
                controller.stopVrmaAnimation()
                uiState.selectedAnimation = null
                "animation stopped"
            }
            "set_idle" -> setIdleCommand(uiState, command, chat)
            "idle_off" -> chat?.clearIdle?.invoke()
                ?: "idle not wired in this screen"
            "move" -> moveCommand(controller, command)
            "zoom" -> zoomCommand(controller, command)
            "pan" -> panCommand(controller, command)
            "orbit" -> orbitCommand(controller, command)
            "reset_camera" -> {
                controller.resetCamera()
                "camera reset to initial pose"
            }
            "camera_shot" -> cameraShotCommand(controller, command.arg)
            "set_drag_mode" -> setDragModeCommand(uiState, command.arg)
            "open_panel" -> openPanelCommand(uiState, command.arg)
            "spring_debug" -> {
                val enabled = when (command.arg?.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> throw IllegalArgumentException("spring_debug expects 'on' or 'off'")
                }
                controller.setSpringBoneDebugLog(enabled)
                if (enabled) "spring bone debug log enabled (logcat tag SpringBone, 1 Hz)"
                else "spring bone debug log disabled"
            }
            "screenshot" -> screenshotCommand(context, controller)
            "send_chat" -> {
                val text = command.arg
                    ?: throw IllegalArgumentException("send_chat expects ai_arg = the message text")
                if (chat?.send(text) != true) {
                    throw IllegalStateException(
                        "no AI chat session — configure the AI service in settings first"
                    )
                }
                "message queued: \"$text\" (reply events stream to AIDebug)"
            }
            "interrupt_chat" -> {
                if (chat?.interrupt() != true) {
                    throw IllegalStateException("no AI chat session to interrupt")
                }
                "interrupt requested"
            }
            "chat_state" -> chat?.snapshot()
                ?: throw IllegalStateException("AI chat not wired in this screen")
            "contexts" -> chat?.contextsSnapshot()
                ?: throw IllegalStateException("AI chat not wired in this screen")
            "new_context" -> chat?.newContext()
                ?: throw IllegalStateException("AI chat not wired in this screen")
            "select_context" -> {
                val prefix = command.arg
                    ?: throw IllegalArgumentException(
                        "select_context expects ai_arg = context id prefix (send ai_cmd contexts to list)"
                    )
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.selectContext(prefix)
            }
            "import_card" -> {
                val arg = command.arg
                    ?: throw IllegalArgumentException(
                        "import_card expects ai_arg = a card file path (absolute, or relative " +
                            "to the app's external files dir; adb push the PNG/JSON there first)"
                    )
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                val file = resolveAppFile(context, arg)
                val result = chat.importCard(file.readBytes())
                    ?: throw IllegalArgumentException(
                        "not a parseable character card: ${file.absolutePath}"
                    )
                result
            }
            "active_card" -> chat?.cardSnapshot()
                ?: throw IllegalStateException("AI chat not wired in this screen")
            "transcribe" -> {
                val arg = command.arg
                    ?: throw IllegalArgumentException(
                        "transcribe expects ai_arg = an audio file path (absolute, or relative " +
                            "to the app's external files dir; adb push it there first)"
                    )
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.transcribeFile(resolveAppFile(context, arg))
            }
            "voice_record" -> {
                val seconds = command.arg?.toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "voice_record expects ai_arg = seconds to record (1..30), e.g. 3"
                    )
                require(seconds in 1..30) { "voice_record seconds must be 1..30, got $seconds" }
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.voiceRecord(seconds)
            }
            "set_mode" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.setInputMode(command.arg)
            }
            "show_buttons" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.showButtons(command.arg)
            }
            "set_provider" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.setProvider(command.arg)
            }
            "set_tts_provider" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.setTtsProvider(command.arg)
            }
            "set_llm_model" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.setLlmModel(command.arg)
            }
            "look_at" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.lookAt(command.arg, command.x, command.y, command.z)
            }
            "video_camera" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.switchLens(command.arg)
            }
            "video_snapshot" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.videoSnapshot()
            }
            "voice_free" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.setVoiceFree(command.arg)
            }
            else -> throw IllegalArgumentException(
                "Unknown command '${command.name}'. Send ai_cmd=help for the command list."
            )
        }
    } catch (e: Exception) {
        Log.e(AI_LOG_TAG, "FAIL ${command.name}: ${e.message}")
        return
    }
    Log.i(AI_LOG_TAG, "OK ${command.name}: $result")
}

// ── Command handlers ─────────────────────────────────────────────────────

private fun describeState(controller: AvatarController, uiState: DemoUiState): String {
    val lookAt = controller.getCameraLookAt()
    return buildString {
        appendLine("state=${controller.state.value}")
        appendLine("model=${uiState.selectedModel}")
        appendLine("scene=${uiState.selectedScene ?: "none"}")
        appendLine("animation=${uiState.selectedAnimation ?: "none"}")
        appendLine("expression=${uiState.selectedExpression ?: "none"}")
        appendLine("dragMode=${uiState.isDragMode}")
        appendLine("fps=${controller.fps.value}")
        appendLine("cameraEye=${lookAt?.first?.toVec() ?: "n/a"}")
        appendLine("cameraTarget=${lookAt?.second?.toVec() ?: "n/a"}")
        appendLine("cameraUp=${lookAt?.third?.toVec() ?: "n/a"}")
        appendLine("cameraShot=${controller.getActiveCameraShot() ?: "none"}")
        appendLine("lookAt=${controller.getLookAtInfo()?.toString() ?: "off"}")
        appendLine("expressions=${DemoUiState.resolveExpressions(controller.state.value)}")
    }.trimEnd()
}

private fun FloatArray.toVec(): String =
    joinToString(prefix = "[", postfix = "]") { String.format(Locale.US, "%.2f", it) }

private fun listAssets(
    controller: AvatarController,
    uiState: DemoUiState,
    arg: String?,
): String = when (arg?.lowercase()) {
    "models", "model" -> "models=${uiState.modelFiles}"
    "scenes", "scene" -> "scenes=${uiState.sceneFiles}"
    "animations", "animation" -> "animations=${uiState.animationFiles}"
    "cards", "card" ->
        if (uiState.cards.isEmpty()) "cards=[]"
        else "cards=" + uiState.cards.joinToString(", ") { entry ->
            "${entry.card.name}(${entry.fileName}${if (entry.fileName == uiState.activeCardFile) ",active" else ""})"
        }
    "expressions", "expression" ->
        "expressions=${DemoUiState.resolveExpressions(controller.state.value)}"
    else -> throw IllegalArgumentException(
        "list expects models|scenes|animations|expressions|cards, got '$arg'"
    )
}

/**
 * Resolve a debug-command file argument (import_card / transcribe): absolute
 * path first, then relative to the app's external files dir (`adb push
 * file.png /sdcard/Android/data/<pkg>/files/` needs no permission). The app
 * itself has no storage permission, so anything under shared storage roots
 * will not read.
 */
private fun resolveAppFile(context: Context, arg: String): File {
    val absolute = File(arg)
    if (absolute.isFile) return absolute
    val external = context.getExternalFilesDir(null)
    if (external != null) {
        val candidate = File(external, arg.removePrefix("/"))
        if (candidate.isFile) return candidate
    }
    throw IllegalArgumentException(
        "no card file at '$arg' (push it to ${external?.absolutePath ?: "<app external files dir>"} " +
            "or pass an absolute path readable by the app)"
    )
}

private fun loadModelCommand(
    controller: AvatarController,
    uiState: DemoUiState,
    arg: String?,
): String {
    val name = resolveAssetFile(arg, uiState.modelFiles, "models", listOf(".glb", ".vrm"))
    if (name == uiState.selectedModel) return "model '$name' is already active"
    uiState.selectedModel = name
    // Matches the UI panel: the animation selection belongs to the old model
    uiState.selectedAnimation = null
    return "loading vrms/$name (expressions/animations reset)"
}

private fun loadSceneCommand(
    controller: AvatarController,
    uiState: DemoUiState,
    arg: String?,
): String {
    if (arg == null || arg.lowercase() in setOf("none", "off", "remove", "clear")) {
        controller.removeScene()
        uiState.selectedScene = null
        return "scene removed"
    }
    val name = resolveAssetFile(arg, uiState.sceneFiles, "scenes", listOf(".glb"))
    if (name == uiState.selectedScene) return "scene '$name' is already active"
    uiState.selectedScene = name
    return "loading scene/$name"
}

private fun setExpressionCommand(
    controller: AvatarController,
    uiState: DemoUiState,
    command: AiDebugCommand,
): String {
    val available = DemoUiState.resolveExpressions(controller.state.value)
    val arg = command.arg
        ?: throw IllegalArgumentException(
            "set_expression expects an expression name. Available: $available"
        )
    val name = available.firstOrNull { it.equals(arg, ignoreCase = true) }
        ?: throw IllegalArgumentException(
            "No expression '$arg' on the current model. Available: $available"
        )
    val weight = command.weight ?: 1.0f
    controller.clearAllExpressions()
    controller.setExpression(name, weight)
    uiState.selectedExpression = name
    return "expression '$name' weight=$weight"
}

private fun playAnimationCommand(
    context: Context,
    controller: AvatarController,
    uiState: DemoUiState,
    command: AiDebugCommand,
): String {
    val name = resolveAssetFile(command.arg, uiState.animationFiles, "animations", listOf(".vrma"))
    if (!uiState.loadAnimation(context, controller, name)) {
        throw IllegalStateException("Failed to load $name — check logcat for details")
    }
    controller.playVrmaAnimation(loop = command.loop)
    uiState.selectedAnimation = name
    val source = if (uiState.useExternalAnimations) "external" else "assets"
    return "playing $name (loop=${command.loop}, source=$source)"
}

/** ai_cmd set_idle：把一个 .vrma 设为待机（LLM 手势播完/手动停止后回到它）。 */
private fun setIdleCommand(
    uiState: DemoUiState,
    command: AiDebugCommand,
    chat: AiChatDebugHooks?,
): String {
    val name = resolveAssetFile(command.arg, uiState.animationFiles, "animations", listOf(".vrma"))
    return chat?.setIdle?.invoke(name, uiState.useExternalAnimations)
        ?: "idle not wired in this screen"
}

private fun moveCommand(controller: AvatarController, command: AiDebugCommand): String {
    requireCoordinates(command, "move")
    val dx = command.x ?: 0f
    val dy = command.y ?: 0f
    val dz = command.z ?: 0f
    controller.moveAvatar(dx, dy, dz)
    return String.format(
        Locale.US, "avatar moved by (%.3f, %.3f, %.3f) world units", dx, dy, dz
    )
}

private fun zoomCommand(controller: AvatarController, command: AiDebugCommand): String {
    val spreadPx = command.x
        ?: throw IllegalArgumentException("zoom expects ai_x = pinch pixels (+ in, - out), e.g. 200")
    controller.zoomCamera(spreadPx)
    return String.format(Locale.US, "camera dollying by %.0f pinch-px", spreadPx)
}

private fun panCommand(controller: AvatarController, command: AiDebugCommand): String {
    requireCoordinates(command, "pan")
    val dx = command.x ?: 0f
    val dy = command.y ?: 0f
    controller.panCamera(dx, dy)
    return String.format(Locale.US, "camera panned by (%.0f, %.0f) px", dx, dy)
}

private fun orbitCommand(controller: AvatarController, command: AiDebugCommand): String {
    requireCoordinates(command, "orbit")
    val yaw = command.x ?: 0f
    val pitch = command.y ?: 0f
    controller.orbitCamera(yaw, pitch)
    return String.format(Locale.US, "camera orbited by (%.0f, %.0f) px", yaw, pitch)
}

private fun cameraShotCommand(controller: AvatarController, arg: String?): String {
    val shot = when (arg?.lowercase()) {
        "closeup", "close_up", "cu" -> CameraShot.CLOSE_UP
        "medium", "ms" -> CameraShot.MEDIUM_SHOT
        "full", "fs" -> CameraShot.FULL_SHOT
        "long", "ls", "wide" -> CameraShot.LONG_SHOT
        "over", "over_shoulder", "os" -> CameraShot.OVER_SHOULDER
        "off", "none", "clear" -> {
            controller.clearCameraShot()
            return "camera shot released (free camera)"
        }
        else -> throw IllegalArgumentException(
            "camera_shot expects closeup|medium|full|long|over|off, got '$arg'"
        )
    }
    controller.setCameraShot(shot)
    return "camera gliding to $shot (${shot.label})"
}

private fun requireCoordinates(command: AiDebugCommand, what: String) {
    if (!command.hasCoordinates) {
        throw IllegalArgumentException(
            "$what expects numeric ai_x/ai_y/ai_z extras, none were provided"
        )
    }
}

private fun setDragModeCommand(uiState: DemoUiState, arg: String?): String {
    val enabled = when (arg?.lowercase()) {
        "on", "true", "1" -> true
        "off", "false", "0" -> false
        else -> throw IllegalArgumentException("set_drag_mode expects 'on' or 'off'")
    }
    uiState.isDragMode = enabled
    return "drag mode ${if (enabled) "enabled" else "disabled"}"
}

/** Open/switch a bottom panel without touching the screen (MIUI blocks shell input). */
private fun openPanelCommand(uiState: DemoUiState, arg: String?): String {
    val panel = when (arg?.lowercase()) {
        null, "none", "off", "close" -> PanelType.NONE
        "models", "model" -> PanelType.MODELS
        "animations", "animation" -> PanelType.ANIMATIONS
        "expressions", "expression" -> PanelType.EXPRESSIONS
        "scenes", "scene" -> PanelType.SCENES
        "cards", "card" -> PanelType.CARDS
        "settings" -> PanelType.SETTINGS
        else -> throw IllegalArgumentException(
            "open_panel expects none|models|animations|expressions|scenes|cards|settings, got '$arg'"
        )
    }
    uiState.activePanel = panel
    return "panel=${panel.name.lowercase()}"
}

private suspend fun screenshotCommand(context: Context, controller: AvatarController): String {
    val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
        val accepted = controller.captureFrame { frame ->
            if (cont.isActive) cont.resume(frame)
        }
        if (!accepted && cont.isActive) cont.resume(null)
    } ?: throw IllegalStateException(
        "No frame captured — the app may be backgrounded or the renderer not attached"
    )

    val dir = File(context.getExternalFilesDir(null), "ai_debug").apply { mkdirs() }
    val file = File(dir, "screenshot_${System.currentTimeMillis()}.png")
    withContext(Dispatchers.IO) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }
    return "saved ${file.absolutePath} — pull with: adb pull ${file.absolutePath}"
}

// ── Helpers ──────────────────────────────────────────────────────────────

/**
 * Resolve [arg] against [files]: accepts a bare file name with or without
 * extension (e.g. `"AvatarDone"` or `"AvatarDone.glb"`) and ignores any
 * directory prefix. Throws with the valid options when nothing matches.
 */
private fun resolveAssetFile(
    arg: String?,
    files: List<String>,
    what: String,
    extensions: List<String>,
): String {
    if (arg.isNullOrBlank()) {
        throw IllegalArgumentException("Missing $what file name. Send ai_cmd=list with ai_arg=$what to see options.")
    }
    val base = arg.substringAfterLast('/')
    val match =
        // 1) 条目全名（外置模式为含子文件夹的相对路径）
        files.firstOrNull { it.equals(arg, ignoreCase = true) }
            ?: files.firstOrNull { it.equals(base, ignoreCase = true) }
            // 2) 省略扩展名
            ?: files.firstOrNull { file -> extensions.any { file.equals(base + it, ignoreCase = true) } }
            // 3) 递归相对路径下仅按文件名匹配（如 "Waving" → "分类/Waving.vrma"）
            ?: files.firstOrNull { file ->
                val name = file.substringAfterLast('/')
                name.equals(base, ignoreCase = true) ||
                    extensions.any { name.equals(base + it, ignoreCase = true) }
            }
            ?: throw IllegalArgumentException(
                "No $what file matches '$arg'. Send ai_cmd=list with ai_arg=$what to see options."
            )
    return match
}
