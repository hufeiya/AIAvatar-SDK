package com.neethu.orchestrator.autonomy

import com.neethu.orchestrator.activity.ActivityCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * P0 道具系统与 FSM 的集成面（docs/prop-system-feasibility.md §3.1）：
 * DoActivity directive 成对跟随 GoTo、选点层 hourRange 过滤、作息表
 * activityWeights（category→倍率）参与加权、活动 minDwellMs 抬高就位停留。
 * 时间与随机全部注入（同 AutonomyDirectorTest 惯例）。
 */
class ActivityAutonomyTest {

    private val clock = AtomicLong(1_000_000L)
    private var hour = 14

    private val noIdle = AutonomySchedule(
        baseWeights = mapOf(
            AutonomyStateKind.IDLE_STAND to 0f,
            AutonomyStateKind.WANDER to 0f,
            AutonomyStateKind.LOOKING to 0f,
            AutonomyStateKind.SITTING to 1f,
            AutonomyStateKind.LYING to 0f,
        ),
        hourRules = emptyList(),
    )

    private fun catalog(vararg entries: String) = ActivityCatalog.parse(
        """
        { "activities": [
            ${entries.joinToString(",\n")}
        ] }
        """.trimIndent(),
    )

    /** desk_reading:hourRange 9-23(含 9 舍 23) work 类,minDwell 20min。 */
    private val reading = """
        { "id": "reading", "name": "看书", "desc": "看书", "category": "daily",
          "anchor": "reading_seat", "animation": "06_坐姿/X.vrma",
          "minDwellMs": 1200000, "hourRange": [9, 23] }
    """.trimIndent()

    private val typing = """
        { "id": "desk_typing", "name": "打字", "desc": "打字", "category": "work",
          "anchor": "desk_seat", "animation": "07_日常互动/Y.vrma" }
    """.trimIndent()

    private fun spots(catalog: ActivityCatalog) = listOf(
        AutonomySpot("reading_seat", AutonomyStateKind.SITTING, x = 2.8f, z = -3.8f, activityIds = listOf("reading")),
        AutonomySpot("desk_seat", AutonomyStateKind.SITTING, x = 1f, z = -5.5f, activityIds = listOf("desk_typing")),
    )

    private fun director(
        schedule: AutonomySchedule = noIdle,
        activities: ActivityCatalog = catalog(reading, typing),
        seed: Long = 42,
        holidayTags: Set<String> = emptySet(),
        spotsOverride: List<AutonomySpot>? = null,
    ) = AutonomyDirector(
        spots = spotsOverride ?: spots(activities),
        schedule = schedule,
        activities = activities,
        rng = Random(seed),
        nowMs = clock::get,
        hourProvider = { hour },
        holidayTags = { holidayTags },
    )

    private fun idleWorld() = AutonomyWorld()

    // ── DoActivity directive ────────────────────────────────────────────────

    @Test
    fun `选中带活动的点——GoTo 后成对跟 DoActivity`() {
        val d = director()
        d.mount()
        clock.addAndGet(120_000)
        val out = d.tick(idleWorld())
        val goTo = out.filterIsInstance<AutonomyDirective.GoTo>().single()
        val doActivity = out.filterIsInstance<AutonomyDirective.DoActivity>().single()
        assertEquals(goTo.spot.activityId, doActivity.activityId)
    }

    @Test
    fun `活动点未命中目录——只发 GoTo 不发 DoActivity`() {
        // spots 带 activityId 但目录为空（资产缺席）：决策不崩，活动静默缺席
        val d = director(activities = ActivityCatalog.EMPTY)
        d.mount()
        clock.addAndGet(120_000)
        val out = d.tick(idleWorld())
        assertTrue(out.filterIsInstance<AutonomyDirective.GoTo>().isNotEmpty())
        assertTrue(out.filterIsInstance<AutonomyDirective.DoActivity>().isEmpty())
    }

    // ── hourRange 选点过滤 ──────────────────────────────────────────────────

    @Test
    fun `作息窗外的活动点不参与选点——窗内正常`() {
        val d = director()
        d.mount()
        // 8 点：reading(9-23) 窗外 → 100 次决策只可能落在 desk_seat
        hour = 8
        repeat(100) { d.chooseNext(8) }
        assertTrue(
            generateSequence { d.chooseNext(8) }.take(100).all { it.second?.id != "reading_seat" },
        )
        // 10 点：reading 窗内 → 两个点都可选（固定种子下必有 reading）
        hour = 10
        assertTrue(
            generateSequence { d.chooseNext(10) }.take(100).any { it.second?.id == "reading_seat" },
        )
    }

    // ── activityWeights 加权 ────────────────────────────────────────────────

