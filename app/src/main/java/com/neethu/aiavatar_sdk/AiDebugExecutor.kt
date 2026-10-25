package com.neethu.aiavatar_sdk

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.neethu.aiavatar_sdk.ui.GuideVariant
import com.neethu.corelib.AvatarController
import com.neethu.corelib.CameraShot
import com.neethu.corelib.RenderMode
import com.neethu.corelib.ScenePlacement
import com.neethu.corelib.WalkStartResult
import com.neethu.orchestrator.locomotion.LocomotionDriver
import com.neethu.orchestrator.session.AvatarSession
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
    /** ai_cmd import_lorebook：独立世界书 JSON 导入（绑当前卡/设全局）。 */
    val importLorebook: (ByteArray, String?) -> String? = { _, _ -> null },
    /** ai_cmd lorebook：世界书库快照（开关/列表/生效书条目）。 */
    val lorebookSnapshot: () -> String = { "AI chat not wired in this screen" },
    /** ai_cmd export_history jsonl|md：当前上下文历史导出到 app 外存，返回路径。 */
    val exportHistory: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /** ai_cmd import_history：ST jsonl 灌入当前上下文（覆盖），返回条数行。 */
    val importHistory: (ByteArray) -> String = { _ -> "AI chat not wired in this screen" },
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
    /** ai_cmd set_provider siliconflow|volcano|openrouter|deepseek|bailian：切大模型服务商（模型清单随之切换）。 */
    val setProvider: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /** ai_cmd set_custom <baseUrl>|<apiKey>|<model>：自定义端点三件套一次配齐（并切到 CUSTOM）。 */
    val setCustomEndpoint: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /** ai_cmd set_tts_provider siliconflow|volcano|openrouter|bailian：TTS 独立服务商切换（自动取消同服务商勾选；deepseek 无 TTS 会被改道 Edge）。 */
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
    /** ai_cmd set_asr cloud|system：切语音识别引擎（云端=跟随大模型服务商；系统=平台 SpeechRecognizer）。 */
    val setAsrEngine: (String?) -> String = { _ -> "AI chat not wired in this screen" },

    /**
     * ai_cmd set_language system|zh|en：切界面语言（持久化；会话随之重建、提示词
     * 换语言，历史保留）。
     */
    val setLanguage: (String?) -> String = { _ -> "AI chat not wired in this screen" },
    /**
     * ai_cmd set_expression <名字> [强度]：走 FaceDriver 手动表情通道（缓动
     * 进场、不自动归零）；null = 无会话，executor 直写控制器（即时，一帧）。
     */
    val manualExpression: ((String, Float) -> String)? = null,
    /** ai_cmd clear_expression：缓动回中性；null = 无会话，executor 直清控制器。 */
    val clearManualExpression: (() -> String)? = null,
    /** ai_cmd skill_status / skill_exit / rps_throw <手>：技能框架调试（挂会话后可用）。 */
    val skillDebug: (String, String?) -> String = { _, _ ->
        "AI chat not wired in this screen"
    },
    /** ai_cmd face_pose：最近一次头部姿态观测行（「看这边」符号标定用）。 */
    val facePose: () -> String = { "head pose not wired in this screen" },
    /** ai_cmd mimic_status：模仿技能 + 渲染引擎 + 相机车道状态汇总。 */
    val mimicStatus: () -> String = { "mimic not wired in this screen" },
    /** ai_cmd mimic_pose：最近解算的镜像方向集（「模仿我」符号标定探针）。 */
    val mimicPose: () -> String = { "mimic not wired in this screen" },
    /** ai_cmd mimic_force <preset>：合成姿态注入（免相机 A/B 镜像标定入口）。 */
    val mimicForce: (String?) -> String = { "mimic not wired in this screen" },
    /** ai_cmd mimic_face on|off：表情车道开关（P2 表情模仿 A/B）。 */
    val setMimicFace: (String?) -> String = { "mimic not wired in this screen" },
    /** ai_cmd tools_list：工具注册表快照（挂会话后可用）。 */
    val toolsList: () -> String = { "AI chat not wired in this screen" },
    /** ai_cmd tools_call <name> [json]：手工执行工具（绕 LLM——真机 A/B 的关键入口，挂起）。 */
    val toolsCall: suspend (String, String?) -> String = { _, _ ->
        "AI chat not wired in this screen"
    },
    /** ai_cmd agent_state：智能体工具状态快照（注册表/最近调用/禁用模型/上限配置）。 */
    val agentState: () -> String = { "AI chat not wired in this screen" },
    /** ai_cmd memory_list [query]：长期记忆条目（可选过滤词）。挂起。 */
    val memoryList: suspend (String?) -> String = { _ ->
        "memory not wired in this screen"
    },
    /** ai_cmd memory_stats：总数/分类分布/来源分布/待提炼窗口状态。挂起。 */
    val memoryStats: suspend () -> String = {
        "memory not wired in this screen"
    },
    /** ai_cmd memory_forget <id前缀>：按 id 前缀删一条（唯一匹配才删）。挂起。 */
    val memoryForget: suspend (String) -> String = { _ ->
        "memory not wired in this screen"
    },
    /** ai_cmd memory_clear：清空全部记忆与待提炼窗口（面板清空同一路径）。挂起。 */
    val memoryClear: suspend () -> String = {
        "memory not wired in this screen"
    },
    /** ai_cmd memory_config [turns] [chars]：无参=查看提炼阈值；带参=运行时调整。 */
    val memoryConfig: (String?) -> String = { _ ->
        "memory not wired in this screen"
    },
    /** 会话直取缝（ai_cmd anchors / walk_to <锚点名> 等 P1 走动调试命令用）；null = 无会话。 */
    val session: () -> AvatarSession? = { null },
    /** 场景交互就位链（ai_cmd interact/pose_state/pose_clear/interactions 用）。 */
    val interaction: InteractionCoordinator? = null,
    /** ai_cmd autonomy_state：自主 FSM 状态快照（状态/锚点/下次决策倒计时/问候调度）。 */
    val autonomyState: () -> String = { "autonomy not wired in this screen" },
    /** ai_cmd autonomy_log：最近 20 条自主转移记录。 */
    val autonomyLog: () -> String = { "autonomy not wired in this screen" },
    /** ai_cmd autonomy on|off（无参=翻转）：自主生命体运行时开关。 */
    val autonomyToggle: (String?) -> String = { _ -> "autonomy not wired in this screen" },
    /** ai_cmd autonomy_greet：立刻问候（测试入口，绕开间隔/安静门，日上限仍守）。 */
    val autonomyGreet: () -> String = { "autonomy not wired in this screen" },
    /** ai_cmd autonomy_think：立刻弹一条思考气泡（气泡渲染验收入口；挂起=查记忆素材）。 */
    val autonomyThink: suspend () -> String = { "autonomy not wired in this screen" },
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
    gamepad: GamepadHub? = null,
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
            "import_model" -> importModelCommand(context, uiState, command.arg)
            "load_scene" -> loadSceneCommand(controller, uiState, command.arg)
            "skybox" -> skyboxCommand(context, controller, uiState, command.arg)
            "set_expression" -> setExpressionCommand(controller, uiState, command, chat)
            "clear_expression" -> {
                val eased = chat?.clearManualExpression?.invoke()
                if (eased != null) {
                    uiState.selectedExpression = null
                    eased
                } else {
                    controller.clearAllExpressions()
                    uiState.selectedExpression = null
                    "face reset to neutral"
                }
            }
            "play_animation" -> playAnimationCommand(context, controller, uiState, command)
            "accessory" -> AccessoryTools.command(context, controller, command.arg)
            "fx" -> FxTools.command(context, controller, command.arg, command.x, command.y, command.z)
            "stop_animation" -> {
                controller.stopVrmaAnimation()
                uiState.selectedAnimation = null
                "animation stopped"
            }
            "set_idle" -> setIdleCommand(uiState, command, chat)
            "idle_off" -> chat?.clearIdle?.invoke()
                ?: "idle not wired in this screen"
            "move" -> moveCommand(controller, command)
            "walk_to" -> walkToCommand(controller, uiState, command, chat)
            "walk_stop" -> {
                controller.stopWalk()
                "walk stopped (back to idle)"
            }
            "walk_state" -> walkStateCommand(controller)
            "walk_gait" -> walkGaitCommand(controller)
            "walk_strafe" -> walkStrafeCommand(controller, uiState, command)
            "walk_fade" -> walkFadeCommand(controller, command.arg)
            "walk_overlay" -> walkOverlayCommand(controller, command.arg)
            "walk_extract" -> walkExtractCommand(controller, command.arg)
            "pad" -> {
                // 手柄枢纽命令族（docs/gamepad-control-feasibility.md §6）：与
                // 硬件手柄写同一份轴状态——AI 的模拟量连续控制通道
                if (gamepad == null) throw IllegalStateException("gamepad hub not wired in this screen")
                gamepad.executeCommand(command)
            }
            "autonomy_state" -> chat?.autonomyState?.invoke()
                ?: "autonomy not wired in this screen"
            "autonomy_log" -> chat?.autonomyLog?.invoke()
                ?: "autonomy not wired in this screen"
            "autonomy" -> chat?.autonomyToggle?.invoke(command.arg)
                ?: "autonomy not wired in this screen"
            "autonomy_greet" -> chat?.autonomyGreet?.invoke()
                ?: "autonomy not wired in this screen"
            "autonomy_think" -> chat?.autonomyThink?.invoke()
                ?: "autonomy not wired in this screen"
            "anchors" -> anchorsCommand(chat)
            "interactions" -> interactionsCommand(chat)
            "interact" -> interactCommand(controller, chat, command.arg)
            "pose_state" -> poseStateCommand(controller, chat)
            "pose_clear" -> poseClearCommand(chat)
            "occlusion" -> occlusionCommand(controller, command.arg)
            "walk_transit" -> walkTransitCommand(controller, command.arg)
            "scene_offset" -> scenePlacementCommand(controller, uiState, command, rotate = false)
            "scene_rot" -> scenePlacementCommand(controller, uiState, command, rotate = true)
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
            "spring" -> {
                val enabled = when (command.arg?.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    null -> throw IllegalArgumentException("spring expects 'on' or 'off' (hair/bust spring physics toggle)")
                    else -> throw IllegalArgumentException("spring expects 'on' or 'off', got '${command.arg}'")
                }
                controller.setSpringBoneEnabled(enabled)
                if (enabled) "spring bone physics ON (hair/bust sway active)"
                else "spring bone physics OFF (hair/bust frozen at rest pose)"
            }
            "culling" -> {
                val enabled = when (command.arg?.lowercase()) {
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> throw IllegalArgumentException("culling expects 'on' or 'off'")
                }
                controller.setAvatarCulling(enabled)
                if (enabled) "avatar frustum culling ON (bug-compatible: eyeballs can vanish at close range when the head turns)"
                else "avatar frustum culling OFF (fix: skinned meshes always draw)"
            }
            "render_mode" -> {
                val mode = when (command.arg?.lowercase()) {
                    "pbr" -> RenderMode.PBR
                    "mtoon" -> RenderMode.MTOON
                    null -> throw IllegalArgumentException("render_mode expects 'pbr' or 'mtoon'")
                    else -> throw IllegalArgumentException("render_mode expects 'pbr' or 'mtoon', got '${command.arg}'")
                }
                // 与设置页同一条链路（手动覆盖仅对当前模型生效，下次切换
                // 模型重新按材质自动判定）；renderMode 变化的模型重载由
                // AvatarController 驱动
                val newSettings = uiState.renderSettings.copy(renderMode = mode)
                uiState.updateRenderSettings(newSettings)
                controller.updateRenderSettings(newSettings)
                if (mode == RenderMode.MTOON) "render mode MTOON (cel shading + outline, model reloaded; manual override until the next model switch)"
                else "render mode PBR (model reloaded; manual override until the next model switch)"
            }
            "breath" -> when (command.arg?.lowercase()) {
                "on", "true", "1" -> {
                    controller.setBreathEnabled(true)
                    "breath overlay ON"
                }
                "off", "false", "0" -> {
                    controller.setBreathEnabled(false)
                    "breath overlay OFF (chest/shoulder micro-motion removed)"
                }
                null, "status", "state" ->
                    controller.getBreathInfo()?.let { "breath: $it" }
                        ?: "breath: no avatar loaded (engine not bound)"
                else -> throw IllegalArgumentException(
                    "breath expects 'on', 'off', or no ai_arg (= status)"
                )
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
            "import_lorebook" -> {
                val arg = command.arg
                    ?: throw IllegalArgumentException(
                        "import_lorebook expects ai_arg = a ST World Info JSON path (absolute, or relative " +
                            "to the app's external files dir; adb push it there first)"
                    )
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                val file = resolveAppFile(context, arg)
                chat.importLorebook(file.readBytes(), file.name)
                    ?: throw IllegalArgumentException(
                        "not a parseable ST World Info JSON: ${file.absolutePath}"
                    )
            }
            "lorebook" -> chat?.lorebookSnapshot()
                ?: throw IllegalStateException("AI chat not wired in this screen")
            "export_history" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.exportHistory(command.arg)
            }
            "import_history" -> {
                val arg = command.arg
                    ?: throw IllegalArgumentException(
                        "import_history expects ai_arg = a ST jsonl path (absolute, or relative " +
                            "to the app's external files dir; adb push it there first)"
                    )
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.importHistory(resolveAppFile(context, arg).readBytes())
            }
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
            "set_custom" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.setCustomEndpoint(command.arg)
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
            "set_asr" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.setAsrEngine(command.arg)
            }
            "set_language" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.setLanguage(command.arg)
            }
            "skill_status" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.skillDebug("rps", "status")
            }
            "skill_exit" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.skillDebug("rps", "exit")
            }
            "rps_throw" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                // ai_arg = rock|scissor|paper（缺省=查看状态）；强制出拳后下一句
                // send_chat/语音即成为裁判回合——真机 A/B 驱动的关键入口
                chat.skillDebug("rps", command.arg)
            }
            "rps_gesture" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                // ai_arg = rock|scissor|paper：模拟一次相机确认的用户手势（P2 本地
                // 判定路径），免摄像头/免真手势驱动——本地出拳+即时宣判立即发生，
                // 下一句 send_chat/语音成为气氛组回合
                chat.skillDebug("rps", command.arg?.lowercase()?.let { "gesture_$it" } ?: "status")
            }
            "look_status" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.skillDebug("look", "status")
            }
            "look_throw" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                // ai_arg = up|down|left|right（省略=查状态）：强制朝指定方向指——
                // 屏幕方向标定与免麦 A/B 的关键入口
                chat.skillDebug("look", command.arg?.lowercase()?.let { "throw_$it" } ?: "status")
            }
            "face_pose" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.facePose()
            }
            "mimic_status" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.mimicStatus()
            }
            "mimic_pose" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.mimicPose()
            }
            "mimic_force" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                // ai_arg = tpose|left_up|right_up|both_up|forward|off（省略=查状态）：
                // 注入合成姿态走真实解算路径——免相机/免技能激活 A/B 引擎与镜像符号
                chat.mimicForce(command.arg?.lowercase())
            }
            "mimic_face" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                // ai_arg = on|off（省略=查状态）：表情车道开关——排查表情通道与
                // 身体通道问题的隔离开关（off=只模仿身体，表情留在情绪通道）
                chat.setMimicFace(command.arg?.lowercase())
            }
            "tools_list" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.toolsList()
            }
            "tools_call" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                // 手工执行绕开 LLM——「模型会不会调、调得对不对」之前先验工具本身
                val (toolName, json) = parseToolsCallArg(command.arg)
                chat.toolsCall(toolName, json)
            }
            "observe" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                // 等价 tools_call observe_camera：抓帧 + 视觉描述全链真机验证
                chat.toolsCall("observe_camera", command.arg?.trim()?.ifEmpty { null } ?: "{}")
            }
            "agent_state" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.agentState()
            }
            "memory_list" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.memoryList(command.arg)
            }
            "memory_stats" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.memoryStats()
            }
            "memory_forget" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.memoryForget(
                    command.arg?.trim()?.takeIf { it.isNotEmpty() }
                        ?: throw IllegalArgumentException(
                            "memory_forget expects ai_arg = memory id prefix (send ai_cmd memory_list to see ids)",
                        ),
                )
            }
            "memory_clear" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.memoryClear()
            }
            "memory_config" -> {
                if (chat == null) throw IllegalStateException("AI chat not wired in this screen")
                chat.memoryConfig(command.arg)
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

/**
 * tools_call 的 ai_arg 解析（纯函数可测）：`"<name> [json]"` 空格分隔，
 * JSON 缺省 `"{}"`。非法（无工具名）抛 [IllegalArgumentException]。
 */
internal fun parseToolsCallArg(arg: String?): Pair<String, String> {
    val spec = arg?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw IllegalArgumentException(
            "tools_call expects ai_arg = tool name (see ai_cmd tools_list), " +
                "optionally followed by a JSON arguments object"
        )
    return spec.substringBefore(' ').trim() to spec.substringAfter(' ', "").trim().ifEmpty { "{}" }
}

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
    uiState.selectModel(name)
    val src = if (name in uiState.importedModelFiles) "imported (filesDir/vrms)" else "assets/vrms"
    // 与 UI 面板刻意不同：调试命令不轮换上下文（模型重载后 FaceDriver 刷新
    // 表情集合接管），方便不破坏当前会话地 A/B 模型；面板切换才新建上下文
    return "loading $name from $src (expressions/animations reset; context kept)"
}

