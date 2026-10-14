package com.neethu.corelib.accessory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccessoryAnimationBinder] 状态机边界单测（docs/accessory-feasibility.md
 * §5.4/§八 风险 7）：以「当前激活剪辑」为准 + 延迟隐藏 + crossfade 窗口撤回。
 * 调度器用手动泵（无真实时钟），show/hide 回调录制断言。
 *
 * 录制语义：binder 命中即回调 show（生产侧 [AccessoryManager.ensurePresent]
 * 对已挂载可见配饰幂等早退，无场景扰动），故 show 可重复、断言看 hide 时机。
 */
class AccessoryAnimationBinderTest {

    private class FakeScheduler : AccessoryAnimationBinder.Scheduler {
        class Task(val id: Int, val runAtMs: Long, val action: () -> Unit) :
            AccessoryAnimationBinder.Cancelable {
            var cancelled = false
            override fun cancel() {
                cancelled = true
            }
        }

        private var seq = 0
        val queue = ArrayList<Task>()
        var nowMs = 0L

        override fun post(delayMs: Long, action: () -> Unit): AccessoryAnimationBinder.Cancelable {
            val task = Task(seq++, nowMs + delayMs, action)
            queue.add(task)
            return task
        }

        /** 推进时钟并执行到期任务（取消的不跑）。 */
        fun advance(toMs: Long) {
            nowMs = toMs
            val due = queue.filter { !it.cancelled && it.runAtMs <= toMs }
            queue.removeAll(due)
            due.forEach { it.action() }
        }
    }

    private class Recorder {
        val shown = ArrayList<String>()
        val hidden = ArrayList<String>()
        fun show(id: String) = shown.add(id)
        fun hide(id: String) = hidden.add(id)
    }

    private fun binder(
        scheduler: FakeScheduler,
        rec: Recorder,
        hideDelayMs: Long = AccessoryAnimationBinder.DEFAULT_HIDE_DELAY_MS,
    ): AccessoryAnimationBinder =
        AccessoryAnimationBinder(hideDelayMs, scheduler, rec::show, rec::hide)

    @Test
    fun `绑定剪辑起播 出道具`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        assertTrue(b.bind("play.vrma", "phone"))

        b.onStarted("play.vrma")
        assertEquals(listOf("phone"), rec.shown)
        assertTrue(rec.hidden.isEmpty())
    }

    @Test
    fun `播完延迟隐藏 窗口内未隐藏`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        b.bind("play.vrma", "phone")
        b.onStarted("play.vrma")

        b.onFinished("play.vrma")
        s.advance(249)
        assertTrue(rec.hidden.isEmpty()) // crossfade 窗口内还在手上
        s.advance(250)
        assertEquals(listOf("phone"), rec.hidden)
    }

    @Test
    fun `连播两个同配饰动画 不闪(撤回待隐藏)`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        b.bind("texting.vrma", "phone")
        b.bind("talking.vrma", "phone")

        b.onStarted("texting.vrma")
        b.onFinished("texting.vrma") // 已排 250ms 待隐藏
        s.advance(100)
        b.onStarted("talking.vrma") // 窗口内接续：撤回待隐藏，只补一次幂等 show
        s.advance(500)
        assertTrue("待隐藏被撤回，全程不 hide", rec.hidden.isEmpty())
        assertEquals("show 只发生在两次起播", listOf("phone", "phone"), rec.shown)
    }

    @Test
    fun `打断(被未绑定剪辑顶替) 旧配饰延迟消失`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        b.bind("texting.vrma", "phone")
        b.onStarted("texting.vrma")

        // 打断：走动步态起播（无绑定）
        b.onStarted("walk.vrma")
        s.advance(250)
        assertEquals(listOf("phone"), rec.hidden)
    }

    @Test
    fun `loop 顶替不发 Finished 也收道具(风险7)`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        b.bind("texting.vrma", "phone")
        b.onStarted("texting.vrma") // loop 剪辑

        // 被另一 loop 剪辑顶替：引擎不发 Finished，onStarted 路径兜底
        b.onStarted("dance.vrma")
        s.advance(250)
        assertEquals(listOf("phone"), rec.hidden)
    }

    @Test
    fun `旧剪辑迟到的 Finished 被忽略(不藏新剪辑的道具)`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        b.bind("a.vrma", "phone")
        b.bind("b.vrma", "hat")
        b.onStarted("a.vrma")
        b.onStarted("b.vrma") // 顶替：a 的 phone 延迟隐藏 + hat 在场
        b.onFinished("a.vrma") // 迟到事件：activeClip 已是 b，必须忽略
        s.advance(500)
        assertEquals(listOf("phone"), rec.hidden) // 只有顶替路径的一次
        // b 正常收尾仍有效（250ms 排在新时钟上）
        b.onFinished("b.vrma")
        s.advance(751)
        assertEquals(listOf("phone", "hat"), rec.hidden)
    }

    @Test
    fun `未绑定剪辑全链零反应`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        b.onStarted("some.vrma")
        b.onFinished("some.vrma")
        s.advance(1000)
        assertTrue(rec.shown.isEmpty() && rec.hidden.isEmpty())
    }

    @Test
    fun `unbind 后不再反应`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        b.bind("play.vrma", "phone")
        assertTrue(b.unbind("play.vrma", "phone"))
        assertFalse(b.unbind("play.vrma", "phone")) // 重复解除 = false
        b.onStarted("play.vrma")
        b.onFinished("play.vrma")
        s.advance(500)
        assertTrue(rec.shown.isEmpty() && rec.hidden.isEmpty())
    }

    @Test
    fun `跨配饰切换 旧的延迟隐藏 新的立即在场`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        b.bind("texting.vrma", "phone")
        b.bind("holding.vrma", "radio")
        b.onStarted("texting.vrma")

        b.onStarted("holding.vrma")
        assertEquals(listOf("phone", "radio"), rec.shown) // phone 先在，radio 补上
        s.advance(250)
        assertEquals(listOf("phone"), rec.hidden)
    }

    @Test
    fun `bind 空参数拒绝`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        assertFalse(b.bind("", "phone"))
        assertFalse(b.bind("play.vrma", " "))
        assertFalse(b.unbind("", "phone"))
    }

    @Test
    fun `animations 前缀归一(绑定表类别相对路径 vs 播放全路径)`() {
        val s = FakeScheduler()
        val rec = Recorder()
        val b = binder(s, rec)
        // 绑定表写类别相对路径（bindings.json 惯例）
        assertTrue(b.bind("07_日常互动/Standing Texting On Phone.vrma", "phone"))

        // 播放侧带 animations/ 前缀（STAGE_CMD play 全路径）同样命中
        b.onStarted("animations/07_日常互动/Standing Texting On Phone.vrma")
        assertEquals(listOf("phone"), rec.shown)
        b.onFinished("animations/07_日常互动/Standing Texting On Phone.vrma")
        s.advance(250)
        assertEquals(listOf("phone"), rec.hidden)
    }
}
