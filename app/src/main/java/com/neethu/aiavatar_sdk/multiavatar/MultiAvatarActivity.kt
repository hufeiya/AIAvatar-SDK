package com.neethu.aiavatar_sdk.multiavatar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.aiavatar_sdk.AiChatPrefs
import com.neethu.aiavatar_sdk.DEFAULT_WALK_BASE_SPEED
import com.neethu.aiavatar_sdk.DEFAULT_WALK_CLIP
import com.neethu.aiavatar_sdk.loadAiPrefs
import com.neethu.aiavatar_sdk.loadWalkAnchors
import com.neethu.aiavatar_sdk.multiavatar.show.DanceShowController
import com.neethu.aiavatar_sdk.multiavatar.show.ShowState
import com.neethu.aiavatar_sdk.multiavatar.show.parseCameraShot
import com.neethu.aiavatar_sdk.parseSceneMeta
import com.neethu.aiavatar_sdk.sceneMetaAssetName
import com.neethu.corelib.AvatarConfig
import com.neethu.corelib.AvatarState
import com.neethu.corelib.CameraShot
import com.neethu.corelib.vfx.VfxAnchor
import com.neethu.corelib.accessory.AccessorySource
import com.neethu.corelib.accessory.AccessorySpec
import com.neethu.corelib.stage.AvatarHandle
import com.neethu.corelib.stage.AvatarSpec
import com.neethu.corelib.stage.AvatarSpawn
import com.neethu.corelib.stage.AvatarStage
import com.neethu.corelib.stage.AvatarStageView
import com.neethu.corelib.stage.rememberAvatarStage
import com.neethu.orchestrator.roundtable.RoundtableSession
import com.neethu.orchestrator.roundtable.RoundtableState
import com.neethu.orchestrator.session.AvatarEvent
import java.io.File

/**
 * 多虚拟人舞台的调试/演示入口（docs/multi-avatar-feasibility.md P0 渲染 +
 * P1 会话多实例/人机路由）：同一 Scene 渲染 N 个 VRM，各自独立模型/站位/
 * 表情/视线/走动/会话（人设卡名、服务商、模型、音色、上下文、记忆分片），
 * 共享灯光、场景与一台相机。
 *
 * 与 MainActivity 的关系：零共享状态、零代码改动；只复用 app 内既有的
 * 独立工具（场景 meta 解析 / 锚点表 / 默认步态常量 / AiChatPrefs 的 Key）。
 *
 * adb 直达：`adb shell am start -n com.neethu.aiavatar_sdk/.multiavatar.MultiAvatarActivity`
 * adb 调试：`adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "<命令>"`
 * （命令表见 [StageCommandRouter]：渲染类 state/shot/load/… + 聊天类
 * chat/say/interrupt/chat_state/cfg/memory + 圆桌类 topic/director + 团播类 dance）
 */
class MultiAvatarActivity : ComponentActivity() {

    /** 团播控制器（composition 内装配，生命周期回调经此转音乐暂停/恢复）。 */
    internal var showRef: DanceShowController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MultiAvatarScreen() }
    }

    override fun onStart() {
        super.onStart()
        showRef?.onHostResume()
    }

    override fun onStop() {
        super.onStop()
        showRef?.onHostPause()
    }
}

// ── 演示默认配置 ─────────────────────────────────────────────────────────

/** 默认双人组合：10.vrm = VRM 1.0 + MToon×24（fresh 加载自动检测 MTOON）；
 *  47058…vrm = VRM 0.x + MToon v0×16（Y-180 翻转 + v0 材质转换双路径覆盖）。 */
private const val DEFAULT_MODEL_A = "vrms/10.vrm"
private const val DEFAULT_MODEL_B = "vrms/4705834820915235427.vrm"

/** 双人共用同一 idle（VRMA 舞台级缓存：解析一次、两份引擎状态共享只读数据）。 */
private const val DEFAULT_IDLE = "animations/01_待机与站姿/Female Idle.vrma"

private const val DEFAULT_SCENE = "home.glb"

/** 槽位表（broadcast 路由与 Compose 共用的可变引用）：Map 承载，A/B 语义保留，
 *  `load C <模型>` 按需扩第三人（团播 P0，§3.5）。 */
internal class Slots {
    private val map = mutableStateMapOf<String, AvatarHandle?>("A" to null, "B" to null)

    operator fun get(id: String): AvatarHandle? = map[id.uppercase()]

    operator fun set(id: String, value: AvatarHandle?) {
        map[id.uppercase()] = value
    }

    fun entries(): List<Pair<String, AvatarHandle?>> =
        map.entries.sortedBy { it.key }.map { it.key to it.value }

    /** 就位演员 id（团播 cast 的来源）。 */
    fun readyIds(): List<String> =
        entries().filter { it.second?.isReady == true }.map { it.first }

    /** 全部槽位 id（fx play 的 slot 归属判定用）。 */
    fun ids(): Set<String> = entries().map { it.first }.toSet()
}

/** 字幕分道的一行（P1 §4.9：ChatLine 加 speaker 语义的舞台版）。 */
data class StageChatLine(
    val slotId: String?,   // null = 用户
    val speaker: String,
    val text: String,
) {
    val isUser: Boolean get() = slotId == null
}

