package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.EmotionCue
import com.neethu.corelib.CameraShot

/** Observable happenings during a conversation turn. */
sealed interface AvatarEvent {
    /** A sentence has been chunked out and submitted for synthesis. */
    data class SentenceQueued(val sequence: Int, val text: String) : AvatarEvent

    /** Playback of a sentence started (also when it begins prefetched playback). */
    data class SentenceStarted(val sequence: Int, val text: String) : AvatarEvent

    /** Playback of a sentence finished naturally. */
    data class SentenceEnded(val sequence: Int, val text: String) : AvatarEvent

    /** A sentence failed to synthesize/decode; the turn continues without it. */
    data class SentenceFailed(val sequence: Int, val text: String, val message: String) : AvatarEvent

    /** An emotion tag was extracted from the LLM stream. */
    data class EmotionChanged(val cue: EmotionCue) : AvatarEvent

    /** An `<act:…>` tag started a gesture from the action catalog. */
    data class ActionStarted(val tag: String, val label: String) : AvatarEvent

    /** A `<cam:…>` tag switched the preset camera framing. */
    data class CameraChanged(val shot: CameraShot) : AvatarEvent

    /**
     * 词表外的标签被过滤（绝不进 TTS 语音）：[raw] 是模型原文（如
     * `<act:laugh:0.5>`），[kind] 是标签家族 `"emo"` / `"act"` / `"cam"`。
     * 模型偶尔编词表外的名字是常态——发这个事件让集成方/调试面板能看见
     * 模型实际写了什么，决定是否收进别名表（[EmotionAliases] 治已知，
     * 本事件发现未知）。
     */
    data class UnknownTag(val kind: String, val raw: String) : AvatarEvent

    /** Validation/execution failure, distinct from an unknown generated name. */
    data class TagRejected(
        val kind: String,
        val raw: String,
        val reason: com.neethu.orchestrator.tag.TagCapabilityRegistry.Reason,
    ) : AvatarEvent

    /** Finite compatibility mapping; counted separately from canonical generation. */
    data class TagAliasResolved(val kind: String, val raw: String, val canonical: String) : AvatarEvent

    /** The current turn finished to the end (LLM + TTS + playback all drained). */
    data class TurnCompleted(val interrupted: Boolean) : AvatarEvent

    /** The turn aborted with an error (LLM/TTS/decode failure). */
    data class TurnFailed(val error: Throwable) : AvatarEvent

    /** Active playback was cut short by [AvatarSession.interrupt]. */
    data object PlaybackInterrupted : AvatarEvent

    /** 技能进度事件（激活/出拳/判定/退场…），skill/ 框架经 SkillContext.event 发出。 */
    data class SkillEvent(val skillId: String, val detail: String) : AvatarEvent

    /**
     * 模型发起了一个工具调用（P0 工具调用循环）。字幕可显示「🔍 …」——让用户
     * 感知等待原因（工具执行 + 续流有数秒空档，docs/agent-feasibility.md §9.4）。
     */
    data class ToolCallStarted(val name: String) : AvatarEvent

    /** 一轮工具调用全量（观测：模型一次请求了哪些工具，顺序执行）。 */
    data class ToolCallsIssued(val names: List<String>) : AvatarEvent

    /** 单个工具执行完毕（[isError]=失败结果已回灌模型，回合不失败）。 */
    data class ToolCallFinished(val name: String, val isError: Boolean) : AvatarEvent

    /**
     * `<move:锚点>`（或 move_to 工具）让虚拟人起步走向一个位置
     * （docs/locomotion-feasibility.md §7.3/§7.4）：[anchor] 是锚点名，
     * 坐标为世界 XZ 米。到达/中断各有 [WalkArrived]/[WalkInterrupted] 跟进。
     */
    data class WalkStarted(val anchor: String, val targetX: Float, val targetZ: Float) : AvatarEvent

    /** 行走到达目标（corelib walkFinished 流转出，到达停步之后发出）。 */
    data class WalkArrived(val x: Float, val z: Float) : AvatarEvent

    /** 行走被中断（stopWalk：用户打断/模型主动停步），坐标为停下位置。 */
    data class WalkInterrupted(val x: Float, val z: Float) : AvatarEvent
}