/** import_model：把外存文件导入 filesDir/vrms 并自动选中（与 UI 导入同语义）。 */
private fun importModelCommand(
    context: Context,
    uiState: DemoUiState,
    arg: String?,
): String {
    val arg2 = arg
        ?: throw IllegalArgumentException(
            "import_model expects ai_arg = a .vrm/.glb file path (absolute, or relative " +
                "to the app's external files dir; adb push it there first)"
        )
    val file = resolveAppFile(context, arg2)
    val name = uiState.importModelBytes(file.readBytes(), file.name)
        ?: throw IllegalArgumentException("not a VRM/GLB file: ${file.absolutePath}")
    // 与 UI 导入同语义：自动选中 + 新建上下文（表情目录随模型轮换）
    uiState.selectModel(name)
    uiState.newContext()
    return "imported '$name' to filesDir/vrms — selected, new context started"
}

private fun loadSceneCommand(
    controller: AvatarController,
    uiState: DemoUiState,
    arg: String?,
): String {
    if (arg == null || arg.lowercase() in setOf("none", "off", "remove", "clear")) {
        uiState.setScene(null)
        return "scene removed"
    }
    val name = resolveAssetFile(arg, uiState.sceneFiles, "scenes", listOf(".glb"))
    if (name == uiState.selectedScene) return "scene '$name' is already active"
    uiState.setScene(name)
    return "loading scene/$name"
}

