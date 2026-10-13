package com.neethu.orchestrator.roundtable

import com.neethu.orchestrator.session.AvatarEvent
import com.neethu.orchestrator.session.AvatarSession
import com.neethu.orchestrator.session.ConversationPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * 舞台合流事件：哪个槽位的会话发生了什么（docs/multi-avatar-feasibility.md
 * P1「AvatarEvent 带 slotId」的落地形态——不侵入既有事件类型，舞台层包一层数据类，
 * 字幕分道/说话人高亮/调试通道都消费这里）。
 */
data class StageEvent(val slotId: String, val event: AvatarEvent)

/**
 * 倾听注视的能力缝（§4.7「倾听感的最大来源」）：发言者的头部世界坐标 +
 * 把某个槽位的视线指过去/指回镜头。app 用舞台 [com.neethu.corelib.stage.AvatarHandle]
 * 实现（lookAt/lookAtCamera/headWorldPosition 都是现成 API）；JVM 单测用 fake。
 */
interface StageGazeRelay {
    /** 槽位头部世界坐标；模型未就绪返回 null（该轮注视降级为不动）。 */
    fun headPosition(slotId: String): FloatArray?

    /** 槽位看向世界点（倾听者看发言者）。 */
    fun lookAtPoint(slotId: String, x: Float, y: Float, z: Float)

    /** 槽位看镜头（发言结束回看用户）。 */
    fun lookAtCamera(slotId: String)
}

/**
 * 多会话编排门面（一屏多虚拟人 P1，docs/multi-avatar-feasibility.md §4.2/§4.6）：
 * N 个各自独立（人设/服务商/音色/上下文/记忆分片）的 [AvatarSession] + 一条
 * 「这句话给谁」的路由 + 一个发言令牌 + 倾听注视接力。P1 刻意**没有**
 * ConversationDirector——虚拟人互聊是 P2；本类只管人对舞台说话的路由语义。
 *
 * 会话归属与生命周期（§4.2 硬要求）：每个 slot 的 session 由集成方装在
 * **各自的 scope** 上（app 一 slot 一个 SupervisorJob 子 scope），本类不持有
 * 也不取消它们——只挂事件收集；配置变化重建某个 slot 后用 [updateSession]
 * 重挂（先 cancel 旧收集，坑 41 僵尸 collector 的教训）。
 *
 * TTS 全局预算（§4.2）：本类不共享 Semaphore——集成方建 session 时按
 * [perSlotTtsBudget] 均分注入 `Options.ttsMaxConcurrent`，总并发不超预算。
 */
