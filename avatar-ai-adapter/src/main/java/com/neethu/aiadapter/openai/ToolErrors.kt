package com.neethu.aiadapter.openai

/**
 * Runtime auto-degrade classifier (P0 tool calling): does this LLM failure mean
 * the model/provider does not support tool calling at all?
 *
 * Ported from AIRI `core-agent/src/runtime/llm-service.ts`
 * TOOLS_RELATED_ERROR_PATTERNS (isToolRelatedError) with the same scoping rule:
 * an error about ONE tool (bad schema, invalid arguments, failed execution)
 * does NOT count — only evidence that the model/endpoint has no tool support.
 * (Live 400 samples from the provider matrix feed new patterns here.)
 */
private val TOOL_ERROR_PATTERNS: List<Regex> = listOf(
    Regex("does not support tools", RegexOption.IGNORE_CASE), // Ollama
    Regex("no endpoints found that support tool use", RegexOption.IGNORE_CASE), // OpenRouter
    Regex("functions are not supported", RegexOption.IGNORE_CASE), // Azure AI Foundry
    // OpenAI/Azure strict gateways rejecting the request argument itself
    // ("Unrecognized request argument supplied: tools")
    Regex("unrecognized request argument.+tools", RegexOption.IGNORE_CASE),
    Regex("tool use with function calling is unsupported", RegexOption.IGNORE_CASE), // Google
    Regex("does not support function.?calling", RegexOption.IGNORE_CASE), // Anthropic
    Regex("tools?\\s+(is|are)\\s+not\\s+supported", RegexOption.IGNORE_CASE), // Cloudflare
)

/** True when [t] (message + cause chain) indicates the endpoint has no tool support. */
fun isToolRelatedError(t: Throwable): Boolean {
    var cur: Throwable? = t
    val seen = HashSet<Throwable>()
    while (cur != null && seen.add(cur)) {
        val msg = cur.message.orEmpty()
        if (TOOL_ERROR_PATTERNS.any { it.containsMatchIn(msg) }) return true
        cur = cur.cause
    }
    return false
}
