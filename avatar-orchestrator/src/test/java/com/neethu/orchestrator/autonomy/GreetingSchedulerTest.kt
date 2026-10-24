package com.neethu.orchestrator.autonomy

import com.neethu.corelib.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** 问候调度与作息表解析的 JVM 单测（docs/autonomy-feasibility.md §5/§4.2）。 */
class GreetingSchedulerTest {

    private val clock = AtomicLong(1_000_000L)
    private var hour = 10

    private fun scheduler(
        config: GreetingScheduler.Config = GreetingScheduler.Config(
            minIntervalMs = 100_000,
            meanIntervalMs = 200_000,
        ),
        seed: Long = 1,
    ) = GreetingScheduler(
        config = config,
        rng = Random(seed),
        nowMs = clock::get,
        hourProvider = { hour },
    )

    @Test
    fun `挂载后到点放行一次`() {
        val s = scheduler()
        s.mount()
        assertEquals(GreetingScheduler.Decision.Wait, s.poll(phaseBusy = false, userSpeaking = false))
        clock.addAndGet(1_000_000)
        assertEquals(GreetingScheduler.Decision.Fire, s.poll(phaseBusy = false, userSpeaking = false))
        // 放行后进入新一轮等待
        assertEquals(GreetingScheduler.Decision.Wait, s.poll(phaseBusy = false, userSpeaking = false))
    }

    @Test
    fun `会话忙或用户说话时推迟`() {
        val s = scheduler()
        s.mount()
        s.forceDue()
        assertEquals(GreetingScheduler.Decision.Wait, s.poll(phaseBusy = true, userSpeaking = false))
        assertEquals(GreetingScheduler.Decision.Wait, s.poll(phaseBusy = false, userSpeaking = true))
        // 忙结束即放行（推迟期已过）
        clock.addAndGet(30_000)
        assertEquals(GreetingScheduler.Decision.Fire, s.poll(phaseBusy = false, userSpeaking = false))
    }

    @Test
    fun `安静时段硬门——深夜自然问候永不放行`() {
        hour = 23
        val s = scheduler()
        s.mount()
        clock.addAndGet(1_000_000) // 自然到点（不走 forceDue 豁免）
        assertEquals(GreetingScheduler.Decision.Wait, s.poll(false, false))
        // 23 点之后任何时刻都闷住
        repeat(8) {
            clock.addAndGet(3_600_000)
            hour = (hour + 1) % 24
            if (hour >= 23 || hour < 8) {
                assertEquals(GreetingScheduler.Decision.Wait, s.poll(false, false))
            }
        }
    }

    @Test
    fun `安静时段结束恢复放行`() {
        hour = 2
        val s = scheduler()
        s.mount()
        clock.addAndGet(1_000_000) // 自然到点
        assertEquals(GreetingScheduler.Decision.Wait, s.poll(false, false))
        clock.addAndGet(6 * 3_600_000L) // 到早上 8 点
        hour = 8
        assertEquals(GreetingScheduler.Decision.Fire, s.poll(false, false))
    }

    @Test
    fun `forceDue 显式豁免安静时段（深夜验收入口）`() {
        hour = 2
        val s = scheduler()
        s.mount()
        s.forceDue()
        assertEquals(GreetingScheduler.Decision.Fire, s.poll(false, false))
    }

    @Test
    fun `每日上限封顶`() {
        val s = scheduler()
        s.mount()
        repeat(3) {
            s.forceDue()
            clock.addAndGet(1_000)
            assertEquals(GreetingScheduler.Decision.Fire, s.poll(false, false))
        }
        s.forceDue()
        assertEquals("第 4 次应被日上限拦住", GreetingScheduler.Decision.Wait, s.poll(false, false))
        assertTrue(s.stateLine().contains("firedToday=3/3"))
    }

    @Test
    fun `用户刚说话——冷却重置`() {
        val s = scheduler()
        s.mount()
        clock.addAndGet(500_000)
        s.onUserActivity() // 重排
        clock.addAndGet(10_000)
        assertEquals("重置后 10s 内不该放行", GreetingScheduler.Decision.Wait, s.poll(false, false))
    }

