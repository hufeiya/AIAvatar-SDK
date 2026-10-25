package com.neethu.companion.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neethu.companion.AiChatPrefs
import com.neethu.companion.CardBindings
import com.neethu.corelib.stage.AvatarHandle
import com.neethu.corelib.stage.AvatarSpec
import com.neethu.corelib.stage.AvatarSpawn
import com.neethu.corelib.stage.AvatarStage
import com.neethu.corelib.stage.AvatarStageView
import com.neethu.corelib.stage.rememberAvatarStage
import com.neethu.orchestrator.card.CharacterCardStore
import com.neethu.orchestrator.facade.AIAvatarSdk
import com.neethu.orchestrator.memory.MemoryStore
import com.neethu.orchestrator.roundtable.DirectorPolicy
import com.neethu.orchestrator.roundtable.RoundtableSession
import com.neethu.orchestrator.roundtable.RoundtableState
import com.neethu.orchestrator.roundtable.StageEvent
import com.neethu.orchestrator.roundtable.StageGazeRelay
import com.neethu.orchestrator.roundtable.StageSlotSpec
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 多卡群聊（酒馆 P3-2 / G12）：从卡库选 2~4 张卡 → 每卡一个 3D 化身（舞台
 * 多实例）+ 一个独立 AvatarSession（人设=卡、音色=卡绑定或轮换默认、独立
 * 上下文）→ 圆桌导演制（规则档,零 LLM 成本）+ @点名路由。单聊模式=点名谁
 * 谁回（IDLE 路由）;开题=导演逐轮仲裁发言权。
 *
 * v1 边界：成员站定站位不走路（StageFloorRelay 不接）、关系追踪不进群聊、
 * LLM 导演档暂不开放（规则档够稳）。
 */

/** 群聊一条消息（分道字幕）。 */
internal data class GroupLine(
    val slotId: String?,
    val name: String,
    val text: String,
    val isUser: Boolean = false,
    val isSystem: Boolean = false,
)

/** 一个成员的装配（卡 + 会话 + 舞台手柄）。 */
internal class GroupMember(
    val slotId: String,
    val displayName: String,
    val cardFile: String,
    val avatarFile: String?,
    val session: AvatarSession,
    val handle: AvatarHandle,
)

