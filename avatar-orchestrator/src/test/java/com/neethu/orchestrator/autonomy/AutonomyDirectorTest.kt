package com.neethu.orchestrator.autonomy

import com.neethu.corelib.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * 自主 FSM/调度/模板的 JVM 单测（docs/autonomy-feasibility.md §8 P0-b）。
 * 时间与随机全部注入：[clock] 逐测试手动推进，rng 固定种子。[noIdle] 作息
 * 把站桩权重归零，让「决策必有 GoTo」成为确定前提。
 */
class AutonomyDirectorTest {

    private val clock = AtomicLong(1_000_000L)
    private var hour = 10

    private val noIdle = AutonomySchedule(
        baseWeights = mapOf(
            AutonomyStateKind.IDLE_STAND to 0f,
            AutonomyStateKind.WANDER to 1f,
            AutonomyStateKind.LOOKING to 1f,
            AutonomyStateKind.SITTING to 1f,
            AutonomyStateKind.LYING to 1f,
        ),
        hourRules = emptyList(),
    )

    private fun spots() = listOf(
        AutonomySpot("bed_sit", AutonomyStateKind.SITTING, x = -1.4f, z = -4.8f),
        AutonomySpot("desk_seat", AutonomyStateKind.SITTING, x = 1f, z = -5.5f),
        AutonomySpot("reading_seat", AutonomyStateKind.SITTING, x = 2.8f, z = -3.8f),
        AutonomySpot("bed_lie", AutonomyStateKind.LYING, x = -2.8f, z = -5f),
        AutonomySpot("window_view", AutonomyStateKind.LOOKING, x = 3.3f, z = -5.2f, look = listOf(5.5f, 1.2f, -5.2f)),
        AutonomySpot("tv_approach", AutonomyStateKind.WANDER, x = 0.2f, z = -1.7f),
        AutonomySpot("aisle_south", AutonomyStateKind.WANDER, x = -1.8f, z = -2.3f),
    )

    private fun director(
        spotList: List<AutonomySpot> = spots(),
        schedule: AutonomySchedule = noIdle,
        limits: AutonomyLimits = AutonomyLimits(),
        seed: Long = 42,
    ): AutonomyDirector = AutonomyDirector(
        spots = spotList,
        schedule = schedule,
        limits = limits,
        rng = Random(seed),
        nowMs = clock::get,
        hourProvider = { hour },
    )

    private fun idleWorld() = AutonomyWorld()

    // ── 决策 ────────────────────────────────────────────────────────────

    @Test
    fun `dwell 到点后决策发出 GoTo 且进入待执行`() {
        val d = director()
        d.mount()
        d.tick(idleWorld()) // 冷启动：站桩 dwell（20~60s）内不决策
        clock.addAndGet(120_000) // 站桩 dwell 一定过点
        val out = d.tick(idleWorld())
        assertTrue(out.filterIsInstance<AutonomyDirective.GoTo>().isNotEmpty())
        assertTrue(d.stateLine().contains("pending=true"))
    }

    @Test
    fun `GoTo 就位后按状态重设 dwell 不立即再决策`() {
        // 只有坐点 → 决策必为 SITTING，dwell 3~10min
        val sittingOnly = listOf(
            AutonomySpot("bed_sit", AutonomyStateKind.SITTING, x = -1.4f, z = -4.8f),
            AutonomySpot("desk_seat", AutonomyStateKind.SITTING, x = 1f, z = -5.5f),
        )
        val d = director(spotList = sittingOnly)
        d.mount()
        clock.addAndGet(120_000)
        d.tick(idleWorld()) // GoTo
        d.tick(idleWorld().copy(autonomyInFlight = true))
        clock.addAndGet(60_000)
        d.tick(idleWorld()) // 就位（inFlight 落 false）
        val next = d.nextDecisionAtMs - clock.get()
        // 坐姿 dwell 3~10min：就位后绝不该在 1min 内再决策
        assertTrue("nextIn=${next}ms", next > 60_000)
    }

    @Test
    fun `禁忌——不连去同一锚点`() {
        // 上限放开：cap 分支会清锚点归位站桩（设计内），会污染禁忌统计。
        // 三拍的指令流都算（b 拍决策可能在 c 拍就地消化返回空）。
        val d = director(seed = 7, limits = AutonomyLimits(maxActionsPerHour = 1000))
        d.mount()
        clock.addAndGet(120_000)
        var last: String? = null
        var issued = 0
        var repeated = 0
        fun consume(dirs: List<AutonomyDirective>) {
            for (dir in dirs) {
                val goTo = dir as? AutonomyDirective.GoTo ?: continue
                issued++
                if (goTo.spot.id == last) repeated++
                last = goTo.spot.id
            }
        }
        repeat(60) {
            consume(d.tick(idleWorld().copy(autonomyInFlight = true)))
            consume(d.tick(idleWorld()))
            clock.set(d.nextDecisionAtMs + 1)
            consume(d.tick(idleWorld()))
        }
        assertTrue("应发出足够多的决策验证禁忌 issued=$issued", issued > 40)
        assertEquals("60 轮决策不应连选同一点", 0, repeated)
    }

    @Test
    fun `躺完偏 WANDER——醒一醒`() {
        hour = 10
        val d = director(seed = 3)
        d.forceStateForTest(AutonomyStateKind.LYING, "bed_lie")
        var wander = 0
        repeat(200) {
            val (kind, _) = d.chooseNext(10)
            if (kind == AutonomyStateKind.WANDER) wander++
        }
        // LYING 后 WANDER ×3（占比 ≈3.6/7.2=50%），无加成约 21%
        assertTrue("wander 占比=$wander/200", wander > 60)
    }

