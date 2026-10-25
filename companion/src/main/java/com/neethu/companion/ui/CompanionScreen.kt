package com.neethu.companion.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.neethu.companion.AiChatController
import com.neethu.companion.ChatLine
import com.neethu.companion.ChatRole
import com.neethu.companion.ChatTranscript
import com.neethu.companion.builtinAnimationPaths
import com.neethu.companion.relationshipStatusLine
import com.neethu.companion.spokenGreetingText
import com.neethu.orchestrator.card.spokenGreeting
import com.neethu.companion.AiProvider
import com.neethu.companion.AsrEngine
import com.neethu.companion.AutonomyRig
import com.neethu.companion.CompanionUiState
import com.neethu.companion.DateMode
import com.neethu.companion.ImportedModelLibrary
import com.neethu.companion.InteractionCoordinator
import com.neethu.companion.SystemAsrController
import com.neethu.companion.VoiceRecorder
import com.neethu.companion.applyCardToSession
import com.neethu.companion.buildLlmActionCatalog
import com.neethu.companion.companionPrefs
import com.neethu.companion.ensureWalkGaits
import com.neethu.companion.loadAutonomySchedule
import com.neethu.companion.loadInteractionAnchors
import com.neethu.companion.loadWalkAnchors
import com.neethu.companion.mimeForFileName
import com.neethu.companion.notify.GreetingWorker
import com.neethu.companion.parseSceneMeta
import com.neethu.companion.parseSceneSpawn
import com.neethu.companion.sceneMetaAssetName
import com.neethu.companion.show.MusicPlayer
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarState
import com.neethu.corelib.AvatarView
import com.neethu.corelib.CameraShot
import com.neethu.corelib.rememberAvatarController
import com.neethu.corelib.vfx.VfxPresets
import com.neethu.orchestrator.agent.AgentToolCatalog
import com.neethu.orchestrator.agent.ToolExecutor
import com.neethu.orchestrator.i18n.promptTextsOf
import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.session.ConversationPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 产品主屏（§6.3 `:companion` 产品本体）：3D 化身 + 聊天（markdown 气泡/按住
 * 说话/自主生命体/思考气泡）+ 卡库 + 关系状态（G11）+ 约会模式（P3-3）+
 * 设置子集 + 群聊入口（G12）+ 问候通知深链（G10）。demo 的橱窗功能（视频
 * 模仿/游戏柄/辩论/团播/技能/自由说话）不进产品 v1。
 */
