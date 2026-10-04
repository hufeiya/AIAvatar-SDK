package com.neethu.aiavatar_sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.neethu.aiadapter.model.AsrConfig
import com.neethu.aiadapter.openai.OpenAiCompatibleAsrAdapter
import com.neethu.aiavatar_sdk.ui.SettingsScreen
import com.neethu.aiavatar_sdk.ui.theme.AIAvatarSDKTheme
import com.neethu.aiavatar_sdk.video.UserCameraTracker
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarController
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.AvatarState
import com.neethu.corelib.AvatarView
import com.neethu.corelib.CameraShot
import com.neethu.corelib.rememberAvatarController
import com.neethu.orchestrator.card.CharacterCard
import com.neethu.orchestrator.card.CharacterCardStore
import com.neethu.orchestrator.card.SystemPromptAssembler
import com.neethu.orchestrator.card.spokenGreeting
import com.neethu.orchestrator.face.GazeMode
import com.neethu.orchestrator.gesture.ActionEntry
import com.neethu.orchestrator.history.ConversationDatabase
import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.session.ConversationPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.roundToInt

/**
 * Demo activity. Beyond the touch UI it exposes an AI-native debug interface:
 * any launch of this activity carrying `ai_cmd` extras (see [AiDebugCommand])
 * is queued and executed once the UI is ready. Commands can arrive at cold
 * start (via [onCreate]) or while the activity is already running (via
 * [onNewIntent], enabled by `launchMode="singleTask"` in the manifest).
 *
 * Results are logged under [AI_LOG_TAG] for the agent to read back:
 * ```
 * adb shell am start -n com.neethu.aiavatar_sdk/.MainActivity --es ai_cmd state
 * adb logcat -d -s AIDebug
 * ```
 */
class MainActivity : ComponentActivity() {

    /** Intent-issued commands, consumed by [DemoScreen] once composed. */
    private val aiCommands = Channel<AiDebugCommand>(Channel.UNLIMITED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIAvatarSDKTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DemoScreen(
                        modifier = Modifier.padding(innerPadding),
                        uiState = remember { DemoUiState(applicationContext) },
                        aiCommands = aiCommands,
                    )
                }
            }
        }
        enqueueAiCommands(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        enqueueAiCommands(intent)
    }

    private fun enqueueAiCommands(intent: Intent?) {
        val extras = intent?.extras ?: return
        val command = parseAiDebugCommand(extras.toAiDebugMap())
        if (command == null) {
            if (extras.keySet().any { it in AI_EXTRA_KEYS }) {
                Log.w(AI_LOG_TAG, "Intent ignored: missing or empty '$EXTRA_AI_CMD' string extra")
            }
            return
        }
        aiCommands.trySend(command)
    }

    private fun Bundle.toAiDebugMap(): Map<String, Any?> =
        keySet().associateWith { get(it) }
}

/** Which panel is currently shown */
internal enum class PanelType { NONE, MODELS, ANIMATIONS, EXPRESSIONS, SCENES, CARDS, SETTINGS }

// ── 设置持久化（SharedPreferences）─────────────────────────────────────

internal const val PREFS_NAME = "demo_settings"
private const val KEY_USE_EXTERNAL_ANIMATIONS = "useExternalAnimations"
private const val KEY_AI_CONTEXT_ID = "ai_context_id"
/** 当前上下文创建时的大模型身份签名（[llmIdentitySignature]）；换模型即轮换上下文。 */
private const val KEY_AI_CONTEXT_LLM_SIG = "ai_context_llm_sig"
private const val KEY_AI_INPUT_MODE = "ai_input_mode"
private const val KEY_VIDEO_PIP_X = "ai_video_pip_x"
private const val KEY_VIDEO_PIP_Y = "ai_video_pip_y"
/** 预置卡导入映射（assetPath→已落盘文件名，[PresetCardImport]）。 */
private const val KEY_AI_PRESET_CARDS = "ai_preset_cards"
/** 人物卡提示词人工编辑覆盖（fileName→文本，[CardPromptOverrides]）。 */
private const val KEY_AI_CARD_PROMPT_OVERRIDES = "ai_card_prompt_overrides"

/** 新上下文的随机 id（UUID；ai_cmd select_context 支持前缀匹配）。 */
private fun newContextId(): String = java.util.UUID.randomUUID().toString()

/**
 * 卡片人设应用到会话：卡片默认人设 + 设置页人工编辑覆盖（session 重建也必须
 * 重放覆盖，否则编辑会被 assemble 冲掉）。预置 grid、导入列表、adb 导入共用。
 */
private fun applyCardToSession(
    session: AvatarSession,
    entry: CharacterCardStore.Entry,
    overrideFor: (String) -> String?,
) {
    session.setCharacterCard(entry.card)
    overrideFor(entry.fileName)?.takeIf { it.isNotEmpty() }?.let { session.systemPrompt = it }
}

/** 读取枚举设置项；名字失效（如改过枚举名）时回落到默认值。 */
private inline fun <reified T : Enum<T>> SharedPreferences.enumValue(
    key: String,
    default: T,
): T = getString(key, null)?.let { name ->
    runCatching { enumValueOf<T>(name) }.getOrNull()
} ?: default

/** 从 SharedPreferences 恢复渲染设置；未保存过的键回落到默认值。 */
private fun SharedPreferences.loadRenderSettings(): AvatarRenderSettings {
    val defaults = AvatarRenderSettings()
    return AvatarRenderSettings(
        iblIntensity = getFloat("render_iblIntensity", defaults.iblIntensity),
        iblRotationDegrees = getFloat("render_iblRotationDegrees", defaults.iblRotationDegrees),
        lightingRig = enumValue("render_lightingRig", defaults.lightingRig),
        shadowMapSize = getInt("render_shadowMapSize", defaults.shadowMapSize),
        softShadows = getBoolean("render_softShadows", defaults.softShadows),
        contactShadows = getBoolean("render_contactShadows", defaults.contactShadows),
        ambientOcclusion = enumValue("render_ambientOcclusion", defaults.ambientOcclusion),
        toneMapping = enumValue("render_toneMapping", defaults.toneMapping),
        bloomEnabled = getBoolean("render_bloomEnabled", defaults.bloomEnabled),
        bloomStrength = getFloat("render_bloomStrength", defaults.bloomStrength),
        antiAliasing = enumValue("render_antiAliasing", defaults.antiAliasing),
        depthOfFieldEnabled = getBoolean("render_depthOfFieldEnabled", defaults.depthOfFieldEnabled),
        enhanceMaterials = getBoolean("render_enhanceMaterials", defaults.enhanceMaterials),
        showFps = getBoolean("render_showFps", defaults.showFps),
    )
}

/** 把渲染设置的全部字段写入 SharedPreferences。 */
private fun SharedPreferences.saveRenderSettings(s: AvatarRenderSettings) {
    edit()
        .putFloat("render_iblIntensity", s.iblIntensity)
        .putFloat("render_iblRotationDegrees", s.iblRotationDegrees)
        .putString("render_lightingRig", s.lightingRig.name)
        .putInt("render_shadowMapSize", s.shadowMapSize)
        .putBoolean("render_softShadows", s.softShadows)
        .putBoolean("render_contactShadows", s.contactShadows)
        .putString("render_ambientOcclusion", s.ambientOcclusion.name)
        .putString("render_toneMapping", s.toneMapping.name)
        .putBoolean("render_bloomEnabled", s.bloomEnabled)
        .putFloat("render_bloomStrength", s.bloomStrength)
        .putString("render_antiAliasing", s.antiAliasing.name)
        .putBoolean("render_depthOfFieldEnabled", s.depthOfFieldEnabled)
        .putBoolean("render_enhanceMaterials", s.enhanceMaterials)
        .putBoolean("render_showFps", s.showFps)
        .apply()
}

/**
 * UI + avatar-selection state for [DemoScreen], hoisted out of the composable
 * so the AI debug executor ([executeAiCommand]) can drive the same switches
 * the buttons do.
 */
internal class DemoUiState(context: Context) {

    /** 设置持久化：动画来源 + 全部渲染设置。 */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val modelFiles: List<String> = listAssets(context, "vrms") {
        it.endsWith(".glb") || it.endsWith(".vrm")
    }
    val sceneFiles: List<String> = listAssets(context, "scene") {
        it.endsWith(".glb")
    }

    /**
     * 动画来源：`false` = APK 内置（assets/animations，默认），
     * `true` = 手机外存（App data 目录，含分类子文件夹）。选择会持久化。
     */
    var useExternalAnimations by mutableStateOf(
        prefs.getBoolean(KEY_USE_EXTERNAL_ANIMATIONS, false)
    )
        private set

    /** 当前来源下可选的动画，条目为相对路径（外置模式含子文件夹）。 */
    var animationFiles: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * 当前待机动作（assets 相对路径或 "ext:<绝对路径>"），LLM 手势播完/手动
     * 停止后回到它（§7.10）；null = 回 rest pose。面板长按或 ai_cmd set_idle
     * 切换，持久化到 demo_settings。
     */
    var idleAnimation by mutableStateOf<String?>(prefs.getString(KEY_IDLE_ANIMATION, null))

    var selectedModel by mutableStateOf("SK_Sun_PERFORMANCE_jacket_off_1024.vrm")
    var selectedAnimation by mutableStateOf<String?>(null)
    var selectedExpression by mutableStateOf<String?>(null)
    var selectedScene: String? by mutableStateOf(sceneFiles.firstOrNull())
    var activePanel by mutableStateOf(PanelType.NONE)
    var isDragMode by mutableStateOf(false)

    /**
     * 对话输入三模式（任务 4，互斥）：手动点击=完整 UI（全部按钮可见），
     * 打字输入/语音模式=隐藏所有界面按钮只留输入条/按住说话。持久化。
     */
    var inputMode by mutableStateOf(prefs.enumValue(KEY_AI_INPUT_MODE, InputMode.MANUAL))
        private set

    /**
     * 按钮显隐开关（模式下拉框旁）：手动点击模式默认显示，进入打字/语音
     * 模式自动隐藏；任何模式下都可临时翻转（语音模式里偶尔也要换模型/
     * 开设置）。刻意不持久化——每次切模式/冷启动都回到该模式的默认值。
     */
    var buttonsVisible by mutableStateOf(inputMode == InputMode.MANUAL)
        private set

    /** 翻转按钮显隐；隐藏时顺手收起面板（面板入口本身也被藏了）。 */
    fun toggleButtons() {
        buttonsVisible = !buttonsVisible
        if (!buttonsVisible) activePanel = PanelType.NONE
    }

    /** 语音输入配置（ASR 模型/直接发送），与 AI 会话身份解耦，改它不重建会话。 */
    var voicePrefs by mutableStateOf(prefs.loadVoicePrefs())
        private set

    /** 切换输入模式并持久化；按钮显隐回到该模式默认（手动点击=显示，其余=隐藏）。 */
    fun switchInputMode(mode: InputMode) {
        if (mode == inputMode) return
        inputMode = mode
        buttonsVisible = mode == InputMode.MANUAL
        if (mode != InputMode.MANUAL) activePanel = PanelType.NONE
        prefs.edit().putString(KEY_AI_INPUT_MODE, mode.name).apply()
    }

