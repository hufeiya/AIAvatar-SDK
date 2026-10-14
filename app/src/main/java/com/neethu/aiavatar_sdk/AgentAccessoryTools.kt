package com.neethu.aiavatar_sdk

import android.content.Context
import com.neethu.aiavatar_sdk.multiavatar.AccessoryCatalog
import com.neethu.corelib.AvatarController
import com.neethu.orchestrator.agent.AccessoryRemoveFailure
import com.neethu.orchestrator.agent.AccessoryWearFailure
import com.neethu.orchestrator.agent.AgentToolCatalog
import com.neethu.orchestrator.i18n.PromptTexts
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P2 配饰工具的 app 侧接线（docs/accessory-feasibility.md §5.6）：把
 * wear_accessory / remove_accessory 挂上会话注册表，缝体落到单人
 * [AvatarController]（AccessoryCatalog 三层合并出规格）。与
 * [AgentDeviceTools] 同款纪律——注册路径不触碰 context，执行时统一
 * Dispatchers.Main（attach 是渲染层主线程操作），失败归因成缝枚举由
 * PromptTexts 双语文案回灌，工具失败永不炸回合。
 *
 * 开关：`accessoryTools`（默认开，无隐私面）且跟随 `agentTools` 总开关；
 * 关闭语义 = 两工具退场（其他工具不动）。
 */
object AgentAccessoryTools {

    private const val WEAR = "wear_accessory"
    private const val REMOVE = "remove_accessory"

    /** 按当前偏好注册/反注册（幂等，同 AgentDeviceTools.registerAll 惯例）。 */
    fun register(
        session: AvatarSession,
        controller: AvatarController,
        context: Context?,
        texts: PromptTexts,
        prefs: AiChatPrefs,
    ) {
        if (!prefs.agentTools || !prefs.accessoryTools) {
            session.tools.unregister(WEAR)
            session.tools.unregister(REMOVE)
            return
        }
        if (session.tools.find(WEAR) == null) {
            AgentToolCatalog.wearAccessory(
                texts,
                catalogIds = { context?.let { AccessoryCatalog.all(it).map { e -> e.id } } ?: emptyList() },
                wear = { id -> wear(context, controller, id) },
            ).let { (spec, exec) -> session.tools.register(spec, exec) }
        }
        if (session.tools.find(REMOVE) == null) {
            AgentToolCatalog.removeAccessory(
                texts,
                wornIds = { controller.accessories.value.map { it.id } },
                remove = { id -> remove(controller, id) },
            ).let { (spec, exec) -> session.tools.register(spec, exec) }
        }
    }

    /** 挂载缝：目录查 id → 三层合并规格 → 主线程 attach。null = 成功。 */
    private suspend fun wear(context: Context?, controller: AvatarController, id: String): AccessoryWearFailure? {
        val ctx = context ?: return AccessoryWearFailure.FAILED
        return withContext(Dispatchers.Main) {
            if (controller.accessories.value.any { it.id.equals(id, ignoreCase = true) }) {
                return@withContext AccessoryWearFailure.ALREADY_WEARING
            }
            val modelId = controller.currentModelId
                ?: return@withContext AccessoryWearFailure.NO_MODEL
            val entry = AccessoryCatalog.find(ctx, id) ?: return@withContext AccessoryWearFailure.FAILED
            val spec = AccessoryCatalog.specFor(ctx, modelId, entry)
            controller.attachAccessory(spec).fold(
                onSuccess = { null },
                onFailure = { AccessoryWearFailure.FAILED },
            )
        }
    }

    /** 卸载缝：主线程 detach（未穿戴在工具层已被拦截）。null = 成功。 */
    private suspend fun remove(controller: AvatarController, id: String): AccessoryRemoveFailure? =
        withContext(Dispatchers.Main) {
            if (controller.detachAccessory(id)) null else AccessoryRemoveFailure.FAILED
        }
}
