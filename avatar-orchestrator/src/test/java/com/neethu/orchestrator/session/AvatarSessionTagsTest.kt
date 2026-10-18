package com.neethu.orchestrator.session

import com.neethu.aiadapter.api.EmotionCue
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
import com.neethu.corelib.CameraShot
import com.neethu.orchestrator.audio.ActivePlayback
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.gesture.ActionEntry
import com.neethu.orchestrator.gesture.GestureDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
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
 * Multimodal inline-tag dispatch (docs/ai-layer-handoff.md §7): `<cam:…>`,
 * `<act:…>`, `<emo:…>` cues are extracted from the LLM stream (and from
 * speak() greetings), routed to their drivers, stripped from speech, and
 * reflected as [AvatarEvent]s. Unknown names are filtered out (never spoken)
 * and reported via [AvatarEvent.UnknownTag]; 情绪词表外自造名先过别名表
 * （中文/英文），动作槽里的情绪词跨类改写成表情。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvatarSessionTagsTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingQueue : PlaybackQueue {
        override var listener: PlaybackQueue.Listener? = null
        val played = mutableListOf<PlaybackItem>()

        override fun active(): ActivePlayback? = null

        override fun enqueue(item: PlaybackItem) {
            played += item
            listener?.onPlaybackStarted(item)
            listener?.onPlaybackEnded(item)
        }

        override fun stopAll(reason: String) = Unit

        override fun release() = Unit
    }

    /** 入队后不自动播放的队列——验证"表情等句子开播才应用"用。 */
    private class ManualQueue : PlaybackQueue {
        override var listener: PlaybackQueue.Listener? = null
        val queued = mutableListOf<PlaybackItem>()

        override fun active(): ActivePlayback? = null

        override fun enqueue(item: PlaybackItem) {
            queued += item
        }

        fun playFirst() {
            val item = queued.removeAt(0)
            listener?.onPlaybackStarted(item)
            listener?.onPlaybackEnded(item)
        }

        override fun stopAll(reason: String) = Unit

        override fun release() = Unit
    }

    /** LLM whose stream is a SharedFlow the test drives with tryEmit. */
    private class SharedFlowLlm : LlmAdapter {
        val stream = MutableSharedFlow<LlmStreamEvent>(extraBufferCapacity = 64)
        val requests = mutableListOf<List<ChatMessage>>()

        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            requests += messages
            // 真实 adapter 在 Finish 后完成 flow；fake 也必须如此——send() 的
            // 收尾段（flushTrailingEmotion 等）全在 collect 之后，流不完成就
            // 永远走不到（trailing 表情测试踩过）。
            return stream.transformWhile { event ->
                emit(event)
                event !is LlmStreamEvent.Finish
            }
        }
    }

    private class FakeTts : TtsAdapter {
        val synthesized = mutableListOf<String>()
        override suspend fun synthesize(text: String, config: TtsConfig): TtsResult {
            synthesized += text
            return TtsResult(ByteArray(64), TtsAudioFormat.RAW_PCM_16LE, 16_000)
        }
    }

    private class FakeGestureDriver(private val accepted: Set<String> = setOf("wave")) :
        GestureDriver(AvatarController()) {
        val played = mutableListOf<String>()
        val idlesSet = mutableListOf<String>()
        var stopCalls = 0
        var clearIdleCalls = 0
        override fun play(tag: String): Boolean {
            if (tag !in accepted) return false
            played += tag
            return true
        }

        override fun setIdle(entry: ActionEntry): Boolean {
            idlesSet += entry.tag
            return true
        }

        override fun clearIdle() {
            clearIdleCalls++
        }

        override fun stop() {
            stopCalls++
        }
    }

    private fun newSession(
        llm: SharedFlowLlm,
        tts: FakeTts,
        queue: PlaybackQueue,
        gestures: FakeGestureDriver,
        options: AvatarSession.Options = AvatarSession.Options(),
    ): AvatarSession =
        AvatarSession(
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            llm = llm,
            tts = tts,
            controller = AvatarController(),
            options = options,
            playbackQueue = queue,
            lipSyncProcessor = null,
            gestureDriver = gestures,
            // decode/lip-sync analysis on the same unconfined dispatcher keeps
            // the pre-Default synchronous sequencing these assertions rely on
            heavyWorkDispatcher = UnconfinedTestDispatcher(),
        )

    private fun kotlinx.coroutines.test.TestScope.collectInto(
        session: AvatarSession,
        events: MutableList<AvatarEvent>,
    ) {
        backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            session.events.collect { events += it }
        }
    }

    @Test
    fun `send dispatches camera emotion action cues and strips tags from speech`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val queue = RecordingQueue()
        val gestures = FakeGestureDriver()
        val session = newSession(llm, tts, queue, gestures)
        session.actionCatalog = listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma"))
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<cam:medium_shot><emo:happy:0.8>你好"))
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:wave>呀。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(listOf("wave"), gestures.played)
        // camera/action cue 即发即执行；emotion cue 挂到句子、开播瞬间才应用
        // （§7.4：TTS 排队延迟下即发即执行会让表情在出声前被自动归零吃掉）。
        // RecordingQueue 同步入队即播，所以 EmotionChanged 出现在其句子提交时。
        val tagEvents = events.filter {
            it is AvatarEvent.CameraChanged || it is AvatarEvent.EmotionChanged || it is AvatarEvent.ActionStarted
        }
        assertEquals(
            listOf(
                AvatarEvent.CameraChanged(CameraShot.MEDIUM_SHOT),
                AvatarEvent.ActionStarted("wave", "挥手问候"),
                AvatarEvent.EmotionChanged(EmotionCue("happy", 0.8f)),
            ),
            tagEvents,
        )
        // 标签不进语音，文字完整送 TTS
        assertEquals(listOf("你好呀。"), tts.synthesized)
        // 协议块出现在系统提示词，动作按分类分组列出
        val system = llm.requests[0].first { it.role == ChatRole.SYSTEM }
        assertTrue(system.content.contains("<act:动作>"))
        assertTrue(system.content.contains("基础动作: wave"))
        assertTrue(system.content.contains("<cam:机位>"))
    }

    @Test
    fun `unknown action and camera names are filtered and reported`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val gestures = FakeGestureDriver()
        val session = newSession(llm, tts, RecordingQueue(), gestures)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:backflip><cam:drone_shot>好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(0, gestures.played.size)
        assertEquals(0, events.filterIsInstance<AvatarEvent.ActionStarted>().size)
        assertEquals(0, events.filterIsInstance<AvatarEvent.CameraChanged>().size)
        // 未识别标签绝不进语音；带原文上报 UnknownTag 供面板/日志定位
        assertEquals(listOf("好。"), tts.synthesized)
        val unknown = events.filterIsInstance<AvatarEvent.UnknownTag>()
        assertEquals(listOf("act", "cam"), unknown.map { it.kind })
        assertEquals(listOf("<act:backflip>", "<cam:drone_shot>"), unknown.map { it.raw })
    }

    @Test
    fun `camera cue respects enableLlmCamera=false and omits camera section from prompt`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val session = newSession(
            llm, tts, RecordingQueue(), FakeGestureDriver(),
            options = AvatarSession.Options(enableLlmCamera = false),
        )
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<cam:close_up>好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(0, events.filterIsInstance<AvatarEvent.CameraChanged>().size)
        val system = llm.requests[0].first { it.role == ChatRole.SYSTEM }
        // 断言用协议块的镜头段专属串——身份前言的"协议遵循"提醒合法地包含
        // 标签家族写法 <cam:（那是提醒文本，不是镜头段）
        assertFalse(system.content.contains("<cam:机位>"))
        assertTrue(system.content.contains("<emo:名字:强度>"))
    }

    @Test
    fun `speak interprets tags in greetings`() = runTest {
        val tts = FakeTts()
        val session = newSession(SharedFlowLlm(), tts, RecordingQueue(), FakeGestureDriver())
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.speak("<emo:happy>晚上好！")
        testScheduler.runCurrent()

        assertEquals(1, events.filterIsInstance<AvatarEvent.EmotionChanged>().size)
        assertEquals(listOf("晚上好！"), tts.synthesized)
    }

    @Test
    fun `interrupt stops the gesture started by the turn`() = runTest {
        val llm = SharedFlowLlm()
        val gestures = FakeGestureDriver()
        val session = newSession(llm, FakeTts(), RecordingQueue(), gestures)
        session.actionCatalog = listOf(ActionEntry("wave", "Wave", assetPath = "wave.vrma"))
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:wave>嗨"))
        testScheduler.runCurrent()
        assertEquals(listOf("wave"), gestures.played)

        session.interrupt()
        assertEquals(1, gestures.stopCalls)
    }

    @Test
    fun `unknown emotion names are dropped while canonical ones pass`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val session = newSession(llm, tts, RecordingQueue(), FakeGestureDriver())
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        // unicorn 不是规范情绪、别名表查不到、模型也没这个 morph → 过滤+上报；
        // happy 是规范情绪 → 通过。标签一律不进语音。
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:unicorn:1><emo:happy:0.5>好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        val emotions = events.filterIsInstance<AvatarEvent.EmotionChanged>()
        assertEquals(listOf("happy"), emotions.map { it.cue.name })
        assertEquals(listOf("好。"), tts.synthesized)
        val unknown = events.filterIsInstance<AvatarEvent.UnknownTag>()
        assertEquals(listOf("emo"), unknown.map { it.kind })
        assertEquals(listOf("<emo:unicorn:1>"), unknown.map { it.raw })
    }

    @Test
    fun `chinese emotion aliases resolve to canonical names`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val session = newSession(llm, tts, RecordingQueue(), FakeGestureDriver())
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        // 中文模型自造的词表外名字（真机踩过 <emo:轻笑>/<emo:大笑>）：别名表
        // 回 canonical 生效；unicorn 无别名 → 过滤+上报。标签一律不进语音。
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:轻笑>你好。"))
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:大笑:0.9>哈哈。<emo:unicorn:1>"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        val emotions = events.filterIsInstance<AvatarEvent.EmotionChanged>()
        assertEquals(listOf("happy", "happy"), emotions.map { it.cue.name })
        assertEquals(0.9f, emotions[1].cue.intensity, 0.001f)
        assertEquals(listOf("你好。", "哈哈。"), tts.synthesized)
        assertEquals(listOf("<emo:unicorn:1>"), events.filterIsInstance<AvatarEvent.UnknownTag>().map { it.raw })
    }

    @Test
    fun `english emotion aliases resolve to canonical names`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val session = newSession(llm, tts, RecordingQueue(), FakeGestureDriver())
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        // 英文模型自造词（词表只有 happy 没有 laugh/excited）：英文表回
        // canonical 生效，强度保留；改写成功不算未识别，不发 UnknownTag。
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:laugh:0.6>哈哈。"))
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:excited>哇。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        val emotions = events.filterIsInstance<AvatarEvent.EmotionChanged>()
        assertEquals(listOf("happy", "happy"), emotions.map { it.cue.name })
        assertEquals(0.6f, emotions[0].cue.intensity, 0.001f)
        assertEquals(listOf("哈哈。", "哇。"), tts.synthesized)
        assertEquals(0, events.filterIsInstance<AvatarEvent.UnknownTag>().size)
    }

    @Test
    fun `act tag carrying an emotion word remaps to the emotion`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val gestures = FakeGestureDriver()
        val session = newSession(llm, tts, RecordingQueue(), gestures)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        // 真机踩过的原案 <act:laugh:0.5>：形状层容忍强度段 → 不漏语音；
        // "laugh" 不在动作目录 → 按情绪词表跨类改写成 happy（act 无强度，按 1.0）
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:laugh:0.5>哈哈，被你发现了！"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(listOf("哈哈，被你发现了！"), tts.synthesized)
        assertEquals(0, gestures.played.size)
        val emotions = events.filterIsInstance<AvatarEvent.EmotionChanged>()
        assertEquals(listOf("happy"), emotions.map { it.cue.name })
        assertEquals(1.0f, emotions[0].cue.intensity, 0.001f)
        // 改写成功 ≠ 未识别：不发 UnknownTag
        assertEquals(0, events.filterIsInstance<AvatarEvent.UnknownTag>().size)
    }

    @Test
    fun `action tag with trailing intensity plays the gesture`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val gestures = FakeGestureDriver()
        val session = newSession(llm, tts, RecordingQueue(), gestures)
        session.actionCatalog = listOf(ActionEntry("wave", "Wave", assetPath = "wave.vrma"))
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        // 目录内的动作带强度段：强度对动作无语义，丢弃后正常开播
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:wave:0.7>你好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(listOf("wave"), gestures.played)
        assertEquals(0, events.filterIsInstance<AvatarEvent.UnknownTag>().size)
        assertEquals(listOf("你好。"), tts.synthesized)
    }

    @Test
    fun `emotion tags apply when their sentence starts playing, not when streamed`() = runTest {
        val llm = SharedFlowLlm()
        val queue = ManualQueue()
        val session = newSession(llm, FakeTts(), queue, FakeGestureDriver())
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:happy:0.8>今天真开心。"))
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:sad:0.9>可是明天要上班。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        // TTS 还没播：表情一个都不发（旧语义在标签吐出瞬间就驱动面部，
        // 等句子开播时早已被 3 秒自动归零吃掉——真机"表情无变化"根因）
        assertEquals(0, events.filterIsInstance<AvatarEvent.EmotionChanged>().size)
        assertEquals(2, queue.queued.size)

        queue.playFirst()
        testScheduler.runCurrent()
        assertEquals(listOf("happy"), events.filterIsInstance<AvatarEvent.EmotionChanged>().map { it.cue.name })
        queue.playFirst()
        testScheduler.runCurrent()
        assertEquals(listOf("happy", "sad"), events.filterIsInstance<AvatarEvent.EmotionChanged>().map { it.cue.name })
    }

    @Test
    fun `trailing emotion with no following sentence applies at turn end`() = runTest {
        val llm = SharedFlowLlm()
        val queue = ManualQueue()
        val session = newSession(llm, FakeTts(), queue, FakeGestureDriver())
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        // delta1：happy 挂到"好。"（未播放，不发事件）
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:happy:0.8>好。"))
        // delta2：sad 之后没有句子了（句尾残余）→ 回合收尾时立即应用
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:sad:0.5>"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(listOf("sad"), events.filterIsInstance<AvatarEvent.EmotionChanged>().map { it.cue.name })
    }

    @Test
    fun `interrupt drops pending emotion attachments`() = runTest {
        val llm = SharedFlowLlm()
        val queue = ManualQueue()
        val session = newSession(llm, FakeTts(), queue, FakeGestureDriver())
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)

        session.send("你好")
        // 无句末标点 → 没有句子提交，cue 还在 pending
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<emo:happy:0.8>嗯"))
        session.interrupt()
        // 打断后流即使继续吐，被取消的回合也不会再应用/发射表情
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()

        assertEquals(0, events.filterIsInstance<AvatarEvent.EmotionChanged>().size)
    }

    @Test
    fun `same-name cue runs split into onsets and deferred steps`() {
        // 协议教的眨眼模式 <emo:x:1><emo:x:0>：首条 t=0 应用，其余按步进补发
        val blink = listOf(EmotionCue("blinkleft", 1f), EmotionCue("blinkleft", 0f))
        assertEquals(
            EmotionCue("blinkleft", 1f) to listOf(EmotionCue("blinkleft", 0f)),
            AvatarSession.splitEmotionRun(blink),
        )
        // 混有不同名：句尾情绪生效，无补发
        val mixed = listOf(EmotionCue("happy", 0.8f), EmotionCue("sad", 0.5f))
        assertEquals(EmotionCue("sad", 0.5f) to emptyList<EmotionCue>(), AvatarSession.splitEmotionRun(mixed))
        // 单条：原样 t=0
        val single = listOf(EmotionCue("think", 0.6f))
        assertEquals(EmotionCue("think", 0.6f) to emptyList<EmotionCue>(), AvatarSession.splitEmotionRun(single))
    }

    @Test
    fun `idleAction routes to the gesture driver`() = runTest {
        val gestures = FakeGestureDriver()
        val session = newSession(SharedFlowLlm(), FakeTts(), RecordingQueue(), gestures)

        session.idleAction = ActionEntry("idle_stand", "Idle Stand Looking Around", assetPath = "animations/i.vrma")
        assertEquals(listOf("idle_stand"), gestures.idlesSet)
        session.idleAction = null
        assertEquals(1, gestures.clearIdleCalls)
    }

    @Test
    fun `gesture driver rejects unknown tags without touching the controller`() {
        val driver = GestureDriver(AvatarController())
        driver.setCatalog(listOf(ActionEntry("wave", "挥手问候", assetPath = "animations/wave.vrma")))
        assertFalse(driver.play("backflip"))
        assertEquals(null, driver.currentTag)
    }
    @Test
    fun `real but unadvertised asset never reaches gesture driver`() = runTest {
        val llm = SharedFlowLlm()
        val tts = FakeTts()
        val gestures = FakeGestureDriver(setOf("dance_50"))
        val session = newSession(llm, tts, RecordingQueue(), gestures)
        session.actionCatalog = (0..60).map { ActionEntry("dance_$it", "Dance $it", assetPath = "$it.vrma") }
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)
        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:dance_50>（低头）你好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()
        assertTrue(gestures.played.isEmpty())
        assertEquals(listOf("你好。"), tts.synthesized)
        assertEquals(com.neethu.orchestrator.tag.TagCapabilityRegistry.Reason.NOT_ALLOWED_THIS_TURN,
            events.filterIsInstance<AvatarEvent.TagRejected>().single().reason)
        assertTrue(events.filterIsInstance<AvatarEvent.UnknownTag>().isEmpty())
        session.send("dance_50")
        assertTrue(llm.requests.last().first().content.contains("dance_50"))
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:dance_50>好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()
        assertEquals(listOf("dance_50"), gestures.played)
        session.send("再做一次")
        assertTrue(llm.requests.last().first().content.contains("dance_50"))
        session.interrupt("test finished")
    }

    @Test
    fun `aliases execute exact asset and consecutive actions cannot preempt it`() = runTest {
        val llm = SharedFlowLlm()
        val gestures = FakeGestureDriver(setOf("aj_waving_gesture", "nodding_head_yes"))
        val session = newSession(llm, FakeTts(), RecordingQueue(), gestures)
        session.actionCatalog = listOf(
            ActionEntry("aj_waving_gesture", "Wave", assetPath = "wave.vrma"),
            ActionEntry("nodding_head_yes", "Nod", assetPath = "nod.vrma"),
        )
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)
        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<cam:closeup><act:aj_waving_gesture>你好"))
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:nod>呀。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()
        assertEquals(listOf("aj_waving_gesture"), gestures.played)
        assertEquals(2, events.filterIsInstance<AvatarEvent.TagAliasResolved>().size)
        assertEquals("wave", events.filterIsInstance<AvatarEvent.ActionStarted>().single().tag)
        assertEquals(com.neethu.orchestrator.tag.TagCapabilityRegistry.Reason.BUSY,
            events.filterIsInstance<AvatarEvent.TagRejected>().single().reason)
    }

    @Test
    fun `asset load failure is distinct from unknown name and catalog changes revoke execution`() = runTest {
        val llm = SharedFlowLlm()
        val session = newSession(llm, FakeTts(), RecordingQueue(), FakeGestureDriver(emptySet()))
        session.actionCatalog = listOf(ActionEntry("wave", "Wave", assetPath = "missing.vrma"))
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)
        session.send("你好")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:wave>你好"))
        session.actionCatalog = emptyList()
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:wave>呀。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()
        assertEquals(listOf(
            com.neethu.orchestrator.tag.TagCapabilityRegistry.Reason.RESOURCE_LOAD_FAILED,
            com.neethu.orchestrator.tag.TagCapabilityRegistry.Reason.UNKNOWN_NAME,
        ), events.filterIsInstance<AvatarEvent.TagRejected>().map { it.reason })
        assertEquals(1, events.filterIsInstance<AvatarEvent.UnknownTag>().size)
    }

    @Test
    fun `assets added during a stream wait until the next advertised request`() = runTest {
        val llm = SharedFlowLlm()
        val gestures = FakeGestureDriver(setOf("wave", "nod"))
        val session = newSession(llm, FakeTts(), RecordingQueue(), gestures)
        val wave = ActionEntry("wave", "Wave", assetPath = "wave.vrma")
        val nod = ActionEntry("nod", "Nod", assetPath = "nod.vrma")
        session.actionCatalog = listOf(wave)
        session.llmConfig = LlmConfig("http://x", "key", "m")
        session.ttsConfig = TtsConfig(model = "tts", voice = "v")
        val events = mutableListOf<AvatarEvent>()
        collectInto(session, events)
        session.send("你好")
        session.actionCatalog = listOf(wave, nod)
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:nod>你好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()
        assertTrue(gestures.played.isEmpty())
        assertEquals(com.neethu.orchestrator.tag.TagCapabilityRegistry.Reason.NOT_ALLOWED_THIS_TURN,
            events.filterIsInstance<AvatarEvent.TagRejected>().single().reason)
        session.send("点头")
        llm.stream.tryEmit(LlmStreamEvent.TextDelta("<act:nod>好。"))
        llm.stream.tryEmit(LlmStreamEvent.Finish(null))
        testScheduler.runCurrent()
        assertEquals(listOf("nod"), gestures.played)
    }

    @Test
    fun `mixed mood and blink preserve both onset and reset in their original positions`() {
        val happy = EmotionCue("happy", 0.6f)
        val on = EmotionCue("blinkLeft", 1f)
        val off = EmotionCue("blinkLeft", 0f)
        assertEquals(happy to listOf(on, off), AvatarSession.splitEmotionRun(listOf(happy, on, off)))
        assertEquals(on to listOf(off, happy), AvatarSession.splitEmotionRun(listOf(on, off, happy)))
        assertEquals(happy to listOf(on, off), AvatarSession.splitEmotionRun(listOf(EmotionCue("sad", .5f), happy, on, off)))
    }

}
