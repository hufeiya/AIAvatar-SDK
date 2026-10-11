package com.neethu.orchestrator.agent

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.corelib.CameraShot
import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.promptTextsOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * P0 内置工具（docs/agent-feasibility.md §4.4）：get_device_time 的墙钟格式、
 * get_current_view 的机位回退、observe_camera 的视觉门控与独立描述请求
 * （方案 A：tool result 恒文本）。
 */
class AgentToolCatalogTest {

    private val texts = promptTextsOf(Lang.ZH)

    // ── get_device_time ─────────────────────────────────────────────────────

    @Test
    fun `device time format zh full-hour offset`() {
        // 2026-10-11 21:34 周日 UTC+8
        val epoch = java.time.LocalDateTime.of(2026, 10, 11, 21, 34)
            .atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val (spec, executor) = AgentToolCatalog.getDeviceTime(
            texts, nowMs = { epoch }, zone = ZoneId.of("Asia/Shanghai"),
        )
        assertTrue(spec.name == "get_device_time")
        assertTrue(spec.parametersSchema.toString().contains("\"type\":\"object\""))
        val result = kotlinx.coroutines.runBlocking { executor.invoke("{}") }
        assertFalse(result.isError)
        assertEquals("2026-10-11 21:34 周日 (UTC+8)", result.content)
    }

    @Test
    fun `device time english locale and half-hour offset`() {
        val epoch = java.time.LocalDateTime.of(2026, 10, 11, 21, 34)
            .atZone(ZoneId.of("Asia/Kolkata")).toInstant().toEpochMilli()
        val (_, executor) = AgentToolCatalog.getDeviceTime(
            promptTextsOf(Lang.EN), nowMs = { epoch }, zone = ZoneId.of("Asia/Kolkata"),
        )
        val result = kotlinx.coroutines.runBlocking { executor.invoke("{}") }
        assertEquals("2026-10-11 21:34 Sun (UTC+5:30)", result.content)
    }

    // ── get_current_view ────────────────────────────────────────────────────

    @Test
    fun `current view reports shot or free view fallback`() = runTest {
        val (spec, executor) = AgentToolCatalog.getCurrentView(texts) { CameraShot.CLOSE_UP }
        assertTrue(spec.name == "get_current_view")
        assertEquals("面部特写 CU (CLOSE_UP)", executor.invoke("{}").content)

        val (_, freeExecutor) = AgentToolCatalog.getCurrentView(texts) { null }
        assertEquals(texts.toolGetCurrentViewFree, freeExecutor.invoke("{}").content)
    }

    // ── observe_camera ──────────────────────────────────────────────────────

    /** Records the describe request and replays a canned text reply. */
    private class FakeVisionLlm : LlmAdapter {
        val requests = mutableListOf<Pair<List<ChatMessage>, LlmConfig>>()
        var reply = "用户举着一个红色的杯子"
        var failWith: Throwable? = null

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages to config
            failWith?.let { return flowOf(LlmStreamEvent.Error(it)) }
            return flowOf(
                LlmStreamEvent.TextDelta(reply),
                LlmStreamEvent.Finish("stop"),
            )
        }
    }

    /** 记录 lens 偏好并返回 canned 帧的取帧缝。 */
    private class SnapshotSeam(var frame: String?) {
        val lensHints = mutableListOf<String?>()
        suspend fun take(lens: String?): String? {
            lensHints += lens
            return frame
        }
    }

    @Test
    fun `observe without vision config feeds back error text`() = runTest {
        val llm = FakeVisionLlm()
        val seam = SnapshotSeam("data:image/jpeg;base64,QUJD")
        val (_, executor) = AgentToolCatalog.observeCamera(
            llm, visionConfig = { null }, snapshot = seam::take, texts = texts,
        )
        val result = executor.invoke("""{"description":"看看"}""")
        assertTrue(result.isError)
        assertEquals(texts.toolObserveNoVision, result.content)
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `observe without camera frame feeds back error text`() = runTest {
        val llm = FakeVisionLlm()
        val cfg = LlmConfig("http://x", "key", "vision-model")
        val seam = SnapshotSeam(null)
        val (_, executor) = AgentToolCatalog.observeCamera(
            llm, visionConfig = { cfg }, snapshot = seam::take, texts = texts,
        )
        val result = executor.invoke("{}")
        assertTrue(result.isError)
        assertEquals(texts.toolObserveNoFrame, result.content)
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun `observe sends independent describe request with the frame and returns text`() = runTest {
        val llm = FakeVisionLlm()
        val cfg = LlmConfig("http://x", "key", "vision-model")
        val seam = SnapshotSeam("data:image/jpeg;base64,QUJD")
        val (_, executor) = AgentToolCatalog.observeCamera(llm, visionConfig = { cfg }, snapshot = seam::take, texts = texts)
        val result = executor.invoke("""{"description":"桌面上有什么","question":"杯子","lens":"back"}""")
        assertFalse(result.isError)
        assertEquals("用户举着一个红色的杯子", result.content)
        assertEquals(listOf("back"), seam.lensHints)
        // 独立描述请求：system(描述器) + user(问题+图)，不带 tools
        assertEquals(1, llm.requests.size)
        val (messages, usedCfg) = llm.requests[0]
        assertEquals(2, messages.size)
        assertEquals(ChatRole.SYSTEM, messages[0].role)
        assertTrue(messages[0].content.contains("截图描述器"))
        assertEquals(ChatRole.USER, messages[1].role)
        assertTrue(messages[1].content.contains("重点关注：杯子"))
        assertEquals(listOf("data:image/jpeg;base64,QUJD"), messages[1].images)
        assertTrue(usedCfg.tools.isEmpty())
    }

    @Test
    fun `observe without lens passes null hint`() = runTest {
        val llm = FakeVisionLlm()
        val cfg = LlmConfig("http://x", "key", "vision-model")
        val seam = SnapshotSeam("data:image/jpeg;base64,QUJD")
        val (_, executor) = AgentToolCatalog.observeCamera(
            llm, visionConfig = { cfg }, snapshot = seam::take, texts = texts,
        )
        executor.invoke("{}")
        assertEquals(listOf<String?>(null), seam.lensHints)
    }

    @Test
    fun `observe vision failure feeds back error with detail`() = runTest {
        val llm = FakeVisionLlm().apply { failWith = IllegalStateException("boom") }
        val cfg = LlmConfig("http://x", "key", "vision-model")
        val seam = SnapshotSeam("data:image/jpeg;base64,QUJD")
        val (_, executor) = AgentToolCatalog.observeCamera(
            llm, visionConfig = { cfg }, snapshot = seam::take, texts = texts,
        )
        val result = executor.invoke("{}")
        assertTrue(result.isError)
        assertTrue(result.content.contains("boom"))
    }
}
