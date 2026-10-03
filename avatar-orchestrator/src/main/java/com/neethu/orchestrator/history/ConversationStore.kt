package com.neethu.orchestrator.history

import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole

/**
 * Conversation persistence boundary. V1 ships an in-memory ring; a Room-backed
 * implementation is the planned Phase-5 addition (same interface).
 */
interface ConversationStore {
    /** All stored user/assistant messages, in order. */
    fun messages(): List<ChatMessage>

    fun appendUser(text: String)
    fun appendAssistant(text: String)

    /** Drop everything (new conversation). */
    fun clear()
}

/**
 * In-memory store bounded by [maxMessages] (oldest messages evicted first).
 * The system prompt is intentionally not stored here — sessions compose it
 * separately at request time.
 */
class InMemoryConversationStore(
    private val maxMessages: Int = 80,
) : ConversationStore {

    private val messages = ArrayDeque<ChatMessage>()

    override fun messages(): List<ChatMessage> = synchronized(messages) { messages.toList() }

    override fun appendUser(text: String) = append(ChatMessage(ChatRole.USER, text))

    override fun appendAssistant(text: String) = append(ChatMessage(ChatRole.ASSISTANT, text))

    override fun clear() = synchronized(messages) { messages.clear() }

    private fun append(message: ChatMessage) = synchronized(messages) {
        messages.addLast(message)
        while (messages.size > maxMessages) messages.removeFirst()
    }
}
