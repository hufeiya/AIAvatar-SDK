package com.neethu.orchestrator.debate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleCompilerTest {

    private fun config(timings: DebateConfigDto.RulesDto = DebateConfigDto.RulesDto()) =
        DebateConfigDto(
            matchId = "m1",
            topic = "人工智能的发展是否更有利于人类创造力",
            sides = DebateConfigDto.SidesDto(PRO = "更有利", CON = "更不利"),
            rules = timings,
            participants = testParticipants(),
        )

    @Test
    fun `默认模板展开为七个赛程段`() {
        val plan = RuleCompiler.compile(config())
        assertEquals(7, plan.segments.size)
        assertEquals(
            listOf(
                DebatePhase.HOST_OPENING,
                DebatePhase.OPENING_STATEMENT,
                DebatePhase.CROSS_EXAM,
                DebatePhase.CROSS_SUMMARY,
                DebatePhase.FREE_DEBATE,
                DebatePhase.CLOSING,
                DebatePhase.HOST_CLOSING,
            ),
            plan.segments.map { it.phase },
        )
    }

    @Test
    fun `立论段顺序为 PRO1 到 CON1 陈词段反方先结`() {
        val plan = RuleCompiler.compile(config())
        val opening = plan.segments[1] as DebateSegment.Statements
        assertEquals(listOf("PRO1", "CON1"), opening.turns.map { it.speakerId })
        assertEquals(listOf(180_000L, 180_000L), opening.turns.map { it.budgetMs })

        val closing = plan.segments[5] as DebateSegment.Statements
        assertEquals(listOf("CON4", "PRO4"), closing.turns.map { it.speakerId })
    }

    @Test
    fun `攻辩四轮发起者为二三次辩且应答者按最少应答策略选定`() {
        val plan = RuleCompiler.compile(config())
        val cross = plan.segments[2] as DebateSegment.CrossExam
        val rounds = cross.rounds
        assertEquals(4, rounds.size)
        // 发起顺序：PRO2 → CON2 → PRO3 → CON3（doc §3）
        assertEquals(listOf("PRO2", "CON2", "PRO3", "CON3"), rounds.map { it.questionerId })
        // 应答策略：对方二/三辩中已应答次数最少，同次数按辩位 →
        // R0: PRO2 问 CON2；R1: CON2 问 PRO2；R2: PRO3 问 CON3（CON2 已答 1 次）；R3: CON3 问 PRO3
        assertEquals(listOf("CON2", "PRO2", "CON3", "PRO3"), rounds.map { it.answererId })
        assertEquals(90_000L, rounds[0].wallBudgetMs)
        assertEquals(10_000L, rounds[0].questionBudgetMs)
        assertEquals(20_000L, rounds[0].answerBudgetMs)
    }

    @Test
    fun `自由辩论段携带双队余额与循环辩位`() {
        val plan = RuleCompiler.compile(config())
        val free = plan.segments[4] as DebateSegment.FreeDebate
        assertEquals(DebateTeam.PRO, free.firstTeam)
        assertEquals(240_000L, free.teamBudgetMs.getValue(DebateTeam.PRO))
        assertEquals(listOf("PRO1", "PRO2", "PRO3", "PRO4"), free.rotation.getValue(DebateTeam.PRO))
        assertEquals(listOf("CON1", "CON2", "CON3", "CON4"), free.rotation.getValue(DebateTeam.CON))
    }

    @Test
    fun `自定义时长透传到赛程表`() {
        val plan = RuleCompiler.compile(
            config(DebateConfigDto.RulesDto(openingMs = 5_000L, closingMs = 7_000L))
        )
        val opening = plan.segments[1] as DebateSegment.Statements
        assertEquals(listOf(5_000L, 5_000L), opening.turns.map { it.budgetMs })
        val closing = plan.segments[5] as DebateSegment.Statements
        assertEquals(7_000L, closing.turns[0].budgetMs)
    }

    @Test(expected = DebateConfigException::class)
    fun `非法配置编译直接抛出`() {
        RuleCompiler.compile(config().copy(participants = testParticipants().dropLast(1)))
    }

    @Test
    fun `AnswererPolicy 同次数按辩位、已应答者靠后`() {
        val policy = AnswererPolicy()
        assertEquals("CON2", policy.pick(DebateTeam.PRO, listOf("CON2", "CON3")))
        assertEquals("CON3", policy.pick(DebateTeam.PRO, listOf("CON2", "CON3")))
        assertEquals("CON2", policy.pick(DebateTeam.PRO, listOf("CON2", "CON3")))
    }
}

class DebateConfigParserTest {

    @Test
    fun `完整配置往返编码保真`() {
        val dto = DebateConfigDto(
            matchId = "m1",
            topic = "辩题",
            rules = DebateConfigDto.RulesDto(openingMs = 1_000L),
            participants = testParticipants(),
            scripts = mapOf("PRO1" to mapOf("OPENING_STATEMENT" to "我方认为")),
        )
        val text = DebateConfigParser.encode(dto)
        val parsed = DebateConfigParser.parseAndValidate(text).getOrThrow()
        assertEquals("m1", parsed.matchId)
        assertEquals(1_000L, parsed.rules.openingMs)
        assertEquals("我方认为", parsed.scripts.getValue("PRO1").getValue("OPENING_STATEMENT"))
    }