    @Test
    fun `activityWeights 压零——该类别活动点选不中`() {
        val d = director(
            schedule = noIdle.copy(activityWeights = mapOf("daily" to 0f)),
        )
        d.mount()
        val picks = generateSequence { d.chooseNext(14) }.take(100).mapNotNull { it.second?.id }.toList()
        assertTrue(picks.isNotEmpty())
        assertTrue("daily 压零后不应再选中 reading_seat", picks.none { it == "reading_seat" })
        assertTrue(picks.all { it == "desk_seat" })
    }

    @Test
    fun `小时规则叠乘 activityWeights——夜里 work 归零`() {
        val schedule = AutonomySchedule(
            baseWeights = noIdle.baseWeights,
            hourRules = listOf(
                AutonomySchedule.HourRule(
                    from = 0, to = 6,
                    weights = emptyMap(),
                    activityWeights = mapOf("work" to 0f),
                ),
            ),
        )
        val d = director(schedule = schedule)
        d.mount()
        // 凌晨 2 点 work×0 → 只剩 reading（daily 无权重项 = 1）
        val night = generateSequence { d.chooseNext(2) }.take(100).mapNotNull { it.second?.id }
        assertTrue(night.none { it == "desk_seat" })
    }

    // ── schedule.json activityWeights 解析 ──────────────────────────────────

    @Test
    fun `作息表解析——基线与小时规则叠乘且缺省为空表`() {
        val json = """
            { "weights": {"SITTING": 1.0},
              "activityWeights": { "daily": 0.5 },
              "hours": [ { "from": 9, "to": 17, "weights": {"SITTING": 3.0},
                           "activityWeights": { "work": 2.0, "daily": 3.0 } } ] }
        """.trimIndent()
        val parsed = AutonomySchedule.parse(json)
        // 基线：daily 0.5；白天 9-17：work 2.0、daily 0.5×3=1.5；夜里基线原样
        assertEquals(mapOf("daily" to 0.5f), parsed.activityWeightsFor(3))
        assertEquals(2.0f, parsed.activityWeightsFor(10)["work"])
        assertEquals(1.5f, parsed.activityWeightsFor(10)["daily"])
        // 状态权重解析不受新字段影响（回归）
        assertEquals(3.0f, parsed.weightsFor(10)[AutonomyStateKind.SITTING])
        // 脏/缺 activityWeights = 空表（全 1,分布不动）
        assertEquals(emptyMap<String, Float>(), AutonomySchedule.parse("""{"weights":{}}""").activityWeights)
    }

    // ── minDwellMs ──────────────────────────────────────────────────────────

    @Test
    fun `带活动就位——dwell 不低于活动 minDwellMs`() {
        val d = director()
        d.mount()
        // 只留 reading_seat：决策必落它（reading minDwell 20min）
        d.spots = listOf(d.spots.first { it.id == "reading_seat" })
        clock.addAndGet(120_000)
        d.tick(idleWorld()) // GoTo(+DoActivity)
        d.tick(idleWorld().copy(autonomyInFlight = true))
        clock.addAndGet(60_000)
        d.tick(idleWorld()) // 就位 → onArrived
        val next = d.nextDecisionAtMs - clock.get()
        // 坐姿 dwell 3~10min;reading minDwell 20min → 至少 20min
        assertTrue("nextIn=${next}ms", next >= 1_200_000)
    }

    @Test
    fun `无活动的点——dwell 不受影响（回归）`() {
        val d = director()
        d.mount()
        val bare = listOf(AutonomySpot("plain_seat", AutonomyStateKind.SITTING, x = 0f, z = 0f))
        d.spots = bare
        clock.addAndGet(120_000)
        d.tick(idleWorld())
        d.tick(idleWorld().copy(autonomyInFlight = true))
        clock.addAndGet(60_000)
        d.tick(idleWorld())
        val next = d.nextDecisionAtMs - clock.get()
        assertTrue("nextIn=${next}ms", next in 180_000..600_000)
    }

    // ── P2 节日活动包：多候选锚点 + 节日加权（docs/prop-system-feasibility.md §5-H）──

    /** 窗边日常(6-11 热饮) + 叠挂中秋赏月(18-24 holiday=mid_autumn) + 对照锚点。 */
    private fun holidayCatalog() = catalog(
        """
        { "id": "hot_drink", "name": "热饮", "desc": "热饮", "category": "food",
          "anchor": "window_view", "animation": "A.vrma", "hourRange": [6, 11] }
        """.trimIndent(),
        """
        { "id": "mooncake", "name": "赏月", "desc": "赏月", "category": "festival",
          "anchor": "window_view", "animation": "B.vrma",
          "hourRange": [18, 24], "holiday": "mid_autumn" }
        """.trimIndent(),
        """
        { "id": "plain_act", "name": "日常", "desc": "日常", "category": "daily",
          "anchor": "rug", "animation": "C.vrma" }
        """.trimIndent(),
    )

    private fun holidaySpots() = listOf(
        AutonomySpot("window_view", AutonomyStateKind.LOOKING, x = 0f, z = 0f, look = listOf(0f, 1f, -5f), activityIds = listOf("hot_drink", "mooncake")),
        AutonomySpot("rug", AutonomyStateKind.WANDER, x = 1f, z = 0f, activityIds = listOf("plain_act")),
    )

