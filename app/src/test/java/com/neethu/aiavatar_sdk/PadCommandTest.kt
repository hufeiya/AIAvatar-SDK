package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `pad` 命令族解析（docs/gamepad-control-feasibility.md §6）：arg token 与
 * extras 双形态、缺省、别名、非法输入报错。
 */
class PadCommandTest {

    private fun cmd(arg: String? = null, x: Float? = null, y: Float? = null, z: Float? = null) =
        AiDebugCommand(name = "pad", arg = arg, x = x, y = y, z = z)

    @Test
    fun `裸 pad 查状态`() {
        assertEquals(PadCommand.State, parsePadCommand(null, cmd()))
        assertEquals(PadCommand.State, parsePadCommand("state", cmd()))
        assertEquals(PadCommand.State, parsePadCommand("", cmd()))
    }

    @Test
    fun `move 用 arg token`() {
        val parsed = parsePadCommand("move 0 1", cmd()) as PadCommand.Move
        assertEquals(0f, parsed.x)
        assertEquals(1f, parsed.y)
        assertNull(parsed.seconds)
    }

    @Test
    fun `move 带时长秒`() {
        val parsed = parsePadCommand("move 0 -1 2", cmd()) as PadCommand.Move
        assertEquals(-1f, parsed.y)
        assertEquals(2f, parsed.seconds)
    }

    @Test
    fun `move 用 extras 位置对应`() {
        // ai_x=第1个数(左右) ai_y=第2个数(前后) ai_z=第3个数(秒)
        val parsed = parsePadCommand("move", cmd(x = 0.5f, y = -1f, z = 3f)) as PadCommand.Move
        assertEquals(0.5f, parsed.x)
        assertEquals(-1f, parsed.y)
        assertEquals(3f, parsed.seconds)
    }

    @Test
    fun `extras 优先于 token`() {
        val parsed = parsePadCommand("move 0 0", cmd(arg = "move", x = -0.5f)) as PadCommand.Move
        assertEquals(-0.5f, parsed.x)
        assertEquals(0f, parsed.y)
    }

    @Test
    fun `move 缺数字走缺省 0`() {
        val parsed = parsePadCommand("move", cmd()) as PadCommand.Move
        assertEquals(0f, parsed.x)
        assertEquals(0f, parsed.y)
    }

    @Test
    fun `look 及别名`() {
        val look = parsePadCommand("look 0.5 0", cmd()) as PadCommand.Look
        assertEquals(0.5f, look.x)
        assertEquals(0f, look.y)
        assertEquals(true, parsePadCommand("camera 0 0 1", cmd()) is PadCommand.Look)
    }

    @Test
    fun `look 非正秒数视作锁存`() {
        val parsed = parsePadCommand("look 0.5 0 0", cmd()) as PadCommand.Look
        assertNull(parsed.seconds)
        val neg = parsePadCommand("look 0.5 0 -1", cmd()) as PadCommand.Look
        assertNull(neg.seconds)
    }

    @Test
    fun `release 别名 stop`() {
        assertEquals(PadCommand.Release, parsePadCommand("release", cmd()))
        assertEquals(PadCommand.Release, parsePadCommand("stop", cmd()))
    }

    @Test
    fun `run 三态`() {
        assertEquals(true, (parsePadCommand("run on", cmd()) as PadCommand.Run).on)
        assertEquals(false, (parsePadCommand("run off", cmd()) as PadCommand.Run).on)
        assertNull((parsePadCommand("run", cmd()) as PadCommand.Run).on)
    }

    @Test
    fun `press 与总开关`() {
        assertEquals("a", (parsePadCommand("press a", cmd()) as PadCommand.Press).button)
        assertEquals(true, (parsePadCommand("on", cmd()) as PadCommand.Enabled).on)
        assertEquals(false, (parsePadCommand("off", cmd()) as PadCommand.Enabled).on)
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(PadCommand.Release, parsePadCommand("RELEASE", cmd()))
        assertEquals(true, (parsePadCommand("Move 0 1", cmd()) is PadCommand.Move))
    }

    @Test
    fun `未知子命令报错带可用清单`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            parsePadCommand("teleport", cmd())
        }
        assert(error.message!!.contains("move|look|release"))
    }

    @Test
    fun `press 缺按键名报错`() {
        assertThrows(IllegalArgumentException::class.java) {
            parsePadCommand("press", cmd())
        }
    }
}
