package com.neethu.orchestrator.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * One tool execution's outcome. [isError]=true means the failure is fed back to
 * the model as the tool result (the model can apologize / change course) — a
 * tool failure NEVER fails the turn (AIRI tool-error semantics). Only LLM HTTP
 * errors take the existing TurnFailed path.
 */
data class ToolResult(val content: String, val isError: Boolean = false)

/**
 * A tool's execution seam. [argumentsJson] is the model-produced arguments
 * string (possibly invalid JSON — the registry pre-validates parseability and
 * feeds back an error result instead of invoking).
 */
fun interface ToolExecutor {
    suspend fun invoke(argumentsJson: String): ToolResult
}

/**
 * LLM-callable tool registry (docs/agent-feasibility.md §4.4): single table,
 * same-name later registration overrides (aligns with AIRI tool-resolver merge
 * semantics). The session layer reads [list] into `LlmConfig.tools` and
 * executes calls through [invoke].
 *
 * invoke never throws except cooperative cancellation ([interrupt] of the turn
 * job): unknown name / unparseable arguments / executor failure / timeout all
 * become isError results.
 *
 * Pure JVM, thread-safe (registry mutation happens on UI threads from app
 * settings/skill wiring; invoke runs on the turn job's dispatcher).
 */
class ToolRegistry(private val timeoutMs: Long = 10_000L) {

    private data class Entry(val spec: com.neethu.aiadapter.model.ToolSpec, val executor: ToolExecutor)

    private val entries = LinkedHashMap<String, Entry>()
    /** Monotonic mutation counter — the session pins its protocol on fingerprint change. */
    private val revision = AtomicInteger(0)

    /** Registers (or overrides) [spec] with [executor]. */
    @Synchronized
    fun register(spec: com.neethu.aiadapter.model.ToolSpec, executor: ToolExecutor) {
        entries[spec.name] = Entry(spec, executor)
        revision.incrementAndGet()
    }

    @Synchronized
    fun unregister(name: String) {
        if (entries.remove(name) != null) revision.incrementAndGet()
    }

    /** Current advertised specs, in registration order (stable for prompt pinning). */
    @Synchronized
    fun list(): List<com.neethu.aiadapter.model.ToolSpec> = entries.values.map { it.spec }

    @Synchronized
    fun find(name: String): com.neethu.aiadapter.model.ToolSpec? = entries[name]?.spec

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    /** (tool name, when-to-call hint) pairs for the pinned protocol's toolset block. */
    @Synchronized
    fun systemHints(): List<Pair<String, String>> =
        entries.values.mapNotNull { e -> e.spec.systemHint?.let { e.spec.name to it } }

    /** Monotonic revision of the tool set (register/unregister bumps it). */
    fun revisionValue(): Int = revision.get()

    /**
     * Executes one tool call. Order of guards: unknown name → arguments JSON
     * unparseable/non-object → cooperative cancel check → timeout → executor.
     */
    suspend fun invoke(name: String, argumentsJson: String): ToolResult {
        val entry = lookup(name)
            ?: return ToolResult("unknown tool: $name (available: ${names()})", isError = true)
        val args: JsonObject = try {
            lenientJson.parseToJsonElement(argumentsJson.ifBlank { "{}" }) as? JsonObject
                ?: return ToolResult("invalid arguments for $name: expected a JSON object", isError = true)
        } catch (e: Exception) {
            return ToolResult("invalid arguments JSON for $name: ${e.message}", isError = true)
        }
        return try {
            withTimeout(timeoutMs) {
                // Cooperative cancellation point: interrupt() cancels the turn
                // job and lands here before the executor starts (or between
                // suspensions inside it).
                currentCoroutineContext().ensureActive()
                entry.executor.invoke(args.toString())
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult("tool '$name' timed out after ${timeoutMs}ms", isError = true)
        } catch (e: CancellationException) {
            throw e // real interruption (user barge-in / supersede) — never swallowed
        } catch (e: Exception) {
            ToolResult("tool '$name' failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    @Synchronized private fun lookup(name: String): Entry? = entries[name]

    @Synchronized private fun names(): List<String> = entries.keys.toList()

    private companion object {
        /** Arguments tolerate loose JSON from weaker models (trailing commas etc.). */
        val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
