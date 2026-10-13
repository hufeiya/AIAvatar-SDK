package com.neethu.aiavatar_sdk.multiavatar

import android.content.Context
import android.content.SharedPreferences
import com.neethu.aiadapter.openai.OpenAiCompatibleEmbeddingAdapter
import com.neethu.aiavatar_sdk.AiChatPrefs
import com.neethu.aiavatar_sdk.AiProvider
import com.neethu.corelib.Lang
import com.neethu.corelib.stage.AvatarHandle
import com.neethu.orchestrator.facade.AIAvatarSdk
import com.neethu.orchestrator.locomotion.MoveAnchor
import com.neethu.orchestrator.memory.RoomMemoryStore
import com.neethu.orchestrator.roundtable.RoundtableSession
import com.neethu.orchestrator.roundtable.StageEvent
import com.neethu.orchestrator.roundtable.StageGazeRelay
import com.neethu.orchestrator.roundtable.StageSlotSpec
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * 槽位独立配置（一屏多虚拟人 P1，docs/multi-avatar-feasibility.md §3.4）：
 * per-slot 的只有「身份与声音」——服务商/模型/音色/人设文本/点名名；API Key、
 * TTS 引擎、智能体/记忆等开关沿用全局 [AiChatPrefs]（一份钱共享，能力面一致）。
 * 持久化在 demo_settings，键名 `stage_<slot>_` 前缀（不与 MainActivity 单人
 * 路径的平铺键互踩）。
 */
data class StageSlotPrefs(
    val displayName: String,
    val provider: AiProvider,
    val llmModel: String,
    val voice: String,
    /** 人设文本（systemPrompt 直填，不走人物卡解析——演示台够用）。 */
    val persona: String,
) {
    companion object {
        fun defaults(slotId: String): StageSlotPrefs = when (slotId.uppercase()) {
            "A" -> StageSlotPrefs(
                displayName = "小柔",
                provider = AiProvider.SILICONFLOW,
                llmModel = "",
                voice = "",
                persona = "你叫小柔，一个活泼可爱的女孩。有人问名字必须答「小柔」；说话轻快热情，爱用「～」，喜欢甜品和猫。",
            )
            else -> StageSlotPrefs(
                displayName = "小刚",
                provider = AiProvider.VOLCANO,
                llmModel = "",
                voice = "",
                persona = "你叫小刚，一个沉稳踏实的工程师。有人问名字必须答「小刚」；说话简洁靠谱，喜欢数码产品和徒步。",
            )
        }

        private fun key(slotId: String, field: String) = "stage_${slotId.lowercase()}_$field"

        fun load(sp: SharedPreferences, slotId: String): StageSlotPrefs {
            val defaults = defaults(slotId)
            return StageSlotPrefs(
                displayName = sp.getString(key(slotId, "name"), null) ?: defaults.displayName,
                provider = sp.getString(key(slotId, "provider"), null)
                    ?.let { runCatching { AiProvider.valueOf(it) }.getOrNull() }
                    ?: defaults.provider,
                llmModel = sp.getString(key(slotId, "llm_model"), null) ?: defaults.llmModel,
                voice = sp.getString(key(slotId, "voice"), null) ?: defaults.voice,
                persona = sp.getString(key(slotId, "persona"), null) ?: defaults.persona,
            )
        }

        fun save(sp: SharedPreferences, slotId: String, p: StageSlotPrefs) {
            sp.edit()
                .putString(key(slotId, "name"), p.displayName)
                .putString(key(slotId, "provider"), p.provider.name)
                .putString(key(slotId, "llm_model"), p.llmModel)
                .putString(key(slotId, "voice"), p.voice)
                .putString(key(slotId, "persona"), p.persona)
                .apply()
        }
    }
}

/** 全局 prefs + 槽位身份 → 一条会话装配输入（A 硅基 B 火山的落点）。 */
internal fun AiChatPrefs.withSlot(p: StageSlotPrefs): AiChatPrefs = copy(
    provider = p.provider,
    llmModel = p.llmModel,
    voice = p.voice,
)

