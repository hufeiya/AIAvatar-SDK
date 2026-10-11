package com.neethu.aiadapter.openai

import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.ToolCallRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Streaming `delta.tool_calls` accumulator (OpenAI chat-completions protocol).
 *
 * Wire behavior measured live (2026-10-11, docs/agent-provider-matrix.md):
 * the first fragment of a call carries `index`+`id`+`function.name`; later
 * fragments only carry `index` and `function.arguments` increments — SiliconFlow
 * sends them as explicit `"id":null, "name":""`, Volcano/OpenRouter omit the
 * fields entirely, so BOTH null and empty string must be treated as "absent".
 * Parallel calls are separate `index` slots in one delta array.
 *
 * Pure JVM — unit tests drive [feed]/[complete] directly and via
 * MockWebServer scripts on the adapter.
 */
internal class ToolCallAccumulator {

    private class Slot {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
        var announced = false
    }

    private val byIndex = sortedMapOf<Int, Slot>()

    /**
     * Feed one delta's `tool_calls` array; returns the stream events to emit in
     * order — [LlmStreamEvent.ToolCallStarted] when a slot's id+name first
     * become visible (inserted before that slot's argument fragments, which can
     * share the same delta), then [LlmStreamEvent.ToolCallArguments] for each
     * non-empty fragment.
     */
    fun feed(deltaTools: List<JsonElement>): List<LlmStreamEvent> {
        val out = ArrayList<LlmStreamEvent>()
        for (tc in deltaTools) {
            val obj = tc as? JsonObject ?: continue
            val idx = obj["index"]?.jsonPrimitive?.intOrNull ?: 0
            val slot = byIndex.getOrPut(idx) { Slot() }
            obj.stringAt("id")?.let { slot.id = it }
            (obj["function"] as? JsonObject)?.let { fn ->
                fn.stringAt("name")?.let { slot.name = it }
                fn.stringAt("arguments")?.let { frag ->
                    if (frag.isNotEmpty()) {
                        slot.args.append(frag)
                        out += LlmStreamEvent.ToolCallArguments(idx, frag)
                    }
                }
            }
            if (!slot.announced && slot.id.isNotEmpty() && slot.name.isNotEmpty()) {
                slot.announced = true
                val at = out.indexOfFirst { it is LlmStreamEvent.ToolCallArguments && it.index == idx }
                out.add(if (at >= 0) at else out.size, LlmStreamEvent.ToolCallStarted(idx, slot.id, slot.name))
            }
        }
        return out
    }

    /** Fully accumulated calls, in index order; ids/names are "" if the provider never sent them. */
    fun complete(): List<ToolCallRequest> =
        byIndex.values.map { ToolCallRequest(it.id, it.name, it.args.toString()) }

    /** Primitive string field: present, not JSON-null, and non-empty — else null. */
    private fun JsonObject.stringAt(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
}