    @Test
    fun `坏 JSON 解析失败`() {
        val result = runCatching { DebateConfigParser.parse("{ not json") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `未知字段直接拒绝`() {
        val result = runCatching {
            DebateConfigParser.parse("""{"matchId":"m1","topic":"t","surprise":1,"participants":[]}""")
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun `展开导出补齐默认值`() {
        val dto = DebateConfigDto(matchId = "m", topic = "t", participants = testParticipants())
        val expanded = dto.expanded()
        assertEquals("four_person_attack_v1", expanded.rules.template)
        assertTrue(expanded.rules.openingMs > 0)
    }
}

class DebateConfigValidatorTest {

    private fun valid(): DebateConfigDto =
        DebateConfigDto(matchId = "m1", topic = "辩题", participants = testParticipants())

    @Test
    fun `合法配置零问题`() {
        assertTrue(DebateConfigValidator.validate(valid()).isEmpty())
    }

    @Test
    fun `缺主持与队伍人数不对各自报错`() {
        val noHost = DebateConfigDto(
            matchId = "m", topic = "t",
            participants = testParticipants().filter { it.role != "host" },
        )
        val issues = DebateConfigValidator.validate(noHost)
        assertTrue(issues.any { it.path == "participants" && it.reason.contains("host") })

        val shortTeam = valid().copy(
            participants = testParticipants().filter { it.id != "CON4" },
        )
        val issues2 = DebateConfigValidator.validate(shortTeam)
        assertTrue(issues2.any { it.reason.contains("CON 队") })
    }

    @Test
    fun `重复 ID 与非法辩位`() {
        val dup = valid().copy(
            participants = testParticipants() + testParticipants().first { it.id == "PRO1" },
        )
        assertTrue(DebateConfigValidator.validate(dup).any { it.reason.contains("重复") })

        val badPos = valid().copy(
            participants = testParticipants().map { if (it.id == "PRO1") it.copy(id = "PROX") else it },
        )
        val issues = DebateConfigValidator.validate(badPos)
        assertTrue(issues.any { it.path.contains(".id") })
        assertTrue(issues.any { it.reason.contains("辩位") })
    }

    @Test
    fun `时间为零或负数报错`() {
        val bad = valid().copy(rules = valid().rules.copy(openingMs = 0L, freeTurnMs = -1L))
        val issues = DebateConfigValidator.validate(bad)
        assertTrue(issues.any { it.path == "rules.openingMs" })
        assertTrue(issues.any { it.path == "rules.freeTurnMs" })
    }

    @Test
    fun `提问上限超过轮墙钟报错`() {
        val bad = valid().copy(rules = valid().rules.copy(questionMs = 120_000L))
        assertTrue(DebateConfigValidator.validate(bad).any { it.path == "rules.questionMs" })
    }

    @Test
    fun `模型来源必须带前缀`() {
        val bad = valid().copy(
            participants = testParticipants().map { if (it.id == "HOST") it.copy(model = "10.vrm") else it },
        )
        assertTrue(DebateConfigValidator.validate(bad).any { it.path.contains("model") })
    }

    @Test
    fun `scripts 引用未知参与者或未知阶段报错`() {
        val bad = valid().copy(
            scripts = mapOf(
                "GHOST" to mapOf("HOST_OPENING" to "x"),
                "PRO1" to mapOf("NOT_A_PHASE" to "y"),
            ),
        )
        val issues = DebateConfigValidator.validate(bad)
        assertTrue(issues.any { it.path == "scripts.GHOST" })
        assertTrue(issues.any { it.path == "scripts.PRO1.NOT_A_PHASE" })
    }

    @Test
    fun `未知模板报错`() {
        val bad = valid().copy(rules = valid().rules.copy(template = "oxford_style"))
        assertTrue(DebateConfigValidator.validate(bad).any { it.path == "rules.template" })
    }
}

/** 合法的九人配置参与者（asset: 前缀模型）。 */
fun testParticipants(): List<DebateConfigDto.ParticipantDto> = buildList {
    add(DebateConfigDto.ParticipantDto(id = "HOST", name = "主持人", role = "host", model = "asset:vrms/10.vrm"))
    (1..4).forEach { i ->
        add(
            DebateConfigDto.ParticipantDto(
                id = "PRO$i", name = "正方$i", role = "debater", team = "PRO", position = i,
                model = "asset:vrms/10.vrm", personality = "正方风格$i",
            )
        )
    }
    (1..4).forEach { i ->
        add(
            DebateConfigDto.ParticipantDto(
                id = "CON$i", name = "反方$i", role = "debater", team = "CON", position = i,
                model = "asset:vrms/4705834820915235427.vrm", personality = "反方风格$i",
            )
        )
    }
}
