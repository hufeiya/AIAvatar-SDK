package com.neethu.aiavatar_sdk

import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.corelib.AvatarController
import com.neethu.corelib.Lang
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.i18n.promptTextsOf
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.5 设备工具的注册接线（docs/agent-tools-expansion.md §3.1）：AgentDeviceTools
 * 对注册表的可观测行为——总开关关闭全退场（连 P0 内置两件也摘）、总开关重开
 * 补回、剪贴板独立开关单独挂/摘、重复调用幂等（revision 不空转）。执行路径
 * （手电/震动/拍照等 Android API）不在 JVM 单测面内，由真机 tools_call 验收。
 *
 * context/lifecycle 传 null：注册路径从不触碰它们（全部延迟到 executor），
 * 这正是接线可测性的设计前提。
 */
class AgentDeviceToolsWiringTest {

    /** 永不被调用的 TTS 桩（会话只建不发）。 */
    private val deadTts = object : TtsAdapter {
        override suspend fun synthesize(text: String, config: TtsConfig) = error("not used")
    }

    /** 永不播队列桩（默认 AudioTrackPlaybackQueue 在 JVM 上是桩类）。 */
    private class SilentQueue : PlaybackQueue {
        override var listener: PlaybackQueue.Listener? = null
        override fun active(): ActivePlayback? = null
        override fun enqueue(item: PlaybackItem) = Unit
        override fun stopAll(reason: String) = Unit
        override fun release() = Unit
    }

    private fun newSession(): AvatarSession = AvatarSession(
        scope = CoroutineScope(Dispatchers.Unconfined),
        llm = null,
        tts = deadTts,
        controller = AvatarController(),
        playbackQueue = SilentQueue(),
        lipSyncProcessor = null,
        store = InMemoryConversationStore(),
    )

    private fun register(
        session: AvatarSession,
        prefs: AiChatPrefs,
    ): Int {
        val before = session.tools.revisionValue()
        AgentDeviceTools.registerAll(
            session = session,
            controller = AvatarController(),
            context = null,
            texts = promptTextsOf(Lang.ZH),
            prefs = prefs,
            lifecycle = null,
            currentRenderSettings = { com.neethu.corelib.AvatarRenderSettings() },
            onRenderSettingsChange = { },
        )
        return session.tools.revisionValue() - before
    }

    @Test
    fun `registerAll with defaults adds five device tools plus clipboard off`() {
        val session = newSession()
        register(session, AiChatPrefs())
        val names = session.tools.list().map { it.name }
        // 会话 init 注册的内置两件 + 新五件；剪贴板默认关
        assertTrue(names.containsAll(listOf("get_device_time", "get_current_view")))
        assertTrue(names.containsAll(listOf("get_device_status", "set_flashlight", "send_haptic", "set_ambience", "take_photo")))
        assertFalse("剪贴板默认关（唯一读用户数据的工具）", names.contains("read_clipboard"))
        assertFalse("observe_camera 归另一个 effect 管", names.contains("observe_camera"))
    }

    @Test
    fun `clipboard switch registers and unregisters independently`() {
        val session = newSession()
        register(session, AiChatPrefs(readClipboard = true))
        assertTrue(session.tools.list().map { it.name }.contains("read_clipboard"))
        // 关掉：只摘剪贴板，其余不动（revision 只 bump 一次）
        val before = session.tools.list().map { it.name }
        val bumped = register(session, AiChatPrefs(readClipboard = false))
        assertEquals(1, bumped)
        val after = session.tools.list().map { it.name }
        assertFalse(after.contains("read_clipboard"))
        assertEquals(before - "read_clipboard", after)
    }

    @Test
    fun `master switch off removes device tools and builtins`() {
        val session = newSession()
        register(session, AiChatPrefs(readClipboard = true))
        assertTrue(session.tools.list().size >= 8)
        // 总开关关：设备六件 + 内置两件全退场，注册表清空（请求不带 tools 键）
        register(session, AiChatPrefs(agentTools = false, readClipboard = true))
        assertTrue(session.tools.isEmpty())
        // 重开：内置两件与设备五件补回，剪贴板按开关保持摘除
        register(session, AiChatPrefs(readClipboard = false))
        val names = session.tools.list().map { it.name }
        assertTrue(names.containsAll(listOf("get_device_time", "get_current_view", "set_flashlight", "take_photo")))
        assertFalse(names.contains("read_clipboard"))
    }

    @Test
    fun `registerAll is idempotent and does not bump revision`() {
        val session = newSession()
        register(session, AiChatPrefs(readClipboard = true))
        val namesBefore = session.tools.list().map { it.name }
        val revisionBefore = session.tools.revisionValue()
        register(session, AiChatPrefs(readClipboard = true))
        assertEquals(namesBefore, session.tools.list().map { it.name })
        assertEquals("重复注册不得推高 revision（协议块无谓重钉）", revisionBefore, session.tools.revisionValue())
    }
}