/** 槽位上下文 id（对话历史隔离）：stage_a / stage_b。 */
internal fun stageContextId(slotId: String) = "stage_${slotId.lowercase()}"

/** 槽位记忆分片 id（与上下文 id 同串，管理面板/STAGE_CMD memory 按它查）。 */
internal fun stageAvatarId(slotId: String) = "stage_${slotId.lowercase()}"

/**
 * 舞台聊天装配（一屏多虚拟人 P1，docs/multi-avatar-feasibility.md §4.2/§4.6）：
 * 每 slot 一套「独立 scope + AIAvatarSdk facade（记忆分片注入）」，上面盖一个
 * [RoundtableSession]（路由 + 发言令牌 + 倾听注视接力）。
 *
 * scope 纪律（§4.2 硬要求，坑 41）：每 slot 一个 SupervisorJob 子 scope，
 * 配置变化重建会话只走 facade 的重建语义；[close] 取消全部。
 */
class StageChatRig(
    private val appContext: Context,
    /** 全局 AI 配置（Key/TTS 引擎/工具开关；身份字段被 [StageSlotPrefs] 覆盖）。 */
    private val globalPrefs: () -> AiChatPrefs,
    /** 槽位配置的持久化（demo_settings；面板/STAGE_CMD cfg 共用）。 */
    val prefs: SharedPreferences,
    /** 场景锚点表（`<move:>` 词表；空 = 移动功能静默缺席，MainActivity 同语义）。 */
    private val anchors: List<MoveAnchor>,
    /** 全局 TTS 并发预算（均分注入各 slot）。 */
    private val globalTtsBudget: Int = 4,
) : StageGazeRelay {

    private val rigScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val globalMemory: RoomMemoryStore by lazy { RoomMemoryStore.from(appContext) }
    private val embedding by lazy { OpenAiCompatibleEmbeddingAdapter() }

    inner class SlotChat internal constructor(
        val slotId: String,
        val handle: AvatarHandle,
    ) {
        /** 硬要求：会话挂自己的 scope（共享 scope 重建会留僵尸 collector）。 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val sdk = AIAvatarSdk(
            scope = scope,
            controller = handle,
            appContext = appContext,
            memoryStore = globalMemory.scoped(stageAvatarId(slotId)),
            embeddingAdapter = embedding,
        )

        val prefs: StageSlotPrefs get() = StageSlotPrefs.load(prefsStore, slotId)

        private val prefsStore: SharedPreferences get() = this@StageChatRig.prefs

        /**
         * 确保「当前槽位配置 + 模型就绪状态」对应的会话存活；配置变化 →
         * facade 重建（人设/锚点重放）。返回会话（未配置完整 = null）。
         */
        fun ensureSession(modelReady: Boolean = handle.isReady): AvatarSession? {
            val global = globalPrefs()
            val slot = prefs
            val base = global.withSlot(slot).toChatConfig(
                contextId = stageContextId(slotId),
                characterId = slot.displayName,
                lang = Lang.ZH,
            )
            // 舞台 N 槽均分 TTS 预算（§4.2）；v1 两个槽位。
            // 人设走 ChatConfig.persona（每轮恒带的身份前言缝）——真机实测
            // doubao-mini 在 brief 轮会把「只随首轮发」的人设忘成自称豆包。
            val cfg = base.copy(
                ttsMaxConcurrent = RoundtableSession.perSlotTtsBudget(globalTtsBudget, 2),
                persona = slot.persona,
            )
            val session = sdk.configure(cfg, modelReady = modelReady)
            if (session != null) {
                // 锚点表注入（协议块移动段 + move_to 词表；空表 = 功能缺席）
                session.locomotionAnchors = anchors
            }
            return session
        }

        fun reconfigure() = ensureSession()

        fun close() {
            sdk.close()
            scope.cancel()
        }
    }

    private val chats = LinkedHashMap<String, SlotChat>()

    /** 舞台会话编排（路由/令牌/注视接力）；槽位集合或会话重建时换代。 */
    var roundtable: RoundtableSession? = null
        private set

    private val _events = MutableStateFlow<RoundtableSession?>(null)

    /** 合流事件（配置换代自动切到新编排实例）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val stageEvents: Flow<StageEvent> =
        _events.flatMapLatest { it?.events ?: emptyFlow() }

    /**
     * 槽位上线（模型句柄加载后调用）：建/换 SlotChat，模型就绪即装配会话并
     * 重建编排。句柄被 replace（remove 后重 load）时旧的先 close。
     */
    fun ensureSlot(slotId: String, handle: AvatarHandle) {
        val existing = chats[slotId]
        if (existing != null && existing.handle === handle) return
        existing?.close()
        val chat = SlotChat(slotId, handle)
        chats[slotId] = chat
        chat.ensureSession()
        rebuildRoundtable()
    }

    /** 槽位下线（removeAvatar 后调用）：关会话 + 撤出编排。 */
    fun removeSlot(slotId: String) {
        chats.remove(slotId)?.close()
        rebuildRoundtable()
    }

    /** 配置变化（STAGE_CMD cfg 后）：重配会话 + 重建编排（contextId 不变，历史保留）。 */
    fun reconfigure(slotId: String) {
        chats[slotId]?.reconfigure()
        rebuildRoundtable()
    }

    private fun rebuildRoundtable() {
        val sessions = chats.mapNotNull { (slotId, chat) ->
            val session = chat.sdk.session ?: return@mapNotNull null
            StageSlotSpec(
                id = slotId,
                displayName = chat.prefs.displayName,
            ) to session
        }
        roundtable = if (sessions.isEmpty()) null else RoundtableSession(rigScope, sessions, gaze = this)
        _events.value = roundtable
    }

    // ── StageGazeRelay（倾听注视的舞台实现）──────────────────────────────

    override fun headPosition(slotId: String): FloatArray? =
        chats[slotId]?.handle?.headWorldPosition()

    override fun lookAtPoint(slotId: String, x: Float, y: Float, z: Float) {
        chats[slotId]?.handle?.setLookAtTarget(x, y, z)
    }

    override fun lookAtCamera(slotId: String) {
        chats[slotId]?.handle?.lookAtCamera()
    }

    /** 调试快照：编排状态 + 各槽会话配置概要。 */
    fun snapshot(): String {
        val rt = roundtable
        return buildString {
            append(if (rt == null) "roundtable=off" else "roundtable=[${rt.snapshot()}]")
            chats.forEach { (slotId, chat) ->
                append(" | $slotId=")
                if (chat.sdk.session == null) {
                    append("unconfigured")
                } else {
                    append("${chat.prefs.provider.name}/${chat.prefs.displayName}")
                }
            }
        }
    }

    /** 槽位记忆分库直取（STAGE_CMD memory 调试）。 */
    fun memoryOf(slotId: String) = globalMemory.scoped(stageAvatarId(slotId))

    /** 全局记忆库直取（STAGE_CMD memory global）。 */
    fun globalMemoryForDebug() = globalMemory

    /**
     * 调试动作（list/count/clear）：镜像读毫秒级；库懒加载在构造时已排后台
     * 任务，极端情况下首次调用短暂阻塞主线程——仅调试通道可接受。
     */
    fun memoryAction(store: com.neethu.orchestrator.memory.MemoryStore, action: String): String =
        kotlinx.coroutines.runBlocking {
            when (action) {
                "list" -> store.all()
                    .joinToString(" ┃ ") { "${it.id.take(8)}[${it.category}]imp=${it.importance} ${it.content}" }
                    .ifEmpty { "(empty)" }
                "count" -> store.count().toString()
                "clear" -> {
                    store.clear(); "cleared"
                }
                else -> "unknown action '$action'"
            }
        }

    /** STAGE_CMD say 的异步入口（speak 挂到 rig scope，不阻塞广播线程）。 */
    fun sayAsync(slotId: String, text: String) {
        rigScope.launch {
            roundtable?.say(slotId.uppercase(), text)
        }
    }

    fun close() {
        rigScope.cancel()
        chats.values.forEach { it.close() }
        chats.clear()
        roundtable = null
    }
}
