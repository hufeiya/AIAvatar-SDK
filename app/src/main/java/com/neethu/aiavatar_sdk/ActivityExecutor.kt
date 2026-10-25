package com.neethu.aiavatar_sdk

import android.content.Context
import android.util.Log
import com.neethu.aiavatar_sdk.multiavatar.VfxCatalog
import com.neethu.corelib.AvatarController
import com.neethu.corelib.WalkPhase
import com.neethu.orchestrator.activity.ActivityCatalog
import com.neethu.orchestrator.activity.ActivityEntry
import com.neethu.orchestrator.agent.ActivityFailure
import com.neethu.orchestrator.agent.AgentToolCatalog
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 活动执行器（P0 道具系统，docs/prop-system-feasibility.md §2.2）：把活动
 * 目录的一条「行为配方」落到既有积木上——可选 anchor 走 [InteractionCoordinator]
 * 就位链 → 播动画（**道具显隐零新代码**：`AccessoryAnimationBinder` 查
 * bindings.json，动画起来道具自动出现）→ 可选 VFX → 可选台词 `speak()`。
 *
 * 与既有执行互斥同锁：就位链忙/自身忙 → [ActivityFailure.BUSY]（就位链也经
 * [InteractionCoordinator.busyProbe] 反向探测本执行器）。loop 活动 = base 槽
 * 循环剪辑，FSM 记为外部活动态不回弹（[holdsExternalState]，AutonomyRig 回喂
 * recentExternalAction 冻结决策——被叫住时书还在手里）；接触姿被清/走动顶替
 * 即打断收尾。one-shot 活动播完自动收（VrmaFinished → 道具自动收起）。
 *
 * 全部在 MainActivity 主线程 scope 上跑（与 InteractionCoordinator 同侧，
 * 渲染层调用不需要额外切线程）。
 */
