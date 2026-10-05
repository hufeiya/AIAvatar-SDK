package com.neethu.orchestrator.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 猜拳技能纯逻辑单测：状态机全迁移表 + 触发启发式 + 指令文本 + 可注入 RNG。
 * 真机验收（出拳延迟/手势观感/LLM 裁判质量）见 docs/rps-skill-feasibility.md §6 P0。
 */
class RpsSkillTest {

    private class FakeHost : SkillHost {
        val played = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, List<String>>>()
        var hangover: Long? = null
        var frame: String? = "data:image/jpeg;base64,FAKE"
        var nextRandom = 0
        override val defaultVadHangoverMs: Long = 800L
        override fun playGestureFile(path: String, loop: Boolean): Boolean {
            played += path
            return true
        }
        override fun speak(text: String) {
            spoken += text
        }
        override fun sendTurn(text: String, images: List<String>) {
            sent += text to images
        }
        override fun snapshotImage(): String? = frame
        override fun setVadHangover(ms: Long) {
            hangover = ms
        }
        override fun nowMs(): Long = 0L
        override fun randomInt(bound: Int): Int = if (bound <= 0) 0 else nextRandom % bound
    }

    private val assets = mapOf(
        RpsSkill.Hand.ROCK to "animations/gesture_rock.vrma",
        RpsSkill.Hand.SCISSORS to "animations/gesture_scissor.vrma",
        RpsSkill.Hand.PAPER to "animations/gesture_paper.vrma",
    )

    private fun newSkill(): Triple<RpsSkill, FakeHost, SkillContext> {
        val host = FakeHost()
        val skill = RpsSkill(assets) { bound -> host.nextRandom % bound.coerceAtLeast(1) }
        return Triple(skill, host, SkillContext(host) { })
    }

    /** IDLE → INVITED（激活回合不消费）→ 回合完成 → ARMED。 */
    private fun activateToArmed(skill: RpsSkill, host: FakeHost, ctx: SkillContext) {
        assertFalse(skill.onUtterance("我们来玩猜拳吧", ctx))
        assertEquals(RpsSkill.State.INVITED, skill.state)
        assertEquals(RpsSkill.FAST_HANGOVER_MS, host.hangover)
        skill.onTurnCompleted("好呀！", ctx)
        assertEquals(RpsSkill.State.ARMED, skill.state)
    }

    // ── 激活 / 退出 ──────────────────────────────────────────────────────

    @Test
    fun `activation keyword enters INVITED and shortens vad hangover`() {
        val (skill, host, ctx) = newSkill()
        assertFalse(skill.onUtterance("来玩猜拳吧", ctx))
        assertEquals(RpsSkill.State.INVITED, skill.state)
        assertTrue(skill.isActive)
        assertEquals(RpsSkill.FAST_HANGOVER_MS, host.hangover)
    }

    @Test
    fun `non keyword in IDLE is a no-op`() {
        val (skill, host, ctx) = newSkill()
        assertFalse(skill.onUtterance("今天天气不错", ctx))
        assertEquals(RpsSkill.State.IDLE, skill.state)
        assertNull(host.hangover)
        assertNull(skill.turnDirective(ctx))
    }

