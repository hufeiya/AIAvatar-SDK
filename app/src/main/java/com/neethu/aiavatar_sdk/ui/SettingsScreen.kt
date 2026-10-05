package com.neethu.aiavatar_sdk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.neethu.aiadapter.openai.SiliconFlowVoiceCatalog
import com.neethu.aiadapter.openai.TtsVoiceOption
import com.neethu.aiavatar_sdk.AsrEngine
import com.neethu.aiavatar_sdk.AiChatPrefs
import com.neethu.aiavatar_sdk.AiProvider
import com.neethu.aiavatar_sdk.ConversationContextSummary
import com.neethu.aiavatar_sdk.EdgeTtsCatalog
import com.neethu.aiavatar_sdk.FreeSpeechSettings
import com.neethu.aiavatar_sdk.TtsEngine
import com.neethu.aiavatar_sdk.VoicePrefs
import com.neethu.aiavatar_sdk.asrExplicitAsrModels
import com.neethu.aiavatar_sdk.isSystemAsrAvailable
import com.neethu.aiavatar_sdk.i18n.AppLang
import com.neethu.aiavatar_sdk.asrProviderFor
import com.neethu.aiavatar_sdk.i18n.LocalStrings
import com.neethu.aiavatar_sdk.composeVoiceRef
import com.neethu.aiavatar_sdk.resolveEdgeVoice
import com.neethu.aiavatar_sdk.resolveLlmModel
import com.neethu.aiavatar_sdk.resolveTtsModel
import com.neethu.aiavatar_sdk.resolveVoice
import com.neethu.corelib.AmbientOcclusionQuality
import com.neethu.corelib.AntiAliasingMode
import com.neethu.aiavatar_sdk.MotionSettings
import com.neethu.corelib.AvatarRenderSettings
import com.neethu.corelib.LightingRig
import com.neethu.corelib.QualityPreset
import com.neethu.corelib.ToneMappingMode
import kotlin.math.abs
import kotlin.math.roundToInt

/** 折叠类别的稳定 key。 */
internal const val SECTION_AI = "ai"
internal const val SECTION_CARD = "card"
internal const val SECTION_CONTEXT = "context"
internal const val SECTION_ANIMATIONS = "animations"
internal const val SECTION_QUALITY = "quality"
internal const val SECTION_LIVENESS = "liveness"
internal const val SECTION_FREE_SPEECH = "free_speech"
internal const val SECTION_LANGUAGE = "language"

/** 下拉框的一个选项：[value] 为存进 prefs 的值，[label] 为显示名。 */
internal data class DropdownOption(val value: String, val label: String)

/** 服务商下拉的统一选项（显示名随语言）。 */
internal fun providerOptions(s: com.neethu.aiavatar_sdk.i18n.Strings): List<DropdownOption> =
    AiProvider.entries.map { DropdownOption(it.name, s.provider(it)) }

/** 写指定服务商的 API Key（其余服务商的 key 原样保留）。 */
private fun withApiKey(prefs: AiChatPrefs, provider: AiProvider, key: String): AiChatPrefs =
    when (provider) {
        AiProvider.SILICONFLOW -> prefs.copy(apiKeySiliconflow = key)
        AiProvider.VOLCANO -> prefs.copy(apiKeyVolcano = key)
        AiProvider.OPENROUTER -> prefs.copy(apiKeyOpenrouter = key)
    }

/** 写语音合成服务的 API Key（火山写豆包语音那把，与大模型 key 互不通用）。 */
private fun withTtsApiKey(prefs: AiChatPrefs, provider: AiProvider, key: String): AiChatPrefs =
    when (provider) {
        AiProvider.SILICONFLOW -> prefs.copy(apiKeySiliconflow = key)
        AiProvider.VOLCANO -> prefs.copy(apiKeyVolcanoTts = key)
        AiProvider.OPENROUTER -> prefs.copy(apiKeyOpenrouter = key)
    }

/**
 * 音色下拉选项：接口拉到的音色在前、静态兜底在后（按 value 去重）；
 * 当前生效值不在清单里（如手填过自定义音色）时追加保底，避免下拉框显示成裸值。
 */
internal fun voiceOptionsWithFetched(
    provider: AiProvider,
    fetched: List<TtsVoiceOption>,
    selectedValue: String,
): List<DropdownOption> {
    val options = mutableListOf<DropdownOption>()
    val seen = mutableSetOf<String>()
    for (voice in fetched.asSequence().map { DropdownOption(composeVoiceRef(provider, it.value), it.label) } +
        provider.voices.map { DropdownOption(composeVoiceRef(provider, it), it) }
    ) {
        if (seen.add(voice.value)) options += voice
    }
    if (selectedValue.isNotBlank() && seen.add(selectedValue)) {
        options += DropdownOption(selectedValue, provider.voiceLabel(selectedValue))
    }
    return options
}

/** 半屏（默认）与全屏两档；拖动 Settings 标题/把手在两档间连续调整。 */
private const val SHEET_HALF_FRACTION = 0.6f
private const val SHEET_SNAP_THRESHOLD = 0.8f

/**
 * Full settings screen: a dim scrim over the 3D view plus a bottom sheet of
 * collapsible categories — AI chat config, conversation contexts (Room-backed
 * history switching + read-only tag-protocol prompt), animation source and
 * render quality (presets, lighting, shadows, AO, post-processing,
 * anti-aliasing, materials, display).
 *
 * The sheet defaults to 60% height; dragging the title/handle moves it
 * continuously between half and full screen and snaps on release. Changes are
 * pushed to the SDK immediately via [onSettingsChange].
 */
