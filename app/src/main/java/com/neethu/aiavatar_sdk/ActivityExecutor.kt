package com.neethu.aiavatar_sdk

import android.content.Context
import android.util.Log
import com.neethu.aiavatar_sdk.multiavatar.AccessoryCatalog
import com.neethu.aiavatar_sdk.multiavatar.VfxCatalog
import com.neethu.corelib.AvatarController
import com.neethu.corelib.ContactPosePhase
import com.neethu.corelib.WalkPhase
import com.neethu.corelib.accessory.AccessorySpec
import com.neethu.corelib.accessory.AccessoryWorldMount
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
 * P1 §6.2 增量：配方 `props` 里的 `{"mount":"world"}` 世界摆件（瑜伽垫/茶具
 * 组）没有动画可绑、不经绑定表——本执行器按其锚点坐标挂到世界（就位前先
 * 摆好），活动收场（stop/打断/播完/顶替）一律收回。
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

    /**
     * 当前活动挂上的世界摆件 id（§6.2：瑜伽垫/茶具组按场景锚点摆世界坐标，
     * 无动画可绑不走绑定表——活动开始出现、结束/打断由本执行器收起）。
     */
    private val worldProps = mutableListOf<String>()

    /**
     * 当前活动播的特效（§2.2 可选联动层）：句柄 id（playVfx 返回）+ preset
     * id（句柄拿不到时兜底扫）。活动收场（stop/打断/播完/失败）一律停——
     * P1 真机验收①的教训：只播不停，magic_circle/rain/petals 永久残留。
     */
    private var playedVfxHandleId: String? = null
    private var playedVfxPresetId: String? = null

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
        // 道具经绑定表自动换手;旧活动的世界摆件与特效一并收回（§6.2/§2.2
        // 生命周期随活动,不随剪辑）——特效句柄不在这里停,会随下方 runActivity
        // 的登记覆写永久丢失（P1 真机验收 r2:umbrella_rain 被顶替后 fx8 rain
        // 无限续命）。
        watchJob?.cancel()
        watchJob = null
        detachWorldProps()
        stopPlayedVfx()
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
        // 0. 世界摆件先上（§6.2）：瑜伽垫先铺地上、茶具先摆桌上,人就位时道具
        //    已在场。锚点在当前场景交互点表里查（无此锚点 = 该场景不摆,记日志
        //    降级——活动本体照跑）。
        attachWorldProps(entry)
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
        // 1.5 等接触定姿对齐完再上配方（P1 真机验收⑤的根因修复）：就位链在
        //     applyContactPose 返回 STARTED 即结束,此时 SIT/LIE 还在 ENTERING
        //     （坐姿过渡 one-shot 在播）——立刻播活动的循环剪辑会把 one-shot 顶
        //     出 base 槽,定姿机 `isOneShotActive()`（循环剪辑也满足）永真,
        //     ENTERING 永不前进（真机卡 116s 不对齐）。
        awaitPoseAligned()
        // 2. 动画：道具显隐走绑定表（VrmaStarted → 出道具），编排器零感知。
        //    例外：锚点定姿引擎已在播同款坐姿循环（配方动画 == 锚点
        //    sitLoopClip,如 reading/meditate）时不重播——重播只会把剪辑拨回
        //    首帧,道具经定姿侧的 VrmaStarted 照常出场。
        val anchorEntry = anchor?.let { a -> coordinator.anchors.firstOrNull { it.id == a } }
        val poseAlreadyPlayingLoop = controller.getContactPoseState()?.aligned == true &&
            clipMatchesAnchorLoop(entry.animation, anchorEntry?.sitLoopClip)
        if (!poseAlreadyPlayingLoop) {
            if (!controller.loadVrmaAnimation("animations/${entry.animation}")) {
                Log.w(TAG, "activity '${entry.id}' animation load failed: ${entry.animation}")
                clearCurrent()
                return
            }
            controller.playVrmaAnimation(loop = entry.loop)
        } else {
            Log.i(TAG, "activity '${entry.id}' reuses anchor sit-loop clip (${entry.animation})")
        }
        Log.i(TAG, "activity '${entry.id}' started (anchor=${entry.anchor} loop=${entry.loop})")
        // 3. 可选 VFX（preset 缺省锚 = 骨优先/角色实时位，同 fx play 口径）；
        //    登记句柄,活动收场由 [stopPlayedVfx] 统一停
        entry.vfx?.let { vfxId ->
            val preset = VfxCatalog.find(context, vfxId)
            if (preset == null) {
                Log.w(TAG, "activity '${entry.id}' vfx '$vfxId' not in catalog (skipped)")
            } else {
                playedVfxPresetId = preset.id
                playedVfxHandleId = controller
                    .playVfx(preset, FxTools.defaultAnchor(controller, preset))?.id
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

    /**
     * 等接触定姿对齐完成（HOLDING）再上配方。非接触锚（stand/原地）或已被
     * 起身 = 无对齐可等,立即返回。超时兜底放行（退回老行为——播了总比卡死
     * 活动链好）,不无限挂起。
     */
    private suspend fun awaitPoseAligned(timeoutMs: Long = POSE_ALIGN_TIMEOUT_MS) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pose = controller.getContactPoseState() ?: return
            if (pose.phase == ContactPosePhase.HOLDING && pose.aligned) return
            delay(120)
        }
        Log.w(TAG, "contact pose not aligned in ${timeoutMs}ms — proceeding anyway")
    }

    /** 活动收场停特效：句柄 id 直停；无句柄（批容量满/渲染未就绪）按 presetId 扫在场特效兜底。 */
    private fun stopPlayedVfx() {
        val handle = playedVfxHandleId
        val preset = playedVfxPresetId
        playedVfxHandleId = null
        playedVfxPresetId = null
        if (handle == null && preset == null) return
        if (handle != null && controller.stopVfx(handle)) {
            Log.i(TAG, "activity vfx stopped: $handle")
            return
        }
        // 句柄缺失或已失效（渲染层重建）→ 按 preset 兜底扫,防残留
        val strays = controller.effects.value.filter { it.presetId == preset }.map { it.id }
        for (id in strays) controller.stopVfx(id)
        if (strays.isNotEmpty()) Log.i(TAG, "activity vfx stopped by preset scan: $strays")
    }

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
        detachWorldProps()
        stopPlayedVfx()
    }

    // ── 世界摆件（§6.2 地面/场景道具）────────────────────────────────────

    /**
     * 活动配方里的 `{"mount":"world"}` 道具按锚点坐标挂到世界：目录三层合并
     * 出规格（模板/校准同链,局部微调=锚点之上偏移）,再叠锚点 TRS（交互点表
     * 坐标,SDK 世界米）。同一活动重复启动先清残留（顶替语义同骨骼道具）。
     */
    private fun attachWorldProps(entry: ActivityEntry) {
        val modelId = controller.currentModelId ?: return
        for (prop in entry.props.filter { it.isWorld }) {
            val anchorId = prop.anchor?.lowercase()
            val anchor = coordinator.anchors.firstOrNull { it.id == anchorId }
            if (anchor == null) {
                Log.i(TAG, "activity '${entry.id}' world prop '${prop.id}' anchor '${prop.anchor}' not in this scene (skipped)")
                continue
            }
            val catalogEntry = AccessoryCatalog.find(context, prop.id)
            if (catalogEntry == null) {
                Log.w(TAG, "activity '${entry.id}' world prop '${prop.id}' not in accessory catalog (skipped)")
                continue
            }
            controller.detachAccessory(prop.id)
            val spec = AccessoryCatalog.specFor(context, modelId, catalogEntry).copy(
                world = AccessoryWorldMount(
                    x = anchor.x,
                    y = anchor.y,
                    z = anchor.z,
                    yawDeg = anchor.yawDeg,
                ),
            )
            controller.attachAccessory(spec).onSuccess {
                worldProps += prop.id
                Log.i(TAG, "activity '${entry.id}' world prop '${prop.id}' @${anchor.id}(${anchor.x},${anchor.y},${anchor.z} yaw=${anchor.yawDeg})")
            }.onFailure {
                Log.w(TAG, "activity '${entry.id}' world prop '${prop.id}' attach failed: ${it.message}")
            }
        }
    }

    /** 活动收场（stop/打断/one-shot 播完/失败）收回世界摆件。 */
    private fun detachWorldProps() {
        if (worldProps.isEmpty()) return
        for (id in worldProps) controller.detachAccessory(id)
        Log.i(TAG, "world props detached: $worldProps")
        worldProps.clear()
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

        /** 等接触定姿对齐的上限（转身 0.35s+过渡剪辑 ~2s+吸附 0.35s 的数倍余量）。 */
        private const val POSE_ALIGN_TIMEOUT_MS = 15_000L
    }
}