@Composable
private fun MultiAvatarScreen() {
    val context = LocalContext.current
    val stage = rememberAvatarStage()
    val slots = remember { Slots() }
    var lastLog by remember { mutableStateOf("ready") }
    var panelVisible by remember { mutableStateOf(true) }
    val attached by stage.attached.collectAsState()

    // P1：聊天装配（每 slot 独立 scope+facade+记忆分片，上盖 RoundtableSession）
    val prefsStore = remember { context.getSharedPreferences("demo_settings", Context.MODE_PRIVATE) }
    val rig = remember {
        StageChatRig(
            appContext = context.applicationContext,
            globalPrefs = { prefsStore.loadAiPrefs() },
            prefs = prefsStore,
            anchors = loadWalkAnchors(context),
        )
    }
    // P0 团播：节目单导演的落地层（互斥检查/白名单/指令执行都在控制器）
    val show = remember {
        DanceShowController(context, stage, slots, rig) { msg -> lastLog = msg.take(120) }
            .also { (context as? MultiAvatarActivity)?.showRef = it }
    }
    // P1 配饰：规格解析器注入（动画联动自动挂载；sidecar 默认+per-model 校准合并）
    LaunchedEffect(stage) {
        stage.accessorySpecResolver = { modelId, accId ->
            AccessoryCatalog.find(context, accId)?.let {
                AccessoryCatalog.specFor(context, modelId, it)
            }
        }
    }
    // 演出状态（P2：聊天框输入在演出中切换为点播路由）
    val showState by show.state.collectAsState()
    // 关闭顺序：先停演出（导演协程/事件收集还引用 rig）再关聊天装配
    DisposableEffect(Unit) {
        onDispose {
            (context as? MultiAvatarActivity)?.showRef = null
            show.close()
            rig.close()
        }
    }

    val transcript = remember { mutableStateListOf<StageChatLine>() }
    var currentSpeaker by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AvatarStageView(
            modifier = Modifier.fillMaxSize(),
            config = AvatarConfig(),
            stage = stage,
        )

            if (attached) {
                // 开场装配：场景 + 双人（spawn 并排站位）
                LaunchedEffect(stage) {
                    if (slots["A"] == null && slots["B"] == null) {
                        val meta = runCatching {
                            context.assets.open(sceneMetaAssetName(DEFAULT_SCENE))
                                .use { it.readBytes().toString(Charsets.UTF_8) }
                        }.getOrNull()?.let { parseSceneMeta(it) }
                        stage.loadScene("scene/$DEFAULT_SCENE", meta)
                        slots["A"] = stage.loadAvatar(
                            AvatarSpec(modelAssetPath = DEFAULT_MODEL_A, spawn = AvatarSpawn.LEFT)
                        )
                        slots["B"] = stage.loadAvatar(
                            AvatarSpec(modelAssetPath = DEFAULT_MODEL_B, spawn = AvatarSpawn.RIGHT)
                        )
                    }
                }

            SlotBootstrap(slots.entries(), rig)

            StageUi(
                stage = stage,
                slots = slots,
                show = show,
                lastLog = lastLog,
                panelVisible = panelVisible,
                onTogglePanel = { panelVisible = !panelVisible },
            )

            ChatPanel(
                modifier = Modifier.align(Alignment.BottomCenter),
                rig = rig,
                slots = slots,
                transcript = transcript,
                speaker = currentSpeaker,
                onLog = { lastLog = it.take(120) },
                canStartTopic = { !show.isRunning },
                showRunning = showState == ShowState.RUNNING,
                onDm = { show.handleDm(it) },
            )

            // 舞台事件 → 字幕分道（发言句 + 失败行）；同时跟踪当前发言者（高亮）
            LaunchedEffect(rig) {
                rig.stageEvents.collect { (slotId, event) ->
                    onStageEvent(slotId, event, transcript)
                    when (event) {
                        is AvatarEvent.SentenceStarted -> currentSpeaker = slotId
                        is AvatarEvent.TurnCompleted,
                        is AvatarEvent.TurnFailed,
                        is AvatarEvent.PlaybackInterrupted,
                        -> currentSpeaker = null

                        else -> Unit
                    }
                }
            }

            // adb 调试通道（槽位/控制器变化时重建路由，闭包永远拿到最新引用）
            DisposableEffect(slots.entries()) {
                val router = StageCommandRouter(context, stage, slots, rig, show) { msg -> lastLog = msg }
                val filter = IntentFilter(ACTION_STAGE_CMD)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(router, filter, Context.RECEIVER_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(router, filter)
                }
                onDispose { context.unregisterReceiver(router) }
            }
        }
    }
}

/** 舞台事件进字幕流（P1：说话人分道；失败行可见，同 UnknownTag 灰字思路）。 */
private fun onStageEvent(slotId: String, event: AvatarEvent, transcript: MutableList<StageChatLine>) {
    when (event) {
        is AvatarEvent.SentenceStarted ->
            transcript.add(StageChatLine(slotId, slotId, event.text))
        is AvatarEvent.TurnFailed ->
            transcript.add(StageChatLine(slotId, slotId, "⚠ ${event.error.message ?: "回合失败"}"))
        else -> Unit
    }
    while (transcript.size > 60) transcript.removeAt(0)
}

/** 槽位就绪后挂 idle + 默认走动步态 + 配饰动画绑定表 + 上线聊天（呼吸/眨眼引擎默认开，无需接线）。 */
@Composable
private fun SlotBootstrap(slotEntries: List<Pair<String, AvatarHandle?>>, rig: StageChatRig) {
    val context = LocalContext.current
    slotEntries.forEach { (id, slot) ->
        if (slot == null) return@forEach
        val state by slot.state.collectAsState()
        LaunchedEffect(slot, state) {
            if (state is AvatarState.Ready) {
                slot.setVrmaIdleAnimation(DEFAULT_IDLE)
                slot.setWalkGait(DEFAULT_WALK_CLIP, DEFAULT_WALK_BASE_SPEED)
                // P1 配饰：bindings.json 白名单（玩手机动画自动出手机等），重复注册幂等
                AccessoryCatalog.loadBindings(context).forEach { b ->
                    slot.bindAccessoryToAnimation(b.accessory, b.animation)
                }
                rig.ensureSlot(id, slot)
            }
        }
    }
}

// ── UI 面板 ──────────────────────────────────────────────────────────────

@Composable
private fun StageUi(
    stage: AvatarStage,
    slots: Slots,
    show: DanceShowController,
    lastLog: String,
    panelVisible: Boolean,
    onTogglePanel: () -> Unit,
) {
    val fps by stage.fps.collectAsState()
    val scenePath by stage.scenePath.collectAsState()
    val showState by show.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onTogglePanel() }
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "fps=$fps  avatars=${stage.avatars.size}  " +
                    "scene=${if (scenePath != null) "on" else "off"}  " +
                    "shot=${stage.getActiveCameraShot()?.name ?: "-"}  " +
                    "show=${showState.name.lowercase()}  " +
                    "drag=${if (stage.isDragMode()) "on" else "off"}",
                color = Color.White,
                fontSize = 12.sp,
            )
            Text(if (panelVisible) "▾" else "▴", color = Color.White, fontSize = 13.sp)
        }

        if (panelVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                slots.entries().forEach { (id, handle) ->
                    val others = slots.entries()
                        .filter { it.first != id }
                        .mapNotNull { it.second?.takeIf { h -> h.isReady } }
                    SlotControls(id, handle, others)
                }
                CameraControls(stage, slots)
                DanceControls(show)
                Text("› $lastLog", color = Color(0xFF9FE29F), fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SlotControls(id: String, slot: AvatarHandle?, others: List<AvatarHandle>) {
    val state = slot?.state?.collectAsState()?.value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val badge = when (val s = state) {
            is AvatarState.Ready -> "$id✓"
            is AvatarState.Loading -> "$id…"
            is AvatarState.Error -> "$id✗"
            else -> "$id-"
        }
        Text(badge, color = Color.White, fontSize = 12.sp)
        if (slot != null && state is AvatarState.Ready) {
            SmallButton("开心") { slot.setExpression("joy", 1f); slot.setExpression("happy", 1f) }
            SmallButton("生气") { slot.setExpression("angry", 1f) }
            SmallButton("悲伤") { slot.setExpression("sorrow", 1f); slot.setExpression("sad", 1f) }
            SmallButton("闭眼") { slot.setExpression("blink", 1f) }
            SmallButton("复位脸") { slot.clearAllExpressions() }
            others.forEach { other ->
                SmallButton("看${other.id}") { slot.lookAt(other) }
            }
            SmallButton("看镜头") { slot.lookAtCamera() }
            SmallButton("停动作") { slot.stopVrmaAnimation(0.25f) }
        }
    }
}

@Composable
private fun CameraControls(stage: AvatarStage, slots: Slots) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        slots.entries().forEach { (id, handle) ->
            SmallButton("特写$id") { stage.setCameraShot(CameraShot.CLOSE_UP, handle) }
            SmallButton("跟拍$id") { stage.setCameraShot(CameraShot.FOLLOW, handle) }
        }
        SmallButton("同框") { stage.setCameraShot(CameraShot.GROUP_SHOT) }
        SmallButton("全景") { stage.setCameraShot(CameraShot.FULL_SHOT) }
        SmallButton("远景") { stage.setCameraShot(CameraShot.LONG_SHOT) }
        SmallButton("清除运镜") { stage.clearCameraShot() }
        SmallButton("相机复位") { stage.resetCamera() }
        SmallButton("拖拽") { stage.setDragMode(!stage.isDragMode()) }
    }
}

