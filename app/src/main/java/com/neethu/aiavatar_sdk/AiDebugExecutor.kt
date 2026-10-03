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
            "state" -> describeState(controller, uiState)
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
    "expressions", "expression" ->
        "expressions=${DemoUiState.resolveExpressions(controller.state.value)}"
    else -> throw IllegalArgumentException(
        "list expects models|scenes|animations|expressions, got '$arg'"
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
