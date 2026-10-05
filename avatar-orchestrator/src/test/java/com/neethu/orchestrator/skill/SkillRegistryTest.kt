package com.neethu.orchestrator.skill

import com.neethu.orchestrator.session.AvatarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能注册表单测：广播/消费语义、指令取值、事件包装、强制退场、host 缺省兜底。
 */
class SkillRegistryTest {

    private class StubSkill(override val id: String) : AvatarSkill {
        var active: Boolean = false
        var directive: String? = null
        var utterances = mutableListOf<String>()
        var consumed = false
        var vadCalls = mutableListOf<Long>()
        var exits = 0
        var debug: String? = null
        override val isActive: Boolean get() = active
        override fun turnDirective(ctx: SkillContext): String? = directive
        override fun onUtterance(text: String, ctx: SkillContext): Boolean {
            utterances += text
            return consumed
        }
        override fun onVadUtterance(wavMs: Long, ctx: SkillContext) {
            vadCalls += wavMs
        }
        override fun onExit(ctx: SkillContext) {
            exits++
            active = false // 与真实技能一致：退场时自清激活态
        }
        override fun debugCommand(arg: String?, ctx: SkillContext): String? = debug
    }

    private class CountingHost : SkillHost {
        override val defaultVadHangoverMs: Long = 800L
        override fun playGestureFile(path: String, loop: Boolean) = true
        override fun speak(text: String) = Unit
        override fun sendTurn(text: String, images: List<String>) = Unit
        override fun snapshotImage(): String? = null
        override fun setVadHangover(ms: Long) = Unit
        override fun nowMs() = 0L
        override fun randomInt(bound: Int) = 0
    }

    @Test
    fun `register replaces by id and find returns it`() {
        val registry = SkillRegistry(CountingHost())
        val a = StubSkill("rps")
        val b = StubSkill("rps")
        registry.register(a)
        registry.register(b)
        assertEquals(b, registry.find("rps"))
    }

    @Test
    fun `activeDirective is null with no skills or no active skill`() {
        val registry = SkillRegistry(CountingHost())
        assertNull(registry.activeDirective())
        val skill = StubSkill("rps")
        registry.register(skill)
        assertNull(registry.activeDirective()) // 未激活
        skill.active = true
        skill.directive = "D"
        assertEquals("D", registry.activeDirective())
    }

    @Test
    fun `first active skill wins the directive slot`() {
        val registry = SkillRegistry(CountingHost())
        val first = StubSkill("a").apply { active = true; directive = "A" }
        val second = StubSkill("b").apply { active = true; directive = "B" }
        registry.register(first)
        registry.register(second)
        assertEquals("A", registry.activeDirective())
    }

    @Test
    fun `utterance broadcasts and consumed short-circuits`() {
        val registry = SkillRegistry(CountingHost())
        val first = StubSkill("a").apply { consumed = true }
        val second = StubSkill("b")
        registry.register(first)
        registry.register(second)
        assertTrue(registry.onUtterance("三二一"))
        assertEquals(listOf("三二一"), first.utterances)
        // any() 短路后第二个技能是否收到属实现细节,只锁"有消费=true"
        registry.deactivate()
    }

    @Test
    fun `no consumption when all skills pass`() {
        val registry = SkillRegistry(CountingHost())
        registry.register(StubSkill("a"))
        assertFalse(registry.onUtterance("你好"))
    }

    @Test
    fun `vad utterance broadcasts to all registered skills`() {
        val registry = SkillRegistry(CountingHost())
        val a = StubSkill("a")
        val b = StubSkill("b")
        registry.register(a)
        registry.register(b)
        registry.onVadUtterance(1_234L)
        assertEquals(listOf(1_234L), a.vadCalls)
        assertEquals(listOf(1_234L), b.vadCalls)
    }

    @Test
    fun `skill events carry the emitting skill id`() {
        val events = mutableListOf<AvatarEvent>()
        val registry = SkillRegistry(CountingHost()) { events += it }
        val skill = StubSkill("rps")
        // 事件包装在 contextFor 里——经 turnDirective 的 ctx 发一条
        registry.register(skill)
        skill.directive = null
        // 直接触发:注册表构造的 ctx 在任何回调里都能发事件
        val probe = object : AvatarSkill {
            override val id = "probe"
            override val isActive = true
            override fun turnDirective(ctx: SkillContext): String? {
                ctx.event("hello")
                return null
            }
        }
        registry.register(probe)
        registry.activeDirective()
        val ev = events.filterIsInstance<AvatarEvent.SkillEvent>().single()
        assertEquals("probe", ev.skillId)
        assertEquals("hello", ev.detail)
    }

    @Test
    fun `deactivate exits only active skills and reports count`() {
        val registry = SkillRegistry(CountingHost())
        val idle = StubSkill("idle")
        val active = StubSkill("rps").apply { active = true }
        registry.register(idle)
        registry.register(active)
        assertEquals("deactivated 1 skill(s)", registry.deactivate())
        assertEquals(1, active.exits)
        assertEquals(0, idle.exits)
        assertEquals("no active skill", registry.deactivate())
    }

    @Test
    fun `debug routes by id and unknown id explains`() {
        val registry = SkillRegistry(CountingHost())
        val skill = StubSkill("rps").apply { debug = "rps: ok" }
        registry.register(skill)
        assertEquals("rps: ok", registry.debug("rps", "status"))
        assertTrue(registry.debug("nope", "status").contains("no skill registered"))
        registry.register(StubSkill("mute"))
        assertEquals("mute: no debug handler for 'x'", registry.debug("mute", "x"))
    }

    @Test
    fun `null host falls back to nop host without crashing`() {
        val registry = SkillRegistry(null)
        val skill = RpsSkill(
            mapOf(RpsSkill.Hand.ROCK to "a.vrma", RpsSkill.Hand.SCISSORS to "b.vrma", RpsSkill.Hand.PAPER to "c.vrma"),
        )
        registry.register(skill)
        // 激活词命中但 IDLE 分支不消费（正常发送语义）
        assertFalse(registry.onUtterance("玩猜拳"))
        assertEquals(RpsSkill.State.INVITED, skill.state)
        skill.onTurnCompleted("好", SkillContext(NopSkillHost))
        // NopSkillHost: 播放返回 false、抓拍 null,但状态机照常走完
        registry.onVadUtterance(1_000L)
        assertEquals(RpsSkill.State.THROWN, skill.state)
        assertNull(skill.pendingThrow?.frame)
        assertTrue(registry.onUtterance("三二一"))
        skill.onTurnCompleted("你没看到吧", SkillContext(NopSkillHost))
        assertEquals(RpsSkill.State.ARMED, skill.state)
    }
}
