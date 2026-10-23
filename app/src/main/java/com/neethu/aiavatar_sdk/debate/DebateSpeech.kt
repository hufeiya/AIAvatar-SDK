package com.neethu.aiavatar_sdk.debate

import com.neethu.aiavatar_sdk.AiChatPrefs
import com.neethu.corelib.Lang
import com.neethu.orchestrator.facade.AIAvatarSdk
import com.neethu.orchestrator.facade.ChatProvider
import com.neethu.orchestrator.facade.TtsEngine

/** 固定稿无需 LLM Key；语音独立使用豆包语音 Key，不继承全局 Edge/其他 TTS。 */
internal object DebateSpeech {
    fun config(prefs: AiChatPrefs, matchId: String, id: String, name: String, voice: String, persona: String): AIAvatarSdk.ChatConfig =
        prefs.toChatConfig("debate_${matchId}_$id", name, Lang.ZH).copy(
            apiKey = prefs.apiKeyFor(prefs.provider).ifBlank { "debate-fixed-script" },
            ttsEngine = TtsEngine.OPENAI_COMPATIBLE,
            ttsProvider = ChatProvider.VOLCANO,
            ttsApiKey = prefs.apiKeyVolcanoTts,
            ttsModel = "seed-tts-2.0",
            voice = voice,
            enableLlmCamera = false,
            enableLlmMove = false,
            persona = persona,
            ttsMaxConcurrent = 2,
        )
}