/** 团播面板（P1 §7：极简四件——开演/停演/下一幕 + 幕次进度行）。 */
@Composable
private fun DanceControls(show: DanceShowController) {
    val state by show.state.collectAsState()
    val progress by show.progress.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SmallButton("开演") { show.startShow(null) }
            SmallButton("停演") { show.stopShow() }
            SmallButton("下一幕") { show.skip() }
        }
        Text(
            "dance ${state.name.lowercase()} ${progress.summary}",
            color = Color(0xFF9FE29F),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

// ── 聊天面板（P1：字幕分道 + 输入路由 + 槽位配置）────────────────────────

@Composable
private fun ChatPanel(
    modifier: Modifier,
    rig: StageChatRig,
    slots: Slots,
    transcript: MutableList<StageChatLine>,
    speaker: String?,
    onLog: (String) -> Unit,
    canStartTopic: () -> Boolean,
    showRunning: Boolean,
    onDm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var configVisible by remember { mutableStateOf(false) }
    // 编排实例换代（配置变化）自动重组；导演日志/话题状态随实例收集
    val rt by rig.roundtableState.collectAsState()
    val targetName = rt?.lastAddressedId?.let { id ->
        val spec = rt?.slotSpecs()?.firstOrNull { it.id == id }
        "${spec?.id ?: id}(${spec?.displayName ?: "?"})"
    } ?: "未配置"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // 字幕分道（最近 4 行，说话人着色；▶=正在发言）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 110.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            transcript.takeLast(6).forEach { line ->
                val mark = if (!line.isUser && line.slotId == speaker) "▶ " else ""
                Text(
                    "$mark${if (line.isUser) "我" else line.slotId}·${line.text}",
                    color = when {
                        line.isUser -> Color.White
                        line.slotId == "A" -> Color(0xFF8FD8FF)
                        else -> Color(0xFFFFC98F)
                    },
                    fontSize = 11.sp,
                    maxLines = 2,
                )
            }
        }

        // 圆桌话题行（P2 §4.9 StagePanel）：开题/停题 + 导演档位切换
        RoundtablePanel(rig, rt, onLog, canStartTopic)

        // 输入行：点名路由（@名字 / 默认 lastAddressed）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF22262E))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                ),
                cursorBrush = SolidColor(Color.White),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text("说点什么（@A/@B 点名）→ $targetName", color = Color(0xFF79828F), fontSize = 12.sp)
                        }
                        inner()
                    }
                },
            )
            SmallButton("发送") {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    transcript.add(StageChatLine(null, "我", text))
                    if (showRunning) {
                        // 演出中聊天框=点播（P2 §4.8：演出归导演，输入走 dm 通道）
                        onDm(text)
                        onLog("dm → 点播路由（演出中）")
                    } else {
                        val hit = rig.roundtable?.sendFromUser(text)
                        onLog(if (hit != null) "chat → $hit" else "chat → 无人接话（会话未配置？）")
                    }
                    input = ""
                }
            }
            SmallButton("打断") {
                rig.roundtable?.interrupt()
                onLog("interrupt → 非空闲会话")
            }
            SmallButton(if (configVisible) "收起" else "配置") { configVisible = !configVisible }
        }

        if (configVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                slots.entries().forEach { (id, handle) ->
                    SlotConfigRow(rig, id, handle != null, onLog)
                }
            }
        }
    }
}

/** 圆桌面板（P2）：话题输入 + 开题/停题 + 导演档位 + 最近一条导演决策。 */
@Composable
private fun RoundtablePanel(
    rig: StageChatRig,
    rt: RoundtableSession?,
    onLog: (String) -> Unit,
    canStartTopic: () -> Boolean,
) {
    var topic by remember { mutableStateOf("") }
    val local = rt
    val state = if (local != null) local.state.collectAsState().value else RoundtableState.IDLE
    val lastDecision = if (local != null) {
        local.directorLog.collectAsState().value.lastOrNull()
    } else {
        null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicTextField(
            value = topic,
            onValueChange = { topic = it },
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF22262E))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color(0xFFB9C2CE),
                fontSize = 12.sp,
            ),
            cursorBrush = SolidColor(Color.White),
            decorationBox = { inner ->
                Box {
                    if (topic.isEmpty()) {
                        Text(
                            "圆桌话题（state=${state.name.lowercase()}）",
                            color = Color(0xFF79828F),
                            fontSize = 12.sp,
                        )
                    }
                    inner()
                }
            },
        )
        SmallButton("开题") {
            val t = topic.trim()
            when {
                t.isEmpty() -> Unit
                !canStartTopic() -> onLog("拒: 团播演出中，先 dance stop")
                else -> {
                    val ok = rig.startTopic(t)
                    onLog(if (ok) "topic 开题：$t" else "topic 失败（会话未配置？）")
                    if (ok) topic = ""
                }
            }
        }
        SmallButton("停题") {
            rig.stopTopic()
            onLog("topic stopped")
        }
        SmallButton("导演:${rt?.directorPolicy?.label ?: "rule"}") {
            val next = if (rt?.directorPolicy?.label == "llm") "rule" else "llm"
            val actual = rig.setDirectorMode(next)
            onLog("director → $actual")
        }
    }
    if (lastDecision != null) {
        Text(
            "导演 $lastDecision",
            color = Color(0xFF9F9FE2),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

/** 槽位配置行：点名名 / 服务商（点击轮换）/ 模型 / 音色，改动即存即重配。 */
@Composable
private fun SlotConfigRow(rig: StageChatRig, id: String, online: Boolean, onLog: (String) -> Unit) {
    var prefs by remember { mutableStateOf(StageSlotPrefs.load(rig.prefs, id)) }
    val providerOrder = com.neethu.aiavatar_sdk.AiProvider.entries

    fun update(transform: (StageSlotPrefs) -> StageSlotPrefs) {
        prefs = transform(prefs)
        StageSlotPrefs.save(rig.prefs, id, prefs)
        rig.reconfigure(id)
        onLog("cfg $id → ${prefs.provider.name} ${prefs.llmModel}")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("$id${if (online) "✓" else "-"}", color = Color.White, fontSize = 12.sp)
        ConfigField("名字", prefs.displayName) { v -> if (v.isNotEmpty()) update { it.copy(displayName = v) } }
        SmallButton(prefs.provider.label) {
            val next = providerOrder[(providerOrder.indexOf(prefs.provider) + 1) % providerOrder.size]
            update { it.copy(provider = next, llmModel = "", voice = "") }
        }
        ConfigField("模型", prefs.llmModel.ifEmpty { "默认" }) { v -> update { it.copy(llmModel = v) } }
        ConfigField("音色", prefs.voice.ifEmpty { "默认" }) { v -> update { it.copy(voice = v) } }
    }
}

@Composable
private fun ConfigField(label: String, value: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .widthIn(min = 90.dp)
                .background(Color(0xFF22262E))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
            cursorBrush = SolidColor(Color.White),
        )
        if (text != value) {
            SmallButton("✓") { onCommit(text.trim()) }
        }
    }
}

