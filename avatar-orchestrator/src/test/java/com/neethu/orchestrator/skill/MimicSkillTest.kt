package com.neethu.orchestrator.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模仿我」技能纯逻辑单测：状态机全迁移表 + 可见性降级阶梯（催促一次/episode、
 * 8s/3 次退场）+ 插话放行 + 退场战报构造 + 调试命令。逐帧姿态数据不经技能层
 * （app 车道直驱渲染引擎），本技能只测生命周期。
 */
class MimicSkillTest {

    private class FakeHost : SkillHost {
        val spoken = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, List<String>>>()
        var latestFrame: String? = "data:image/jpeg;base64,LATEST"
        var now = 0L
        override val defaultVadHangoverMs: Long = 800L
        override fun playGestureFile(path: String, loop: Boolean): Boolean = true
        override fun speak(text: String) {
            spoken += text
        }
        override fun sendTurn(text: String, images: List<String>) {
            sent += text to images
        }
        override fun snapshotImage(): String? = null
        override fun snapshotLatest(): String? = latestFrame
        override fun setVadHangover(ms: Long) = Unit
        override fun nowMs(): Long = now
        override fun randomInt(bound: Int): Int = 0
    }

    private fun newSkill(): Triple<MimicSkill, FakeHost, SkillContext> {
        val host = FakeHost()
        val skill = MimicSkill()
        return Triple(skill, host, SkillContext(host) { })
    }

    private fun activateToIntro(skill: MimicSkill, ctx: SkillContext) {
        assertFalse(skill.onUtterance("来模仿我吧", ctx))
        assertEquals(MimicSkill.State.INTRO, skill.state)
        assertTrue(skill.isActive)
    }

    private fun activateToActive(skill: MimicSkill, host: FakeHost, ctx: SkillContext) {
        activateToIntro(skill, ctx)
        skill.onTurnCompleted("好呀,学你!", ctx)
        assertEquals(MimicSkill.State.ACTIVE, skill.state)
        assertTrue(host.spoken.isEmpty()) // 开场不直通 speak,走正常回合
    }

    // ── 激活 / 指令 ──────────────────────────────────────────────────────

    @Test
    fun `activation keyword enters INTRO without consuming and injects the intro directive`() {
        val (skill, _, ctx) = newSkill()
        activateToIntro(skill, ctx)
        assertTrue(skill.turnDirective(ctx)!!.contains("模仿"))
        assertTrue(skill.turnDirective(ctx)!!.contains("镜像"))
    }

    @Test
    fun `english activation keyword also works`() {
        val (skill, _, ctx) = newSkill()
        assertFalse(skill.onUtterance("copy me please", ctx))
        assertEquals(MimicSkill.State.INTRO, skill.state)
        assertFalse(skill.onUtterance("MIMIC ME", ctx).let { skill.state == MimicSkill.State.IDLE })
    }

    @Test
    fun `unrelated utterance in IDLE does nothing`() {
        val (skill, _, ctx) = newSkill()
        assertFalse(skill.onUtterance("今天天气不错", ctx))
        assertEquals(MimicSkill.State.IDLE, skill.state)
        assertNull(skill.turnDirective(ctx))
    }

    @Test
    fun `intro completed starts ACTIVE with a mid-mimic directive`() {
        val (skill, _, ctx) = newSkill()
        activateToActive(skill, FakeHost(), ctx)
        val directive = skill.turnDirective(ctx)!!
        assertTrue(directive.contains("模仿"))
        assertTrue(directive.contains("短") || directive.contains("节奏"))
    }

