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
import kotlin.math.sqrt
import kotlin.random.Random

/** 演出状态：待演 / 演出中 / 演完（可重演）/ 中途停演。 */
enum class ShowState { IDLE, RUNNING, DONE, STOPPED }

/** 幕内阶段：表演中 / 收尾回位（观众席复位+走回 mark）。 */
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

    /** 串场词（P1 接 TTS；完成信号 = 该槽 TurnCompleted，超时兜底推进）。 */
    data class Say(val performer: String, val text: String) : DirectorCmd {
        override val label: String get() = "say $performer: $text"
    }

    /** 走位（P1）：从当前位置走到世界点（引擎自行 clamp 进可行走区）。 */
    data class Walk(val performer: String, val x: Float, val z: Float) : DirectorCmd {
        override val label: String get() = "walk $performer → (%.2f, %.2f)".format(x, z)
    }

    /** 到位后转回镜头方向（fire-and-forget；其 walkFinished 由控制器吞掉不进导演）。 */
    data class FaceCamera(val performer: String) : DirectorCmd {
        override val label: String get() = "face camera $performer"
    }

    /** 背景音乐：播 assets 曲目（loop）/ 停 / 音量两档（duck=0.35、full=基线）。 */
    data class MusicStart(val assetPath: String) : DirectorCmd {
        override val label: String get() = "music start $assetPath"
    }

    data object MusicStop : DirectorCmd {
        override val label: String get() = "music stop"
    }

    data class MusicVolume(val volume: Float, val tag: String) : DirectorCmd {
        override val label: String get() = "music $tag(vol=$volume)"
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

    /** 幕次道具上（P2 配饰：act.props 声明，act 开始时给表演者挂上）。 */
    data class PropOn(val performer: String, val accessoryId: String) : DirectorCmd {
        override val label: String get() = "prop +$accessoryId @ $performer"
    }

    /** 幕次道具下（收幕摘下；没挂着 = 静默，动画绑定自动出的道具不归这里管）。 */
    data class PropOff(val performer: String, val accessoryId: String) : DirectorCmd {
        override val label: String get() = "prop -$accessoryId @ $performer"
    }

    /** 应援一次性动作（并行发射，不等）。 */
    data class Clap(val performer: String) : DirectorCmd {
        override val label: String get() = "clap $performer"
    }

    /**
     * 特效插幕开（P1，docs/vfx-feasibility.md §六）：[preset] 为 VfxCatalog
     * 预设 id；[performer] 给了 = 锚其头顶世界位快照，[bone] 给了 = 骨骼锚定
     * （两者都空 = 舞台中心）。fire-and-forget 播放，不打断编舞。
     */
    data class PlayFx(val preset: String, val performer: String? = null, val bone: String? = null) : DirectorCmd {
        override val label: String get() = "fx $preset${performer?.let { " @$it" } ?: ""}"
    }

    /** 特效插幕收（fx 幕 hold 完成后；按预设 id 停，仍在播才停得掉）。 */
    data class StopFx(val preset: String) : DirectorCmd {
        override val label: String get() = "fx stop $preset"
    }

    /** 幕次/兜底日志（调试通道可见）。 */
    data class Log(val text: String) : DirectorCmd {
        override val label: String get() = text
    }
}

