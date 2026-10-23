package com.neethu.aiavatar_sdk.debate

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.*
import com.neethu.orchestrator.debate.*
import com.neethu.orchestrator.facade.AIAvatarSdk
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test

class DebateAiSpeechTest {
    private fun config() = DebateConfigParser.parseAndValidate(
        File("src/main/assets/debate/debate_default.json").readText()).getOrThrow()

    private fun turn() = ActiveTurn(1L, 1L, DebatePhase.CROSS_EXAM,
        "PRO2", DebateTeam.PRO, TurnKind.CROSS_QUESTION, 10_000L, 0)

    @Test fun `real AI receives persona stance and recent played argument`() = runTest {
        val dto = config()
        val prior = TranscriptEntry(0L, 1L, DebatePhase.OPENING_STATEMENT, "CON1", DebateTeam.CON,
            TurnKind.STATEMENT, "推荐按钮会偷走你的想象力。", TranscriptStatus.PLAYED,
            TurnEndReason.COMPLETED, 0L, 100L, 100L)
        val adapter = object : LlmAdapter {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
                assertTrue(messages.first().content.contains("DeepSeek"))
                assertTrue(messages.first().content.contains(dto.sides.PRO))
                assertTrue(messages.last().content.contains(prior.text))
                assertFalse(messages.last().content.contains("没有播出的失败台词"))
                assertEquals("test-model", config.model)
                assertTrue(config.tools.isEmpty())
                return flowOf(LlmStreamEvent.TextDelta("拆一下："),
                    LlmStreamEvent.TextDelta("选择更多，怎么就等于没了判断？"))
            }
        }
        val actual = DebateAiSpeech.generate(adapter,
            AIAvatarSdk.ChatConfig(baseUrl = "https://example.test/v1", apiKey = "test", llmModel = "test-model"),
            dto, turn(), listOf(prior, prior.copy(text = "没有播出的失败台词", status = TranscriptStatus.FAILED)))
        assertEquals("拆一下：选择更多，怎么就等于没了判断？", actual)
    }

    @Test fun `overlong AI speech requests fallback instead of cutting an argument mid word`() = runTest {
        val adapter = object : LlmAdapter {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig) =
                flowOf(LlmStreamEvent.TextDelta("创造力".repeat(40)))
        }
        try {
            DebateAiSpeech.generate(adapter, AIAvatarSdk.ChatConfig(), config(), turn(), emptyList())
            fail("overlong speech must use a complete fallback")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("短句上限"))
        }
    }

    @Test fun `generation timeout and cancellation stop the in flight adapter`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val adapter = object : LlmAdapter {
            override fun streamChat(messages: List<ChatMessage>, config: LlmConfig) = flow<LlmStreamEvent> {
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        val request = async {
            DebateAiSpeech.generate(adapter, AIAvatarSdk.ChatConfig(),
                config().let { it.copy(speech = it.speech.copy(generationTimeoutMs = 25L)) }, turn(), emptyList())
        }
        try { request.await(); fail("expected timeout") } catch (_: TimeoutCancellationException) { }
        assertTrue(cancelled.isCompleted)
    }

    @Test fun `cleaning removes reasoning stage directions and caps spoken length`() {
        assertEquals("我是豆包！", DebateAiSpeech.clean("<think>隐藏思考</think>（挥手）**我是豆包！**<move:host>"))
        val long = DebateAiSpeech.clean("灵感".repeat(100), 30)
        assertTrue(long.length <= 30)
        assertTrue(long.endsWith("。"))
    }

    @Test fun `bundled AI show has nine different models and short fallback speeches`() {
        for (file in listOf("debate_default", "debate_quick")) {
            val dto = DebateConfigParser.parseAndValidate(
                File("src/main/assets/debate/$file.json").readText()).getOrThrow()
            assertEquals("ai", dto.speech.mode)
            assertEquals(9, dto.participants.map { it.model }.toSet().size)
            for (p in dto.participants) {
                assertTrue(File("src/main/assets/" + p.model.removePrefix("asset:")).isFile)
                val scripts = dto.scripts.getValue(p.id)
                assertTrue(scripts.values.all { it.length <= DebateAiSpeech.MAX_SPEECH_CHARS })
                if (p.id != DebateIds.HOST) {
                    assertTrue(scripts.keys.containsAll(listOf("OPENING_STATEMENT", "CROSS_EXAM", "CROSS_SUMMARY", "FREE_DEBATE", "CLOSING")))
                }
            }
        }
    }
}
