package com.neethu.aiavatar_sdk.debate

import android.util.Log
import com.neethu.corelib.stage.AvatarHandle
import com.neethu.corelib.stage.AvatarStage
import com.neethu.orchestrator.debate.DebateIds
import com.neethu.orchestrator.debate.DebatePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 辩论镜头导演（docs/debate-feasibility.md §8）：比赛事件 → 自动取景，
 * 手动命令可锁定（hold_ms 临时接管，到期按当下状态重算，不回放过期队列）。
 * 优先级：手动锁定 > 比赛事件；目标签名相同不重复下发，避免每句甩镜头。
 *
 * 自动取景规则由 [autoSpecProvider] 注入（rig 读引擎状态快照生成），本类
 * 只管仲裁与下发——与舞台能力（特写锚定 / 显式名单拟合同框）一一对应。
 */
class DebateCameraDirector(
    private val stage: AvatarStage,
    private val scope: CoroutineScope,
) {
    private val TAG = "DebateCamera"

    data class ShotSpec(
        val kind: Kind,
        val targetId: String? = null,
        val targets: List<String> = emptyList(),
    ) {
        enum class Kind { SPEAKER, TEAM, HOST, WIDE, DUEL }

        fun signature(): String = "$kind:${targetId ?: targets.joinToString(",")}"
    }

    /** 自动取景规则（rig 注入：按引擎当前回合/阶段生成；null = 无可取景）。 */
    var autoSpecProvider: (() -> ShotSpec?)? = null

    /** 业务 ID → 句柄解析（rig 注入；舞台句柄 id 是自动分配的，不能混用）。 */
    var handleResolver: ((String) -> AvatarHandle?)? = null

    private var manualLock: ManualLock? = null
    private var holdJob: Job? = null
    private var lastSignature: String = ""

    private class ManualLock(val spec: ShotSpec, val sticky: Boolean)

    /** 手动锁定镜头。不改变发言权；holdMs 非空 = 临时接管。 */
    fun lock(spec: ShotSpec, holdMs: Long?) {
        holdJob?.cancel()
        holdJob = null
        manualLock = ManualLock(spec, sticky = holdMs == null)
        apply(spec, force = true)
        if (holdMs != null && holdMs > 0) {
            holdJob = scope.launch {
                delay(holdMs)
                manualLock = null
                Log.i(TAG, "手动镜头 hold_ms 到期，恢复自动")
                refreshAuto(force = true)
            }
        }
    }

    /** camera_auto：解除人工接管，按当下实际状态重新计算。 */
    fun auto() {
        holdJob?.cancel()
        holdJob = null
        manualLock = null
        refreshAuto(force = true)
    }

    fun reset() {
        holdJob?.cancel()
        holdJob = null
        manualLock = null
        lastSignature = ""
    }

    /** 比赛事件入口（回合开始 / 阶段切换 / 攻辩问答交接）。 */
    fun onMatchEvent() {
        refreshAuto()
    }

    private fun refreshAuto(force: Boolean = false) {
        if (!force && manualLock != null) return
        val spec = autoSpecProvider?.invoke() ?: return
        apply(spec, force = force)
    }

    private fun apply(spec: ShotSpec, force: Boolean = false) {
        val signature = spec.signature()
        if (!force && signature == lastSignature) return
        lastSignature = signature
        val resolve = handleResolver
        when (spec.kind) {
            ShotSpec.Kind.SPEAKER -> resolve?.invoke(spec.targetId ?: return)?.let {
                stage.setGroupShotFitted(listOf(it))
            }
            ShotSpec.Kind.HOST -> resolve?.invoke(DebateIds.HOST)?.let {
                stage.setGroupShotFitted(listOf(it))
            }
            ShotSpec.Kind.TEAM, ShotSpec.Kind.WIDE, ShotSpec.Kind.DUEL -> {
                val handles = spec.targets.mapNotNull { resolve?.invoke(it) }
                if (handles.size >= 2) stage.setGroupShotFitted(handles)
            }
        }
    }
}