@Composable
private fun SmallButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, fontSize = 11.sp)
    }
}

// ── adb 调试通道 ─────────────────────────────────────────────────────────

const val ACTION_STAGE_CMD = "com.neethu.aiavatar_sdk.STAGE_CMD"

/**
 * 最小命令路由（ai_cmd 的舞台版，真机验收主力手段）：
 *
 * 渲染类（P0）：
 * ```
 * adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "state"
 * adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "expr A joy 1"
 * adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "gaze B A"
 * adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "walk A 1.2 -3 1.0"
 * adb shell am broadcast -a com.neethu.aiavatar_sdk.STAGE_CMD --es cmd "cam closeup B"
 * ```
 * 聊天类（P1）：
 * ```
 * ... --es cmd "chat auto 你好"           # 路由分发（点名/默认）
 * ... --es cmd "chat B 你好"              # 固定槽位
 * ... --es cmd "say A 开场白"             # speak（不进历史）
 * ... --es cmd "interrupt"                # 打断非空闲会话
 * ... --es cmd "chat_state"               # 编排+各槽会话快照
 * ... --es cmd "cfg A provider volcano"   # 槽位配置（name/provider/model/voice/persona）
 * ... --es cmd "memory A list"            # 槽位记忆分库（list/count/clear）
 * ```
 * 圆桌类（P2）：
 * ```
 * ... --es cmd "topic 周末去哪玩"         # 开题（导演接管发言权）
 * ... --es cmd "stop"                     # 停题
 * ... --es cmd "director llm"             # 导演热切换（rule|llm）
 * ... --es cmd "director rounds 5"        # 轮数上限（验收收场白用）
 * ... --es cmd "director_log"             # 导演决策日志全量
 * ```
 * 空间智能（P3）：
 * ```
 * ... --es cmd "cam group"                # 双人同框（GROUP_SHOT 跟踪机位）
 * ... --es cmd "turn A 0.8 -4.0"          # 原地转向世界点（对谈位互相对着）
 * ```
 * 团播（P0/P1，docs/multi-dance-feasibility.md §4.5）：
 * ```
 * ... --es cmd "dance"                    # 快照：state/幕次/cast/音乐/auto/点播成本/fps
 * ... --es cmd "dance start [名]"         # 开演（缺省=内置默认单；圆桌 ACTIVE 时拒绝）
 * ... --es cmd "dance load <名>"          # filesDir/shows/（回落 assets/shows/）装载并校验
 * ... --es cmd "dance stop"               # 停演四清（音乐停/说话打断/停舞走动停/注视复位）
 * ... --es cmd "dance next"               # 跳过当前幕
 * ... --es cmd "dance list"               # 曲目白名单数 + 已装载节目单幕次表
 * ```
 * 团播点播（P2，LLM 导播）：
 * ```
 * ... --es cmd "dance dm <文本>"          # 点播（如：让A跳一段街舞）→ 规则档→LLM 档→白名单校验→插幕
 * ... --es cmd "dance auto [on|off]"      # LLM 导播总开关（缺 Key 静默规则档）
 * ... --es cmd "dance polish [on|off]"    # 串场词润色（默认关，失败降级原文）
 * ... --es cmd "dance log"                # 导播决策日志（点播/润色/插幕全程可复盘）
 * ```
 * 配饰（P0 挂载 + P1 动画联动/导入/校准，docs/accessory-feasibility.md §7）：
 * ```
 * ... --es cmd "acc list"                 # 目录（内置+导入）+ 绑定表 + 各槽已挂快照
 * ... --es cmd "acc attach A phone"       # 挂手机（sidecar 默认+该模型校准 preset 合并）
 * ... --es cmd "acc attach A phone head 0 0.1 0 0 0 0 1"   # 显式参数覆盖（米/度/校准系数）
 * ... --es cmd "acc detach A phone"       # 卸载
 * ... --es cmd "acc show|hide A phone"    # 显隐（不卸载）
 * ... --es cmd "acc import <glb路径>"     # 外置 GLB → filesDir/accessories/（adb push 先行）
 * ... --es cmd "acc cal A phone"          # 把 A 当前挂载规格落成 per-model 校准 preset
 * ... --es cmd "acc cal show|clear A phone"   # 查看/删除 preset
 * ... --es cmd "acc bind A radio <动画路径>"   # 追加动画绑定（白名单；玩手机动画自动出手机即内置表）
 * ... --es cmd "acc bindings [A]"         # 查看绑定表
 * ```
 * 特效（P1，docs/vfx-feasibility.md §六）：
 * ```
 * ... --es cmd "fx list"                  # 目录（内置+presets.json 合并）+ 在播快照
 * ... --es cmd "fx play confetti"         # 舞台中心 (0,0,-4) 播彩带
 * ... --es cmd "fx play confetti A"       # 锚定 A 脚底实时位
 * ... --es cmd "fx play fire B bone:rightHand"   # B 右手骨骼锚定（发射跟随）
 * ... --es cmd "fx stop <id|all>" / "fx state"
 * ```
 * 演出中聊天框文本自动走点播路由（演出归导演）；演出收场后可 topic 开题
 * 圆桌点评（两套节目形态合流，§7-P2 彩蛋）。
 * topic 开题时若台上 ≥2 人就位，自动先走对谈位（stage_a/stage_b 锚点优先、
 * 推导落点兜底）再开轮——站位编排，导演日志可见；团播演出中开题被拒（两导演互斥）。
 *
 * 全部结果落 logcat（tag StageCmd）并回显到面板。
 */
