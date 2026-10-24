package com.neethu.aiavatar_sdk

import android.content.Context
import com.neethu.corelib.AvatarController
import com.neethu.orchestrator.agent.AgentToolCatalog
import com.neethu.orchestrator.i18n.PromptTexts
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 天空盒工具的 app 侧接线（docs/skybox.md）：把 `set_skybox` 挂上会话注册
 * 表，id 枚举来自 [SkyboxCatalog]（assets/skybox 目录扫描），执行时统一
 * Dispatchers.Main（渲染层主线程操作），失败归因文案回灌，工具失败永不
 * 炸回合——与 [AgentEffectTools] 同款纪律。
 *
 * 开关：只跟 `agentTools` 总开关（无独立子开关——换背景是低频良性动作，
 * 不同于特效的刷屏风险，fxTools 式的显式开关不需要）。关闭语义 = 工具退场。
 */
object AgentSkyboxTools {

    private const val SET = "set_skybox"

    /** 按当前偏好注册/反注册（幂等，同 AgentEffectTools.register 惯例）。 */
    fun register(
        session: AvatarSession,
        controller: AvatarController,
        context: Context?,
        texts: PromptTexts,
        prefs: AiChatPrefs,
    ) {
        if (!prefs.agentTools) {
            session.tools.unregister(SET)
            return
        }
        if (session.tools.find(SET) == null) {
            AgentToolCatalog.setSkybox(
                texts,
                skyboxIds = { context?.let { SkyboxCatalog.ids(it) } ?: emptyList() },
                set = { id -> set(context, controller, id) },
            ).let { (spec, exec) -> session.tools.register(spec, exec) }
        }
    }

    /** 执行缝：id → 目录解析 → 主线程 setSkybox；null = off。true = 已生效。 */
    private suspend fun set(context: Context?, controller: AvatarController, id: String?): Boolean =
        withContext(Dispatchers.Main) {
            if (id == null) {
                controller.clearSkybox()
                true
            } else {
                val ctx = context ?: return@withContext false
                val file = SkyboxCatalog.resolve(ctx, id) ?: return@withContext false
                controller.setSkybox("skybox/$file")
            }
        }
}
