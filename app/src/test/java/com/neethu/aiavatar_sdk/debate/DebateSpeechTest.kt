package com.neethu.aiavatar_sdk.debate

import com.neethu.aiavatar_sdk.AiChatPrefs
import com.neethu.aiavatar_sdk.AiProvider
import com.neethu.orchestrator.debate.DebateConfigParser
import com.neethu.orchestrator.facade.ChatProvider
import com.neethu.orchestrator.facade.TtsEngine
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DebateSpeechTest {
    @Test fun `fixed script uses Volcano with no LLM key even when global TTS is Edge`() {
        val prefs = AiChatPrefs(provider = AiProvider.DEEPSEEK, ttsEngine = TtsEngine.EDGE,
            apiKeyVolcanoTts = "test-tts-key")
        val config = DebateSpeech.config(prefs, "match", "HOST", "主持人",
            "zh_male_m191_uranus_bigtts", "中立")
        assertTrue(config.isConfigured)
        assertEquals(ChatProvider.VOLCANO, config.ttsProviderResolved)
        assertEquals(TtsEngine.OPENAI_COMPATIBLE, config.ttsEngineResolved)
        assertEquals("test-tts-key", config.ttsKeyResolved)
        assertEquals("seed-tts-2.0", config.resolvedTtsModel)
        assertEquals("zh_male_m191_uranus_bigtts", config.resolvedVoice)
    }

    @Test fun `Ark key cannot substitute for the speech key or silently fall back to Edge`() {
        val config = DebateSpeech.config(AiChatPrefs(apiKeyVolcano = "ark-only"),
            "match", "HOST", "主持人", "", "中立")
        assertFalse(config.ttsReady)
        assertEquals("", config.ttsKeyResolved)
        assertEquals(TtsEngine.OPENAI_COMPATIBLE, config.ttsEngineResolved)
    }

    @Test fun `both bundled schedules resolve nine distinct voices without catalog fallback`() {
        for (name in listOf("debate_default", "debate_quick")) {
            val dto = DebateConfigParser.parseAndValidate(
                File("src/main/assets/debate/$name.json").readText()).getOrThrow()
            val voices = dto.participants.map { p ->
                val config = DebateSpeech.config(AiChatPrefs(apiKeyVolcanoTts = "test"),
                    dto.matchId, p.id, p.name, p.voice, p.personality)
                assertEquals(p.voice, config.resolvedVoice)
                config.resolvedVoice
            }
            assertEquals(9, voices.toSet().size)
        }
    }
}
