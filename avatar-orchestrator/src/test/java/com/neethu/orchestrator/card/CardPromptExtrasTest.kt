package com.neethu.orchestrator.card

import com.neethu.aiadapter.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡字段补全（酒馆 P1-3）：mes_example → few-shot 消息（`<START>` 分块、
 * 说话人→角色、宏展开、总预算）；post_history_instructions 宏展开。
 */
class CardPromptExtrasTest {

    private val card = CharacterCard(name = "Aria", messageExample = """
        <START>
        {{char}}: Hello, traveler. *waves*
        {{user}}: Hi Aria!
        <START>
        Aria: Nice weather today.
        You: Indeed.
        <START>
        <START>
        {{char}}: Last block.
    """.trimIndent())

    @Test
    fun `blocks split on START and roles resolved`() {
        val msgs = CardPromptExtras.parse(card, userAlias = "you")
        // 6 行 → 每块 2 条（块 3 是空的，跳过）：2 + 2 + 1
        assertEquals(5, msgs.size)
        assertEquals(ChatRole.ASSISTANT, msgs[0].role)
        assertEquals("Hello, traveler. *waves*", msgs[0].content)
        assertEquals(ChatRole.USER, msgs[1].role)
        assertEquals("Hi Aria!", msgs[1].content)
        // 实名说话人（卡名→assistant，You→user）
        assertEquals(ChatRole.ASSISTANT, msgs[2].role)
        assertEquals(ChatRole.USER, msgs[3].role)
        // 宏展开：{{char}} → 卡名
        assertEquals("Last block.", msgs[4].content)
        assertEquals(ChatRole.ASSISTANT, msgs[4].role)
    }

    @Test
    fun `macros expanded in content`() {
        val card2 = CharacterCard(
            name = "Aria",
            messageExample = "<START>\n{{char}}: Greetings, {{user}}.",
        )
        val msgs = CardPromptExtras.parse(card2, userAlias = "you")
        assertEquals("Greetings, you.", msgs.single().content)
    }

    @Test
    fun `empty mes_example yields nothing`() {
        assertTrue(CardPromptExtras.parse(CharacterCard(name = "X"), "you").isEmpty())
    }

    @Test
    fun `total char budget drops trailing blocks whole`() {
        val big = "<START>\n{{char}}: " + "a".repeat(4000) + "\n<START>\n{{char}}: tail"
        val card2 = CharacterCard(name = "X", messageExample = big)
        val msgs = CardPromptExtras.parse(card2, "you")
        assertEquals(1, msgs.size)
        assertTrue(!msgs[0].content.contains("tail"))
    }

    @Test
    fun `post_history_instructions expanded and blank-safe`() {
        val with = CharacterCard(
            name = "Aria",
            postHistoryInstructions = "Stay in character as {{char}}. Address the user as {{user}}.",
        )
        assertEquals(
            "Stay in character as Aria. Address the user as you.",
            CardPromptExtras.postHistoryInstructions(with, "you"),
        )
        assertNull(CardPromptExtras.postHistoryInstructions(CharacterCard(name = "X"), "you"))
    }
}