/**
 * 演出导演（纯 JVM 状态机，docs/multi-dance-feasibility.md §4.2/§4.6）：
 * 输入=节目单+事件（[onVrmaFinished]/[onWalkFinished]/[onSayCompleted]/[skip]），
 * 输出=指令流（[start] 的 out）。
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
        data class WalkFinished(val avatarId: String, val arrived: Boolean) : Event
        data class SayCompleted(val avatarId: String) : Event
        data object Skip : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    /** 点播插幕队列（P2）：当前幕收尾后消费，FIFO；换代/停演即清。 */
    private val insertions = Channel<ShowAct>(Channel.UNLIMITED)

    /** 本场已受理的插幕数（进度总幕数随之增长）。 */
    private var insertedCount = 0
    private var job: Job? = null

    /** 演出换代号：防旧协程被取消后其收尾代码覆盖新一场的状态。 */
    private var generation = 0

    /** 队列 mark（键=演员 id）＝开演位（§7-P1 验收口径：退场走回队列；演出中不变）。 */
    private var marks: Map<String, Pair<Float, Float>> = emptyMap()

    /** 演员当前世界位查询（start 时由控制器注入，读活状态）。 */
    private var positionOf: (performer: String) -> Pair<Float, Float>? = { null }

    /**
     * 开演（已在演则重启换单）。[cast] 为在场演员 id（齐舞/谢幕/观众席的依据）；
     * [out] 在导演协程（Main）内同步调用；[durationOf] 取演员当前 VRMA 时长
     * （PlayDance 落地后查询，独舞超时=1.2×duration+1s 的输入），null 用默认兜底；
     * [anchorOf] 解析 move 幕的锚点名 → 世界坐标（null = 表里没有）；
     * [positionOf] 取演员当前世界位（走位「已在位」判定与 mark 初值的输入）。
     */
    fun start(
        script: ShowScript,
        cast: List<String>,
        out: (DirectorCmd) -> Unit,
        durationOf: (performer: String, clip: String) -> Float? = { _, _ -> null },
        anchorOf: (name: String) -> Pair<Float, Float>? = { null },
        positionOf: (performer: String) -> Pair<Float, Float>? = { null },
    ) {
        job?.cancel()
        generation++
        val gen = generation
        drainEvents()
        drainInsertions()
        insertedCount = 0
        _state.value = ShowState.RUNNING
        this.positionOf = positionOf
        marks = cast.associateWith { id -> positionOf(id) ?: (0f to -4f) }
        job = scope.launch {
            try {
                runScript(script, cast, out, durationOf, anchorOf)
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

    /** 演员走动结束（AvatarHandle.walkFinished 的转发点；arrived=false=被取消）。 */
    fun onWalkFinished(avatarId: String, arrived: Boolean) {
        events.trySend(Event.WalkFinished(avatarId, arrived))
    }

    /** 槽位串场词收尾（该槽 TurnCompleted/TurnFailed/被打断，或无会话就地合成）。 */
    fun onSayCompleted(avatarId: String) {
        events.trySend(Event.SayCompleted(avatarId))
    }

    /** 跳过当前幕（正在等待/停顿即提前收，进收尾再进下一幕）；非演出中忽略。 */
    fun skip() {
        if (isRunning) events.trySend(Event.Skip)
    }

    /**
     * 点播插幕（P2）：返回 false=非演出中（调用方回提示）。插幕在**当前幕
     * 收尾后**作为下一幕执行（不打断进行中的独舞，§7-P2）；多次插播按受理
     * 顺序 FIFO。路由/白名单校验在 DanceRequestRouter，这里只收队列。
     */
    fun enqueueAct(act: ShowAct): Boolean {
        if (!isRunning) return false
        insertedCount++
        insertions.trySend(act)
        return true
    }

    /** 停演：同步置 STOPPED 并取消推进协程（三清由控制器做，这里只管状态机）。 */
    fun stop() {
        job?.cancel()
        job = null
        generation++
        drainInsertions()
        insertedCount = 0
        if (_state.value == ShowState.RUNNING) _state.value = ShowState.STOPPED
        _progress.value = ActProgress.NONE
    }

    // ── 幕次推进 ─────────────────────────────────────────────────────────

    private suspend fun runScript(
        script: ShowScript,
        cast: List<String>,
        out: (DirectorCmd) -> Unit,
        durationOf: (performer: String, clip: String) -> Float?,
        anchorOf: (name: String) -> Pair<Float, Float>?,
    ) {
        // 幕次工作队列（P2）：节目单打底，点播插幕在幕间插队（当前幕收尾后
        // 即是下一幕）。index/total 只是进度显示，插幕让总数增长。
        val queue = ArrayDeque(script.acts)
        var index = 0
        while (queue.isNotEmpty()) {
            val act = queue.removeFirst()
            _progress.value = ActProgress(index, script.acts.size + insertedCount, act, ActPhase.PERFORM)
            drainEvents() // 上一幕的迟到事件不污染本幕的等待
            when (act.type) {
                ActTypes.CAMERA -> out(DirectorCmd.Camera(act.shot ?: "group", act.target))
                ActTypes.WAIT -> hold(act.seconds ?: 1f)
                ActTypes.SAY -> performSay(act, cast, out)
                ActTypes.DANCE -> performSolo(act, cast, out, durationOf)
                ActTypes.GROUP_DANCE -> performGroup(act, cast, out)
                ActTypes.BOW -> performBow(act, cast, out, durationOf)
                ActTypes.MOVE -> performMove(act, out, anchorOf)
                ActTypes.MUSIC -> performMusic(act, script, out)
                ActTypes.FX -> performFx(act, out)
            }
            index++
            consumeInsertions(queue)
        }
    }

    /** 当前幕收尾后消费插幕：本批插幕作为接下来的幕（保持受理顺序 FIFO）。 */
    private fun consumeInsertions(queue: ArrayDeque<ShowAct>) {
        val batch = mutableListOf<ShowAct>()
        while (true) {
            batch += insertions.tryReceive().getOrNull() ?: break
        }
        batch.asReversed().forEach { queue.addFirst(it) }
    }

    private fun drainInsertions() {
        while (insertions.tryReceive().isSuccess) { /* 换代/停演丢弃 */ }
    }

    /** 串场词：发 Say → 等该槽收尾（8s 兜底）；观众无感降级（无会话时控制器秒回）。 */
    private suspend fun performSay(act: ShowAct, cast: List<String>, out: (DirectorCmd) -> Unit) {
        val performer = act.performer ?: cast.firstOrNull() ?: return
        out(DirectorCmd.Say(performer, act.text ?: ""))
        val completed = awaitSay(performer, SAY_TIMEOUT_S)
        if (!completed) out(DirectorCmd.Log("say 超时兜底推进: $performer（兜底${SAY_TIMEOUT_S.toInt()}s）"))
    }

    /** 走位（P1）：锚点 → 已在位跳过 / Walk → walkFinished（15s 超时就地开演）。 */
    private suspend fun performMove(
        act: ShowAct,
        out: (DirectorCmd) -> Unit,
        anchorOf: (name: String) -> Pair<Float, Float>?,
    ) {
        val performer = act.performer ?: return
        val spot = act.target?.let(anchorOf) ?: run {
            out(DirectorCmd.Log("move 跳过: 锚点 '${act.target}' 不在表内"))
            return
        }
        act.camera?.let { out(DirectorCmd.Camera(it, performer)) }
        if (!walkTo(performer, spot.first, spot.second, out)) {
            out(DirectorCmd.Log("walk 超时兜底推进: $performer（就位失败，就地开演）"))
        }
    }

    /** 配乐（P1）：start/stop 控播放，duck/full 两档音量（手动 duck act，§4.8）。 */
    private suspend fun performMusic(act: ShowAct, script: ShowScript, out: (DirectorCmd) -> Unit) {
        when (act.action) {
            MusicActions.START -> script.music?.let { out(DirectorCmd.MusicStart(it)) }
                ?: out(DirectorCmd.Log("music start 跳过: 节目单未声明 music"))
            MusicActions.STOP -> out(DirectorCmd.MusicStop)
            MusicActions.DUCK -> out(DirectorCmd.MusicVolume(MusicActions.DUCK_VOLUME, "duck"))
            MusicActions.FULL -> out(DirectorCmd.MusicVolume(MusicActions.BASE_VOLUME, "full"))
        }
    }

    /**
     * 特效插幕（P1 §六）：播 → hold 秒数（可被 skip 提前收）→ 按预设 id 停。
     * 全程不等待任何表演事件——特效是幕间观感件，不打断编舞（burst 型在
     * hold 内自然播完，StopFx 空转无害）。
     */
    private suspend fun performFx(act: ShowAct, out: (DirectorCmd) -> Unit) {
        val preset = act.fx ?: return
        out(DirectorCmd.PlayFx(preset, act.performer, act.bone))
        hold(act.seconds ?: DEFAULT_FX_SECONDS)
        out(DirectorCmd.StopFx(preset))
    }

    /** 独舞：观众席注视+应援 → [line 串场词] → 切机位 → one-shot → 播完/超时 → 走回队列。 */
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
            // 鼓掌并行发射：串场词的 TTS 首包空隙正好被应援填上（§7-P1）
            audience.shuffled(random).take(CLAP_MAX_FANS)
                .forEach { out(DirectorCmd.Clap(it)) }
        }
        act.fans.orEmpty().filter { it == FanActions.SWAY }
            .forEach { _ -> out(DirectorCmd.Log("fans sway 未实现，跳过")) }
        val line = act.line
        if (!line.isNullOrBlank()) {
            out(DirectorCmd.Say(performer, line))
            if (!awaitSay(performer, SAY_TIMEOUT_S)) {
                out(DirectorCmd.Log("say 超时兜底推进: $performer（兜底${SAY_TIMEOUT_S.toInt()}s）"))
            }
        }
        act.camera?.let { out(DirectorCmd.Camera(it, performer)) }
        // 幕次道具（P2）：开演挂、收幕摘（确定性导演语义，§5.6）
        act.props.orEmpty().forEach { out(DirectorCmd.PropOn(performer, it)) }
        out(DirectorCmd.PlayDance(performer, clip, loop = false))
        val duration = durationOf(performer, clip) ?: DEFAULT_DANCE_SECONDS
        val timeoutS = 1.2f * duration + 1f
        val arrived = awaitVrma(performer, timeoutS)
        if (!arrived) {
            out(DirectorCmd.Log("vrma 超时兜底推进: $performer（兜底%.1fs）".format(timeoutS)))
        }
        act.props.orEmpty().forEach { out(DirectorCmd.PropOff(performer, it)) }
        _progress.value = _progress.value.copy(phase = ActPhase.RETURN)
        walkBackToMark(performer, out)
        audience.forEach { out(DirectorCmd.GazeCamera(it)) }
    }

    /** 收尾回位（§4.7 P1 版）：走回队列 mark（15s 超时）+ 转回镜头方向。 */
    private suspend fun walkBackToMark(performer: String, out: (DirectorCmd) -> Unit) {
        val mark = marks[performer]
        if (mark == null) {
            out(DirectorCmd.Log("warn: $performer 无回位 mark，就地收"))
        } else {
            if (!walkTo(performer, mark.first, mark.second, out)) {
                out(DirectorCmd.Log("walk 超时兜底推进: $performer 回位（兜底${WALK_TIMEOUT_S.toInt()}s）"))
            }
        }
        out(DirectorCmd.FaceCamera(performer))
    }

    /** 齐舞：全员同帧 loop 起播（舞台级 VRMA 缓存），到点统一 stop 回 idle。 */
    private suspend fun performGroup(act: ShowAct, cast: List<String>, out: (DirectorCmd) -> Unit) {
        val clip = act.clip ?: return
        act.camera?.let { out(DirectorCmd.Camera(it, null)) }
        // 幕次道具（P2）：全员挂上（齐舞无主次）
        act.props.orEmpty().forEach { id -> cast.forEach { out(DirectorCmd.PropOn(it, id)) } }
        cast.forEach { out(DirectorCmd.PlayDance(it, clip, loop = true)) }
        hold(act.seconds ?: DEFAULT_GROUP_SECONDS)
        act.props.orEmpty().forEach { id -> cast.forEach { out(DirectorCmd.PropOff(it, id)) } }
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
        // 幕次道具（P2）：谢幕人手一支应援棒（default.json 的 bow act 声明）
        act.props.orEmpty().forEach { id -> cast.forEach { out(DirectorCmd.PropOn(it, id)) } }
        cast.forEach { out(DirectorCmd.PlayDance(it, BOW_CLIP, loop = false)) }
        val duration = cast.firstOrNull()?.let { durationOf(it, BOW_CLIP) } ?: DEFAULT_DANCE_SECONDS
        hold(1.2f * duration + 0.5f)
        act.props.orEmpty().forEach { id -> cast.forEach { out(DirectorCmd.PropOff(it, id)) } }
    }

    // ── 等待原语（事件或 skip 提前收，超时也是推进信号）──────────────────

    private suspend fun awaitVrma(avatarId: String, timeoutSeconds: Float): Boolean =
        waitSignal((timeoutSeconds * 1000).toLong()) {
            it is Event.Skip || (it is Event.VrmaFinished && it.avatarId == avatarId)
        }

    /** 等一次走动收尾（arrived/cancelled 都算收——取消也不卡场，只推进）。 */
    private suspend fun awaitWalk(avatarId: String, timeoutSeconds: Float): Boolean =
        waitSignal((timeoutSeconds * 1000).toLong()) {
            it is Event.Skip || (it is Event.WalkFinished && it.avatarId == avatarId)
        }

    /** 等该槽串场词收尾（TurnCompleted/失败/被打断都算收）。 */
    private suspend fun awaitSay(avatarId: String, timeoutSeconds: Float): Boolean =
        waitSignal((timeoutSeconds * 1000).toLong()) {
            it is Event.Skip || (it is Event.SayCompleted && it.avatarId == avatarId)
        }

    /**
     * 走位原语：已在位（Δ<[WALK_EPS_M]）直接算到位不发指令；否则发 Walk 等
     * 收尾（15s 超时也算收——取消/丢事件都不卡场，只推进）。
     */
    private suspend fun walkTo(
        performer: String,
        x: Float,
        z: Float,
        out: (DirectorCmd) -> Unit,
    ): Boolean {
        val cur = positionOf(performer)
        if (cur != null) {
            val dx = x - cur.first
            val dz = z - cur.second
            if (dx * dx + dz * dz < WALK_EPS_M * WALK_EPS_M) {
                out(DirectorCmd.Log("$performer 已在位（Δ%.2fm，免走）".format(sqrt(dx * dx + dz * dz))))
                return true
            }
        }
        out(DirectorCmd.Walk(performer, x, z))
        return awaitWalk(performer, WALK_TIMEOUT_S)
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

        /** fx 幕缺省时长（秒；特效播完即收，节目单可用 seconds 覆盖）。 */
        const val DEFAULT_FX_SECONDS = 3f

        /** 串场词收尾兜底（§4.6：TTS 首包 1~2s + 句长余量）。 */
        const val SAY_TIMEOUT_S = 8f

        /** 走位收尾兜底（§4.6/§7-P1：超时就地开演，P3 编排同款纪律）。 */
        const val WALK_TIMEOUT_S = 15f

        /** 走位就位判定（米）：小于此距离不再起步直接算到位（防 walkFinished 空等）。 */
        const val WALK_EPS_M = 0.2f
    }
}
