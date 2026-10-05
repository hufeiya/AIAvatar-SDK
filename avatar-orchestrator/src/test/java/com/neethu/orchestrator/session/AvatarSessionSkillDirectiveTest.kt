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
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.skill.RpsSkill
import com.neethu.orchestrator.skill.SkillHost
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
 * 技能指令注入（docs/rps-skill-feasibility.md §4.3）：激活技能的指令行挂在
 * 末尾 user 消息前缀、视角行之后；不进 system（恒一条）、不进历史（store 只存
 * 干净文字）；回合结果驱动技能状态机（INVITED→ARMED 等）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionSkillDirectiveTest {

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
            return flowOf(LlmStreamEvent.TextDelta("好呀,三二一一起来!"))
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

    /** 记录能力调用、可回连 session 的 host（对齐 app 侧 DemoSkillHost 的形态）。 */
    private class FakeHost : SkillHost {
        lateinit var sessionProvider: () -> AvatarSession?
        val played = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, List<String>>>()
        var frame: String? = "data:image/jpeg;base64,FAKE"
        override val defaultVadHangoverMs: Long = 800L
        override fun playGestureFile(path: String, loop: Boolean): Boolean {
            played += path
            return true
        }
        override fun speak(text: String) = Unit
        override fun sendTurn(text: String, images: List<String>) {
            sent += text to images
            sessionProvider()?.send(text, images)
        }
        override fun snapshotImage(): String? = frame
        override fun setVadHangover(ms: Long) = Unit
        override fun nowMs() = 0L
        override fun randomInt(bound: Int) = 0
    }

    private fun newSession(llm: RecordingLlm, host: SkillHost?): Pair<AvatarSession, InMemoryConversationStore> {
        val store = InMemoryConversationStore()
        val session = AvatarSession(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = SilentTts(),
            controller = AvatarController(),
            AvatarSession.Options(protocolInstructions = false),
            playbackQueue = RecordingQueue(),
            lipSyncProcessor = null,
            store = store,
            skillHost = host,
        )
        session.llmConfig = LlmConfig("http://t", "k", model = "llm")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        return session to store
    }

    private val rpsAssets = mapOf(
        RpsSkill.Hand.ROCK to "animations/gesture_rock.vrma",
        RpsSkill.Hand.SCISSORS to "animations/gesture_scissor.vrma",
        RpsSkill.Hand.PAPER to "animations/gesture_paper.vrma",
    )

    @Test
    fun `no skill registered - request unchanged`() = runTest {
        val llm = RecordingLlm()
        val (session, _) = newSession(llm, host = null)
        session.sendAndAwait("你好")
        val last = llm.requests.last().last()
        assertEquals(ChatRole.USER, last.role)
        assertTrue(last.content.endsWith("你好"))
        assertFalse(last.content.contains("【技能"))
    }

    @Test
    fun `skill directive rides the trailing user message after the view line`() = runTest {
        val llm = RecordingLlm()
        val host = FakeHost()
        val (session, store) = newSession(llm, host)
        host.sessionProvider = { session }
        val rps = RpsSkill(rpsAssets)
        session.skills.register(rps)

        // IDLE：激活词命中 → INVITED，不消费；本句照常作为激活回合发送
        assertFalse(session.skills.onUtterance("我们来玩猜拳吧"))
        session.sendAndAwait("我们来玩猜拳吧")

        val req = llm.requests.last()
        // system 至多一条（协议关闭时为 0 条）、绝不含技能指令（技能指令是逐轮变化的）
        val systems = req.filter { it.role == ChatRole.SYSTEM }
        assertTrue(systems.size <= 1)
        assertTrue(systems.none { it.content.contains("【技能") })
        // 末尾 user：视角行 → 技能指令 → 用户原文
        val last = req.last()
        assertEquals(ChatRole.USER, last.role)
        val content = last.content
        assertTrue(content.startsWith("【当前镜头视角】"))
        assertTrue(content.indexOf("【技能:猜拳】") > content.indexOf("【当前镜头视角】"))
        assertTrue(content.endsWith("我们来玩猜拳吧"))
        // store 只存干净文字（指令不进历史）
        assertEquals("我们来玩猜拳吧", store.messages().last { it.role == ChatRole.USER }.content)

        // 激活回合完成 → INVITED→ARMED（session 回合钩子驱动）
        assertEquals(RpsSkill.State.ARMED, rps.state)
    }

    @Test
    fun `throw and judge turn flow through the session with the frame image`() = runTest {
        val llm = RecordingLlm()
        val host = FakeHost()
        val (session, _) = newSession(llm, host)
        host.sessionProvider = { session }
        val rps = RpsSkill(rpsAssets) { 0 } // rng=0 → 恒出 ROCK
        session.skills.register(rps)

        session.skills.onUtterance("玩猜拳")
        session.sendAndAwait("玩猜拳") // 激活回合 → ARMED
        val requestsAfterActivation = llm.requests.size

        // VAD 快路径：本地出拳（不产生请求）
        session.skills.onVadUtterance(1_000L)
        assertEquals(RpsSkill.State.THROWN, rps.state)
        assertEquals(requestsAfterActivation, llm.requests.size)
        assertEquals(listOf("animations/gesture_rock.vrma"), host.played)

        // ASR 文本到达 → 技能消费并发起裁判回合（带抓拍图）。测试调度器下裁判
        // 回合同步跑完（真机上这中间是数秒的 JUDGING 态，纯状态机测试已锁），
        // onUtterance 返回时回合可能已结束——这里锁请求形态与最终状态。
        val requestsBeforeJudge = llm.requests.size
        assertTrue(session.skills.onUtterance("三二一"))
        assertEquals(requestsBeforeJudge + 1, llm.requests.size)
        val judgeReq = llm.requests.last()
        val judgeUser = judgeReq.last()
        assertEquals("三二一", judgeUser.content.substringAfterLast("\n\n"))
        assertEquals(listOf("data:image/jpeg;base64,FAKE"), judgeUser.images)
        assertTrue(judgeUser.content.contains("【技能:猜拳·第1局判定】"))
        assertTrue(judgeUser.content.contains("石头")) // rng=0 → ROCK
        // 裁判回合完成 → ARMED（下一局可出拳）
        assertEquals(RpsSkill.State.ARMED, rps.state)
    }
}
