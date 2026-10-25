package com.neethu.aiavatar_sdk

import com.neethu.corelib.Lang
import com.neethu.orchestrator.agent.AgentToolCatalog
import com.neethu.orchestrator.activity.ActivityEntry
import com.neethu.orchestrator.i18n.PromptTexts
import com.neethu.orchestrator.session.AvatarSession

/**
 * P0 活动工具的 app 侧接线（docs/prop-system-feasibility.md §2.3）：把
 * start_activity / stop_activity 挂上会话注册表，缝体落到 [ActivityExecutor]
 * （就位链→动画→VFX→台词→FSM 外部活动态）。与 [AgentAccessoryTools] 同款
 * 纪律——id 白名单 schema/执行双保险（目录经 [AgentToolCatalog.ActivityChoice]
 * 投影注入，orchestrator 不感知资产表），活动目录摘要随 systemHint 注入，
 * 失败归因成缝枚举由 PromptTexts 双语文案回灌，工具失败永不炸回合。
 *
 * 开关：`activityTools`（默认开——「做一件事」是对话自然延伸，无隐私面）
 * 且跟随 `agentTools` 总开关；关闭语义 = 两工具退场（FSM 作息活动不受影响，
 * 那是 autonomy 总开关管的）。
 */
internal object AgentActivityTools {

    private const val START = "start_activity"
    private const val STOP = "stop_activity"

    /** 按当前偏好注册/反注册（幂等，同 AgentAccessoryTools.register 惯例）。 */
    fun register(
        session: AvatarSession,
        texts: PromptTexts,
        prefs: AiChatPrefs,
        executor: ActivityExecutor,
        lang: Lang,
    ) {
        val catalogEmpty = executor.catalog.entries.isEmpty()
        if (!prefs.agentTools || !prefs.activityTools || catalogEmpty) {
            session.tools.unregister(START)
            session.tools.unregister(STOP)
            return
        }
        if (session.tools.find(START) == null) {
            AgentToolCatalog.startActivity(
                texts,
                activities = { executor.catalog.entries.map { it.toChoice(lang) } },
                start = { id -> executor.start(id) },
            ).let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        if (session.tools.find(STOP) == null) {
            AgentToolCatalog.stopActivity(
                texts,
                current = { executor.currentChoice() },
                stop = { executor.stop() },
            ).let { (spec, exec) -> session.tools.register(spec, exec) }
        }
    }

    private fun ActivityEntry.toChoice(lang: Lang) =
        AgentToolCatalog.ActivityChoice(
            id = id,
            label = if (lang == Lang.EN) nameEn else name,
            description = desc,
            category = category,
        )
}