private class StageCommandRouter(
    private val context: Context,
    private val stage: AvatarStage,
    private val slots: Slots,
    private val rig: StageChatRig,
    private val show: DanceShowController,
    private val onLog: (String) -> Unit,
) : BroadcastReceiver() {

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg.take(120))
    }

    /** 各槽位最近一次 attach 成功的规格（`acc cal` 落 preset 的数据源）。 */
    private val lastAttachSpecs = mutableMapOf<String, MutableMap<String, AccessorySpec>>()

    /** 外部文件解析：绝对路径优先，回落 app 外部目录相对名（import_model 同款）。 */
    private fun resolveExternalFile(arg: String): File? {
        val absolute = File(arg)
        if (absolute.isFile) return absolute
        val external = context.getExternalFilesDir(null) ?: return null
        return File(external, arg.removePrefix("/")).takeIf { it.isFile }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        Log.i(TAG, "cmd: $cmd")
        try {
            dispatch(cmd.trim().split(Regex("\\s+")))
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
        }
    }

    private fun dispatch(t: List<String>) {
        when (t[0].lowercase()) {
            "state" -> dumpState()
            "chat_state" -> log(rig.snapshot())

            // ── P0 团播（确定性节目单导演）───────────────────────────────
            "dance" -> {
                val sub = t.getOrNull(1)?.lowercase() ?: "status"
                val arg = t.drop(2).joinToString(" ").trim().ifEmpty { null }
                when (sub) {
                    "status" -> log(show.statusLine())
                    "start" -> {
                        val ok = show.startShow(arg)
                        if (ok) log("dance start 受理（幕次推进见 DanceShow）")
                    }
                    "load" -> log(show.loadShow(arg))
                    "stop" -> show.stopShow()
                    "next" -> show.skip()
                    "list" -> log(show.listLine())

                    // ── P2 点播（LLM 导播）───────────────────────────────
                    "dm" -> {
                        val text = t.drop(2).joinToString(" ").trim()
                        if (text.isEmpty()) log("usage: dance dm <点播文本，如：让A跳一段街舞>")
                        else show.handleDm(text)
                    }
                    "auto" -> log(show.setAutoMode(arg))
                    "polish" -> log(show.setPolish(arg))
                    "log" -> log(show.directorLogLine())
                    else -> log(
                        "usage: dance [status|start [名]|load <名>|stop|next|list|" +
                            "dm <文本>|auto on|off|polish on|off|log]",
                    )
                }
            }

            // ── P2 圆桌（导演制）─────────────────────────────────────────
            "topic" -> {
                val text = t.drop(1).joinToString(" ").trim()
                when {
                    text.isEmpty() -> log("usage: topic <话题...>")
                    show.isRunning -> log("拒: 团播演出中，先 dance stop")
                    else -> {
                        val ok = rig.startTopic(text)
                        log(if (ok) "topic 开题：$text" else "topic 失败（会话未配置？）")
                    }
                }
            }

            "stop" -> {
                rig.stopTopic()
                log("roundtable stopped")
            }

            "director" -> when {
                t.size >= 3 && t[1].equals("rounds", true) ->
                    t[2].toIntOrNull()?.let { rig.setDirectorRounds(it); log("director rounds → $it") }
                        ?: log("usage: director rounds <n>")
                t.size >= 2 -> log("director → ${rig.setDirectorMode(t[1])}")
                else -> log("director=${rig.roundtable?.directorPolicy?.label ?: "-"} " +
                    "log=${rig.roundtable?.directorLog?.value ?: emptyList()}")
            }

            "director_log" -> log("log=${rig.roundtable?.directorLog?.value ?: emptyList()}")

            // ── P1 聊天类 ────────────────────────────────────────────────
            "chat" -> {
                val rt = rig.roundtable ?: return log("roundtable off (sessions not configured)")
                if (t.size < 3) return log("usage: chat <slot|auto> <text...>")
                val text = t.drop(2).joinToString(" ")
                val slotToken = t[1].uppercase()
                val hit = if (slotToken == "AUTO") {
                    rt.sendFromUser(text)
                } else {
                    rt.lastAddressedId = slotToken
                    rt.sessionOf(slotToken)?.send(text)
                    slotToken
                }
                val echo = when {
                    hit != null -> hit
                    rt.state.value != com.neethu.orchestrator.roundtable.RoundtableState.IDLE ->
                        "插话已受理（导演安排接话人）"
                    else -> "unroutable"
                }
                log("chat '$text' → $echo")
            }

            "say" -> {
                val rt = rig.roundtable ?: return log("roundtable off")
                if (t.size < 3) return log("usage: say <slot> <text...>")
                val slot = t[1].uppercase()
                rig.sayAsync(slot, t.drop(2).joinToString(" "))
                log("say $slot (async)")
            }

            "interrupt" -> {
                rig.roundtable?.interrupt()
                log("interrupt sent to non-idle sessions")
            }

            "cfg" -> {
                // cfg <slot> <name|provider|model|voice|persona> <value...>
                if (t.size < 3) return log("usage: cfg <slot> <field> <value...>")
                val id = t[1].uppercase()
                val field = t[2].lowercase()
                val value = t.drop(3).joinToString(" ")
                val cur = StageSlotPrefs.load(rig.prefs, id)
                val next = when (field) {
                    "name" -> cur.copy(displayName = value)
                    "provider" -> cur.copy(
                        provider = com.neethu.aiavatar_sdk.AiProvider.entries
                            .firstOrNull { it.name.equals(value, true) || it.label == value }
                            ?: return log("unknown provider '$value' (try ${com.neethu.aiavatar_sdk.AiProvider.entries.joinToString("/") { it.name }})"),
                    )
                    "model" -> cur.copy(llmModel = value)
                    "voice" -> cur.copy(voice = value)
                    "persona" -> cur.copy(persona = value)
                    else -> return log("unknown field '$field'")
                }
                StageSlotPrefs.save(rig.prefs, id, next)
                rig.reconfigure(id)
                log("cfg $id $field='$value' → reconfigured")
            }

            "memory" -> {
                // memory <slot|global> list|count|clear
                if (t.size < 3) return log("usage: memory <slot|global> <list|count|clear>")
                val scope = t[1].uppercase()
                val action = t[2].lowercase()
                val store = if (scope == "GLOBAL") rig.globalMemoryForDebug() else rig.memoryOf(scope)
                log("memory[$scope] ${rig.memoryAction(store, action)}")
            }

            // ── P0 渲染类 ────────────────────────────────────────────────
            "shot" -> captureShot()

            // ── 配饰（P0 挂载 + P1 动画联动/导入/校准，docs/accessory-feasibility.md §7）──
            "acc" -> {
                when (t.getOrNull(1)?.lowercase()) {
                    "list" -> {
                        val all = AccessoryCatalog.all(context)
                        log("catalog(${all.size}): " + all.joinToString(", ") { e ->
                            "${e.id}(${e.license}, bone=${e.defaultBone}" +
                                (if (e.source is AccessorySource.File) ", imported" else "") + ")"
                        })
                        val bound = AccessoryCatalog.loadBindings(context)
                        log("bindings(${bound.size}): " + bound.joinToString(", ") { "${it.accessory}←${it.animation.substringAfterLast('/')}" })
                        for ((slotId, h) in slots.entries()) {
                            val list = h?.accessories?.value.orEmpty()
                            if (list.isNotEmpty()) {
                                log("  $slotId: " + list.joinToString(", ") {
                                    "${it.id}@${it.bone}" + if (it.visible) "" else "(hidden)"
                                })
                            }
                        }
                    }
                    "attach" -> {
                        // acc attach <slot> <id> [bone] [dx dy dz rx ry rz scale]
                        //（显式参数逐层覆盖：命令参数 > per-model 校准 preset > sidecar 默认）
                        val h = slots[t.getOrNull(2) ?: ""]
                            ?: return log("usage: acc attach <slot> <id> [bone] [dx dy dz rx ry rz scale]")
                        val entry = AccessoryCatalog.find(context, t.getOrNull(3) ?: "")
                            ?: return log("unknown accessory '${t.getOrNull(3)}' (try acc list)")
                        val nums = t.drop(5).mapNotNull { it.toFloatOrNull() }
                        if (t.drop(5).size != nums.size) return log("offset args must be numbers")
                        val spec = AccessoryCatalog.specFor(
                            context,
                            modelId = h.spec.displayName,
                            entry = entry,
                            boneOverride = t.getOrNull(4),
                            positionOverride = nums.take(3).takeIf { it.size == 3 }?.toFloatArray(),
                            rotationOverride = nums.drop(3).take(3).takeIf { it.size == 3 }?.toFloatArray(),
                            scaleOverride = nums.getOrNull(6),
                        )
                        h.attachAccessory(spec)
                            .onSuccess {
                                lastAttachSpecs.getOrPut(t[2].uppercase()) { mutableMapOf() }[entry.id] = spec
                                log("acc ${t[2]} attach ${entry.id}@${spec.bone} ok (pos=${spec.position.contentToString()} rot=${spec.rotationEulerYXZ.contentToString()} scale=${spec.scale})")
                            }
                            .onFailure { log("acc attach failed: ${it.message}") }
                    }
                    "detach" -> {
                        val h = slots[t.getOrNull(2) ?: ""] ?: return log("usage: acc detach <slot> <id>")
                        val id = t.getOrNull(3) ?: return log("usage: acc detach <slot> <id>")
                        log(if (h.detachAccessory(id)) "acc ${t[2]} detach $id ok" else "no accessory $id on ${t[2]}")
                    }
                    "show", "hide" -> {
                        val h = slots[t.getOrNull(2) ?: ""] ?: return log("usage: acc show|hide <slot> <id>")
                        val id = t.getOrNull(3) ?: return log("usage: acc show|hide <slot> <id>")
                        val visible = t[1].equals("show", true)
                        log(if (h.setAccessoryVisible(id, visible)) "acc $id ${if (visible) "shown" else "hidden"}" else "no accessory $id")
                    }
                    "import" -> {
                        // acc import <文件路径...>（绝对路径或 app 外部目录相对；adb push 先行）
                        val raw = t.drop(2).joinToString(" ").trim()
                        if (raw.isEmpty()) return log("usage: acc import <glb路径>（adb push 到 app 外部目录后按名引用）")
                        runCatching {
                            val src = resolveExternalFile(raw)
                                ?: return log("no file at '$raw' (push it to ${context.getExternalFilesDir(null)?.absolutePath})")
                            val name = ImportedAccessoryLibrary.importFile(context, src)
                                ?: return log("import failed: not a GLB or unwritable '$raw'")
                            log("acc import ok: $name (id=${name.removeSuffix(".glb")})")
                        }.onFailure { log("acc import failed: ${it.message}") }
                    }
                    "cal" -> {
                        // acc cal <slot> <id>            把该演员当前挂载规格落成 per-model preset
                        // acc cal clear <slot> <id>      删除 preset
                        // acc cal show <slot> <id>       查看 preset
                        when (t.getOrNull(2)?.lowercase()) {
                            "clear" -> {
                                val slotId = t.getOrNull(3) ?: return log("usage: acc cal clear <slot> <id>")
                                val id = t.getOrNull(4) ?: return log("usage: acc cal clear <slot> <id>")
                                val modelId = slots[slotId]?.spec?.displayName ?: return log("no avatar $slotId")
                                val gone = AccessoryCalibrationStore.clear(context, modelId, id)
                                log("acc cal clear $id@$modelId = $gone")
                            }
                            "show" -> {
                                val slotId = t.getOrNull(3) ?: return log("usage: acc cal show <slot> <id>")
                                val id = t.getOrNull(4) ?: return log("usage: acc cal show <slot> <id>")
                                val modelId = slots[slotId]?.spec?.displayName ?: return log("no avatar $slotId")
                                val p = AccessoryCalibrationStore.load(context, modelId, id)
                                log("acc cal $id@$modelId = ${p ?: "none"}")
                            }
                            else -> {
                                val slotId = t.getOrNull(2) ?: return log("usage: acc cal <slot> <id> [clear|show <slot> <id>]")
                                val id = t.getOrNull(3) ?: return log("usage: acc cal <slot> <id> [clear|show <slot> <id>]")
                                val h = slots[slotId] ?: return log("no avatar $slotId")
                                val spec = lastAttachSpecs[slotId.uppercase()]?.get(id)
                                    ?: return log("no attach record for $id on $slotId (acc attach first, then acc cal)")
                                val modelId = h.spec.displayName
                                AccessoryCalibrationStore.save(
                                    context, modelId, id,
                                    AccessoryCalibrationStore.Preset(
                                        bone = spec.bone,
                                        position = spec.position.toList(),
                                        rotationEulerYXZ = spec.rotationEulerYXZ.toList(),
                                        scale = spec.scale,
                                    ),
                                ).onSuccess {
                                    log("acc cal $id@$modelId saved: bone=${spec.bone} pos=${spec.position.contentToString()} rot=${spec.rotationEulerYXZ.contentToString()} scale=${spec.scale}")
                                }.onFailure { log("acc cal save failed: ${it.message}") }
                            }
                        }
                    }
                    "bind" -> {
                        // acc bind <slot> <id> <动画路径...>（运行时追加白名单；SlotBootstrap 已载入内置表）
                        val h = slots[t.getOrNull(2) ?: ""] ?: return log("usage: acc bind <slot> <id> <动画路径...>")
                        val id = t.getOrNull(3) ?: return log("usage: acc bind <slot> <id> <动画路径...>")
                        val clip = t.drop(4).joinToString(" ").trim()
                        if (clip.isEmpty()) return log("usage: acc bind <slot> <id> <动画路径...>")
                        log(if (h.bindAccessoryToAnimation(id, clip)) "acc bind $id ← $clip ok" else "bind rejected")
                    }
                    "unbind" -> {
                        val h = slots[t.getOrNull(2) ?: ""] ?: return log("usage: acc unbind <slot> <id> <动画路径...>")
                        val id = t.getOrNull(3) ?: return log("usage: acc unbind <slot> <id> <动画路径...>")
                        val clip = t.drop(4).joinToString(" ").trim()
                        log(if (h.unbindAccessoryFromAnimation(id, clip)) "acc unbind $id ← $clip ok" else "no such binding")
                    }
                    "bindings" -> {
                        val slotId = t.getOrNull(2)
                        if (slotId == null) {
                            for ((sid, h) in slots.entries()) {
                                h?.let { log("  $sid: " + it.accessoryAnimationBindings.entries.joinToString(", ") { (c, a) -> "$a←${c.substringAfterLast('/')}" }) }
                            }
                        } else {
                            val h = slots[slotId] ?: return log("no avatar $slotId")
                            log("bindings[$slotId]: " + h.accessoryAnimationBindings.entries.joinToString(", ") { (c, a) -> "$a←$c" })
                        }
                    }
                    else -> log(
                        "usage: acc [list|attach <slot> <id> [bone] [dx dy dz rx ry rz scale]|detach <slot> <id>|" +
                            "show|hide <slot> <id>|import <glb路径>|cal <slot> <id> [clear|show]|" +
                            "bind|unbind <slot> <id> <动画路径...>|bindings [slot]]",
                    )
                }
            }

            // ── VFX 特效（P1，docs/vfx-feasibility.md §六）────────────────
            "fx" -> {
                val sub = t.getOrNull(1)?.lowercase()
                when (sub) {
                    null, "list" -> {
                        val presets = VfxCatalog.all(context)
                        val catalog = presets.values.joinToString(", ") { p ->
                            "${p.id}(${p.blending.name.lowercase()}, max=${p.maxParticles}, " +
                                (if (p.burst > 0) "burst=${p.burst}" else "rate=${p.rate}") +
                                (p.defaultBone?.let { ", bone=$it" } ?: "") + ")"
                        }
                        val active = stage.vfxSnapshot()
                            .joinToString(", ") { "${it.id}:${it.presetId}×${it.activeParticles}" }
                        log("presets(${presets.size}): $catalog\nactive: ${active.ifEmpty { "(none)" }}")
                    }
                    "play" -> {
                        // fx play <preset> [slot] [bone:<骨名>] [x y z]
                        // 锚点优先级：bone: > 显式 x y z > slot 演员脚底 > 预设
                        // defaultBone(带 slot) > 舞台中心 (0,0,-4)
                        val presetId = t.getOrNull(2)
                        val preset = presetId?.let { VfxCatalog.find(context, it) }
                        if (preset == null) {
                            log("unknown preset '${presetId ?: "?"}' (try: fx list)")
                        } else {
                            val rest = t.drop(3)
                            val boneArg = rest.firstOrNull {
                                it.startsWith("bone:", true) || it.startsWith("anchor:", true)
                            }?.substringAfter(':')?.takeIf { it.isNotBlank() }
                            val slot = rest.firstOrNull { it.uppercase() in slots.ids() }
                            val nums = rest.filter { it != slot && !(boneArg != null && (it.equals("bone:$boneArg", true) || it.equals("anchor:$boneArg", true))) }
                                .mapNotNull { it.toFloatOrNull() }
                            val xyz = nums.takeIf { it.size >= 3 }
                            val defaultBone = preset.defaultBone
                            val anchor = when {
                                boneArg != null -> VfxAnchor.Bone(boneArg, slot?.uppercase())
                                xyz != null -> VfxAnchor.World(xyz[0], xyz[1], xyz[2])
                                slot != null -> {
                                    val ws = slots[slot]?.getWalkState()
                                    if (ws != null) VfxAnchor.World(ws.x, 0f, ws.z)
                                    else VfxAnchor.World(0f, 0f, -4f)
                                }
                                defaultBone != null -> VfxAnchor.Bone(defaultBone)
                                else -> VfxAnchor.World(0f, 0f, -4f)
                            }
                            val handle = stage.playVfx(preset, anchor)
                            log(if (handle != null) "fx play ${preset.id} ok as ${handle.id}"
                                else "fx play rejected (renderer not ready or batch full)")
                        }
                    }
                    "stop" -> when (val id = t.getOrNull(2)) {
                        null -> log("usage: fx stop <id|all>")
                        "all", "ALL" -> {
                            val n = stage.vfxSnapshot().size
                            stage.stopAllVfx()
                            log("fx stop all ok ($n active)")
                        }
                        else -> log(if (stage.stopVfx(id)) "fx stop $id ok" else "no fx $id (try: fx state)")
                    }
                    "state" -> {
                        val live = stage.vfxSnapshot()
                        log(if (live.isEmpty()) "fx: (none active)"
                            else live.joinToString("\n") { info ->
                                "fx ${info.id}: ${info.presetId} ${info.blending.name.lowercase()} " +
                                    "particles=${info.activeParticles} age=${"%.1f".format(info.ageSeconds)}s" +
                                    (info.anchor.takeIf { it.isNotEmpty() }?.let { " @$it" } ?: "")
                            })
                    }
                    else -> log("usage: fx [list|play <preset> [slot] [bone:<骨名>] [x y z]|stop <id|all>|state]")
                }
            }

            "load" -> {
                // load <slot> <assets路径|file:/abs> [x z yaw]
                val id = t[1]
                if (slots[id]?.isReady == true) {
                    log("$id already loaded; remove first")
                    return
                }
                val spawn = if (t.size >= 6) AvatarSpawn(
                    x = t[3].toFloat(), z = t[4].toFloat(), yawDeg = t[5].toFloat()
                ) else if (id.uppercase() == "A") AvatarSpawn.LEFT else AvatarSpawn.RIGHT
                val src = t[2]
                val spec = if (src.startsWith("file:")) {
                    AvatarSpec(modelFilePath = src.removePrefix("file:"), spawn = spawn)
                } else {
                    AvatarSpec(modelAssetPath = src, spawn = spawn)
                }
                slots[id] = stage.loadAvatar(spec)
                log("loading $id: ${spec.displayName} spawn=(${spawn.x},${spawn.z},${spawn.yawDeg}°)")
            }

            "remove" -> {
                val h = slots[t[1]]
                if (h != null) {
                    stage.removeAvatar(h)
                    slots[t[1]] = null
                    rig.removeSlot(t[1].uppercase())
                    log("removed ${t[1]}")
                } else log("no avatar ${t[1]}")
            }

            "expr" -> {
                val h = slots[t[1]] ?: return log("no avatar ${t[1]}")
                when (t[2].lowercase()) {
                    "clear" -> { h.clearAllExpressions(); log("${t[1]} expressions cleared") }
                    "list" -> log("${t[1]} expressions=${h.getAvailableExpressions()}")
                    else -> {
                        val w = t.getOrNull(3)?.toFloatOrNull() ?: 1f
                        h.setExpression(t[2], w)
                        log("${t[1]} expr ${t[2]}=$w")
                    }
                }
            }

            "idle" -> {
                val h = slots[t[1]] ?: return log("no avatar ${t[1]}")
                val path = t.drop(2).joinToString(" ")
                val ok = if (path.equals("off", true)) {
                    h.clearVrmaIdleAnimation(); true
                } else h.setVrmaIdleAnimation(path)
                log("idle ${t[1]} $path = $ok")
            }

            "play" -> {
                val h = slots[t[1]] ?: return log("no avatar ${t[1]}")
                // 路径含空格（中文动作库名）：末位 "loop" 标志外，剩余 token 拼回单一路径
                val hasLoop = t.lastOrNull().equals("loop", true)
                val srcPath = (if (hasLoop) t.drop(2).dropLast(1) else t.drop(2))
                    .joinToString(" ")
                if (h.loadVrmaAnimation(srcPath)) {
                    h.playVrmaAnimation(loop = hasLoop)
                    log("${t[1]} play $srcPath loop=$hasLoop dur=${h.getVrmaAnimationDuration()}s")
                } else log("load failed: $srcPath")
            }

            "gaze" -> {
                val h = slots[t[1]] ?: return log("no avatar ${t[1]}")
                when (t[2].lowercase()) {
                    "camera" -> { h.lookAtCamera(); log("${t[1]} gaze → camera") }
                    "off" -> { h.clearLookAtTarget(); log("${t[1]} gaze off") }
                    else -> {
                        val other = slots[t[2]]
                        if (other != null) {
                            h.lookAt(other)
                            log("${t[1]} gaze → ${t[2]}")
                        } else log("no avatar ${t[2]}")
                    }
                }
            }

            "walk" -> {
                val h = slots[t[1]] ?: return log("no avatar ${t[1]}")
                if (t[2].equals("stop", true)) {
                    h.stopWalk(); log("${t[1]} walk stopped")
                } else {
                    val speed = t.getOrNull(4)?.toFloatOrNull() ?: 1f
                    val r = h.startWalk(t[2].toFloat(), t[3].toFloat(), speed)
                    log("${t[1]} walk (${t[2]},${t[3]}) speed=$speed → $r")
                }
            }

            "turn" -> {
                val h = slots[t[1]] ?: return log("no avatar ${t[1]}")
                val ok = h.turnTo(t[2].toFloat(), t[3].toFloat())
                log("${t[1]} turnTo (${t[2]},${t[3]}) → ${if (ok) "started" else "rejected"}")
            }

            "cam" -> when (t[1].lowercase()) {
                "clear" -> { stage.clearCameraShot(); log("shot cleared") }
                "reset" -> { stage.resetCamera(); log("camera reset") }
                else -> {
                    val shot = parseCameraShot(t[1]) ?: return log("unknown shot ${t[1]}")
                    val target = t.getOrNull(2)?.let { slots[it] }
                    stage.setCameraShot(shot, target)
                    log("cam $shot target=${target?.id ?: if (shot == CameraShot.GROUP_SHOT) "全体同框" else "focus"}")
                }
            }

            "scene" -> {
                if (t[1].equals("off", true)) {
                    stage.removeScene(); log("scene removed")
                } else {
                    val meta = runCatching {
                        context.assets.open(sceneMetaAssetName(t[1]))
                            .use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull()?.let { parseSceneMeta(it) }
                    stage.loadScene("scene/${t[1]}", meta)
                    log("scene ${t[1]} loaded (meta=${meta != null})")
                }
            }

            "drag" -> {
                stage.setDragMode(t[1].equals("on", true))
                log("drag ${t[1]}")
            }

            "focus" -> {
                slots[t[1]]?.focus(); log("focus ${t[1]}")
            }

            "move" -> {
                val h = slots[t[1]] ?: return log("no avatar ${t[1]}")
                h.moveAvatar(t[2].toFloat(), t[3].toFloat(), t[4].toFloat())
                val w = h.getWalkState()
                log("${t[1]} moved → (${w?.x}, ${w?.z})")
            }

            "fade" -> {
                val h = slots[t[1]] ?: return log("no avatar ${t[1]}")
                h.setWalkFadeSeconds(t[2].toFloat())
                log("${t[1]} walk fade=${t[2]}")
            }

            "springs" -> {
                val h = slots[t[1]] ?: return log("no avatar ${t[1]}")
                h.setSpringBoneEnabled(t[2].toBooleanStrict())
                log("${t[1]} springs=${t[2]}")
            }

            else -> log("unknown cmd: ${t[0]} (try: state|chat_state|chat|say|interrupt|topic|stop|director|director_log|cfg|memory|dance|shot|load|remove|expr|idle|play|gaze|walk|turn|cam|scene|drag|focus|move|fade|springs|acc)")
        }
    }

    private fun dumpState() {
        log("fps=${stage.fps.value} shot=${stage.getActiveCameraShot()?.name ?: "-"} " +
            "scene=${stage.scenePath.value ?: "off"} avatars=${stage.avatars.size}")
        for (h in stage.avatars) {
            val s = h.state.value
            val walk = h.getWalkState()
            val walkInfo = if (walk != null) {
                "walk=${walk.phase}@(%.2f,%.2f)h=%.0f°".format(walk.x, walk.z, walk.headingDeg)
            } else "walk=-"
            val stateInfo = when (s) {
                is AvatarState.Ready -> "ready(anim=${s.animationCount},expr=${s.expressions.size})"
                is AvatarState.Loading -> "loading"
                is AvatarState.Error -> "error(${s.message})"
                else -> "idle"
            }
            log("avatar ${h.id} [${h.spec.displayName}] $stateInfo $walkInfo " +
                "gaze=${h.getLookAtInfo() != null} breath=${h.getBreathInfo() != null} " +
                "gaits=[${h.walkGaitsSummary()}]")
        }
        log(rig.snapshot())
    }

    private fun captureShot() {
        val ok = stage.captureFrame { bitmap: Bitmap? ->
            if (bitmap == null) {
                log("shot failed (frame unavailable)")
                return@captureFrame
            }
            val out = File(context.getExternalFilesDir(null), "stage_shot.png")
            out.outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            log("shot saved: ${out.absolutePath}")
        }
        if (!ok) log("shot rejected (no view / not rendering)")
    }

    companion object {
        const val TAG = "StageCmd"
    }
}