class RoundtableSession(
    private val scope: CoroutineScope,
    slots: List<Pair<StageSlotSpec, AvatarSession>>,
    /** null = 不做注视接力（headless/无舞台形态照常可用）。 */
    private val gaze: StageGazeRelay? = null,
) {

    private val specs: List<StageSlotSpec> = slots.map { it.first }
    private val router = InputRouter(specs)
    private val sessionById = LinkedHashMap<String, AvatarSession>()
    private val collectorJobs = HashMap<String, Job>()

    private val _events = MutableSharedFlow<StageEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** 合流事件（按 slot 分道消费）。 */
    val events: SharedFlow<StageEvent> = _events.asSharedFlow()

    init {
        slots.forEach { (spec, session) -> attach(spec.id, session) }
    }

    /**
     * 当前发言令牌持有者（最近一条 [AvatarEvent.SentenceStarted] 的槽位）；
     * 回合完成/播放被打断时释放。打断路由与调试通道的数据源。
     */
    @Volatile
    var currentSpeakerId: String? = null
        private set

    /** 最后一个与用户交互的槽位（默认路由目标，UI 高亮同步用，可写以跨重建保留）。 */
    @Volatile
    var lastAddressedId: String? = null

    /**
     * 用户一句话进舞台：点名/默认路由（[InputRouter]）→ 目标 session 发起
     * 回合，其余人保持倾听（注视接力见 [onEvent]）。
     *
     * @return 实际接话的槽位 id；null = 无可路由对象（舞台为空/文本无法归属）。
     */
    fun sendFromUser(text: String): String? {
        val decision = router.route(text, lastAddressedId)
        val target = decision as? RoutingDecision.Target ?: return null
        lastAddressedId = target.slotId
        sessionById[target.slotId]?.send(target.cleanedText)
        return target.slotId
    }

    /** 直接对某个槽位说话（开场白/舞台指令），不走路由、不进历史。 */
    suspend fun say(slotId: String, text: String): Boolean {
        val session = sessionById[slotId] ?: return false
        lastAddressedId = slotId
        session.speak(text)
        return true
    }

    /**
     * 打断（§4.5）：命中**全部非 IDLE** 的会话——正在说的（发言令牌）与正在
     * 想的（THINKING，还没出声也要掐断）。各自的三层打断链路（LLM 流+在途
     * TTS+播放静音）由 AvatarSession 自理；空闲会话不碰（打断一个 IDLE 会话
     * 无意义且会误伤未来回合）。
     */
    fun interrupt() {
        sessionById.forEach { (_, session) ->
            if (session.phase.value != ConversationPhase.IDLE) session.interrupt()
        }
    }

    /** 槽位 id → 当前会话（调试通道/进阶接线用）。 */
    fun sessionOf(slotId: String): AvatarSession? = sessionById[slotId]

    /** 槽位表（路由词表同源）。 */
    fun slotSpecs(): List<StageSlotSpec> = specs.toList()

    /**
     * 配置变化重建某个槽位的会话后重挂事件收集：先 cancel 旧收集者再挂新的
     * （共享 scope 下不 cancel 就是坑 41 的僵尸 collector——旧实例还在收事件）。
     * 旧会话的 close 由集成方（facade.configure 的重建语义）负责。
     */
    fun updateSession(slotId: String, session: AvatarSession) {
        if (sessionById[slotId] == null) return
        attach(slotId, session)
    }

    /** 单行诊断快照（STAGE_CMD chat_state 的正本）。 */
    fun snapshot(): String = buildString {
        append("speaker=${currentSpeakerId ?: "-"}")
        append(" lastAddressed=${lastAddressedId ?: "-"}")
        sessionById.forEach { (id, session) ->
            append(" $id=${session.phase.value.name.lowercase()}")
        }
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun attach(slotId: String, session: AvatarSession) {
        sessionById[slotId] = session
        collectorJobs.remove(slotId)?.cancel()
        collectorJobs[slotId] = scope.launch {
            session.events.collect { onEvent(slotId, it) }
        }
    }

    private fun onEvent(slotId: String, event: AvatarEvent) {
        _events.tryEmit(StageEvent(slotId, event))
        val relay = gaze ?: return
        when (event) {
            is AvatarEvent.SentenceStarted -> {
                currentSpeakerId = slotId
                // 倾听态：其余人看发言者的头（头坐标拿不到=模型未就绪，本轮跳过）
                val head = relay.headPosition(slotId) ?: return
                specs.forEach { spec ->
                    if (spec.id != slotId) relay.lookAtPoint(spec.id, head[0], head[1], head[2])
                }
            }

            // 回合收尾/播放被打断 → 全员回看镜头。SentenceEnded 不触发回看：
            // 一回合多句之间听众不该来回甩头。
            is AvatarEvent.TurnCompleted,
            is AvatarEvent.TurnFailed,
            is AvatarEvent.PlaybackInterrupted,
            -> {
                currentSpeakerId = null
                specs.forEach { spec -> relay.lookAtCamera(spec.id) }
            }

            else -> Unit
        }
    }

    companion object {
        /**
         * 全局 TTS 并发预算的均分（§4.2）：每个 session 各自 Semaphore(N) 的
         * 形态下，slotCount 等分才能保证总在途请求不超预算——2 slot × 2 = 4，
         * 与单虚拟人默认一致。预算小于槽数时每槽保底 1（总预算被 exceeded，
         * 但 2~3 人 × 1 并发仍是可听的体验）。
         */
        fun perSlotTtsBudget(globalBudget: Int, slotCount: Int): Int =
            if (slotCount <= 0) globalBudget else maxOf(1, globalBudget / slotCount)
    }
}
