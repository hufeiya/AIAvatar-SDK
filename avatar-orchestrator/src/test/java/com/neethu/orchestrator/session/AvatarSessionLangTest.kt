package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.TtsAudioFormat
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult
import com.neethu.corelib.AvatarController
import com.neethu.corelib.Lang
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.skill.NopSkillHost
import com.neethu.orchestrator.skill.RpsSkill
import com.neethu.orchestrator.skill.SkillContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 会话级多语言不变量（多语言支持）：`Options(lang = EN)` 下发出的请求——身份
 * 前言、协议块、视角行——整条 system 与 user 前缀都不得出现中文字符（用户需求
 * 「英文模式下不要出现中文」）；默认 ZH 保持既有行为（ViewLineTest/ProtocolPinTest
 * 锁中文锚点）。猜拳技能指令随 RpsSkill.lang 切换。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionLangTest {

    private val cjk = Regex("[\\u4e00-\\u9fff]")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingLlm : LlmAdapter {
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            return flowOf(LlmStreamEvent.TextDelta("Sure."))
        }
    }

    private class SilentTts : TtsAdapter {
        override suspend fun synthesize(text: String, config: TtsConfig): TtsResult =
            TtsResult(ByteArray(64), TtsAudioFormat.RAW_PCM_16LE, 16_000)
    }

    private class RecordingQueue : PlaybackQueue {
        override var listener: PlaybackQueue.Listener? = null
        override fun active(): ActivePlayback? = null
        override fun enqueue(item: PlaybackItem) {
            listener?.onPlaybackStarted(item)
            listener?.onPlaybackEnded(item)
        }
        override fun stopAll(reason: String) = Unit
        override fun release() = Unit
    }

    private fun newSession(lang: Lang): Pair<AvatarSession, RecordingLlm> {
        val llm = RecordingLlm()
        val skill = RpsSkill(emptyMap()).also { it.lang = lang }
        val session = AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = AvatarController(),
            AvatarSession.Options(lang = lang),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = InMemoryConversationStore(),
            skillHost = NopSkillHost,
        )
        session.llmConfig = LlmConfig("http://t", "k", model = "llm")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        session.skills.register(skill)
        return session to llm
    }

    /** 与 demo 相同的发送缝：技能先看文本（激活/消费），未消费才默认发送。 */
    private suspend fun sendLikeApp(session: AvatarSession, text: String) {
        val consumed = session.skills.onUtterance(text)
        if (!consumed) session.sendAndAwait(text)
    }

    private fun List<ChatMessage>.allText(): String =
        joinToString("\n") { m -> "[${m.role}] ${m.content}" }

    @Test
    fun `en session prompts contain no chinese`() = runTest {
        val (session, llm) = newSession(Lang.EN)
        sendLikeApp(session, "hello, let's play rock paper scissors")
        val all = llm.requests.single().allText()
        val hit = cjk.findAll(all).map { it.value }.distinct().take(5).joinToString("")
        assertFalse("EN 请求含中文: $hit", cjk.containsMatchIn(all))
        assertTrue(all.contains("Identity & task"))
        assertTrue(all.contains("Current camera view"))
        assertTrue(all.contains("[Skill: Rock-Paper-Scissors]"))
    }

    @Test
    fun `zh session keeps legacy anchors`() = runTest {
        val (session, llm) = newSession(Lang.ZH)
        sendLikeApp(session, "你好，我们来猜拳吧")
        val all = llm.requests.single().allText()
        assertTrue(all.contains("【身份与任务】"))
        assertTrue(all.contains("【当前镜头视角】"))
        assertTrue(all.contains("【技能:猜拳】"))
    }

    @Test
    fun `rps skill language is switchable at runtime`() = runTest {
        val skill = RpsSkill(emptyMap())
        val ctx = SkillContext(NopSkillHost)
        skill.lang = Lang.EN
        // 英文激活词也能命中（激活词表中英合并，不随语言切换）；激活不消费
        // 该句（返回 false），邀请回合由默认发送路径照常发出
        assertFalse(skill.onUtterance("let's play rock paper scissors", ctx))
        assertEquals(RpsSkill.State.INVITED, skill.state)
        assertTrue(skill.turnDirective(ctx)!!.contains("[Skill: Rock-Paper-Scissors]"))
        skill.lang = Lang.ZH
        assertTrue(skill.turnDirective(ctx)!!.contains("【技能:猜拳"))
    }
}
