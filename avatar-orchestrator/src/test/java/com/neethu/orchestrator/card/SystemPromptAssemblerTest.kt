package com.neethu.orchestrator.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptAssemblerTest {

    @Test
    fun `joins card fields in AIRI order`() {
        val assembler = SystemPromptAssembler()
        val prompt = assembler.assemble(
            CharacterCard(
                name = "星",
                systemPrompt = "SYS",
                description = "DESC",
                personality = "PERSONA",
                scenario = "SCEN",
            )
        )
        assertEquals("SYS\n\nDESC\n\nPERSONA\n\nSCEN", prompt)
    }

    @Test
    fun `blank fields are skipped`() {
        val assembler = SystemPromptAssembler()
        val prompt = assembler.assemble(CharacterCard(name = "x", description = "仅描述"))
        assertEquals("仅描述", prompt)
    }

    @Test
    fun `assemble is persona only — protocol never duplicated`() {
        // buildRequestMessages appends the protocol block once at send time;
        // assemble() must not bake it into the card persona.
        val assembler = SystemPromptAssembler()
        val prompt = assembler.assemble(CharacterCard(name = "x", description = "描述"))
        assertEquals("描述", prompt)
        assertTrue(assembler.assemble(null).isEmpty())
    }

    @Test
    fun `protocol block lists cameras grouped actions and direct expressions`() {
        val block = SystemPromptAssembler().multimodalProtocolBlock(
            actionGroups = listOf("04_交流手势" to listOf("wave", "nod"), "05_情绪表达" to listOf("pumping_a_fist")),
            directExpressions = listOf("aa", "blink_l"),
        )
        assertTrue(block.contains("<cam:机位>"))
        assertTrue(block.contains("close_up(面部特写)"))
        assertTrue(block.contains("<act:动作>"))
        assertTrue(block.contains("04_交流手势: wave nod"))
        assertTrue(block.contains("05_情绪表达: pumping_a_fist"))
        assertTrue(block.contains("<emo:名字:强度>"))
        assertTrue(block.contains("neutral"))
        // 直接表情段：原名直驱 + 0 恢复说明
        assertTrue(block.contains("blink_l"))
        assertTrue(block.contains("0=恢复"))
    }

    @Test
    fun `protocol block omits sections with empty usable lists`() {
        val noActions = SystemPromptAssembler().multimodalProtocolBlock(actionGroups = emptyList())
        assertFalse(noActions.contains("<act:"))
        assertTrue(noActions.contains("<cam:"))

        val noCameras = SystemPromptAssembler().multimodalProtocolBlock(cameras = emptyList())
        assertFalse(noCameras.contains("<cam:"))
        assertTrue(noCameras.contains("<emo:"))

        // 关掉的通道连示例里都不能出现（few-shot 示例按启用清单条件拼接）
        val bare = SystemPromptAssembler().multimodalProtocolBlock(
            cameras = emptyList(), actionGroups = emptyList(), directExpressions = emptyList(),
        )
        assertFalse(bare.contains("<cam:"))
        assertFalse(bare.contains("<act:"))
        assertFalse(bare.contains("原生名"))
        assertTrue(bare.contains("<emo:happy:0.8>"))
        // 每句换情绪的指导 + 示例里的情绪切换
        assertTrue(bare.contains("句首"))
        assertTrue(bare.contains("<emo:surprised:0.7>"))
    }

    @Test
    fun `advertised canonical emotions match EmotionBlender defs exactly`() {
        // 词表与 EmotionBlender.defs 键集必须一一对应（不多不少）：词表告知
        // LLM 的名字若 defs 没有 → 被门控静默丢弃（think 曾这样整体失效）；
        // defs 有而词表没有 → 模型永远不知道可以用。
        val advertised = SystemPromptAssembler().emotionNames
            .map { it.substringBefore('(') }
            .toSet()
        val defs = com.neethu.orchestrator.face.EmotionBlender(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        ).defs.keys
        assertEquals(defs, advertised)
    }
}
