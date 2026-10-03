package com.neethu.aiadapter.api

import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * Pluggable LLM backend speaking the OpenAI chat-completions wire protocol.
 *
 * Implementations must be safe to share across coroutines; the returned Flow is
 * cold — each collection performs one streaming request. Cancelling the
 * collector cancels the underlying HTTP call.
 */
interface LlmAdapter {
    fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent>
}
