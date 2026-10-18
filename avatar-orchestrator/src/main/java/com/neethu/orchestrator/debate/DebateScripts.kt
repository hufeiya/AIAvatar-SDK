package com.neethu.orchestrator.debate

/**
 * P0 固定稿来源：配置 `scripts`（participantId → phase → 文本）优先，
 * 缺失回退内置兜底稿。兜底稿用辩题/立场/人名填确定模板——主持词绝不
 * 由模型即兴宣布阶段或剩余时间（doc §3/§6）。
 */
class ConfigScriptProvider(private val dto: DebateConfigDto) : DebateScriptProvider {

    private val participantsById = dto.participants.associateBy { it.id }

    override fun scriptFor(phase: DebatePhase, participantId: String, turnIndexInPhase: Int): String {
        dto.scripts[participantId]?.get(phase.name)?.takeIf { it.isNotBlank() }?.let { return it }
        return defaultScript(phase, participantId, turnIndexInPhase)
    }

    private fun defaultScript(phase: DebatePhase, participantId: String, turnIndexInPhase: Int): String {
        val p = participantsById[participantId]
        val name = p?.name ?: participantId
        val topic = dto.topic
        return when (phase) {
            DebatePhase.HOST_OPENING ->
                "欢迎来到辩论赛现场。今天的辩题是「$topic」。" +
                    "正方主张「${dto.sides.PRO}」，反方主张「${dto.sides.CON}」。" +
                    "双方各有四位辩手，比赛马上开始。"
            DebatePhase.OPENING_STATEMENT ->
                "大家好，我是$name。我方认为，人工智能的发展${sideOf(participantId)}。" +
                    "接下来我将从定义和标准两个层面展开论证。"
            DebatePhase.CROSS_SUMMARY ->
                "我是$name。刚才的攻辩中，对方的回答恰恰暴露了他们立场的漏洞。" +
                    "我方重申：人工智能的发展${sideOf(participantId)}。"
            DebatePhase.CROSS_EXAM ->
                if (turnIndexInPhase % 2 == 0) {
                    "请对方辩友正面回答：关于「$topic」，你方的判断标准是什么？"
                } else {
                    "我方认为这一关键分歧正好支持我方立场：人工智能的发展${sideOf(participantId)}。"
                }
            DebatePhase.FREE_DEBATE ->
                "补充一点：$topic，请对方不要回避这个核心问题。我方坚持认为，人工智能的发展${sideOf(participantId)}。"
            DebatePhase.CLOSING ->
                "最后总结。今天整场比赛，我方始终证明了一件事：人工智能的发展${sideOf(participantId)}。" +
                    "谢谢大家。"
            DebatePhase.HOST_CLOSING ->
                "感谢双方辩手的精彩表现，本场辩论到此结束。"
        }
    }

    private fun sideOf(participantId: String): String = when (DebateIds.teamOf(participantId)) {
        DebateTeam.PRO -> dto.sides.PRO.ifBlank { "更有利于人类创造力" }
        DebateTeam.CON -> dto.sides.CON.ifBlank { "更不利于人类创造力" }
        null -> ""
    }
}
