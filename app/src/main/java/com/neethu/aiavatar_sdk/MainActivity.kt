package com.neethu.aiavatar_sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.material.icons.filled.Add
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
import com.neethu.aiavatar_sdk.ui.SECTION_AI
import com.neethu.aiavatar_sdk.ui.SECTION_ANIMATIONS
import com.neethu.aiavatar_sdk.ui.SECTION_CARD
import com.neethu.aiavatar_sdk.ui.SECTION_CONTEXT
import com.neethu.aiavatar_sdk.ui.SECTION_FREE_SPEECH
import com.neethu.aiavatar_sdk.ui.SECTION_LANGUAGE
import com.neethu.aiavatar_sdk.ui.SECTION_LIVENESS
import com.neethu.aiavatar_sdk.ui.SECTION_QUALITY
import com.neethu.aiavatar_sdk.ui.OnboardingGuideSheet
import com.neethu.aiavatar_sdk.ui.GuideVariant
import com.neethu.aiavatar_sdk.ui.SettingsScreen
import com.neethu.aiavatar_sdk.ui.onboardingGuideVariant
import com.neethu.aiavatar_sdk.ui.withOnboardingAsr
import com.neethu.aiavatar_sdk.ui.withOnboardingKey
import com.neethu.aiavatar_sdk.ui.theme.AIAvatarSDKTheme
import com.neethu.aiavatar_sdk.video.UserCameraTracker
import com.neethu.aiavatar_sdk.video.PoseMimicMath
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarController
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.AvatarState
import com.neethu.corelib.AvatarView
import com.neethu.corelib.CameraShot
import com.neethu.corelib.Lang
import com.neethu.corelib.rememberAvatarController
import com.neethu.aiavatar_sdk.i18n.AppLang
import com.neethu.aiavatar_sdk.i18n.LocalStrings
import com.neethu.aiavatar_sdk.i18n.Strings
import com.neethu.aiavatar_sdk.i18n.resolveAppLang
import com.neethu.orchestrator.card.CharacterCard
import com.neethu.orchestrator.card.CharacterCardStore
import com.neethu.orchestrator.card.SystemPromptAssembler
import com.neethu.orchestrator.card.spokenGreeting
import com.neethu.orchestrator.face.GazeMode
import com.neethu.orchestrator.face.MimicFaceMapper
import com.neethu.orchestrator.i18n.promptTextsOf
import com.neethu.orchestrator.gesture.ActionEntry
import com.neethu.orchestrator.history.ConversationDatabase
import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.session.ConversationPhase
import com.neethu.orchestrator.skill.LookHereSkill
import com.neethu.orchestrator.skill.MimicSkill
import com.neethu.orchestrator.skill.RpsSkill
import com.neethu.aiavatar_sdk.skills.DemoSkillHost
import com.neethu.aiavatar_sdk.skills.isSkillGestureAsset
import com.neethu.aiavatar_sdk.skills.lookHereAssets
import com.neethu.aiavatar_sdk.skills.rpsHandAssets
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
/** 场景选择持久化（空串 = 无场景，纯色背景）。 */
private const val KEY_SELECTED_SCENE = "selected_scene"
/** 选中模型持久化（内置或导入，均按文件名）。 */
private const val KEY_SELECTED_MODEL = "selected_model"
/** 无持久化存档时的默认模型（assets/vrms 内置）。 */
private const val DEFAULT_MODEL = "FreeTestCharacterAsuna_1024.vrm"
private const val KEY_APP_LANGUAGE = "app_language"
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
/** IBL 默认亮度 5000→13000（2026-10 调亮）的一次性迁移标记。 */
private const val KEY_RENDER_IBL_MIGRATED = "render_ibl_migrated_13000"

/**
 * 表情模仿帧的新鲜窗（「模仿我」P2）：表情隔帧 ~160ms 采样，容忍 2-3 个丢帧；
 * 超龄帧不再喂 FaceDriver（通道让位回情绪/眨眼）——否则人脸离开画面/技能退场
 * 后最后一帧表情会永久钉在脸上。与 UserCameraTracker 的头部矩阵保鲜窗同量级。
 */
private const val MIMIC_FACE_FRESH_MS = 500L

/** 新上下文的随机 id（UUID；ai_cmd select_context 支持前缀匹配）。 */
private fun newContextId(): String = java.util.UUID.randomUUID().toString()

/**
 * 卡片人设应用到会话：卡片默认人设（英文模式下预置中文卡替换为英文人设）+
 * 设置页人工编辑覆盖（session 重建也必须重放覆盖，否则编辑会被 assemble 冲掉）。
 * 预置 grid、导入列表、adb 导入共用。
 */
private fun applyCardToSession(
    session: AvatarSession,
    entry: CharacterCardStore.Entry,
    overrideFor: (String) -> String?,
    lang: Lang,
    presetAssetByFile: (String) -> String? = { null },
) {
    val assetFile = presetAssetByFile(entry.fileName)?.substringAfterLast('/')
    session.setCharacterCard(PresetCardsEn.translate(entry.card, assetFile, lang) ?: entry.card)
    overrideFor(entry.fileName)?.takeIf { it.isNotEmpty() }?.let { session.systemPrompt = it }
}

/** 语言化后的卡片对象（预置中文卡在英文模式下替换为英文人设；其余原样）。 */
private fun localizedCard(
    uiState: DemoUiState,
    entry: CharacterCardStore.Entry,
): CharacterCard {
    val assetFile = uiState.presetAssetByFile(entry.fileName)?.substringAfterLast('/')
    return PresetCardsEn.translate(entry.card, assetFile, uiState.lang) ?: entry.card
}

