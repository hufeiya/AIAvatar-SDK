package com.neethu.orchestrator.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「看这边」技能纯逻辑单测：状态机全迁移表 + 判定→宣判→连局的链式节拍 +
 * 无脸阶梯 + 退场战报 + 调试命令。真机验收（符号标定/节奏/宣判观感）见
 * docs/lookhere-skill-feasibility.md §9。
 */
class LookHereSkillTest {

    private class FakeHost : SkillHost {
        val played = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, List<String>>>()
        var latestFrame: String? = "data:image/jpeg;base64,LATEST"
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
        override fun sendTurn(text: String, images: List<String>) {
            sent += text to images
        }
        override fun snapshotImage(): String? = null
        override fun snapshotLatest(): String? = latestFrame
        override fun setVadHangover(ms: Long) = Unit
        override fun nowMs(): Long = now
        override fun randomInt(bound: Int): Int = if (bound <= 0) 0 else nextRandom % bound
    }

    private val assets = LookDir.entries.associateWith { "animations/gesture_${it.en}.vrma" }

    private fun newSkill(): Triple<LookHereSkill, FakeHost, SkillContext> {
        val host = FakeHost()
        val skill = LookHereSkill(assets) { bound -> host.nextRandom % bound.coerceAtLeast(1) }
        // 测试用短窗（生产默认 2200ms 是为覆盖 TTS 延迟+反应时）；窗长语义
        // 本身的用例在 LookHereJudgeTest
        skill.tuning = LookHereTuning(windowMs = 1_200L)
        return Triple(skill, host, SkillContext(host) { })
    }

    /** nextRandom=0 → UP：虚拟人指上。 */
    private fun activateToIntro(skill: LookHereSkill, host: FakeHost, ctx: SkillContext) {
        assertFalse(skill.onUtterance("来玩看这边吧", ctx))
        assertEquals(LookHereSkill.State.INTRO, skill.state)
        assertTrue(skill.isActive)
    }

    /** INTRO 回合完成 → 第一局开局：指 UP + 喊 go 信号。 */
    private fun beginRound(skill: LookHereSkill, host: FakeHost, ctx: SkillContext) {
        skill.onTurnCompleted("好呀!", ctx)
        assertEquals(LookHereSkill.State.POINTING, skill.state)
        assertEquals(listOf("animations/gesture_up.vrma"), host.played)
        assertTrue(host.spoken.single().contains("看这边"))
    }

    /** 喂一帧头部姿态。 */
    private fun pose(skill: LookHereSkill, host: FakeHost, ctx: SkillContext, tMs: Long, pitch: Float = 0f, yaw: Float = 0f) {
        host.now = tMs
        skill.onHeadPose(yaw, pitch, ctx)
    }

    // ── 激活 / 指令 ──────────────────────────────────────────────────────

