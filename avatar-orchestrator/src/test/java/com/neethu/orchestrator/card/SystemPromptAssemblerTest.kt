package com.neethu.orchestrator.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptAssemblerTest {

    @Test
    fun `joins card fields in AIRI order`() {
        val assembler = SystemPromptAssembler(includeEmotionProtocol = false)
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
        val assembler = SystemPromptAssembler(includeEmotionProtocol = false)
        val prompt = assembler.assemble(CharacterCard(name = "x", description = "仅描述"))
        assertEquals("仅描述", prompt)
    }

    @Test
    fun `emotion protocol is appended and lists all names`() {
        val assembler = SystemPromptAssembler()
        val prompt = assembler.assemble(CharacterCard(name = "x", description = "描述"))
        assertTrue(prompt.startsWith("描述"))
        assertTrue(prompt.contains("<|emotion:"))
        assertTrue(prompt.contains("0.8"))
        assertTrue(prompt.contains("neutral"))
    }

    @Test
    fun `null card yields protocol only`() {
        val assembler = SystemPromptAssembler()
        assertTrue(assembler.assemble(null).contains("emotion"))
    }
}
