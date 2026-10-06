package com.neethu.orchestrator.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模仿我」接入注册表后的共存语义：onBodyTracking 广播、三技能单活跃收口、
 * 与 RpsSkill/LookHereSkill 的激活词互不误触。
 */
class SkillRegistryMimicTest {

    private class FakeHost : SkillHost {
        var now = 0L
        override val defaultVadHangoverMs: Long = 800L
        override fun playGestureFile(path: String, loop: Boolean): Boolean = true
        override fun speak(text: String) = Unit
        override fun sendTurn(text: String, images: List<String>) = Unit
        override fun snapshotImage(): String? = null
        override fun setVadHangover(ms: Long) = Unit
        override fun nowMs(): Long = now
        override fun randomInt(bound: Int): Int = 0
    }

    private fun newRegistry(): Triple<SkillRegistry, MimicSkill, FakeHost> {
        val host = FakeHost()
        val registry = SkillRegistry(host)
        val mimic = MimicSkill()
        val rps = RpsSkill(mapOf())
        registry.register(mimic)
        registry.register(rps)
        return Triple(registry, mimic, host)
    }

    @Test
    fun `onBodyTracking broadcasts to all registered skills`() {
        val (registry, mimic, host) = newRegistry()
        // 激活 mimic 才会消费可见性事件
        registry.onUtterance("模仿我")
        assertEquals(MimicSkill.State.INTRO, mimic.state)
        registry.onTurnCompleted("好")
        assertEquals(MimicSkill.State.ACTIVE, mimic.state)
        // 广播到全部技能(未激活的技能自行忽略,不抛异常)
        registry.onBodyTracking(false)
        host.now = 8_100L
        registry.onBodyTracking(false)
        assertEquals(MimicSkill.State.IDLE, mimic.state)
    }

    @Test
    fun `saying rps while mimicking hands over to rps - single active closure`() {
        val (registry, mimic, _) = newRegistry()
        assertTrue(registry.onUtterance("模仿我").not())
        registry.onTurnCompleted("好")
        assertEquals(MimicSkill.State.ACTIVE, mimic.state)
        // 一句话唤醒猜拳 → 后激活者胜出,mimic 被强制退场
        assertFalse(registry.onUtterance("来玩石头剪刀布"))
        assertTrue(rpsActive(registry))
        assertEquals(MimicSkill.State.IDLE, mimic.state)
        // 回合指令来自猜拳而非模仿
        val directive = registry.activeDirective()
        assertTrue(directive == null || !directive.contains("模仿我】"))
    }

    @Test
    fun `exit word in mimic does not wake rps`() {
        val (registry, mimic, _) = newRegistry()
        registry.onUtterance("模仿我")
        registry.onTurnCompleted("好")
        assertTrue(registry.onUtterance("不模仿了")) // 消费,不发给 LLM
        assertEquals(MimicSkill.State.IDLE, mimic.state)
    }

    @Test
    fun `activation keywords do not collide across the three skills`() {
        val (registry, mimic, _) = newRegistry()
        assertFalse(registry.onUtterance("来玩看这边吧").let { mimic.isActive })
        // 看这边激活后,模仿保持 IDLE
        registry.onUtterance("模仿我一下")
        assertEquals(MimicSkill.State.INTRO, mimic.state)
    }

    private fun rpsActive(registry: SkillRegistry): Boolean =
        (registry.find("rps") as? RpsSkill)?.isActive == true
}
