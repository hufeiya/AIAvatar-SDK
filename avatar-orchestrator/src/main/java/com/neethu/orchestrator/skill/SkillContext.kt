package com.neethu.orchestrator.skill

/**
 * 传给技能回调的上下文：能力缝 + 事件出口。事件经 [event] 发出后变成
 * [com.neethu.orchestrator.session.AvatarEvent.SkillEvent]（skillId 由
 * [SkillRegistry] 绑定到具体技能实例），进 session 事件流 → AIDebug 日志。
 */
class SkillContext(
    val host: SkillHost,
    private val onDetail: (String) -> Unit = {},
) {
    /** 技能进度事件（激活/出拳/判定/退场…），观测与真机排查用。 */
    fun event(detail: String) = onDetail(detail)
}
