package com.neethu.aiavatar_sdk.multiavatar.show

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ShowDirector 状态机全分支（docs/multi-dance-feasibility.md §4.6/§7-P0）：
 * 幕次顺序推进、独舞事件/超时双路收尾、无关事件不误推进、skip 跳幕（独舞与
 * 齐舞 hold 都可跳）、stop 中断零残留、DONE 重演、应援随机人数。
 *
 * 时序纪律（multi-avatar P3 单测教训）：超时分支用 advanceTimeBy+runCurrent，
 * 绝不 advanceUntilIdle 断言中途状态——advanceUntilIdle 会把 delay 兜底推过。
 * 独舞 happy path 用「out 回调里自动补发 vrmaFinished」的应答器模拟引擎播完。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowDirectorTest {

    private fun script(vararg acts: ShowAct) = ShowScript(title = "t", acts = acts.toList())

    private fun soloAct(performer: String = "A", clip: String = "animations/x.vrma") =
        ShowAct(type = "dance", performer = performer, clip = clip)

    /** 记指令并在一次性 PlayDance 时自动补发「播完」事件（模拟引擎翻沿）。 */
    private fun autoOut(dir: ShowDirector, cmds: MutableList<String>): (DirectorCmd) -> Unit = { cmd ->
        cmds += cmd.label
        if (cmd is DirectorCmd.PlayDance && !cmd.loop) dir.onVrmaFinished(cmd.performer)
    }

    @Test
    fun `完整节目单按序推进到DONE`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        val s = script(
            ShowAct(type = "camera", shot = "group"),
            ShowAct(type = "dance", performer = "A", clip = "animations/x.vrma",
                camera = "full", fans = listOf("clap")),
            ShowAct(type = "wait", seconds = 1.5f),
            ShowAct(type = "group_dance", clip = "animations/y.vrma", seconds = 12f, camera = "group"),
            ShowAct(type = "bow", camera = "group"),
        )
        dir.start(s, listOf("A", "B"), out = autoOut(dir, cmds), durationOf = { _, _ -> 5f })
        advanceUntilIdle()

        assertEquals(ShowState.DONE, dir.state.value)
        assertTrue(dir.progress.value.isIdle)
        // 开场同框 → 观众注视+应援+机位 → 独舞 → 回位+观众复位
        assertEquals("cam group", cmds[0])
        val iGaze = cmds.indexOf("gaze B → A")
        val iClap = cmds.indexOf("clap B")
        val iCam = cmds.indexOf("cam full A")
        val iPlay = cmds.indexOf("play A animations/x.vrma")
        assertTrue(iGaze in 0 until iClap && iClap < iCam && iCam < iPlay)
        assertTrue(cmds.contains("return A → mark"))
        assertTrue(cmds.indexOf("gaze B → camera") > cmds.indexOf("return A → mark"))
        // 齐舞：全员 loop 起播在前，统一收尾在后
        val iGa = cmds.indexOf("play A animations/y.vrma loop")
        val iGb = cmds.indexOf("play B animations/y.vrma loop")
        val iStop = cmds.indexOf("stop dances(fade=0.3)")
        assertTrue(iGa in 0 until iGb && iGb < iStop)
        // 谢幕：全员鞠躬
        assertTrue(cmds.contains("play A animations/${ShowDirector.BOW_CLIP.removePrefix("animations/")}"))
        assertTrue(cmds.contains("play B animations/${ShowDirector.BOW_CLIP.removePrefix("animations/")}"))
        assertTrue(cmds.last().startsWith("show done"))
    }

    @Test
    fun `独舞播完事件丢失时超时兜底推进`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(script(soloAct()), listOf("A", "B"), out = { cmds += it.label },
            durationOf = { _, _ -> 5f })
        runCurrent()
        assertEquals(ShowState.RUNNING, dir.state.value)

        // 兜底 = 1.2×5+1 = 7s：差 1ms 不触发
        advanceTimeBy(6_999); runCurrent()
        assertFalse(cmds.any { it.startsWith("vrma 超时") })
        assertFalse(cmds.contains("return A → mark"))

        advanceTimeBy(10); runCurrent()
        assertTrue(cmds.any { it.startsWith("vrma 超时兜底推进: A") })
        assertTrue(cmds.contains("return A → mark"))
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `无关演员的播完事件不误推进`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(script(soloAct()), listOf("A", "B"), out = { cmds += it.label },
            durationOf = { _, _ -> 5f })
        runCurrent()
        assertEquals("dance", dir.progress.value.act?.type)
        assertEquals(0, dir.progress.value.index)

        dir.onVrmaFinished("B") // 观众应援播完，不是表演者
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertFalse(cmds.contains("return A → mark"))

        dir.onVrmaFinished("A")
        runCurrent()
        assertTrue(cmds.contains("return A → mark"))
    }

    @Test
    fun `skip跳过独舞且仍走回位收尾`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(soloAct(), ShowAct(type = "wait", seconds = 5f)),
            listOf("A", "B"), out = { cmds += it.label }, durationOf = { _, _ -> 5f },
        )
        runCurrent()
        dir.skip(); runCurrent()
        assertTrue(cmds.contains("return A → mark"))
        assertEquals(1, dir.progress.value.index) // 已推进到 wait 幕
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `skip可提前收齐舞hold`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "group_dance", clip = "animations/y.vrma", seconds = 12f)),
            listOf("A", "B"), out = { cmds += it.label },
        )
        runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertFalse(cmds.contains("stop dances(fade=0.3)"))
        dir.skip(); runCurrent()
        assertTrue(cmds.contains("stop dances(fade=0.3)"))
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `stop中断零残留`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(soloAct(), ShowAct(type = "group_dance", clip = "animations/y.vrma", seconds = 12f)),
            listOf("A", "B"), out = { cmds += it.label }, durationOf = { _, _ -> 5f },
        )
        runCurrent()
        val snapshot = cmds.toList()
        dir.stop()
        assertEquals(ShowState.STOPPED, dir.state.value)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(snapshot, cmds) // 停演后无任何新指令
        assertEquals(ShowState.STOPPED, dir.state.value)
    }

    @Test
    fun `DONE后可重演`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        val s = script(ShowAct(type = "camera", shot = "group"), soloAct())
        dir.start(s, listOf("A", "B"), out = autoOut(dir, cmds), durationOf = { _, _ -> 5f })
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
        val firstRound = cmds.size

        dir.start(s, listOf("A", "B"), out = autoOut(dir, cmds), durationOf = { _, _ -> 5f })
        assertEquals(ShowState.RUNNING, dir.state.value)
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
        assertTrue(cmds.size > firstRound)
        assertEquals(2, cmds.count { it == "cam group" })
    }

    @Test
    fun `应援随机挑1到2名观众`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "dance", performer = "A", clip = "animations/x.vrma", fans = listOf("clap"))),
            listOf("A", "B", "C"), out = autoOut(dir, cmds), durationOf = { _, _ -> 5f },
        )
        advanceUntilIdle()
        val claps = cmds.filter { it.startsWith("clap ") }
        assertTrue("claps=$claps", claps.size in 1..ShowDirector.CLAP_MAX_FANS)
        assertTrue(claps.all { it == "clap B" || it == "clap C" })
        assertTrue(cmds.containsAll(listOf("gaze B → A", "gaze C → A")))
    }

    @Test
    fun `say幕P0落日志指令不等待`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "say", performer = "A", text = "欢迎"), ShowAct(type = "bow")),
            listOf("A", "B"), out = autoOut(dir, cmds), durationOf = { _, _ -> 3f },
        )
        runCurrent()
        assertTrue(cmds.contains("say A: 欢迎")) // say 后立即推进，无需事件
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `camera幕支持锚定目标`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "camera", shot = "closeup", target = "B")),
            listOf("A", "B"), out = { cmds += it.label },
        )
        runCurrent()
        assertTrue(cmds.contains("cam closeup B"))
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }
}
