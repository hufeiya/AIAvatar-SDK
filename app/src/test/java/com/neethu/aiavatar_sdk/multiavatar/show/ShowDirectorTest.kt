package com.neethu.aiavatar_sdk.multiavatar.show

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ShowDirector 状态机全分支（docs/multi-dance-feasibility.md §4.6/§7-P0/P1）：
 * 幕次顺序推进、独舞/走位/串场词的事件+超时双路收尾、无关事件不误推进、
 * skip 跳幕、stop 中断零残留、DONE 重演、应援随机人数、回位 mark 簿记、
 * music 幕指令。
 *
 * 时序纪律（multi-avatar P3 单测教训）：超时分支用 advanceTimeBy+runCurrent，
 * 绝不 advanceUntilIdle 断言中途状态——advanceUntilIdle 会把 delay 兜底推过。
 * 应答器（autoOut）在指令落地时自动补发引擎事件，模拟真机的 vrmaFinished/
 * walkFinished 回流。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowDirectorTest {

    private fun script(vararg acts: ShowAct) = ShowScript(title = "t", acts = acts.toList())

    private fun soloAct(performer: String = "A", clip: String = "animations/x.vrma") =
        ShowAct(type = "dance", performer = performer, clip = clip)

    /**
     * 记指令并模拟引擎回流：一次性 PlayDance → vrmaFinished；Walk →
     * walkFinished(arrived)。say 默认不回流（超时分支用），要事件推进传
     * respondSay=true。齐舞（loop）不回流（时间驱动收尾）。
     */
    private fun autoOut(
        dir: ShowDirector,
        cmds: MutableList<String>,
        respondSay: Boolean = false,
    ): (DirectorCmd) -> Unit = { cmd ->
        cmds += cmd.label
        when (cmd) {
            is DirectorCmd.PlayDance -> if (!cmd.loop) dir.onVrmaFinished(cmd.performer)
            is DirectorCmd.Walk -> dir.onWalkFinished(cmd.performer, arrived = true)
            is DirectorCmd.Say -> if (respondSay) dir.onSayCompleted(cmd.performer)
            else -> Unit
        }
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
        // 开场同框 → 观众注视+应援+机位 → 独舞 → 走回队列+转身+观众复位
        assertEquals("cam group", cmds[0])
        val iGaze = cmds.indexOf("gaze B → A")
        val iClap = cmds.indexOf("clap B")
        val iCam = cmds.indexOf("cam full A")
        val iPlay = cmds.indexOf("play A animations/x.vrma")
        assertTrue(iGaze in 0 until iClap && iClap < iCam && iCam < iPlay)
        // 回位：mark=开演位 (0,-4)（positionOf 未注入时的兜底），走回+转镜头
        assertTrue(cmds.contains("walk A → (0.00, -4.00)"))
        assertTrue(cmds.contains("face camera A"))
        assertTrue(cmds.indexOf("face camera A") < cmds.indexOf("gaze B → camera"))
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
        assertFalse(cmds.any { it.startsWith("walk A") })

        advanceTimeBy(10); runCurrent()
        assertTrue(cmds.any { it.startsWith("vrma 超时兜底推进: A") })
        assertTrue(cmds.contains("walk A → (0.00, -4.00)"))
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
        assertFalse(cmds.any { it.startsWith("walk A") })

        dir.onVrmaFinished("A")
        runCurrent()
        assertTrue(cmds.any { it.startsWith("walk A") })
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
        // skip 提前收独舞 → 进入 RETURN：走回队列（P1 起回位本身也要走完）
        assertTrue(cmds.contains("walk A → (0.00, -4.00)"))
        assertEquals(0, dir.progress.value.index)
        dir.onWalkFinished("A", arrived = true)
        runCurrent()
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

    // ── P1：串场词（say 幕 / dance.line）──────────────────────────────────

    @Test
    fun `say幕等收尾事件再推进`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "say", performer = "A", text = "欢迎"), ShowAct(type = "bow")),
            listOf("A", "B"), out = { cmds += it.label }, durationOf = { _, _ -> 3f },
        )
        runCurrent()
        assertTrue(cmds.contains("say A: 欢迎"))
        assertEquals(0, dir.progress.value.index) // 说完才放行（等待中）

        dir.onSayCompleted("A") // 该槽 TurnCompleted 的转发点
        runCurrent()
        assertEquals(1, dir.progress.value.index)
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `say幕8s超时兜底推进`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "say", performer = "A", text = "欢迎"), ShowAct(type = "bow")),
            listOf("A", "B"), out = { cmds += it.label }, durationOf = { _, _ -> 3f },
        )
        runCurrent()
        advanceTimeBy(7_999); runCurrent()
        assertEquals(0, dir.progress.value.index)
        assertFalse(cmds.any { it.startsWith("say 超时") })

        advanceTimeBy(10); runCurrent()
        assertTrue(cmds.any { it.startsWith("say 超时兜底推进: A") })
        assertEquals(1, dir.progress.value.index)
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `dance带line先说串场词再起舞且应援并行`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(
                ShowAct(type = "dance", performer = "A", clip = "animations/x.vrma",
                    fans = listOf("clap"), line = "看我的！"),
            ),
            listOf("A", "B"), out = autoOut(dir, cmds, respondSay = true),
            durationOf = { _, _ -> 4f },
        )
        runCurrent()
        // 应援与串场词并行发射（TTS 首包空隙被鼓掌填上），说收才 play
        val iClap = cmds.indexOf("clap B")
        val iSay = cmds.indexOf("say A: 看我的！")
        val iPlay = cmds.indexOf("play A animations/x.vrma")
        assertTrue(iClap in 0 until iSay && iSay < iPlay)
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    // ── P1：走位（move 幕）────────────────────────────────────────────────

    /** positionOf：A 固定在原点；随 walked 标志「瞬移」到走位目标（模拟到位）。 */
    private fun stageFrontAnchors() = mapOf(
        "stage_front_a" to (1.0f to -2.0f),
        "stage_a" to (-0.8f to -4.0f),
    )

    @Test
    fun `move幕走位到位才推进且回位走回开演位`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        val anchors = stageFrontAnchors()
        var aPos: Pair<Float, Float> = 0f to 0f // 开演位（应答器随走动更新，模拟真实位移）
        dir.start(
            script(
                ShowAct(type = "move", performer = "A", target = "stage_front_a"),
                soloAct(),
            ),
            listOf("A", "B"),
            out = { cmd ->
                cmds += cmd.label
                when (cmd) {
                    is DirectorCmd.PlayDance -> if (!cmd.loop) dir.onVrmaFinished(cmd.performer)
                    is DirectorCmd.Walk -> {
                        aPos = cmd.x to cmd.z
                        dir.onWalkFinished(cmd.performer, arrived = true)
                    }
                    else -> Unit
                }
            },
            durationOf = { _, _ -> 3f },
            anchorOf = { anchors[it] },
            positionOf = { if (it == "A") aPos else null },
        )
        runCurrent()
        // 上场：walk 到表演位，到位才 play
        assertTrue(cmds.contains("walk A → (1.00, -2.00)"))
        val iWalk = cmds.indexOf("walk A → (1.00, -2.00)")
        val iPlay = cmds.indexOf("play A animations/x.vrma")
        assertTrue(iWalk in 0 until iPlay)
        advanceUntilIdle()
        // 回位：mark＝开演位原点（退场走回队列，不留在表演位）
        assertTrue(cmds.contains("walk A → (0.00, 0.00)"))
        assertTrue(cmds.indexOf("walk A → (0.00, 0.00)") > iPlay)
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `move幕已在位免走直接到位`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        val anchors = stageFrontAnchors()
        dir.start(
            script(ShowAct(type = "move", performer = "A", target = "stage_front_a"), ShowAct(type = "bow")),
            listOf("A", "B"),
            out = { cmds += it.label },
            anchorOf = { anchors[it] },
            positionOf = { if (it == "A") (1.0f to -2.0f) else null }, // 已站在锚点上
        )
        runCurrent()
        assertFalse(cmds.any { it.startsWith("walk A") })
        assertTrue(cmds.any { it.contains("已在位") })
        assertEquals(1, dir.progress.value.index) // 无等待直接推进
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `move幕锚点不在表内记行跳过`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "move", performer = "A", target = "nowhere"), ShowAct(type = "bow")),
            listOf("A", "B"), out = { cmds += it.label }, anchorOf = { null },
        )
        runCurrent()
        assertTrue(cmds.any { it.startsWith("move 跳过: 锚点 'nowhere'") })
        assertEquals(1, dir.progress.value.index)
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `move幕走位15s超时就地推进`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        val anchors = stageFrontAnchors()
        dir.start(
            script(ShowAct(type = "move", performer = "A", target = "stage_front_a"), ShowAct(type = "bow")),
            listOf("A", "B"), out = { cmds += it.label }, anchorOf = { anchors[it] },
        )
        runCurrent()
        advanceTimeBy(14_999); runCurrent()
        assertEquals(0, dir.progress.value.index)
        advanceTimeBy(10); runCurrent()
        assertTrue(cmds.any { it.startsWith("walk 超时兜底推进: A") })
        assertEquals(1, dir.progress.value.index)
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `walk取消事件也算收尾不卡场`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        val anchors = stageFrontAnchors()
        dir.start(
            script(ShowAct(type = "move", performer = "A", target = "stage_front_a"), ShowAct(type = "bow")),
            listOf("A", "B"), out = { cmds += it.label }, anchorOf = { anchors[it] },
        )
        runCurrent()
        dir.onWalkFinished("A", arrived = false) // 半路被取消
        runCurrent()
        assertEquals(1, dir.progress.value.index)
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    // ── P1：配乐（music 幕）───────────────────────────────────────────────

    @Test
    fun `music幕发启停与音量指令`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        val s = ShowScript(
            title = "t", music = "music/x.mp3",
            acts = listOf(
                ShowAct(type = "music", action = "start"),
                ShowAct(type = "music", action = "duck"),
                ShowAct(type = "music", action = "full"),
                ShowAct(type = "music", action = "stop"),
                ShowAct(type = "bow"),
            ),
        )
        dir.start(s, listOf("A", "B"), out = { cmds += it.label }, durationOf = { _, _ -> 3f })
        advanceUntilIdle()
        assertEquals(
            listOf("music start music/x.mp3", "music duck(vol=0.35)", "music full(vol=0.65)", "music stop"),
            cmds.subList(0, 4),
        )
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `music幕缺曲目声明记行跳过不拦单`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        // 绕过解析器直接构造（解析器会拒 start+无 music；导演侧防御路径兜底）
        dir.start(
            script(ShowAct(type = "music", action = "start"), ShowAct(type = "bow")),
            listOf("A", "B"), out = { cmds += it.label }, durationOf = { _, _ -> 3f },
        )
        runCurrent()
        assertTrue(cmds.any { it.startsWith("music start 跳过") })
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    // ── P2：点播插幕 ─────────────────────────────────────────────────────

    @Test
    fun `点播插幕在当前幕收尾后执行不打断进行中的幕`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "wait", seconds = 5f), soloAct(performer = "B", clip = "animations/b.vrma")),
            listOf("A", "B"), out = autoOut(dir, cmds), durationOf = { _, _ -> 5f },
        )
        runCurrent()
        // wait 幕进行中点播 A——插幕应成为 wait 之后的下一幕（原定幕顺延）
        assertTrue(dir.enqueueAct(soloAct()))
        advanceUntilIdle()
        val iInsert = cmds.indexOf("play A animations/x.vrma")
        val iNext = cmds.indexOf("play B animations/b.vrma")
        assertTrue("insert=$iInsert next=$iNext", iInsert in 0 until iNext)
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `插幕更新进度总幕数`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(script(ShowAct(type = "wait", seconds = 5f)), listOf("A", "B"), out = { cmds += it.label })
        runCurrent()
        assertEquals(1, dir.progress.value.total)
        assertTrue(dir.enqueueAct(soloAct()))
        runCurrent() // 插幕受理后、wait 收尾前：总数已含插幕
        advanceTimeBy(5_100); runCurrent() // wait 收尾 → 插幕成为当前幕
        assertEquals(2, dir.progress.value.total)
        assertEquals("dance", dir.progress.value.act?.type)
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `非演出中插幕被拒`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        assertFalse(dir.enqueueAct(soloAct())) // 开演前
        dir.start(script(soloAct()), listOf("A"), out = autoOut(dir, cmds), durationOf = { _, _ -> 1f })
        advanceUntilIdle()
        assertEquals(ShowState.DONE, dir.state.value)
        val sizeAfterShow = cmds.size
        assertFalse(dir.enqueueAct(soloAct())) // 演毕
        runCurrent()
        assertEquals("拒收不得产生新指令", sizeAfterShow, cmds.size)
    }

    @Test
    fun `停演清空待插幕不泄漏到下一场`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(script(ShowAct(type = "wait", seconds = 30f)), listOf("A", "B"), out = { cmds += it.label })
        runCurrent()
        assertTrue(dir.enqueueAct(soloAct()))
        dir.stop()
        assertEquals(ShowState.STOPPED, dir.state.value)
        // 新一场：旧插幕已随 stop 清空
        dir.start(script(ShowAct(type = "wait", seconds = 1f)), listOf("A", "B"), out = { cmds += it.label })
        advanceUntilIdle()
        assertFalse(cmds.any { it.startsWith("play A animations/x.vrma") })
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `连续插幕按受理顺序FIFO执行`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(script(ShowAct(type = "wait", seconds = 5f)), listOf("A", "B"), out = autoOut(dir, cmds), durationOf = { _, _ -> 3f })
        runCurrent()
        assertTrue(dir.enqueueAct(soloAct(performer = "A", clip = "animations/x.vrma")))
        assertTrue(dir.enqueueAct(soloAct(performer = "B", clip = "animations/y.vrma")))
        advanceUntilIdle()
        val iX = cmds.indexOf("play A animations/x.vrma")
        val iY = cmds.indexOf("play B animations/y.vrma")
        assertTrue("x=$iX y=$iY", iX in 0 until iY)
        assertEquals(ShowState.DONE, dir.state.value)
    }

    // ── 幕次道具（P2 配饰生态，docs/accessory-feasibility.md §5.6）────────

    @Test
    fun `dance幕props开演挂收幕摘且在play两侧`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        val act = soloAct().copy(props = listOf("microphone", "lightstick"))
        dir.start(script(act), listOf("A"), out = autoOut(dir, cmds), durationOf = { _, _ -> 3f })
        advanceUntilIdle()
        val iOn = cmds.mapIndexedNotNull { i, c -> if (c.startsWith("prop +")) i else null }
        val iPlay = cmds.indexOf("play A animations/x.vrma")
        val iOff = cmds.mapIndexedNotNull { i, c -> if (c.startsWith("prop -")) i else null }
        assertEquals(listOf("prop +microphone @ A", "prop +lightstick @ A"), iOn.map { cmds[it] })
        assertTrue("挂载须在起播前", iOn.all { it < iPlay })
        assertEquals(listOf("prop -microphone @ A", "prop -lightstick @ A"), iOff.map { cmds[it] })
        assertTrue("摘除须在收幕后", iOff.all { it > iPlay })
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `bow幕props全员挂全员摘`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "bow", props = listOf("lightstick"))),
            listOf("A", "B"),
            out = autoOut(dir, cmds),
            durationOf = { _, _ -> 3f },
        )
        advanceUntilIdle()
        assertTrue(cmds.containsAll(listOf("prop +lightstick @ A", "prop +lightstick @ B")))
        assertTrue(cmds.containsAll(listOf("prop -lightstick @ A", "prop -lightstick @ B")))
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `group_dance幕props在StopDances前摘`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(
            script(ShowAct(type = "group_dance", clip = "animations/g.vrma", seconds = 4f, props = listOf("hat"))),
            listOf("A", "B"),
            out = autoOut(dir, cmds),
        )
        advanceUntilIdle()
        val iOff = cmds.indexOf("prop -hat @ A")
        val iStop = cmds.indexOf("stop dances(fade=0.3)")
        assertTrue("摘道具须在停舞指令前 iOff=$iOff iStop=$iStop", iOff in 0 until iStop)
        assertEquals(ShowState.DONE, dir.state.value)
    }

    @Test
    fun `无props幕零道具指令`() = runTest {
        val cmds = mutableListOf<String>()
        val dir = ShowDirector(this)
        dir.start(script(soloAct()), listOf("A"), out = autoOut(dir, cmds), durationOf = { _, _ -> 3f })
        advanceUntilIdle()
        assertTrue(cmds.none { it.startsWith("prop ") })
        assertEquals(ShowState.DONE, dir.state.value)
    }
}
