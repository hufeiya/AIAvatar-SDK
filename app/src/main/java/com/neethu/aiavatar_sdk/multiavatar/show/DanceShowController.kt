package com.neethu.aiavatar_sdk.multiavatar.show

import android.content.Context
import android.util.Log
import com.neethu.aiavatar_sdk.loadWalkAnchors
import com.neethu.aiavatar_sdk.multiavatar.AccessoryCatalog
import com.neethu.aiavatar_sdk.multiavatar.Slots
import com.neethu.aiavatar_sdk.multiavatar.VfxCatalog
import com.neethu.corelib.vfx.VfxAnchor
import com.neethu.aiavatar_sdk.multiavatar.StageChatRig
import com.neethu.corelib.CameraShot
import com.neethu.corelib.WalkStartResult
import com.neethu.corelib.stage.AvatarHandle
import com.neethu.orchestrator.locomotion.MoveAnchor
import com.neethu.orchestrator.roundtable.RoundtableState
import com.neethu.orchestrator.session.AvatarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** 机位词表 → CameraShot（STAGE_CMD cam 与团播节目单共用一张映射）。 */
fun parseCameraShot(name: String): CameraShot? = when (name.lowercase()) {
    "closeup" -> CameraShot.CLOSE_UP
    "macro" -> CameraShot.MACRO
    "medium" -> CameraShot.MEDIUM_SHOT
    "full" -> CameraShot.FULL_SHOT
    "long" -> CameraShot.LONG_SHOT
    "over" -> CameraShot.OVER_SHOULDER
    "follow" -> CameraShot.FOLLOW
    "group" -> CameraShot.GROUP_SHOT
    else -> null
}

/**
 * 团播落地层（docs/multi-dance-feasibility.md §4.2/§五）：[ShowDirector] 发指令，
 * 这里落到 AvatarHandle/舞台相机；外加节目单装载（assets 内置单 + filesDir 自定义单）、
 * 曲目白名单（启动扫 assets/animations 生成）、开演前置校验与圆桌互斥。
 *
 * 职责边界：状态机与时序全在导演（纯 JVM 可单测）；这里只做「指令 → 舞台调用」
 * 与运行环境相关的校验（演员在场/曲目在库）。
 */