@Composable
internal fun GroupChatScreen(
    cards: List<CharacterCardStore.Entry>,
    bytesFor: (String) -> ByteArray?,
    aiPrefs: AiChatPrefs,
    bindings: CardBindings,
    memoryStore: MemoryStore?,
    lang: com.neethu.corelib.Lang,
    defaultModels: List<String>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stage = rememberAvatarStage()

    // ── 选人态 ────────────────────────────────────────────────────────────
    var selecting by remember { mutableStateOf(true) }
    val selectedFiles = remember { mutableStateListOf<String>() }
    var groupError by remember { mutableStateOf<String?>(null) }

    // ── 会话态 ────────────────────────────────────────────────────────────
    val members = remember { mutableStateListOf<GroupMember>() }
    var roundtable by remember { mutableStateOf<RoundtableSession?>(null) }
    var rtState by remember { mutableStateOf(RoundtableState.IDLE) }
    val lines = remember { mutableStateListOf<GroupLine>() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var topicInput by remember { mutableStateOf("") }

    fun pushLine(line: GroupLine) {
        lines.add(line)
        if (lines.size > 120) lines.removeRange(0, lines.size - 120)
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem((lines.size - 1).coerceAtLeast(0))
    }

    // ── 装配成员（选人 → 开聊）────────────────────────────────────────────
    fun startGroup() {
        if (selectedFiles.size < 2) {
            groupError = "至少选 2 张卡才能群聊"
            return
        }
        if (!aiPrefs.isConfigured) {
            groupError = "先在设置里配好 AI 服务"
            return
        }
        groupError = null
        val ts = System.currentTimeMillis()
        val newMembers = mutableListOf<GroupMember>()
        selectedFiles.forEachIndexed { index, file ->
            val entry = cards.firstOrNull { it.fileName == file } ?: return@forEachIndexed
            val binding = bindings.forCard(file)
            // 3D 化身:先建舞台手柄(会话的 controller 就是它),并排站位
            val model = binding?.modelFile ?: defaultModels.getOrNull(index % defaultModels.size) ?: return@forEachIndexed
            val spec = if (model.startsWith("vrms/") || !model.contains('/')) {
                AvatarSpec(modelAssetPath = "vrms/${model.substringAfterLast('/')}", spawn = spawnFor(index, selectedFiles.size))
            } else {
                AvatarSpec(modelFilePath = model, spawn = spawnFor(index, selectedFiles.size))
            }
            val handle = stage.loadAvatar(spec)
            val contextId = "group-$ts-$index"
            val config = aiPrefs.toChatConfig(contextId, file, lang).copy(
                // 成员音色:卡绑定优先,否则 Edge 目录轮换(听感区分成员)
                voice = binding?.voice?.takeIf { it.isNotBlank() } ?: defaultEdgeVoice(index),
                ttsMaxConcurrent = RoundtableSession.perSlotTtsBudget(4, selectedFiles.size),
            )
            val sdk = AIAvatarSdk(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                controller = handle,
                appContext = null, // 群聊历史走内存(storeFactory 注入),不占 Room 上下文
                memoryStore = memoryStore,
                storeFactory = { _, _ -> com.neethu.orchestrator.history.InMemoryConversationStore() },
            )
            val session = sdk.configure(config)
            if (session == null) {
                stage.removeAvatar(handle)
                return@forEachIndexed
            }
            session.setCharacterCard(entry.card)
            newMembers += GroupMember(
                slotId = "slot$index",
                displayName = entry.card.name.ifEmpty { "成员${index + 1}" },
                cardFile = file,
                avatarFile = model,
                session = session,
                handle = handle,
            )
        }
        if (newMembers.size < 2) {
            groupError = "成员装配不足(模型/配置失败?)"
            newMembers.forEach { it.session.close() }
            return
        }
        members.clear()
        members.addAll(newMembers)
        // 圆桌:规则导演 + 注视接力(倾听者看发言者,发言者看镜头)
        val gaze = object : StageGazeRelay {
            override fun headPosition(slotId: String): FloatArray? =
                members.firstOrNull { it.slotId == slotId }?.handle?.headWorldPosition()
            override fun lookAtPoint(slotId: String, x: Float, y: Float, z: Float) {
                members.firstOrNull { it.slotId == slotId }?.handle?.setLookAtTarget(x, y, z)
            }
            override fun lookAtCamera(slotId: String) {
                members.firstOrNull { it.slotId == slotId }?.handle?.lookAtCamera()
            }
        }
        val rt = RoundtableSession(
            scope = scope,
            slots = members.map { StageSlotSpec(it.slotId, it.displayName) to it.session },
            gaze = gaze,
        )
        roundtable = rt
        scope.launch {
            rt.state.collect { rtState = it }
        }
        scope.launch {
            rt.events.collect { ev -> onStageEvent(ev, members, ::pushLine) }
        }
        // 集体取景:等全员就绪(15s 超时兜底)后 GROUP_SHOT 全员入画
        scope.launch {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val handles = members.map { it.handle }
                if (handles.isNotEmpty() && handles.all { it.isReady }) {
                    stage.setGroupShotFitted(handles)
                    return@launch
                }
                kotlinx.coroutines.delay(500)
            }
        }
        pushLine(GroupLine(null, "系统", "群聊就绪:${members.joinToString("、") { it.displayName }}。输入话题开题,或直接 @成员点名。", isSystem = true))
        selecting = false
    }

    fun endGroup() {
        roundtable?.stopRoundtable()
        members.forEach { it.session.close() }
        members.clear()
        roundtable = null
        lines.clear()
        selecting = true
    }

    DisposableEffect(Unit) {
        onDispose { endGroup() }
    }

    fun sendCurrent() {
        val text = input.trim()
        if (text.isEmpty()) return
        val rt = roundtable ?: return
        input = ""
        if (rtState == RoundtableState.IDLE) {
            val routed = rt.sendFromUser(text)
            pushLine(GroupLine(routed, "你", text, isUser = true))
            if (routed == null) pushLine(GroupLine(null, "系统", "没听出在跟谁说话,试试 @名字", isSystem = true))
        } else {
            rt.sendFromUser(text)
            pushLine(GroupLine(null, "你", text, isUser = true))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 顶栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (selecting) onDismiss() else endGroup() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (selecting) "多卡群聊 · 选人" else "群聊进行中",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (!selecting && rtState != RoundtableState.IDLE) {
                OutlinedButton(onClick = { roundtable?.stopRoundtable() }) { Text("停题", fontSize = 12.sp) }
            }
        }

        if (selecting) {
            Text("从卡库选 2~4 张卡(再次点击取消):", fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(cards, key = { it.fileName }) { entry ->
                    val selected = entry.fileName in selectedFiles
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Black.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (selected) selectedFiles.remove(entry.fileName)
                            else if (selectedFiles.size < 4) selectedFiles.add(entry.fileName)
                            else groupError = "最多 4 人"
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(10.dp)) {
                            rememberCardAvatar(entry.fileName, bytesFor)?.let { bmp ->
                                Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                                Spacer(Modifier.width(10.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(entry.card.name.ifEmpty { "未命名" }, fontSize = 14.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.card.spec, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
                            }
                            if (selected) Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            groupError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            Button(onClick = { startGroup() }, modifier = Modifier.fillMaxWidth()) {
                Text("开始群聊(${selectedFiles.size} 人)")
            }
        } else {
            // 群聊字幕
            Surface(shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    items(lines) { line ->
                        Text(
                            text = if (line.isUser) "你：${line.text}"
                            else if (line.isSystem) line.text
                            else "【${line.name}】${line.text}",
                            color = when {
                                line.isUser -> Color(0xFF9ED8FF)
                                line.isSystem -> Color(0xFF9E9E9E)
                                else -> Color.White
                            },
                            fontSize = if (line.isSystem) 11.sp else 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
            // 开题条(IDLE 态才有)
            if (rtState == RoundtableState.IDLE) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = topicInput,
                        onValueChange = { topicInput = it },
                        placeholder = { Text("抛个话题让大家聊…", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        if (roundtable?.startRoundtable(topicInput) == true) {
                            pushLine(GroupLine(null, "你", "开题:$topicInput", isUser = true))
                            topicInput = ""
                        }
                    }) { Text("开题") }
                }
                // @点名快捷条
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    members.forEach { m ->
                        AssistChip(
                            onClick = { input = "@${m.displayName} " },
                            label = { Text("@${m.displayName}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
            groupError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            // 输入条
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(if (rtState == RoundtableState.IDLE) "说话或 @成员" else "插话…", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { sendCurrent() }) { Text("发送") }
                IconButton(onClick = { roundtable?.interrupt() }) {
                    Icon(Icons.Default.Close, contentDescription = "打断")
                }
            }
        }
    }
}

private fun spawnFor(index: Int, total: Int): AvatarSpawn {
    // 并排站位:以中线对称铺开,微微朝向中间
    val spacing = 0.9f
    val offset = (index - (total - 1) / 2f) * spacing
    return AvatarSpawn(x = offset, yawDeg = -offset * 12f)
}

/** 成员默认音色:Edge 免费目录轮换(无绑定时听感区分)。 */
private fun defaultEdgeVoice(index: Int): String {
    val voices = com.neethu.orchestrator.facade.EdgeTtsCatalog.voices
    return voices.getOrNull(index)?.first ?: com.neethu.orchestrator.facade.EdgeTtsCatalog.DEFAULT_VOICE
}

/** StageEvent → 字幕行(分道)。 */
private fun onStageEvent(
    ev: StageEvent,
    members: List<GroupMember>,
    push: (GroupLine) -> Unit,
) {
    val name = members.firstOrNull { it.slotId == ev.slotId }?.displayName ?: ev.slotId
    when (val e = ev.event) {
        is com.neethu.orchestrator.session.AvatarEvent.SentenceStarted ->
            push(GroupLine(ev.slotId, name, e.text))
        is com.neethu.orchestrator.session.AvatarEvent.TurnFailed ->
            push(GroupLine(ev.slotId, name, "（没接上话:${e.error.message ?: "未知错误"}）", isSystem = true))
        else -> Unit
    }
}