@Composable
internal fun SettingsScreen(
    settings: AvatarRenderSettings,
    motionSettings: MotionSettings,
    useExternalAnimations: Boolean,
    externalRootPath: String?,
    /** 折叠类别集合：由调用方持有，面板关开不丢失。 */
    expandedSections: Set<String>,
    onToggleSection: (String) -> Unit,
    /** 列表滚动状态：由调用方持有，面板关开不丢失。 */
    listState: androidx.compose.foundation.lazy.LazyListState,
    aiPrefs: AiChatPrefs,
    voicePrefs: VoicePrefs,
    /** 界面语言偏好（跟随系统/中文/英文）。 */
    appLangPref: AppLang,
    onAppLangChange: (AppLang) -> Unit,
    /** 生效语言（会话实际使用的提示词/默认音色语言，显示一致性用）。 */
    appLang: com.neethu.corelib.Lang,
    contexts: List<ConversationContextSummary>,
    activeContextId: String,
    protocolPrompt: String,
    cardNameFor: (String?) -> String? = { null },
    activeCardName: String? = null,
    activeCardPromptDefault: String = "",
    activeCardPromptOverride: String? = null,
    onSaveCardPrompt: (String) -> Unit = {},
    onResetCardPrompt: () -> Unit = {},
    onAnimationSourceChange: (Boolean) -> Unit,
    onMotionSettingsChange: (MotionSettings) -> Unit,
    freeSpeechSettings: FreeSpeechSettings,
    onFreeSpeechSettingsChange: (FreeSpeechSettings) -> Unit,
    onSettingsChange: (AvatarRenderSettings) -> Unit,
    onAiPrefsChange: (AiChatPrefs) -> Unit,
    onVoicePrefsChange: (VoicePrefs) -> Unit,
    onNewContext: () -> Unit,
    onSelectContext: (String) -> Unit,
    onDeleteContext: (ConversationContextSummary) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val s = LocalStrings.current
    // ── 半屏 ⇄ 全屏：拖动标题连续调整，松手按阈值吸附 ──────────────────────
    var heightFraction by remember { mutableStateOf(SHEET_HALF_FRACTION) }
    val animatable = remember { Animatable(SHEET_HALF_FRACTION) }
    LaunchedEffect(heightFraction) {
        animatable.animateTo(heightFraction, tween(220))
    }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(SHEET_HALF_FRACTION) }
    val shownFraction = if (dragging) dragFraction else animatable.value
    val currentShownFraction by rememberUpdatedState(shownFraction)
    var sheetHeightPx by remember { mutableStateOf(1f) }


    val toggleSection: (String) -> Unit = onToggleSection

    // ── 音色清单接口拉取（尽力而为）：OpenAI 兼容引擎且硅基流动 TTS 时拉
    //    /audio/voice/list，失败/为空由 voiceOptionsWithFetched 落回静态清单；
    //    火山无公开接口用静态；Edge-TTS 引擎有自己的静态目录不拉取。
    val voiceCatalog = remember { SiliconFlowVoiceCatalog() }
    var fetchedVoices by remember { mutableStateOf<List<TtsVoiceOption>>(emptyList()) }
    val fetchProvider = aiPrefs.ttsProviderResolved
    val fetchKey = aiPrefs.apiKeyFor(fetchProvider)
    val fetchEnabled = aiPrefs.ttsEngine == TtsEngine.OPENAI_COMPATIBLE
    LaunchedEffect(fetchEnabled, fetchProvider, fetchKey) {
        fetchedVoices =
            if (fetchEnabled && fetchProvider == AiProvider.SILICONFLOW && fetchKey.isNotBlank()) {
                voiceCatalog.fetch(fetchProvider.baseUrl, fetchKey)
            } else {
                emptyList()
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(shownFraction)
                .onSizeChanged { sheetHeightPx = it.height.toFloat().coerceAtLeast(1f) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* consume taps inside the sheet so it stays open */ }
                ),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            shadowElevation = 12.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 可拖拽的标题区（含把手）：上下拖 = 调整面板高度
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragFraction = currentShownFraction
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    // 手指上移（dragAmount < 0）→ 面板变高
                                    dragFraction = (dragFraction - dragAmount / sheetHeightPx)
                                        .coerceIn(SHEET_HALF_FRACTION, 1f)
                                },
                                onDragEnd = {
                                    dragging = false
                                    heightFraction =
                                        if (dragFraction > SHEET_SNAP_THRESHOLD) 1f else SHEET_HALF_FRACTION
                                },
                                onDragCancel = {
                                    dragging = false
                                    heightFraction =
                                        if (dragFraction > SHEET_SNAP_THRESHOLD) 1f else SHEET_HALF_FRACTION
                                },
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = s.settingsTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = s.closeSettingsA11y)
                        }
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // ── 语言 ─────────────────────────────────────────────
                    item {
                        CollapsibleSection(
                            title = s.sectionLanguage,
                            expanded = SECTION_LANGUAGE in expandedSections,
                            onToggle = { toggleSection(SECTION_LANGUAGE) }
                        ) {
                            SettingsDropdownRow(
                                title = s.languageTitle,
                                options = AppLang.entries.map { DropdownOption(it.name, s.appLangLabel(it.name)) },
                                selectedValue = appLangPref.name,
                            ) { value -> onAppLangChange(AppLang.valueOf(value)) }
                            SettingsGroupLabel(s.languageSubtitle)
                        }
                    }

                    // ── AI 配置 ───────────────────────────────────────────
                    item {
                        CollapsibleSection(
                            title = s.sectionAi,
                            expanded = SECTION_AI in expandedSections,
                            onToggle = { toggleSection(SECTION_AI) }
                        ) {
                            SettingsGroupLabel(
                                when {
                                    aiPrefs.isConfigured -> s.aiConfigured
                                    aiPrefs.ttsEngine == TtsEngine.EDGE -> s.aiEdgeReadyHint
                                    else -> s.aiPickProviderHint
                                }
                            )
                            // ── 大模型 ────────────────────────────────────
                            SettingsDropdownRow(
                                title = s.llmProviderTitle,
                                options = providerOptions(s),
                                selectedValue = aiPrefs.provider.name,
                            ) { value ->
                                // 同服务商勾选时 TTS 由 ttsProviderResolved 自动跟随 provider
                                onAiPrefsChange(aiPrefs.copy(provider = AiProvider.valueOf(value)))
                            }
                            SettingsTextFieldRow(
                                title = s.llmKeyLabel(aiPrefs.provider),
                                value = aiPrefs.apiKeyFor(aiPrefs.provider),
                                placeholder = s.keyHint(aiPrefs.provider, tts = false),
                                password = true
                            ) { onAiPrefsChange(withApiKey(aiPrefs, aiPrefs.provider, it)) }
                            SettingsDropdownRow(
                                title = s.llmModelTitle,
                                options = aiPrefs.provider.llmModels.map { DropdownOption(it, it) },
                                selectedValue = resolveLlmModel(aiPrefs.provider, aiPrefs.llmModel),
                            ) { onAiPrefsChange(aiPrefs.copy(llmModel = it)) }

                            // ── 语音合成（TTS）─────────────────────────────
                            // 下文 ASR 区块也引用（硅基流动 Key 显隐判定），提在引擎分支外
                            val ttsProvider = aiPrefs.ttsProviderResolved
                            SettingsDropdownRow(
                                title = s.ttsEngineTitle,
                                options = TtsEngine.entries.map { DropdownOption(it.name, s.ttsEngine(it)) },
                                selectedValue = aiPrefs.ttsEngine.name,
                            ) { value ->
                                onAiPrefsChange(aiPrefs.copy(ttsEngine = TtsEngine.valueOf(value)))
                            }
                            if (aiPrefs.ttsEngine == TtsEngine.EDGE) {
                                // Edge-TTS（任务 6）：免费无 Key，服务商/Key/模型全旁路
                                SettingsGroupLabel(s.edgeTtsHint)
                                SettingsDropdownRow(
                                    title = s.voiceTitle,
                                    options = EdgeTtsCatalog.voices.map { DropdownOption(it.first, s.edgeVoiceLabel(it.first)) },
                                    // 默认音色按语言解析（英文模式空配置=Emma，与实际请求一致）
                                    selectedValue = resolveEdgeVoice(aiPrefs.voice, appLang),
                                ) { onAiPrefsChange(aiPrefs.copy(voice = it)) }
                            } else {
                            SettingsCheckRow(
                                title = s.sameProviderTitle,
                                subtitle = s.sameProviderSubtitle,
                                checked = aiPrefs.ttsSameProvider
                            ) { onAiPrefsChange(
                                // 取消勾选瞬间 TTS 行为不跳变：独立服务商初始化为
                                // 当前生效值，用户再显式改
                                aiPrefs.copy(ttsSameProvider = it, ttsProvider = aiPrefs.ttsProviderResolved)
                            ) }
                            if (!aiPrefs.ttsSameProvider) {
                                SettingsDropdownRow(
                                    title = s.ttsProviderTitle,
                                    options = providerOptions(s),
                                    selectedValue = ttsProvider.name,
                                ) { value ->
                                    onAiPrefsChange(aiPrefs.copy(ttsProvider = AiProvider.valueOf(value)))
                                }
                                if (ttsProvider != aiPrefs.provider || ttsProvider == AiProvider.VOLCANO) {
                                    // 火山语音与火山大模型是两把互不通用的 key（豆包语音
                                    // 控制台 vs 方舟控制台），勾了「同服务商」也必须单填；
                                    // 硅基流动 TTS 仅在独立于大模型服务商时才露 key
                                    SettingsTextFieldRow(
                                        title = s.ttsKeyLabel(ttsProvider),
                                        value = aiPrefs.apiKeyForTts(),
                                        placeholder = s.keyHint(ttsProvider, tts = true),
                                        password = true
                                    ) { onAiPrefsChange(withTtsApiKey(aiPrefs, ttsProvider, it)) }
                                }
                            }
                            SettingsDropdownRow(
                                title = s.ttsModelTitle,
                                options = ttsProvider.ttsModels.map { DropdownOption(it, ttsProvider.ttsModelLabel(it)) },
                                selectedValue = resolveTtsModel(ttsProvider, aiPrefs.ttsModel),
                            ) { onAiPrefsChange(aiPrefs.copy(ttsModel = it)) }
                            SettingsDropdownRow(
                                title = s.voiceTitle,
                                options = voiceOptionsWithFetched(ttsProvider, fetchedVoices, resolveVoice(ttsProvider, aiPrefs.voice)),
                                selectedValue = resolveVoice(ttsProvider, aiPrefs.voice),
                            ) { onAiPrefsChange(aiPrefs.copy(voice = it)) }
                            }

                            // ── 语音识别（ASR）─────────────────────────────
                            // ASR 跟随大模型服务商（硅基流动/OpenRouter 同构端点），
                            // 火山回落硅基流动——硅基流动 Key 在上面没露过面
                            // （LLM 不走硅基流动、TTS 也不走硅基流动）时单独填一份
                            val asrProvider = asrProviderFor(aiPrefs.provider)
                            SettingsGroupLabel(
                                s.asrGroupLabel(asrProvider, aiPrefs.provider == AiProvider.VOLCANO)
                            )
                            if (asrProvider == AiProvider.SILICONFLOW &&
                                aiPrefs.provider != AiProvider.SILICONFLOW &&
                                ttsProvider != AiProvider.SILICONFLOW &&
                                voicePrefs.asrEngine == AsrEngine.CLOUD
                            ) {
                                SettingsTextFieldRow(
                                    title = s.sfAsrKeyTitle,
                                    value = aiPrefs.apiKeySiliconflow,
                                    placeholder = "sk-...",
                                    password = true
                                ) { onAiPrefsChange(withApiKey(aiPrefs, AiProvider.SILICONFLOW, it)) }
                            }
                            // ── 识别引擎：云端（跟随大模型服务商）或系统内置（免费）──
                            SettingsDropdownRow(
                                title = s.recognitionEngineTitle,
                                options = AsrEngine.entries.map { DropdownOption(it.name, s.asrEngine(it)) },
                                selectedValue = voicePrefs.asrEngine.name,
                            ) { value ->
                                onVoicePrefsChange(voicePrefs.copy(asrEngine = AsrEngine.valueOf(value)))
                            }
                            if (voicePrefs.asrEngine == AsrEngine.SYSTEM) {
                                // 系统识别：平台 SpeechRecognizer（GMS=Google，国产 ROM=厂商服务）
                                val systemAsrReady = remember { isSystemAsrAvailable(context) }
                                SettingsGroupLabel(
                                    if (systemAsrReady) s.systemAsrAvailableHint else s.systemAsrUnavailableHint
                                )
                            } else {
                            SettingsDropdownRow(
                                title = s.asrModelTitle,
                                options = listOf(DropdownOption("", s.asrModelAuto)) +
                                    asrExplicitAsrModels(asrProvider).map {
                                        DropdownOption(it, it)
                                    },
                                selectedValue = voicePrefs.asrModel,
                            ) { onVoicePrefsChange(voicePrefs.copy(asrModel = it)) }
                            }
                            SettingsSwitchRow(
                                title = s.voiceAutoSendTitle,
                                subtitle = s.voiceAutoSendSubtitle,
                                checked = voicePrefs.autoSend
                            ) { onVoicePrefsChange(voicePrefs.copy(autoSend = it)) }

                            SettingsSwitchRow(
                                title = s.llmCameraTitle,
                                subtitle = s.llmCameraSubtitle,
                                checked = aiPrefs.llmCamera
                            ) { onAiPrefsChange(aiPrefs.copy(llmCamera = it)) }
                        }
                    }

                    // ── 人物卡提示词 ──────────────────────────────────────
                    item {
                        CollapsibleSection(
                            title = s.sectionCard,
                            expanded = SECTION_CARD in expandedSections,
                            onToggle = { toggleSection(SECTION_CARD) }
                        ) {
                            CardPromptSection(
                                cardName = activeCardName,
                                defaultPrompt = activeCardPromptDefault,
                                override = activeCardPromptOverride,
                                onSave = onSaveCardPrompt,
                                onReset = onResetCardPrompt,
                            )
                        }
                    }

                    // ── 对话上下文 ────────────────────────────────────────
                    item {
                        CollapsibleSection(
                            title = s.sectionContext,
                            expanded = SECTION_CONTEXT in expandedSections,
                            onToggle = { toggleSection(SECTION_CONTEXT) }
                        ) {
                            SettingsActionRow(
                                title = s.newContextTitle,
                                subtitle = s.newContextSubtitle
                            ) { onNewContext() }
                            SettingsGroupLabel(s.contextHistoryLabel)
                            if (contexts.isEmpty()) {
                                SettingsGroupLabel(s.contextEmptyHint)
                            }
                            contexts.forEach { summary ->
                                ContextRow(
                                    summary = summary,
                                    title = summary.characterId
                                        ?.let { cardNameFor(it) }
                                        ?.takeIf { it.isNotBlank() }
                                        ?: s.freeChat,
                                    active = summary.id == activeContextId,
                                    onSelect = { onSelectContext(summary.id) },
                                    onDelete = { onDeleteContext(summary) }
                                )
                            }
                            // 教大模型发标签的协议提示词：只读展示，默认折叠
                            ProtocolPromptSection(protocolPrompt)
                        }
                    }

                    // ── 动画资源 ─────────────────────────────────────────
                    item {
                        CollapsibleSection(
                            title = s.sectionAnimations,
                            expanded = SECTION_ANIMATIONS in expandedSections,
                            onToggle = { toggleSection(SECTION_ANIMATIONS) }
                        ) {
                            SettingsOptionRow(
                                title = s.animBuiltInTitle,
                                subtitle = s.animBuiltInSubtitle,
                                selected = !useExternalAnimations
                            ) {
                                onAnimationSourceChange(false)
                            }
                            SettingsOptionRow(
                                title = s.animExternalTitle,
                                subtitle = s.animExternalSubtitle,
                                selected = useExternalAnimations
                            ) {
                                onAnimationSourceChange(true)
                            }
                            if (useExternalAnimations) {
                                SettingsGroupLabel(s.animExternalDir(externalRootPath))
                            }
                        }
                    }

                    // ── 画质设置 ─────────────────────────────────────────
                    item {
                        CollapsibleSection(
                            title = s.sectionQuality,
                            expanded = SECTION_QUALITY in expandedSections,
                            onToggle = { toggleSection(SECTION_QUALITY) }
                        ) {
                            QualityPresetContent(settings, onSettingsChange)
                        }
                    }

                    // ── 拟人感（呼吸/视线/眨眼） ─────────────────────────
                    item {
                        CollapsibleSection(
                            title = s.sectionLiveness,
                            expanded = SECTION_LIVENESS in expandedSections,
                            onToggle = { toggleSection(SECTION_LIVENESS) }
                        ) {
                            SettingsGroupLabel(s.livenessGroupLabel)
                            SettingsActionRow(
                                title = s.restoreDefault,
                                subtitle = s.livenessRestoreSubtitle
                            ) {
                                onMotionSettingsChange(MotionSettings())
                            }
                            SettingsSwitchRow(
                                title = s.breathTitle,
                                subtitle = s.breathSubtitle,
                                checked = motionSettings.breathEnabled,
                                onCheckedChange = { v ->
                                    onMotionSettingsChange(motionSettings.copy(breathEnabled = v))
                                }
                            )
                            if (motionSettings.breathEnabled) {
                                SliderTextFieldRow(
                                    title = s.breathAmplitude,
                                    value = motionSettings.breathAmplitude,
                                    valueRange = 0f..2f,
                                    format = "%.2f",
                                    unit = "×",
                                    onCommit = { v ->
                                        onMotionSettingsChange(motionSettings.copy(breathAmplitude = v))
                                    }
                                )
                                SliderTextFieldRow(
                                    title = s.breathRate,
                                    value = motionSettings.breathRateBpm,
                                    valueRange = 8f..30f,
                                    format = "%.1f",
                                    unit = s.bpmUnit,
                                    decimalCount = 1,
                                    onCommit = { v ->
                                        onMotionSettingsChange(motionSettings.copy(breathRateBpm = v))
                                    }
                                )
                            }
                            SettingsSwitchRow(
                                title = s.saccadeTitle,
                                subtitle = s.saccadeSubtitle,
                                checked = motionSettings.saccadeEnabled,
                                onCheckedChange = { v ->
                                    onMotionSettingsChange(motionSettings.copy(saccadeEnabled = v))
                                }
                            )
                            if (motionSettings.saccadeEnabled) {
                                SliderTextFieldRow(
                                    title = s.saccadeAmplitude,
                                    value = motionSettings.saccadeJitter,
                                    valueRange = 0f..0.25f,
                                    format = "%.3f",
                                    unit = "",
                                    decimalCount = 3,
                                    onCommit = { v ->
                                        onMotionSettingsChange(motionSettings.copy(saccadeJitter = v))
                                    }
                                )
                            }
                            SettingsSwitchRow(
                                title = s.blinkTitle,
                                subtitle = s.blinkSubtitle,
                                checked = motionSettings.blinkEnabled,
                                onCheckedChange = { v ->
                                    onMotionSettingsChange(motionSettings.copy(blinkEnabled = v))
                                }
                            )
                            if (motionSettings.blinkEnabled) {
                                SliderTextFieldRow(
                                    title = s.blinkInterval,
                                    value = motionSettings.blinkIntervalS,
                                    valueRange = 1f..8f,
                                    format = "%.1f",
                                    unit = s.secondsUnit,
                                    decimalCount = 1,
                                    onCommit = { v ->
                                        onMotionSettingsChange(motionSettings.copy(blinkIntervalS = v))
                                    }
                                )
                            }
                        }
                    }

                    // ── 自由说话（灵敏度） ────────────────────────────────
                    item {
                        CollapsibleSection(
                            title = s.sectionFreeSpeech,
                            expanded = SECTION_FREE_SPEECH in expandedSections,
                            onToggle = { toggleSection(SECTION_FREE_SPEECH) }
                        ) {
                            if (voicePrefs.asrEngine == AsrEngine.SYSTEM) {
                                SettingsGroupLabel(s.freeSpeechSystemNote)
                            }
                            SettingsGroupLabel(s.freeSpeechGroupLabel)
                            SettingsActionRow(
                                title = s.restoreDefault,
                                subtitle = s.freeSpeechRestoreSubtitle
                            ) {
                                onFreeSpeechSettingsChange(FreeSpeechSettings())
                            }
                            SliderTextFieldRow(
                                title = s.startThreshold,
                                value = freeSpeechSettings.startAbsolute,
                                valueRange = FreeSpeechSettings.START_ABSOLUTE_RANGE,
                                format = "%.3f",
                                unit = "",
                                decimalCount = 3,
                                onCommit = { v ->
                                    onFreeSpeechSettingsChange(freeSpeechSettings.copy(startAbsolute = v))
                                }
                            )
                            SliderTextFieldRow(
                                title = s.bargeThreshold,
                                value = freeSpeechSettings.bargeAbsolute,
                                valueRange = FreeSpeechSettings.BARGE_ABSOLUTE_RANGE,
                                format = "%.2f",
                                unit = "",
                                decimalCount = 2,
                                onCommit = { v ->
                                    onFreeSpeechSettingsChange(freeSpeechSettings.copy(bargeAbsolute = v))
                                }
                            )
                            SliderTextFieldRow(
                                title = s.hangoverTitle,
                                value = freeSpeechSettings.hangoverMs,
                                valueRange = FreeSpeechSettings.HANGOVER_MS_RANGE,
                                format = "%.0f",
                                unit = "ms",
                                decimalCount = 0,
                                onCommit = { v ->
                                    onFreeSpeechSettingsChange(freeSpeechSettings.copy(hangoverMs = v))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 滑条 + 数值输入框二合一的参数行：拖滑条或直接键入数值均可，双向同步。
 * 文本输入允许任意中间态（如 "0."），可解析即提交；滑条拖动时反向刷新文本。
 */
@Composable
private fun SliderTextFieldRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    format: String,
    unit: String,
    decimalCount: Int = 2,
    onCommit: (Float) -> Unit,
) {
    var text by remember { mutableStateOf(String.format(format, value)) }
    var lastEmitted by remember { mutableStateOf(value) }

    // 滑杆/外部改了 value → 同步文本；文本自身提交的值不回填（不打断输入）
    LaunchedEffect(value) {
        val parsed = text.toFloatOrNull()
        if (parsed == null || abs(parsed - value) > 1e-4) {
            text = String.format(format, value)
            lastEmitted = value
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = { v ->
                    onCommit(v.coerceIn(valueRange.start, valueRange.endInclusive))
                },
                valueRange = valueRange,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = text,
                onValueChange = { new ->
                    text = new
                    new.toFloatOrNull()?.let { v ->
                        val clamped = v.coerceIn(valueRange.start, valueRange.endInclusive)
                        lastEmitted = clamped
                        onCommit(clamped)
                    }
                },
                modifier = Modifier.width(88.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            )
        }
    }
}

/** 画质设置的全部子区块（预设/光照/阴影/AO/后处理/抗锯齿/材质/显示）。 */
@Composable
private fun QualityPresetContent(
    settings: AvatarRenderSettings,
    onSettingsChange: (AvatarRenderSettings) -> Unit,
) {
    val s = LocalStrings.current
    // ── 画质预设 ──────────────────────────────────────────────────────────
    SettingsSectionHeader(s.qualityPresetHeader)
    SettingsActionRow(
        title = s.presetLow,
        subtitle = s.presetLowSubtitle
    ) {
        onSettingsChange(
            QualityPreset.LOW.toRenderSettings(
                settings.iblIntensity,
                settings.iblRotationDegrees,
                settings.showFps
            )
        )
    }
    SettingsActionRow(
        title = s.presetMedium,
        subtitle = s.presetMediumSubtitle
    ) {
        onSettingsChange(
            QualityPreset.MEDIUM.toRenderSettings(
                settings.iblIntensity,
                settings.iblRotationDegrees,
                settings.showFps
            )
        )
    }
    SettingsActionRow(
        title = s.presetHigh,
        subtitle = s.presetHighSubtitle
    ) {
        onSettingsChange(
            QualityPreset.HIGH.toRenderSettings(
                settings.iblIntensity,
                settings.iblRotationDegrees,
                settings.showFps
            )
        )
    }
    SettingsActionRow(
        title = s.presetUltra,
        subtitle = s.presetUltraSubtitle
    ) {
        onSettingsChange(
            QualityPreset.ULTRA.toRenderSettings(
                settings.iblIntensity,
                settings.iblRotationDegrees,
                settings.showFps
            )
        )
    }

    // ── 光照与环境 ───────────────────────────────────────────────────────
    SettingsSectionHeader(s.lightingHeader)
    SettingsSliderRow(
        title = s.iblIntensity,
        valueText = "${settings.iblIntensity.roundToInt()} lux",
        value = settings.iblIntensity,
        valueRange = 0f..50_000f
    ) {
        onSettingsChange(settings.copy(iblIntensity = it))
    }
    SettingsSliderRow(
        title = s.iblRotation,
        valueText = "${settings.iblRotationDegrees.roundToInt()}°",
        value = settings.iblRotationDegrees,
        valueRange = 0f..360f
    ) {
        onSettingsChange(settings.copy(iblRotationDegrees = it))
    }
    SettingsGroupLabel(s.lightingRigLabel)
    SettingsOptionRow(
        title = s.rigKeyOnly,
        subtitle = s.rigKeyOnlySubtitle,
        selected = settings.lightingRig == LightingRig.KEY_ONLY
    ) {
        onSettingsChange(settings.copy(lightingRig = LightingRig.KEY_ONLY))
    }
    SettingsOptionRow(
        title = s.rigKeyFill,
        subtitle = s.rigKeyFillSubtitle,
        selected = settings.lightingRig == LightingRig.KEY_FILL
    ) {
        onSettingsChange(settings.copy(lightingRig = LightingRig.KEY_FILL))
    }
    SettingsOptionRow(
        title = s.rigStudio,
        subtitle = s.rigStudioSubtitle,
        selected = settings.lightingRig == LightingRig.STUDIO
    ) {
        onSettingsChange(settings.copy(lightingRig = LightingRig.STUDIO))
    }

    // ── 阴影 ─────────────────────────────────────────────────────────────
    SettingsSectionHeader(s.shadowsHeader)
    SettingsGroupLabel(s.shadowResolutionLabel)
    SettingsOptionRow(
        title = "1024 (Low)",
        subtitle = s.shadow1024Subtitle,
        selected = settings.shadowMapSize == 1024
    ) {
        onSettingsChange(settings.copy(shadowMapSize = 1024))
    }
    SettingsOptionRow(
        title = "2048 (Medium)",
        subtitle = s.shadow2048Subtitle,
        selected = settings.shadowMapSize == 2048
    ) {
        onSettingsChange(settings.copy(shadowMapSize = 2048))
    }
    SettingsOptionRow(
        title = "4096 (Ultra)",
        subtitle = s.shadow4096Subtitle,
        selected = settings.shadowMapSize == 4096
    ) {
        onSettingsChange(settings.copy(shadowMapSize = 4096))
    }
    SettingsSwitchRow(
        title = s.pcssTitle,
        subtitle = s.pcssSubtitle,
        checked = settings.softShadows
    ) {
        onSettingsChange(settings.copy(softShadows = it))
    }
    SettingsSwitchRow(
        title = s.contactShadowsTitle,
        subtitle = s.contactShadowsSubtitle,
        checked = settings.contactShadows
    ) {
        onSettingsChange(settings.copy(contactShadows = it))
    }

    // ── 环境光遮蔽 ───────────────────────────────────────────────────────
    SettingsSectionHeader(s.aoHeader)
    SettingsOptionRow(
        title = s.off,
        subtitle = s.aoOffSubtitle,
        selected = settings.ambientOcclusion == AmbientOcclusionQuality.OFF
    ) {
        onSettingsChange(settings.copy(ambientOcclusion = AmbientOcclusionQuality.OFF))
    }
    SettingsOptionRow(
        title = s.aoSsao,
        subtitle = s.aoSsaoSubtitle,
        selected = settings.ambientOcclusion == AmbientOcclusionQuality.STANDARD
    ) {
        onSettingsChange(settings.copy(ambientOcclusion = AmbientOcclusionQuality.STANDARD))
    }
    SettingsOptionRow(
        title = s.aoGtao,
        subtitle = s.aoGtaoSubtitle,
        selected = settings.ambientOcclusion == AmbientOcclusionQuality.HIGH
    ) {
        onSettingsChange(settings.copy(ambientOcclusion = AmbientOcclusionQuality.HIGH))
    }

    // ── 后处理 ───────────────────────────────────────────────────────────
    SettingsSectionHeader(s.postHeader)
    SettingsGroupLabel(s.toneMappingLabel)
    SettingsOptionRow(
        title = "Linear",
        subtitle = s.toneLinearSubtitle,
        selected = settings.toneMapping == ToneMappingMode.LINEAR
    ) {
        onSettingsChange(settings.copy(toneMapping = ToneMappingMode.LINEAR))
    }
    SettingsOptionRow(
        title = "Filmic",
        subtitle = s.toneFilmicSubtitle,
        selected = settings.toneMapping == ToneMappingMode.FILMIC
    ) {
        onSettingsChange(settings.copy(toneMapping = ToneMappingMode.FILMIC))
    }
    SettingsOptionRow(
        title = s.toneAces,
        subtitle = s.toneAcesSubtitle,
        selected = settings.toneMapping == ToneMappingMode.ACES
    ) {
        onSettingsChange(settings.copy(toneMapping = ToneMappingMode.ACES))
    }
    SettingsSwitchRow(
        title = s.bloomTitle,
        subtitle = s.bloomSubtitle,
        checked = settings.bloomEnabled
    ) {
        onSettingsChange(settings.copy(bloomEnabled = it))
    }
    SettingsSliderRow(
        title = s.bloomStrength,
        valueText = "%.2f".format(settings.bloomStrength),
        value = settings.bloomStrength,
        valueRange = 0f..0.5f
    ) {
        onSettingsChange(settings.copy(bloomStrength = it))
    }
    SettingsSwitchRow(
        title = s.dofTitle,
        subtitle = s.dofSubtitle,
        checked = settings.depthOfFieldEnabled
    ) {
        onSettingsChange(settings.copy(depthOfFieldEnabled = it))
    }

    // ── 抗锯齿 ───────────────────────────────────────────────────────────
    SettingsSectionHeader(s.aaHeader)
    SettingsOptionRow(
        title = s.off,
        subtitle = s.aaOffSubtitle,
        selected = settings.antiAliasing == AntiAliasingMode.NONE
    ) {
        onSettingsChange(settings.copy(antiAliasing = AntiAliasingMode.NONE))
    }
    SettingsOptionRow(
        title = "FXAA",
        subtitle = s.aaFxaaSubtitle,
        selected = settings.antiAliasing == AntiAliasingMode.FXAA
    ) {
        onSettingsChange(settings.copy(antiAliasing = AntiAliasingMode.FXAA))
    }
    SettingsOptionRow(
        title = "MSAA 4x",
        subtitle = s.aaMsaaSubtitle,
        selected = settings.antiAliasing == AntiAliasingMode.MSAA_4X
    ) {
        onSettingsChange(settings.copy(antiAliasing = AntiAliasingMode.MSAA_4X))
    }
    SettingsOptionRow(
        title = s.aaTaa,
        subtitle = s.aaTaaSubtitle,
        selected = settings.antiAliasing == AntiAliasingMode.TAA
    ) {
        onSettingsChange(settings.copy(antiAliasing = AntiAliasingMode.TAA))
    }

    // ── 材质 ─────────────────────────────────────────────────────────────
    SettingsSectionHeader(s.materialsHeader)
    SettingsSwitchRow(
        title = s.enhanceMaterialsTitle,
        subtitle = s.enhanceMaterialsSubtitle,
        checked = settings.enhanceMaterials
    ) {
        onSettingsChange(settings.copy(enhanceMaterials = it))
    }

    // ── 显示 ─────────────────────────────────────────────────────────────
    SettingsSectionHeader(s.displayHeader)
    SettingsSwitchRow(
        title = s.showFpsTitle,
        subtitle = s.showFpsSubtitle,
        checked = settings.showFps
    ) {
        onSettingsChange(settings.copy(showFps = it))
    }
}

/** 可折叠类别：标题行点按展开/收起，箭头随状态旋转。 */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                label = "chevronRotation"
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) LocalStrings.current.collapseA11y else LocalStrings.current.expandA11y,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(rotation)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content
            )
        }
    }
}

