package com.neethu.aiavatar_sdk.multiavatar.show

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** 演出状态：待演 / 演出中 / 演完（可重演）/ 中途停演。 */
enum class ShowState { IDLE, RUNNING, DONE, STOPPED }

/** 幕内阶段：表演中 / 收尾回位（观众席复位+回 mark）。 */
enum class ActPhase { PERFORM, RETURN }

/** 幕次进度（`dance status` 与 UI 状态行的数据源）。 */
data class ActProgress(
    val index: Int,
    val total: Int,
    val act: ShowAct?,
    val phase: ActPhase,
) {
    val isIdle: Boolean get() = act == null
    val summary: String
        get() = act?.let { "${index + 1}/$total ${it.type} phase=$phase" } ?: "-"

    companion object {
        val NONE = ActProgress(-1, 0, null, ActPhase.PERFORM)
    }
}

/**
 * 导演指令（纯数据，不依赖 Android）：[ShowDirector] 只发不做，落地在
 * DanceShowController（AvatarHandle/RoundtableSession 调用都在那边）。
 */
sealed interface DirectorCmd {
    val label: String

    /** 切机位（shot 为 [CameraNames] 词表；target = 锚定演员 id，可空）。 */
    data class Camera(val shot: String, val target: String? = null) : DirectorCmd {
        override val label: String get() = "cam $shot${target?.let { " $it" } ?: ""}"
    }

    /** 串场词（P0 只落日志，P1 接 TTS）。 */
    data class Say(val performer: String, val text: String) : DirectorCmd {
        override val label: String get() = "say $performer: $text"
    }

    /** 播一支曲目：loop=false 独舞/谢幕（one-shot，播完自动回 idle），loop=true 齐舞。 */
    data class PlayDance(val performer: String, val clip: String, val loop: Boolean) : DirectorCmd {
        override val label: String get() = "play $performer $clip${if (loop) " loop" else ""}"
    }

    /** 全员停舞回 idle（齐舞到点统一收）。 */
    data class StopDances(val fade: Float = 0.3f) : DirectorCmd {
        override val label: String get() = "stop dances(fade=$fade)"
    }

    /** 观众席看表演者（头部+眼睛；身体朝向由注视引擎带动）。 */
    data class GazeAt(val watcher: String, val target: String) : DirectorCmd {
        override val label: String get() = "gaze $watcher → $target"
    }

    /** 观众席复位（回看镜头）。 */
    data class GazeCamera(val watcher: String) : DirectorCmd {
        override val label: String get() = "gaze $watcher → camera"
    }

    /** 应援一次性动作（并行发射，不等）。 */
    data class Clap(val performer: String) : DirectorCmd {
        override val label: String get() = "clap $performer"
    }

    /** 幕间回位：snap 回自己的队列 mark 并转回镜头方向（§4.7 最简版）。 */
    data class ReturnToMark(val performer: String) : DirectorCmd {
        override val label: String get() = "return $performer → mark"
    }

    /** 幕次/兜底日志（调试通道可见）。 */
    data class Log(val text: String) : DirectorCmd {
        override val label: String get() = text
    }
}

/**
 * 演出导演（纯 JVM 状态机，docs/multi-dance-feasibility.md §4.2/§4.6）：
 * 输入=节目单+事件（[onVrmaFinished]/[skip]），输出=指令流（[start] 的 out）。
 * 幕次推进是一条顺序协程，每个等待都包超时兜底——事件丢了也能推进，
 * 演出宁可瑕疵不可卡死。
 *
 * 不依赖 Android；协程跑在传入 scope（demo 传 rig scope，Main dispatcher），
 * JVM 单测传 TestScope（注意 delay 兜底会被 advanceUntilIdle 推过，超时分支
 * 用 advanceTimeBy/runCurrent——multi-avatar P3 单测同款纪律）。
 */