/** 卡片显示名（英文模式下预置中文卡显示英文名）。 */
private fun localizedCardName(
    entry: CharacterCardStore.Entry,
    lang: Lang,
    presetAssetByFile: (String) -> String? = { null },
): String {
    val assetFile = presetAssetByFile(entry.fileName)?.substringAfterLast('/')
    return PresetCardsEn.translate(entry.card, assetFile, lang)?.name ?: entry.card.name
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
    // 老版本把当时的默认 5000 随全字段持久化写进了存档，只改代码默认值对
    // 已有设备无效——首启把存档里的 IBL 强制刷成新默认，其余字段不动；
    // 此后用户手调的值照常持久化，不会被再刷。
    if (!getBoolean(KEY_RENDER_IBL_MIGRATED, false)) {
        edit()
            .putBoolean(KEY_RENDER_IBL_MIGRATED, true)
            .putFloat("render_iblIntensity", defaults.iblIntensity)
            .apply()
    }
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

    /** applicationContext：读系统语言（配置区）用，判定「跟随系统」的生效语言。 */
    private val appContext = context.applicationContext

    // ── 界面语言（多语言支持）────────────────────────────────────────────
    /** 语言偏好（跟随系统/中文/英文），持久化；生效语言见 [lang]。 */
    var appLangPref by mutableStateOf(prefs.enumValue(KEY_APP_LANGUAGE, AppLang.SYSTEM))
        private set

    /** 生效语言：偏好按系统语言解析（系统中文→简体，否则英文）。 */
    val lang: Lang
        get() = resolveAppLang(
            appLangPref,
            appContext.resources.configuration.locales[0].language,
        )

    /** 非组合代码（调试命令/错误回调）取文案表：总是按当前语言现建。 */
    fun strings(): Strings = Strings(lang)

    /** 切换界面语言并持久化；会话由 produceState 依 lang 重建（提示词换语言）。 */
    fun setAppLang(pref: AppLang) {
        if (pref == appLangPref) return
        appLangPref = pref
        prefs.edit().putString(KEY_APP_LANGUAGE, pref.name).apply()
    }

    /** APK 内置模型（assets/vrms，随包只读）。 */
    val builtinModelFiles: List<String> = listAssets(context, "vrms") {
        it.endsWith(".glb") || it.endsWith(".vrm")
    }

    /** 已导入模型（filesDir/vrms，[importModel]/[importModelBytes] 后刷新）。 */
    var importedModelFiles by mutableStateOf(ImportedModelLibrary.list(context))
        private set

    /** 模型面板全列表 = 内置 + 导入（导入名防撞，绝不遮蔽内置）。 */
    val modelFiles: List<String> get() = builtinModelFiles + importedModelFiles

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

    /**
     * 拟人感设置（呼吸/视线/眨眼的开关与参数）。持久化；变更经会话实时生效
     * （见下方 applyLivenessEffect），不重建会话。
     */
    var motionSettings by mutableStateOf(prefs.loadMotionSettings())
        private set

    fun updateMotionSettings(m: MotionSettings) {
        motionSettings = m
        prefs.saveMotionSettings(m)
    }

    /**
     * 自由说话灵敏度（起音/打断门限+切句停顿）。持久化；变更经
     * FreeSpeechController.applyTuning 对采音中的 VAD 实时生效，不需重启聆听。
     */
    var freeSpeechSettings by mutableStateOf(prefs.loadFreeSpeechSettings())
        private set

    fun updateFreeSpeechSettings(s: FreeSpeechSettings) {
        freeSpeechSettings = s
        prefs.saveFreeSpeechSettings(s)
    }

    /**
     * 当前模型（内置或导入的文件名）。持久化（[KEY_SELECTED_MODEL]）；存档
     * 失效（导入文件被删）回落 [DEFAULT_MODEL]。写入口统一走 [selectModel]，
     * [LaunchedEffect] 据此加载（导入模型按绝对路径，内置走 assets）。
     */
    var selectedModel by mutableStateOf(initialSelectedModel())
        private set

    /** 切换模型并持久化；动画选择随模型作废。同名 no-op。 */
    fun selectModel(name: String) {
        if (name == selectedModel) return
        selectedModel = name
        selectedAnimation = null
        prefs.edit().putString(KEY_SELECTED_MODEL, name).apply()
    }

    /** 存档名仍存在（内置或已导入）才采纳，否则默认模型。 */
    private fun initialSelectedModel(): String {
        val stored = prefs.getString(KEY_SELECTED_MODEL, null)
        return if (stored != null && (stored in builtinModelFiles || stored in importedModelFiles)) stored
        else DEFAULT_MODEL
    }
    var selectedAnimation by mutableStateOf<String?>(null)
    var selectedExpression by mutableStateOf<String?>(null)
    /**
     * 场景选择：null = 无场景（纯色背景）。持久化（空串存档=null），面板「无」
     * 选项或 `ai_cmd load_scene none` 切换，写入口统一走 [setScene]。
     * 判定用 [SharedPreferences.contains] 区分「无存档」（升级首启→默认第一个
     * 场景）与「存档=无场景」（空串）——Elvis 兜底会把后者吞回第一个场景。
     */
    var selectedScene: String? by mutableStateOf(
        if (prefs.contains(KEY_SELECTED_SCENE)) prefs.getString(KEY_SELECTED_SCENE, null)?.ifEmpty { null }
        else sceneFiles.firstOrNull()
    )
        private set

    /** 切换场景（null = 无场景）；持久化，[LaunchedEffect] 据此加载/移除场景。 */
    fun setScene(name: String?) {
        selectedScene = name
        prefs.edit().putString(KEY_SELECTED_SCENE, name ?: "").apply()
    }
    var activePanel by mutableStateOf(PanelType.NONE)
    var isDragMode by mutableStateOf(false)

    /**
     * 新手引导面板显隐（ui/OnboardingGuide.kt）。触发判定（未配 Key：中文→国内
     * 版硅基流动，其余→海外版 OpenRouter）在 DemoScreen；这里只是显隐位——
     * adb `open_panel guide` 也走它强制打开。
     */
    var guideVisible by mutableStateOf(false)

    /**
     * adb `open_panel guide_cn|guide_intl` 强制指定的受众；null=按界面语言自动
     * （DemoScreen 计算）。仅影响面板展示，不改变触发判定。
     */
    var guideVariantOverride by mutableStateOf<GuideVariant?>(null)

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
    fun videoModeBlockReason(s: Strings): String? = when {
        !aiPrefs.isConfigured -> s.videoNeedsConfig
        !isVisionLlm(aiPrefs.provider, aiPrefs.llmModel) ->
            s.videoNeedsVision(resolveLlmModel(aiPrefs.provider, aiPrefs.llmModel))
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

    // ── 模型导入（filesDir/vrms，与内置模型同列表同加载路径）──────────────

    /** 从 SAF Uri 导入 VRM/GLB；成功返回落盘文件名并刷新列表，null = 不可读或不是 GLB。 */
    fun importModel(context: Context, uri: Uri): String? {
        val name = ImportedModelLibrary.import(context, uri) ?: return null
        importedModelFiles = ImportedModelLibrary.list(context)
        return name
    }

    /** 字节级导入（adb 调试命令用），与 UI 导入同一条落盘/防撞路径。 */
    fun importModelBytes(bytes: ByteArray, suggestedName: String?): String? {
        val name = ImportedModelLibrary.importBytes(appContext, bytes, suggestedName) ?: return null
        importedModelFiles = ImportedModelLibrary.list(appContext)
        return name
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
    "openrouter", "or", "openr" -> AiProvider.OPENROUTER
    else -> throw IllegalArgumentException(
        "expects siliconflow|volcano|openrouter, got '$arg'"
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

    // ── 界面语言（多语言支持）：语言变化 = 重组全部 UI 文案 + 重建 AI 会话 ──
    val strings = remember(uiState.lang) { Strings(uiState.lang) }

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

    // Load the selected model whenever it changes. 导入模型（filesDir/vrms）按
    // 绝对路径加载，内置模型走 assets——两条入口共用同一渲染管线（loadModelBytes）。
    val loadSelectedModel: (Boolean) -> Unit = { force ->
        uiState.selectedExpression = null
        controller.clearAllExpressions()
        val imported = ImportedModelLibrary.modelFile(context, uiState.selectedModel)
        if (imported != null) controller.loadModelFromFile(imported.absolutePath, forceReload = force)
        else controller.loadModel("vrms/${uiState.selectedModel}", forceReload = force)
    }
    LaunchedEffect(uiState.selectedModel) {
        loadSelectedModel(false)
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

    // Load the selected scene whenever it changes (null = 无场景，移除当前场景)
    LaunchedEffect(uiState.selectedScene) {
        val scene = uiState.selectedScene
        if (scene != null) controller.loadScene("scene/$scene") else controller.removeScene()
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
        if (materialReverted) loadSelectedModel(true)
    }

    // ── AI 对话：装配 AvatarSession 并订阅其状态 ──────────────────────────
    val scope = rememberCoroutineScope()
    // 技能框架（docs/rps-skill-feasibility.md §4.3）：host 先于会话创建（懒
    // 引用，session/freeSpeech/videoTracker 就绪后回填 provider），随会话
    // 构造传入；RpsSkill 实例跨会话重建保持状态（局数/激活态）。
    val skillHost = remember { DemoSkillHost(scope) }
    val rpsSkill = remember { RpsSkill(rpsHandAssets) }
    val lookHereSkill = remember { LookHereSkill(lookHereAssets) }
    val mimicSkill = remember { MimicSkill() }
    // 技能指令行/宣判词随界面语言切换（实例跨会话重建保持状态）
    LaunchedEffect(uiState.lang) {
        rpsSkill.lang = uiState.lang
        lookHereSkill.lang = uiState.lang
        mimicSkill.lang = uiState.lang
    }
    val aiChat = remember {
        AiChatController(scope, controller, context.applicationContext, skillHost)
    }

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

    // 设置面板的滚动位置与分类展开状态：Activity 级持有，面板关开不丢失
    val settingsExpandedSections = rememberSaveable {
        mutableStateOf(setOf(
            SECTION_LANGUAGE, SECTION_AI, SECTION_CARD, SECTION_CONTEXT,
            SECTION_ANIMATIONS, SECTION_QUALITY, SECTION_LIVENESS, SECTION_FREE_SPEECH,
        ))
    }
    val settingsListState = rememberLazyListState()

    // 会话身份 = AI 配置 + 上下文 id：任一变化（含新建/切换上下文）即重建
    val session by produceState<AvatarSession?>(
        initialValue = null, state, uiState.aiPrefs, uiState.useExternalAnimations,
        uiState.contextId, uiState.lang,
    ) {
        // 设置页逐字符提交配置；不等输入停稳就 ensure 会把会话每个按键重建一次，
        // 正在播放/合成的回合被反复杀掉（表现为"还没输完就不出声了"）。
        delay(800)
        value = aiChat.ensure(
            prefs = uiState.aiPrefs,
            ready = state is AvatarState.Ready,
            contextId = uiState.contextId,
            characterId = uiState.activeCardFile,
            lang = uiState.lang,
        )?.also { s ->
            // 动作目录 = 内置库全量扫描（分类子文件夹 → tag），外置模式追加
            // 外置库。待机不在这里挂——它属于渲染控制器而非 AI 会话（见上方
            // LaunchedEffect(state) 的 applyIdle），未配置 AI 也有待机。
            // 技能手势目录（11_技能_猜拳）排除在 LLM 目录之外：`<act:>` 广告
            // 位不收技能 tag，技能走 session.playGestureFile 非广告通道。
            val assetPaths = uiState.assetAnimationPaths(context).filterNot { isSkillGestureAsset(it) }
            val externalFiles =
                if (uiState.useExternalAnimations) {
                    uiState.externalAnimationAbsolutePaths(context).filterNot { isSkillGestureAsset(it) }
                } else {
                    emptyList()
                }
            s.actionCatalog = buildLlmActionCatalog(assetPaths, externalFiles)
            s.skills.register(rpsSkill)
            s.skills.register(lookHereSkill)
            s.skills.register(mimicSkill)
        }
    }

    // 模型（重）加载完成后刷新 FaceDriver 的表情集合：ensure 只在会话首次
    // Ready 时启动驱动，同一会话内换模型（含 adb load_model）新模型的 morph
    // 名要重新过 send 门控（FaceDriver.start 二次调用=刷新语义）。
    LaunchedEffect(state) {
        if (state is AvatarState.Ready) session?.startFaceDriving()
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

    // 拟人感设置（呼吸/视线/眨眼）→ 会话实时生效；会话重建后自动重放
    LaunchedEffect(session, uiState.motionSettings) {
        val m = uiState.motionSettings
        session?.applyLivenessSettings(
            breathEnabled = m.breathEnabled,
            breathAmplitude = m.breathAmplitude,
            breathRateBpm = m.breathRateBpm,
            saccadeEnabled = m.saccadeEnabled,
            saccadeJitter = m.saccadeJitter,
            blinkEnabled = m.blinkEnabled,
            blinkIntervalS = m.blinkIntervalS,
        )
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
                applyCardToSession(s, entry, { fileName -> uiState.cardPromptOverride(fileName) }, uiState.lang, uiState::presetAssetByFile)
                if (deferredActivation) {
                    uiState.pendingGreetingFile = null
                    val greeting = localizedCard(uiState, entry).spokenGreeting(promptTextsOf(uiState.lang).userAlias)
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
                        chatError = uiState.strings().sentenceFailed(ev.sequence, ev.message)
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
                        shotLabel = ev.shot.label(uiState.lang)
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
                                    shotLabel = CameraShot.CLOSE_UP.label(uiState.lang)
                                }
                            }
                        }
                    }
                    is AvatarEvent.TurnFailed -> {
                        chatError = ev.error.message ?: uiState.strings().turnFailed
                        Log.e(AI_LOG_TAG, "[InfoStreamDectect] chat: TurnFailed: ${ev.error.message}")
                    }
                    is AvatarEvent.PlaybackInterrupted ->
                        chatLog("PlaybackInterrupted")
                    is AvatarEvent.SkillEvent ->
                        // 技能进度（激活/出拳/裁判/退场），排查技能时序全靠它
                        chatLog("Skill[${ev.skillId}] ${ev.detail}")
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

    // ── 新手引导（ui/OnboardingGuide.kt）：AI 还没配好 → 未配 Key 期间每次
    // 冷启动都弹（中文系统=国内版硅基流动，其余语言=海外版 OpenRouter）；
    // 用户可关闭，点输入框/语音框会再弹（见 AiChatBar 的 onTapWhenDisabled）。
    // Key 配置好后 guideVariant 恒 null。
    val guideVariant = onboardingGuideVariant(uiState.lang, uiState.aiPrefs.isConfigured)
    LaunchedEffect(Unit) { if (guideVariant != null) uiState.guideVisible = true }

    val onGuideConfirm: (String) -> Unit = { key ->
        guideVariant?.let { v ->
            // 一键配置：大模型选免费+带视觉的落点（国内 Qwen3.8-27B 多模态 /
            // 海外 openrouter/free）；海外 TTS=Edge-TTS（避开 OpenRouter 音频端点
            // 的 ≥$0.5 余额门槛）、ASR=系统内置（免费）；国内 TTS/ASR 走硅基流动
            // 默认。签名变化 → updateAiPrefs 轮换一次上下文（新用户无感）
            uiState.updateAiPrefs(withOnboardingKey(uiState.aiPrefs, key, v))
            uiState.updateVoicePrefs(withOnboardingAsr(uiState.voicePrefs, v))
            uiState.guideVisible = false
            chatError = null
            Toast.makeText(context, strings.guideConfiguredToast(v.provider), Toast.LENGTH_LONG).show()
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
        if (!granted) chatError = uiState.strings().micPermissionNeeded
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
        if (!granted) chatError = uiState.strings().cameraPermissionNeeded
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
            shotLabel = CameraShot.CLOSE_UP.label(uiState.lang)
        } else {
            videoTracker.stop()
            session?.faceDriver?.setGazeMode(GazeMode.CAMERA)
            if (session == null) controller.clearLookAtTarget()
        }
    }

    // 「模仿我」调试/开关状态（先于两个消费循环声明——Kotlin 局部变量顺序）
    var mimicForcedPreset by remember { mutableStateOf<String?>(null) }
    var mimicFaceEnabled by remember { mutableStateOf(true) }

    // 视线消费（~30Hz）：追踪器的新人脸观测 → FaceDriver POINT 注入缝；人脸
    // 离开画面 >1.5s 回退 CAMERA（看着镜头等用户回来）。无会话时直驱 controller。
    // 顺路消费「模仿我」车道（同一节奏）：最新目标 → 渲染引擎（原子换手），
    // 可见性状态变化才广播给技能层（降级阶梯在技能内部计时）
    LaunchedEffect(uiState.inputMode, session) {
        if (uiState.inputMode != InputMode.VIDEO) return@LaunchedEffect
        var lastNanos = System.nanoTime()
        var lastBodyVisSent: Boolean? = null
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
            videoTracker.latestMimicPose?.let { controller.setMimicPose(it) }
            val vis = videoTracker.latestBodyVisible
            if (vis != lastBodyVisSent) {
                lastBodyVisSent = vis
                session?.skills?.onBodyTracking(vis)
            }
            // 表情模仿（P2）：52 ARKit blendshapes → MimicFaceMapper 映射成模型
            // morph 名（FaceDriver 的契约：setMimicFace 只收模型已有的名字，原始
            // ARKit 名在 VRM 预设命名的模型上一个都对不上），再喂表情通道。
            // 门控三连——用户开关（mimic_face A/B）/技能激活（车道关闭即让位）/
            // 帧龄（人脸离开画面或车道断供超窗）：任一不满足传 null，FaceDriver
            // 把上次驱着的 morph 一次性归零并让情绪/眨眼接回，表情不会钉死在
            // 最后一帧上。映射结果为空（模型无任何对应 morph）同样传 null，别让
            // 空 map 空占通道压死情绪。
            session?.faceDriver?.let { fd ->
                val mapped = videoTracker.latestMimicFace
                    ?.takeIf {
                        mimicFaceEnabled && mimicSkill.isActive &&
                            SystemClock.elapsedRealtime() - it.atMs <= MIMIC_FACE_FRESH_MS
                    }
                    ?.let { MimicFaceMapper.map(it.shapes, fd.availableExpressions) }
                fd.setMimicFace(mapped?.takeIf { it.isNotEmpty() })
            }
        }
    }

    // 「模仿我」常驻循环（任意输入模式，~15Hz）：①mimic_force 合成姿态续时戳
    // （免相机 A/B，引擎按新鲜度消费）②激活时不在视频模式→自动切换（模仿不挑
    // 模型，不走 videoModeBlockReason 的视觉门控；相机权限由进模式的 effect 补
    // 申请）③技能退场/撤销合成姿态→引擎立即缓动回待机（不等 600ms 过期自愈）
    // ④呼吸让位（P2 躯干通道与呼吸同骨组，模仿期关呼吸，退场恢复用户设置）
    var breathYielded = false
    var wasActivePrev = false
    LaunchedEffect(Unit) {
        while (true) {
            delay(66)
            val forced = mimicForcedPreset
            if (forced != null) {
                PoseMimicMath.forcedPreset(forced, SystemClock.elapsedRealtime())
                    ?.let { controller.setMimicPose(it) }
            }
            if (mimicSkill.isActive && uiState.inputMode != InputMode.VIDEO) {
                uiState.switchInputMode(InputMode.VIDEO)
            }
            val active = mimicSkill.isActive || forced != null
            if (active) {
                controller.setBreathEnabled(false)
                breathYielded = true
            } else {
                if (breathYielded) {
                    controller.setBreathEnabled(uiState.motionSettings.breathEnabled)
                    breathYielded = false
                }
                if (!mimicSkill.isActive && forced == null && videoTracker.latestMimicPose == null) {
                    // 幂等兜底：确保没有残留姿态目标（缓动自愈已在引擎侧）
                }
            }
            if (wasActivePrev && !active) controller.setMimicPose(null)
            wasActivePrev = active
        }
    }

    /**
     * 每次识别现建适配器读最新 prefs；缓存实例会在改端点后用旧地址。
     * ASR 跟随大模型服务商（OpenAI 兼容 /audio/transcriptions，硅基流动与
     * OpenRouter 同构，key 各自共用大模型那份）；火山无该形态端点，回落
     * 硅基流动（见 [asrProviderFor]）。
     */
    fun asrFor(): OpenAiCompatibleAsrAdapter {
        val p = asrProviderFor(uiState.aiPrefs.provider)
        val key = uiState.aiPrefs.apiKeyFor(p)
        check(key.isNotBlank()) { uiState.strings().asrNeedsKey(p) }
        return OpenAiCompatibleAsrAdapter(p.baseUrl, key)
    }

    /** ASR 模型解析：端点随大模型服务商（显式配置过的用显式值）。 */
    fun asrConfig(): AsrConfig = AsrConfig(
        model = resolveAsrModel(asrProviderFor(uiState.aiPrefs.provider).baseUrl, uiState.voicePrefs.asrModel),
    )

    // ── 自由说话（连续聆听 + VAD 自动断句，按住/自由按钮切换）─────────────
    val freeSpeech = remember {
        val s = uiState.freeSpeechSettings
        FreeSpeechController(context, vad = SpeechVad(
            startAbsolute = s.startAbsolute,
            bargeAbsolute = s.bargeAbsolute,
            hangoverMs = s.hangoverMs.toLong(),
        ))
    }
    // 设置页改灵敏度/切句停顿：对采音中的 VAD 实时生效
    LaunchedEffect(uiState.freeSpeechSettings) {
        val s = uiState.freeSpeechSettings
        freeSpeech.applyTuning(s.startAbsolute, s.bargeAbsolute, s.hangoverMs.toLong())
    }
    var freeHearing by remember { mutableStateOf(false) }
    // 并发句串行:上一句还在 ASR 时新一句排队,防止识别结果乱序发送
    val freeAsrChain = remember { kotlinx.coroutines.sync.Mutex() }

    // ── 系统内置语音识别（免费无 Key，海外用户主场景；SystemAsr.kt）────────
    // 与 freeSpeech（云端链路采音+VAD）互斥运行：freeTalk 引擎=SYSTEM 时用它
    val systemAsr = remember { SystemAsrController(context) }
    // 识别语言/错误文案随界面语言（SYSTEM 偏好 = 跟随系统默认识别语言）
    LaunchedEffect(uiState.lang, uiState.appLangPref) {
        systemAsr.texts = Strings(uiState.lang)
        systemAsr.languageTag = when (uiState.appLangPref) {
            AppLang.ZH -> "zh-CN"
            AppLang.EN -> "en-US"
            AppLang.SYSTEM -> null
        }
    }
    systemAsr.onPartial = { text -> freeHearing = text.isNotEmpty() }
    systemAsr.onError = { msg -> scope.launch { chatError = uiState.strings().systemAsrError(msg) } }
    systemAsr.onConsentNeeded = {
        scope.launch {
            chatError = uiState.strings().systemAsrConsent
        }
    }
    systemAsr.onFinal = { text, singleShot ->
        scope.launch {
            if (singleShot) {
                // 按住说话（系统识别版）：终稿或错误直接落同一条发送链
                voiceRecognizing = false
                if (text.isEmpty()) {
                    chatError = uiState.strings().noSpeechHeard
                    return@launch
                }
                if (uiState.voicePrefs.autoSend) {
                    pushUserLine(text)
                    val consumed = session?.skills?.onUtterance(text) ?: false
                    if (!consumed) session?.send(text, videoSnapshotImages())
                } else {
                    voicePrefill = text
                }
            } else {
                // 自由说话（系统识别版）：与云端同一条发送缝；空终稿=服务端
                // 噪声断句，静默忽略（同云端空识别语义，但不报错）
                freeAsrChain.withLock {
                    voiceRecognizing = true
                    if (text.isNotEmpty()) {
                        pushUserLine(text)
                        // 技能快路径：系统识别没有 WAV 可测时长，用文本量估——
                        // 短句（≤2.5s）仍是猜拳出拳信号，然后文本即裁判回合输入
                        session?.skills?.onVadUtterance(estimateSpeechMs(text))
                        val consumed = session?.skills?.onUtterance(text) ?: false
                        if (!consumed) session?.send(text, videoSnapshotImages())
                    }
                    voiceRecognizing = false
                }
            }
        }
    }

    // 回调在采音线程触发,统一 post 回主协程操作 UI/会话
    freeSpeech.onBargeIn = { scope.launch { session?.interrupt() } }
    freeSpeech.onHearingChanged = { hearing -> freeHearing = hearing }
    freeSpeech.onUtterance = { wav ->
        scope.launch {
            // 技能快路径（docs/rps-skill-feasibility.md §2 P0）：ASR 之前先把
            // 「时机」给技能——猜拳 ARMED 态的短句=出拳信号，本地随机出手势+
            // 抓帧，不等识别；ASR 文本稍后经 onUtterance 到达（裁判回合输入）
            session?.skills?.onVadUtterance(wavDurationMs(wav))
            freeAsrChain.withLock {
                voiceRecognizing = true
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        asrFor().transcribe(wav, mimeForFileName("utterance.wav"), asrConfig())
                    }
                }
                voiceRecognizing = false
                // 技能先看文本（激活/退出/裁判）；消费=技能已自行发起回合，
                // 调用方跳过默认发送。ASR 失败也送空串：出拳已发生，图照样裁判。
                val text = result.getOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) pushUserLine(text)
                val consumed = session?.skills?.onUtterance(text) ?: false
                when {
                    consumed -> if (text.isEmpty()) pushUserLine(uiState.strings().threwPlaceholder)
                    text.isEmpty() -> result.onFailure { t -> chatError = uiState.strings().asrFailed(t.message) }
                    else -> session?.send(text, videoSnapshotImages())
                }
            }
        }
    }

    // 文案表随界面语言（控制器异常消息的语言）
    LaunchedEffect(strings) {
        voiceRecorder.texts = strings
        freeSpeech.texts = strings
    }

    // 技能能力缝的懒引用回填：此刻 freeSpeech/videoTracker 已就绪，session 由
    // produceState 持续更新（读的是最新值）
    skillHost.sessionProvider = { session }
    skillHost.snapshotProvider = { videoTracker.snapshotDataUrl() }
    skillHost.vadTuner = { ms ->
        val s = uiState.freeSpeechSettings
        freeSpeech.applyTuning(s.startAbsolute, s.bargeAbsolute, ms)
    }
    skillHost.defaultHangoverMsProvider = { uiState.freeSpeechSettings.hangoverMs.toLong() }
    // 猜拳 P2 手势车道（docs/rps-skill-feasibility.md §5）：技能激活（INVITED 起，
    // 顺带预热引擎）才在分析线程跑 MediaPipe；确认手势主线程广播给全部技能。
    // 无事件时零开销（不转位图不建引擎），MediaPipe 不可用则纯走 P0 语音路径。
    // 「看这边」的头部姿态车道互斥优先（同一分析线程不并跑两个 MediaPipe 任务）。
    videoTracker.gestureEnabled = { rpsSkill.isActive && !lookHereSkill.isActive }
    videoTracker.headPoseEnabled = { lookHereSkill.isActive }
    // 「模仿我」身体车道（docs/mimic-skill-feasibility.md §8）：第三条 MediaPipe
    // 车道，与手势/头姿态互斥（同一分析线程），仅技能激活+前摄时跑
    videoTracker.bodyMimicEnabled = { mimicSkill.isActive && !lookHereSkill.isActive && !rpsSkill.isActive }
    // 表情车道开关（P2）：仅用户 A/B 开关（mimic_face）；技能互斥与"仅技能激活
    // 才咨询"由 wantBody 短路收口，这里不该再叠条件（否则双层门控查状态时会互相糊）
    videoTracker.faceMimicEnabled = { mimicFaceEnabled }
    videoTracker.onGestureConfirmed = { code ->
        scope.launch { session?.skills?.onUserGesture(code) }
    }
    videoTracker.onHeadPose = { yaw, pitch ->
        scope.launch { session?.skills?.onHeadPose(yaw, pitch) }
    }
    skillHost.snapshotLatestProvider = { videoTracker.snapshotLatestDataUrl() }
    freeSpeech.isAvatarSpeaking = { chatPhase == ConversationPhase.SPEAKING }

    val onToggleFreeTalk: () -> Unit = {
        val new = !uiState.voicePrefs.freeTalk
        val s = uiState.strings()
        uiState.updateVoicePrefs(uiState.voicePrefs.copy(freeTalk = new))
        when {
            new && !micGranted -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
            new && uiState.voicePrefs.asrEngine == AsrEngine.CLOUD &&
                uiState.aiPrefs.apiKeyFor(AiProvider.SILICONFLOW).isBlank() ->
                chatError = s.freeTalkCloudNeedsKey
            new && uiState.voicePrefs.asrEngine == AsrEngine.SYSTEM && !systemAsr.isAvailable() ->
                chatError = s.freeTalkNoSystemAsr
            new -> chatError = s.freeTalkEnabled(uiState.voicePrefs.asrEngine)
        }
    }

    // 自由说话生命周期：语音/视频模式 + 开关开 + 麦克风权限 + 识别来源就绪
    // （云端=有硅基流动 Key；系统=平台识别服务可用）。两引擎互斥运行。
    LaunchedEffect(
        uiState.inputMode, uiState.voicePrefs.freeTalk, micGranted, uiState.voicePrefs.asrEngine,
    ) {
        val system = uiState.voicePrefs.asrEngine == AsrEngine.SYSTEM
        val want = micGranted && uiState.voicePrefs.freeTalk &&
            (uiState.inputMode == InputMode.VOICE || uiState.inputMode == InputMode.VIDEO) &&
            if (system) systemAsr.isAvailable()
            else uiState.aiPrefs.apiKeyFor(AiProvider.SILICONFLOW).isNotBlank()
        if (want && system) {
            freeSpeech.stop()
            runCatching { systemAsr.startContinuous() }
                .onFailure { chatError = uiState.strings().freeTalkStartFailed(it.message) }
        } else if (want) {
            systemAsr.stop()
            runCatching { freeSpeech.start(echoCancellation = uiState.inputMode == InputMode.VIDEO) }
                .onFailure { chatError = uiState.strings().freeTalkStartFailed(it.message) }
        } else {
            freeSpeech.stop()
            systemAsr.stop()
        }
    }

    // 系统识别的半双工：虚拟人 SPEAKING 期暂停聆听（防 TTS 自回声进识别器），
    // 回合结束/打断后带 600ms 宽限恢复；云端引擎保持 VAD barge-in 不受影响
    LaunchedEffect(uiState.voicePrefs.asrEngine) {
        if (uiState.voicePrefs.asrEngine != AsrEngine.SYSTEM) return@LaunchedEffect
        snapshotFlow { chatPhase }.collect { phase ->
            systemAsr.setPaused(phase == ConversationPhase.SPEAKING)
        }
    }

    val onHoldStart: () -> Unit = {
        when {
            voiceRecording || voiceRecognizing -> Unit
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED ->
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            uiState.voicePrefs.asrEngine == AsrEngine.SYSTEM -> {
                // 半双工同云端：按下先打断正在播的回复；系统识别单发监听，
                // 松手取终稿（onFinal singleShot=true 回调落发送链）
                if (chatPhase == ConversationPhase.SPEAKING) session?.interrupt()
                try {
                    systemAsr.startSingleShot()
                    voiceRecording = true
                } catch (e: IllegalStateException) {
                    chatError = e.message ?: uiState.strings().systemAsrStartFailed
                }
            }
            else -> {
                // 半双工（对齐 AIRI 说话时抑制聆听）：按下的瞬间打断正在播的回复
                if (chatPhase == ConversationPhase.SPEAKING) session?.interrupt()
                try {
                    // 视频模式走 VOICE_COMMUNICATION 音源（平台硬件 AEC）：
                    // 扬声器里的虚拟人声音不再混进 ASR 录音
                    voiceRecorder.start(echoCancellation = uiState.inputMode == InputMode.VIDEO)
                    voiceRecording = true
                } catch (e: IllegalStateException) {
                    chatError = e.message ?: uiState.strings().recordStartFailed
                }
            }
        }
    }
    val onHoldEnd: () -> Unit = {
        if (systemAsr.singleShot) {
            // 系统识别：松手=句尾，终稿异步到达（onFinal），期间亮"识别中"
            voiceRecording = false
            voiceRecognizing = true
            systemAsr.stopListening()
        } else if (voiceRecorder.isRecording) {
            voiceRecording = false
            val file = voiceRecorder.stop()
            if (file == null) {
                chatError = uiState.strings().noAudioRecorded
            } else {
                voiceRecognizing = true
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                    // 松手=句尾：技能快路径与自由说话同源（猜拳短句=出拳信号）
                    session?.skills?.onVadUtterance(wavDurationMs(bytes))
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            asrFor().transcribe(bytes, mimeForFileName(file.name), asrConfig())
                        }
                    }
                    file.delete()
                    voiceRecognizing = false
                    result.onSuccess { raw ->
                        val text = raw.trim()
                        if (text.isEmpty()) {
                            chatError = uiState.strings().noSpeechHeard
                        } else if (uiState.voicePrefs.autoSend) {
                            pushUserLine(text)
                            val consumed = session?.skills?.onUtterance(text) ?: false
                            if (!consumed) session?.send(text, videoSnapshotImages())
                        } else {
                            voicePrefill = text
                        }
                    }.onFailure {
                        chatError = uiState.strings().asrFailed(it.message)
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
            systemAsr.stop()
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
                    applyCardToSession(s, entry, applyOverrideFor, uiState.lang, uiState::presetAssetByFile)
                    s.clearHistory()
                    val greeting = localizedCard(uiState, entry).spokenGreeting(promptTextsOf(uiState.lang).userAlias)
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
            chatError = uiState.strings().presetCardUnreadable(preset.assetPath)
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
                chatError = uiState.strings().cardParseFailed
            } else {
                activateCard(entry)
            }
        }
    }

    // 导入模型：SAF 选 VRM/GLB → 落盘 filesDir/vrms → 自动选中 + 新建上下文
    //（每个模型自带表情集不同，协议目录/表情标签不跨模型复用，聊天上下文随
    // 模型轮换——与「换大模型即轮换上下文」同一语义）。MIME 用 */*：VRM 无
    // 标准类型，部分文件管理器报 octet-stream、部分报空，按扩展名过滤会漏。
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uiState.importModel(context, uri)
            if (name == null) {
                chatError = uiState.strings().modelImportFailed
            } else {
                uiState.selectModel(name)
                uiState.newContext()
                Toast.makeText(context, uiState.strings().modelImported(name), Toast.LENGTH_SHORT).show()
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
                    // 与打字路径同缝：技能先看文本，消费=技能已自行发起回合
                    //（rps_throw 后 send_chat "三二一" 即可真机驱动裁判回合）
                    val consumed = s.skills.onUtterance(text)
                    if (!consumed) s.send(text, videoSnapshotImages())
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
                    append("ttsEngine=${p.ttsEngine.name.lowercase()} ")
                    append("ttsProvider=${p.ttsProviderResolved.name.lowercase()}(same=${p.ttsSameProvider}) ")
                    append("ttsModel=${resolveTtsModel(p.ttsProviderResolved, p.ttsModel)} ")
                    append(
                        "voice=" + when (p.ttsEngine) {
                            TtsEngine.EDGE -> resolveEdgeVoice(p.voice)
                            TtsEngine.OPENAI_COMPATIBLE -> resolveVoice(p.ttsProviderResolved, p.voice)
                        } + " "
                    )
                    append("vision=${isVisionLlm(p.provider, p.llmModel)}")
                    if (uiState.inputMode == InputMode.VIDEO) append(" video=[${videoTracker.debugStatus()}]")
                    if (uiState.inputMode == InputMode.VOICE || uiState.inputMode == InputMode.VIDEO) {
                        append(" freeTalk=${uiState.voicePrefs.freeTalk}")
                        append(" asrEngine=${uiState.voicePrefs.asrEngine.name.lowercase()}")
                        if (uiState.voicePrefs.asrEngine == AsrEngine.SYSTEM) {
                            append("(avail=${systemAsr.isAvailable()}")
                            if (systemAsr.running) append(",listening" + (if (freeHearing) ",hearing" else ""))
                            append(")")
                        } else if (freeSpeech.running) {
                            append("(listening" + (if (freeHearing) ",hearing)" else ")"))
                        } else {
                            append("(off)")
                        }
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
                    val reason = uiState.videoModeBlockReason(uiState.strings())
                    if (reason != null) throw IllegalStateException(reason)
                }
                uiState.switchInputMode(mode)
                "input mode = ${mode.name.lowercase()} (${uiState.strings().inputMode(mode)})" +
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
                    error(uiState.strings().videoCameraNotRunning)
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
                    error(uiState.strings().videoCameraNotRunning)
                }
                val f = videoTracker.ring.best()
                    ?: error(uiState.strings().videoSnapshotEmpty)
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
            // ai_cmd set_asr cloud|system：切语音识别引擎（freeTalk 开着也会即时切换，
            // 生命周期 LaunchedEffect 的 asrEngine key 驱动两个控制器互斥启停）
            setAsrEngine = { arg ->
                val engine = when (arg?.lowercase()) {
                    "cloud", "cloud_asr", "sf", "openai" -> AsrEngine.CLOUD
                    "system", "google", "device" -> AsrEngine.SYSTEM
                    null -> throw IllegalArgumentException("set_asr expects cloud|system, got null")
                    else -> throw IllegalArgumentException("set_asr expects cloud|system, got '$arg'")
                }
                uiState.updateVoicePrefs(uiState.voicePrefs.copy(asrEngine = engine))
                "asrEngine=${engine.name.lowercase()} " +
                    if (engine == AsrEngine.SYSTEM) "(avail=${systemAsr.isAvailable()})" else "(cloud chain follows LLM provider)"
            },
            // ai_cmd set_language system|zh|en：界面语言切换（持久化）。会话身份含
            // 语言 → 自动重建、提示词按新语言重发；上下文不轮换，历史保留
            setLanguage = { arg ->
                val pref = when (arg?.lowercase()) {
                    "system", "auto" -> AppLang.SYSTEM
                    "zh", "cn", "chinese" -> AppLang.ZH
                    "en", "english" -> AppLang.EN
                    null -> throw IllegalArgumentException("set_language expects system|zh|en, got null")
                    else -> throw IllegalArgumentException("set_language expects system|zh|en, got '$arg'")
                }
                uiState.setAppLang(pref)
                "appLang=${pref.name.lowercase()} (effective=${uiState.lang.name.lowercase()}, " +
                    "prompts follow this language; history kept)"
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
            // ai_cmd skill_status / skill_exit / rps_throw <手> / rps_gesture <手>：技能调试
            skillDebug = { id, arg ->
                session?.skills?.debug(id, arg)
                    ?: throw IllegalStateException("no AI chat session (skills live on the session)")
            },
            // ai_cmd face_pose：最近一次头部姿态观测（「看这边」符号标定用）——
            // 数据在 videoTracker（分析线程写），不经技能注册表；直接显示系统
            // 按当前标定常量把该头姿解读成哪个屏幕方向，用户转头一对照即知
            // 符号布尔是否要翻
            facePose = {
                val p = videoTracker.latestHeadPose
                if (p == null) {
                    "no head pose yet (requires: look-here skill active + video mode + " +
                        "front camera + a face in frame)"
                } else {
                    val age = android.os.SystemClock.elapsedRealtime() - p.atMs
                    val believed = lookHereSkill.tuning.dominantDirection(p.yawDeg, p.pitchDeg)
                    val believedStr = believed?.label(uiState.lang) ?: "≈center"
                    ("yaw=%.1f pitch=%.1f age=%dms → system reads your head as: %s " +
                        "(hold your head turned to one side; if this label disagrees with " +
                        "reality, flip LookHereTuning.yawPositiveIsScreenLeft/pitchPositiveIsScreenUp)")
                        .format(p.yawDeg, p.pitchDeg, age, believedStr)
                }
            },
            // ai_cmd mimic_status：技能状态机 + 渲染引擎 + 相机车道三段汇总
            mimicStatus = {
                val skillLine = session?.skills?.debug("mimic", "status")
                    ?: "no session (mimic skill lives on the AI session)"
                val info = controller.getMimicInfo()
                val engineLine = info?.let {
                    "engaged=${it.engaged} restoring=${it.restoring} " +
                        "poseAge=${it.poseAgeMs}ms head=(yaw=%.0f°,pitch=%.0f°) ".format(
                            it.headYawDeg, it.headPitchDeg,
                        ) +
                        "torso=(pitch=%.0f°,roll=%.0f°,yaw=%.0f°)".format(
                            it.torsoPitchDeg, it.torsoRollDeg, it.torsoYawDeg,
                        )
                } ?: "renderer not attached"
                val laneLine = videoTracker.latestMimicPose?.let {
                    "age=${SystemClock.elapsedRealtime() - it.timestampMs}ms visible=${it.visible}"
                } ?: "no pose"
                val face = videoTracker.latestMimicFace
                val faceLine = face?.let {
                    "face: age=${SystemClock.elapsedRealtime() - it.atMs}ms " +
                        "shapes=${it.shapes.size} matrix=${it.matrix != null} " +
                        "feed=${if (mimicFaceEnabled) "on" else "off"}"
                } ?: "face: no frame"
                "$skillLine | engine: $engineLine | lane: $laneLine | $faceLine"
            },
            // ai_cmd mimic_pose：最近解算的镜像方向集（符号标定探针，mimic_force 同源）
            mimicPose = {
                val p = videoTracker.latestMimicPose
                    ?: error(
                        "no mimic pose yet (requires: mimic skill active + video mode + " +
                            "front camera + upper body in frame; or inject with mimic_force)"
                    )
                fun d(name: String, v: FloatArray?) =
                    v?.joinToString(prefix = "$name=(", postfix = ")", separator = ",") { "%.2f".format(it) }
                        ?: "$name=null"
                "avatar-frame directions (T-pose check: user-left arm should read Lu=(1,0,0) " +
                    "→ avatar-RIGHT, Ru=(-1,0,0); torso upright axis=(0,1,0) line=(1,0,0)): " +
                    "${d("head", p.headForward)} ${d("Lu", p.leftUpperArm)} ${d("Ll", p.leftLowerArm)} " +
                    "${d("Ru", p.rightUpperArm)} ${d("Rl", p.rightLowerArm)} " +
                    "${d("torso", p.torsoAxis)} ${d("sline", p.shoulderLine)} visible=${p.visible}"
            },
            // ai_cmd mimic_force <preset>：合成姿态注入（走真实解算路径，免相机 A/B）
            mimicForce = { arg ->
                when (arg) {
                    null, "status" ->
                        "forced preset = ${mimicForcedPreset ?: "(none)"} " +
                            "(options: ${PoseMimicMath.FORCED_PRESETS.joinToString("|")})"
                    "off", "none" -> {
                        mimicForcedPreset = null
                        "mimic force cleared (engine eases back)"
                    }
                    in PoseMimicMath.FORCED_PRESETS -> {
                        mimicForcedPreset = arg
                        "mimic forced preset=$arg — watch which side the avatar raises " +
                            "(mirror calibration entry; works without camera/skill)"
                    }
                    else -> throw IllegalArgumentException(
                        "mimic_force expects ${PoseMimicMath.FORCED_PRESETS.joinToString("|")}, got '$arg'"
                    )
                }
            },
            // ai_cmd mimic_face on|off（省略=查状态）：表情车道开关（P2 A/B）
            setMimicFace = { arg ->
                val new = when (arg) {
                    null, "status" -> mimicFaceEnabled
                    "on", "true", "1" -> true
                    "off", "false", "0" -> false
                    else -> throw IllegalArgumentException("mimic_face expects on|off, got '$arg'")
                }
                if (arg != null && arg != "status") mimicFaceEnabled = new
                "mimic face lane = ${if (mimicFaceEnabled) "on" else "off"} " +
                    "(blendshapes→FaceDriver; body lane unaffected)"
            },
        )
    }

    // AI debug interface: execute queued Intent commands for the screen's lifetime
    LaunchedEffect(aiCommands) {
        for (command in aiCommands) {
            executeAiCommand(context, controller, uiState, command, chatHooks)
        }
    }

    CompositionLocalProvider(LocalStrings provides strings) {
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
                        val reason = uiState.videoModeBlockReason(strings)
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
            // 未配置 AI 服务时点输入框/语音框 → 重新拉起新手引导（国内/海外按语言）
            onTapWhenDisabled = { if (guideVariant != null) uiState.guideVisible = true },
            onSend = { text ->
                pushUserLine(text)
                // 技能先看文本（激活/退出/裁判），消费=技能已自行发起回合
                val consumed = session?.skills?.onUtterance(text) ?: false
                if (!consumed) session?.send(text, videoSnapshotImages())
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
                    val s = LocalStrings.current
                    Text(text = s.fabMove, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Camera shot FAB (视角) — cycles the 4 preset framings
            SmallFloatingActionButton(
                onClick = {
                    val shots = CameraShot.entries
                    val next = shots[(shots.indexOf(currentShot) + 1).mod(shots.size)]
                    currentShot = next
                    controller.setCameraShot(next)
                    shotLabel = next.label(uiState.lang)
                },
                shape = RoundedCornerShape(50),
                containerColor = if (currentShot != null)
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                else
                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f)
            ) {
                Text(text = LocalStrings.current.fabView, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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

            // Character card FAB (人物卡, text like the camera FAB)
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
                Text(text = LocalStrings.current.fabCard, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
            val s = LocalStrings.current
            ListPanel(
                title = "Models",
                items = uiState.modelFiles,
                selectedItem = uiState.selectedModel,
                onItemClick = { fileName ->
                    if (fileName != uiState.selectedModel) {
                        uiState.selectModel(fileName)
                        // 换模型 = 换表情目录：协议目录/表情标签不跨模型复用，
                        // 上下文随模型轮换（导入自动选中走同一语义）
                        uiState.newContext()
                    }
                    uiState.activePanel = PanelType.NONE
                },
                header = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .clickable { modelPicker.launch(arrayOf("*/*")) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = s.importModel, fontSize = 14.sp)
                    }
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
            val s = LocalStrings.current
            ListPanel(
                title = s.animationsTitle,
                items = uiState.animationFiles,
                selectedItem = uiState.selectedAnimation,
                // 只隐藏顶层目录名（如 VRMA_Selected_Categorized），保留分类子文件夹
                displayName = {
                    val base = it.removeSuffix(".vrma").substringAfter('/')
                    if (idlePrefValueFor(
                            it, uiState.useExternalAnimations, uiState.externalAnimationsRoot(context)
                        ) == uiState.idleAnimation
                    ) "$base ${s.idleSuffix}" else base
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
                        Toast.makeText(context, s.idleSetFailed, Toast.LENGTH_SHORT).show()
                    } else {
                        val prefValue = idlePrefValueFor(fileName, uiState.useExternalAnimations, root)
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_IDLE_ANIMATION, prefValue).apply()
                        uiState.idleAnimation = prefValue
                        applyIdle(entry)
                        Toast.makeText(context, s.idleSet(entry.label), Toast.LENGTH_SHORT).show()
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
                items = listOf("") + uiState.sceneFiles,
                selectedItem = uiState.selectedScene ?: "",
                displayName = { if (it.isEmpty()) strings.sceneNone else it.removeSuffix(".glb").replace("_", " ") },
                onItemClick = { fileName ->
                    uiState.setScene(fileName.ifEmpty { null })
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
                presetNameFor = { preset ->
                    // 英文模式下预置中文卡显示英文名（grid 与激活名一致）
                    PresetCardsEn.translate(preset.card, preset.assetPath.substringAfterLast('/'), uiState.lang)
                        ?.name ?: preset.card.name
                },
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
            // 人物卡提示词 = 卡片默认人设（英文模式下预置中文卡为英文人设）+ 人工编辑覆盖
            val activeCardEntry = uiState.activeCardFile?.let { uiState.cardByFile(it) }
            val cardPromptDefault = activeCardEntry
                ?.let { SystemPromptAssembler().assemble(localizedCard(uiState, it)) }
                .orEmpty()
            val cardPromptOverride = uiState.activeCardFile?.let { uiState.cardPromptOverride(it) }
            SettingsScreen(
                settings = uiState.renderSettings,
                motionSettings = uiState.motionSettings,
                expandedSections = settingsExpandedSections.value,
                onToggleSection = { key ->
                    settingsExpandedSections.value =
                        if (key in settingsExpandedSections.value) settingsExpandedSections.value - key
                        else settingsExpandedSections.value + key
                },
                listState = settingsListState,
                useExternalAnimations = uiState.useExternalAnimations,
                externalRootPath = uiState.externalAnimationsRoot(context)?.absolutePath,
                aiPrefs = uiState.aiPrefs,
                voicePrefs = uiState.voicePrefs,
                appLangPref = uiState.appLangPref,
                onAppLangChange = { uiState.setAppLang(it) },
                appLang = uiState.lang,
                contexts = contextList,
                activeContextId = uiState.contextId,
                protocolPrompt = session?.protocolBlock().orEmpty(),
                cardNameFor = { characterId ->
                    uiState.cardByFile(characterId)?.let { localizedCardName(it, uiState.lang, uiState::presetAssetByFile) }
                },
                activeCardName = activeCardEntry?.let { localizedCardName(it, uiState.lang, uiState::presetAssetByFile) },
                activeCardPromptDefault = cardPromptDefault,
                activeCardPromptOverride = cardPromptOverride,
                onSaveCardPrompt = { text ->
                    val file = uiState.activeCardFile
                    if (file != null) {
                        uiState.setCardPromptOverride(file, text)
                        // 空文本 = 视为还原默认（CardPromptOverrides.set 会清除该键）
                        val entry = uiState.cardByFile(file)
                        if (text.isEmpty()) {
                            if (entry != null) session?.let {
                                applyCardToSession(it, entry, applyOverrideFor, uiState.lang, uiState::presetAssetByFile)
                            }
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
                        session?.let {
                            applyCardToSession(it, entry, applyOverrideFor, uiState.lang, uiState::presetAssetByFile)
                        }
                    }
                },
                onAnimationSourceChange = { uiState.setAnimationSource(context, it) },
                onMotionSettingsChange = { uiState.updateMotionSettings(it) },
                freeSpeechSettings = uiState.freeSpeechSettings,
                onFreeSpeechSettingsChange = { uiState.updateFreeSpeechSettings(it) },
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

        // 新手引导（国内/海外双版本）：Box 最上层，盖过包括设置在内的全部面板
        AnimatedVisibility(
            visible = uiState.guideVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            OnboardingGuideSheet(
                // adb guide_cn/guide_intl 强制指定；否则按界面语言自动
                variant = uiState.guideVariantOverride ?: guideVariant ?: GuideVariant.CN,
                onDismiss = {
                    uiState.guideVariantOverride = null
                    uiState.guideVisible = false
                },
                onConfirm = onGuideConfirm,
            )
        }
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
    /**
     * 未配置 AI 服务（[enabled]=false）时点输入框/语音输入区的兜底：新手引导
     * 借它把用户带回配置流程（ui/OnboardingGuide.kt）。已配置时调用方自行忽略。
     */
    onTapWhenDisabled: () -> Unit = {},
) {
    var input by remember { mutableStateOf("") }
    val s = LocalStrings.current
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
                            text = if (user) "${s.transcriptUserPrefix}${line.text}" else line.text,
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
                            ConversationPhase.THINKING -> s.phaseThinking
                            ConversationPhase.SPEAKING -> s.phaseSpeaking
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
                            onTapWhenDisabled = onTapWhenDisabled,
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
                            onTapWhenDisabled = onTapWhenDisabled,
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
                            onTapWhenDisabled = onTapWhenDisabled,
                            compact = true,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                    // 未配置（enabled=false）时输入框不接受输入，点击由透明层
                    // 接走 → 拉起新手引导（onTapWhenDisabled）
                    Box(modifier = Modifier.weight(1f)) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = {
                                Text(
                                    text = when {
                                        !enabled -> s.placeholderNotConfigured
                                        inputMode == InputMode.VIDEO -> s.placeholderVideo
                                        inputMode == InputMode.VOICE -> s.placeholderVoiceConfirm
                                        else -> s.placeholderType
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
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (!enabled) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onTapWhenDisabled,
                                    )
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            onSend(input)
                            input = ""
                        },
                        enabled = enabled && input.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = s.sendA11y,
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
                            contentDescription = s.interruptA11y
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
    onTapWhenDisabled: () -> Unit = {},
) {
    val bgColor = when {
        recording -> MaterialTheme.colorScheme.errorContainer
        recognizing -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val s = LocalStrings.current
    val label = when {
        recording -> s.holdReleaseToRecognize
        recognizing -> s.recognizing
        enabled -> s.holdToTalk
        else -> s.notConfigured
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
                    // 未配置：点击不再是无响应，交给新手引导重新拉起配置流程
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onTap = { onTapWhenDisabled() })
                    }
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
            val s = LocalStrings.current
            Text(
                text = if (free) s.toggleFree else s.toggleHold,
                color = if (free) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = s.talk,
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
    onTapWhenDisabled: () -> Unit = {},
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            recognizing -> MaterialTheme.colorScheme.secondaryContainer
            hearing -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        },
        label = "freeListenBg",
    )
    val s = LocalStrings.current
    val label = when {
        recognizing -> s.recognizing
        hearing -> s.hearingYou
        enabled -> s.freeListening
        else -> s.notConfigured
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor)
            .then(
                if (enabled) Modifier
                else Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTapWhenDisabled,
                )
            ),
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
    val s = LocalStrings.current
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
                    text = s.inputMode(current),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = s.toggleInputModeA11y,
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
                            text = if (mode == current) s.inputMode(mode) + s.currentSuffix else s.inputMode(mode),
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
        val s = LocalStrings.current
        Text(
            text = if (visible) s.hideButtons else s.showButtons,
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
                val s = LocalStrings.current
                Text(
                    text = if (lensFront) s.pipFront else s.pipBack,
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
    presetNameFor: (PresetCard) -> String,
    activePresetAsset: String?,
    onSelectPreset: (PresetCard) -> Unit,
    onClearCard: () -> Unit,
    onImport: () -> Unit,
    onActivate: (CharacterCardStore.Entry) -> Unit,
    onDelete: (CharacterCardStore.Entry) -> Unit,
) {
    val s = LocalStrings.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = s.presetCardsTitle,
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
                        displayName = presetNameFor(preset),
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
                    text = s.myCardsTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onImport) {
                    Text(text = s.importCard, fontSize = 13.sp)
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
                                text = entry.card.name.ifEmpty { s.unnamedCard },
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
                                text = s.cardInUse,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        IconButton(onClick = { onDelete(entry) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = s.deleteCardA11y,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                if (cards.isEmpty()) {
                    item {
                        Text(
                            text = s.cardsEmptyHint,
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

/** 预置卡 grid 单元：卡图 + 名称（[displayName] 已按语言本地化）；选中描边高亮。 */
@Composable
private fun PresetCardCell(
    preset: PresetCard,
    displayName: String,
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
                contentDescription = displayName,
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
            text = displayName,
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
            val s = LocalStrings.current
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = s.clearCardA11y,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = LocalStrings.current.noCard,
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
    onItemLongClick: ((String) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null
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
            header?.invoke()

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