/** 上下文列表条目：标题（卡片名/自由对话）+ 最近使用时间与消息数。 */
@Composable
private fun ContextRow(
    summary: ConversationContextSummary,
    title: String,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalStrings.current
    val bgColor by animateColorAsState(
        targetValue = if (active)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "contextRowBg"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${summary.updatedAtText} ${s.slashCount(summary.messageCount)}",
                fontSize = 12.sp,
                color = if (active)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (active) {
            Text(
                text = s.contextCurrent,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = s.deleteContextA11y,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 人物卡人设提示词区：默认只读展示当前激活卡的完整人设（与实际注入 system
 * 的 persona 段一致）；点「编辑」进入可改文本框，「OK」保存覆盖并立即生效，
 * 「还原默认」清除覆盖回到卡片原始人设。未激活卡片时显示引导文案。
 */
@Composable
private fun CardPromptSection(
    cardName: String?,
    defaultPrompt: String,
    override: String?,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val s = LocalStrings.current

    if (cardName == null) {
        SettingsGroupLabel(s.cardNotActive)
        return
    }

    val effective = override ?: defaultPrompt

    if (!editing) {
        SettingsGroupLabel(
            buildString {
                append(s.cardCurrent(cardName))
                append(if (override != null) s.cardPromptEdited else s.cardPromptDefault)
            }
        )
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = effective.ifEmpty { s.cardPromptEmpty },
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = {
                draft = effective
                editing = true
            }) {
                Text(text = s.cardEdit, fontSize = 13.sp)
            }
        }
    } else {
        SettingsGroupLabel(s.cardEditHint)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
            maxLines = 12,
            textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp),
        )
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (override != null) {
                TextButton(onClick = {
                    onReset()
                    editing = false
                }) {
                    Text(text = s.cardRestoreDefault, fontSize = 13.sp)
                }
            }
            TextButton(onClick = { editing = false }) {
                Text(text = s.cancel, fontSize = 13.sp)
            }
            FilledTonalButton(onClick = {
                onSave(draft.trim())
                editing = false
            }) {
                Text(text = "OK", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * 标签协议提示词的只读展示（默认折叠）：内容与 [AvatarSession.protocolBlock]
 * 逐字一致——即每次请求实际追加给大模型的那段指令，仅供查看，不可编辑。
 */
@Composable
private fun ProtocolPromptSection(prompt: String) {
    var open by remember { mutableStateOf(false) }
    val s = LocalStrings.current
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        label = "promptChevron"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { open = !open }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = s.protocolPromptTitle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = if (open) s.protocolCollapseA11y else s.protocolExpandA11y,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(rotation)
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (prompt.isEmpty())
                        s.protocolEmpty
                    else
                        s.protocolIntro(prompt.length),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Text(
                    text = prompt,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)
    )
}

/**
 * A selectable settings entry with title + description and a trailing radio.
 */
@Composable
private fun SettingsOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "settingsOptionBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(selected = selected, onClick = null)
    }
}

/**
 * A one-shot preset action row (same look as [SettingsOptionRow] without a radio).
 */
@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A boolean settings entry with a trailing [Switch]. The whole row toggles.
 */
@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * A slider settings entry showing the current value on the trailing side.
 */
@Composable
private fun SettingsSliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

/**
 * A small label inside a section that groups a set of related rows.
 */
@Composable
private fun SettingsGroupLabel(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp)
    )
}

