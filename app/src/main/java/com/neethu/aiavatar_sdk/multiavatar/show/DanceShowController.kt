package com.neethu.aiavatar_sdk.multiavatar.show

import android.content.Context
import android.util.Log
import com.neethu.aiavatar_sdk.loadWalkAnchors
import com.neethu.aiavatar_sdk.multiavatar.Slots
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

    /** 背景音乐（P1：music 幕的落点；随停演/页面停止收干净）。 */
    private val music = MusicPlayer(context, ::log)

    /** 锚点表（move 幕词表 = `<move:>` 同一张 home.anchors.json，P1 扩前排表演位）。 */
    private val anchors: List<MoveAnchor> = loadWalkAnchors(context)

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
        director.start(resolved, cast, ::execute, ::durationOf, ::anchorOf, ::positionOf)
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
        log("停演（音乐停/停舞回 idle/走动停/注视复位）")
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
            "music=${if (music.isPlaying) "on" else "off"} fps=${stage.fps.value}"
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
     */
    private fun sayLine(id: String, text: String) {
        val rt = rig.roundtable
        if (rt == null || rt.sessionOf(id.uppercase()) == null) {
            log("warn: $id 无会话，串场词跳过: $text")
            director.onSayCompleted(id)
            return
        }
        log("show say: $id: $text")
        scope.launch { rt.say(id.uppercase(), text) }
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
