package com.neethu.orchestrator.locomotion

import com.neethu.corelib.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锚点表解析（docs/locomotion-feasibility.md §7.2）：两种顶层形态、脏条目
 * 丢弃、名字归一与双语 label 回落。协议块的 `<move:>` 词表直接吃解析产物
 * ——名字大小写/缺字段的规则锁在这里。
 */
class MoveAnchorTest {

    @Test
    fun `parses anchors object with labels`() {
        val json = """
            {"anchors":[
              {"name":"sofa","x":1.6,"z":-1.2,"label":"沙发","labelEn":"the sofa"},
              {"name":"window","x":-1.8,"z":-2.8,"label":"窗边","labelEn":"the window"}
            ]}
        """.trimIndent()
        val anchors = parseMoveAnchors(json)
        assertEquals(
            listOf(
                MoveAnchor("sofa", 1.6f, -1.2f, "沙发", "the sofa"),
                MoveAnchor("window", -1.8f, -2.8f, "窗边", "the window"),
            ),
            anchors,
        )
    }

    @Test
    fun `accepts a bare top-level array`() {
        val anchors = parseMoveAnchors("""[{"name":"door","x":0,"z":-4.6}]""")
        assertEquals(listOf(MoveAnchor("door", 0f, -4.6f)), anchors)
    }

    @Test
    fun `drops entries missing name or coordinates and lowercases names`() {
        val json = """
            {"anchors":[
              {"x":1.0,"z":2.0},
              {"name":"no-z","x":1.0},
              {"name":"MixEd","x":3.0,"z":4.0}
            ]}
        """.trimIndent()
        val anchors = parseMoveAnchors(json)
        assertEquals(listOf(MoveAnchor("mixed", 3f, 4f)), anchors)
    }

    @Test
    fun `malformed json yields empty list`() {
        assertTrue(parseMoveAnchors("not json").isEmpty())
        assertTrue(parseMoveAnchors("""{"anchors":"nope"}""").isEmpty())
        assertTrue(parseMoveAnchors("""{"other":[]}""").isEmpty())
    }

    @Test
    fun `labelFor falls back across languages`() {
        val full = MoveAnchor("sofa", 1f, 2f, "沙发", "the sofa")
        assertEquals("沙发", full.labelFor(Lang.ZH))
        assertEquals("the sofa", full.labelFor(Lang.EN))

        val zhOnly = MoveAnchor("sofa", 1f, 2f, label = "沙发")
        assertEquals("沙发", zhOnly.labelFor(Lang.EN))

        val bare = MoveAnchor("sofa", 1f, 2f)
        assertEquals("sofa", bare.labelFor(Lang.ZH))
        assertEquals("sofa", bare.labelFor(Lang.EN))
    }
}