    @Test
    fun `activation keyword enters INTRO without consuming the utterance`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        assertTrue(skill.turnDirective(ctx)!!.contains("看这边"))
        assertTrue(skill.turnDirective(ctx)!!.contains("相反"))
    }

    @Test
    fun `english activation keyword also works`() {
        val (skill, _, ctx) = newSkill()
        assertFalse(skill.onUtterance("let's play look over there!", ctx))
        assertEquals(LookHereSkill.State.INTRO, skill.state)
    }

    @Test
    fun `non keyword in IDLE is a no-op`() {
        val (skill, _, ctx) = newSkill()
        assertFalse(skill.onUtterance("今天天气不错", ctx))
        assertEquals(LookHereSkill.State.IDLE, skill.state)
        assertNull(skill.turnDirective(ctx))
    }

    @Test
    fun `directive only in INTRO`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        skill.onTurnCompleted("好呀!", ctx)
        assertNull(skill.turnDirective(ctx))
    }

    // ── 一局完整链路：判定 → 宣判 → 连局 ─────────────────────────────────

    @Test
    fun `user dodging opposite wins without frame capture`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        beginRound(skill, host, ctx) // 指上
        // 用户低头躲开（标定后 pitch 正=屏幕下：低头=正 pitch）
        for (t in longArrayOf(0L, 80L, 160L, 240L)) pose(skill, host, ctx, t)
        pose(skill, host, ctx, 320L, pitch = 14f) // 成基线帧
        pose(skill, host, ctx, 400L, pitch = 16f)
        pose(skill, host, ctx, 480L, pitch = 16f) // 确认 DOWN
        assertEquals(LookHereSkill.State.POINTING, skill.state) // 宣判等 go 信号播完
        assertEquals(1, skill.round)
        assertEquals(1, skill.scoreYou)
        assertEquals(0, skill.scoreMe)
        assertNull(skill.caughtFrame) // 赢局不抓帧
        assertEquals(LookDir.DOWN, skill.lastResult?.userDir)
        // go 信号播完 → 宣判
        host.now = 1_900L
        skill.onSpeakCompleted("看这边!", ctx)
        assertEquals(LookHereSkill.State.ANNOUNCING, skill.state)
        assertTrue(host.spoken.last().contains("躲开"))
        // 宣判播完 → 自动连局（新指向 + 新 go 信号）
        host.now = 3_300L
        skill.onSpeakCompleted("——哦?!竟让你躲开了!", ctx)
        assertEquals(LookHereSkill.State.POINTING, skill.state)
        assertEquals(2, host.played.size)
        assertEquals(3, host.spoken.size) // cry + 宣判 + 新 cry
    }

    @Test
    fun `user looking the same way loses and the frame is captured`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        beginRound(skill, host, ctx) // 指上
        for (t in longArrayOf(0L, 80L, 160L, 240L)) pose(skill, host, ctx, t)
        pose(skill, host, ctx, 320L, pitch = -14f)
        pose(skill, host, ctx, 400L, pitch = -16f)
        pose(skill, host, ctx, 480L, pitch = -16f) // 确认 UP = 同向 → 输
        assertEquals(1, skill.scoreMe)
        assertEquals("data:image/jpeg;base64,LATEST", skill.caughtFrame) // 判负瞬间存证
        host.now = 1_900L
        skill.onSpeakCompleted("看这边!", ctx)
        assertTrue(host.spoken.last().contains("抓到你了"))
    }

    @Test
    fun `freezing still ends the round as a loss`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        beginRound(skill, host, ctx)
        for (t in longArrayOf(0L, 80L, 160L, 240L, 320L, 400L, 480L)) pose(skill, host, ctx, t, pitch = 2f)
        pose(skill, host, ctx, 1_280L, pitch = 1f) // 过窗触发终判 → Frozen
        assertTrue(skill.lastResult!!.frozen)
        assertFalse(skill.lastResult!!.userWon)
        assertEquals("data:image/jpeg;base64,LATEST", skill.caughtFrame)
        host.now = 1_900L
        skill.onSpeakCompleted("看这边!", ctx)
        assertTrue(host.spoken.last().contains("发什么呆"))
    }

    @Test
    fun `go signal finishing early still announces via the next sample`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        beginRound(skill, host, ctx)
        // go 信号 TTS 失败即时"完成"（无脸也没样本）：节拍事件先到
        host.now = 60L
        skill.onSpeakCompleted("看这边!", ctx)
        assertEquals(LookHereSkill.State.POINTING, skill.state) // 窗还没跑完,等样本
        for (t in longArrayOf(0L, 80L, 160L, 240L)) pose(skill, host, ctx, t)
        pose(skill, host, ctx, 320L, pitch = 14f)
        pose(skill, host, ctx, 400L, pitch = 16f) // 投票 1
        pose(skill, host, ctx, 480L, pitch = 16f) // 投票 2 → 确认 DOWN,赢
        // go 信号已结算,没有下一次 speak 事件可等 → 下一个样本直接自愈宣判
        pose(skill, host, ctx, 560L, pitch = 16f)
        assertEquals(LookHereSkill.State.ANNOUNCING, skill.state)
        assertTrue(host.spoken.last().contains("躲开"))
    }

    // ── 无脸阶梯 ─────────────────────────────────────────────────────────

    @Test
    fun `rounds without face samples are void then exit with a hint`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        beginRound(skill, host, ctx)
        // 第 1 局：go 信号播完仍无任何样本 → 作废重指（不计局）
        host.now = 1_900L
        skill.onSpeakCompleted("看这边!", ctx)
        assertEquals(LookHereSkill.State.POINTING, skill.state)
        assertEquals(0, skill.round)
        assertEquals(2, host.played.size)
        // 第 2 局同样
        host.now = 3_800L
        skill.onSpeakCompleted("看这边!", ctx)
        assertEquals(3, host.played.size)
        // 第 3 局：连续 3 局无脸 → 退场 + 提示查摄像头
        host.now = 5_700L
        skill.onSpeakCompleted("看这边!", ctx)
        assertEquals(LookHereSkill.State.IDLE, skill.state)
        assertTrue(host.spoken.last().contains("看不到你的脸"))
        assertEquals(3, host.played.size)
    }

    // ── 插话 / 打断 / 退出 ───────────────────────────────────────────────

    @Test
    fun `mid-game utterances are consumed and restart a stale round`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        beginRound(skill, host, ctx)
        host.now = 2_000L
        assertTrue(skill.onUtterance("哈哈你指的好快", ctx)) // 消费,不默认发送
        assertEquals(LookHereSkill.State.POINTING, skill.state)
        assertEquals(2, host.played.size) // 重指
        assertTrue(host.sent.isEmpty())
        // 保护窗内的连续插话只消费不重指（打断路径已自动重开局）
        host.now = 2_100L
        assertTrue(skill.onUtterance("再说点", ctx))
        assertEquals(2, host.played.size)
    }

    @Test
    fun `interrupt during a round restarts it`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        beginRound(skill, host, ctx)
        skill.onInterrupted(ctx) // barge-in 掐断 go 信号
        assertEquals(LookHereSkill.State.POINTING, skill.state)
        assertEquals(2, host.played.size)
    }

    @Test
    fun `intro interruption starts the game anyway`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        skill.onInterrupted(ctx)
        assertEquals(LookHereSkill.State.POINTING, skill.state)
        assertEquals(1, host.played.size)
    }

    @Test
    fun `intro turn failure deactivates silently`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        skill.onTurnFailed(ctx)
        assertEquals(LookHereSkill.State.IDLE, skill.state)
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun `exit keyword is consumed and sends the report turn with the frame`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        beginRound(skill, host, ctx)
        for (t in longArrayOf(0L, 80L, 160L, 240L)) pose(skill, host, ctx, t)
        pose(skill, host, ctx, 320L, pitch = -14f)
        pose(skill, host, ctx, 400L, pitch = -16f)
        pose(skill, host, ctx, 480L, pitch = -16f) // 确认 UP = 同向 → 输
        assertEquals(1, skill.round)
        assertTrue(skill.onUtterance("不玩了不玩了", ctx))
        assertEquals(LookHereSkill.State.IDLE, skill.state)
        val (text, images) = host.sent.single()
        assertTrue(text.contains("战报"))
        assertTrue(text.contains("1:0"))
        assertEquals(listOf("data:image/jpeg;base64,LATEST"), images)
    }

    @Test
    fun `exit without any scored round skips the report turn`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        assertTrue(skill.onUtterance("不玩了", ctx))
        assertEquals(LookHereSkill.State.IDLE, skill.state)
        assertTrue(host.sent.isEmpty())
    }

    // ── 调试命令 ─────────────────────────────────────────────────────────

    @Test
    fun `head held left is read absolutely across rounds without re-centering`() {
        val (skill, host, ctx) = newSkill()
        host.nextRandom = 2 // 虚拟人指左
        // INTRO 期采样：用户回正（低头看手机的姿态 pitch≈-30 被中性位吸收）
        assertFalse(skill.onUtterance("看这边", ctx))
        for (t in longArrayOf(0L, 80L, 160L, 240L, 320L, 400L, 480L, 560L, 640L, 720L)) {
            host.now = t
            skill.onHeadPose(2f, -30f, ctx)
        }
        skill.onTurnCompleted("好呀!", ctx) // → 第 1 局,中性位=(2,-30) 来自介绍期
        assertEquals(LookHereSkill.State.POINTING, skill.state)
        // 用户保持头左偏(yaw≈36)且不回正：绝对方向判定立即读出 LEFT(同向→输)
        host.now = 1_000L
        skill.onHeadPose(36f, -30f, ctx)
        host.now = 1_080L
        skill.onHeadPose(37f, -30f, ctx)
        assertEquals(1, skill.round)
        assertFalse(skill.lastResult!!.frozen)
        assertEquals(LookDir.LEFT, skill.lastResult!!.userDir)
        assertEquals(1, skill.scoreMe)
        // 第 2 局：中性位持久化,继续偏头继续被读出（不会被吸收成"发呆"）
        host.now = 3_000L
        skill.onSpeakCompleted("看这边!", ctx) // 宣判
        host.now = 4_500L
        skill.onSpeakCompleted("——抓到你了!", ctx) // → 下一局
        host.now = 4_600L
        skill.onHeadPose(36f, -30f, ctx)
        host.now = 4_680L
        skill.onHeadPose(37f, -30f, ctx)
        assertEquals(2, skill.round)
        assertFalse(skill.lastResult!!.frozen)
        assertEquals(2, skill.scoreMe)
    }

    @Test
    fun `debug throw forces a direction from IDLE for calibration`() {
        val (skill, host, ctx) = newSkill()
        val result = skill.debugCommand("throw_left", ctx)
        assertTrue(result.contains("左"))
        assertEquals(LookDir.LEFT, skill.lastPoint)
        assertEquals(LookHereSkill.State.POINTING, skill.state)
        // 真机标定（2026-10-06）：左臂动画文件语义与屏幕相反 → 屏幕左播 gesture_right
        assertEquals(listOf("animations/gesture_right.vrma"), host.played)
        assertTrue(skill.debugCommand("status", ctx).contains("point=左"))
    }

    @Test
    fun `debug status exposes score and outcome`() {
        val (skill, host, ctx) = newSkill()
        activateToIntro(skill, host, ctx)
        beginRound(skill, host, ctx)
        for (t in longArrayOf(0L, 80L, 160L, 240L)) pose(skill, host, ctx, t)
        pose(skill, host, ctx, 320L, pitch = -14f)
        pose(skill, host, ctx, 400L, pitch = -16f)
        pose(skill, host, ctx, 480L, pitch = -16f) // 同向输
        val status = skill.debugCommand("status", ctx)
        assertTrue(status.contains("score=1:0"))
        assertTrue(status.contains("caught"))
    }
}