/**
 * A labeled single-line text input for AI provider configuration.
 * Changes stream straight through [onValueChange]; the caller decides
 * when to persist / rebuild the session.
 */
@Composable
private fun SettingsTextFieldRow(
    title: String,
    value: String,
    placeholder: String,
    password: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(text = placeholder, fontSize = 13.sp) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
    }
}

/**
 * A labeled dropdown row for AI provider/model/voice selection — the only
 * editable AI configs besides API keys are picks from curated catalogs.
 * Options open in a [DropdownMenu] anchored under the current value.
 */
@Composable
private fun SettingsDropdownRow(
    title: String,
    options: List<DropdownOption>,
    selectedValue: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val s = LocalStrings.current
    val selectedLabel = options.firstOrNull { it.value == selectedValue }?.label ?: selectedValue
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedLabel,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = s.chooseA11y(title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (option.value == selectedValue) option.label + s.currentSuffix else option.label,
                                fontWeight = if (option.value == selectedValue) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        onClick = {
                            expanded = false
                            if (option.value != selectedValue) onSelect(option.value)
                        }
                    )
                }
            }
        }
    }
}

/**
 * A boolean settings entry with a trailing [Checkbox]（需求明确的勾选框形态）。
 * The whole row toggles.
 */
@Composable
private fun SettingsCheckRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}
