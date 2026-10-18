package com.neethu.aiavatar_sdk.debate

import android.content.Intent
import android.util.Log
import com.neethu.orchestrator.debate.DebateConfigDto
import com.neethu.orchestrator.debate.DebateConfigException
import com.neethu.orchestrator.debate.DebateConfigParser
import com.neethu.orchestrator.debate.DebateTeam
import com.neethu.orchestrator.debate.DebateIds
import com.neethu.orchestrator.debate.MatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 辩论 Intent 控制协议（docs/debate-feasibility.md §10）：命令主线程串行入队，
 * request_id 去重，关键变更整体应用；每条命令落一个
 * `files/debate/control/<request_id>.json` 结构化结果。
 *
 * `am start` 送达 ≠ 执行完成：load 是异步装配（九模型加载），期间冲突命令
 * 按「rejected」回；结果文件在命令真正收尾时写出。
 */
class DebateControlRouter(
    private val context: androidx.activity.ComponentActivity,
    private val rigProvider: () -> DebateRig?,
) {
    private val TAG = "DebateControl"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<Command>(Channel.UNLIMITED)

    /** request_id 去重（LRU）。 */
    private val handledIds = LinkedHashSet<String>()

    @Volatile
    private var loading = false

    private class Command(
        val requestId: String,
        val name: String,
        val run: suspend () -> Outcome,
    )

    private data class Outcome(
        val status: String, // completed / rejected
        val detail: String,
        val extra: JSONObject? = null,
    )

    /** Activity 调：onCreate(intent) 与 onNewIntent(intent) 都进这里。 */
    fun submit(intent: Intent?) {
        val extras = intent ?: return
        val cmd = extras.getStringExtra(EXTRA_CMD)?.trim().orEmpty()
        if (cmd.isEmpty()) return
        val requestId = DebateFiles.sanitizeRequestId(extras.getStringExtra(EXTRA_REQUEST_ID))
        val params = mapOf(
            "config_file" to extras.getStringExtra(EXTRA_CONFIG_FILE),
            "speaker_id" to extras.getStringExtra(EXTRA_SPEAKER_ID),
            "shot" to extras.getStringExtra(EXTRA_SHOT),
            "target_id" to extras.getStringExtra(EXTRA_TARGET_ID),
            "team" to extras.getStringExtra(EXTRA_TEAM),
            "other_id" to extras.getStringExtra(EXTRA_OTHER_ID),
            "hold_ms" to (extras.getStringExtra(EXTRA_HOLD_MS)
                ?: extras.getLongExtra(EXTRA_HOLD_MS, -1L).takeIf { it >= 0 }?.toString()),
        )
        scope.launch { queue.send(buildCommand(requestId, cmd, params)) }
    }

    /** 启动消费循环（Activity onCreate 一次）。 */
    fun start() {
        scope.launch {
            for (command in queue) {
                execute(command)
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    private suspend fun execute(command: Command) {
        if (command.requestId in handledIds) {
            Log.i(TAG, "重复 request_id 忽略: ${command.requestId} (${command.name})")
            writeResult(command.requestId, command.name, "duplicate", "重复 request_id，未再次执行")
            return
        }
        handledIds += command.requestId
        while (handledIds.size > 256) {
            handledIds.remove(handledIds.first())
        }
        val outcome = runCatching { command.run() }
            .getOrElse { Outcome("rejected", "异常: ${it.javaClass.simpleName}: ${it.message}") }
        Log.i(TAG, "[${command.requestId}] ${command.name} → ${outcome.status}: ${outcome.detail}")
        writeResult(command.requestId, command.name, outcome.status, outcome.detail, outcome.extra)
        writeStateSnapshot()
    }

    // ── 命令构建 ────────────────────────────────────────────────────────

    private fun buildCommand(requestId: String, cmd: String, params: Map<String, String?>): Command {
        fun text(key: String): String = params[key]?.trim().orEmpty()
        val run: suspend () -> Outcome = when (cmd) {
            "load" -> {{ loadCommand(text("config_file")) }}
            "validate" -> {{ validateCommand(text("config_file")) }}
            "start" -> {{
                withEngine("start") { engine ->
                    engine.start()
                    Outcome("completed", "开赛，状态=${engine.state.value.matchState}")
                }
            }}
            "pause" -> {{
                withEngine("pause") { engine ->
                    engine.pause("intent")
                    Outcome("completed", "已暂停")
                }
            }}
            "resume" -> {{
                withEngine("resume") { engine ->
                    engine.resume()
                    Outcome("completed", "已恢复")
                }
            }}
            "stop" -> {{
                withEngine("stop") { engine ->
                    engine.stop("intent")
                    Outcome("completed", "已终止")
                }
            }}
            "reset" -> {{
                if (loading) Outcome("rejected", "装配中，暂不接受 reset")
                else withEngine("reset") { engine ->
                    engine.reset()
                    Outcome("completed", "已回到开赛前（READY）")
                }
            }}
            "end_turn" -> {{
                withEngine("end_turn") { engine ->
                    engine.endTurn()
                    Outcome("completed", "已请求结束当前发言")
                }
            }}
            "next_phase" -> {{
                withEngine("next_phase") { engine ->
                    engine.nextPhase()
                    Outcome("completed", "已人工跳到下一阶段（日志注明）")
                }
            }}
            "grant" -> {{
                val speaker = text("speaker_id")
                withEngine("grant") { engine ->
                    engine.grant(speaker)
                    Outcome("completed", "grant($speaker) 已提交，合法集合=${engine.state.value.legalSpeakers}")
                }
            }}
            "camera" -> {{ cameraCommand(params) }}
            "camera_auto" -> {{ cameraAutoCommand() }}
            "state" -> {{ Outcome("completed", "状态快照", extra = stateSnapshotJson()) }}
            "export" -> {{ exportCommand(requestId) }}
            else -> {{ Outcome("rejected", "未知命令 $cmd") }}
        }
        return Command(requestId, cmd, run)
    }

    private inline fun withEngine(
        name: String,
        block: (com.neethu.orchestrator.debate.DebateEngine) -> Outcome,
    ): Outcome {
        val rig = rigProvider() ?: return Outcome("rejected", "$name: 舞台尚未就绪（页面未装配）")
        return block(rig.engine)
    }

    // ── load / validate / camera / export ──────────────────────────────

    private suspend fun loadCommand(file: String): Outcome {
        if (file.isEmpty()) return Outcome("rejected", "load 缺 config_file")
        if (loading) return Outcome("rejected", "上一次装配仍在进行，拒绝并发 load")
        val rig = withTimeoutOrNull(10_000L) {
            while (rigProvider() == null) delay(50L)
            rigProvider()
        } ?: return Outcome("rejected", "load: 等待舞台就绪超时")
        if (rig.engine.state.value.matchState == MatchState.RUNNING) {
            return Outcome("rejected", "比赛中禁止重载，请先暂停或停止")
        }
        val text = DebateFiles.readConfigText(context, file)
            ?: return Outcome("rejected", "配置文件不存在或不可读: $file")
        val dto = try {
            DebateConfigParser.parseAndValidate(text).getOrThrow()
        } catch (e: DebateConfigException) {
            return Outcome("rejected", "配置校验失败", extra = issuesJson(e.issues.map { it.toString() }))
        } catch (e: Exception) {
            return Outcome("rejected", "配置解析失败: ${e.message}")
        }
        val layoutDto = loadLayout(dto) ?: return Outcome("rejected", "布局文件缺失: ${dto.scene.layout}")
        loading = true
        try {
            val ok = rig.rebuild(dto, layoutDto)
            return if (ok) {
                rig.activate()
                Outcome("completed", "装配完成，READY（${dto.matchId}）")
            } else {
                Outcome("rejected", "模型加载失败（见 DebateEngine 日志）")
            }
        } finally {
            loading = false
        }
    }

    private fun validateCommand(file: String): Outcome {
        if (file.isEmpty()) return Outcome("rejected", "validate 缺 config_file")
        val text = DebateFiles.readConfigText(context, file)
            ?: return Outcome("rejected", "配置文件不存在或不可读: $file")
        return try {
            DebateConfigParser.parseAndValidate(text).getOrThrow()
            Outcome("completed", "校验通过: $file")
        } catch (e: DebateConfigException) {
            Outcome("rejected", "配置校验失败", extra = issuesJson(e.issues.map { it.toString() }))
        } catch (e: Exception) {
            Outcome("rejected", "配置解析失败: ${e.message}")
        }
    }

    private fun loadLayout(dto: DebateConfigDto): DebateLayoutDto? {
        val rawName = dto.scene.layout.ifBlank { "layout_home_debate_v1" }
        // 布局引用允许不带扩展名（约定 .json）
        val layoutName = if (rawName.endsWith(".json")) rawName else "$rawName.json"
        if (layoutName.isBlank()) return DebateLayoutDto(layoutId = "default")
        val text = DebateFiles.readConfigText(context, layoutName) ?: return null
        return runCatching { DebateFiles.parseLayout(text) }
            .onFailure { Log.e(TAG, "布局解析失败: $layoutName (${it.message})") }
            .getOrNull()
    }

    private suspend fun cameraCommand(extras: Map<String, String?>): Outcome {
        val rig = rigProvider() ?: return Outcome("rejected", "camera: 舞台尚未就绪")
        val shot = extras["shot"]?.trim()?.lowercase().orEmpty()
        val holdMs = extras["hold_ms"]?.toLongOrNull()
        val spec = when (shot) {
            "speaker" -> {
                val target = extras["target_id"]?.trim().orEmpty()
                if (target.isEmpty()) return Outcome("rejected", "camera speaker 缺 target_id")
                DebateCameraDirector.ShotSpec(DebateCameraDirector.ShotSpec.Kind.SPEAKER, targetId = target)
            }
            "team" -> {
                val team = when (extras["team"]?.trim()?.uppercase()) {
                    "PRO" -> DebateTeam.PRO
                    "CON" -> DebateTeam.CON
                    else -> return Outcome("rejected", "camera team 缺 team=PRO/CON")
                }
                DebateCameraDirector.ShotSpec(
                    DebateCameraDirector.ShotSpec.Kind.TEAM,
                    targets = DebateIds.DEBATERS.filter { DebateIds.teamOf(it) == team },
                )
            }
            "host" -> DebateCameraDirector.ShotSpec(DebateCameraDirector.ShotSpec.Kind.HOST)
            "wide" -> DebateCameraDirector.ShotSpec(
                DebateCameraDirector.ShotSpec.Kind.WIDE,
                targets = DebateIds.ALL,
            )
            "duel" -> {
                val q = extras["target_id"]?.trim().orEmpty()
                val a = extras["other_id"]?.trim().orEmpty()
                if (q.isEmpty() || a.isEmpty()) return Outcome("rejected", "camera duel 缺 target_id/other_id")
                DebateCameraDirector.ShotSpec(DebateCameraDirector.ShotSpec.Kind.DUEL, targets = listOf(q, a))
            }
            else -> return Outcome("rejected", "未知 shot=$shot（speaker/team/host/wide/duel）")
        }
        rig.camera.lock(spec, holdMs)
        return Outcome("completed", "镜头 $shot${holdMs?.let { "（hold ${it}ms）" } ?: "（锁定）"}")
    }

    private suspend fun cameraAutoCommand(): Outcome {
        val rig = rigProvider() ?: return Outcome("rejected", "camera_auto: 舞台尚未就绪")
        rig.camera.auto()
        return Outcome("completed", "已恢复自动镜头")
    }

    private suspend fun exportCommand(requestId: String): Outcome {
        val rig = rigProvider() ?: return Outcome("rejected", "export: 舞台尚未就绪")
        val dto = rig.config ?: return Outcome("rejected", "尚无已加载配置")
        val json = JSONObject()
        json.put("requestId", requestId)
        json.put("exportedAt", System.currentTimeMillis())
        json.put("config", JSONObject(DebateConfigParser.encode(dto)))
        val entries = JSONArray()
        for (t in rig.engine.transcriptEntries) {
            entries.put(
                JSONObject()
                    .put("turnId", t.turnId)
                    .put("phase", t.phase.name)
                    .put("speaker", t.speakerId)
                    .put("team", t.team?.name)
                    .put("kind", t.kind.name)
                    .put("status", t.status.name)
                    .put("endReason", t.endReason?.name)
                    .put("consumedMs", t.consumedMs)
                    .put("text", t.text)
            )
        }
        json.put("transcript", entries)
        val dir = DebateFiles.exportDir(context)
        val file = File(dir, "export-${System.currentTimeMillis()}.json")
        file.writeText(json.toString(2))
        return Outcome("completed", "导出 ${entries.length()} 条记录", extra = JSONObject().put("file", file.absolutePath))
    }

    // ── 状态快照（state 命令 + 每命令执行后落 state.json）───────────────

    fun stateSnapshotJson(): JSONObject {
        val rig = rigProvider()
        val json = JSONObject()
        json.put("timestamp", System.currentTimeMillis())
        if (rig == null) {
            json.put("matchState", "NO_RIG")
            return json
        }
        val s = rig.engine.state.value
        json.put("matchState", s.matchState.name)
        json.put("epoch", s.epoch)
        json.put("matchId", s.matchId)
        json.put("phase", s.phase?.name)
        json.put("segment", "${s.segmentIndex + 1}/${s.segmentCount}")
        s.currentTurn?.let { turn ->
            json.put(
                "currentTurn",
                JSONObject()
                    .put("turnId", turn.turnId)
                    .put("speaker", turn.speakerId)
                    .put("kind", turn.kind.name)
                    .put("stage", turn.stage.name)
                    .put("remainingMs", turn.remainingMs)
                    .put("frozen", turn.frozen),
            )
        }
        json.put("legalSpeakers", JSONArray(s.legalSpeakers))
        json.put("postureWaiting", JSONArray(s.postureWaiting))
        s.teamBalancesMs?.let { balances ->
            json.put("teamBalancesMs", JSONObject().put("PRO", balances[DebateTeam.PRO] ?: 0).put("CON", balances[DebateTeam.CON] ?: 0))
        }
        json.put("wallRemainingMs", s.wallRemainingMs ?: JSONObject.NULL)
        json.put("lastError", s.lastError ?: JSONObject.NULL)
        val loads = JSONArray()
        for (status in rig.loadStatus.value) {
            loads.put(
                JSONObject()
                    .put("id", status.id)
                    .put("name", status.name)
                    .put("ready", status.ready)
                    .put("failed", status.failed)
            )
        }
        json.put("participants", loads)
        return json
    }

    fun writeStateSnapshot() {
        runCatching {
            File(DebateFiles.root(context), "state.json")
                .writeText(stateSnapshotJson().toString(2))
        }.onFailure { Log.w(TAG, "state.json 写入失败: ${it.message}") }
    }

    private fun writeResult(
        requestId: String,
        cmd: String,
        status: String,
        detail: String,
        extra: JSONObject? = null,
    ) {
        val json = JSONObject()
            .put("requestId", requestId)
            .put("cmd", cmd)
            .put("status", status)
            .put("detail", detail)
            .put("at", SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()))
        extra?.let { json.put("extra", it) }
        DebateFiles.writeResult(context, requestId, json.toString(2))
    }

    private fun issuesJson(issues: List<String>): JSONObject =
        JSONObject().put("issues", JSONArray(issues))

    companion object {
        const val ACTION_DEBATE_CONTROL = "com.neethu.aiavatar_sdk.DEBATE_CONTROL"
        const val EXTRA_CMD = "debate_cmd"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CONFIG_FILE = "config_file"
        const val EXTRA_SPEAKER_ID = "speaker_id"
        const val EXTRA_SHOT = "shot"
        const val EXTRA_TARGET_ID = "target_id"
        const val EXTRA_TEAM = "team"
        const val EXTRA_OTHER_ID = "other_id"
        const val EXTRA_HOLD_MS = "hold_ms"
    }
}
