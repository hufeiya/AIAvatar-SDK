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

    // ── P2 相机手势（本地权威判定，docs/rps-skill-feasibility.md §5）──────

    @Test
    fun `gesture codes map to hands`() {
        assertEquals(RpsSkill.Hand.ROCK, RpsSkill.gestureCodeToHand(1))
        assertEquals(RpsSkill.Hand.SCISSORS, RpsSkill.gestureCodeToHand(2))
        assertEquals(RpsSkill.Hand.PAPER, RpsSkill.gestureCodeToHand(3))
        assertNull(RpsSkill.gestureCodeToHand(0))
        assertNull(RpsSkill.gestureCodeToHand(7))
    }

    @Test
    fun `camera gesture in ARMED throws locally and speaks the verdict`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        host.nextRandom = 1 // 我出剪刀
        skill.onUserGesture(1, ctx) // 用户石头 → 石头砸剪刀,用户赢
        assertEquals(RpsSkill.State.THROWN, skill.state)
        assertEquals(1, skill.round)
        assertEquals(RpsSkill.Hand.ROCK, skill.lastUserGesture)
        assertEquals(RpsSkill.Hand.SCISSORS, skill.lastChoice)
        assertEquals(RpsSkill.Verdict.USER_WIN, skill.lastVerdict)
        // 出的是我的手,存的是用户的手与帧
        assertEquals(listOf(assets[RpsSkill.Hand.SCISSORS]), host.played)
        assertEquals(RpsSkill.Hand.SCISSORS, skill.pendingThrow?.choice)
        assertEquals(RpsSkill.Hand.ROCK, skill.pendingThrow?.userChoice)
        assertEquals("data:image/jpeg;base64,FAKE", skill.pendingThrow?.frame)
        // 即时宣判(直通 TTS,不经 LLM);LLM 回合还没发起
        assertEquals(1, host.spoken.size)
        assertTrue(host.spoken.single().contains("你出石头"))
        assertTrue(host.spoken.single().contains("我出剪刀"))
        assertTrue(host.spoken.single().contains("你赢"))
        assertTrue(host.sent.isEmpty())
    }

    @Test
    fun `local verdict matrix covers all nine combinations`() {
        for ((seed, mine) in RpsSkill.Hand.entries.withIndex()) {
            for (user in RpsSkill.Hand.entries) {
                val (skill, host, ctx) = newSkill()
                activateToArmed(skill, host, ctx)
                host.nextRandom = seed
                skill.onUserGesture(user.ordinal + 1, ctx)
                val expected = when {
                    user == mine -> RpsSkill.Verdict.DRAW
                    user.beats(mine) -> RpsSkill.Verdict.USER_WIN
                    else -> RpsSkill.Verdict.AVATAR_WIN
                }
                assertEquals("user=$user mine=$mine", expected, skill.lastVerdict)
                val keyword = when (expected) {
                    RpsSkill.Verdict.USER_WIN -> "你赢"
                    RpsSkill.Verdict.AVATAR_WIN -> "我赢"
                    RpsSkill.Verdict.DRAW -> "平局"
                }
                assertTrue(
                    "user=$user mine=$mine spoken=${host.spoken}",
                    host.spoken.single().contains(keyword),
                )
            }
        }
    }

    @Test
    fun `camera gesture in THROWN starts the next round`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        host.nextRandom = 0
        skill.onUserGesture(1, ctx) // 第1局
        assertEquals(1, skill.round)
        host.nextRandom = 2
        skill.onUserGesture(2, ctx) // 第2局:用户剪刀,我出布 → 剪刀剪布,用户赢
        assertEquals(RpsSkill.State.THROWN, skill.state)
        assertEquals(2, skill.round)
        assertEquals(RpsSkill.Hand.SCISSORS, skill.lastUserGesture)
        assertEquals(RpsSkill.Hand.PAPER, skill.lastChoice)
        assertEquals(RpsSkill.Verdict.USER_WIN, skill.lastVerdict)
        assertEquals(2, host.spoken.size)
    }

    @Test
    fun `camera gesture outside ARMED-or-THROWN is ignored`() {
        val (skill, host, ctx) = newSkill()
        skill.onUserGesture(1, ctx) // IDLE
        assertEquals(RpsSkill.State.IDLE, skill.state)
        assertTrue(host.spoken.isEmpty())
        skill.onUtterance("玩猜拳", ctx) // INVITED:激活回合还没完
        skill.onUserGesture(1, ctx)
        assertEquals(RpsSkill.State.INVITED, skill.state)
        assertTrue(host.played.isEmpty())
        skill.onTurnCompleted("好呀", ctx)
        host.nextRandom = 0
        skill.onUserGesture(1, ctx)
        assertTrue(skill.onUtterance("三二一", ctx)) // JUDGING
        host.spoken.clear()
        skill.onUserGesture(3, ctx) // JUDGING 中忽略(多半是说话的比划)
        assertEquals(RpsSkill.State.JUDGING, skill.state)
        assertTrue(host.spoken.isEmpty())
    }

    @Test
    fun `utterance after camera throw is the atmosphere turn with local result`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        host.nextRandom = 0 // 我出石头
        skill.onUserGesture(2, ctx) // 用户剪刀 → 石头砸剪刀,我赢
        assertTrue(skill.onUtterance("哈哈!", ctx))
        assertEquals(RpsSkill.State.JUDGING, skill.state)
        assertEquals(1, host.sent.size)
        val (text, images) = host.sent.single()
        assertEquals("哈哈!", text)
        assertEquals(listOf("data:image/jpeg;base64,FAKE"), images)
        val directive = skill.turnDirective(ctx)
        assertTrue(directive!!.contains("本地摄像头识别"))
        assertTrue(directive.contains("用户出的是「剪刀」"))
        assertTrue(directive.contains("出的是「石头」"))
        assertTrue(directive.contains("不要重新判定"))
        skill.onTurnCompleted("哈哈你输了!", ctx)
        assertEquals(RpsSkill.State.ARMED, skill.state)
    }

    @Test
    fun `vad in a locally judged THROWN does not double throw`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        skill.onUserGesture(1, ctx)
        val spokenBefore = host.spoken.size
        skill.onVadUtterance(1_000L, ctx) // 三二一喊晚了,拳已经出了
        assertEquals(RpsSkill.State.THROWN, skill.state)
        assertEquals(1, skill.round)
        assertEquals(spokenBefore, host.spoken.size)
        assertEquals(1, host.played.size)
    }

    @Test
    fun `late camera gesture upgrades a vad throw to local judging`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        host.nextRandom = 0 // 我出石头
        skill.onVadUtterance(1_000L, ctx) // P0 语音先出拳(用户的手未知)
        assertNull(skill.pendingThrow?.userChoice)
        skill.onUserGesture(2, ctx) // 用户剪刀补观测到 → 石头砸剪刀,我赢
        assertEquals(RpsSkill.Hand.SCISSORS, skill.pendingThrow?.userChoice)
        assertEquals(RpsSkill.Verdict.AVATAR_WIN, skill.lastVerdict)
        assertEquals(1, host.spoken.size) // 补即时宣判
        assertEquals(1, host.played.size) // 不重出拳、不重抓帧
        // ASR 到达后走气氛组指令,不再让 VLM 看图判定
        assertTrue(skill.onUtterance("三二一!", ctx))
        val directive = skill.turnDirective(ctx)
        assertTrue(directive!!.contains("本地摄像头识别"))
        assertFalse(directive.contains("看随本轮附上的抓拍画面"))
    }

    @Test
    fun `camera throw without a frame still judges and speaks locally`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        host.frame = null
        skill.onUserGesture(3, ctx)
        assertEquals(1, host.spoken.size) // 本地判定不看图
        assertTrue(skill.onUtterance("三二一", ctx))
        assertTrue(host.sent.single().second.isEmpty())
    }

    @Test
    fun `debug gesture command runs the same local path`() {
        val (skill, host, ctx) = newSkill()
        activateToArmed(skill, host, ctx)
        host.nextRandom = 0 // 我出石头 vs 用户布 → 布包石头,用户赢
        val result = skill.debugCommand("gesture_paper", ctx)
        assertTrue(result.contains("verdict="))
        assertEquals(RpsSkill.Hand.PAPER, skill.lastUserGesture)
        assertEquals(RpsSkill.Verdict.USER_WIN, skill.lastVerdict)
        assertEquals(1, host.spoken.size)
        assertTrue(skill.debugCommand("status", ctx).contains("user=布"))
        assertTrue(skill.debugCommand("status", ctx).contains("verdict=用户赢"))
    }

    @Test
    fun `debug gesture outside a round is rejected`() {
        val (skill, host, ctx) = newSkill()
        assertTrue(skill.debugCommand("gesture_rock", ctx).contains("ignored"))
    }

    // ── 指令内容（用户反馈：AI 有时不知道规则，指令里写明胜负规则）────────

    @Test
    fun `directives teach the rps rules`() {
        val (skill, host, ctx) = newSkill()
        // INVITED：邀请回合的指令带全规则，LLM 照着讲不会讲错
        skill.onUtterance("玩猜拳", ctx)
        val invited = skill.turnDirective(ctx)!!
        assertTrue(invited.contains("剪刀赢布"))
        assertTrue(invited.contains("布赢石头"))
        assertTrue(invited.contains("石头赢剪刀"))
        assertTrue(invited.contains("平局"))
        // ARMED：出拳间隙的对话指令也带规则
        skill.onTurnCompleted("好呀", ctx)
        assertTrue(skill.turnDirective(ctx)!!.contains("剪刀赢布"))
        // P0 看图裁判指令带规则（VLM 据此宣判）
        host.nextRandom = 0
        skill.onVadUtterance(1_000L, ctx)
        assertTrue(skill.onUtterance("三二一", ctx))
        assertTrue(skill.turnDirective(ctx)!!.contains("剪刀赢布"))
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