internal class DanceShowController(
    context: Context,
    private val stage: com.neethu.corelib.stage.AvatarStage,
    private val slots: Slots,
    private val rig: StageChatRig,
    private val onLog: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val director = ShowDirector(scope)
    private val appContext = context.applicationContext

    /** 曲目白名单（归一后的完整 assets 路径），启动时全库扫描。 */
    val whitelist: Set<String> = scanAnimationWhitelist(context)

    /** 特效白名单（P2 点播特效走导播链：目录 id 表，空目录=不宣传 fx 能力）。 */
    val fxWhitelist: Set<String> = VfxCatalog.all(context).keys

    /** 背景音乐（P1：music 幕的落点；随停演/页面停止收干净）。 */
    private val music = MusicPlayer(context, ::log)

    /** 锚点表（move 幕词表 = `<move:>` 同一张 home.anchors.json，P1 扩前排表演位）。 */
    private val anchors: List<MoveAnchor> = loadWalkAnchors(context)

    /**
     * 活动目录（P2 道具系统 activity 幕，docs/prop-system-feasibility.md
     * §7-P2.4）：assets/activities/activities.json 解析（缺/脏 = EMPTY——activity
     * 幕开演前校验拒绝、运行期记行跳过，不影响其他幕型）。
     */
    private val activityCatalog: com.neethu.orchestrator.activity.ActivityCatalog =
        com.neethu.aiavatar_sdk.loadActivityCatalog(context)

    /** 点播路由器（P2）：档位随 prefs 装配（`dance auto` 切换重建）。 */
    private var router: DanceRequestRouter = buildRouter()

    /** 导播决策日志（director_log 风格，ring 缓冲，`dance log` 回显）。 */
    private val directorLog = ArrayDeque<String>()

    private var cast: List<String> = emptyList()

    /** 本场发起的原地转向（turnTo 完成也发 walkFinished，这里吞掉防误推进）。 */
    private val pendingTurns = mutableSetOf<String>()

    /** `dance load` 装载待演的节目单（`dance start` 不带名时优先用它）。 */
    var loadedScript: ShowScript? = null
        private set

    private var collectorJobs: List<Job> = emptyList()

    init {
        // 代码常量引用的曲目也须在库内（资产增删时早发现）
        listOf(ShowDirector.BOW_CLIP, FANS_CLAP_CLIP)
            .filter { it !in whitelist }
            .forEach { log("warn: 内置曲目不在库内 $it") }
        // 演出收场提示（P2 彩蛋入口：DONE 后可 topic 开题圆桌点评）
        scope.launch {
            director.state.collect { s ->
                if (s == ShowState.DONE) {
                    log("演出收场（dance start 重演 / topic 点评刚才的演出 / 演出中聊天框=dance dm 点播）")
                }
            }
        }
    }

    val state get() = director.state
    val progress get() = director.progress
    val isRunning: Boolean get() = director.isRunning

    // ── 节目单装载 ───────────────────────────────────────────────────────

    /**
     * 读节目单并校验：优先 filesDir/shows/<名>（adb push 进来的自定义单），
     * 回落 assets/shows/<名>（内置单）；名空 = 默认单。返回回显行。
     */
    fun loadShow(name: String?): String {
        val showName = name?.takeIf { it.isNotBlank() } ?: DEFAULT_SHOW
        val text = runCatching {
            File(appContext.filesDir, "shows/$showName").takeIf { it.isFile }?.readText()
        }.getOrNull() ?: runCatching {
            appContext.assets.open("shows/$showName").use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { return "FAIL: 找不到节目单 $showName（filesDir/shows 与 assets/shows 均无）" }
        val script = ShowScriptParser.parse(text).getOrElse {
            return "FAIL: ${it.message}"
        }
        loadedScript = script
        return "OK: ${script.title} ${script.acts.size} 幕（估 ${estimateSeconds(script).toInt()}s）已装载"
    }

    // ── 开演 / 停演 / 跳幕 ───────────────────────────────────────────────

    /** 开演；false = 拒绝（原因走 onLog）。 */
    fun startShow(name: String? = null): Boolean {
        if (director.isRunning) {
            log("拒绝: 已在演出中（先 dance stop）")
            return false
        }
        rig.roundtable?.let { rt ->
            if (rt.state.value != RoundtableState.IDLE) {
                log("拒绝: 圆桌进行中（先 stop 停题再开演）")
                return false
            }
        }
        val ready = slots.readyIds()
        if (ready.isEmpty()) {
            log("拒绝: 台上无就位演员")
            return false
        }
        // 带名开演=装载该单；不带名=用 dance load 的存量单，没有则装载默认单。
        // 装载失败即拒绝，不静默回落旧单（早失败纪律）。
        val script: ShowScript? = if (name.isNullOrBlank()) {
            loadedScript ?: (loadShow(null).takeIf { it.startsWith("OK") }?.let { loadedScript })
        } else {
            loadShow(name).takeIf { it.startsWith("OK") }?.let { loadedScript }
        }
        if (script == null) {
            log("拒绝: 无可用节目单（dance load 回显原因）")
            return false
        }
        val resolved = script.resolveClips()
        validateAgainstStage(resolved, ready)?.let { reason ->
            log("拒绝: $reason")
            return false
        }
        cast = ready
        pendingTurns.clear()
        restartCollectors()
        // 在途发言让路（圆桌停题后可能仍有收尾 TTS）
        rig.roundtable?.interrupt()
        resolved.music?.let { m ->
            runCatching { appContext.assets.open(m).close() }
                .onFailure { log("warn: 配乐文件缺失 $m（music start 时报错跳过）") }
        }
        log("开演: ${resolved.title} cast=[${cast.joinToString(",")}] ${resolved.acts.size} 幕")
        director.start(resolved, cast, ::execute, ::durationOf, ::anchorOf, ::positionOf, ::activityOf)
        return true
    }

    /** 停演四清：音乐停 + 导演停 + 串场词打断 + 全员停舞走动停 + 观众席复位。 */
    fun stopShow() {
        director.stop()
        cancelCollectors()
        music.stop()
        rig.roundtable?.interrupt()
        slots.entries().forEach { (_, h) ->
            h?.stopWalk()
            h?.stopVrmaAnimation(0.3f)
            if (h?.isReady == true) h.lookAtCamera()
        }
        stage.stopAllVfx()
        log("停演（音乐停/停舞回 idle/走动停/注视复位/特效全停）")
    }

    /** 跳过当前幕。 */
    fun skip() {
        if (!director.isRunning) {
            log("skip 忽略: 非演出中")
        } else {
            director.skip()
            log("skip → 推进下一幕")
        }
    }

    // ── 点播路由（P2）────────────────────────────────────────────────────

    /**
     * 点播受理：`dance dm <文本>` 与演出中聊天框共用入口。路由含一次 LLM
     * 调用（异步跑在控制器 scope），命中插幕进导演队列（当前幕收尾后执行），
     * 拒绝只留日志——任何路由结果都不打断当前幕。
     */
    fun handleDm(text: String) {
        if (text.isBlank()) return
        if (!director.isRunning) {
            log("dm 忽略: 非演出中（dance start 后再点播）")
            return
        }
        val members = castMembers()
        scope.launch {
            val decision = router.route(text, members, whitelist, fxWhitelist)
            recordDecision("dm「${text.take(20)}」→ ${decisionSummary(decision)}")
            when (decision) {
                is RequestDecision.Insert -> {
                    val desc = if (decision.act.type == ActTypes.FX) {
                        "特效幕 ${decision.act.fx}"
                    } else {
                        "${decision.act.performer} ${decision.act.clip?.substringAfterLast('/') ?: "-"}"
                    }
                    if (director.enqueueAct(decision.act)) {
                        log("点播插幕（${decision.track}）: $desc — ${decision.reason}")
                    } else {
                        log("点播未受理: 演出已收场")
                    }
                }
                is RequestDecision.Reject -> log("点播拒绝: ${decision.reason}")
            }
        }
    }

    /** `dance auto on|off`：LLM 导播总开关（缺 Key 静默落规则档——圆桌既有模式）。 */
    fun setAutoMode(arg: String?): String {
        when (arg?.lowercase()) {
            "on", "off" -> rig.prefs.edit().putString(KEY_AUTO_MODE, arg.lowercase()).apply()
        }
        router = buildRouter()
        val on = autoModePref()
        return if (on && router.policyLabel == "llm") {
            "auto=on → 生效档=llm（${router.costLine()}）"
        } else if (on) {
            "auto=on → 生效档=rule（缺硅基流动 Key，自然语言点播降级）"
        } else {
            "auto=off → 生效档=rule（仅精确「演员+曲目名」可插幕）"
        }
    }

    /** `dance polish on|off`：串场词润色开关（默认关——固定串场已够演出）。 */
    fun setPolish(arg: String?): String {
        when (arg?.lowercase()) {
            "on", "off" -> rig.prefs.edit().putString(KEY_POLISH, arg.lowercase()).apply()
        }
        return "polish=${polishPref()}（${router.policyLabel} 档才生效）"
    }

    /** `dance log`：导播决策日志全量（最近 [DIRECTOR_LOG_MAX] 条）。 */
    fun directorLogLine(): String =
        if (directorLog.isEmpty()) "log=(空)" else "log=\n${directorLog.joinToString("\n")}"

    /** 点播路由器装配：LLM 档要求 auto 开 + 有硅基流动 Key，否则静默规则档。 */
    private fun buildRouter(): DanceRequestRouter {
        val llm = if (autoModePref()) rig.danceDirectorLlm() else null
        return DanceRequestRouter(
            if (llm != null) RequestPolicy.Llm(llm.llm, llm.config) else RequestPolicy.Rule,
        )
    }

    private fun autoModePref(): Boolean = rig.prefs.getString(KEY_AUTO_MODE, "on") != "off"

    private fun polishPref(): Boolean = rig.prefs.getString(KEY_POLISH, "off") == "on"

    /** 在场演员名册（id + 显示名，规则档匹配与 LLM 名册的输入）。 */
    private fun castMembers(): List<RequestCastMember> {
        val specs = rig.roundtable?.slotSpecs().orEmpty().associate { it.id to it.displayName }
        return cast.map { RequestCastMember(it, specs[it]?.takeIf { n -> n.isNotBlank() } ?: it) }
    }

    private fun decisionSummary(d: RequestDecision): String = when (d) {
        is RequestDecision.Insert ->
            "插幕 ${d.track} ${d.act.performer} ${d.act.clip?.substringAfterLast('/')}"
        is RequestDecision.Reject -> "拒绝 ${d.reason.take(40)}"
    }

    private fun recordDecision(line: String) {
        if (directorLog.size >= DIRECTOR_LOG_MAX) directorLog.removeFirst()
        directorLog.addLast(line)
        Log.i(TAG, "director_log: $line")
    }

    fun close() {
        director.stop()
        cancelCollectors()
        music.release()
        scope.cancel()
    }

    /** 页面不可见：音乐暂停（风险 9 最低语义）；回来续播。 */
    fun onHostPause() = music.pause()

    fun onHostResume() = music.resume()

    // ── 回显 ─────────────────────────────────────────────────────────────

    fun statusLine(): String {
        val p = director.progress.value
        return "show=${director.state.value} act=${p.summary} cast=[${cast.joinToString(",")}] " +
            "music=${if (music.isPlaying) "on" else "off"} auto=${router.policyLabel} " +
            "dm(${router.costLine()}) fps=${stage.fps.value}"
    }

    fun listLine(): String {
        val script = loadedScript
        val acts = script?.acts?.mapIndexed { i, a ->
            "${i + 1}.${a.type}${a.performer?.let { " $it" } ?: ""}${a.clip?.let { " ${it.substringAfterLast('/')}" } ?: ""}"
        } ?: emptyList()
        Log.i(TAG, "whitelist(${whitelist.size}):\n${whitelist.sorted().joinToString("\n")}")
        return "clips=${whitelist.size}（全表见 logcat DanceShow）" +
            (script?.let { " | ${it.title}: ${acts.joinToString(" ┃ ")}" } ?: " | 未装载节目单")
    }

    // ── 开演前置校验（依赖运行环境：演员在场/曲目在库）────────────────────

    private fun validateAgainstStage(script: ShowScript, ready: List<String>): String? {
        for ((i, act) in script.acts.withIndex()) {
            val at = "act ${i + 1} (${act.type})"
            if (act.type == ActTypes.MOVE) {
                // move 的 target 是锚点名（其余幕的 target 是机位锚定演员 id）
                act.performer?.let {
                    if (it.uppercase() !in ready) return "$at: 演员 $it 不在台上（就位: ${ready.joinToString("/")}) "
                }
                val anchor = act.target
                if (anchor == null || anchorOf(anchor) == null) {
                    return "$at: 锚点 '$anchor' 不在表内（home.anchors.json）"
                }
                continue
            }
            act.performer?.let {
                if (it.uppercase() !in ready) return "$at: 演员 $it 不在台上（就位: ${ready.joinToString("/")}) "
            }
            act.target?.let {
                if (it.uppercase() !in ready) return "$at: 机位目标 $it 不在台上"
            }
            act.clip?.let {
                if (it !in whitelist) return "$at: 曲目不在白名单 $it"
            }
            // 幕次道具（P2）：id 必须在配饰目录（形状校验已在解析层）
            for (p in act.props.orEmpty()) {
                if (AccessoryCatalog.find(appContext, p) == null) return "$at: 道具 '$p' 不在配饰目录"
            }
            // activity 幕（P2 道具系统）：活动 id 必须在活动目录
            if (act.type == ActTypes.ACTIVITY && act.activity != null &&
                activityCatalog[act.activity] == null
            ) {
                return "$at: 活动 '${act.activity}' 不在目录（assets/activities/activities.json）"
            }
            // fx 幕（P1）：预设必须在特效目录
            if (act.type == ActTypes.FX && act.fx != null &&
                VfxCatalog.find(appContext, act.fx) == null
            ) {
                return "$at: 特效 '${act.fx}' 不在目录（assets/vfx/presets.json）"
            }
        }
        if (ShowDirector.BOW_CLIP !in whitelist) return "谢幕曲目不在白名单 ${ShowDirector.BOW_CLIP}"
        return null
    }

    /** move 幕锚点解析（名字不区分大小写，与解析层的小写归一对齐）。 */
    private fun anchorOf(name: String): Pair<Float, Float>? =
        anchors.firstOrNull { it.name == name.lowercase() }?.let { it.x to it.z }

    /** 演员当前世界位（导演「已在位」判定与 mark 初值的活查询）。 */
    private fun positionOf(id: String): Pair<Float, Float>? =
        slots[id]?.getWalkState()?.let { it.x to it.z }

    /** activity 幕的活动目录解析（id 大小写不敏感，同 start_activity 白名单口径）。 */
    private fun activityOf(id: String): com.neethu.orchestrator.activity.ActivityEntry? =
        activityCatalog[id]

    private fun durationOf(performer: String, @Suppress("UNUSED_PARAMETER") clip: String): Float? =
        slots[performer]?.getVrmaAnimationDuration()?.takeIf { it > 0f }

    // ── 指令落地（导演协程/Main 线程内同步执行）──────────────────────────

    private fun execute(cmd: DirectorCmd) {
        when (cmd) {
            is DirectorCmd.Camera -> {
                val shot = parseCameraShot(cmd.shot) ?: return
                stage.setCameraShot(shot, cmd.target?.let { slots[it] })
            }
            is DirectorCmd.Say -> sayLine(cmd.performer, cmd.text)
            is DirectorCmd.Walk -> executeWalk(cmd.performer, cmd.x, cmd.z)
            is DirectorCmd.FaceCamera -> {
                val h = slots[cmd.performer] ?: return
                val ok = h.getCameraLookAt()?.let { (eye, _, _) -> h.turnTo(eye[0], eye[2]) } ?: false
                // turnTo 完成也发 walkFinished——登记后由收集器吞掉，防误推进
                if (ok) pendingTurns.add(cmd.performer.uppercase())
            }
            is DirectorCmd.MusicStart -> music.start(cmd.assetPath)
            is DirectorCmd.MusicStop -> music.stop()
            is DirectorCmd.MusicVolume -> music.setVolume(cmd.volume, cmd.tag)
            is DirectorCmd.PlayDance -> slots[cmd.performer]?.let { h ->
                if (h.loadVrmaAnimation(cmd.clip)) {
                    h.playVrmaAnimation(loop = cmd.loop, fadeSeconds = 0.25f)
                } else log("FAIL: ${cmd.performer} 装载失败 ${cmd.clip}（超时兜底会推进）")
            }
            is DirectorCmd.StopDances -> cast.forEach { id ->
                slots[id]?.stopVrmaAnimation(cmd.fade)
            }
            is DirectorCmd.StopDance -> slots[cmd.performer]?.stopVrmaAnimation(cmd.fade)
            is DirectorCmd.GazeAt -> {
                val target = slots[cmd.target] ?: return
                slots[cmd.watcher]?.lookAt(target)
            }
            is DirectorCmd.GazeCamera -> slots[cmd.watcher]?.takeIf { it.isReady }?.lookAtCamera()
            is DirectorCmd.Clap -> slots[cmd.performer]?.let { h ->
                if (h.loadVrmaAnimation(FANS_CLAP_CLIP)) {
                    h.playVrmaAnimation(loop = false, fadeSeconds = 0.2f)
                }
            }
            is DirectorCmd.PropOn -> slots[cmd.performer]?.let { h ->
                // 已在场（动画绑定表自动出的同款）则跳过——挂载幂等语义在调用侧
                if (h.accessories.value.none { it.id.equals(cmd.accessoryId, true) }) {
                    val entry = AccessoryCatalog.find(appContext, cmd.accessoryId)
                    if (entry == null) {
                        log("warn: 道具 '${cmd.accessoryId}' 不在目录（跳过）")
                    } else {
                        val spec = AccessoryCatalog.specFor(appContext, h.spec.displayName, entry)
                        h.attachAccessory(spec)
                            .onSuccess { log("prop on: ${cmd.performer} +${entry.id}@${spec.bone}") }
                            .onFailure { log("warn: ${cmd.performer} 挂 ${entry.id} 失败 ${it.message}") }
                    }
                }
            }
            is DirectorCmd.PropOff -> slots[cmd.performer]?.let { h ->
                // 没挂着 = 静默（绑定表自动出的道具不归 props 摘）
                if (h.detachAccessory(cmd.accessoryId)) {
                    log("prop off: ${cmd.performer} -${cmd.accessoryId}")
                }
            }
            is DirectorCmd.PlayFx -> {
                // 锚点：bone 给了 = 骨骼锚定（发射跟随）；performer = 头顶世界位
                // 快照；都空 = 舞台中心（AvatarStage 缺省锚）
                val anchor = when {
                    cmd.bone != null -> VfxAnchor.Bone(cmd.bone, cmd.performer)
                    cmd.performer != null -> {
                        val head = slots[cmd.performer]?.headWorldPosition()
                        if (head != null) VfxAnchor.World(head[0], head[1] + 0.3f, head[2])
                        else VfxAnchor.World(0f, 0f, -4f)
                    }
                    else -> VfxAnchor.World(0f, 0f, -4f)
                }
                val spec = VfxCatalog.find(appContext, cmd.preset)
                if (spec == null) {
                    log("warn: fx preset '${cmd.preset}' 不在目录（跳过）")
                } else {
                    stage.playVfx(spec, anchor)?.let { log("fx on: ${cmd.preset} as ${it.id}") }
                        ?: log("warn: fx '${cmd.preset}' 播放被拒（批满/渲染未就绪）")
                }
            }
            is DirectorCmd.StopFx -> {
                // 按预设 id 停（fx 幕收幕；burst 型已自然播完 = 静默）
                val live = stage.vfxSnapshot().firstOrNull { it.presetId.equals(cmd.preset, true) }
                if (live != null && stage.stopVfx(live.id)) {
                    log("fx off: ${cmd.preset}")
                }
            }
            is DirectorCmd.Log -> log(cmd.text)
        }
    }

    /**
     * 走位落地（「已在位」判定在导演侧）：起步失败时**就地合成收尾事件**
     * （arrived=false）——导演的 awaitWalk 不空等，演出继续不卡场。
     */
    private fun executeWalk(id: String, x: Float, z: Float) {
        val h = slots[id] ?: return
        log("$id 起步 → (%.2f, %.2f)".format(x, z))
        val result = h.startWalk(x, z, WALK_SPEED_MPS)
        if (result != WalkStartResult.STARTED) {
            log("warn: $id 起步失败 $result（就地继续）")
            director.onWalkFinished(id, arrived = false)
        }
    }

    /**
     * 串场词落地（P1）：接圆桌 say 全链（TTS→口型→字幕）。该槽无会话 =
     * 秒回完成事件（团播可纯渲染不带 AI 跑，§7-P1），只留警告行。
     * P2 润色（默认关）：开关开着且文本非空，先 LLM 一句话变体（失败降级
     * 原文）；润色期间串场词未开播，TurnCompleted 不会提前到达。
     */
    private fun sayLine(id: String, text: String) {
        val rt = rig.roundtable
        if (rt == null || rt.sessionOf(id.uppercase()) == null) {
            log("warn: $id 无会话，串场词跳过: $text")
            director.onSayCompleted(id)
            return
        }
        if (!polishPref() || text.isBlank()) {
            log("show say: $id: $text")
            scope.launch { rt.say(id.uppercase(), text) }
            return
        }
        val name = rt.slotSpecs().firstOrNull { it.id.equals(id, true) }?.displayName ?: id
        scope.launch {
            val polished = runCatching { router.polishLine(name, text) }.getOrNull()
            // 润色的 LLM 等待落在导演协程之外：停演发生在等待中时，迟到的
            // 串场词不再播出（演出已收，say 也无人等了）
            if (!director.isRunning) return@launch
            val finalText = polished ?: text
            if (polished != null) recordDecision("polish「${text.take(16)}」→「$finalText」")
            log("show say: $id: $finalText${if (polished != null) "（润色）" else ""}")
            rt.say(id.uppercase(), finalText)
        }
    }

    // ── 事件收集（vrmaFinished/walkFinished/stageEvents → 导演）──────────

    private fun restartCollectors() {
        cancelCollectors()
        collectorJobs = cast.mapNotNull { id ->
            val h = slots[id] ?: return@mapNotNull null
            scope.launch {
                h.vrmaFinished.collect { director.onVrmaFinished(id) }
            }
        } + cast.mapNotNull { id ->
            val h = slots[id] ?: return@mapNotNull null
            scope.launch {
                h.walkFinished.collect { f ->
                    if (pendingTurns.remove(id)) return@collect // 本场转向的事件，吞
                    director.onWalkFinished(id, f.arrived)
                }
            }
        } + listOfNotNull(
            rig.roundtable?.let { rt ->
                scope.launch {
                    rt.events.collect { (slotId, event) ->
                        when (event) {
                            is AvatarEvent.TurnCompleted,
                            is AvatarEvent.TurnFailed,
                            is AvatarEvent.PlaybackInterrupted,
                            -> director.onSayCompleted(slotId)

                            else -> Unit
                        }
                    }
                }
            }
        )
    }

    private fun cancelCollectors() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs = emptyList()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog(msg.take(120))
    }

    companion object {
        const val TAG = "DanceShow"

        /** 内置默认节目单（assets/shows/）。 */
        const val DEFAULT_SHOW = "default.json"

        /** 应援鼓掌曲目（04_交流手势；多为坐姿起手，真机验收挑选后可换）。 */
        const val FANS_CLAP_CLIP = "animations/04_交流手势/Sitting Ethusiastic Clap.vrma"

        /** 团播走位速度（m/s；步态时间标度由引擎换算）。 */
        const val WALK_SPEED_MPS = 1.0f

        /** 点播 LLM 导播总开关 prefs（默认 on：缺 Key 静默落规则档）。 */
        private const val KEY_AUTO_MODE = "show_auto_mode"

        /** 串场词润色 prefs（默认 off——固定串场已够演出，§7-P2）。 */
        private const val KEY_POLISH = "show_polish"

        /** 导播决策日志条数上限（ring 缓冲）。 */
        private const val DIRECTOR_LOG_MAX = 20

        /** 全库扫描 animations/ 下所有 .vrma（归一完整路径），生成曲目白名单。 */
        fun scanAnimationWhitelist(context: Context): Set<String> {
            val out = mutableSetOf<String>()
            fun walk(rel: String) {
                val dir = if (rel.isEmpty()) "animations" else "animations/$rel"
                val children = runCatching { context.assets.list(dir) }.getOrNull() ?: return
                for (c in children) {
                    val childRel = if (rel.isEmpty()) c else "$rel/$c"
                    if (c.endsWith(".vrma", ignoreCase = true)) {
                        out += "animations/$childRel"
                    } else {
                        walk(childRel)
                    }
                }
            }
            walk("")
            return out
        }
    }
}