internal class ActivityExecutor(
    private val context: Context,
    private val controller: AvatarController,
    private val sessionProvider: () -> AvatarSession?,
    private val coordinator: InteractionCoordinator,
    private val scope: CoroutineScope,
) {

    /** 活动目录（MainActivity 从 assets 读出注入；缺/脏 = EMPTY 功能静默缺席）。 */
    var catalog: ActivityCatalog = ActivityCatalog.EMPTY

    private var job: Job? = null
    private var watchJob: Job? = null

    /** 配方链占用（就位→动画→VFX→台词,不含 loop 看守——看守不挡新指令）。 */
    val isBusy: Boolean get() = job?.isActive == true

    /** 当前活动（执行中 / loop 持续中）；null = 空闲。 */
    var currentId: String? = null
        private set

    private var currentEntry: ActivityEntry? = null
    private var startedAtMs = 0L
    private var startedExternally = false

    /** 最近 8 条启动/停止/打断记录（ai_cmd `activity last` 正本）。 */
    private val history = ArrayDeque<String>()

    // ── 启动 / 停止 ─────────────────────────────────────────────────────

    /**
     * 启动一个活动（异步起步：就位+配方在协程里跑完，起步即返回）。
     * [fromAutonomy]=true 是 FSM `DoActivity` 路径——GoTo 就位链已在跑，
     * 等它完成后直接上配方（不重复就位、不因就位链忙判 BUSY）。
     */
    fun start(id: String, fromAutonomy: Boolean = false): ActivityFailure? {
        val entry = catalog[id] ?: return ActivityFailure.UNKNOWN_ACTIVITY
        if (controller.currentModelId == null) return ActivityFailure.NO_MODEL
        if (isBusy) return ActivityFailure.BUSY
        if (!fromAutonomy && coordinator.isBusy) return ActivityFailure.BUSY
        // 顶替语义：旧 loop 活动的看守必须先撤（否则它会在新活动播起来后
        // 误判打断、把新活动杀掉）;旧循环剪辑由新 playVrmaAnimation 顶替,
        // 道具经绑定表自动换手。
        watchJob?.cancel()
        watchJob = null
        record(entry, if (fromAutonomy) "autonomy" else "external")
        currentId = entry.id
        currentEntry = entry
        startedAtMs = System.currentTimeMillis()
        startedExternally = !fromAutonomy
        job = scope.launch {
            try {
                if (fromAutonomy) awaitCoordinatorIdle()
                runActivity(entry, skipAnchor = fromAutonomy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "activity '${entry.id}' failed", e)
                clearCurrent()
            }
        }
        return null
    }

    /**
     * 停止当前活动（幂等，无活动 = 空操作）：loop 停动画（道具经绑定表自动
     * 收起），坐/躺中起身（现成起身链），FSM 回 IDLE_STAND。
     */
    fun stop(): ActivityFailure? {
        val entry = currentEntry ?: return null
        history.addLast(stamp("stop ${entry.id}"))
        trimHistory()
        job?.cancel()
        job = null
        watchJob?.cancel()
        watchJob = null
        if (entry.loop) controller.stopVrmaAnimation()
        if (controller.getContactPoseState() != null) coordinator.clearPose()
        clearCurrent()
        Log.i(TAG, "activity '${entry.id}' stopped")
        return null
    }

    /** LLM 工具观测投影（AgentActivityTools 的 stop 当前态）。 */
    fun currentChoice(): AgentToolCatalog.ActivityChoice? {
        val entry = currentEntry ?: return null
        return AgentToolCatalog.ActivityChoice(entry.id, entry.name, entry.desc, entry.category)
    }

    /**
     * FSM「外部活动态」判定（§3.1/autonomy §4.3 优先级 3）：LLM/用户启动的
     * loop 活动未结束前，FSM 观望不决策不回弹（recentExternalAction 持续为真）。
     * FSM 自己发起的活动不算（dwell/minDwellMs 管它的生命周期）。
     */
    fun holdsExternalState(): Boolean = currentId != null && startedExternally

    // ── 执行链（§2.2）───────────────────────────────────────────────────

    private suspend fun runActivity(entry: ActivityEntry, skipAnchor: Boolean) {
        // 1. 就位（可选）：FSM GoTo 已就过位（skipAnchor）或已在该锚上则跳过。
        //    借道就位链绕开反向互斥探测（本执行器已自行判过锁,防自锁）。
        val anchor = entry.anchor
        if (anchor != null && !skipAnchor && !seatedAt(anchor)) {
            val result = coordinator.interact(anchor, ignoreBusyProbe = true)
            if (!result.startsWith("interaction")) {
                Log.w(TAG, "activity '${entry.id}' anchor '$anchor' refused: $result")
                clearCurrent()
                return
            }
            awaitCoordinatorIdle()
        }
        // 2. 动画：道具显隐走绑定表（VrmaStarted → 出道具），编排器零感知
        if (!controller.loadVrmaAnimation("animations/${entry.animation}")) {
            Log.w(TAG, "activity '${entry.id}' animation load failed: ${entry.animation}")
            clearCurrent()
            return
        }
        controller.playVrmaAnimation(loop = entry.loop)
        Log.i(TAG, "activity '${entry.id}' started (anchor=${entry.anchor} loop=${entry.loop})")
        // 3. 可选 VFX（preset 缺省锚 = 骨优先/角色实时位，同 fx play 口径）
        entry.vfx?.let { vfxId ->
            val preset = VfxCatalog.find(context, vfxId)
            if (preset == null) {
                Log.w(TAG, "activity '${entry.id}' vfx '$vfxId' not in catalog (skipped)")
            } else {
                controller.playVfx(preset, FxTools.defaultAnchor(controller, preset))
            }
        }
        // 4. 可选台词：免 LLM 开口（不进对话历史），可带 <emo:>/<act:> 标签
        entry.says.randomOrNull()?.let { line ->
            runCatching { sessionProvider()?.speak(line) }
                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "says failed", it) }
        }
        // 5. 持续态看守（独立轻量协程,不占执行锁——外部就位/走动指令照常进来,
        //    它们打断活动反而正是期望语义）;one-shot 播完即算结束。
        if (entry.loop) startInterruptionWatcher(entry) else clearCurrent()
    }

    /** 已在该锚的接触位上（重复启动/坐定后再玩手机的判据）。 */
    private fun seatedAt(anchorId: String): Boolean =
        controller.getContactPoseState() != null && coordinator.lastAnchorId == anchorId

    /** 等就位链跑完（链路本身 45s 超时,这里只防挂死）。 */
    private suspend fun awaitCoordinatorIdle(timeoutMs: Long = 60_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (coordinator.isBusy && System.currentTimeMillis() < deadline) delay(250)
    }

    /**
     * loop 活动看守：坐/躺锚的活动被起身打断,无锚活动被走动顶替 → 停动画、
     * 清状态（道具由绑定表在剪辑收尾路径自动收起）。前 [GRACE_MS] 不判
     * （就位链的转身/过渡剪辑窗口,接触姿可能还没挂上）。
     */
    private fun startInterruptionWatcher(entry: ActivityEntry) {
        val anchoredContact = entry.anchor != null &&
            coordinator.anchors.firstOrNull { it.id == entry.anchor }?.isContactPose == true
        watchJob?.cancel()
        watchJob = scope.launch {
            try {
                delay(GRACE_MS)
                while (isActive) {
                    delay(1_000)
                    val interrupted = if (anchoredContact) {
                        controller.getContactPoseState() == null
                    } else {
                        controller.getWalkState()?.let { it.phase != WalkPhase.IDLE } == true
                    }
                    if (interrupted) {
                        Log.i(TAG, "activity '${entry.id}' interrupted (pose cleared / walking)")
                        controller.stopVrmaAnimation()
                        history.addLast(stamp("interrupt ${entry.id}"))
                        trimHistory()
                        clearCurrent()
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "watcher for '${entry.id}' died", e)
            }
        }
    }

    private fun clearCurrent() {
        currentId = null
        currentEntry = null
        startedExternally = false
    }

    private fun record(entry: ActivityEntry, source: String) {
        history.addLast(stamp("start ${entry.id} ($source)"))
        trimHistory()
    }

    private fun stamp(line: String): String =
        "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $line"

    private fun trimHistory() {
        while (history.size > HISTORY_MAX) history.removeFirst()
    }

    // ── 观测（ai_cmd activity）──────────────────────────────────────────

    /** `activity state` 的一行快照。 */
    fun stateLine(): String {
        val entry = currentEntry
            ?: return "activity=none (catalog=${catalog.entries.size})${history.lastOrNull()?.let { " last: [$it]" } ?: ""}"
        val secs = (System.currentTimeMillis() - startedAtMs) / 1000
        return "activity=${entry.id}(${entry.name}) loop=${entry.loop} ${secs}s " +
            "source=${if (startedExternally) "external" else "autonomy"} " +
            "executor=${if (isBusy) "running" else "settled"} catalog=${catalog.entries.size}"
    }

    /** `activity last`：启动/停止/打断的最近记录（新在上）。 */
    fun historyDump(): String =
        history.reversed().joinToString("\n").ifEmpty { "no activity records yet" }

    companion object {
        private const val TAG = "ActivityExecutor"
        private const val HISTORY_MAX = 8

        /** 看守宽限：就位链转身+过渡剪辑窗口（接触姿未挂上不算打断）。 */
        private const val GRACE_MS = 10_000L
    }
}