    @Test
    fun `卸载后不再问候`() {
        val s = scheduler()
        s.mount()
        s.unmount()
        clock.addAndGet(10_000_000)
        assertEquals(GreetingScheduler.Decision.Wait, s.poll(false, false))
    }
}

/** 作息表 JSON 解析与思考/问候模板的 JVM 单测。 */
class AutonomyScheduleTest {

    @Test
    fun `解析标准表并叠加小时乘子`() {
        val json = """
            {
              "weights": {"SITTING": 2.0},
              "hours": [
                {"from": 0, "to": 6, "weights": {"LYING": 5.0}},
                {"from": 18, "to": 23, "weights": {"LOOKING": 2.0}}
              ],
              "quietHours": {"from": 23, "to": 8}
            }
        """.trimIndent()
        val s = AutonomySchedule.parse(json)
        val noon = s.weightsFor(12)
        assertEquals(2f, noon[AutonomyStateKind.SITTING])
        val deepNight = s.weightsFor(3)
        assertEquals(5f, deepNight[AutonomyStateKind.LYING])
        val evening = s.weightsFor(20)
        assertEquals(2f, evening[AutonomyStateKind.LOOKING])
        assertTrue(s.inQuietHours(23))
        assertTrue(s.inQuietHours(3))
        assertTrue(!s.inQuietHours(12))
    }

    @Test
    fun `脏 JSON 整表回落默认`() {
        assertEquals(AutonomySchedule.DEFAULT, AutonomySchedule.parse("not json at all"))
    }

    @Test
    fun `跨午夜区间判定`() {
        assertTrue(AutonomySchedule.hourIn(2, 23, 8))
        assertTrue(AutonomySchedule.hourIn(23, 23, 8))
        assertTrue(!AutonomySchedule.hourIn(12, 23, 8))
        assertTrue(AutonomySchedule.hourIn(6, 0, 6) || true) // 0..6 的 to 舍
        assertTrue(!AutonomySchedule.hourIn(6, 0, 6))
    }
}

/** 思考/问候模板（zh/en）单测：无记忆分支、长度上限、语言切换。 */
class ThoughtComposerTest {

    private val rng = Random(7)

    @Test
    fun `各状态各时段都有中文思考可命中且不超 20 字`() {
        val c = ThoughtComposer(Lang.ZH, rng)
        for (state in AutonomyStateKind.entries) {
            for (hour in listOf(2, 9, 14, 20)) {
                val t = c.thoughtFor(state, hour, memoryFact = null)
                assertNotNull("state=$state hour=$hour", t)
                assertTrue("「$t」超 20 字", t!!.length <= 20)
            }
        }
    }

    @Test
    fun `记忆槽填入事实且无 null 漏出`() {
        val c = ThoughtComposer(Lang.ZH, rng)
        var seenMemory = false
        repeat(50) {
            val t = c.thoughtFor(AutonomyStateKind.SITTING, 14, memoryFact = "用户喜欢爬山")!!
            assertTrue(!t.contains("{m}") && !t.contains("null"))
            if (t.contains("爬山")) seenMemory = true
        }
        assertTrue("50 次应至少命中一次记忆槽", seenMemory)
    }

    @Test
    fun `问候文本——有记忆走记忆模板无记忆走通用池`() {
        val c = ThoughtComposer(Lang.ZH, rng)
        val withMemory = c.greetingText(10, "用户下周去东京出差")
        assertTrue(withMemory!!.contains("东京"))
        var plainHit = false
        repeat(10) {
            val t = c.greetingText(10, null)!!
            assertTrue(!t.contains("{m}") && !t.contains("null"))
            plainHit = plainHit || t.isNotEmpty()
        }
        assertTrue(plainHit)
    }

    @Test
    fun `英文模板可用`() {
        val c = ThoughtComposer(Lang.EN, rng)
        val t = c.thoughtFor(AutonomyStateKind.LYING, 20, null)
        assertNotNull(t)
        assertTrue(t!!.isNotBlank())
        assertTrue(c.greetingText(10, "they love hiking")!!.contains("hiking"))
    }
}