/**
 * 360° 全景天空盒（assets/skybox，docs/skybox.md）：
 * - 无参/list：当前 + 可用列表 + 亮度倍率
 * - off/none：恢复纯色背景
 * - yaw <deg>：水平偏移（原画面右侧内容转到正前方；全景模式才有意义）
 * - gain <f>：亮度倍率（1.0 = 与原图一致；>1 提亮 <1 压暗，范围 0.1–4）
 * - <name>：切换全景（支持省扩展名/大小写不敏感）
 */
private fun skyboxCommand(
    context: Context,
    controller: AvatarController,
    uiState: DemoUiState,
    arg: String?,
): String {
    val files = uiState.skyboxFiles
    val parts = arg?.trim()?.split(Regex("\\s+")) ?: emptyList()
    return when (val head = parts.firstOrNull()?.lowercase()) {
        null, "list" -> {
            val current = controller.getSkyboxSource()?.substringAfterLast('/')
                ?: uiState.selectedSkybox ?: "none"
            "current=$current brightness=${controller.getSkyboxBrightness()}\n" +
                "skyboxes=${files.joinToString(",")}"
        }
        "off", "none" -> {
            uiState.setSkybox(null)
            "skybox cleared (solid background)"
        }
        "yaw" -> {
            val deg = parts.getOrNull(1)?.toFloatOrNull()
                ?: throw IllegalArgumentException("skybox yaw expects a degree number, e.g. ai_cmd=skybox ai_arg=\"yaw 90\"")
            if (controller.setSkyboxYaw(deg)) "skybox yaw rotated to $deg°"
            else "no panorama active (yaw ignored)"
        }
        "gain", "brightness" -> {
            val factor = parts.getOrNull(1)?.toFloatOrNull()
                ?: throw IllegalArgumentException(
                    "skybox gain expects a multiplier, e.g. ai_cmd=skybox ai_arg=\"gain 1.3\"",
                )
            val applied = controller.setSkyboxBrightness(factor)
            (if (applied) "skybox brightness set to $factor (re-deriving panorama)"
            else "brightness stored ($factor); no panorama active") +
                " [applied=${controller.getSkyboxBrightness()}]"
        }
        else -> {
            val name = SkyboxCatalog.resolve(context, head)
                ?: throw IllegalArgumentException(
                    "Unknown skybox '$head'. Available: ${files.joinToString(",")}"
                )
            if (name == uiState.selectedSkybox) return "skybox '$name' is already active"
            uiState.setSkybox(name)
            "loading skybox/$name"
        }
    }
}

