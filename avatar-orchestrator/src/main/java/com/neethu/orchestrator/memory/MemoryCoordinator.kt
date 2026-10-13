package com.neethu.orchestrator.memory

import android.util.Log
import com.neethu.aiadapter.api.EmbeddingAdapter
import com.neethu.aiadapter.api.EmbeddingConfig
import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.LlmConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 记忆协调器（docs/memory-feasibility.md §4.3）：通道 B（攒批自动提炼）的
 * 触发层 + [记忆参考] 注入的门面。
 *
 * - **窗口**：每个完整回合收尾把 (user, assistantCleanText) 追加进待提炼窗口
 *   （持久化到 agent_memory.db，force-stop 不丢、跨会话连续计数）；被打断/
 *   失败的回合走不到 [onTurnCompleted]（调用点在 try 块内 TurnCompleted 之前），
 *   speak() 不调用本方法——没有用户输入，无可提炼。
 * - **双阈值或触发**：窗口轮次 ≥ [everyTurns] 或累计字符 ≥ [minChars] 才发
 *   提炼请求（聊天短句多，逐轮提炼既费 token 又逼模型硬挤垃圾）；触发时窗口
 *   累计 <[SILENCE_GATE_CHARS] 字 → 静默清空不烧请求（连续寒暄凑满轮次的
 *   退化情形）。
 * - **fire-and-forget**：提炼在 [scope] 后台跑，失败只 Log、窗口保留下次重试
 *   （硬顶 [WINDOW_MAX_TURNS] 轮丢最旧，防长故障下无限膨胀）；永不影响对话。
 *   快照语义：提炼期间新增的回合不被误删（成功只移除快照覆盖的前缀轮次）。
 */
