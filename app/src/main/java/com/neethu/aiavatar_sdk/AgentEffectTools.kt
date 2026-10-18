package com.neethu.aiavatar_sdk

import android.content.Context
import com.neethu.aiavatar_sdk.multiavatar.VfxCatalog
import com.neethu.corelib.AvatarController
import com.neethu.corelib.vfx.VfxAnchor
import com.neethu.orchestrator.agent.AgentToolCatalog
import com.neethu.orchestrator.agent.EffectPlayFailure
import com.neethu.orchestrator.i18n.PromptTexts
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P2 特效工具的 app 侧接线（docs/vfx-feasibility.md §六 P2）：把 play_effect /
 * stop_effect 挂上会话注册表，缝体落到单人 [AvatarController]（VfxCatalog
 * 三层合并出规格）。与 [AgentAccessoryTools] 同款纪律——注册路径不触碰
 * context，执行时统一 Dispatchers.Main（playVfx 是渲染层主线程操作），失败
 * 归因成缝枚举由 PromptTexts 双语文案回灌，工具失败永不炸回合。
 *
 * 锚点语义（`at` 枚举，P0 坑 B 口径——世界原点会在取景框外）：
 * - around_me：角色脚底实时位（自由走动后跟人走）；
 * - stage_center：舞台中心 (0,0,-4)（单人 MODEL_CENTER / 团播舞台中心同口径）；
 * - camera_front：VfxAnchor.CameraFront（渲染层每帧 eye+forward 解析）；
 * - position 显式坐标覆盖 at；都不给 = FxTools.defaultAnchor（预设缺省骨优先）。
 *
 * 开关：`fxTools`（**默认关**——特效是观感面，模型滥调会刷屏，用户显式开），
 * 且跟随 `agentTools` 总开关；关闭语义 = 两工具退场。
 */
object AgentEffectTools {

    private const val PLAY = "play_effect"
    private const val STOP = "stop_effect"

    /** 舞台中心世界坐标（单人 MODEL_CENTER / 团播 fx 缺省锚同口径）。 */
    private val STAGE_CENTER = VfxAnchor.World(0f, 0f, -4f)

    /** 按当前偏好注册/反注册（幂等，同 AgentAccessoryTools.register 惯例）。 */
    fun register(
        session: AvatarSession,
        controller: AvatarController,
        context: Context?,
        texts: PromptTexts,
        prefs: AiChatPrefs,
    ) {
        if (!prefs.agentTools || !prefs.fxTools) {
            session.tools.unregister(PLAY)
            session.tools.unregister(STOP)
            return
        }
        if (session.tools.find(PLAY) == null) {
            AgentToolCatalog.playEffect(
                texts,
                effectIds = { context?.let { VfxCatalog.all(it).keys.toList() } ?: emptyList() },
                play = { effect, at, position -> play(context, controller, effect, at, position) },
            ).let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        if (session.tools.find(STOP) == null) {
            AgentToolCatalog.stopEffect(
                texts,
                stop = { stop(controller) },
            ).let { (spec, exec) -> session.tools.register(spec, exec) }
        }
    }

    /** 播放缝：目录查 id → 锚点语义 → 主线程 playVfx。null = 成功。 */
    private suspend fun play(
        context: Context?,
        controller: AvatarController,
        effect: String,
        at: String?,
        position: FloatArray?,
    ): EffectPlayFailure? = withContext(Dispatchers.Main) {
        val ctx = context ?: return@withContext EffectPlayFailure.NOT_AVAILABLE
        val spec = VfxCatalog.find(ctx, effect) ?: return@withContext EffectPlayFailure.UNKNOWN_EFFECT
        val anchor = when {
            position != null -> VfxAnchor.World(position[0], position[1], position[2])
            at == "stage_center" -> STAGE_CENTER
            at == "camera_front" -> VfxAnchor.CameraFront()
            else -> FxTools.defaultAnchor(controller, spec) // around_me/缺省：预设骨优先，退角色实时位
        }
        if (controller.playVfx(spec, anchor) == null) EffectPlayFailure.NOT_AVAILABLE else null
    }

    /** 停止缝：全停（stop 是安全动作，幂等成功）。 */
    private suspend fun stop(controller: AvatarController) = withContext(Dispatchers.Main) {
        controller.stopAllVfx()
    }
}