    @Test
    fun `exit keyword restores default hangover and returns to IDLE`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        assertFalse(skill.onUtterance("不玩了不玩了", ctx))
        assertEquals(RpsSkill.State.IDLE, skill.state)
        assertEquals(800L, host.hangover)
        assertFalse(skill.isActive)
    }

    @Test
    fun `forced exit via onExit also restores hangover`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        skill.onExit(ctx)
        assertEquals(RpsSkill.State.IDLE, skill.state)
        assertEquals(800L, host.hangover)
    }

    // ── 出拳触发（VAD 快路径）────────────────────────────────────────────

    @Test
    fun `short vad in ARMED throws locally with mapped gesture and snapshot frame`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        host.nextRandom = 0 // ROCK
        skill.onVadUtterance(1_200L, ctx)
        assertEquals(RpsSkill.State.THROWN, skill.state)
        assertEquals(1, skill.round)
        assertEquals(RpsSkill.Hand.ROCK, skill.lastChoice)
        assertEquals(listOf("animations/gesture_rock.vrma"), host.played)
        assertEquals("data:image/jpeg;base64,FAKE", skill.pendingThrow?.frame)
    }

    @Test
    fun `long vad in ARMED does not throw`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        skill.onVadUtterance(RpsSkill.THROW_MAX_WAV_MS + 1, ctx)
        assertEquals(RpsSkill.State.ARMED, skill.state)
        assertTrue(host.played.isEmpty())
    }

    @Test
    fun `throw outside ARMED is ignored`() {
        val (skill, host, ctx) = newSkill()
        skill.onVadUtterance(500L, ctx) // IDLE：不出拳
        assertEquals(RpsSkill.State.IDLE, skill.state)
        assertFalse(skill.onUtterance("来玩猜拳", ctx)) // → INVITED
        assertEquals(RpsSkill.State.INVITED, skill.state)
        skill.onVadUtterance(500L, ctx) // INVITED：激活回合还没完，不能出拳
        assertEquals(RpsSkill.State.INVITED, skill.state)
        assertTrue(host.played.isEmpty())
    }

    @Test
    fun `rng index maps to each hand asset`() {
        for (seed in 0..2) {
            val (skill, host, ctx) = newSkill()
            activateToArmed(skill, host, ctx)
            host.nextRandom = seed
            skill.onVadUtterance(900L, ctx)
            val expected = RpsSkill.Hand.entries[seed]
            assertEquals(expected, skill.lastChoice)
            assertEquals(listOf(assets[expected]), host.played)
        }
    }

    // ── 裁判回合 ─────────────────────────────────────────────────────────

    @Test
    fun `utterance in THROWN is consumed as the judge turn with the frame`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        host.nextRandom = 0
        skill.onVadUtterance(1_000L, ctx)
        assertTrue(skill.onUtterance("三二一！", ctx)) // 消费
        assertEquals(RpsSkill.State.JUDGING, skill.state)
        assertEquals(1, host.sent.size)
        val (text, images) = host.sent.single()
        assertEquals("三二一！", text)
        assertEquals(listOf("data:image/jpeg;base64,FAKE"), images)
        // 指令带本地出拳结果与局数
        val directive = skill.turnDirective(ctx)
        assertTrue(directive!!.contains("石头"))
        assertTrue(directive.contains("第1局"))
        assertTrue(directive.contains("不要改口"))
    }

    @Test
    fun `blank utterance in THROWN still judges with placeholder text`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        skill.onVadUtterance(1_000L, ctx)
        assertTrue(skill.onUtterance("", ctx))
        assertEquals("（出拳）", host.sent.single().first)
    }

    @Test
    fun `throw without a camera frame judges without image and says so in directive`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        host.frame = null
        skill.onVadUtterance(1_000L, ctx)
        assertTrue(skill.onUtterance("三二一", ctx))
        assertTrue(host.sent.single().second.isEmpty())
        assertTrue(skill.turnDirective(ctx)!!.contains("没能抓拍到用户画面"))
    }

    @Test
    fun `judge turn completion returns to ARMED for the next round`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        skill.onVadUtterance(1_000L, ctx)
        skill.onUtterance("三二一", ctx)
        skill.onTurnCompleted("你赢了！", ctx)
        assertEquals(RpsSkill.State.ARMED, skill.state)
        assertEquals(1, skill.round)
        // 第二拳局数递增
        skill.onVadUtterance(900L, ctx)
        assertEquals(2, skill.round)
        assertEquals(RpsSkill.State.THROWN, skill.state)
    }

    @Test
    fun `judge turn failure returns to ARMED`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        skill.onVadUtterance(1_000L, ctx)
        skill.onUtterance("三二一", ctx)
        skill.onTurnFailed(ctx)
        assertEquals(RpsSkill.State.ARMED, skill.state)
    }

    @Test
    fun `new utterance during JUDGING self-heals to ARMED`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        skill.onVadUtterance(1_000L, ctx)
        skill.onUtterance("三二一", ctx)
        assertEquals(RpsSkill.State.JUDGING, skill.state)
        assertFalse(skill.onUtterance("等下再来", ctx)) // 让位：裁判回合将被 supersede
        assertEquals(RpsSkill.State.ARMED, skill.state)
    }

    @Test
    fun `interrupt during INVITED or JUDGING returns to ARMED`() {
        val (skill, host, ctx) = newSkill()
        // INVITED（激活回合未完成时被打断）
        assertFalse(skill.onUtterance("玩猜拳", ctx))
        assertEquals(RpsSkill.State.INVITED, skill.state)
        skill.onInterrupted(ctx)
        assertEquals(RpsSkill.State.ARMED, skill.state)
        // JUDGING
        skill.onVadUtterance(1_000L, ctx)
        skill.onUtterance("三二一", ctx)
        skill.onInterrupted(ctx)
        assertEquals(RpsSkill.State.ARMED, skill.state)
    }

    // ── 调试命令 ─────────────────────────────────────────────────────────

    @Test
    fun `debug throw forces a hand in ARMED`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        val result = skill.debugCommand("rock", ctx)
        assertTrue(result.contains("石头"))
        assertEquals(RpsSkill.Hand.ROCK, skill.lastChoice)
        assertEquals(RpsSkill.State.THROWN, skill.state)
        assertTrue(skill.debugCommand("status", ctx).contains("state=THROWN"))
    }

    @Test
    fun `debug throw outside ARMED is rejected`() {
        val (skill, host, ctx) = newSkill()
        assertTrue(skill.debugCommand("rock", ctx).contains("cannot throw"))
    }
}
