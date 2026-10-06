package com.neethu.orchestrator.skill

import com.neethu.orchestrator.skill.LookHereJudge.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「看这边」判定窗纯逻辑单测：基线中位数、连续帧确认早退、窗截止峰值终判、
 * 呆住判负、符号布尔映射（真机标定只动 LookHereTuning 的两个布尔，这里锁死
 * 默认约定：yaw 正=屏幕左、pitch 正=屏幕上）。
 *
 * 节奏注记：基线形成的那一帧不参与投票，之后连续 [confirmFrames]=2 帧同向
 * 越线才确认——静止基线 + 320ms 成基线 + 400/480 两帧 20° = 480ms 出结论。
 */
class LookHereJudgeTest {

    private fun judge(tuning: LookHereTuning = LookHereTuning()) =
        LookHereJudge(tuning, startMs = 0L)

    /** 基线窗内的静止样本（80ms 分析节奏；用户还没反应）。 */
    private fun settleBaseline(j: LookHereJudge) {
        for (ts in longArrayOf(0L, 80L, 160L, 240L)) assertNull(j.onSample(ts, 0f, 0f))
    }

    @Test
    fun `quiet baseline window never produces a verdict`() {
        val j = judge()
        // 大幅度也只进基线（250ms 内人类来不及反应）
        for (ts in longArrayOf(0L, 80L, 160L, 240L)) assertNull(j.onSample(ts, 40f, -30f))
    }

    @Test
    fun `sustained yaw turn confirms early as screen-left`() {
        val j = judge()
        settleBaseline(j)
        assertNull(j.onSample(320L, 18f, 0f)) // 此帧形成基线（中位数≈0），不投票
        assertNull(j.onSample(400L, 20f, 0f)) // 越线投票 1
        assertEquals(Outcome.Turned(LookDir.LEFT), j.onSample(480L, 22f, 0f)) // 投票 2 → 确认
    }

    @Test
    fun `judge is final - samples after a verdict return null`() {
        val j = judge()
        settleBaseline(j)
        assertNull(j.onSample(320L, 18f, 0f))
        assertNull(j.onSample(400L, 20f, 0f))
        assertEquals(Outcome.Turned(LookDir.LEFT), j.onSample(480L, 22f, 0f))
        assertNull(j.onSample(560L, 25f, 0f))
        assertNull(j.onSample(1_300L, 28f, 0f))
    }

    @Test
    fun `single frame spike does not confirm early`() {
        val j = judge()
        settleBaseline(j)
        assertNull(j.onSample(320L, 25f, 0f)) // 基线形成帧
        assertNull(j.onSample(400L, 25f, 0f)) // 单帧尖峰：streak=1 不足以确认
        assertNull(j.onSample(480L, 1f, 0f)) // 回落打断 streak
    }

    @Test
    fun `window end rules by peak direction`() {
        // 窗长显式 1200ms（生产默认 2200 是为覆盖 TTS 延迟+反应时，见 tuning doc）
        val j = judge(LookHereTuning(windowMs = 1_200L))
        settleBaseline(j)
        assertNull(j.onSample(320L, 25f, 0f))
        assertNull(j.onSample(400L, 25f, 0f)) // 尖峰只一帧,早退不触发
        assertNull(j.onSample(480L, 1f, 0f)) // 回落,峰值仍记 25°
        // 过窗后的第一个样本触发终判：峰值 25° ≥ 确认线 15° → 按峰值判
        assertEquals(Outcome.Turned(LookDir.LEFT), j.onSample(1_280L, 2f, 0f))
    }

    @Test
    fun `staying still ends frozen (freeze loses by design)`() {
        val j = judge(LookHereTuning(windowMs = 1_200L))
        settleBaseline(j)
        assertNull(j.onSample(320L, 3f, -2f)) // 微动不越确认线
        assertEquals(Outcome.Frozen, j.onSample(1_280L, 2f, 1f))
    }

    @Test
    fun `pitch axis wins when dominant with its lower threshold`() {
        val j = judge()
        settleBaseline(j)
        assertNull(j.onSample(320L, 5f, 13f)) // 基线帧
        assertNull(j.onSample(400L, 4f, 14f)) // yaw 4 < pitch 14 → 主轴 pitch,|14|≥12
        assertEquals(Outcome.Turned(LookDir.DOWN), j.onSample(480L, 6f, 15f))
    }

    @Test
    fun `yaw sign boolean flips the screen mapping`() {
        val j = judge(LookHereTuning(yawPositiveIsScreenLeft = false))
        settleBaseline(j)
        assertNull(j.onSample(320L, 18f, 0f))
        assertNull(j.onSample(400L, 20f, 0f))
        assertEquals(Outcome.Turned(LookDir.RIGHT), j.onSample(480L, 20f, 0f))
    }