/**
 * 锚点定姿引擎已在播的坐姿循环剪辑与配方动画是否同款（P1 真机验收⑤的
 * 不重播判据）：双方统一剥 `animations/` 前缀精确比较（binder 归一同款）。
 */
internal fun clipMatchesAnchorLoop(animation: String, sitLoopClip: String?): Boolean {
    if (sitLoopClip == null) return false
    return animation.removePrefix("animations/") == sitLoopClip.removePrefix("animations/")
}

/**
 * 活动收场的 VFX 停止目标（纯函数,JVM 可测）：句柄在 = 只停句柄（精确,
 * 不误伤同 preset 的其他实例——如手动 fx play 的同款）;句柄缺失（批容量满/
 * 渲染未就绪）= 按 presetId 扫在场特效兜底防残留;都没播 = 空。
 */
internal fun activityVfxStopTargets(
    presetId: String?,
    handleId: String?,
    active: List<com.neethu.corelib.vfx.VfxInfo>,
): List<String> {
    if (handleId != null) return listOf(handleId)
    if (presetId == null) return emptyList()
    return active.filter { it.presetId == presetId }.map { it.id }
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
                        (e.hourRange?.let { " hours=${it.first}..${it.last}" } ?: "") +
                        (e.props.filter { it.isWorld }.joinToString("") { " world=${it.id}@${it.anchor}" })
                }
            }
        }
        "state" -> executor.stateLine()
        "last", "log" -> executor.historyDump()
        else -> "usage: activity <list|state|last>"
    }
}