    /** 更新语音输入配置并持久化。 */
    fun updateVoicePrefs(p: VoicePrefs) {
        voicePrefs = p
        prefs.saveVoicePrefs(p)
    }

    // ── 视频模式（任务 6）────────────────────────────────────────────────

    /**
     * 视频模式准入门控：需求是"只能多模态可以输入图片的大模型才能开启"。
     * 返回 null = 可进入；否则返回给用户的拒绝原因（错误条展示）。
     */
    fun videoModeBlockReason(): String? = when {
        !aiPrefs.isConfigured -> "先在 ⚙️ 设置里配置 AI 服务，再开视频模式"
        !isVisionLlm(aiPrefs.provider, aiPrefs.llmModel) ->
            "当前大模型「${resolveLlmModel(aiPrefs.provider, aiPrefs.llmModel)}」不支持图片输入，" +
                "视频模式需要多模态模型（⚙️ 设置里切换：硅基流动选 Qwen3.8-27B / Qwen3-VL，火山默认模型即可）"
        else -> null
    }

    /**
     * PiP 小窗位置（相对屏幕左上角的 0..1 分数），拖动实时更新并持久化——
     * 重启回到上次放的位置。
     */
    var videoPipOffset by mutableStateOf(
        Offset(
            prefs.getFloat(KEY_VIDEO_PIP_X, 0.58f),
            prefs.getFloat(KEY_VIDEO_PIP_Y, 0.12f),
        )
    )
        private set

    fun updateVideoPipOffset(f: Offset) {
        val x = f.x.coerceIn(0f, 1f)
        val y = f.y.coerceIn(0f, 1f)
        videoPipOffset = Offset(x, y)
        prefs.edit().putFloat(KEY_VIDEO_PIP_X, x).putFloat(KEY_VIDEO_PIP_Y, y).apply()
    }

    /** 渲染设置，初始值来自上一次会话的持久化。 */
    var renderSettings by mutableStateOf(prefs.loadRenderSettings())

    /** AI 对话配置（端点/Key/模型/音色），持久化到 demo_settings。 */
    var aiPrefs by mutableStateOf(prefs.loadAiPrefs())
        private set

    /** 人物卡库：bytes 落 filesDir/cards，索引/激活卡持久化到 demo_settings。 */
    val cardLibrary = CardLibrary(context)

    // ── 对话上下文（任务 3：Room 持久化的多套对话历史）────────────────────

    /**
     * 当前上下文 id。会话身份 = AI 配置 + 上下文：新建/切换上下文即重建
     * AvatarSession（换一条 RoomConversationStore），旧上下文的历史保留在
     * 库里可随时切回。id 持久化，重启后继续同一上下文。
     */
    var contextId by mutableStateOf(prefs.getString(KEY_AI_CONTEXT_ID, null) ?: newContextId())
        private set

    /**
     * 当前上下文对应的大模型身份（[llmIdentitySignature]）。与 contextId 一起
     * 持久化：设置页换服务商/模型时签名变化 → 同步新开上下文（协议目录随模型
     * 实现可能不同，旧对话里的标签对新模型不再可靠）。升级首次启动无记录时
     * 只采纳当前值不轮换（无从判断上次用的什么模型）。
     */
    var contextLlmSig: String? = null
        private set

    init {
        if (prefs.getString(KEY_AI_CONTEXT_ID, null) == null) {
            prefs.edit().putString(KEY_AI_CONTEXT_ID, contextId).apply()
        }
        contextLlmSig = prefs.getString(KEY_AI_CONTEXT_LLM_SIG, null) ?: llmIdentitySignature(aiPrefs)
        prefs.edit().putString(KEY_AI_CONTEXT_LLM_SIG, contextLlmSig).apply()
    }

    /** 切换到已有上下文并持久化；会话由 produceState 依 contextId 重建。 */
    fun switchContext(id: String) {
        contextId = id
        prefs.edit().putString(KEY_AI_CONTEXT_ID, id).apply()
    }

    /** 新建上下文（随机 id）：空历史起步，旧上下文不动。 */
    fun newContext() {
        switchContext(newContextId())
    }

    /** 已导入的卡片（镜像 cardLibrary.cards，驱动 UI 重组）。 */
    var cards by mutableStateOf(cardLibrary.cards)
        private set

    /** 当前激活卡片的文件名；null = 未激活。 */
    var activeCardFile by mutableStateOf(cardLibrary.activeFile)
        private set

    /**
     * 激活卡片时 AI 会话尚不可用（未配置服务）则记下文件名，会话就绪后由
     * DemoScreen 补播一次开场白；仅内存，不跨进程。
     */
    var pendingGreetingFile: String? = null

    init {
        refreshAnimationFiles(context)
    }

    /**
     * 切换动画来源并持久化；列表随后由 [refreshAnimationFiles] 重新扫描。
     */
    fun setAnimationSource(context: Context, external: Boolean) {
        useExternalAnimations = external
        prefs.edit().putBoolean(KEY_USE_EXTERNAL_ANIMATIONS, external).apply()
        refreshAnimationFiles(context)
    }

    /** 更新渲染设置并持久化全部字段。 */
    fun updateRenderSettings(new: AvatarRenderSettings) {
        renderSettings = new
        prefs.saveRenderSettings(new)
    }

    /**
     * 更新 AI 对话配置并持久化。大模型身份（服务商/模型）变化时同步新开
     * 上下文：协议目录按所配模型钉住（AvatarSession 每上下文只注入一次），
     * 换模型后旧历史里的标签对新模型不再可靠，历史跟着换新。语音/Key 等
     * 不影响模型能力的字段变化不轮换，对话延续。
     */
    fun updateAiPrefs(p: AiChatPrefs) {
        val oldSig = contextLlmSig
        aiPrefs = p
        prefs.saveAiPrefs(p)
        val newSig = llmIdentitySignature(p)
        if (oldSig != null && newSig != oldSig) newContext()
        contextLlmSig = newSig
        prefs.edit().putString(KEY_AI_CONTEXT_LLM_SIG, newSig).apply()
    }

    // ── 人物卡 ────────────────────────────────────────────────────────────

    /** 按文件名查卡片条目。 */
    fun cardByFile(fileName: String?): CharacterCardStore.Entry? =
        cards.firstOrNull { it.fileName == fileName }

    // ── 预置卡（assets/cards，点选即导入激活）────────────────────────────

    /** APK 内置的预置卡（官方 SillyTavern 卡 + 原创中文卡）。 */
    var presetCards: List<PresetCard> = PresetCardLibrary(context).load()
        private set

    /** 预置卡导入映射（assetPath→落盘文件名）的持久化 JSON。 */
    private var presetImportMapJson: String? = prefs.getString(KEY_AI_PRESET_CARDS, null)

    /** 按落盘文件名反查预置卡 asset 路径；null = 该卡不是从预置 grid 导入的。 */
    fun presetAssetByFile(fileName: String?): String? =
        PresetCardImport.reverseGet(presetImportMapJson, fileName)

    /** 从 SAF Uri 导入；null = 不可读或解析失败。成功后刷新列表。 */
    fun importCard(context: Context, uri: Uri): CharacterCardStore.Entry? {
        val entry = cardLibrary.import(context, uri) ?: return null
        cards = cardLibrary.cards
        return entry
    }

    /** 字节级导入（adb 调试命令用），与 UI 导入同一条落盘/索引路径。 */
    fun importCardBytes(bytes: ByteArray): CharacterCardStore.Entry? {
        val entry = cardLibrary.importBytes(bytes) ?: return null
        cards = cardLibrary.cards
        return entry
    }

    /** 激活/取消激活（null）并持久化；会话内的系统提示由 DemoScreen 处理。 */
    fun setActiveCard(fileName: String?) {
        activeCardFile = fileName
        cardLibrary.setActive(fileName)
        if (fileName == null) pendingGreetingFile = null
    }

    /** 删除卡片；若删的是激活卡同时清激活态。 */
    fun deleteCard(fileName: String) {
        cardLibrary.delete(fileName)
        cards = cardLibrary.cards
        if (activeCardFile == fileName) {
            activeCardFile = null
            pendingGreetingFile = null
        }
    }

    /**
     * 点选预置卡：先查导入映射复用已落盘的文件（用户删过则重新导入），
     * 没有就从 assets 读 bytes 走同一条 [importCardBytes] 落盘路径并记映射。
     * 返回 null = assets 不可读或解析失败。
     */
    fun importPresetCard(context: Context, preset: PresetCard): CharacterCardStore.Entry? {
        val (existing, newMap) = PresetCardImport.resolve(
            presetImportMapJson, preset.assetPath, cards.map { it.fileName }.toSet(),
        )
        presetImportMapJson = newMap
        prefs.edit().putString(KEY_AI_PRESET_CARDS, newMap).apply()
        existing?.let { return cardByFile(it) }
        val bytes = PresetCardLibrary(context).readBytes(preset.assetPath) ?: return null
        val entry = importCardBytes(bytes) ?: return null
        presetImportMapJson = PresetCardImport.record(presetImportMapJson, preset.assetPath, entry.fileName)
        prefs.edit().putString(KEY_AI_PRESET_CARDS, presetImportMapJson).apply()
        return entry
    }

    // ── 人物卡提示词的人工编辑覆盖 ────────────────────────────────────────

    /** 覆盖映射的持久化 JSON（fileName→自定义提示词）。 */
    private var cardPromptOverridesJson: String? = prefs.getString(KEY_AI_CARD_PROMPT_OVERRIDES, null)

    /** 激活卡的提示词覆盖；null = 未编辑，用卡片默认人设。 */
    fun cardPromptOverride(fileName: String?): String? =
        CardPromptOverrides.get(cardPromptOverridesJson, fileName)

    /** 保存/清除（传 null）激活卡的提示词覆盖并持久化。 */
    fun setCardPromptOverride(fileName: String, text: String?) {
        cardPromptOverridesJson = CardPromptOverrides.set(cardPromptOverridesJson, fileName, text)
        prefs.edit().putString(KEY_AI_CARD_PROMPT_OVERRIDES, cardPromptOverridesJson).apply()
    }

    /** 外置动画根目录：App 外部存储私有区（`/sdcard/Android/data/<pkg>/files`）。 */
    fun externalAnimationsRoot(context: Context): File? = context.getExternalFilesDir(null)

    /** 按当前动画来源重新扫描可选动画列表。 */
    fun refreshAnimationFiles(context: Context) {
        animationFiles = if (useExternalAnimations) {
            val root = externalAnimationsRoot(context)
            root?.walkTopDown()
                ?.filter { it.isFile && it.extension.equals("vrma", ignoreCase = true) }
                ?.map { it.relativeTo(root).path }
                ?.sorted()
                ?.toList()
                ?: emptyList()
        } else {
            listAssetsRecursive(context, "animations") { it.endsWith(".vrma") }
                .map { it.removePrefix("animations/") }
        }
    }

    /** 内置动画库全量相对路径（LLM 动作目录与待机选型的来源，§7.2/§7.10）。 */
    fun assetAnimationPaths(context: Context): List<String> =
        listAssetsRecursive(context, "animations") { it.endsWith(".vrma") }
            .map { it.removePrefix("animations/") }