private fun setExpressionCommand(
    controller: AvatarController,
    uiState: DemoUiState,
    command: AiDebugCommand,
    chat: AiChatDebugHooks?,
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
    // 有会话走 FaceDriver 手动表情通道（缓动进场、不自动归零）；无会话直写
    // 控制器（控制器此时不在 instant 模式，还保留默认 300ms 过渡）。
    chat?.manualExpression?.let { manual -> return manual(name, weight) }
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

// ── Locomotion（自由走动 P0/P1，docs/locomotion-feasibility.md）──────────

private fun walkToCommand(
    controller: AvatarController,
    uiState: DemoUiState,
    command: AiDebugCommand,
    chat: AiChatDebugHooks?,
): String {
    val speed = command.arg?.toFloatOrNull()
    // 两种目标形态：ai_x/ai_z 坐标（P0，直线走）或锚点名（P1，ai_cmd anchors
    // 可查词表）——锚点名走 driver（P2 waypoint 图路由 + 失败归因同 LLM 通道）
    if (command.x == null || command.z == null) {
        if (command.arg.isNullOrBlank() || speed != null) {
            throw IllegalArgumentException(
                "walk_to expects ai_x/ai_z = target world meters (ai_arg = speed m/s), or an anchor name: walk_to sofa",
            )
        }
        val driver = chat?.session?.invoke()?.locomotionDriver
            ?: throw IllegalStateException("AI chat not wired in this screen — anchor walking needs the session")
        ensureWalkGaits(controller)
        if (speed != null) driver.speed = speed
        Log.i("AiDebugExecutor", "walkTo via driver=${driver.hashCode()} session=${chat.session.invoke().hashCode()}")
        val outcome = driver.walkTo(command.arg.trim().lowercase())
        return when (outcome) {
            LocomotionDriver.Outcome.STARTED -> {
                val legs = driver.pendingLegsSnapshot()
                buildString {
                    append("walking to '${command.arg.trim().lowercase()}'")
                    if (legs.isNotEmpty()) append(" via waypoints: ${legs.joinToString(" → ")}")
                    append("; pair with `camera_shot follow` for a tracking camera")
                }
            }
            LocomotionDriver.Outcome.UNKNOWN_ANCHOR ->
                throw IllegalArgumentException(
                    "unknown anchor '${command.arg}' — run `anchors` for the table, or use coordinates: walk_to ai_x ai_z [speed]",
                )
            LocomotionDriver.Outcome.ALREADY_WALKING -> "already walking — new move cue ignored (debounce)"
            else -> "walk rejected: $outcome"
        }
    }
    val target: Pair<Float, Float> = command.x to command.z
    val (tx, tz) = target
    if (uiState.selectedScene == null) {
        throw IllegalStateException(
            "no scene loaded — run load_scene home.glb first (walk bounds come from its placement meta)"
        )
    }
    if (!controller.isWalkGaitConfigured()) {
        ensureWalkGaits(controller)
    }
    val placement = controller.getScenePlacement()
    val result = if (speed != null) controller.startWalk(tx, tz, speed) else controller.startWalk(tx, tz)
    return when (result) {
        WalkStartResult.STARTED -> buildString {
            append(String.format(Locale.US, "walking to (%.2f, %.2f)", tx, tz))
            if (speed != null) append(String.format(Locale.US, " at %.2f m/s", speed))
            placement?.bounds?.let {
                append(
                    String.format(
                        Locale.US, " [bounds x %.1f..%.1f, z %.1f..%.1f]",
                        it.minX, it.maxX, it.minZ, it.maxZ
                    )
                )
            }
            append("; pair with `camera_shot follow` for a tracking camera")
        }
        WalkStartResult.NO_MODEL -> throw IllegalStateException("no model loaded")
        WalkStartResult.NO_SCENE -> throw IllegalStateException("no scene loaded — walking without a room means walking on air; run load_scene first")
        WalkStartResult.NO_GAIT -> throw IllegalStateException("walk gait not configured")
        WalkStartResult.MIMIC_ACTIVE -> throw IllegalStateException("mimic engaged — run `mimic_force off` first")
        WalkStartResult.POSE_ACTIVE -> throw IllegalStateException("contact pose active — run `pose_clear` first")
        WalkStartResult.LOAD_FAILED -> throw IllegalStateException("walk gait clip failed to load")
        WalkStartResult.NO_RENDERER -> throw IllegalStateException("renderer not attached")
    }
}

/**
 * 走动步态一次性全量配置（walk/run/strafe）：walkToCommand 与 MainActivity
 * 接线共用，幂等（已配置则跳过）。run/strafe 素材 baseSpeed=0 → 由素材自带
 * 的烘焙位移自动标定（P2 方案 C，无需 walk-calib）。
 */
internal fun ensureWalkGaits(controller: AvatarController) {
    if (controller.isWalkGaitConfigured()) return
    if (!controller.setWalkGait(DEFAULT_WALK_CLIP, DEFAULT_WALK_BASE_SPEED)) {
        throw IllegalStateException("walk gait failed to load: assets/$DEFAULT_WALK_CLIP")
    }
    // P2 跑步/侧移步态（带烘焙位移，提取回灌驱动；失败不阻断基础走动）
    if (!controller.setRunGait(DEFAULT_RUN_CLIP)) {
        Log.w(AI_LOG_TAG, "run gait failed to load: assets/$DEFAULT_RUN_CLIP")
    }
    if (!controller.setStrafeGaits(DEFAULT_STRAFE_LEFT_CLIP, DEFAULT_STRAFE_RIGHT_CLIP)) {
        Log.w(AI_LOG_TAG, "strafe gaits failed to load: assets/$DEFAULT_STRAFE_LEFT_CLIP / $DEFAULT_STRAFE_RIGHT_CLIP")
    }
}

/** ai_cmd walk_gait：步态槽一览（walk/run/strafe 左右 + 烘焙位移标记）。 */
private fun walkGaitCommand(controller: AvatarController): String = controller.walkGaitsSummary()

/**
 * ai_cmd walk_strafe：朝向锁死横步挪（P2 侧移）。
 * `walk_strafe left|right` + 可选 `ai_x`=米数（默认 1.0）`ai_z`=速度（默认 1.0）
 */
private fun walkStrafeCommand(
    controller: AvatarController,
    uiState: DemoUiState,
    command: AiDebugCommand,
): String {
    val dir = command.arg?.trim()?.lowercase()
        ?: throw IllegalArgumentException("walk_strafe expects ai_arg = left|right (ai_x = meters, ai_z = speed)")
    if (dir != "left" && dir != "right") {
        throw IllegalArgumentException("walk_strafe expects ai_arg = left|right, got '$dir'")
    }
    if (uiState.selectedScene == null) {
        throw IllegalStateException("no scene loaded — run load_scene home.glb first")
    }
    ensureWalkGaits(controller)
    val meters = command.x ?: 1.0f
    val speed = command.z ?: 1.0f
    return when (val r = controller.startStrafe(dir == "left", meters, speed)) {
        WalkStartResult.STARTED ->
            "strafing $dir %.1fm at %.2f m/s (heading locked)".format(meters, speed)
        WalkStartResult.NO_GAIT -> throw IllegalStateException("strafe gait not configured")
        else -> "strafe rejected: $r"
    }
}

/** ai_cmd walk_fade：走动起步/停步 crossfade 时长（秒）。空=查看，0=关。 */
private fun walkFadeCommand(controller: AvatarController, arg: String?): String {
    if (arg.isNullOrBlank()) {
        return "walk fade = ${controller.getWalkFadeSeconds()} s (0 = hard cut)"
    }
    val seconds = arg.toFloatOrNull()
        ?: throw IllegalArgumentException("walk_fade expects seconds (0..2), got '$arg'")
    controller.setWalkFadeSeconds(seconds)
    return "walk fade = ${controller.getWalkFadeSeconds()} s"
}

/** ai_cmd walk_overlay：走动中手势 overlay（上半身 mask 边走边比划）开关。 */
private fun walkOverlayCommand(controller: AvatarController, arg: String?): String {
    return when (arg?.lowercase()) {
        null, "", "status" ->
            "gesture overlay = ${controller.isGestureOverlayEnabled()}"
        "on", "true", "1" -> {
            controller.setGestureOverlayEnabled(true)
            "gesture overlay ON (walk + <act:> gesture together, upper-body mask)"
        }
        "off", "false", "0" -> {
            controller.setGestureOverlayEnabled(false)
            "gesture overlay OFF (walking gestures stop-and-play, P1 behavior)"
        }
        else -> throw IllegalArgumentException("walk_overlay expects on|off|status")
    }
}

/** ai_cmd walk_extract：方案 C 位移提取总开关（A/B 对拍用）。 */
private fun walkExtractCommand(controller: AvatarController, arg: String?): String {
    return when (arg?.lowercase()) {
        null, "", "status" ->
            "walk extraction = ${controller.isExtractionEnabled()} (baked gait motion → root)"
        "on", "true", "1" -> {
            controller.setExtractionEnabled(true)
            "walk extraction ON (baked hips motion drives root; no loop snap-back)"
        }
        "off", "false", "0" -> {
            controller.setExtractionEnabled(false)
            "walk extraction OFF (program displacement only, P1 behavior)"
        }
        else -> throw IllegalArgumentException("walk_extract expects on|off|status")
    }
}

/** ai_cmd anchors：会话注入的锚点表（`<move:>` 词表 = move_to 白名单），踩点核对用。 */
private fun anchorsCommand(chat: AiChatDebugHooks?): String {
    val anchors = chat?.session?.invoke()?.locomotionAnchors
        ?: return "AI chat not wired in this screen"
    if (anchors.isEmpty()) {
        return "no anchors injected (llmMove off, or assets/scene/home.anchors.json missing/empty)"
    }
    return anchors.joinToString("\n") { a ->
        "- ${a.name} → (${a.x}, ${a.z})" +
            if (a.links.isNotEmpty()) " ⇄ ${a.links.joinToString(",")}" else ""
    }
}

/** ai_cmd interactions：当前场景的交互点表（interact_with 工具白名单同源）。 */
private fun interactionsCommand(chat: AiChatDebugHooks?): String {
    val coordinator = chat?.interaction
        ?: return "AI chat not wired in this screen"
    if (coordinator.anchors.isEmpty()) {
        return "no interaction anchors for this scene (assets/scene/<scene>.interactions.json missing)"
    }
    return coordinator.anchors.joinToString("\n") { a ->
        "- ${a.id} [${a.kind}]" +
            (a.approach?.let { " via $it" } ?: "") +
            " yaw=${a.yawDeg}" +
            " — ${a.label}"
    }
}

/**
 * ai_cmd interact <锚点id>：就位链（走位→转向→坐/躺接触对齐），异步起步
 * ——链路在 InteractionCoordinator 的 coroutine 里跑完，命令立即回显状态。
 */
private fun interactCommand(
    controller: AvatarController,
    chat: AiChatDebugHooks?,
    arg: String?,
): String {
    val coordinator = chat?.interaction
        ?: throw IllegalStateException("AI chat not wired in this screen")
    if (arg.isNullOrBlank()) {
        throw IllegalArgumentException("interact expects an anchor id — run `interactions` for the table")
    }
    val result = coordinator.interact(arg.trim().lowercase())
    if (result.startsWith("unknown")) throw IllegalArgumentException(result)
    return "$result; pair with `camera_shot follow` for a tracking camera"
}

/** ai_cmd pose_state：接触定姿状态 + 防遮挡诊断。 */
private fun poseStateCommand(controller: AvatarController, chat: AiChatDebugHooks?): String {
    val poseLine = chat?.interaction?.stateText() ?: "pose=none (coordinator not wired)"
    return "$poseLine\nocclusion: ${controller.getOcclusionDebugInfo() ?: "n/a"}"
}

/** ai_cmd pose_clear：起身（结束坐/躺姿）。 */
private fun poseClearCommand(chat: AiChatDebugHooks?): String {
    val coordinator = chat?.interaction
        ?: throw IllegalStateException("AI chat not wired in this screen")
    return coordinator.clearPose()
}

/** ai_cmd occlusion on|off|state：墙体自动剖切开关（防遮挡 A/B 用）。 */
private fun occlusionCommand(controller: AvatarController, arg: String?): String {
    when (arg?.lowercase()) {
        null, "state", "status" -> {}
        "on", "true", "1" -> controller.setOcclusionEnabled(true)
        "off", "false", "0" -> controller.setOcclusionEnabled(false)
        else -> throw IllegalArgumentException("occlusion expects on|off|state")
    }
    return "occlusion ${if (controller.isOcclusionEnabled()) "on" else "off"} — " +
        (controller.getOcclusionDebugInfo() ?: "no scene")
}

/**
 * ai_cmd walk_transit：起步/停步过渡剪辑配置（P1 档 1 真机 A/B 入口）。
 * `walk_transit start <assets路径>` / `walk_transit stop <assets路径>` /
 * `walk_transit off` / 无参 = 查看当前配置。素材位移测量见
 * docs/locomotion-feasibility.md §5.2（默认关——库内起步候选带 root motion）。
 */
private fun walkTransitCommand(controller: AvatarController, arg: String?): String {
    val current = controller.getWalkTransitions()
    fun dump(configured: Boolean): String {
        val (start, stop) = current
        return "walk transitions: start=${start ?: "off"} stop=${stop ?: "off"}" +
            if (configured) " (configured)" else ""
    }
    val parts = arg?.trim()?.split(" ", limit = 2) ?: emptyList()
    return when {
        arg.isNullOrBlank() -> dump(controller.hasWalkTransitions())
        parts[0].lowercase() == "off" -> {
            controller.setWalkTransitions(null, null)
            "walk transitions cleared (hard start/stop)"
        }
        parts.size == 2 && parts[0].lowercase() in setOf("start", "stop") -> {
            val path = parts[1].trim()
            val (curStart, curStop) = current
            val ok = if (parts[0].equals("start", ignoreCase = true)) {
                controller.setWalkTransitions(path, curStop)
            } else {
                controller.setWalkTransitions(curStart, path)
            }
            if (ok) {
                val (s, st) = controller.getWalkTransitions()
                "walk transition ${parts[0]} set: start=${s ?: "off"} stop=${st ?: "off"}"
            } else {
                throw IllegalStateException("walk transition clip failed to load: assets/$path")
            }
        }
        else -> throw IllegalArgumentException(
            "walk_transit expects: (none to dump) | off | start <assets/*.vrma> | stop <assets/*.vrma>",
        )
    }
}

private fun walkStateCommand(controller: AvatarController): String {
    val s = controller.getWalkState() ?: return "walk: no renderer attached"
    val gait = if (controller.isWalkGaitConfigured()) controller.walkGaitsSummary() else "none"
    val placement = controller.getScenePlacement()
    return String.format(
        Locale.US,
        "walk: phase=%s pos=(%.2f, %.2f) heading=%.0f° target=(%.2f, %.2f) speed=%.2f m/s | gait: %s | fade=%.2fs overlay=%s extract=%s | placement: %s",
        s.phase, s.x, s.z, s.headingDeg, s.targetX, s.targetZ, s.speed,
        gait,
        controller.getWalkFadeSeconds(),
        controller.isGestureOverlayEnabled(),
        controller.isExtractionEnabled(),
        placement?.let {
            String.format(
                Locale.US, "yaw=%.1f° off=(%.2f, %.2f, %.2f)",
                it.yawDeg, it.offsetX, it.offsetY, it.offsetZ
            )
        } ?: "none",
    )
}

private fun scenePlacementCommand(
    controller: AvatarController,
    uiState: DemoUiState,
    command: AiDebugCommand,
    rotate: Boolean,
): String {
    if (uiState.selectedScene == null) throw IllegalStateException("no scene loaded — run load_scene first")
    val cur = controller.getScenePlacement() ?: ScenePlacement()
    val next = if (rotate) {
        cur.copy(yawDeg = cur.yawDeg + (command.x ?: 0f))
    } else {
        cur.copy(
            offsetX = cur.offsetX + (command.x ?: 0f),
            offsetY = cur.offsetY + (command.y ?: 0f),
            offsetZ = cur.offsetZ + (command.z ?: 0f),
        )
    }
    controller.setScenePlacement(next)
    // 回显可直接抄进 assets/scene/<scene>.meta.json 的 placement 段
    return String.format(
        Locale.US,
        "scene placement → yaw=%.2f° offset=(%.3f, %.3f, %.3f) | meta json: " +
            "{\"offsetX\": %.3f, \"offsetY\": %.3f, \"offsetZ\": %.3f, \"yawDeg\": %.2f}",
        next.yawDeg, next.offsetX, next.offsetY, next.offsetZ,
        next.offsetX, next.offsetY, next.offsetZ, next.yawDeg,
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
        "macro", "macro_shot", "mc" -> CameraShot.MACRO
        "medium", "ms" -> CameraShot.MEDIUM_SHOT
        "full", "fs" -> CameraShot.FULL_SHOT
        "long", "ls", "wide" -> CameraShot.LONG_SHOT
        "over", "over_shoulder", "os" -> CameraShot.OVER_SHOULDER
        "follow", "fl", "tracking" -> CameraShot.FOLLOW
        "off", "none", "clear" -> {
            controller.clearCameraShot()
            return "camera shot released (free camera)"
        }
        else -> throw IllegalArgumentException(
            "camera_shot expects closeup|macro|medium|full|long|over|follow|off, got '$arg'"
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
    when (arg?.lowercase()) {
        // 新手引导不是 PanelType（独立 overlay，优先级高于全部面板）：
        // guide=按界面语言自动选受众，guide_cn/guide_intl=强制指定（A/B 另一版）
        "guide", "onboarding" -> {
            uiState.guideVariantOverride = null
            uiState.activePanel = PanelType.NONE
            uiState.guideVisible = true
            return "panel=guide (variant by app language)"
        }
        "guide_cn" -> {
            uiState.guideVariantOverride = GuideVariant.CN
            uiState.activePanel = PanelType.NONE
            uiState.guideVisible = true
            return "panel=guide (forced CN/siliconflow)"
        }
        "guide_intl", "guide_en" -> {
            uiState.guideVariantOverride = GuideVariant.INTL
            uiState.activePanel = PanelType.NONE
            uiState.guideVisible = true
            return "panel=guide (forced INTL/openrouter)"
        }
    }
    val panel = when (arg?.lowercase()) {
        null, "none", "off", "close" -> PanelType.NONE
        "models", "model" -> PanelType.MODELS
        "animations", "animation" -> PanelType.ANIMATIONS
        "expressions", "expression" -> PanelType.EXPRESSIONS
        "scenes", "scene" -> PanelType.SCENES
        "cards", "card" -> PanelType.CARDS
        "settings" -> PanelType.SETTINGS
        else -> throw IllegalArgumentException(
            "open_panel expects none|models|animations|expressions|scenes|cards|settings|guide|guide_cn|guide_intl, got '$arg'"
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