class ShowDirector(
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(ShowState.IDLE)
    val state: StateFlow<ShowState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(ActProgress.NONE)
    val progress: StateFlow<ActProgress> = _progress.asStateFlow()

    val isRunning: Boolean get() = _state.value == ShowState.RUNNING

    private sealed interface Event {
        data class VrmaFinished(val avatarId: String) : Event
        data object Skip : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private var job: Job? = null

    /** 演出换代号：防旧协程被取消后其收尾代码覆盖新一场的状态。 */
    private var generation = 0

    /**
     * 开演（已在演则重启换单）。[cast] 为在场演员 id（齐舞/谢幕/观众席的依据）；
     * [out] 在导演协程（Main）内同步调用；[durationOf] 取演员当前 VRMA 时长
     * （PlayDance 落地后查询，独舞超时=1.2×duration+1s 的输入），null 用默认兜底。
     */
    fun start(
        script: ShowScript,
        cast: List<String>,
        out: (DirectorCmd) -> Unit,
        durationOf: (performer: String, clip: String) -> Float? = { _, _ -> null },
    ) {
        job?.cancel()
        generation++
        val gen = generation
        drainEvents()
        _state.value = ShowState.RUNNING
        job = scope.launch {
            try {
                runScript(script, cast, out, durationOf)
                if (gen == generation) {
                    _state.value = ShowState.DONE
                    _progress.value = ActProgress.NONE
                    out(DirectorCmd.Log("show done: ${script.title}"))
                }
            } catch (e: CancellationException) {
                if (gen == generation) _state.value = ShowState.STOPPED
                throw e
            }
        }
    }

    /** 演员一次性动作播完（AvatarHandle.vrmaFinished 的转发点）。 */
    fun onVrmaFinished(avatarId: String) {
        events.trySend(Event.VrmaFinished(avatarId))
    }

    /** 跳过当前幕（正在等待/停顿即提前收，进收尾再进下一幕）；非演出中忽略。 */
    fun skip() {
        if (isRunning) events.trySend(Event.Skip)
    }

    /** 停演：同步置 STOPPED 并取消推进协程（三清由控制器做，这里只管状态机）。 */
    fun stop() {
        job?.cancel()
        job = null
        generation++
        if (_state.value == ShowState.RUNNING) _state.value = ShowState.STOPPED
        _progress.value = ActProgress.NONE
    }

    // ── 幕次推进 ─────────────────────────────────────────────────────────

    private suspend fun runScript(
        script: ShowScript,
        cast: List<String>,
        out: (DirectorCmd) -> Unit,
        durationOf: (performer: String, clip: String) -> Float?,
    ) {
        for ((index, act) in script.acts.withIndex()) {
            _progress.value = ActProgress(index, script.acts.size, act, ActPhase.PERFORM)
            drainEvents() // 上一幕的迟到事件不污染本幕的等待
            when (act.type) {
                ActTypes.CAMERA -> out(DirectorCmd.Camera(act.shot ?: "group", act.target))
                ActTypes.WAIT -> hold(act.seconds ?: 1f)
                ActTypes.SAY -> out(DirectorCmd.Say(act.performer ?: cast.first(), act.text ?: ""))
                ActTypes.DANCE -> performSolo(act, cast, out, durationOf)
                ActTypes.GROUP_DANCE -> performGroup(act, cast, out)
                ActTypes.BOW -> performBow(act, cast, out, durationOf)
                // P1 接入（move=走位 / music=配乐）：先占位不拦整单
                ActTypes.MOVE, ActTypes.MUSIC ->
                    out(DirectorCmd.Log("act '${act.type}' P1 未接入，跳过"))
            }
        }
    }

    /** 独舞：观众席注视+应援 → 切机位 → one-shot → 播完/超时 → 回位+观众复位。 */
    private suspend fun performSolo(
        act: ShowAct,
        cast: List<String>,
        out: (DirectorCmd) -> Unit,
        durationOf: (performer: String, clip: String) -> Float?,
    ) {
        val performer = act.performer ?: return
        val clip = act.clip ?: return
        val audience = cast.filter { it != performer }
        audience.forEach { out(DirectorCmd.GazeAt(it, performer)) }
        if (FanActions.CLAP in act.fans.orEmpty() && audience.isNotEmpty()) {
            audience.shuffled(random).take(CLAP_MAX_FANS)
                .forEach { out(DirectorCmd.Clap(it)) }
        }
        act.fans.orEmpty().filter { it == FanActions.SWAY }
            .forEach { _ -> out(DirectorCmd.Log("fans sway P1 未接入，跳过")) }
        act.camera?.let { out(DirectorCmd.Camera(it, performer)) }
        out(DirectorCmd.PlayDance(performer, clip, loop = false))
        val duration = durationOf(performer, clip) ?: DEFAULT_DANCE_SECONDS
        val timeoutS = 1.2f * duration + 1f
        val arrived = awaitVrma(performer, timeoutS)
        if (!arrived) {
            out(DirectorCmd.Log("vrma 超时兜底推进: $performer（兜底%.1fs）".format(timeoutS)))
        }
        _progress.value = _progress.value.copy(phase = ActPhase.RETURN)
        out(DirectorCmd.ReturnToMark(performer))
        audience.forEach { out(DirectorCmd.GazeCamera(it)) }
    }

    /** 齐舞：全员同帧 loop 起播（舞台级 VRMA 缓存），到点统一 stop 回 idle。 */
    private suspend fun performGroup(act: ShowAct, cast: List<String>, out: (DirectorCmd) -> Unit) {
        val clip = act.clip ?: return
        act.camera?.let { out(DirectorCmd.Camera(it, null)) }
        cast.forEach { out(DirectorCmd.PlayDance(it, clip, loop = true)) }
        hold(act.seconds ?: DEFAULT_GROUP_SECONDS)
        out(DirectorCmd.StopDances())
    }

    /** 谢幕：全员一次性鞠躬（同帧起播同刻收），时间驱动等收尾。 */
    private suspend fun performBow(
        act: ShowAct,
        cast: List<String>,
        out: (DirectorCmd) -> Unit,
        durationOf: (performer: String, clip: String) -> Float?,
    ) {
        act.camera?.let { out(DirectorCmd.Camera(it, null)) }
        cast.forEach { out(DirectorCmd.PlayDance(it, BOW_CLIP, loop = false)) }
        val duration = cast.firstOrNull()?.let { durationOf(it, BOW_CLIP) } ?: DEFAULT_DANCE_SECONDS
        hold(1.2f * duration + 0.5f)
    }

    // ── 等待原语（事件或 skip 提前收，超时也是推进信号）──────────────────

    private suspend fun awaitVrma(avatarId: String, timeoutSeconds: Float): Boolean =
        waitSignal((timeoutSeconds * 1000).toLong()) {
            it is Event.Skip || (it is Event.VrmaFinished && it.avatarId == avatarId)
        }

    private suspend fun hold(seconds: Float): Boolean =
        waitSignal((seconds * 1000).toLong()) { it is Event.Skip }

    private suspend fun waitSignal(timeoutMs: Long, matches: (Event) -> Boolean): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val e = events.receive()
                if (matches(e)) return@withTimeoutOrNull true
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false

    private fun drainEvents() {
        while (events.tryReceive().isSuccess) { /* 丢弃换代/上一幕残留 */ }
    }

    companion object {
        /** 谢幕鞠躬曲目（03_打招呼与礼仪，净位移小的站姿短动作）。 */
        const val BOW_CLIP = "animations/03_打招呼与礼仪/Quick Informal Bow.vrma"

        /** 应援最多几人同时拍（§4.4：随机挑 1~2 人）。 */
        const val CLAP_MAX_FANS = 2

        const val DEFAULT_GROUP_SECONDS = 12f
    }
}
