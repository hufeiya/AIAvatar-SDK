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
    fun `protocol block lists cameras emotions and actions`() {
        val block = SystemPromptAssembler()
            .multimodalProtocolBlock(actions = listOf("wave" to "挥手问候", "nod" to "点头认可"))
        assertTrue(block.contains("<cam:机位>"))
        assertTrue(block.contains("close_up(面部特写)"))
        assertTrue(block.contains("<act:动作>"))
        assertTrue(block.contains("wave(挥手问候)"))
        assertTrue(block.contains("<emo:情绪:强度>"))
        assertTrue(block.contains("neutral"))
        assertTrue(block.contains("0.0~1.0"))
    }

    @Test
    fun `protocol block omits sections with empty usable lists`() {
        val noActions = SystemPromptAssembler().multimodalProtocolBlock(actions = emptyList())
        assertFalse(noActions.contains("<act:"))
        assertTrue(noActions.contains("<cam:"))

        val noCameras = SystemPromptAssembler().multimodalProtocolBlock(cameras = emptyList())
        assertFalse(noCameras.contains("<cam:"))
        assertTrue(noCameras.contains("<emo:"))
    }
}
