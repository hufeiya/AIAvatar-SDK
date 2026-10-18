package com.neethu.orchestrator.debate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 计时基础设施（docs/debate-feasibility.md §5）：
 * - 单调时钟：[DebateClock]，业务只取差值，绝不碰系统日期。
 * - 赛程定时器：[DebateTimer]，个人 deadline / 攻辩墙钟 / 姿态超时 / 收尾
 *   宽限统一走它。
 * 暂停/恢复的冻结语义收口在 [DebateEngine]：引擎为每个 key 记录 fireAt，
 * 暂停 = cancelAll + 保留账目，恢复 = 按剩余重排。两者的测试替身让整场赛程
 * 在虚拟时钟下确定性推进（doc §13.1）。
 */

/** 单调毫秒时钟（Android 实现 = SystemClock.elapsedRealtime）。 */
fun interface DebateClock {
    fun nowMs(): Long
}

/** 一次性定时器。同 key 重复 schedule 顶替旧的。 */
interface DebateTimer {
    fun schedule(key: String, delayMs: Long, onFire: () -> Unit)
    fun cancel(key: String)
    fun cancelAll()
}

/** 真实实现：挂到 [scope]，回调在 scope 的调度器上执行（引擎内部再串行化）。 */
class CoroutineDebateTimer(private val scope: CoroutineScope) : DebateTimer {
    private val jobs = LinkedHashMap<String, Job>()
    private val lock = Any()

    override fun schedule(key: String, delayMs: Long, onFire: () -> Unit) {
        synchronized(lock) {
            jobs.remove(key)?.cancel()
            val job = scope.launch {
                delay(delayMs.coerceAtLeast(0L))
                synchronized(lock) { jobs.remove(key) }
                onFire()
            }
            jobs[key] = job
        }
    }

    override fun cancel(key: String) {
        synchronized(lock) { jobs.remove(key)?.cancel() }
    }

    override fun cancelAll() {
        synchronized(lock) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
    }
}

/**
 * 引擎 ← app 的发言播放缝：start 是异步起播（姿态/令牌门控已由引擎完成），
 * 结果一律经 [DebateEngine] 的 notify* 回调带回，携带 turnId 防串轮。
 */
interface DebateSpeechPlayer {
    /** 起播固定稿文本；同回合重复 start = 顶替前一次（重试/恢复续讲）。 */
    fun start(turn: ActiveTurn, text: String)

    /** 取消当前播放（LLM 流 + 在途 TTS + 播放队列 + 口型）。无在途时为 no-op。 */
    fun cancelActive(reason: String)

    /** 释放资源（stop/reset/页面销毁）。 */
    fun release()
}

/** 引擎 → app 的姿态执行缝（坐站链由 app 的姿态导演落地）。 */
interface DebatePostureDirector {
    fun onPostureCommand(command: PostureCommand)
}

data class PostureCommand(
    val turnId: Long,
    val participantIds: List<String>,
    val action: PostureAction,
)

/**
 * 固定稿来源。P0 = 配置 scripts + 内置兜底；P1 的 [DebateSpeechService]
 * （按公共记录生成）也实现本接口接入，引擎无感。
 */
fun interface DebateScriptProvider {
    fun scriptFor(phase: DebatePhase, participantId: String, turnIndexInPhase: Int): String
}