    @Test
    fun `无节日标签——窗外锚点不开门且窗内照常可选（回归）`() {
        val d = director(
            activities = holidayCatalog(),
            spotsOverride = holidaySpots(),
            schedule = AutonomySchedule(
                baseWeights = mapOf(
                    AutonomyStateKind.IDLE_STAND to 0f,
                    AutonomyStateKind.WANDER to 0f,
                    AutonomyStateKind.LOOKING to 1f,
                ),
                hourRules = emptyList(),
            ),
        )
        d.mount()
        // 20 点:主活动 hot_drink(6-11)窗外、无节日 → window_view 不开门
        assertTrue(generateSequence { d.chooseNext(20) }.take(100).none { it.second?.id == "window_view" })
        // 8 点:主活动窗内 → 照常可选(P1 行为逐位不变;每轮重置锚点防自排除)
        repeat(100) {
            d.forceStateForTest(AutonomyStateKind.IDLE_STAND, null)
            assertEquals("window_view", d.chooseNext(8).second?.id)
        }
    }

    @Test
    fun `节日命中——窗外锚点重新开门且节日活动出线`() {
        val d = director(
            activities = holidayCatalog(),
            spotsOverride = holidaySpots(),
            schedule = AutonomySchedule(
                baseWeights = mapOf(AutonomyStateKind.IDLE_STAND to 0f, AutonomyStateKind.WANDER to 0f, AutonomyStateKind.LOOKING to 1f),
                hourRules = emptyList(),
            ),
            holidayTags = setOf("mid_autumn"),
        )
        d.mount()
        // 20 点:hot_drink(6-11)窗外,但 mooncake(18-24)节日命中 → 锚点开门
        repeat(100) {
            d.forceStateForTest(AutonomyStateKind.IDLE_STAND, null)
            assertEquals("window_view", d.chooseNext(20).second?.id)
        }
    }

    @Test
    fun `节日命中——DoActivity 带出节日活动而非主活动`() {
        val d = director(
            activities = holidayCatalog(),
            spotsOverride = holidaySpots(),
            schedule = AutonomySchedule(
                baseWeights = mapOf(AutonomyStateKind.IDLE_STAND to 0f, AutonomyStateKind.WANDER to 0f, AutonomyStateKind.LOOKING to 1f),
                hourRules = emptyList(),
            ),
            holidayTags = setOf("mid_autumn"),
        )
        d.mount()
        hour = 20 // hot_drink 窗外、mooncake 窗内
        clock.addAndGet(120_000)
        val out = d.tick(idleWorld())
        assertEquals("mooncake", out.filterIsInstance<AutonomyDirective.DoActivity>().single().activityId)
    }

    @Test
    fun `节日强倍率——同 kind 候选里节日锚点压倒距离劣势`() {
        // 节日锚点放远（衰减 1/(1+8/4)=0.33）,对照锚点放脚边（≈1.0）
        val d = director(
            activities = holidayCatalog(),
            spotsOverride = listOf(
                AutonomySpot("window_view", AutonomyStateKind.WANDER, x = 8f, z = 0f, activityIds = listOf("hot_drink", "mooncake")),
                AutonomySpot("rug", AutonomyStateKind.WANDER, x = 0.2f, z = 0f, activityIds = listOf("plain_act")),
            ),
            schedule = AutonomySchedule(
                baseWeights = mapOf(AutonomyStateKind.WANDER to 1f),
                hourRules = emptyList(),
            ),
            holidayTags = setOf("mid_autumn"),
        )
        d.mount()
        // 20 点:rug 的 plain_act 可选,window_view 靠节日开门+×8 → 压倒 4 倍距离劣势
        // (每轮重置锚点,消禁忌过滤的自排除偏置)
        var window = 0
        var rug = 0
        repeat(200) {
            d.forceStateForTest(AutonomyStateKind.IDLE_STAND, null)
            when (d.chooseNext(20).second?.id) {
                "window_view" -> window++
                "rug" -> rug++
            }
        }
        assertTrue("window_view=$window 应占多数(>$rug)", window > rug)
    }

    @Test
    fun `节日活动 hourRange 窗外——不开门不出线（白天不是中秋夜）`() {
        val d = director(
            activities = holidayCatalog(),
            spotsOverride = holidaySpots(),
            schedule = AutonomySchedule(
                baseWeights = mapOf(AutonomyStateKind.IDLE_STAND to 0f, AutonomyStateKind.WANDER to 0f, AutonomyStateKind.LOOKING to 1f),
                hourRules = emptyList(),
            ),
            holidayTags = setOf("mid_autumn"),
        )
        d.mount()
        // 15 点:hot_drink 窗外、mooncake(18-24)也窗外 → 锚点不开门
        assertTrue(generateSequence { d.chooseNext(15) }.take(100).none { it.second?.id == "window_view" })
    }
}
