package com.neethu.orchestrator.agent

import com.neethu.aiadapter.model.ToolSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 工具注册表（docs/agent-feasibility.md §4.4）：注册/覆盖/未知工具/坏参数
 * JSON/超时/执行异常全部转 isError 结果（工具失败永不炸回合），只有协作取消
 * （打断）原样传播。
 */
class ToolRegistryTest {

    private fun spec(name: String, hint: String? = null) =
        ToolSpec(name, "desc of $name", buildJsonObject { put("type", "object") }, hint)

    @Test
    fun `register list unregister and same-name override`() {
        val registry = ToolRegistry()
        registry.register(spec("a"), ToolExecutor { ToolResult("A") })
        registry.register(spec("b", hint = "use b"), ToolExecutor { ToolResult("B") })
        assertEquals(listOf("a", "b"), registry.list().map { it.name })

        // 同名覆盖（airi tool-resolver 合并语义）
        registry.register(spec("a"), ToolExecutor { ToolResult("A2") })
        assertEquals(2, registry.list().size)

        assertEquals(listOf("b" to "use b"), registry.systemHints())

        registry.unregister("a")
        assertEquals(listOf("b"), registry.list().map { it.name })
        assertNull(registry.find("a"))
    }

    @Test
    fun `revision bumps on mutation only`() {
        val registry = ToolRegistry()
        val r0 = registry.revisionValue()
        registry.register(spec("a"), ToolExecutor { ToolResult("A") })
        val r1 = registry.revisionValue()
        registry.register(spec("a"), ToolExecutor { ToolResult("A2") })
        val r2 = registry.revisionValue()
        registry.unregister("missing")
        assertEquals(r2, registry.revisionValue())
        registry.unregister("a")
        assertTrue(r2 < registry.revisionValue())
        assertFalse(r0 == r1 && r1 == r2)
    }

    @Test
    fun `unknown tool returns error result never throws`() = runTest {
        val registry = ToolRegistry()
        val result = registry.invoke("nope", "{}")
        assertTrue(result.isError)
        assertTrue(result.content.contains("unknown tool"))
    }

    @Test
    fun `unparseable arguments json feeds back error instead of invoking`() = runTest {
        var invoked = 0
        val registry = ToolRegistry()
        registry.register(spec("t")) { invoked++; ToolResult("ok") }
        val bad = registry.invoke("t", "{not json")
        assertTrue(bad.isError)
        assertEquals(0, invoked)
        // 空串按空对象放行
        val empty = registry.invoke("t", "")
        assertFalse(empty.isError)
        assertEquals(1, invoked)
    }

    @Test
    fun `executor failure becomes error result`() = runTest {
        val registry = ToolRegistry()
        registry.register(spec("boom")) { error("device exploded") }
        val result = registry.invoke("boom", "{}")
        assertTrue(result.isError)
        assertTrue(result.content.contains("device exploded"))
    }

    @Test
    fun `timeout converts to error result`() = runTest {
        val registry = ToolRegistry(timeoutMs = 50)
        registry.register(spec("slow")) {
            delay(10_000)
            ToolResult("never")
        }
        val result = withTimeout(5_000) { registry.invoke("slow", "{}") }
        assertTrue(result.isError)
        assertTrue(result.content.contains("timed out"))
    }

    @Test
    fun `cancellation propagates - interrupt during tool execution is not swallowed`() = runTest {
        val registry = ToolRegistry(timeoutMs = 60_000)
        var cancelled = false
        registry.register(spec("hang")) {
            try {
                delay(60_000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            ToolResult("never")
        }
        val job = launch { registry.invoke("hang", "{}") }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
    }

    @Test
    fun `executor receives serialized arguments object`() = runTest {
        var received = ""
        val registry = ToolRegistry()
        registry.register(spec("echo")) { args -> received = args; ToolResult("ok") }
        registry.invoke("echo", """{"tz":"UTC+8"}""")
        assertTrue(received.contains("UTC+8"))
    }
}
