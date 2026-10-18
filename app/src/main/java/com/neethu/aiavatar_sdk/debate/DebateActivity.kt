package com.neethu.aiavatar_sdk.debate

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.neethu.corelib.stage.AvatarStage
import com.neethu.corelib.stage.AvatarStageView
import com.neethu.corelib.stage.rememberAvatarStage
import com.neethu.orchestrator.debate.DebateTeam
import com.neethu.aiavatar_sdk.loadAiPrefs
import com.neethu.orchestrator.debate.MatchState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 4v4 虚拟人辩论赛页面（docs/debate-feasibility.md）：固定横屏、舞台为主体、
 * 顶部赛况、右侧辩位牌、底部字幕与折叠控制。Intent 控制协议见
 * [DebateControlRouter]（onCreate/onNewIntent 同一入口）。
 */
class DebateActivity : ComponentActivity() {

    private var rigRef: DebateRig? = null
    private lateinit var router: DebateControlRouter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        router = DebateControlRouter(this) { rigRef }
        router.start()
        setContent { DebateScreen(onRigReady = { rigRef = it }) }
        router.submit(intent)
    }

    /** UI 控制按钮与 Intent 协议同一条命令通道。 */
    fun submitControl(intent: Intent) {
        router.submit(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        router.submit(intent)
    }

    override fun onStop() {
        // 退后台自动暂停（doc §5）：引擎对非 RUNNING 是安全 no-op/拒绝
        rigRef?.engine?.let { engine ->
            if (engine.state.value.matchState == MatchState.RUNNING) {
                engine.pause("background")
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        router.dispose()
        runCatching { rigRef?.close() }
        rigRef = null
        super.onDestroy()
    }
}

@Composable
private fun DebateScreen(onRigReady: (DebateRig) -> Unit) {
    val stage = rememberAvatarStage()
    var rig by remember { mutableStateOf<DebateRig?>(null) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

    Box(Modifier.fillMaxSize().background(Color(0xFF0D1117)).systemBarsPadding()) {
        AvatarStageView(
            modifier = Modifier.fillMaxSize().padding(top = 30.dp, bottom = 104.dp, end = 156.dp),
            stage = stage,
        )

        LaunchedEffect(stage) {
            if (rig == null) {
                stage.attached.first { it }
                val prefsStore = appContext.getSharedPreferences("demo_settings", android.content.Context.MODE_PRIVATE)
                val newRig = DebateRig(
                    appContext = appContext,
                    stage = stage,
                    globalPrefs = { prefsStore.loadAiPrefs() },
                )
                newRig.activate()
                rig = newRig
                onRigReady(newRig)
            }
        }

        rig?.let { r -> DebateOverlay(r, stage) }
    }
}

@Composable
private fun DebateOverlay(rig: DebateRig, stage: AvatarStage) {
    val state by rig.engine.state.collectAsState()
    val subtitle by rig.subtitle.collectAsState()
    val loadStatus by rig.loadStatus.collectAsState()
    val matchId by rig.matchId.collectAsState()
    val activity = androidx.compose.ui.platform.LocalContext.current as DebateActivity

    Column(Modifier.fillMaxSize()) {
        // ── 顶部赛况条 ──────────────────────────────────────────────
        Surface(color = Color(0xCC101820), modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "辩论赛 ${matchId ?: "未加载"}",
                    color = Color(0xFFE6EDF3), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("[${state.matchState}]", color = Color(0xFF7EE787), fontSize = 12.sp)
                state.phase?.let { phase ->
                    Text(phaseLabel(phase), color = Color(0xFF79C0FF), fontSize = 12.sp)
                }
                state.teamBalancesMs?.let { balances ->
                    Text(
                        "正 ${formatMs(balances[DebateTeam.PRO] ?: 0)}",
                        color = Color(0xFFFF9E9E), fontSize = 12.sp,
                    )
                    Text(
                        "反 ${formatMs(balances[DebateTeam.CON] ?: 0)}",
                        color = Color(0xFF9ECBFF), fontSize = 12.sp,
                    )
                }
                state.wallRemainingMs?.let {
                    Text("轮 ${formatMs(it)}", color = Color(0xFFF2CC60), fontSize = 12.sp)
                }
            }
        }

        Row(Modifier.weight(1f)) {
            // ── 左侧：当前回合信息 + 字幕 ────────────────────────────
            Column(
                Modifier.weight(1f).padding(10.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.Bottom,
            ) {
                state.currentTurn?.let { turn ->
                    Surface(color = Color(0xAA161B22), modifier = Modifier.padding(bottom = 6.dp)) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(
                                "▶ ${rig.parts[turn.speakerId]?.dto?.name ?: turn.speakerId}（${kindLabel(turn.kind)}）",
                                color = Color(0xFFFFD700), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "${stageLabel(turn.stage)} 剩余 ${formatMs(turn.remainingMs)}" +
                                    if (state.postureWaiting.isNotEmpty()) " · 起立中:${state.postureWaiting.joinToString()}" else "",
                                color = Color(0xFFB0BEC5), fontSize = 11.sp,
                            )
                        }
                    }
                }
                subtitle?.let { (speaker, text) ->
                    Surface(color = Color(0xCC0D1117), modifier = Modifier.fillMaxWidth(0.75f)) {
                        Text(
                            "${rig.parts[speaker]?.dto?.name ?: speaker}：$text",
                            color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                state.lastError?.let {
                    Text(it, color = Color(0xFFFF7B72), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            // ── 右侧：九人辩位牌 ─────────────────────────────────────
            Surface(
                color = Color(0xB3101820),
                modifier = Modifier.width(148.dp).fillMaxHeight().padding(4.dp),
            ) {
                LazyColumn(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items(loadStatus, key = { it.id }) { status ->
                        val speaking = state.currentTurn?.speakerId == status.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        speaking -> Color(0xFF2EA043)
                                        status.failed -> Color(0xFFB62324)
                                        status.ready -> Color(0xFF21262D)
                                        else -> Color(0xFF30363D)
                                    }
                                )
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "${status.name}${if (speaking) " ▶" else ""}",
                                color = Color(0xFFE6EDF3), fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // ── 底部控制栏 ──────────────────────────────────────────────
        Surface(color = Color(0xCC101820), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ControlButton("载入短赛程") {
                        submitCommand(activity, buildIntent("load") { putExtra("config_file", "debate_quick.json") })
                    }
                    ControlButton("载入全时赛程") {
                        submitCommand(activity, buildIntent("load") { putExtra("config_file", "debate_default.json") })
                    }
                    if (state.matchState == MatchState.READY) {
                        ControlButton("开赛") { submitCommand(activity, buildIntent("start")) }
                    }
                    if (state.matchState == MatchState.RUNNING) {
                        ControlButton("暂停") { submitCommand(activity, buildIntent("pause")) }
                        ControlButton("结束发言") { submitCommand(activity, buildIntent("end_turn")) }
                        ControlButton("跳段") { submitCommand(activity, buildIntent("next_phase")) }
                    }
                    if (state.matchState == MatchState.PAUSED) {
                        ControlButton("恢复") { submitCommand(activity, buildIntent("resume")) }
                    }
                    if (state.matchState == MatchState.FINISHED || state.matchState == MatchState.STOPPED) {
                        ControlButton("重置") { submitCommand(activity, buildIntent("reset")) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ControlButton("正方镜头") { submitCommand(activity, cameraIntent("team", team = "PRO")) }
                    ControlButton("反方镜头") { submitCommand(activity, cameraIntent("team", team = "CON")) }
                    ControlButton("主持人") { submitCommand(activity, cameraIntent("host")) }
                    ControlButton("九人全景") { submitCommand(activity, cameraIntent("wide")) }
                    ControlButton("全景5秒") { submitCommand(activity, cameraIntent("wide", holdMs = 5000L)) }
                    ControlButton("恢复自动") { submitCommand(activity, buildIntent("camera_auto")) }
                    ControlButton("状态") { submitCommand(activity, buildIntent("state")) }
                }
            }
        }
    }
}

private fun submitCommand(activity: DebateActivity, intent: Intent) {
    activity.submitControl(intent)
}

private fun buildIntent(cmd: String, extra: Intent.() -> Unit = {}): Intent =
    Intent(DebateControlRouter.ACTION_DEBATE_CONTROL)
        .putExtra(DebateControlRouter.EXTRA_CMD, cmd)
        .putExtra(DebateControlRouter.EXTRA_REQUEST_ID, "ui-$cmd-${System.currentTimeMillis()}")
        .apply(extra)

private fun cameraIntent(shot: String, team: String? = null, holdMs: Long? = null): Intent = buildIntent("camera") {
    putExtra("shot", shot)
    team?.let { putExtra("team", it) }
    holdMs?.let { putExtra("hold_ms", it) }
}


@Composable
private fun ControlButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
        Text(label, fontSize = 11.sp)
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms.coerceAtLeast(0)) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

private fun phaseLabel(phase: com.neethu.orchestrator.debate.DebatePhase): String = when (phase) {
    com.neethu.orchestrator.debate.DebatePhase.HOST_OPENING -> "主持开场"
    com.neethu.orchestrator.debate.DebatePhase.OPENING_STATEMENT -> "开篇立论"
    com.neethu.orchestrator.debate.DebatePhase.CROSS_EXAM -> "攻辩"
    com.neethu.orchestrator.debate.DebatePhase.CROSS_SUMMARY -> "攻辩小结"
    com.neethu.orchestrator.debate.DebatePhase.FREE_DEBATE -> "自由辩论"
    com.neethu.orchestrator.debate.DebatePhase.CLOSING -> "总结陈词"
    com.neethu.orchestrator.debate.DebatePhase.HOST_CLOSING -> "主持结束"
}

private fun kindLabel(kind: com.neethu.orchestrator.debate.TurnKind): String = when (kind) {
    com.neethu.orchestrator.debate.TurnKind.HOST -> "主持"
    com.neethu.orchestrator.debate.TurnKind.STATEMENT -> "陈词"
    com.neethu.orchestrator.debate.TurnKind.CROSS_QUESTION -> "提问"
    com.neethu.orchestrator.debate.TurnKind.CROSS_ANSWER -> "回答"
    com.neethu.orchestrator.debate.TurnKind.FREE -> "自由发言"
}

private fun stageLabel(stage: com.neethu.orchestrator.debate.TurnStage): String = when (stage) {
    com.neethu.orchestrator.debate.TurnStage.AWAITING_POSTURE -> "起立中"
    com.neethu.orchestrator.debate.TurnStage.PREPARING_AUDIO -> "准备中"
    com.neethu.orchestrator.debate.TurnStage.SPEAKING -> "发言中"
    com.neethu.orchestrator.debate.TurnStage.DRAINING -> "收尾中"
}