@Composable
internal fun CompanionScreen(
    pollAiCommand: () -> com.neethu.companion.AiDebugCommand?,
    pendingGreeting: String?,
    onPendingGreetingConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { companionPrefs(context) }
    val ui = remember { CompanionUiState(context, prefs) }

    val controller = rememberAvatarController()
    val state by controller.state.collectAsState()

    // ── 模型加载（内置 assets / 导入 filesDir，demo 同款路径）────────────────
    fun loadSelectedModel(force: Boolean) {
        val name = ui.selectedModel ?: return
        if (!force && state !is AvatarState.Idle) return
        if (name in ui.importedModelFiles) {
            val f = ImportedModelLibrary.modelFile(context, name) ?: return
        controller.loadModelFromFile(f.absolutePath)
        } else {
            controller.loadModel("vrms/$name")
        }
    }
    LaunchedEffect(ui.selectedModel) { loadSelectedModel(true) }

    // ── 场景切换（meta + spawn 投影；卡绑场景走 ui.selectedScene 同一条路）────
    LaunchedEffect(ui.selectedScene) {
        val scene = ui.selectedScene
        if (scene.isNullOrBlank()) {
            controller.removeScene()
        } else {
            val metaJson = runCatching {
                context.assets.open(sceneMetaAssetName(scene)).bufferedReader().use { it.readText() }
            }.getOrNull()
            controller.loadScene("scene/$scene", metaJson?.let { parseSceneMeta(it) })
            metaJson?.let { json ->
                parseSceneSpawn(json)?.let { sp ->
                    controller.setAvatarWorldPosition(sp.x, sp.y, sp.z, sp.yawDeg)
                }
            }
        }
    }

    // ── AI 会话装配（800ms 防抖；配置/上下文/语言变化即重建）─────────────────
    val aiChat = remember { AiChatController(scope, controller, context.applicationContext) }
    aiChat.embeddingConfigProvider = {
        val key = ui.aiPrefs.apiKeySiliconflow.trim()
        if (key.isEmpty()) null
        else com.neethu.aiadapter.api.EmbeddingConfig(
            baseUrl = AiProvider.SILICONFLOW.sdk.baseUrl,
            apiKey = key,
            model = "BAAI/bge-m3",
        )
    }

    val session by produceState<AvatarSession?>(
        initialValue = null, state, ui.aiPrefs, ui.contextId, ui.lang,
    ) {
        delay(800)
        value = aiChat.ensure(
            prefs = ui.aiPrefs,
            ready = state is AvatarState.Ready,
            contextId = ui.contextId,
            characterId = ui.activeCardFile,
            lang = ui.lang,
        )?.also { s ->
            s.actionCatalog = buildLlmActionCatalog(builtinAnimationPaths(context), emptyList())
        }
    }
    // 关系分片键跟随激活卡（P3-1；会话重建后重挂）
    LaunchedEffect(session, ui.activeCardFile) {
        aiChat.setRelationshipSubject(ui.activeCardFile)
    }
    LaunchedEffect(state) {
        if (state is AvatarState.Ready) session?.startFaceDriving()
    }

    // ── 字幕与回合状态 ────────────────────────────────────────────────────
    var chatPhase by remember { mutableStateOf(ConversationPhase.IDLE) }
    var autonomyUserSpokeHook by remember { mutableStateOf<(() -> Unit)?>(null) }
    val chatLines = remember { mutableStateListOf<ChatLine>() }
    fun pushUserLine(text: String) {
        val next = ChatTranscript.cap(ChatTranscript.append(chatLines.toList(), ChatRole.USER, text))
        chatLines.clear(); chatLines.addAll(next)
        autonomyUserSpokeHook?.invoke()
    }
    var chatError by remember { mutableStateOf<String?>(null) }
    var currentShot by remember { mutableStateOf<CameraShot?>(null) }

    LaunchedEffect(ui.contextId) { chatLines.clear(); chatError = null }

    LaunchedEffect(session) {
        val s = session ?: return@LaunchedEffect
        // 会话（重）建：激活卡重放 + 延迟开场白 + 世界书覆盖 + 关系分片
        ui.activeCardFile?.let { file ->
            ui.cardByFile(file)?.let { entry ->
                val deferred = ui.pendingGreetingFile == file
                if (deferred) s.clearHistory()
                applyCardToSession(s, entry, { ui.cardPromptOverride(it) }, ui.lang)
                ui.applyLorebookToSession(s)
                if (deferred) {
                    ui.pendingGreetingFile = null
                    val card0 = ui.localizedCard(entry)
                    val greeting = spokenGreetingText(card0, card0.firstMessage, ui.lang)
                    if (greeting.isNotEmpty()) launch { runCatching { s.speak(greeting) } }
                }
            }
        } ?: run {
            s.lorebook = ui.lorebookLibrary.activeBookFor(null)
            s.lorebookEnabled = ui.lorebookEnabled
        }
        launch { s.phase.collect { chatPhase = it } }
        launch {
            s.events.collect { ev ->
                when (ev) {
                    is AvatarEvent.SentenceStarted -> {
                        val next = ChatTranscript.cap(ChatTranscript.append(chatLines.toList(), ChatRole.AVATAR, ev.text))
                        chatLines.clear(); chatLines.addAll(next)
                    }
                    is AvatarEvent.SentenceFailed -> chatError = "第 ${ev.sequence} 句播放失败：${ev.message}"
                    is AvatarEvent.CameraChanged -> {
                        currentShot = ev.shot
                        controller.setCameraShot(ev.shot)
                    }
                    is AvatarEvent.UnknownTag -> {
                        val next = ChatTranscript.cap(ChatTranscript.append(chatLines.toList(), ChatRole.SYSTEM, "（模型想用标签 ${ev.raw}，已过滤）"))
                        chatLines.clear(); chatLines.addAll(next)
                    }
                    is AvatarEvent.ToolCallStarted -> {
                        val next = ChatTranscript.cap(ChatTranscript.append(chatLines.toList(), ChatRole.SYSTEM, "正在调用工具 ${ev.name}…"))
                        chatLines.clear(); chatLines.addAll(next)
                    }
                    is AvatarEvent.TurnFailed -> chatError = ev.error.message ?: "请求失败"
                    else -> Unit
                }
            }
        }
    }

    LaunchedEffect(chatError) {
        if (chatError != null) { delay(6000); chatError = null }
    }

    // ── 走动/交互/自主生命体（demo 同款接线，词汇表按场景注入）────────────────
    val walkAnchors = remember(ui.selectedScene) { loadWalkAnchors(context, ui.selectedScene) }
    val interactionAnchors = remember(ui.selectedScene) { loadInteractionAnchors(context, ui.selectedScene) }
    val interactionCoordinator = remember { InteractionCoordinator(controller, { session }, scope) }
    interactionCoordinator.anchors = interactionAnchors
    val autonomyRig = remember {
        AutonomyRig(scope, controller, { session }, interactionCoordinator, { aiChat.memoryStore }) {
            session?.relationship?.snapshots?.value
        }
    }
    autonomyUserSpokeHook = { autonomyRig.onUserSpoke() }
    LaunchedEffect(interactionAnchors, ui.lang) {
        autonomyRig.setVocabulary(interactionAnchors, loadAutonomySchedule(context), ui.lang)
    }
    LaunchedEffect(ui.aiPrefs.autonomy, ui.aiPrefs.greetings, ui.aiPrefs.thoughtBubble, session != null) {
        autonomyRig.setMounted(
            enabled = ui.aiPrefs.autonomy && session != null,
            greeting = ui.aiPrefs.greetings,
            bubble = ui.aiPrefs.thoughtBubble,
        )
    }
    LaunchedEffect(session, ui.aiPrefs.llmMove, ui.lang, walkAnchors, interactionAnchors) {
        val s = session ?: return@LaunchedEffect
        s.locomotionAnchors = if (ui.aiPrefs.llmMove) walkAnchors else emptyList()
        val wantMoveTools = ui.aiPrefs.llmMove && walkAnchors.isNotEmpty()
        if (wantMoveTools && s.tools.find("move_to") == null) {
            AgentToolCatalog.moveTo(promptTextsOf(ui.lang)) { s.locomotionDriver }
                .let { (spec, exec) ->
                    s.tools.register(spec, ToolExecutor { args ->
                        // 坐/躺中直接走动被 POSE_ACTIVE 拒——先起身再走
                        if (controller.getContactPoseState() != null) {
                            controller.clearContactPose()
                            delay(900)
                        }
                        exec.invoke(args)
                    })
                }
            AgentToolCatalog.stopMoving(promptTextsOf(ui.lang)) { s.locomotionDriver }
                .let { (spec, exec) -> s.tools.register(spec, exec) }
            ensureWalkGaits(controller)
        } else if (!wantMoveTools) {
            s.tools.unregister("move_to"); s.tools.unregister("stop_moving")
        }
        val wantInteractTools = ui.aiPrefs.llmMove && interactionAnchors.isNotEmpty()
        if (wantInteractTools) {
            AgentToolCatalog.interactWith(
                promptTextsOf(ui.lang),
                choices = { interactionAnchors.map { AgentToolCatalog.InteractionChoice(it.id, it.label) } },
                start = { id ->
                    val text = interactionCoordinator.interact(id)
                    when {
                        text.startsWith("interaction") -> AgentToolCatalog.InteractOutcome.STARTED
                        text.startsWith("another") -> AgentToolCatalog.InteractOutcome.BUSY
                        else -> AgentToolCatalog.InteractOutcome.FAILED
                    }
                },
            ).let { (spec, exec) -> s.tools.register(spec, exec) }
            AgentToolCatalog.standUp(promptTextsOf(ui.lang)) { controller.clearContactPose() }
                .let { (spec, exec) -> s.tools.register(spec, exec) }
        } else {
            s.tools.unregister("interact_with"); s.tools.unregister("stand_up")
        }
    }

    // ── 语音：按住说话（云端 ASR 优先，无 Key/选系统 = 免费系统识别）──────────
    var micGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) ui.voiceInputMode = true
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        ui.setGreetingNotify(granted)
    }
    val voiceRecorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recognizing by remember { mutableStateOf(false) }
    var voicePrefill by remember { mutableStateOf<String?>(null) }
    val systemAsr = remember { SystemAsrController(context) }
    DisposableEffect(Unit) {
        onDispose {
            voiceRecorder.cancel()
            systemAsr.stop()
        }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        pushUserLine(text)
        session?.send(text)
    }

    fun onHoldStart() {
        if (!micGranted) { micPermission.launch(Manifest.permission.RECORD_AUDIO); return }
        session?.interrupt()
        recording = true
        runCatching { voiceRecorder.start() }
    }

    fun onHoldEnd() {
        if (!recording) return
        recording = false
        val file = voiceRecorder.stop() ?: return
        if (ui.voicePrefs.asrEngine == AsrEngine.SYSTEM || ui.cloudAsr() == null) {
            recognizing = true
            systemAsr.onFinal = { text, _ ->
                recognizing = false
                if (text.isNotBlank()) sendText(text) else chatError = "没听清，再试一次？"
            }
            systemAsr.onError = { msg -> recognizing = false; chatError = "系统识别不可用：$msg" }
            systemAsr.startSingleShot()
        } else {
            scope.launch {
                recognizing = true
                val result = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        ui.cloudAsr()!!.transcribe(file.readBytes(), mimeForFileName("voice_input.m4a"), ui.asrConfig())
                    }
                }
                recognizing = false
                val text = result.getOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) sendText(text)
                else chatError = result.exceptionOrNull()?.message ?: "识别失败"
            }
        }
    }

    // ── G10 问候通知：开关切换挂/卸周期任务；深链问候等会话就绪后开口 ─────────
    LaunchedEffect(ui.greetingNotifyEnabled) {
        GreetingWorker.schedule(context, ui.greetingNotifyEnabled)
    }
    LaunchedEffect(pendingGreeting, session) {
        val text = pendingGreeting ?: return@LaunchedEffect
        val s = session ?: return@LaunchedEffect
        if (state is AvatarState.Ready) {
            onPendingGreetingConsumed()
            runCatching { s.speak("<emo:joy><act:wave>$text") }
            s.writeAutonomyReceipt("我发过通知问候你：「${text.take(60)}」，你点进来了")
        }
    }

    // ── 约会模式（P3-3）：卡绑场景 + 烛光 + 梦幻灯光 + 音乐 + 开场一句 ────────
    val music = remember { MusicPlayer(context) { Log.i("Companion", "music: $it") } }
    DisposableEffect(Unit) { onDispose { music.stop() } }
    fun toggleDateMode() {
        val turningOn = !ui.dateMode
        ui.changeDateMode(turningOn)
        if (turningOn) {
            VfxPresets.find(DateMode.VFX_ID)?.let { controller.playVfx(it) }
            music.start(DateMode.MUSIC_ASSET)
            scope.launch { runCatching { session?.speak(DateMode.DATE_LINE) } }
        } else {
            music.stop()
            controller.stopAllVfx()
            scope.launch { runCatching { session?.speak(DateMode.DATE_LINE_OFF) } }
        }
    }

    // ── 卡激活链（demo activateEntry 产品版：绑定恢复 + 关系分片 + 开场白）────
    var greetingChooserFile by remember { mutableStateOf<String?>(null) }
    fun activateEntry(entry: com.neethu.orchestrator.card.CharacterCardStore.Entry) {
        if (ui.activeCardFile == entry.fileName) return
        ui.setActiveCard(entry.fileName)
        val binding = ui.cardBindings.forCard(entry.fileName)
        // 卡级绑定：音色/VRM/开场白按卡的档案恢复（P2-3）
        binding?.voice?.takeIf { it.isNotBlank() && it != ui.aiPrefs.voice }?.let { voice ->
            ui.updateAiPrefs(ui.aiPrefs.copy(voice = voice))
        }
        binding?.modelFile?.takeIf { it in ui.modelFiles }?.let { ui.selectModel(it) }
        binding?.sceneFile?.takeIf { it in ui.sceneFiles }?.let { ui.setScene(it) }
        val s = session
        when {
            s != null -> {
                applyCardToSession(s, entry, { ui.cardPromptOverride(it) }, ui.lang)
                ui.applyLorebookToSession(s)
                scope.launch { aiChat.setRelationshipSubject(entry.fileName) }
                s.clearHistory()
                val card = ui.localizedCard(entry)
                val greetings = listOf(card.firstMessage) + card.alternateGreetings
                val boundIndex = binding?.greetingIndex?.takeIf { it in greetings.indices }
                when {
                    greetings.size == 1 -> {
                        val greeting = card.spokenGreeting(promptTextsOf(ui.lang).userAlias)
                        if (greeting.isNotEmpty()) scope.launch { runCatching { s.speak(greeting) } }
                    }
                    boundIndex != null -> {
                        val greeting = com.neethu.orchestrator.card.expandCardMacros(
                            greetings[boundIndex], card.name, promptTextsOf(ui.lang).userAlias,
                        ).trim()
                        if (greeting.isNotEmpty()) scope.launch { runCatching { s.speak(greeting) } }
                    }
                    else -> greetingChooserFile = entry.fileName
                }
            }
            else -> ui.pendingGreetingFile = entry.fileName
        }
    }
    fun deactivateCard() {
        if (ui.activeCardFile != null) {
            ui.setActiveCard(null)
            session?.clearCharacterCard()
            session?.clearHistory()
            session?.let { ui.applyLorebookToSession(it) }
            scope.launch { aiChat.setRelationshipSubject(null) }
        }
    }
    fun deleteCard(entry: com.neethu.orchestrator.card.CharacterCardStore.Entry) {
        val wasActive = ui.activeCardFile == entry.fileName
        ui.deleteCard(entry)
        if (wasActive) {
            session?.clearCharacterCard()
            session?.clearHistory()
            session?.let { ui.applyLorebookToSession(it) }
            scope.launch { aiChat.setRelationshipSubject(null) }
        }
    }

    // ── SAF 出入口 ────────────────────────────────────────────────────────
    val cardPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val entry = bytes?.let { ui.importCardBytes(it) }
            if (entry == null) chatError = "卡片解析失败（支持酒馆 PNG/JSON 卡）" else activateEntry(entry)
        }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching { ImportedModelLibrary.import(context, uri) }.getOrNull()
            if (name != null) ui.selectModel(name) else chatError = "模型导入失败"
        }
    }
    val lorebookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            val entry = bytes?.let { ui.importLorebookBytes(it, uri.lastPathSegment) }
            if (entry == null) chatError = "世界书解析失败"
            else {
                session?.let { ui.applyLorebookToSession(it) }
                chatError = null
            }
        }
    }
    val exportJsonl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val jsonl = com.neethu.orchestrator.history.StChatTranscript.toJsonl(
                ui.activeCardName() ?: "Char", "User",
                session?.historyMessages().orEmpty(),
            )
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(jsonl.toByteArray()) } }
        }
    }
    val exportMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            val md = com.neethu.orchestrator.history.StChatTranscript.toMarkdown(
                ui.activeCardName() ?: "Char", "User",
                session?.historyMessages().orEmpty(),
            )
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(md.toByteArray()) } }
        }
    }
    val importHistoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val raw = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val lines = raw?.let { com.neethu.orchestrator.history.StChatTranscript.parseJsonl(it) }.orEmpty()
            if (lines.isEmpty()) {
                chatError = "导入失败：不是有效的 ST jsonl"
            } else {
                session?.importHistory(com.neethu.orchestrator.history.StChatTranscript.toMessages(lines))
                chatLines.clear()
            }
        }
    }

    // ── 面板显隐（声明在前:下方 effect 引用）──────────────────────────────
    var panel by remember { mutableStateOf(PanelType.NONE) }
    var relationshipSheet by remember { mutableStateOf(false) }

    // ── 上下文列表（设置面板数据源）────────────────────────────────────────
    val convoDb = remember { com.neethu.orchestrator.history.ConversationDatabase.getInstance(context) }
    var contextListVersion by remember { mutableStateOf(0) }
    val contextList by produceState<List<com.neethu.companion.ConversationContextSummary>>(
        initialValue = emptyList(), contextListVersion,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.neethu.companion.loadContextSummaries(convoDb)
        }
    }
    LaunchedEffect(panel) { if (panel == PanelType.SETTINGS) contextListVersion++ }
    val historyCount = remember(contextList, ui.contextId) {
        contextList.firstOrNull { it.id == ui.contextId }?.messageCount ?: 0
    }

    var groupChatOpen by remember { mutableStateOf(false) }

    // ── ai_cmd 调试通道（真机验收;demo 同款 logcat 回读惯例）──────────────────
    LaunchedEffect(Unit) {
        val hooks = com.neethu.companion.debug.CompanionDebugHooks(
            sessionProvider = { session },
            stateLine = {
                "model=${ui.selectedModel} scene=${ui.selectedScene ?: "none"} card=${ui.activeCardFile ?: "none"} " +
                    "phase=$chatPhase provider=${ui.aiPrefs.provider.name} model2=${ui.aiPrefs.llmModel} ctx=${ui.contextId.take(8)}"
            },
            autonomyStateLine = { autonomyRig.stateLine() },
            autonomyToggle = { arg -> autonomyRig.toggle(arg) },
            forceGreet = { autonomyRig.forceGreet() },
            forceThink = { "think unavailable" },
            relationshipLine = { session?.relationship?.promptLine },
            activateCard = { file ->
                val entry = ui.cardByFile(file)
                if (entry == null) {
                    "card not found: $file (see 'cards')"
                } else {
                    activateEntry(entry)
                    "activated: $file"
                }
            },
            clearCard = { deactivateCard(); "card cleared" },
            cardsSnapshot = { ui.cards.joinToString("\n") { c -> c.fileName + " | " + c.card.name.take(20) } },
            sendText = { text -> sendText(text) },
            sendGreeting = { text -> scope.launch { runCatching { session?.speak(text) } } },
            memoryList = {
                val all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { aiChat.memoryStore.all() }
                if (all.isEmpty()) "memory empty" else all.joinToString("\n") { "[${it.id.take(8)}]${it.category} ${it.content.take(40)}" }
            },
            memoryForget = { id ->
                val all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { aiChat.memoryStore.all() }
                val hit = all.firstOrNull { it.id.startsWith(id) }
                if (hit == null) {
                    "no memory matches $id"
                } else {
                    aiChat.memoryStore.delete(hit.id)
                    "forgot ${hit.id.take(8)}"
                }
            },
            memoryClear = {
                aiChat.memoryStore.clear()
                "memory cleared"
            },
        )
        while (true) {
            pollAiCommand()?.let { cmd ->
                val result = com.neethu.companion.debug.executeAiCommand(cmd, hooks)
                Log.i(com.neethu.companion.AI_LOG_TAG, "ai_cmd ${cmd.name} -> $result")
            }
            delay(300)
        }
    }

    // 群聊屏为覆盖层而非 early-return:移除 AvatarView 会触发 corelib 引擎
    // 销毁,Choreographer 下一帧撞已释放的 TransformManager(SIGSEGV 真机实锤)。
    // 主渲染保持在组合树里被遮盖即可。

    // 关系快照（G11 低调状态行数据源）
    val relationship = session?.relationship?.snapshots?.collectAsState()?.value

    Box(modifier = Modifier.fillMaxSize()) {
      Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AvatarView(
                modifier = Modifier.fillMaxSize(),
                controller = controller,
                config = AvatarConfig(iblPath = "default_env.ktx"),
            )
            if (ui.aiPrefs.thoughtBubble) {
                ThoughtBubbleOverlay(controller = controller, state = autonomyRig.bubble)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
            ) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = 0.45f)) {
                    Row(modifier = Modifier.padding(2.dp)) {
                        IconButton(onClick = { groupChatOpen = true }) {
                            Icon(Icons.Default.Person, contentDescription = "多卡群聊", tint = Color.White)
                        }
                        IconButton(onClick = { toggleDateMode() }) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = "约会模式",
                                tint = if (ui.dateMode) Color(0xFFFF6E91) else Color.White,
                            )
                        }
                    }
                }
                Spacer(Modifier.size(6.dp))
                if (currentShot != null) {
                    Surface(shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.45f)) {
                        Text(
                            currentShot!!.label(ui.lang),
                            color = Color.White, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Row(modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = Color.Black.copy(alpha = 0.45f)) {
                    Row(modifier = Modifier.padding(2.dp)) {
                        IconButton(onClick = { panel = if (panel == PanelType.CARDS) PanelType.NONE else PanelType.CARDS }) {
                            Icon(Icons.Default.Face, contentDescription = "角色卡", tint = Color.White)
                        }
                        IconButton(onClick = { panel = if (panel == PanelType.MODELS) PanelType.NONE else PanelType.MODELS }) {
                            Icon(Icons.Default.Home, contentDescription = "模型", tint = Color.White)
                        }
                        IconButton(onClick = { panel = if (panel == PanelType.SETTINGS) PanelType.NONE else PanelType.SETTINGS }) {
                            Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color.White)
                        }
                    }
                }
            }
        }

        AiChatBar(
            phase = chatPhase,
            chatLines = chatLines,
            error = chatError,
            enabled = ui.aiPrefs.isConfigured,
            voiceMode = ui.voiceInputMode,
            recording = recording,
            recognizing = recognizing,
            voiceFreeTalk = false,
            freeHearing = false,
            onToggleFreeTalk = {},
            voicePrefill = voicePrefill,
            onVoicePrefillConsumed = { voicePrefill = null },
            onHoldStart = ::onHoldStart,
            onHoldEnd = ::onHoldEnd,
            onSend = ::sendText,
            onInterrupt = { session?.interrupt() },
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            relationshipLine = relationshipStatusLine(relationship),
            onRelationshipTap = { relationshipSheet = true },
            onTapWhenDisabled = { panel = PanelType.SETTINGS },
        )
      }
      if (groupChatOpen) {
        GroupChatScreen(
            cards = ui.cards,
            bytesFor = { ui.cardLibrary.bytes(it) },
            aiPrefs = ui.aiPrefs,
            bindings = ui.cardBindings,
            memoryStore = aiChat.memoryStore,
            lang = ui.lang,
            defaultModels = ui.modelFiles.filter { it.endsWith(".vrm") },
            onDismiss = { groupChatOpen = false },
        )
      }
    }

    when (panel) {
        PanelType.CARDS -> CardsPanel(
            cards = ui.cards,
            activeFile = ui.activeCardFile,
            presets = ui.presetCards,
            presetNameFor = { preset -> ui.localizedCardName(preset) },
            activePresetAsset = ui.activeCardFile?.let { ui.presetAssetByFile(it) },
            bytesFor = { ui.cardLibrary.bytes(it) },
            bindingFor = { ui.cardBindings.forCard(it) },
            voiceLabelFor = { com.neethu.companion.voiceLabel(it) },
            onSelectPreset = { preset ->
                ui.selectPresetCard(preset)?.let { activateEntry(it) }
            },
            onClearCard = { deactivateCard() },
            onImport = { cardPicker.launch(arrayOf("image/png", "application/json")) },
            onActivate = { entry -> activateEntry(entry) },
            onDelete = { entry -> deleteCard(entry) },
        )
        PanelType.MODELS -> ListPanel(
            title = "模型（点切换）",
            entries = ui.modelFiles,
            selectedItem = ui.selectedModel,
            displayName = { it },
            onItemClick = { ui.selectModel(it) },
            header = {
                TextButton(onClick = { modelPicker.launch(arrayOf("application/octet-stream", "model/gltf-binary")) }) {
                    Text("＋ 导入 VRM 文件…", fontSize = 13.sp)
                }
            },
        )
        PanelType.SETTINGS -> SettingsPanel(
            aiPrefs = ui.aiPrefs,
            voicePrefs = ui.voicePrefs,
            appLangZh = ui.langZh,
            onAiPrefsChange = { ui.updateAiPrefs(it) },
            onVoicePrefsChange = { ui.updateVoicePrefs(it) },
            onAppLangChange = { ui.changeLang(it) },
            relationshipTracking = ui.relationshipTracking,
            onRelationshipTrackingChange = { ui.setRelationshipTracking(it, aiChat) },
            greetingNotify = ui.greetingNotifyEnabled,
            onGreetingNotifyChange = { enabled ->
                if (enabled) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                        ui.setGreetingNotify(true)
                    } else {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                } else {
                    ui.setGreetingNotify(false)
                }
            },
            onResetRelationship = { scope.launch { aiChat.session?.relationship?.reset() } },
            memoryPanelOpen = { panel = PanelType.MEMORY },
            lorebooks = ui.lorebooks,
            lorebookEnabled = ui.lorebookEnabled,
            lorebookGlobalFile = ui.lorebookLibrary.globalFile,
            onLorebookEnabledChange = { ui.setLorebookEnabled(it, session) },
            onImportLorebook = { lorebookPicker.launch(arrayOf("application/json")) },
            onLorebookSetGlobal = { ui.setGlobalLorebook(it, session) },
            onLorebookDelete = { ui.deleteLorebook(it, session) },
            contexts = contextList,
            activeContextId = ui.contextId,
            historyMessageCount = historyCount,
            onNewContext = { ui.newContext() },
            onSelectContext = { ui.switchContext(it) },
            onDeleteContext = { ui.deleteContext(it) },
            onExportHistoryJsonl = { exportJsonl.launch("companion-chat-${System.currentTimeMillis()}.jsonl") },
            onExportHistoryMarkdown = { exportMarkdown.launch("companion-chat-${System.currentTimeMillis()}.md") },
            onImportHistoryJsonl = { importHistoryPicker.launch(arrayOf("application/octet-stream")) },
            ambienceNames = listOf(
                "STUDIO" to "摄影棚", "BRIGHT" to "明亮", "DIM" to "夜话",
                "SPOTLIGHT" to "聚光", "DREAMY" to "梦幻",
            ),
            selectedAmbience = ui.ambience,
            onAmbienceChange = { ui.setAmbience(it, controller) },
            historySummaryEnabled = ui.aiPrefs.historySummary,
            onHistorySummaryChange = { ui.updateAiPrefs(ui.aiPrefs.copy(historySummary = it)) },
            onDismiss = { panel = PanelType.NONE },
            modifier = Modifier.padding(8.dp),
        )
        PanelType.MEMORY -> MemoryPanel(store = aiChat.memoryStore, lang = ui.lang, onDismiss = { panel = PanelType.NONE })
        PanelType.NONE -> Unit
    }

    if (relationshipSheet) {
        RelationshipSheet(
            snapshot = relationship,
            cardName = ui.activeCardFile?.let { ui.cardByFile(it)?.card?.name },
            onDismiss = { relationshipSheet = false },
            onReset = {
                scope.launch { aiChat.session?.relationship?.reset() }
                relationshipSheet = false
            },
        )
    }
}

internal enum class PanelType { NONE, MODELS, CARDS, SETTINGS, MEMORY }
