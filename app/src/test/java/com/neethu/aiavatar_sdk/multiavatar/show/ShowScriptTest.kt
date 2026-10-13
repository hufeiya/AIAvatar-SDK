package com.neethu.aiavatar_sdk.multiavatar.show

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 团播节目单解析校验（docs/multi-dance-feasibility.md §4.4）：坏 JSON/未知 act/
 * 缺字段/未知机位/未知观众行为整单拒绝并给原因行；clip 路径归一；时长粗估。
 */
class ShowScriptTest {

    private fun parse(json: String): Result<ShowScript> = ShowScriptParser.parse(json)

    private val fullShow = """
        {
          "version": 1,
          "title": "开业团播",
          "acts": [
            { "type": "camera", "shot": "group" },
            { "type": "say",    "performer": "A", "text": "欢迎来到今晚的团播现场！" },
            { "type": "dance",  "performer": "A", "clip": "08_舞蹈与表演/Bboy Hip Hop Variation One", "camera": "full", "fans": ["clap"] },
            { "type": "wait",   "seconds": 1.5 },
            { "type": "group_dance", "clip": "08_舞蹈与表演/Doing The Shuffling Dance", "seconds": 12, "camera": "group" },
            { "type": "bow",    "camera": "group" }
          ]
        }
    """.trimIndent()

    @Test
    fun `完整节目单解析通过且字段映射正确`() {
        val script = parse(fullShow).getOrThrow()
        assertEquals("开业团播", script.title)
        assertEquals(6, script.acts.size)
        assertEquals("camera", script.acts[0].type)
        assertEquals("group", script.acts[0].shot)
        val dance = script.acts[2]
        assertEquals("dance", dance.type)
        assertEquals("A", dance.performer)
        assertEquals("full", dance.camera)
        assertEquals(listOf("clap"), dance.fans)
        assertEquals(1.5f, script.acts[3].seconds)
        assertEquals(12f, script.acts[4].seconds)
    }

    @Test
    fun `坏JSON整单拒绝且原因带前缀`() {
        val result = parse("{ not json ]")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.startsWith("节目单拒绝"))
    }

    @Test
    fun `未知键拒绝（手误不静默吞）`() {
        val result = parse("""{"version":1,"acts":[{"type":"bow","camra":"group"}]}""")
        assertTrue(result.isFailure)
    }

    @Test
    fun `未知act类型拒绝并带幕序号`() {
        val result = parse("""{"version":1,"acts":[{"type":"solo","performer":"A"}]}""")
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("act 1"))
        assertTrue(msg, msg.contains("未知类型"))
    }

    @Test
    fun `dance缺performer与clip都报出`() {
        val result = parse("""{"version":1,"acts":[{"type":"dance"}]}""")
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("缺 performer"))
        assertTrue(msg, msg.contains("缺 clip"))
    }

    @Test
    fun `group_dance缺seconds拒绝`() {
        val result = parse("""{"version":1,"acts":[{"type":"group_dance","clip":"08_x/Y"}]}""")
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("seconds"))
    }

    @Test
    fun `wait非正seconds拒绝`() {
        val result = parse("""{"version":1,"acts":[{"type":"wait","seconds":0}]}""")
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("seconds"))
    }

    @Test
    fun `camera缺shot拒绝`() {
        val result = parse("""{"version":1,"acts":[{"type":"camera"}]}""")
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("缺 shot"))
    }

    @Test
    fun `未知机位拒绝`() {
        val result = parse("""{"version":1,"acts":[{"type":"camera","shot":"drone"}]}""")
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("未知机位"))
    }

    @Test
    fun `未知观众行为拒绝`() {
        val result = parse(
            """{"version":1,"acts":[{"type":"dance","performer":"A","clip":"c","fans":["mosh"]}]}"""
        )
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("未知观众行为"))
    }

    @Test
    fun `version不符与空acts拒绝`() {
        val v = parse("""{"version":2,"acts":[{"type":"bow"}]}""")
        assertTrue(v.exceptionOrNull()!!.message!!, v.exceptionOrNull()!!.message!!.contains("version"))
        val e = parse("""{"version":1,"acts":[]}""")
        assertTrue(e.exceptionOrNull()!!.message!!, e.exceptionOrNull()!!.message!!.contains("acts 为空"))
    }

    @Test
    fun `say缺text拒绝`() {
        val result = parse("""{"version":1,"acts":[{"type":"say","performer":"A"}]}""")
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg, msg.contains("缺 text"))
    }

    @Test
    fun `clip路径归一三形态`() {
        assertEquals(
            "animations/08_舞蹈与表演/Bboy Hip Hop Variation One.vrma",
            resolveClipAsset("08_舞蹈与表演/Bboy Hip Hop Variation One"),
        )
        assertEquals("animations/X.vrma", resolveClipAsset("animations/X.vrma"))
        assertEquals("animations/X.vrma", resolveClipAsset("animations/X"))
    }

    @Test
    fun `resolveClips只动clip字段`() {
        val script = parse(fullShow).getOrThrow().resolveClips()
        assertEquals(
            "animations/08_舞蹈与表演/Bboy Hip Hop Variation One.vrma",
            script.acts[2].clip,
        )
        assertEquals("A", script.acts[2].performer)
    }

    @Test
    fun `时长粗估`() {
        val script = parse(fullShow).getOrThrow()
        // camera 3 + say 3 + dance 8 + wait 1.5 + group 12 + bow 3 = 30.5
        assertEquals(30.5f, estimateSeconds(script), 0.01f)
    }
}