    @Test
    fun `深夜作息——LYING 权重明显`() {
        // 用真实缺省作息表（带小时规则）；noIdle 是无规则表，深夜用例不适用
        hour = 2
        val d = director(seed = 5, schedule = AutonomySchedule.DEFAULT)
        d.forceStateForTest(AutonomyStateKind.IDLE_STAND, null)
        var lying = 0
        repeat(200) {
            val (kind, _) = d.chooseNext(2)
            if (kind == AutonomyStateKind.LYING) lying++
        }
        // 深夜 LYING ×5：约 5/9 ≈ 55%，下限放宽到 50%
        assertTrue("lying 占比=$lying/200", lying > 100)
    }

    @Test
    fun `躺卧时长深夜加长`() {
        val d = director(seed = 1)
        hour = 2
        val nightAvg = (1..30).map { d.dwellFor(AutonomyStateKind.LYING) }.average()
        hour = 14
        val dayAvg = (1..30).map { d.dwellFor(AutonomyStateKind.LYING) }.average()
        assertTrue("night=$nightAvg day=$dayAvg", nightAvg > dayAvg * 1.3)
    }

    // ── 冲突仲裁 ────────────────────────────────────────────────────────

    @Test
    fun `会话冻结——走动中只停步一次且状态归位`() {
        val d = director()
        d.mount()
        clock.addAndGet(120_000)
        d.tick(idleWorld()) // GoTo pending
        val busy = idleWorld().copy(phaseBusy = true, walking = true)
        assertEquals(listOf<AutonomyDirective>(AutonomyDirective.StopWalking), d.tick(busy))
        assertEquals(AutonomyStateKind.IDLE_STAND, d.state)
        assertTrue(d.tick(busy).isEmpty())
        assertTrue(d.tick(busy).isEmpty())
    }

    @Test
    fun `冻结期间剩余 dwell 不变，解冻到点立刻决策`() {
        val d = director()
        d.mount()
        d.tick(idleWorld())
        val remainingBefore = d.nextDecisionAtMs - clock.get()
        clock.addAndGet(1_000)
        d.tick(idleWorld().copy(phaseBusy = true))
        // 冻结期间 dwell 与墙钟同速顺延（每拍补偿一拍）：剩余量守恒
        assertEquals(remainingBefore.toDouble(), (d.nextDecisionAtMs - clock.get()).toDouble(), 1_500.0)
        clock.set(d.nextDecisionAtMs + 1)
        // 解冻且 dwell 已过：立刻出决策（noIdle 下必有 GoTo）
        assertTrue(d.tick(idleWorld()).filterIsInstance<AutonomyDirective.GoTo>().isNotEmpty())
    }

    @Test
    fun `外部走动结束——以新位置续跑不回弹`() {
        val d = director()
        d.mount()
        d.tick(idleWorld())
        // 外部走动（LLM move_to）：观望
        assertTrue(d.tick(idleWorld().copy(walking = true)).isEmpty())
        clock.addAndGet(30_000)
        assertTrue(d.tick(idleWorld().copy(walking = true)).isEmpty())
        // 走完：就地认领（无接触定姿 → 站桩保持，不弹 GoTo）
        assertTrue(d.tick(idleWorld()).isEmpty())
        // 外部把人放到了书桌坐：下一拍认领 SITTING
        d.tick(idleWorld().copy(recentExternalAction = true, contactKind = "sit", contactAnchorId = "desk_seat"))
        assertEquals(AutonomyStateKind.SITTING, d.state)
        assertEquals("desk_seat", d.currentAnchorId)
    }

    @Test
    fun `技能占用——FSM 让位不决策`() {
        val d = director()
        d.mount()
        clock.addAndGet(120_000)
        assertTrue(d.tick(idleWorld().copy(skillBusy = true)).isEmpty())
    }

    @Test
    fun `指令被拒——退避期内不再决策`() {
        val d = director()
        d.mount()
        clock.addAndGet(120_000)
        assertTrue(d.tick(idleWorld()).isNotEmpty()) // GoTo
        d.markDirectiveAborted()
        assertEquals(AutonomyStateKind.IDLE_STAND, d.state)
        clock.addAndGet(5_000)
        assertTrue("退避 30s 内不决策", d.tick(idleWorld()).isEmpty())
    }

    // ── 限速 ────────────────────────────────────────────────────────────

    @Test
    fun `每小时动作上限——超限纯站桩`() {
        val d = director(limits = AutonomyLimits(maxActionsPerHour = 2))
        d.mount()
        repeat(2) {
            clock.addAndGet(120_000)
            d.tick(idleWorld()) // GoTo
            d.markDirectiveAborted()
            clock.addAndGet(60_000) // 过退避
        }
        clock.addAndGet(120_000)
        val out = d.tick(idleWorld())
        assertTrue("超限后不应再有 GoTo", out.filterIsInstance<AutonomyDirective.GoTo>().isEmpty())
        assertTrue(d.stateLine().contains("actionsLastHour=2/2"))
    }

    // ── 观测 ────────────────────────────────────────────────────────────

    @Test
    fun `转移日志保留 20 条且 stateLine 可读`() {
        val d = director(seed = 9)
        d.mount()
        d.tick(idleWorld())
        repeat(30) {
            clock.addAndGet(120_000)
            if (d.tick(idleWorld()).isNotEmpty()) {
                d.tick(idleWorld().copy(autonomyInFlight = true))
                d.tick(idleWorld())
            }
            clock.set(d.nextDecisionAtMs + 1)
        }
        assertTrue(d.transitions.size <= 20)
        assertTrue(d.stateLine().startsWith("state="))
        assertTrue(d.logDump().isNotEmpty())
    }
}
