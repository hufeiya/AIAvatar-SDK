package com.neethu.aiavatar_sdk

import android.content.Context
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.aiavatar_sdk.ui.SettingsScreen
import com.neethu.aiavatar_sdk.ui.theme.AIAvatarSDKTheme
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarController
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.AvatarState
import com.neethu.corelib.AvatarView
import com.neethu.corelib.CameraShot
import com.neethu.corelib.rememberAvatarController
import com.neethu.orchestrator.card.CharacterCard
import com.neethu.orchestrator.card.CharacterCardStore
import com.neethu.orchestrator.card.spokenGreeting
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
import kotlinx.coroutines.withContext
import java.io.File

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

/** 新上下文的随机 id（UUID；ai_cmd select_context 支持前缀匹配）。 */
private fun newContextId(): String = java.util.UUID.randomUUID().toString()

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

    init {
        if (prefs.getString(KEY_AI_CONTEXT_ID, null) == null) {
            prefs.edit().putString(KEY_AI_CONTEXT_ID, contextId).apply()
        }
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

    /** 更新 AI 对话配置并持久化。 */
    fun updateAiPrefs(p: AiChatPrefs) {
        aiPrefs = p
        prefs.saveAiPrefs(p)
    }

    // ── 人物卡 ────────────────────────────────────────────────────────────

    /** 按文件名查卡片条目。 */
    fun cardByFile(fileName: String?): CharacterCardStore.Entry? =
        cards.firstOrNull { it.fileName == fileName }

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
    var replyText by remember { mutableStateOf("") }
    var chatError by remember { mutableStateOf<String?>(null) }

    // 切换/新建上下文：新会话的历史来自另一条 Room 记录，旧字幕一并清掉
    LaunchedEffect(uiState.contextId) {
        replyText = ""
        chatError = null
    }

    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        // 会话（重）建后让激活的人物卡重新生效；激活时若 AI 尚未配置，
        // pendingGreetingFile 记着开场白意图，这里补播一次。
        uiState.activeCardFile?.let { file ->
            uiState.cardByFile(file)?.let { entry ->
                s.setCharacterCard(entry.card)
                if (uiState.pendingGreetingFile == file) {
                    uiState.pendingGreetingFile = null
                    val greeting = entry.card.spokenGreeting()
                    if (greeting.isNotEmpty()) launch { runCatching { s.speak(greeting) } }
                }
            }
        }
        launch { s.phase.collect { chatPhase = it } }
        launch {
            s.events.collect { ev ->
                when (ev) {
                    is AvatarEvent.SentenceQueued ->
                        Log.i(AI_LOG_TAG, "chat: SentenceQueued #${ev.sequence} \"${ev.text}\"")
                    is AvatarEvent.SentenceStarted -> {
                        replyText += ev.text
                        Log.i(AI_LOG_TAG, "chat: SentenceStarted #${ev.sequence}")
                    }
                    is AvatarEvent.SentenceEnded ->
                        Log.i(AI_LOG_TAG, "chat: SentenceEnded #${ev.sequence}")
                    is AvatarEvent.SentenceFailed -> {
                        // 上错误条：静默失败的句子只会让人以为"没出声/崩了"
                        chatError = "第 ${ev.sequence + 1} 句语音合成失败：${ev.message}"
                        Log.w(AI_LOG_TAG, "chat: SentenceFailed #${ev.sequence}: ${ev.message}")
                    }
                    is AvatarEvent.EmotionChanged ->
                        Log.i(AI_LOG_TAG, "chat: EmotionChanged ${ev.cue.name} intensity=${ev.cue.intensity}")
                    is AvatarEvent.ActionStarted ->
                        Log.i(AI_LOG_TAG, "chat: ActionStarted ${ev.tag} (${ev.label})")
                    is AvatarEvent.CameraChanged -> {
                        // 与手动视角 FAB 共用同一枚徽标，LLM 切机位时同步显示
                        currentShot = ev.shot
                        shotLabel = ev.shot.label
                        Log.i(AI_LOG_TAG, "chat: CameraChanged ${ev.shot.name}")
                    }
                    is AvatarEvent.TurnCompleted ->
                        Log.i(AI_LOG_TAG, "chat: TurnCompleted subtitleLen=${replyText.length}")
                    is AvatarEvent.TurnFailed -> {
                        chatError = ev.error.message ?: "对话失败"
                        Log.e(AI_LOG_TAG, "chat: TurnFailed: ${ev.error.message}")
                    }
                    is AvatarEvent.PlaybackInterrupted ->
                        Log.i(AI_LOG_TAG, "chat: PlaybackInterrupted")
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

    DisposableEffect(Unit) {
        onDispose { aiChat.shutdown() }
    }

    // ── 人物卡：激活/删除逻辑，卡片面板与 adb import_card 共用 ────────────
    val activateCard: (CharacterCardStore.Entry) -> Unit = { entry ->
        val wasActive = uiState.activeCardFile == entry.fileName
        uiState.setActiveCard(if (wasActive) null else entry.fileName)
        val s = session
        when {
            wasActive -> {
                s?.clearCharacterCard()
                s?.clearHistory()
            }
            s != null -> {
                s.setCharacterCard(entry.card)
                s.clearHistory()
                val greeting = entry.card.spokenGreeting()
                if (greeting.isNotEmpty()) scope.launch { runCatching { s.speak(greeting) } }
            }
            else -> uiState.pendingGreetingFile = entry.fileName
        }
    }
    val deleteCard: (CharacterCardStore.Entry) -> Unit = { entry ->
        val wasActive = uiState.activeCardFile == entry.fileName
        uiState.deleteCard(entry.fileName)
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
                    replyText = ""
                    s.send(text)
                }
                s != null
            },
            interrupt = {
                val s = session
                if (s != null) s.interrupt()
                s != null
            },
            snapshot = {
                "phase=$chatPhase subtitleLen=${replyText.length} error=${chatError ?: "none"}"
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

        // AI 对话条（输入 + 发送 + 打断）与回复字幕
        AiChatBar(
            phase = chatPhase,
            replyText = replyText,
            error = chatError,
            enabled = session != null,
            onSend = { text ->
                replyText = ""
                session?.send(text)
            },
            onInterrupt = { session?.interrupt() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 96.dp)
        )

        // Drag mode FAB at bottom-start
        SmallFloatingActionButton(
            onClick = { uiState.isDragMode = !uiState.isDragMode },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            shape = CircleShape,
            containerColor = if (uiState.isDragMode)
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
            else
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
        ) {
            Icon(
                imageVector = if (uiState.isDragMode) Icons.Default.Close else Icons.Default.Build,
                contentDescription = if (uiState.isDragMode) "Exit drag mode" else "Enter drag mode"
            )
        }

        // Row of FABs at bottom-end
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
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
                    if (uiState.selectedExpression == name) {
                        // Toggle off — clear the expression
                        controller.clearAllExpressions()
                        uiState.selectedExpression = null
                    } else {
                        // Apply the new expression at full weight
                        controller.clearAllExpressions()
                        controller.setExpression(name, 1.0f)
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
            SettingsScreen(
                settings = uiState.renderSettings,
                useExternalAnimations = uiState.useExternalAnimations,
                externalRootPath = uiState.externalAnimationsRoot(context)?.absolutePath,
                aiPrefs = uiState.aiPrefs,
                contexts = contextList,
                activeContextId = uiState.contextId,
                protocolPrompt = session?.protocolBlock().orEmpty(),
                cardNameFor = { characterId -> uiState.cardByFile(characterId)?.card?.name },
                onAnimationSourceChange = { uiState.setAnimationSource(context, it) },
                onSettingsChange = applyRenderSettings,
                onAiPrefsChange = { uiState.updateAiPrefs(it) },
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
 * AI 对话条：状态徽标 + 当前轮回复字幕 + 输入框（发送/打断）。
 * `enabled = false`（未配置或未就绪）时输入框仍可见但不可发送。
 */
@Composable
private fun AiChatBar(
    phase: ConversationPhase,
    replyText: String,
    error: String?,
    enabled: Boolean,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }
    val busy = phase != ConversationPhase.IDLE

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (replyText.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = replyText,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
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
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            text = if (enabled) "说点什么，回车或发送…" else "先在 ⚙️ 设置里配置 AI 服务",
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
 * 人物卡面板：ListPanel 的双行条目扩展版 —— 标题 = 卡片名，副标题 =
 * spec + characterVersion；点条目激活/取消激活（激活时播开场白），尾部
 * 图标删除。导入走 SAF（[onImport]）。
 */
@Composable
private fun CardsPanel(
    cards: List<CharacterCardStore.Entry>,
    activeFile: String?,
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp, start = 4.dp)
            ) {
                Text(
                    text = "角色卡",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onImport) {
                    Text(text = "导入 PNG/JSON…", fontSize = 13.sp)
                }
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 240.dp),
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
                            text = "还没有卡片。导入 SillyTavern 导出的 PNG / JSON 角色卡后，\n" +
                                "点按卡片激活人设，AI 会按人设回答并朗读开场白。",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        }
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