    /** 外置动画库全量绝对路径（追加进 LLM 动作目录用）。 */
    fun externalAnimationAbsolutePaths(context: Context): List<String> {
        val root = externalAnimationsRoot(context) ?: return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.extension.equals("vrma", ignoreCase = true) }
            .map { it.absolutePath }
            .sorted()
            .toList()
    }

    /**
     * 按当前动画来源加载动画；[relativePath] 为 [animationFiles] 中的条目。
     */
    fun loadAnimation(context: Context, controller: AvatarController, relativePath: String): Boolean =
        if (useExternalAnimations) {
            val root = externalAnimationsRoot(context) ?: return false
            controller.loadVrmaAnimationFromFile(File(root, relativePath).path)
        } else {
            controller.loadVrmaAnimation("animations/$relativePath")
        }

    companion object {
        /** Preset expression names (used as fallback if model has none). */
        val presetExpressions = listOf(
            "happy", "sad", "angry", "surprised", "relaxed", "blink",
            "blinkLeft", "blinkRight", "aa", "ih", "ou", "ee", "oh", "neutral"
        )

        /** Model-parsed expressions if available, otherwise the presets. */
        fun resolveExpressions(state: AvatarState): List<String> {
            val modelExpressions = (state as? AvatarState.Ready)?.expressions ?: emptyList()
            return modelExpressions.ifEmpty { presetExpressions }
        }

        private fun listAssets(
            context: Context,
            path: String,
            filter: (String) -> Boolean,
        ): List<String> = try {
            context.assets.list(path)?.filter(filter)?.sorted() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        /** 递归列出 assets/[path] 下（含子文件夹）满足 [filter] 的文件，返回相对路径。 */
        private fun listAssetsRecursive(
            context: Context,
            path: String,
            filter: (String) -> Boolean,
        ): List<String> = try {
            val am = context.assets
            val found = mutableListOf<String>()
            fun walk(dir: String) {
                for (name in am.list(dir) ?: emptyArray()) {
                    val child = "$dir/$name"
                    if (!am.list(child).isNullOrEmpty()) walk(child)
                    else if (filter(name)) found += child
                }
            }
            walk(path)
            found.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** ai_cmd set_provider / set_tts_provider 的服务商参数解析（含常用别名）。 */
private fun parseProviderArg(arg: String?): AiProvider = when (arg?.lowercase()) {
    "siliconflow", "sf", "silicon" -> AiProvider.SILICONFLOW
    "volcano", "volc", "ark", "bytedance" -> AiProvider.VOLCANO
    else -> throw IllegalArgumentException(
        "expects siliconflow|volcano, got '$arg'"
    )
}

@Composable
private fun DemoScreen(
    modifier: Modifier = Modifier,
    uiState: DemoUiState,
    aiCommands: Channel<AiDebugCommand>,
) {
    val context = LocalContext.current
    val controller = rememberAvatarController()
    val state by controller.state.collectAsState()
    val fps by controller.fps.collectAsState()

    // Camera-shot cycling: null = no shot applied yet (first tap → CLOSE_UP).
    var currentShot by remember { mutableStateOf<CameraShot?>(null) }
    var shotLabel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shotLabel) {
        if (shotLabel != null) {
            delay(1600)
            shotLabel = null
        }
    }
    // 最近一次 LLM 手势的预计结束时刻（ActionStarted 时按动画时长推算）——
    // 视频模式回合结束回面部特写时要等它播完（需求 6）
    var gestureEndsAtMs by remember { mutableStateOf(0L) }

    val expressionList = remember(state) {
        DemoUiState.resolveExpressions(state)
    }

    // Load the selected model whenever it changes
    LaunchedEffect(uiState.selectedModel) {
        uiState.selectedExpression = null
        controller.clearAllExpressions()
        controller.loadModel("vrms/${uiState.selectedModel}")
    }

    // ── 待机动作：直接挂到渲染控制器，与 AI 会话解耦 ──────────────────────
    // 模型每次（重）加载后按持久化选择（否则内置优先级 Arms Down 优先）挂
    // idle；引擎在无动作播放时会立即进入待机循环，冷启动不再是 T-pose，
    // 未配置 AI 服务也同样有待机。同源重复挂载由 renderer 去重（不重解析）。
    val applyIdle: (ActionEntry?) -> Unit = { entry ->
        val assetPath = entry?.assetPath
        val filePath = entry?.filePath
        when {
            entry == null -> controller.clearVrmaIdleAnimation()
            assetPath != null -> controller.setVrmaIdleAnimation(assetPath)
            filePath != null -> controller.setVrmaIdleAnimationFromFile(filePath)
        }
    }
    LaunchedEffect(state) {
        applyIdle(
            resolveIdleAction(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_IDLE_ANIMATION, null),
                uiState.assetAnimationPaths(context),
            )
        )
    }

    // Load the selected scene whenever it changes
    LaunchedEffect(uiState.selectedScene) {
        uiState.selectedScene?.let { scene ->
            controller.loadScene("scene/$scene")
        }
    }

    // AI debug interface: the executor loop lives below, after the AI chat
    // state it hooks into, so `send_chat` can drive the same session as the UI.

    // Apply render settings to the controller; material enhancements cannot be
    // reverted in place (material params have no read-back), so turning them
    // off reloads the pristine model.
    val applyRenderSettings: (AvatarRenderSettings) -> Unit = { new ->
        val materialReverted = uiState.renderSettings.enhanceMaterials && !new.enhanceMaterials
        uiState.updateRenderSettings(new)
        controller.updateRenderSettings(new)
        if (materialReverted) {
            uiState.selectedExpression = null
            controller.loadModel("vrms/${uiState.selectedModel}", forceReload = true)
        }
    }

    // ── AI 对话：装配 AvatarSession 并订阅其状态 ──────────────────────────
    val scope = rememberCoroutineScope()
    val aiChat = remember { AiChatController(scope, controller, context.applicationContext) }

    // Room 会话库（任务 3）：设置页「上下文」类别的数据源
    val convoDb = remember { ConversationDatabase.getInstance(context) }
    var contextListVersion by remember { mutableStateOf(0) }
    val contextList by produceState<List<ConversationContextSummary>>(
        initialValue = emptyList(), contextListVersion,
    ) {
        value = withContext(Dispatchers.IO) { loadContextSummaries(convoDb) }
    }
    // 打开设置页时刷新一次（消息数/最近使用时间随对话变化）
    LaunchedEffect(uiState.activePanel) {
        if (uiState.activePanel == PanelType.SETTINGS) contextListVersion++
    }

    // 会话身份 = AI 配置 + 上下文 id：任一变化（含新建/切换上下文）即重建
    val session by produceState<AvatarSession?>(
        initialValue = null, state, uiState.aiPrefs, uiState.useExternalAnimations,
        uiState.contextId,
    ) {
        // 设置页逐字符提交配置；不等输入停稳就 ensure 会把会话每个按键重建一次，
        // 正在播放/合成的回合被反复杀掉（表现为"还没输完就不出声了"）。
        delay(800)
        value = aiChat.ensure(
            prefs = uiState.aiPrefs,
            ready = state is AvatarState.Ready,
            contextId = uiState.contextId,
            characterId = uiState.activeCardFile,
        )?.also { s ->
            // 动作目录 = 内置库全量扫描（分类子文件夹 → tag），外置模式追加
            // 外置库。待机不在这里挂——它属于渲染控制器而非 AI 会话（见上方
            // LaunchedEffect(state) 的 applyIdle），未配置 AI 也有待机。
            val assetPaths = uiState.assetAnimationPaths(context)
            val externalFiles =
                if (uiState.useExternalAnimations) uiState.externalAnimationAbsolutePaths(context)
                else emptyList()
            s.actionCatalog = buildLlmActionCatalog(assetPaths, externalFiles)
        }
    }

    var chatPhase by remember { mutableStateOf(ConversationPhase.IDLE) }
    // 对话字幕面板：用户消息在上、虚拟人回复在下，可滚动回看、自动贴底。
    // 所有发送入口（打字/按住/自由说话/ai_cmd）都经 [pushUserLine] 记录。
    val chatLines = remember { mutableStateListOf<ChatLine>() }
    fun pushUserLine(text: String) {
        val next = ChatTranscript.cap(ChatTranscript.append(chatLines.toList(), ChatRole.USER, text))
        chatLines.clear()
        chatLines.addAll(next)
    }
    var chatError by remember { mutableStateOf<String?>(null) }

    // 切换/新建上下文：新会话的历史来自另一条 Room 记录，旧字幕一并清掉
    LaunchedEffect(uiState.contextId) {
        chatLines.clear()
        chatError = null
    }

    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        // 会话（重）建后让激活的人物卡重新生效（含提示词人工编辑覆盖）；
        // 激活时若 AI 尚未配置，pendingGreetingFile 记着开场白意图，这里补播一次。
        // 补播 = 延迟激活的真正生效时刻，此刻要补上 activateEntry 里跳过的
        // clearHistory（否则旧上下文的历史泄漏进新角色——真机踩过：AI 未就绪
        // 时切卡，Room 里上一个角色的对话被带进新卡的请求）。常规 session
        // 重建（配置微调/换音色）不清历史，只有 pendingGreeting 被消费才算切卡。
        uiState.activeCardFile?.let { file ->
            uiState.cardByFile(file)?.let { entry ->
                val deferredActivation = uiState.pendingGreetingFile == file
                if (deferredActivation) s.clearHistory()
                applyCardToSession(s, entry) { fileName -> uiState.cardPromptOverride(fileName) }
                if (deferredActivation) {
                    uiState.pendingGreetingFile = null
                    val greeting = entry.card.spokenGreeting()
                    if (greeting.isNotEmpty()) launch { runCatching { s.speak(greeting) } }
                }
            }
        }
        launch { s.phase.collect { chatPhase = it } }
        launch {
            // 排查问答等待时长:实时播放事件统一带 [InfoStreamDectect] 前缀
            val chatLog: (String) -> Unit = { msg -> Log.i(AI_LOG_TAG, "[InfoStreamDectect] chat: $msg") }
            s.events.collect { ev ->
                when (ev) {
                    is AvatarEvent.SentenceQueued ->
                        chatLog("SentenceQueued #${ev.sequence} \"${ev.text}\"")
                    is AvatarEvent.SentenceStarted -> {
                        // 开播即上字幕：同一回合逐句开播，并入最后一条 AVATAR 行
                        val next = ChatTranscript.append(chatLines.toList(), ChatRole.AVATAR, ev.text)
                        chatLines.clear()
                        chatLines.addAll(next)
                        chatLog("SentenceStarted #${ev.sequence}")
                    }
                    is AvatarEvent.SentenceEnded ->
                        chatLog("SentenceEnded #${ev.sequence}")
                    is AvatarEvent.SentenceFailed -> {
                        // 上错误条：静默失败的句子只会让人以为"没出声/崩了"
                        chatError = "第 ${ev.sequence + 1} 句语音合成失败：${ev.message}"
                        Log.w(AI_LOG_TAG, "[InfoStreamDectect] chat: SentenceFailed #${ev.sequence}: ${ev.message}")
                    }
                    is AvatarEvent.EmotionChanged ->
                        chatLog("EmotionChanged ${ev.cue.name} intensity=${ev.cue.intensity}")
                    is AvatarEvent.ActionStarted -> {
                        chatLog("ActionStarted ${ev.tag} (${ev.label})")
                        // 手势刚加载开播,动画时长即到点时刻(视频模式回特写要等它)
                        gestureEndsAtMs = System.currentTimeMillis() +
                            (controller.getVrmaAnimationDuration() * 1000).toLong() + 400
                    }
                    is AvatarEvent.CameraChanged -> {
                        // 与手动视角 FAB 共用同一枚徽标，LLM 切机位时同步显示
                        currentShot = ev.shot
                        shotLabel = ev.shot.label
                        chatLog("CameraChanged ${ev.shot.name}")
                    }
                    is AvatarEvent.TurnCompleted -> {
                        chatLog("TurnCompleted subtitleLen=${chatLines.lastOrNull { it.role == ChatRole.AVATAR }?.text?.length ?: 0}")
                        // 需求 6:视频模式回合结束(语音+手势都到点)自动回面部特写
                        if (uiState.inputMode == InputMode.VIDEO) {
                            launch {
                                val waitMs = gestureEndsAtMs - System.currentTimeMillis()
                                if (waitMs > 0) delay(waitMs)
                                // 等待期间用户可能已切走视频模式,复查再动镜头
                                if (uiState.inputMode == InputMode.VIDEO) {
                                    controller.setCameraShot(CameraShot.CLOSE_UP)
                                    currentShot = CameraShot.CLOSE_UP
                                    shotLabel = CameraShot.CLOSE_UP.label
                                }
                            }
                        }
                    }
                    is AvatarEvent.TurnFailed -> {
                        chatError = ev.error.message ?: "对话失败"
                        Log.e(AI_LOG_TAG, "[InfoStreamDectect] chat: TurnFailed: ${ev.error.message}")
                    }
                    is AvatarEvent.PlaybackInterrupted ->
                        chatLog("PlaybackInterrupted")
                }
            }
        }
    }

    LaunchedEffect(chatError) {
        if (chatError != null) {
            delay(6000)
            chatError = null
        }
    }

    // ── 语音输入（任务 4）：按住说话 → MediaRecorder(m4a) → ASR → 发送/填入 ─
    val voiceRecorder = remember { VoiceRecorder(context) }
    var voiceRecording by remember { mutableStateOf(false) }
    var voiceRecognizing by remember { mutableStateOf(false) }

    /** autoSend=false 时的识别文本：经 AiChatBar 填入输入框待确认。 */
    var voicePrefill by remember { mutableStateOf<String?>(null) }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        micGranted = granted
        if (!granted) chatError = "需要麦克风权限才能语音输入"
    }

    // ── 视频模式（任务 6）：用户相机追踪 + PiP 小窗 + 发送附抓拍 ──────────
    val videoTracker = remember { UserCameraTracker(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var pipLensFront by remember { mutableStateOf(true) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) chatError = "需要相机权限才能视频通话（打字/按住说话仍可用，无抓拍与注视追踪）"
    }

    /**
     * 发送时附带的多模态抓拍（模式 A+B 合并语义：视频模式每轮都附缓存窗内
     * 最清晰一帧）。仅视频模式 + 相机权限 + 模型确实收图才产出。
     */
    fun videoSnapshotImages(): List<String> {
        if (uiState.inputMode != InputMode.VIDEO || !cameraGranted) return emptyList()
        if (!isVisionLlm(uiState.aiPrefs.provider, uiState.aiPrefs.llmModel)) return emptyList()
        return listOfNotNull(videoTracker.snapshotDataUrl())
    }

    // 进出视频模式：进入时补申请相机权限并默认面部特写机位；离开时停相机并把视线交回默认
    LaunchedEffect(uiState.inputMode) {
        if (uiState.inputMode == InputMode.VIDEO) {
            if (!cameraGranted) cameraPermission.launch(Manifest.permission.CAMERA)
            // 需求 1：视频模式默认面部特写（视频通话的取景）
            controller.setCameraShot(CameraShot.CLOSE_UP)
            currentShot = CameraShot.CLOSE_UP
            shotLabel = CameraShot.CLOSE_UP.label
        } else {
            videoTracker.stop()
            session?.faceDriver?.setGazeMode(GazeMode.CAMERA)
            if (session == null) controller.clearLookAtTarget()
        }
    }

    // 视线消费（~30Hz）：追踪器的新人脸观测 → FaceDriver POINT 注入缝；人脸
    // 离开画面 >1.5s 回退 CAMERA（看着镜头等用户回来）。无会话时直驱 controller。
    LaunchedEffect(uiState.inputMode, session) {
        if (uiState.inputMode != InputMode.VIDEO) return@LaunchedEffect
        var lastNanos = System.nanoTime()
        while (true) {
            delay(33)
            val now = System.nanoTime()
            val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(1f / 240f, 0.25f)
            lastNanos = now
            val point = videoTracker.pollGazeWorld(controller.getCameraLookAt(), dt)
            val fd = session?.faceDriver
            if (point != null) {
                if (fd != null) {
                    fd.setGazePoint(point[0], point[1], point[2])
                    fd.setGazeMode(GazeMode.POINT)
                } else {
                    controller.setLookAtTarget(point[0], point[1], point[2])
                }
            } else if (videoTracker.isActive && videoTracker.faceAgeMs() > 1500) {
                fd?.setGazeMode(GazeMode.CAMERA)
            }
        }
    }

    /**
     * 每次识别现建适配器读最新 prefs；缓存实例会在改端点后用旧地址。
     * ASR 目前仅硅基流动提供（OpenAI 兼容 /audio/transcriptions），key 固定
     * 复用硅基流动那份——大模型选火山时这里也要有硅基流动 key。
     */
    fun asrFor(): OpenAiCompatibleAsrAdapter {
        val sfKey = uiState.aiPrefs.apiKeyFor(AiProvider.SILICONFLOW)
        check(sfKey.isNotBlank()) { "语音识别走硅基流动：请先在 ⚙️ 设置里填硅基流动 API Key" }
        return OpenAiCompatibleAsrAdapter(AiProvider.SILICONFLOW.baseUrl, sfKey)
    }

    /** ASR 模型解析：端点恒为硅基流动（显式配置过的用显式值）。 */
    fun asrConfig(): AsrConfig = AsrConfig(
        model = resolveAsrModel(AiProvider.SILICONFLOW.baseUrl, uiState.voicePrefs.asrModel),
    )

    // ── 自由说话（连续聆听 + VAD 自动断句，按住/自由按钮切换）─────────────
    val freeSpeech = remember { FreeSpeechController(context) }
    var freeHearing by remember { mutableStateOf(false) }
    // 并发句串行:上一句还在 ASR 时新一句排队,防止识别结果乱序发送
    val freeAsrChain = remember { kotlinx.coroutines.sync.Mutex() }

    // 回调在采音线程触发,统一 post 回主协程操作 UI/会话
    freeSpeech.onBargeIn = { scope.launch { session?.interrupt() } }
    freeSpeech.onHearingChanged = { hearing -> freeHearing = hearing }
    freeSpeech.onUtterance = { wav ->
        scope.launch {
            freeAsrChain.withLock {
                voiceRecognizing = true
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        asrFor().transcribe(wav, mimeForFileName("utterance.wav"), asrConfig())
                    }
                }
                voiceRecognizing = false
                result.onSuccess { raw ->
                    val text = raw.trim()
                    when {
                        // 环境噪声切片常识别为空:静默忽略,不刷错误条
                        text.isEmpty() -> Unit
                        else -> {
                            pushUserLine(text)
                            session?.send(text, videoSnapshotImages())
                        }
                    }
                }.onFailure { chatError = "语音识别失败：${it.message}" }
            }
        }
    }
    freeSpeech.isAvatarSpeaking = { chatPhase == ConversationPhase.SPEAKING }

    val onToggleFreeTalk: () -> Unit = {
        val new = !uiState.voicePrefs.freeTalk
        uiState.updateVoicePrefs(uiState.voicePrefs.copy(freeTalk = new))
        when {
            new && !micGranted -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            new && uiState.aiPrefs.apiKeyFor(AiProvider.SILICONFLOW).isBlank() ->
                chatError = "自由说话需要硅基流动 API Key（语音识别用），先在 ⚙️ 设置里配置"
            new -> chatError = "自由说话已开启：直接开口，说完一句自动发送；虚拟人说话时大声即可打断"
        }
    }

    // 自由说话生命周期：语音/视频模式 + 开关开 + 麦克风权限 + 有 ASR Key 才跑
    LaunchedEffect(uiState.inputMode, uiState.voicePrefs.freeTalk, micGranted) {
        val want = micGranted && uiState.voicePrefs.freeTalk &&
            (uiState.inputMode == InputMode.VOICE || uiState.inputMode == InputMode.VIDEO) &&
            uiState.aiPrefs.apiKeyFor(AiProvider.SILICONFLOW).isNotBlank()
        if (want) {
            runCatching { freeSpeech.start(echoCancellation = uiState.inputMode == InputMode.VIDEO) }
                .onFailure { chatError = "自由说话启动失败：${it.message}" }
        } else {
            freeSpeech.stop()
        }
    }

    val onHoldStart: () -> Unit = {
        when {
            voiceRecording || voiceRecognizing -> Unit
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED ->
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else -> {
                // 半双工（对齐 AIRI 说话时抑制聆听）：按下的瞬间打断正在播的回复
                if (chatPhase == ConversationPhase.SPEAKING) session?.interrupt()
                try {
                    // 视频模式走 VOICE_COMMUNICATION 音源（平台硬件 AEC）：
                    // 扬声器里的虚拟人声音不再混进 ASR 录音
                    voiceRecorder.start(echoCancellation = uiState.inputMode == InputMode.VIDEO)
                    voiceRecording = true
                } catch (e: IllegalStateException) {
                    chatError = e.message ?: "录音启动失败"
                }
            }
        }
    }
    val onHoldEnd: () -> Unit = {
        if (voiceRecorder.isRecording) {
            voiceRecording = false
            val file = voiceRecorder.stop()
            if (file == null) {
                chatError = "没录到声音——按下后稍等半秒再开口"
            } else {
                voiceRecognizing = true
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            asrFor().transcribe(file.readBytes(), mimeForFileName(file.name), asrConfig())
                        }
                    }
                    file.delete()
                    voiceRecognizing = false
                    result.onSuccess { raw ->
                        val text = raw.trim()
                        if (text.isEmpty()) {
                            chatError = "未识别到语音内容，请靠近一点重试"
                        } else if (uiState.voicePrefs.autoSend) {
                            pushUserLine(text)
                            session?.send(text, videoSnapshotImages())
                        } else {
                            voicePrefill = text
                        }
                    }.onFailure {
                        chatError = "语音识别失败：${it.message}"
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            aiChat.shutdown()
            // 录音中离开组合（切模式/退出）不能留下一个占着麦克风的 MediaRecorder
            voiceRecorder.cancel()
            freeSpeech.stop()
        }
    }

    // ── 人物卡：激活/删除逻辑（卡片人设进会话统一走 [applyCardToSession]）────
    val applyOverrideFor: (String) -> String? = { fileName -> uiState.cardPromptOverride(fileName) }
    val deactivateCard: () -> Unit = {
        if (uiState.activeCardFile != null) {
            uiState.setActiveCard(null)
            session?.clearCharacterCard()
            session?.clearHistory()
        }
    }
    val activateEntry: (CharacterCardStore.Entry) -> Unit = { entry ->
        if (uiState.activeCardFile != entry.fileName) {
            uiState.setActiveCard(entry.fileName)
            val s = session
            when {
                s != null -> {
                    applyCardToSession(s, entry, applyOverrideFor)
                    s.clearHistory()
                    val greeting = entry.card.spokenGreeting()
                    if (greeting.isNotEmpty()) scope.launch { runCatching { s.speak(greeting) } }
                }
                else -> uiState.pendingGreetingFile = entry.fileName
            }
        }
    }
    // 导入列表保持旧行为：点已激活的卡 = 取消激活（预置 grid 的取消走空卡片）
    val activateCard: (CharacterCardStore.Entry) -> Unit = { entry ->
        if (uiState.activeCardFile == entry.fileName) deactivateCard() else activateEntry(entry)
    }
    // 预置卡点选：导入（或复用已导入）并激活；取消只能点空卡片
    val selectPresetCard: (PresetCard) -> Unit = { preset ->
        val entry = uiState.importPresetCard(context, preset)
        if (entry == null) {
            chatError = "预置卡不可读（${preset.assetPath}）"
        } else {
            activateEntry(entry)
        }
    }
    val deleteCard: (CharacterCardStore.Entry) -> Unit = { entry ->
        val wasActive = uiState.activeCardFile == entry.fileName
        uiState.deleteCard(entry.fileName)
        uiState.setCardPromptOverride(entry.fileName, null)
        if (wasActive) {
            session?.clearCharacterCard()
            session?.clearHistory()
        }
    }

    // 导入：SAF 选 PNG / JSON → parse → 落盘 → 索引 → 自动激活
    val cardPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val entry = uiState.importCard(context, uri)
            if (entry == null) {
                chatError = "无法解析所选文件为角色卡（支持 SillyTavern PNG / JSON）"
            } else {
                activateCard(entry)
            }
        }
    }

    // AI debug hooks: adb commands drive the same chat path as the chat bar
    val chatHooks = remember {
        AiChatDebugHooks(
            send = { text ->
                val s = session
                if (s != null) {
                    pushUserLine(text)
                    s.send(text, videoSnapshotImages())
                }
                s != null
            },
            interrupt = {
                val s = session
                if (s != null) s.interrupt()
                s != null
            },
            snapshot = {
                val p = uiState.aiPrefs
                buildString {
                    append("phase=$chatPhase subtitleLen=${chatLines.lastOrNull { it.role == ChatRole.AVATAR }?.text?.length ?: 0} error=${chatError ?: "none"} ")
                    append("llmProvider=${p.provider.name.lowercase()} llmModel=${resolveLlmModel(p.provider, p.llmModel)} ")
                    append("ttsProvider=${p.ttsProviderResolved.name.lowercase()}(same=${p.ttsSameProvider}) ")
                    append("ttsModel=${resolveTtsModel(p.ttsProviderResolved, p.ttsModel)} ")
                    append("voice=${resolveVoice(p.ttsProviderResolved, p.voice)} vision=${isVisionLlm(p.provider, p.llmModel)}")
                    if (uiState.inputMode == InputMode.VIDEO) append(" video=[${videoTracker.debugStatus()}]")
                    if (uiState.inputMode == InputMode.VOICE || uiState.inputMode == InputMode.VIDEO) {
                        append(" freeTalk=${uiState.voicePrefs.freeTalk}")
                        if (freeSpeech.running) append("(listening" + (if (freeHearing) ",hearing)" else ")"))
                        else append("(off)")
                    }
                }
            },
            importCard = { bytes ->
                val entry = uiState.importCardBytes(bytes)
                if (entry == null) null
                else {
                    activateCard(entry)
                    "imported ${entry.fileName} (name=${entry.card.name}, spec=${entry.card.spec}) and activated"
                }
            },
            cardSnapshot = {
                val file = uiState.activeCardFile
                if (file == null) "no active card (cards=${uiState.cards.size})"
                else {
                    val entry = uiState.cardByFile(file)
                    if (entry == null) "active=$file but the file is missing/corrupt"
                    else {
                        val prompt = session?.systemPrompt.orEmpty()
                        "active=$file name=${entry.card.name} spec=${entry.card.spec} " +
                            "version=${entry.card.characterVersion} firstMes=${entry.card.firstMessage.length}ch " +
                            "sysPromptChars=${prompt.length} sysPromptHead=${prompt.take(100)}"
                    }
                }
            },
            setIdle = { relativePath, external ->
                val root = uiState.externalAnimationsRoot(context)
                val entry = idleEntryFor(relativePath, external, root)
                    ?: return@AiChatDebugHooks "cannot resolve idle entry for $relativePath"
                val prefValue = idlePrefValueFor(relativePath, external, root)
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_IDLE_ANIMATION, prefValue).apply()
                uiState.idleAnimation = prefValue
                applyIdle(entry)
                "idle set to ${entry.label} (${entry.tag})"
            },
            clearIdle = {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_IDLE_ANIMATION).apply()
                uiState.idleAnimation = null
                applyIdle(null)
                "idle cleared (one-shots fall back to rest pose)"
            },
            contextsSnapshot = {
                // 实时查库而非 UI 快照：调试命令可能在任意时刻发出，
                // 期间对话刚写入的会话行必须可见
                val fresh = runBlocking {
                    withContext(Dispatchers.IO) { loadContextSummaries(convoDb) }
                }
                "active=${uiState.contextId.take(8)} contexts=" +
                    fresh.joinToString(prefix = "[", postfix = "]") {
                        "${it.id.take(8)}(${it.characterId ?: "free"}, ${it.messageCount}msg, ${it.updatedAtText})"
                    }
            },
            newContext = {
                uiState.newContext()
                "context switched to ${uiState.contextId.take(8)} (fresh, history empty)"
            },
            selectContext = { prefix ->
                val fresh = runBlocking {
                    withContext(Dispatchers.IO) { loadContextSummaries(convoDb) }
                }
                val match = fresh.firstOrNull { it.id.startsWith(prefix.trim(), ignoreCase = true) }
                    ?: error("no context matches '$prefix' — send ai_cmd contexts to list ids")
                uiState.switchContext(match.id)
                "context switched to ${match.id.take(8)} " +
                    "(${match.characterId ?: "free"}, ${match.messageCount} messages)"
            },
            // 与按住说话同一条 ASR 链路，只是音频来自文件（真机无手也能验 ASR）
            transcribeFile = { file ->
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val text = asrFor().transcribe(bytes, mimeForFileName(file.name), asrConfig())
                if (text.isBlank()) "recognized: <empty>" else "recognized: $text"
            },
            // MediaRecorder 真录音 N 秒后走 ASR：验证录音配置（AAC/m4a）服务端可收
            voiceRecord = { seconds ->
                // 半双工：录的是麦克风，先打断正在播的 TTS 防串音
                if (chatPhase == ConversationPhase.SPEAKING) session?.interrupt()
                voiceRecorder.start(echoCancellation = uiState.inputMode == InputMode.VIDEO)
                delay(seconds * 1000L)
                val file = voiceRecorder.stop()
                    ?: error("no valid audio captured (recording too short?)")
                val sizeKb = file.length() / 1024
                try {
                    val text = withContext(Dispatchers.IO) {
                        asrFor().transcribe(file.readBytes(), mimeForFileName(file.name), asrConfig())
                    }
                    "file=${sizeKb}KB text=${text.ifBlank { "<empty>" }}"
                } finally {
                    file.delete()
                }
            },
            setInputMode = { arg ->
                val mode = when (arg?.lowercase()) {
                    "manual", "manual_text" -> InputMode.MANUAL
                    "text", "typing" -> InputMode.TEXT
                    "voice", "asr" -> InputMode.VOICE
                    "video", "camera" -> InputMode.VIDEO
                    else -> throw IllegalArgumentException(
                        "set_mode expects manual|text|voice|video, got '$arg'"
                    )
                }
                if (mode == InputMode.VIDEO) {
                    val reason = uiState.videoModeBlockReason()
                    if (reason != null) throw IllegalStateException(reason)
                }
                uiState.switchInputMode(mode)
                "input mode = ${mode.name.lowercase()} (${mode.label})" +
                    if (mode == InputMode.VIDEO) " — camera starting" else ""
            },
            showButtons = { arg ->
                val show = when (arg?.lowercase()) {
                    null -> !uiState.buttonsVisible
                    "on", "true", "1", "show" -> true
                    "off", "false", "0", "hide" -> false
                    else -> throw IllegalArgumentException(
                        "show_buttons expects on|off (omit ai_arg to toggle), got '$arg'"
                    )
                }
                if (show != uiState.buttonsVisible) uiState.toggleButtons()
                "buttons visible=${uiState.buttonsVisible} (mode=${uiState.inputMode.name.lowercase()})"
            },
            // 大模型服务商切换（模型清单随服务商走，留空即默认模型；adb 驱动双厂商验证用）
            setProvider = { arg ->
                val provider = parseProviderArg(arg)
                uiState.updateAiPrefs(uiState.aiPrefs.copy(provider = provider))
                "LLM provider=${provider.name.lowercase()} (${provider.label}) " +
                    "llmModel=${resolveLlmModel(provider, uiState.aiPrefs.llmModel)} " +
                    "key=${if (uiState.aiPrefs.apiKeyFor(provider).isNotBlank()) "set" else "MISSING"}"
            },
            // TTS 独立服务商切换：自动取消「与大模型同服务商」勾选
            setTtsProvider = { arg ->
                val provider = parseProviderArg(arg)
                uiState.updateAiPrefs(uiState.aiPrefs.copy(ttsSameProvider = false, ttsProvider = provider))
                "TTS provider=${provider.name.lowercase()} (independent of LLM) " +
                    "ttsModel=${resolveTtsModel(provider, uiState.aiPrefs.ttsModel)} " +
                    "voice=${resolveVoice(provider, uiState.aiPrefs.voice)} " +
                    "ttsKey=${if (uiState.aiPrefs.apiKeyForTts().isNotBlank()) "set" else "MISSING"}"
            },
            // 当前服务商的大模型切换（清单校验；MIUI 禁触摸注入，设置页下拉的命令入口）
            setLlmModel = { arg ->
                val provider = uiState.aiPrefs.provider
                val id = arg?.trim()
                    ?: throw IllegalArgumentException(
                        "set_llm_model expects ai_arg = a model id from ${provider.name.lowercase()} " +
                            "catalog: ${provider.llmModels}"
                    )
                if (id !in provider.llmModels) {
                    throw IllegalArgumentException(
                        "model '$id' not in ${provider.name.lowercase()} catalog: ${provider.llmModels}"
                    )
                }
                uiState.updateAiPrefs(uiState.aiPrefs.copy(llmModel = id))
                "llmModel=$id vision=${isVisionLlm(provider, id)}"
            },
            // 视线控制：会话存在时切 FaceDriver 的 GazeMode（SaccadeEngine 接管
            // 每帧写入）；无会话时直接驱动 controller（corelib 视线叠加不依赖会话）
            lookAt = { arg, x, y, z ->
                when (arg?.lowercase()) {
                    "off", "none" -> {
                        session?.faceDriver?.setGazeMode(GazeMode.NONE)
                        controller.clearLookAtTarget()
                        "gaze off (head returns to the animation pose)"
                    }
                    "camera", "user", "lens" -> {
                        val fd = session?.faceDriver
                        if (fd != null) {
                            fd.setGazeMode(GazeMode.CAMERA)
                        } else {
                            // 无会话：写一次相机眼位快照（有会话时 FaceDriver 每帧写）
                            controller.getCameraLookAt()?.first?.let {
                                controller.setLookAtTarget(it[0], it[1], it[2])
                            }
                        }
                        "gaze mode = camera (tracking the user's position)"
                    }
                    null -> controller.getLookAtInfo()?.toString()
                        ?: "gaze state unavailable (renderer not attached)"
                    else -> {
                        if (x == null && y == null && z == null) {
                            throw IllegalArgumentException(
                                "look_at expects camera|off, no ai_arg (state), or ai_x/ai_y/ai_z, got '$arg'"
                            )
                        }
                        val px = x ?: 0f; val py = y ?: 0f; val pz = z ?: 0f
                        session?.faceDriver?.let { fd ->
                            fd.setGazePoint(px, py, pz)
                            fd.setGazeMode(GazeMode.POINT)
                        }
                        controller.setLookAtTarget(px, py, pz)
                        "look-at target = (%.2f, %.2f, %.2f)%s".format(
                            px, py, pz,
                            if (session?.faceDriver != null) " (FaceDriver POINT mode)" else " (controller, no session)",
                        )
                    }
                }
            },
            // 视频模式命令组：前后摄切换 / 抓拍缓存探测 / state 增量行
            switchLens = { arg ->
                if (!videoTracker.isActive) {
                    error("视频相机未启动——先进入视频模式 (set_mode video)")
                }
                val wantFront = when (arg?.lowercase()) {
                    null, "toggle", "flip" -> !videoTracker.currentLensFront
                    "front", "user", "qian" -> true
                    "back", "rear", "world" -> false
                    else -> throw IllegalArgumentException(
                        "video_camera expects front|back (omit ai_arg to toggle), got '$arg'"
                    )
                }
                if (wantFront != videoTracker.currentLensFront) {
                    pipLensFront = wantFront
                    videoTracker.start(
                        lifecycleOwner,
                        if (wantFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
                    )
                }
                "video camera = ${if (pipLensFront) "front" else "back"}"
            },
            videoSnapshot = {
                if (!videoTracker.isActive) {
                    error("视频相机未启动——先进入视频模式 (set_mode video)")
                }
                val f = videoTracker.ring.best()
                    ?: error("抓拍缓存还是空的——相机刚起或第一帧还没编码完，等 1s 再试")
                "snapshot ${f.byteCount}B sharpness=${"%.1f".format(f.sharpness)} " +
                    "age=${android.os.SystemClock.elapsedRealtime() - f.atMs}ms ring=${videoTracker.ring.size()}"
            },
            videoStatusLine = {
                if (uiState.inputMode == InputMode.VIDEO) videoTracker.debugStatus() else null
            },
            // ai_cmd voice_free on|off（无参=翻转）：按住说话 ⇄ 自由说话（连续聆听）
            setVoiceFree = { arg ->
                val new = when (arg?.lowercase()) {
                    null -> !uiState.voicePrefs.freeTalk
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> throw IllegalArgumentException(
                        "voice_free expects on|off (omit ai_arg to toggle), got '$arg'"
                    )
                }
                uiState.updateVoicePrefs(uiState.voicePrefs.copy(freeTalk = new))
                "freeTalk=$new (mode=${uiState.inputMode.name.lowercase()}, " +
                    "listening=${freeSpeech.running})"
            },
            // ai_cmd set_expression：走 FaceDriver 手动表情通道（缓动进场、保持不归零）
            manualExpression = { name, weight ->
                val fd = session?.faceDriver
                    ?: throw IllegalStateException("no face driver (session not ready)")
                fd.applyManualExpression(name, weight)
                uiState.selectedExpression = name
                "expression '$name' weight=$weight (eased, holds until cleared)"
            },
            // ai_cmd clear_expression：缓动回中性
            clearManualExpression = {
                val fd = session?.faceDriver
                    ?: throw IllegalStateException("no face driver (session not ready)")
                fd.clearManualExpression()
                "face easing back to neutral"
            },
        )
    }

    // AI debug interface: execute queued Intent commands for the screen's lifetime
    LaunchedEffect(aiCommands) {
        for (command in aiCommands) {
            executeAiCommand(context, controller, uiState, command, chatHooks)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 3D Avatar (full-screen background)
        AvatarView(
            modifier = Modifier.fillMaxSize(),
            controller = controller,
            config = AvatarConfig(
                iblPath = "default_env.ktx",
                renderSettings = uiState.renderSettings
            )
        )

        LaunchedEffect(uiState.isDragMode) {
            controller.setDragMode(uiState.isDragMode)
        }

        // FPS counter badge (top-right), toggled from the settings screen
        if (uiState.renderSettings.showFps && fps > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.45f)
            ) {
                Text(
                    text = "$fps FPS",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Camera shot badge (bottom-center), briefly shown after switching
        CameraShotLabelBadge(
            label = shotLabel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 180.dp)
        )

        // 视频模式 PiP：用户相机实时预览小窗（可拖动，位置持久化；可切前后摄）。
        // 摆在 Box 最上层（输入条之上），拖动不受聊天条遮挡。
        if (uiState.inputMode == InputMode.VIDEO && cameraGranted) {
            VideoCallPip(
                tracker = videoTracker,
                lensFront = pipLensFront,
                owner = lifecycleOwner,
                offsetFraction = uiState.videoPipOffset,
                onOffsetChange = { uiState.updateVideoPipOffset(it) },
                onToggleLens = { pipLensFront = !pipLensFront },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 左上角常驻控制：输入模式下拉框 + 按钮显隐开关（任务 4）
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InputModeSelector(
                current = uiState.inputMode,
                onSelect = { mode ->
                    // 视频模式准入：只有多模态模型可进（需求硬性门控），拒绝原因上错误条
                    if (mode == InputMode.VIDEO) {
                        val reason = uiState.videoModeBlockReason()
                        if (reason != null) {
                            chatError = reason
                            return@InputModeSelector
                        }
                    }
                    uiState.switchInputMode(mode)
                },
            )
            ButtonsToggle(
                visible = uiState.buttonsVisible,
                onToggle = { uiState.toggleButtons() },
            )
        }

        // AI 对话条（输入区按模式切换：打字 or 按住说话/自由说话）与对话字幕面板
        AiChatBar(
            phase = chatPhase,
            chatLines = chatLines,
            error = chatError,
            enabled = session != null,
            inputMode = uiState.inputMode,
            recording = voiceRecording,
            recognizing = voiceRecognizing,
            voiceAutoSend = uiState.voicePrefs.autoSend,
            voiceFreeTalk = uiState.voicePrefs.freeTalk,
            freeHearing = freeHearing,
            onToggleFreeTalk = onToggleFreeTalk,
            voicePrefill = voicePrefill,
            onVoicePrefillConsumed = { voicePrefill = null },
            onHoldStart = onHoldStart,
            onHoldEnd = onHoldEnd,
            onSend = { text ->
                pushUserLine(text)
                session?.send(text, videoSnapshotImages())
            },
            onInterrupt = { session?.interrupt() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    // 挪动人物 FAB 已挪进右列、底部两端不再有按钮——不再整条
                    // 抬高；但右下 FAB 列仍占着右下角，按钮显示时输入条（含
                    // 字幕面板）收窄让位，否则打字模式的发送键会压在 ☰ 后面
                    // （与各 ListPanel 的 end=72dp 同一让位惯例）
                    end = if (uiState.buttonsVisible) 72.dp else 12.dp,
                    bottom = 12.dp,
                )
        )

        // Row of FABs at bottom-end（跟随按钮显隐开关；打字/语音模式默认隐藏）
        if (uiState.buttonsVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End
            ) {
            // Drag mode FAB (挪动人物) — toggles drag mode. 从左下角挪进本列、
            // 置于视角之上；样式与「视/卡」文字 FAB 一致（原扳手图标语义不对）。
            SmallFloatingActionButton(
                onClick = { uiState.isDragMode = !uiState.isDragMode },
                shape = RoundedCornerShape(50),
                containerColor = if (uiState.isDragMode)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                if (uiState.isDragMode) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Exit drag mode"
                    )
                } else {
                    Text(text = "移", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Camera shot FAB (视角) — cycles the 4 preset framings
            SmallFloatingActionButton(
                onClick = {
                    val shots = CameraShot.entries
                    val next = shots[(shots.indexOf(currentShot) + 1).mod(shots.size)]
                    currentShot = next
                    controller.setCameraShot(next)
                    shotLabel = next.label
                },
                shape = RoundedCornerShape(50),
                containerColor = if (currentShot != null)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Text(text = "视", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            // Settings FAB (⚙️ gear icon) — opens the full settings screen
            SmallFloatingActionButton(
                onClick = {
                    uiState.activePanel =
                        if (uiState.activePanel == PanelType.SETTINGS) PanelType.NONE
                        else PanelType.SETTINGS
                },
                shape = CircleShape,
                containerColor = if (uiState.activePanel == PanelType.SETTINGS)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Icon(
                    imageVector = if (uiState.activePanel == PanelType.SETTINGS) Icons.Default.Close else Icons.Default.Settings,
                    contentDescription = if (uiState.activePanel == PanelType.SETTINGS) "Hide settings" else "Show settings"
                )
            }

            // Scene FAB (🏠 Home icon)
            SmallFloatingActionButton(
                onClick = {
                    uiState.activePanel =
                        if (uiState.activePanel == PanelType.SCENES) PanelType.NONE
                        else PanelType.SCENES
                },
                shape = CircleShape,
                containerColor = if (uiState.activePanel == PanelType.SCENES)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Icon(
                    imageVector = if (uiState.activePanel == PanelType.SCENES) Icons.Default.Close else Icons.Default.Home,
                    contentDescription = if (uiState.activePanel == PanelType.SCENES) "Hide scenes" else "Show scenes"
                )
            }

            // Expression FAB (😊 Face icon)
            SmallFloatingActionButton(
                onClick = {
                    uiState.activePanel =
                        if (uiState.activePanel == PanelType.EXPRESSIONS) PanelType.NONE
                        else PanelType.EXPRESSIONS
                },
                shape = CircleShape,
                containerColor = if (uiState.activePanel == PanelType.EXPRESSIONS)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Icon(
                    imageVector = if (uiState.activePanel == PanelType.EXPRESSIONS) Icons.Default.Close else Icons.Default.Face,
                    contentDescription = if (uiState.activePanel == PanelType.EXPRESSIONS) "Hide expressions" else "Show expressions"
                )
            }

            // Character card FAB (人物卡, text "卡" like the camera "视" FAB)
            SmallFloatingActionButton(
                onClick = {
                    uiState.activePanel =
                        if (uiState.activePanel == PanelType.CARDS) PanelType.NONE
                        else PanelType.CARDS
                },
                shape = RoundedCornerShape(50),
                containerColor = if (uiState.activePanel == PanelType.CARDS)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Text(text = "卡", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            // Animation FAB
            SmallFloatingActionButton(
                onClick = {
                    uiState.activePanel =
                        if (uiState.activePanel == PanelType.ANIMATIONS) PanelType.NONE
                        else PanelType.ANIMATIONS
                },
                shape = CircleShape,
                containerColor = if (uiState.activePanel == PanelType.ANIMATIONS)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Icon(
                    imageVector = if (uiState.activePanel == PanelType.ANIMATIONS) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = if (uiState.activePanel == PanelType.ANIMATIONS) "Hide animations" else "Show animations"
                )
            }

            // Model FAB
            SmallFloatingActionButton(
                onClick = {
                    uiState.activePanel =
                        if (uiState.activePanel == PanelType.MODELS) PanelType.NONE
                        else PanelType.MODELS
                },
                shape = CircleShape,
                containerColor = if (uiState.activePanel == PanelType.MODELS)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Icon(
                    imageVector = if (uiState.activePanel == PanelType.MODELS) Icons.Default.Close else Icons.Default.List,
                    contentDescription = if (uiState.activePanel == PanelType.MODELS) "Hide models" else "Show models"
                )
            }
            }
        }

        // Model selector overlay at the bottom
        AnimatedVisibility(
            visible = uiState.activePanel == PanelType.MODELS,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            ListPanel(
                title = "Models",
                items = uiState.modelFiles,
                selectedItem = uiState.selectedModel,
                onItemClick = { fileName ->
                    uiState.selectedModel = fileName
                    uiState.selectedAnimation = null // reset animation on model switch
                    uiState.activePanel = PanelType.NONE
                }
            )
        }

        // Animation selector overlay at the bottom
        AnimatedVisibility(
            visible = uiState.activePanel == PanelType.ANIMATIONS,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            ListPanel(
                title = "Animations · 长按条目设为待机",
                items = uiState.animationFiles,
                selectedItem = uiState.selectedAnimation,
                // 只隐藏顶层目录名（如 VRMA_Selected_Categorized），保留分类子文件夹
                displayName = {
                    val base = it.removeSuffix(".vrma").substringAfter('/')
                    if (idlePrefValueFor(
                            it, uiState.useExternalAnimations, uiState.externalAnimationsRoot(context)
                        ) == uiState.idleAnimation
                    ) "$base · 待机" else base
                },
                onItemClick = { fileName ->
                    uiState.selectedAnimation = fileName
                    uiState.loadAnimation(context, controller, fileName)
                    controller.playVrmaAnimation(loop = true)
                },
                onItemLongClick = { fileName ->
                    val root = uiState.externalAnimationsRoot(context)
                    val entry = idleEntryFor(fileName, uiState.useExternalAnimations, root)
                    if (entry == null) {
                        Toast.makeText(context, "该文件无法设为待机", Toast.LENGTH_SHORT).show()
                    } else {
                        val prefValue = idlePrefValueFor(fileName, uiState.useExternalAnimations, root)
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_IDLE_ANIMATION, prefValue).apply()
                        uiState.idleAnimation = prefValue
                        applyIdle(entry)
                        Toast.makeText(context, "待机动作：${entry.label}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // Expression selector overlay at the bottom
        AnimatedVisibility(
            visible = uiState.activePanel == PanelType.EXPRESSIONS,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            ListPanel(
                title = "Expressions",
                items = expressionList,
                selectedItem = uiState.selectedExpression,
                onItemClick = { name ->
                    val fd = session?.faceDriver
                    if (uiState.selectedExpression == name) {
                        // Toggle off — ease back to neutral (face driver) / snap (no session)
                        if (fd != null) fd.clearManualExpression() else controller.clearAllExpressions()
                        uiState.selectedExpression = null
                    } else {
                        // Apply the new expression at full weight. 有 FaceDriver 时走
                        // 手动表情通道：缓动进场（控制器已被驱动器切成 instant 模式，
                        // 直写 setExpression 是一帧闪现——真机踩过），且不自动归零。
                        if (fd != null) {
                            fd.applyManualExpression(name, 1.0f)
                        } else {
                            controller.clearAllExpressions()
                            controller.setExpression(name, 1.0f)
                        }
                        uiState.selectedExpression = name
                    }
                }
            )
        }

        // Scene selector overlay at the bottom
        AnimatedVisibility(
            visible = uiState.activePanel == PanelType.SCENES,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            ListPanel(
                title = "Scenes",
                items = uiState.sceneFiles,
                selectedItem = uiState.selectedScene,
                displayName = { it.removeSuffix(".glb").replace("_", " ") },
                onItemClick = { fileName ->
                    uiState.selectedScene = fileName
                    uiState.activePanel = PanelType.NONE
                }
            )
        }

        // Character card panel: import (SAF) / activate / delete
        AnimatedVisibility(
            visible = uiState.activePanel == PanelType.CARDS,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 72.dp, bottom = 12.dp)
        ) {
            CardsPanel(
                cards = uiState.cards,
                activeFile = uiState.activeCardFile,
                presets = uiState.presetCards,
                activePresetAsset = uiState.presetAssetByFile(uiState.activeCardFile),
                onSelectPreset = selectPresetCard,
                onClearCard = deactivateCard,
                onImport = { cardPicker.launch(arrayOf("image/png", "application/json")) },
                onActivate = activateCard,
                onDelete = deleteCard,
            )
        }

        // Settings screen overlay: dim scrim + bottom sheet.
        AnimatedVisibility(
            visible = uiState.activePanel == PanelType.SETTINGS,
            enter = fadeIn() + slideInVertically { it },
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            // 人物卡提示词 = 卡片默认人设（SystemPromptAssembler 拼装）+ 人工编辑覆盖
            val activeCardEntry = uiState.activeCardFile?.let { uiState.cardByFile(it) }
            val cardPromptDefault = activeCardEntry
                ?.let { SystemPromptAssembler().assemble(it.card) }
                .orEmpty()
            val cardPromptOverride = uiState.activeCardFile?.let { uiState.cardPromptOverride(it) }
            SettingsScreen(
                settings = uiState.renderSettings,
                useExternalAnimations = uiState.useExternalAnimations,
                externalRootPath = uiState.externalAnimationsRoot(context)?.absolutePath,
                aiPrefs = uiState.aiPrefs,
                voicePrefs = uiState.voicePrefs,
                contexts = contextList,
                activeContextId = uiState.contextId,
                protocolPrompt = session?.protocolBlock().orEmpty(),
                cardNameFor = { characterId -> uiState.cardByFile(characterId)?.card?.name },
                activeCardName = activeCardEntry?.card?.name,
                activeCardPromptDefault = cardPromptDefault,
                activeCardPromptOverride = cardPromptOverride,
                onSaveCardPrompt = { text ->
                    val file = uiState.activeCardFile
                    if (file != null) {
                        uiState.setCardPromptOverride(file, text)
                        // 空文本 = 视为还原默认（CardPromptOverrides.set 会清除该键）
                        val entry = uiState.cardByFile(file)
                        if (text.isEmpty()) {
                            if (entry != null) session?.let { applyCardToSession(it, entry, applyOverrideFor) }
                        } else {
                            session?.systemPrompt = text
                        }
                    }
                },
                onResetCardPrompt = {
                    val file = uiState.activeCardFile
                    val entry = file?.let { uiState.cardByFile(it) }
                    if (file != null && entry != null) {
                        uiState.setCardPromptOverride(file, null)
                        session?.let { applyCardToSession(it, entry, applyOverrideFor) }
                    }
                },
                onAnimationSourceChange = { uiState.setAnimationSource(context, it) },
                onSettingsChange = applyRenderSettings,
                onAiPrefsChange = { uiState.updateAiPrefs(it) },
                onVoicePrefsChange = { uiState.updateVoicePrefs(it) },
                onNewContext = { uiState.newContext() },
                onSelectContext = { uiState.switchContext(it) },
                onDeleteContext = { summary ->
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            convoDb.messageDao().deleteFor(summary.id)
                            convoDb.sessionDao().delete(summary.id)
                        }
                        if (summary.id == uiState.contextId) uiState.newContext() else contextListVersion++
                    }
                },
                onDismiss = { uiState.activePanel = PanelType.NONE }
            )
        }
    }
}

/** Transient badge naming the camera shot that was just applied. */
@Composable
private fun CameraShotLabelBadge(label: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = label != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color.Black.copy(alpha = 0.45f)
        ) {
            Text(
                text = label.orEmpty(),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * AI 对话条：状态徽标 + 对话字幕面板（用户消息在上、虚拟人回复在下，可滚动
 * 回看、自动贴底）+ 输入区（按输入模式切换：打字输入框 / 按住说话）。
 * `enabled = false`（未配置或未就绪）时输入区仍可见但不可交互。
 */
@Composable
private fun AiChatBar(
    phase: ConversationPhase,
    chatLines: List<ChatLine>,
    error: String?,
    enabled: Boolean,
    inputMode: InputMode,
    recording: Boolean,
    recognizing: Boolean,
    voiceAutoSend: Boolean,
    voiceFreeTalk: Boolean,
    freeHearing: Boolean,
    onToggleFreeTalk: () -> Unit,
    voicePrefill: String?,
    onVoicePrefillConsumed: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val busy = phase != ConversationPhase.IDLE

    // 语音识别后不直接发送的路径：识别文本填入输入框，由用户确认发送
    LaunchedEffect(voicePrefill) {
        voicePrefill?.let {
            input = it
            onVoicePrefillConsumed()
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (chatLines.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                val scrollState = rememberScrollState()
                // 实时贴底：面板限高、超出可下拉回看（替代旧 maxLines=4 的省略
                // 号截断）；内容长高（新消息/流式追加）即自动滚到最底。
                LaunchedEffect(chatLines) {
                    snapshotFlow { scrollState.maxValue }.collect { max ->
                        if (max > 0) scrollState.animateScrollTo(max)
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 168.dp)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    chatLines.forEach { line ->
                        val user = line.role == ChatRole.USER
                        Text(
                            text = if (user) "我：${line.text}" else line.text,
                            color = if (user) Color(0xFF9ED8FF) else Color.White,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = error.orEmpty(),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            ) {
                AnimatedVisibility(visible = busy) {
                    Text(
                        text = when (phase) {
                            ConversationPhase.THINKING -> "思考中"
                            ConversationPhase.SPEAKING -> "说话中"
                            ConversationPhase.IDLE -> ""
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 12.dp, end = 6.dp)
                    )
                }
                if ((inputMode == InputMode.VOICE || inputMode == InputMode.VIDEO) && input.isBlank()) {
                    // 语音/视频模式主形态：左侧「按住/自由」切换 + 按住说话条或自由聆听指示
                    VoiceTalkModeToggle(
                        free = voiceFreeTalk,
                        onToggle = onToggleFreeTalk,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    if (voiceFreeTalk) {
                        FreeListenIndicator(
                            recognizing = recognizing,
                            hearing = freeHearing,
                            enabled = enabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .padding(horizontal = 4.dp),
                        )
                    } else {
                        HoldToTalk(
                            recording = recording,
                            recognizing = recognizing,
                            enabled = enabled,
                            onPressStart = onHoldStart,
                            onPressEnd = onHoldEnd,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .padding(horizontal = 4.dp),
                        )
                    }
                } else {
                    if (inputMode == InputMode.VOICE || inputMode == InputMode.VIDEO) {
                        // 确认形态（关闭"直接发送"时）：识别文本可改，左侧保留小按住键
                        VoiceTalkModeToggle(
                            free = voiceFreeTalk,
                            onToggle = onToggleFreeTalk,
                            modifier = Modifier.padding(end = 2.dp),
                        )
                        HoldToTalk(
                            recording = recording,
                            recognizing = recognizing,
                            enabled = enabled,
                            onPressStart = onHoldStart,
                            onPressEnd = onHoldEnd,
                            compact = true,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = {
                            Text(
                                text = when {
                                    !enabled -> "先在 ⚙️ 设置里配置 AI 服务"
                                    inputMode == InputMode.VIDEO -> "说话（视频模式：每轮附相机画面）…"
                                    inputMode == InputMode.VOICE -> "识别结果确认后发送…"
                                    else -> "说点什么，回车或发送…"
                                },
                                fontSize = 13.sp
                            )
                        },
                        singleLine = true,
                        enabled = enabled,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (enabled && input.isNotBlank()) {
                                onSend(input)
                                input = ""
                            }
                        }),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            onSend(input)
                            input = ""
                        },
                        enabled = enabled && input.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (enabled && input.isNotBlank())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (busy) {
                    IconButton(onClick = onInterrupt) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "打断"
                        )
                    }
                }
            }
        }
    }
}

/**
 * 按住说话（任务 4）：press-hold 手势驱动 [onPressStart]/[onPressEnd]。
 * 录音中红底"松开 识别"，识别中"识别中…"；`compact` 为确认形态的小圆钮。
 */
@Composable
private fun HoldToTalk(
    recording: Boolean,
    recognizing: Boolean,
    enabled: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val bgColor = when {
        recording -> MaterialTheme.colorScheme.errorContainer
        recognizing -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val label = when {
        recording -> "松开 识别"
        recognizing -> "识别中…"
        enabled -> "按住 说话"
        else -> "未配置 AI 服务"
    }
    Box(
        modifier = modifier
            .clip(if (compact) CircleShape else RoundedCornerShape(22.dp))
            .background(bgColor)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onPressStart()
                                tryAwaitRelease()
                                onPressEnd()
                            }
                        )
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (compact) "🎤" else label,
            color = if (recording)
                MaterialTheme.colorScheme.onErrorContainer
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (compact) 16.sp else 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * 语音/视频模式下按住说话与自由说话的切换按钮（用户需求：一个按钮切换两种
 * 说话方式）。状态持久化在 [VoicePrefs.freeTalk]，切模式/重启保持。
 */
@Composable
private fun VoiceTalkModeToggle(
    free: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(14.dp),
        color = if (free) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
        else Color.Black.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (free) "自由" else "按住",
                color = if (free) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "说话",
                color = if (free) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else Color.White.copy(alpha = 0.7f),
                fontSize = 9.sp,
            )
        }
    }
}

/**
 * 自由说话聆听指示条：平静=「自由说话中」，VAD 检测到人声=「听到你说话…」
 * （呼吸色），切片送识别=「识别中…」。点击可关回按住说话。
 */
@Composable
private fun FreeListenIndicator(
    recognizing: Boolean,
    hearing: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            recognizing -> MaterialTheme.colorScheme.secondaryContainer
            hearing -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        },
        label = "freeListenBg",
    )
    val label = when {
        recognizing -> "识别中…"
        hearing -> "听到你说话…"
        enabled -> "自由说话中，直接开口"
        else -> "未配置 AI 服务"
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            recognizing -> MaterialTheme.colorScheme.secondary
                            hearing -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
            )
            Text(
                text = label,
                color = if (hearing || recognizing) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** 左上角输入模式下拉框：手动点击 / 打字输入 / 语音模式 / 视频模式，互斥切换。 */
@Composable
private fun InputModeSelector(
    current: InputMode,
    onSelect: (InputMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.45f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp)
            ) {
                Text(
                    text = current.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "切换输入模式",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            InputMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (mode == current) "${mode.label}（当前）" else mode.label,
                            fontWeight = if (mode == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        expanded = false
                        if (mode != current) onSelect(mode)
                    },
                )
            }
        }
    }
}

/**
 * 模式下拉框旁的按钮显隐开关：任何模式下都可临时显示/隐藏所有悬浮按钮
 * （打字/语音模式进入时自动隐藏，语音模式里偶尔要换模型/开设置就用它）。
 */
@Composable
private fun ButtonsToggle(
    visible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Text(
            text = if (visible) "隐藏按钮" else "显示按钮",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

/**
 * 视频模式的用户相机 PiP 小窗：实时预览 + 拖动（位置 0..1 分数持久化，重启
 * 回到上次位置）+ 底部前后摄切换钮。相机绑定跟随本组合的生命周期
 * （组合出现/镜头切换时重绑；离开视频模式由模式 LaunchedEffect 统一 stop）。
 * 拖动起点在 onDragStart 捕获一次、手势内只累加增量——不每帧重读状态，
 * 避免写状态后回读造成的位置漂移。
 */
@Composable
private fun VideoCallPip(
    tracker: UserCameraTracker,
    lensFront: Boolean,
    owner: LifecycleOwner,
    offsetFraction: Offset,
    onOffsetChange: (Offset) -> Unit,
    onToggleLens: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pipWidth = 108.dp
    val pipHeight = 144.dp
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val fraction by rememberUpdatedState(offsetFraction)

    LaunchedEffect(lensFront) {
        tracker.start(
            owner,
            if (lensFront) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
        )
    }
    DisposableEffect(Unit) {
        onDispose { tracker.detachPreview() }
    }

    Box(modifier = modifier.onGloballyPositioned { containerSize = it.size }) {
        if (containerSize == IntSize.Zero) return@Box
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (fraction.x * containerSize.width).roundToInt(),
                        (fraction.y * containerSize.height).roundToInt(),
                    )
                }
                .size(pipWidth, pipHeight)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .background(Color.Black)
                .pointerInput(containerSize) {
                    var accumX = 0f
                    var accumY = 0f
                    var startPx = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            accumX = 0f; accumY = 0f
                            startPx = Offset(
                                fraction.x * containerSize.width,
                                fraction.y * containerSize.height,
                            )
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (containerSize == IntSize.Zero) return@detectDragGestures
                            accumX += amount.x
                            accumY += amount.y
                            val wPx = pipWidth.toPx()
                            val hPx = pipHeight.toPx()
                            val maxX = (containerSize.width - wPx).coerceAtLeast(1f)
                            val maxY = (containerSize.height - hPx).coerceAtLeast(1f)
                            onOffsetChange(
                                Offset(
                                    (startPx.x + accumX).coerceIn(0f, maxX) / containerSize.width,
                                    (startPx.y + accumY).coerceIn(0f, maxY) / containerSize.height,
                                )
                            )
                        },
                    )
                },
        ) {
            AndroidView(
                // owner 一并传给 attachPreview:组合时序上 start 可能先于本视图
                // 挂载执行(容器首帧无尺寸),attach 时追踪器会自动补绑 Preview
                factory = { ctx -> PreviewView(ctx).also { tracker.attachPreview(it, owner) } },
                modifier = Modifier.fillMaxSize(),
            )
            SmallFloatingActionButton(
                onClick = onToggleLens,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .size(30.dp),
                shape = CircleShape,
                containerColor = Color.Black.copy(alpha = 0.55f),
            ) {
                Text(
                    text = if (lensFront) "前" else "后",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * 人物卡面板：ListPanel 的双行条目扩展版 —— 标题 = 卡片名，副标题 =
 * spec + characterVersion；点条目激活/取消激活（激活时播开场白），尾部
 * 图标删除。导入走 SAF（[onImport]）。
 */
@Composable
private fun CardsPanel(
    cards: List<CharacterCardStore.Entry>,
    activeFile: String?,
    presets: List<PresetCard>,
    activePresetAsset: String?,
    onSelectPreset: (PresetCard) -> Unit,
    onClearCard: () -> Unit,
    onImport: () -> Unit,
    onActivate: (CharacterCardStore.Entry) -> Unit,
    onDelete: (CharacterCardStore.Entry) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "预置角色",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(max = 320.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 空卡片 = 取消人物卡；仅当完全没有激活卡（含手工导入）时才高亮
                item(key = "preset_none") {
                    EmptyCardCell(selected = activeFile == null, onClick = onClearCard)
                }
                items(presets, key = { it.assetPath }) { preset ->
                    PresetCardCell(
                        preset = preset,
                        selected = preset.assetPath == activePresetAsset,
                        onClick = { onSelectPreset(preset) },
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp, start = 4.dp)
            ) {
                Text(
                    text = "我的卡片",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onImport) {
                    Text(text = "导入 PNG/JSON…", fontSize = 13.sp)
                }
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(cards, key = { it.fileName }) { entry ->
                    val isActive = entry.fileName == activeFile
                    val bgColor by animateColorAsState(
                        targetValue = if (isActive)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface,
                        label = "cardBg"
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .clickable { onActivate(entry) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.card.name.ifEmpty { "（未命名卡片）" },
                                fontSize = 14.sp,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isActive)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = entry.card.characterVersion
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { "${entry.card.spec} · $it" }
                                    ?: entry.card.spec,
                                fontSize = 11.sp,
                                color = if (isActive)
                                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isActive) {
                            Text(
                                text = "使用中",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        IconButton(onClick = { onDelete(entry) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "删除卡片",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                if (cards.isEmpty()) {
                    item {
                        Text(
                            text = "点上方预置角色即可开始对话；从 SillyTavern 导出的 PNG / JSON " +
                                "卡导入后也会出现在这里。",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/** assets 卡图缩略图：按 ~256px 目标边解码，避免整图位图进内存。 */
@Composable
private fun rememberPresetCardBitmap(assetPath: String): ImageBitmap? {
    val context = LocalContext.current
    return remember(assetPath) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.assets.open(assetPath).use {
                BitmapFactory.decodeStream(it, null, opts)
            }?.asImageBitmap()
        }.getOrNull()
    }
}

/** 预置卡 grid 单元：卡图 + 名称；选中描边高亮。 */
@Composable
private fun PresetCardCell(
    preset: PresetCard,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bitmap = rememberPresetCardBitmap(preset.assetPath)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = preset.card.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = preset.card.name,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected)
                MaterialTheme.colorScheme.onPrimaryContainer
            else
                MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** 空卡片单元：取消人物卡。 */
@Composable
private fun EmptyCardCell(selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "取消人物卡",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "无人物卡",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * Reusable list panel component for models and animations. While shown, the
 * panel stays open on item click; each time it (re)appears it jumps straight
 * to the currently selected item.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ListPanel(
    title: String,
    items: List<String>,
    selectedItem: String?,
    displayName: (String) -> String = { it },
    onItemClick: (String) -> Unit,
    onItemLongClick: ((String) -> Unit)? = null
) {    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )

            val listState = rememberLazyListState()
            // The panel's content is only composed while visible, so this runs
            // on every (re)open and lands the list on the selected item.
            LaunchedEffect(Unit) {
                items.indexOf(selectedItem)
                    .takeIf { it >= 0 }
                    ?.let { listState.scrollToItem(it) }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items) { fileName ->
                    val isSelected = fileName == selectedItem
                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surface,
                        label = "itemBg"
                    )
                    val clickModifier = if (onItemLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = { onItemClick(fileName) },
                            onLongClick = { onItemLongClick(fileName) },
                        )
                    } else {
                        Modifier.clickable { onItemClick(fileName) }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(bgColor)
                            .then(clickModifier)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName(fileName),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