class MemoryCoordinator(
    private val store: MemoryStore,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val texts: MemoryTexts,
    val retriever: MemoryRetriever,
    private val llmProvider: () -> LlmAdapter?,
    private val llmConfigProvider: () -> LlmConfig?,
    private val charName: () -> String,
    private val embedder: EmbeddingAdapter? = null,
    private val embeddingConfig: () -> EmbeddingConfig? = { null },
    private val extractorFactory: (LlmAdapter) -> MemoryExtractor = { adapter ->
        MemoryExtractor(adapter, store, texts, embedder, embeddingConfig)
    },
    everyTurns: Int = DEFAULT_EVERY_TURNS,
    minChars: Int = DEFAULT_MIN_CHARS,
) {

    /** 提炼触发阈值：窗口轮次（SDK API `Options.memoryExtractEveryTurns`；≥1）。 */
    @Volatile var everyTurns: Int = everyTurns.coerceAtLeast(1)

    /** 提炼触发阈值：窗口累计字符（SDK API `Options.memoryExtractMinChars`）。 */
    @Volatile var minChars: Int = minChars.coerceAtLeast(0)

    /** 自动提炼子开关（设置页「自动提炼记忆」）：只影响通道 B，检索注入照常。 */
    @Volatile var autoExtract: Boolean = true

    /** 总开关（设置页「长期记忆」）：关 = 零注入 + 不提炼 + 窗口清空。 */
    @Volatile var enabled: Boolean = true
        private set

    private val mutex = Mutex()
    private var window: MutableList<MemoryTurn> = mutableListOf()
    private var windowLoaded = false
    private var extracting = false

    /** 窗口计数快照（mutex 内刷新，windowLine 免挂起读）。 */
    @Volatile private var windowTurns = 0
    @Volatile private var windowChars = 0

    private companion object {
        const val TAG = "MemoryCoordinator"
        const val DEFAULT_EVERY_TURNS = 5
        const val DEFAULT_MIN_CHARS = 600

        /** 静默门：触发条件满足但窗口累计 <12 字 → 本地判定无可提炼，清空不请求。 */
        const val SILENCE_GATE_CHARS = 12

        /** 窗口硬顶（失败保留语义下防无限膨胀）：超顶丢最旧。 */
        const val WINDOW_MAX_TURNS = 20
    }

    // ── 注入（读路径）──────────────────────────────────────────────────

    /** 本轮请求的 [记忆参考] 行；总开关关/空库/无命中 → null（零注入）。 */
    suspend fun lineFor(query: String): String? {
        if (!enabled) return null
        return retriever.lineFor(query, texts)
    }

    // ── 写路径：回合收尾 ───────────────────────────────────────────────

    /**
     * 回合收尾钩子（AvatarSession 在 TurnCompleted 之前调用，取消/异常路径
     * 天然跳过）：窗口追加（持久化）→ 阈值判定 → 达标才后台提炼。
     */
    suspend fun onTurnCompleted(userText: String, assistantText: String) {
        if (!enabled || !autoExtract) return
        if (userText.isBlank() && assistantText.isBlank()) return
        ensureWindowLoaded()
        var shouldExtract = false
        mutex.withLock {
            window.add(MemoryTurn(userText, assistantText))
            while (window.size > WINDOW_MAX_TURNS) window.removeAt(0)
            refreshCountersLocked()
            persistLocked()
            shouldExtract = shouldTriggerLocked()
            if (shouldExtract && totalCharsLocked() < SILENCE_GATE_CHARS) {
                // 静默门：连续「嗯嗯」「哈哈」把轮次阈值凑满的退化情形——本地
                // 判定无可提炼，清空窗口不烧请求
                Log.i(TAG, "silence gate: window has < $SILENCE_GATE_CHARS chars, dropping without a request")
                window.clear()
                refreshCountersLocked()
                persistLocked()
                shouldExtract = false
            }
        }
        if (shouldExtract) startExtraction()
    }

    private fun shouldTriggerLocked(): Boolean =
        window.size >= everyTurns.coerceAtLeast(1) || totalCharsLocked() >= minChars.coerceAtLeast(0)

    private fun totalCharsLocked(): Int =
        window.sumOf { it.user.length + it.assistant.length }

    /** 所有窗口变更路径收尾调用（mutex 内），windowLine 的非挂起读依赖它。 */
    private fun refreshCountersLocked() {
        windowTurns = window.size
        windowChars = totalCharsLocked()
    }

    // ── 提炼 ──────────────────────────────────────────────────────────

    private fun startExtraction() {
        synchronized(this) {
            if (extracting) return
            extracting = true
        }
        scope.launch {
            try {
                // 快照语义：提炼请求用快照，成功只移除快照覆盖的前缀轮次；
                // 提炼期间新增的回合留在窗口里等下一轮触发
                val (snapshot, adapter, baseConfig) = mutex.withLock {
                    Triple(window.toList(), llmProvider(), llmConfigProvider())
                }
                if (adapter == null || baseConfig == null) {
                    Log.i(TAG, "extraction skipped: main llm not ready (window kept)")
                    return@launch
                }
                val ok = try {
                    extractorFactory(adapter).extract(snapshot, charName(), baseConfig)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "extraction request failed, window kept for retry: ${t.message}")
                    false
                }
                mutex.withLock {
                    if (ok) {
                        repeat(snapshot.size) { if (window.isNotEmpty()) window.removeAt(0) }
                        refreshCountersLocked()
                        persistLocked()
                        Log.i(TAG, "window drained by ${snapshot.size} turn(s); ${window.size} turn(s) remain")
                    }
                }
            } finally {
                synchronized(this@MemoryCoordinator) { extracting = false }
            }
        }
    }

    // ── 开关与维护 ─────────────────────────────────────────────────────

    /**
     * 总开关切换（运行时，不重建会话——observeCamera 先例）：关 = 窗口一并
     * 清空（§4.7）；开 = 恢复注入与提炼。
     */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            scope.launch {
                mutex.withLock {
                    window.clear()
                    refreshCountersLocked()
                    persistLocked()
                }
            }
            Log.i(TAG, "memory disabled: pending window cleared")
        }
    }

    /** 清空记忆库与窗口（ai_cmd memory_clear / 管理面板清空）。 */
    suspend fun clearAll() {
        mutex.withLock {
            window.clear()
            refreshCountersLocked()
            persistLocked()
        }
        store.clear()
    }

    /**
     * 运行时调整提炼阈值（ai_cmd memory_config）：负数 = 保持不变。窗口里的
     * 既有内容不动，下个回合收尾按新阈值判定。
     */
    fun configure(everyTurns: Int?, minChars: Int?) {
        everyTurns?.let { if (it >= 1) this.everyTurns = it }
        minChars?.let { if (it >= 0) this.minChars = it }
    }

    /** 调试行：待提炼窗口状态（memory_stats 用；非挂起，读计数快照）。 */
    fun windowLine(): String =
        "window=$windowTurns turns/${windowChars}chars trigger=turns>=$everyTurns||chars>=$minChars " +
            "autoExtract=$autoExtract enabled=$enabled extracting=$extracting"

    // ── internals ──────────────────────────────────────────────────────

    private suspend fun ensureWindowLoaded() {
        if (windowLoaded) return
        mutex.withLock {
            if (windowLoaded) return
            val persisted = (store as? PendingWindowStore)?.loadWindow().orEmpty()
            window = persisted.toMutableList()
            windowLoaded = true
            refreshCountersLocked()
            if (persisted.isNotEmpty()) {
                Log.i(TAG, "restored pending window: ${persisted.size} turn(s)")
            }
        }
    }

    private suspend fun persistLocked() {
        (store as? PendingWindowStore)?.saveWindow(window.toList())
    }
}
