package com.neethu.orchestrator.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「看这边」接入注册表后的共存语义：onHeadPose / onSpeakCompleted 广播、
 * 单活跃收口（一句话唤醒第二个技能时先激活的走 onExit 退场）。
 */
class SkillRegistryLookHereTest {

    private class FakeHost : SkillHost {
        val played = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var hangover: Long? = null
        var now = 0L
        var nextRandom = 0
        override val defaultVadHangoverMs: Long = 800L
        override fun playGestureFile(path: String, loop: Boolean): Boolean {
            played += path
            return true
        }
        override fun speak(text: String) {
            spoken += text
        }
        override fun sendTurn(text: String, images: List<String>) = Unit
        override fun snapshotImage(): String? = null
        override fun setVadHangover(ms: Long) {
            hangover = ms
        }
        override fun nowMs(): Long = now
        override fun randomInt(bound: Int): Int = if (bound <= 0) 0 else nextRandom % bound
    }

    private data class Rig(
        val registry: SkillRegistry,
        val host: FakeHost,
        val rps: RpsSkill,
        val look: LookHereSkill,
    )

    private fun newRegistry(): Rig {
        val host = FakeHost()
        val rps = RpsSkill(RpsSkill.Hand.entries.associateWith { "animations/gesture_${it.assetKey}.vrma" }) {
            host.nextRandom % it.coerceAtLeast(1)
        }
        val look = LookHereSkill(LookDir.entries.associateWith { "animations/gesture_${it.en}.vrma" }) {
            host.nextRandom % it.coerceAtLeast(1)
        }
        val registry = SkillRegistry(host)
        registry.register(rps)
        registry.register(look)
        return Rig(registry, host, rps, look)
    }

    @Test
    fun `look activation during an rps game force-exits rps`() {
        val rig = newRegistry()
        assertFalse(rig.registry.onUtterance("来玩猜拳吧"))
        assertEquals(RpsSkill.State.INVITED, rig.rps.state)
        assertTrue(rig.rps.isActive)
        // 游戏中用户又说"看这边"：两个技能同时转活 → 后激活者胜出
        assertFalse(rig.registry.onUtterance("看这边"))
        assertEquals(LookHereSkill.State.INTRO, rig.look.state)
        assertTrue(rig.look.isActive)
        assertEquals(RpsSkill.State.IDLE, rig.rps.state) // 先激活的被收口退场
        assertEquals(800L, rig.host.hangover) // rps 的 exit 副作用（VAD 悬停恢复）跑过
    }

    @Test
    fun `rps activation during a look game force-exits look`() {
        val rig = newRegistry()
        assertFalse(rig.registry.onUtterance("看这边"))
        assertEquals(LookHereSkill.State.INTRO, rig.look.state)
        assertFalse(rig.registry.onUtterance("来玩猜拳吧"))
        assertEquals(RpsSkill.State.INVITED, rig.rps.state)
        assertTrue(rig.rps.isActive)
        assertEquals(LookHereSkill.State.IDLE, rig.look.state) // 后激活的 rps 胜出
    }

    @Test
    fun `head pose and speak completion broadcast through the registry`() {
        val rig = newRegistry()
        assertFalse(rig.registry.onUtterance("看这边")) // look → INTRO
        rig.registry.onTurnCompleted("好呀!") // → 第一局开局（指 UP）
        assertEquals(LookHereSkill.State.POINTING, rig.look.state)
        assertEquals(listOf("animations/gesture_up.vrma"), rig.host.played)
        // 姿态流经 registry 广播进技能判定窗（连续 2 帧同向越线 → 本局落定）
        for (t in longArrayOf(0L, 80L, 160L, 240L)) {
            rig.host.now = t
            rig.registry.onHeadPose(0f, 0f)
        }
        rig.host.now = 320L; rig.registry.onHeadPose(18f, 0f) // 成基线帧
        rig.host.now = 400L; rig.registry.onHeadPose(20f, 0f)
        rig.host.now = 480L; rig.registry.onHeadPose(22f, 0f) // 确认 LEFT → 同向判负
        assertEquals(1, rig.look.round)
        assertEquals(1, rig.look.scoreMe)
        // go 信号完成 → 宣判（经 registry.onSpeakCompleted → announce → host.speak）
        rig.registry.onSpeakCompleted("看这边!")
        assertEquals(LookHereSkill.State.ANNOUNCING, rig.look.state)
        assertEquals(2, rig.host.spoken.size)
        assertTrue(rig.host.spoken.last().contains("抓到你了"))
    }
}