private const val ACTIVITIES_ASSET = "activities/activities.json"

/** `assets/activities/activities.json` → 活动目录（缺/脏 = EMPTY 静默缺席）。 */
internal fun loadActivityCatalog(context: Context): ActivityCatalog = runCatching {
    context.assets.open(ACTIVITIES_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
}.map { ActivityCatalog.parse(it) }.getOrDefault(ActivityCatalog.EMPTY)

/** `ai_cmd activity <list|state|last>`（只读观测,不改决策逻辑）。 */
internal fun activityDebugCommand(executor: ActivityExecutor, arg: String?): String {
    return when (arg?.trim()?.lowercase()) {
        null, "", "list" -> {
            val entries = executor.catalog.entries
            if (entries.isEmpty()) {
                "activities catalog empty (assets/activities/activities.json missing or unparsable)"
            } else {
                entries.joinToString("\n") { e ->
                    "- ${e.id} [${e.category}] ${e.name}: ${e.desc}" +
                        (e.anchor?.let { " @$it" } ?: "") +
                        " loop=${e.loop}" +
                        (e.vfx?.let { " vfx=$it" } ?: "") +
                        (e.hourRange?.let { " hours=${it.first}..${it.last}" } ?: "")
                }
            }
        }
        "state" -> executor.stateLine()
        "last", "log" -> executor.historyDump()
        else -> "usage: activity <list|state|last>"
    }
}
