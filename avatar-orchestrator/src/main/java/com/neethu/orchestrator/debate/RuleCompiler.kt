package com.neethu.orchestrator.debate

/**
 * 规则编译器（docs/debate-feasibility.md §4）：校验过的配置 → 确定性赛程表。
 * 引擎执行期不再看 JSON，也不接受任何运行期脚本注入——「不让任意 JSON 执行
 * 脚本」在这一步落死。
 */
object RuleCompiler {

    fun compile(dto: DebateConfigDto): DebatePlan {
        val issues = DebateConfigValidator.validate(dto)
        if (issues.isNotEmpty()) throw DebateConfigException(issues)
        val r = dto.rules
        val segments = mutableListOf<DebateSegment>()

        segments += DebateSegment.HostSpeech(DebatePhase.HOST_OPENING, r.hostSegmentMs)
        segments += DebateSegment.Statements(
            DebatePhase.OPENING_STATEMENT,
            listOf(
                StatementTurn(DebateIds.pro(1), DebateTeam.PRO, r.openingMs),
                StatementTurn(DebateIds.con(1), DebateTeam.CON, r.openingMs),
            ),
        )
        segments += compileCrossExam(
            DebatePhase.CROSS_EXAM,
            r.crossRoundMs,
            r.questionMs,
            r.answerMs,
        )
        segments += DebateSegment.Statements(
            DebatePhase.CROSS_SUMMARY,
            listOf(
                StatementTurn(DebateIds.pro(1), DebateTeam.PRO, r.crossSummaryMs),
                StatementTurn(DebateIds.con(1), DebateTeam.CON, r.crossSummaryMs),
            ),
        )
        segments += DebateSegment.FreeDebate(
            DebatePhase.FREE_DEBATE,
            firstTeam = DebateTeam.valueOf(r.freeFirstTeam.uppercase()),
            teamBudgetMs = mapOf(DebateTeam.PRO to r.freeTeamMs, DebateTeam.CON to r.freeTeamMs),
            turnBudgetMs = r.freeTurnMs,
            rotation = mapOf(
                DebateTeam.PRO to DebateIds.PRO_ALL,
                DebateTeam.CON to DebateIds.CON_ALL,
            ),
        )
        // 总结陈词按惯例反方先结（doc §3：CON4 → PRO4）
        segments += DebateSegment.Statements(
            DebatePhase.CLOSING,
            listOf(
                StatementTurn(DebateIds.con(4), DebateTeam.CON, r.closingMs),
                StatementTurn(DebateIds.pro(4), DebateTeam.PRO, r.closingMs),
            ),
        )
        segments += DebateSegment.HostSpeech(DebatePhase.HOST_CLOSING, r.hostSegmentMs)
        return DebatePlan(matchId = dto.matchId, segments = segments)
    }

    /**
     * 攻辩 = 4 轮：PRO2→CON2→PRO3→CON3 发起（doc §3）；应答者在编译期就定死
     * （默认策略：对方二/三辩中已应答次数最少、同次数按辩位）——赛程表完全
     * 确定，验收可逐轮断言。
     */
    private fun compileCrossExam(
        phase: DebatePhase,
        wallMs: Long,
        questionMs: Long,
        answerMs: Long,
    ): DebateSegment.CrossExam {
        val policy = AnswererPolicy()
        val initiators = listOf(
            DebateIds.pro(2) to DebateTeam.PRO,
            DebateIds.con(2) to DebateTeam.CON,
            DebateIds.pro(3) to DebateTeam.PRO,
            DebateIds.con(3) to DebateTeam.CON,
        )
        val rounds = initiators.mapIndexed { idx, (questioner, team) ->
            val opposing = team.opposing
            val candidates = DebateConfigSchema.CROSS_ANSWER_CANDIDATES.map { pos ->
                if (opposing == DebateTeam.PRO) DebateIds.pro(pos) else DebateIds.con(pos)
            }
            CrossRound(
                roundIndex = idx,
                questionerId = questioner,
                answererId = policy.pick(team, candidates),
                wallBudgetMs = wallMs,
                questionBudgetMs = questionMs,
                answerBudgetMs = answerMs,
            )
        }
        return DebateSegment.CrossExam(phase, rounds)
    }
}

/** 编译后的整场赛程（不可变）。 */
data class DebatePlan(
    val matchId: String,
    val segments: List<DebateSegment>,
)