    @Test
    fun `intro interrupted or failed still starts mimicry`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, ctx)
        skill.onInterrupted(ctx)
        assertEquals(MimicSkill.State.ACTIVE, skill.state)

        val (s2, _, c2) = newSkill()
        activateToIntro(s2, c2)
        s2.onTurnFailed(c2)
        assertEquals(MimicSkill.State.ACTIVE, s2.state)
        assertTrue(host.spoken.isEmpty())
    }

    // ── 插话放行（与看这边"一律消费"刻意相反）────────────────────────────

    @Test
    fun `mid-mimic chat passes through instead of being consumed`() {
        val (skill, _, ctx) = newSkill()
        activateToActive(skill, FakeHost(), ctx)
        assertFalse(skill.onUtterance("你觉得今天热不热", ctx))
        assertEquals(MimicSkill.State.ACTIVE, skill.state)
    }

    // ── 退出战报 ─────────────────────────────────────────────────────────

    @Test
    fun `exit word consumes the utterance and sends a banter turn with the frame`() {
        val (skill, host, ctx) = newSkill()
        activateToActive(skill, host, ctx)
        host.now = 10_000L
        skill.onBodyTracking(true, ctx) // 见过人 → hadSignal
        assertTrue(skill.onUtterance("不模仿了", ctx))
        assertEquals(MimicSkill.State.BANTER, skill.state)
        assertEquals(1, host.sent.size)
        assertTrue(host.sent.single().first.contains("模仿"))
        assertTrue(host.sent.single().first.contains("最后的姿势"))
        assertEquals(listOf("data:image/jpeg;base64,LATEST"), host.sent.single().second)
        // 战报回合完成 → IDLE
        skill.onTurnCompleted("哈哈学得不错", ctx)
        assertEquals(MimicSkill.State.IDLE, skill.state)
    }

    @Test
    fun `exit before ever seeing a body gives a camera hint instead of a banter`() {
        val (skill, host, ctx) = newSkill()
        activateToActive(skill, host, ctx)
        assertTrue(skill.onUtterance("停止模仿", ctx))
        assertEquals(MimicSkill.State.IDLE, skill.state)
        assertTrue(host.sent.isEmpty())
        assertEquals(1, host.spoken.size)
        assertTrue(host.spoken.single().contains("找不到") || host.spoken.single().contains("镜头"))
    }

    @Test
    fun `banter interrupted or failed deactivates`() {
        val (skill, host, ctx) = newSkill()
        activateToActive(skill, host, ctx)
        skill.onBodyTracking(true, ctx)
        skill.onUtterance("别学了", ctx)
        assertEquals(MimicSkill.State.BANTER, skill.state)
        skill.onTurnFailed(ctx)
        assertEquals(MimicSkill.State.IDLE, skill.state)
    }

    // ── 可见性降级阶梯 ───────────────────────────────────────────────────

    @Test
    fun `body lost for 2s prompts once per episode`() {
        val (skill, host, ctx) = newSkill()
        activateToActive(skill, host, ctx)
        host.now = 0L
        skill.onBodyTracking(false, ctx)
        host.now = 2_100L
        skill.onBodyTracking(false, ctx) // 注意:app 侧只在状态变化广播,false 期间只来一次
        assertEquals(1, host.spoken.size)
        assertTrue(host.spoken.single().contains("退后") || host.spoken.single().contains("上半身"))
        // 不满 8s 不退场
        assertEquals(MimicSkill.State.ACTIVE, skill.state)
    }

    @Test
    fun `body reappearing resets the episode so a later loss prompts again`() {
        val (skill, host, ctx) = newSkill()
        activateToActive(skill, host, ctx)
        host.now = 0L
        skill.onBodyTracking(false, ctx)
        host.now = 2_100L
        skill.onBodyTracking(false, ctx)
        assertEquals(1, host.spoken.size)
        // 回来了
        skill.onBodyTracking(true, ctx)
        host.now = 20_000L
        skill.onBodyTracking(false, ctx) // 新 episode 起点
        host.now = 22_100L
        skill.onBodyTracking(false, ctx)
        assertEquals(2, host.spoken.size)
        assertEquals(MimicSkill.State.ACTIVE, skill.state)
    }

    @Test
    fun `body lost for 8s exits with a camera hint`() {
        val (skill, host, ctx) = newSkill()
        activateToActive(skill, host, ctx)
        host.now = 0L
        skill.onBodyTracking(false, ctx)
        host.now = 8_100L
        skill.onBodyTracking(false, ctx)
        assertEquals(MimicSkill.State.IDLE, skill.state)
        assertTrue(host.spoken.single().contains("找不到") || host.spoken.single().contains("镜头"))
        assertTrue(host.sent.isEmpty()) // 没人在场无战报
    }

    @Test
    fun `three prompted episodes force the exit`() {
        val (skill, host, ctx) = newSkill()
        activateToActive(skill, host, ctx)
        // 三个 episode:各催促一次后短暂回来
        for (episode in 0 until 3) {
            val base = episode * 30_000L
            host.now = base
            skill.onBodyTracking(false, ctx)
            host.now = base + 2_100L
            skill.onBodyTracking(false, ctx)
            skill.onBodyTracking(true, ctx)
        }
        assertEquals(MimicSkill.State.ACTIVE, skill.state)
        // 第四次消失不用等 8s:催促数已满,直接退场
        host.now = 90_000L
        skill.onBodyTracking(false, ctx)
        host.now = 90_100L
        skill.onBodyTracking(false, ctx)
        assertEquals(MimicSkill.State.IDLE, skill.state)
    }

    // ── 强制退场 / 调试 ──────────────────────────────────────────────────

    @Test
    fun `forced exit is silent and returns to IDLE`() {
        val (skill, host, ctx) = newSkill()
        activateToActive(skill, host, ctx)
        skill.onBodyTracking(true, ctx)
        skill.onExit(ctx)
        assertEquals(MimicSkill.State.IDLE, skill.state)
        assertTrue(host.spoken.isEmpty())
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun `debug status and exit`() {
        val (skill, host, ctx) = newSkill()
        assertTrue(skill.debugCommand("status", ctx).contains("state=IDLE"))
        activateToActive(skill, host, ctx)
        assertTrue(skill.debugCommand("status", ctx).contains("state=ACTIVE"))
        assertTrue(skill.debugCommand("exit", ctx).isNotEmpty())
        assertEquals(MimicSkill.State.IDLE, skill.state)
        assertTrue(skill.debugCommand("bogus", ctx).contains("expects"))
    }

    @Test
    fun `exit word also works during INTRO`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, ctx)
        assertTrue(skill.onUtterance("不学了", ctx))
        // 从没见过人 → 提示路径直接回 IDLE
        assertEquals(MimicSkill.State.IDLE, skill.state)
        assertTrue(host.sent.isEmpty())
    }
}