    @Test
    fun `pitch sign boolean flips the screen mapping`() {
        // 默认（真机标定）pitch 正=屏幕下；=true 翻回"正=上"
        val j = judge(LookHereTuning(pitchPositiveIsScreenUp = true))
        settleBaseline(j)
        assertNull(j.onSample(320L, 0f, 14f))
        assertNull(j.onSample(400L, 1f, 16f))
        assertEquals(Outcome.Turned(LookDir.UP), j.onSample(480L, 0f, 16f))
    }

    @Test
    fun `late face still gets a verdict at window end`() {
        val j = judge(LookHereTuning(windowMs = 1_200L))
        // 脸 1.3s 才出现（基线窗与判定窗都已过）：首个样本形成中性位后
        // 立即按窗口截止终判（新语义下无需再等第二帧）
        assertEquals(Outcome.Frozen, j.onSample(1_300L, 5f, 5f))
    }

    @Test
    fun `production default window covers tts latency plus reaction`() {
        // 真机归因（第 1 局 Frozen 误判）：go 信号音频 ~1.9s 才播完，反应+转头
        // 再要 0.3~0.6s——默认窗必须装得下"听到再动"
        assertEquals(2_200L, LookHereTuning().windowMs)
        val j = judge()
        settleBaseline(j)
        assertNull(j.onSample(320L, 18f, 0f))
        assertNull(j.onSample(1_900L, 20f, 0f)) // 1.9s 才转头(听到语音才动)——旧 1200ms 窗已判 Frozen
        assertEquals(Outcome.Turned(LookDir.LEFT), j.onSample(1_980L, 22f, 0f))
    }

    @Test
    fun `median baseline absorbs pre-reaction jitter`() {
        val j = judge()
        // 基线窗内用户晃了一下 +30°（4 帧里 1 帧）——中位数吸收，基线仍≈0
        assertNull(j.onSample(0L, 0f, 0f))
        assertNull(j.onSample(80L, 30f, 0f))
        assertNull(j.onSample(160L, 0f, 0f))
        assertNull(j.onSample(240L, 0f, 0f))
        assertNull(j.onSample(320L, 18f, 0f)) // 成基线（含本帧共 5 样本,中位数 0）
        assertNull(j.onSample(400L, 19f, 0f))
        assertEquals(Outcome.Turned(LookDir.LEFT), j.onSample(480L, 20f, 0f))
    }

    @Test
    fun `opposite enum mapping is total and involutive`() {
        for (d in LookDir.entries) assertEquals(d, d.opposite().opposite())
        assertEquals(LookDir.UP, LookDir.DOWN.opposite())
        assertEquals(LookDir.LEFT, LookDir.RIGHT.opposite())
    }

    @Test
    fun `session neutral enables absolute reading of a held direction`() {
        // 真机归因（2026-10-06"我一直往左看却连判发呆"）：判定=相对会话中性位的
        // 绝对方向——用户不回正也必须被持续读出方向，开局即时可判（不等 250ms 基线）
        val j = LookHereJudge(LookHereTuning(), startMs = 0L, initialNeutral = 0f to 0f)
        assertNull(j.onSample(0L, 34f, 0f))
        assertEquals(Outcome.Turned(LookDir.LEFT), j.onSample(80L, 35f, 0f))
        // 候选明显偏头 → 中性位不被带跑
        assertEquals(0f to 0f, j.currentNeutral)
    }

    @Test
    fun `neutral tracks drift only when the user is near centered`() {
        val j = LookHereJudge(LookHereTuning(windowMs = 1_200L), startMs = 0L, initialNeutral = 0f to 0f)
        for (t in longArrayOf(0L, 80L, 160L, 240L, 320L)) assertNull(j.onSample(t, 4f, 1f))
        assertNull(j.onSample(400L, 4f, 1f)) // 过 ~330ms → 候选中位数 4°<15° → 中性位吸收漂移
        assertEquals(4f to 1f, j.currentNeutral)
        assertEquals(Outcome.Frozen, j.onSample(1_280L, 4f, 1f))
    }

    @Test
    fun `tuning probe maps raw signs to screen dirs (face_pose calibration aid)`() {
        val t = LookHereTuning()
        assertEquals(LookDir.LEFT, t.yawToScreen(10f))
        assertEquals(LookDir.RIGHT, t.yawToScreen(-10f))
        // 真机标定（2026-10-06）：pitch 正号 = 屏幕下
        assertEquals(LookDir.DOWN, t.pitchToScreen(10f))
        assertEquals(LookDir.UP, t.pitchToScreen(-10f))
        assertNull(t.yawToScreen(0f))
        // 主轴投票：pitch 占优取 pitch 方向
        assertEquals(LookDir.UP, t.dominantDirection(3f, -14f))
        assertEquals(LookDir.RIGHT, t.dominantDirection(-20f, 5f))
    }
}
