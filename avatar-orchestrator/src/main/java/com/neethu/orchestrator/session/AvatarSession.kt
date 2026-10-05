package com.neethu.orchestrator.session

import android.os.SystemClock
import android.util.Log
import com.neethu.aiadapter.api.LipSyncProcessor
import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.api.TtsAdapter
import com.neethu.aiadapter.api.EmotionCue
import com.neethu.aiadapter.api.TagCue
import com.neethu.aiadapter.api.TagExtractor
import com.neethu.aiadapter.emotion.InlineTagExtractor
import com.neethu.aiadapter.lipsync.WlipsyncLipSyncProcessor
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.aiadapter.model.LlmUsage
import com.neethu.aiadapter.model.TtsConfig
import com.neethu.corelib.AvatarController
import com.neethu.corelib.CameraShot
import com.neethu.orchestrator.audio.AudioTrackPlaybackQueue
import com.neethu.orchestrator.audio.PlaybackItem
import com.neethu.orchestrator.audio.PlaybackQueue
import com.neethu.orchestrator.card.CharacterCard
import com.neethu.orchestrator.card.SystemPromptAssembler
import com.neethu.orchestrator.chunker.SentenceChunker
import com.neethu.orchestrator.face.FaceDriver
import com.neethu.orchestrator.gesture.ActionEntry
import com.neethu.orchestrator.gesture.GestureDriver
import com.neethu.orchestrator.history.ConversationStore
import com.neethu.orchestrator.history.InMemoryConversationStore
import com.neethu.orchestrator.pipeline.SpeechPipeline
import com.neethu.orchestrator.skill.SkillHost
import com.neethu.orchestrator.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * High-level facade of the avatar conversational pipeline — the single entry
 * point integrators use. One instance drives: LLM streaming → sentence
 * chunking → concurrent TTS → ordered playback → face driving (visemes,
 * emotions, blinking) on the given [AvatarController].
 *
 * Typical wiring:
 * ```kotlin
 * val session = AvatarSession(
 *     scope = lifecycleScope,
 *     llm = OpenAiCompatibleLlmAdapter(baseUrl, apiKey),
 *     tts = OpenAiCompatibleTtsAdapter(baseUrl, apiKey),
 *     controller = avatarController,
 * )
 * session.llmConfig = LlmConfig(baseUrl, apiKey, model = "gpt-4o-mini")
 * session.ttsConfig = TtsConfig(model = "tts-1", voice = "alloy")
 * session.setCharacterCard(card)
 *
 * session.send("你好！")
 * session.interrupt()          // cut LLM + TTS + audio instantly
 * ```
 *
 * If no [AvatarController] is provided the session still works (audio-only,
 * headless) — useful for tests and voice-only deployments.
 */
class AvatarSession(
    private val scope: CoroutineScope,
    private val llm: LlmAdapter,
    private val tts: TtsAdapter,
    private val controller: AvatarController? = null,
    private val options: Options = Options(),
    playbackQueue: PlaybackQueue? = null,
    lipSyncProcessor: LipSyncProcessor? = WlipsyncLipSyncProcessor(),
    gestureDriver: GestureDriver? = null,
    /**
     * 对话历史存储（任务 3）：默认内存环形（杀进程即失），集成方注入
     * [com.neethu.orchestrator.history.RoomConversationStore] 即获得跨进程
     * 持久化与多上下文。
     */
    store: ConversationStore = InMemoryConversationStore(),
    /**
     * 技能框架的能力缝（docs/rps-skill-feasibility.md §4）：null = 未接线，
     * 技能状态机照常运转但能力调用（出动作/抓拍/VAD 调参）全部落空。
     */
    skillHost: SkillHost? = null,
) {

    data class Options(
        /** Concurrent TTS requests (AIRI default: 4). */
        val ttsMaxConcurrent: Int = 4,
        val chunkerOptions: SentenceChunker.Options = SentenceChunker.Options(),
        val enableFaceDriving: Boolean = true,
        val assembler: SystemPromptAssembler = SystemPromptAssembler(),
        /**
         * 每轮请求都携带的身份/任务/表达纪律前言（用户需求：模型要清楚自己是
         * 虚拟人、只说口语台词、持续遵循首轮提供的多模态协议）。人设全文与
         * 协议目录（全量表情/动作/镜头词表）**只随上下文第一轮发送**——之后
         * 历史里留着模型自己发的标签做自我示范；目录指纹变化（重载模型/换
         * 动作来源）或上一轮没送达（失败/被打断）时下一轮重发完整身份块。
         * 前言与人设/协议合并为**一条** system 消息（部分服务商拒绝多条
         * system，A.1 第 34 条）。置空字符串可关闭前言。
         */
        val identityPreamble: String = DEFAULT_IDENTITY_PREAMBLE,
        /**
         * 是否在首轮注入多模态协议块（全量表情/动作/镜头目录）；关闭时身份
         * 前言里的「协议遵循」段一并省略。
         */
        val protocolInstructions: Boolean = true,
        /** Let `<act:…>` tags play gestures from the action catalog. */
        val enableLlmGestures: Boolean = true,
        /** Let `<cam:…>` tags switch the preset camera framing. */
        val enableLlmCamera: Boolean = true,
        /**
         * LLM 请求只带最近 N 条 user/assistant 历史（system 消息恒置顶、不受
         * 裁剪影响）；null = 全量发送。持久化存储下上下文会无限增长，长会话
         * 建议设置。
         */
        val recentTurnLimit: Int? = null,
    )

    /** LLM request parameters; must be set before [send]. */
    var llmConfig: LlmConfig? = null

    /** TTS request parameters; must be set before [send]. */
    var ttsConfig: TtsConfig? = null

    /** Base system prompt. [setCharacterCard] fills this from a card. */
    var systemPrompt: String = ""

    private val _phase = MutableStateFlow(ConversationPhase.IDLE)
    val phase: StateFlow<ConversationPhase> = _phase.asStateFlow()

    private val _events = MutableSharedFlow<AvatarEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AvatarEvent> = _events.asSharedFlow()

    /** Face mixer; null when no controller or face driving disabled. */
    val faceDriver: FaceDriver? =
        if (controller != null && options.enableFaceDriving) FaceDriver(controller, scope) else null

    /**
     * LLM-driven gesture player; null when no controller or gestures
     * disabled. Injectable for tests (pass a fake via the constructor).
     */
    val gestureDriver: GestureDriver? =
        if (controller != null && options.enableLlmGestures) {
            gestureDriver ?: GestureDriver(controller)
        } else {
            null
        }

    /**
     * The gesture catalog advertised to the LLM in the protocol block and
     * playable by `<act:…>` tags. Empty catalog = no action section in the
     * prompt and action cues are silently dropped.
     */
    var actionCatalog: List<ActionEntry> = emptyList()
        set(value) {
            field = value
            gestureDriver?.setCatalog(value)
        }

    /**
     * The looping idle the model returns to after one-shot gestures and
     * manual stops (§7.10). Null = rest pose fallback. Applied through
     * [GestureDriver.setIdle] whenever the model is (re)loaded.
     */
    var idleAction: ActionEntry? = null
        set(value) {
            field = value
            if (value != null) gestureDriver?.setIdle(value) else gestureDriver?.clearIdle()
        }

    /**
     * 技能注册表（docs/rps-skill-feasibility.md §4.2）：app 喂事件
     * （语音文本/VAD 时机/相机手势），session 喂回合结果
     * （[onTurnCompleted 钩子在 send]/interrupt），每轮组请求时读
     * [SkillRegistry.activeDirective]。技能经能力缝反向调用本会话
     * （[playGestureFile]/[speak]/[send]）——host 实现持有 session 引用即可。
     */
    val skills: SkillRegistry = SkillRegistry(skillHost) { emit(it) }

    private val queue: PlaybackQueue = playbackQueue ?: AudioTrackPlaybackQueue()
    private val pipeline = SpeechPipeline(scope, tts, queue, lipSyncProcessor, options.ttsMaxConcurrent)
    private val extractor: TagExtractor = InlineTagExtractor()
    private val chunker = SentenceChunker(options.chunkerOptions)
    private val store: ConversationStore = store
    private val replyBuffer = StringBuilder()
    /** 标签剥离后的正文（与 [replyBuffer] 同步累积，LlmPrompt 日志的"解析后"一路）。 */
    private val cleanBuffer = StringBuilder()
    /**
     * 钉住的协议块文本（内容不变就逐轮复用同一份，不逐轮重拼）；目录变化时
     * 原地重钉并打日志。**钉住 ≠ 发送**——发不发由身份块的送达状态决定
     * （见 [buildRequestMessages]）。
     */
    private var pinnedProtocol: String? = null
    /**
     * 本实例最近一次**成功送达**的完整身份块（前言+人设+协议）文本；null =
     * 还没有任何一回合把身份块送到模型手里。发送与否的判据见
     * [buildRequestMessages]：上下文首轮（store 为空）或身份块指纹相对上次
     * **成功送达**有变化时重发，其余轮次 system 只带前言。判据读的是「成功
     * 送达」而非「拼进了请求」——回合失败/被打断时不提交，下一轮自动重发
     * （用户需求：第一次没发送成功，人设/协议下一次不能省略）。会话重建后
     * 新实例从 null 起步，首轮必然重发一次——刻意的自愈：换 VRM 模型（表情
     * 目录变化）等重建不轮换上下文，靠它保证模型手里的目录永远新鲜。
     */
    private var identitySentBlock: String? = null
    /** 每轮请求恒带的前言（身份/任务/纪律 + 协议遵循提醒），实例内逐字节稳定。 */
    private val missionText: String =
        listOf(options.identityPreamble.trim(), if (options.protocolInstructions) PROTOCOL_REMINDER else "")
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
    private var turnJob: Job? = null
    /**
     * 等待挂接的表情 cue 列表（§7.4）：标签语义上修饰"它后面的那句话"，而
     * TTS 合成+排队常把开播推迟数秒——若在 LLM 吐标签瞬间就驱动面部，3 秒
     * 自动归零会在句子出声前就把表情吃掉（真机：整段回复的表情全部提前衰减
     * 完，脸全程无变化）。cue 先存这里，[submitSentence] 挂到句序号上，开播
     * 瞬间才应用并保持"clip 时长 + 余量"。同名连续序列（协议教的眨眼模式
     * `<emo:x:1><emo:x:0>`）开播后按步进补发后续 cue；句尾残余在回合收尾时
     * 立即应用。
     */
    private val pendingEmotions = ArrayList<EmotionCue>()
    private val emotionsBySequence = java.util.concurrent.ConcurrentHashMap<Int, List<EmotionCue>>()

    init {
        lipSyncProcessor?.let { lsp ->
            if (lsp is WlipsyncLipSyncProcessor) faceDriver?.setPhonemeLayout(lsp.vowelLayout)
        }
        pipeline.listener = object : SpeechPipeline.Listener {
        override fun onSentenceFailed(sequence: Int, text: String, error: Throwable) {
            Log.e("AvatarSession", "${STREAM_PREFIX}sentence #$sequence failed", error)
            emit(AvatarEvent.SentenceFailed(sequence, text, error.message ?: "TTS failed"))
        }
        }
        queue.listener = object : PlaybackQueue.Listener {
            override fun onPlaybackStarted(item: PlaybackItem) {
                queue.active()?.let { faceDriver?.onPlaybackStarted(item, it) }
                scope.launch(Dispatchers.Main.immediate) {
                    // 表情在开播瞬间应用（§7.4）：hold = clip 时长 + 余量，长句
                    // 不再中途被默认 3 秒归零切断。
                    emotionsBySequence.remove(item.sequence)?.let { cues ->
                        val holdMs = item.pcm.size * 1_000L / item.sampleRateHz.coerceAtLeast(1) +
                            EMOTION_HOLD_MARGIN_MS
                        applyEmotionCues(faceDriver, cues, holdMs)
                            ?.let { emit(AvatarEvent.EmotionChanged(it)) }
                    }
                    // Normal turns arrive here via THINKING; speak()'s greeting
                    // turns start from IDLE — either way playback means speaking.
                    if (_phase.value != ConversationPhase.SPEAKING) {
                        _phase.value = ConversationPhase.SPEAKING
                    }
                    emit(AvatarEvent.SentenceStarted(item.sequence, item.text))
                }
            }

            override fun onPlaybackEnded(item: PlaybackItem) {
                pipeline.onPlaybackSettled()
                faceDriver?.onPlaybackEnded()
                // 观测点：Ended 应发生在整段 PCM 播完之后（墙钟差 ≥ pcm 时长），
                // 若明显小于说明句尾被截断（对照 logcat 的 SentenceStarted 时间戳）。
                Log.i(
                    "AvatarSession",
                    "${STREAM_PREFIX}clip #${item.sequence} pcm=${"%.2f".format(item.pcm.size.toFloat() / item.sampleRateHz)}s",
                )
                scope.launch(Dispatchers.Main.immediate) {
                    emit(AvatarEvent.SentenceEnded(item.sequence, item.text))
                }
            }

            override fun onPlaybackInterrupted(item: PlaybackItem?) {
                pipeline.onPlaybackSettled()
                faceDriver?.onPlaybackInterrupted()
                scope.launch(Dispatchers.Main.immediate) {
                    if (_phase.value == ConversationPhase.SPEAKING) _phase.value = ConversationPhase.IDLE
                    emit(AvatarEvent.PlaybackInterrupted)
                }
            }
        }
    }

    // ── Configuration ─────────────────────────────────────────────────────

    /** Load persona from a character card and rebuild the system prompt. */
    fun setCharacterCard(card: CharacterCard) {
        systemPrompt = options.assembler.assemble(card)
    }

    /** Drop the card persona (system prompt reverts to empty). */
    fun clearCharacterCard() {
        systemPrompt = ""
    }

    /**
     * Start the face driver. Call once the avatar model is loaded so the
     * expression name list is available. Safe to call again after model reload.
     */
    fun startFaceDriving() {
        faceDriver?.start()
    }

    fun stopFaceDriving() {
        faceDriver?.stop()
    }

    /**
     * 应用拟人感设置（呼吸/视线/眨眼的开关与参数）。全部实时生效、不重建
     * 会话；会话（重）建后由调用方重放，使设置跨会话存续。
     */
    fun applyLivenessSettings(
        breathEnabled: Boolean,
        breathAmplitude: Float,
        breathRateBpm: Float,
        saccadeEnabled: Boolean,
        saccadeJitter: Float,
        blinkEnabled: Boolean,
        blinkIntervalS: Float,
    ) {
        controller?.setBreathEnabled(breathEnabled)
        controller?.setBreathAmplitudeScale(breathAmplitude)
        controller?.setBreathRateBpm(breathRateBpm)
        faceDriver?.setSaccadeEnabled(saccadeEnabled)
        faceDriver?.setSaccadeJitter(saccadeJitter)
        faceDriver?.setBlinkEnabled(blinkEnabled)
        faceDriver?.setBlinkIntervalMean(blinkIntervalS)
    }

    /** Clear dialog history (a fresh conversation). */
    fun clearHistory() {
        store.clear()
    }

    // ── Conversation control ──────────────────────────────────────────────

    /**
     * Fire-and-forget user turn. Emits [AvatarEvent]s on [events].
     *
     * [images] (data URLs) ride the CURRENT request only — the multimodal
     * camera snapshot of a video-call turn. They are attached to the trailing
     * user message but never persisted into history: keeping every historical
     * turn's frame in context would multiply vision-token cost per turn for
     * no conversational gain (the model reads "刚才那张图" without pixels).
     */
    fun send(text: String, images: List<String> = emptyList()) {
        if (turnJob?.isActive == true) interrupt("superseded")
        val llmCfg = llmConfig ?: run {
            emit(AvatarEvent.TurnFailed(IllegalStateException("llmConfig not set"))); return
        }
        val ttsCfg = ttsConfig ?: run {
            emit(AvatarEvent.TurnFailed(IllegalStateException("ttsConfig not set"))); return
        }
        if (images.isNotEmpty()) {
            Log.i("AvatarSession", "${STREAM_PREFIX}multimodal turn: ${images.size} image(s), text=${text.take(40)}")
        }
        turnJob = scope.launch {
            _phase.value = ConversationPhase.THINKING
            // 上下文首轮判定必须在 appendUser 之前采样（append 后历史永不为空）
            val contextFirstTurn = store.messages().isEmpty()
            extractor.reset()
            chunker.reset()
            replyBuffer.setLength(0)
            cleanBuffer.setLength(0)
            pendingEmotions.clear()
            emotionsBySequence.clear()
            pipeline.beginTurn()
            store.appendUser(text)
            // 首句延迟三段法（grep InfoStreamDectect）：REQUEST→首个 TextDelta 的
            // ttfb + SentenceQueued→SentenceStarted 的 TTS 合成，都在本行/事件流里
            val turnStartMs = SystemClock.elapsedRealtime()
            var ttfbMs = -1L
            var usage: LlmUsage? = null
            try {
                val built = buildRequestMessages(images, contextFirstTurn)
                llm.streamChat(built.messages, llmCfg).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> {
                            if (ttfbMs < 0) ttfbMs = SystemClock.elapsedRealtime() - turnStartMs
                            handleDelta(event.text, ttsCfg)
                        }
                        is LlmStreamEvent.Finish -> usage = event.usage
                        is LlmStreamEvent.Error -> throw event.throwable
                    }
                }
                // LLM 流成功走完 = 人设/协议确实送达了模型 → 才标记「已发送」。
                // 失败/被打断走不到这里，下一轮自动重发完整身份块（用户需求：
                // 第一次没发送成功，人设/协议下一次不能省略）。精简轮不携带身份
                // 块（null），不动已提交的状态。
                built.identityBlock?.let { identitySentBlock = it }
                val streamMs = SystemClock.elapsedRealtime() - turnStartMs
                // Drain the extractor tail, then any chunker remainder.
                val tail = extractor.flush()
                cleanBuffer.append(tail.cleanText)
                if (tail.cleanText.isNotEmpty()) {
                    chunker.feed(tail.cleanText).forEach { submitSentence(it, ttsCfg) }
                }
                chunker.flush().forEach { submitSentence(it, ttsCfg) }
                // 句尾残余标签（其后没有新句子了）：立即应用
                flushTrailingEmotion()
                pipeline.endTurn()
                pipeline.awaitTurnComplete()
                // 观测点：原始回复（含标签）——排查"模型没发标签/标签被丢弃"先看这行
                Log.i("AvatarSession", "${STREAM_PREFIX}raw reply: ${replyBuffer}")
                // 需求 5：原始与解析后的完整回复落到专用 tag（分段+单行化，方便排查）。
                // ttfb=首 token 延迟（首句等待的大头）；tok 一段=prompt/cached/completion
                // token（cached>0=服务商前缀缓存命中，钉住协议的省钱验证位）
                Log.i(
                    PROMPT_TAG,
                    "${STREAM_PREFIX}=== RESPONSE model=${llmCfg.model} raw=${replyBuffer.length}ch clean=${cleanBuffer.length}ch " +
                        "ttfb=${ttfbMs}ms stream=${streamMs}ms${usagePart(usage)} ===",
                )
                logChunked(PROMPT_TAG, "RESPONSE raw", replyBuffer.toString())
                logChunked(PROMPT_TAG, "RESPONSE clean", cleanBuffer.toString())
                store.appendAssistant(replyBuffer.toString())
                _phase.value = ConversationPhase.IDLE
                emit(AvatarEvent.TurnCompleted(interrupted = false))
                // 技能缝：回合结果广播给技能（猜拳 INVITED→ARMED / JUDGING→ARMED）
                skills.onTurnCompleted(cleanBuffer.toString())
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                pipeline.cancelTurn("turn-error")
                _phase.value = ConversationPhase.IDLE
                emit(AvatarEvent.TurnFailed(t))
                skills.onTurnFailed()
            }
        }
    }

    /** Send and suspend until the whole turn (LLM + TTS + playback) settles. */
    suspend fun sendAndAwait(text: String, images: List<String> = emptyList()) {
        send(text, images)
        turnJob?.join()
    }

    /**
     * 技能专用动作通道：不经 LLM 动作目录直接按路径播一个动作 clip（非循环，
     * 播完引擎自动回待机）——猜拳出拳手势走这里，`<act:>` 协议块里永远看不到
     * 这些 tag（防 LLM 对话中随机刷出）。[assetPath] 是 assets 相对路径
     * （"animations/…"）或绝对文件路径。
     */
    fun playGestureFile(assetPath: String): Boolean {
        val driver = gestureDriver ?: return false
        val name = assetPath.substringAfterLast('/').removeSuffix(".vrma")
        return driver.playFile(ActionEntry(tag = "skill:$name", label = name, assetPath = assetPath))
    }

    /**
     * Speak [text] directly, without an LLM turn — character-card greetings,
     * announcements. The text goes through the same tag extractor → chunker
     * → TTS → ordered playback pipeline as LLM turns (inline tags — emotion,
     * action, camera — ARE interpreted, so a greeting can wave and smile);
     * phase skips THINKING and the playback callback drives IDLE→SPEAKING→
     * IDLE. The text is not appended to conversation history. Suspending
     * like [sendAndAwait]; [interrupt] cuts it mid-playback.
     */
    suspend fun speak(text: String) {
        if (text.isBlank()) return
        if (turnJob?.isActive == true) interrupt("superseded")
        val ttsCfg = ttsConfig ?: run {
            emit(AvatarEvent.TurnFailed(IllegalStateException("ttsConfig not set")))
            return
        }
        val job = scope.launch {
            extractor.reset()
            chunker.reset()
            pendingEmotions.clear()
            emotionsBySequence.clear()
            pipeline.beginTurn()
            try {
                val tagged = extractor.feed(text)
                val tail = extractor.flush()
                dispatchCues(tagged.cues + tail.cues)
                (chunker.feed(tagged.cleanText + tail.cleanText) + chunker.flush())
                    .forEach { submitSentence(it, ttsCfg) }
                flushTrailingEmotion()
                pipeline.endTurn()
                pipeline.awaitTurnComplete()
                _phase.value = ConversationPhase.IDLE
                emit(AvatarEvent.TurnCompleted(interrupted = false))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                pipeline.cancelTurn("speak-error")
                _phase.value = ConversationPhase.IDLE
                emit(AvatarEvent.TurnFailed(t))
            }
        }
        turnJob = job
        job.join()
    }

    /**
     * Three-layer interrupt (AIRI parity): cancels the LLM stream, aborts
     * in-flight TTS, and silences playback immediately. Any LLM-started
     * gesture is cut back to the rest pose; the camera keeps its current
     * framing (a shot is a mode, not a transient). Phase returns to IDLE.
     */
    fun interrupt(reason: String = "user-interrupt") {
        turnJob?.cancel()
        turnJob = null
        pendingEmotions.clear()
        emotionsBySequence.clear()
        gestureDriver?.stop()
        pipeline.cancelTurn(reason)
        skills.onInterrupted()
        scope.launch(Dispatchers.Main.immediate) {
            if (_phase.value != ConversationPhase.IDLE) _phase.value = ConversationPhase.IDLE
        }
    }

    /** Interrupt + stop face driving + release audio resources. */
    fun close() {
        interrupt("close")
        faceDriver?.stop()
        queue.release()
    }

    // ── Turn internals ────────────────────────────────────────────────────

    private fun handleDelta(delta: String, ttsCfg: TtsConfig) {
        replyBuffer.append(delta)
        val result = extractor.feed(delta)
        cleanBuffer.append(result.cleanText)
        dispatchCues(result.cues)
        if (result.cleanText.isNotEmpty()) {
            chunker.feed(result.cleanText).forEach { submitSentence(it, ttsCfg) }
        }
    }

    /**
     * Multimodal dispatch (docs/ai-layer-handoff.md §7.4)：camera/action cue
     * 在 LLM 吐出的瞬间执行（反应快，且动作/镜头是持续态，早几秒无妨）；
     * emotion cue 改为挂到其后第一个句子上、开播瞬间才驱动面部——TTS 排队
     * 延迟下"即发即执行"会让表情在出声前就被 3 秒归零吃掉（真机踩过）。
     * Unknown names are silently dropped, never surfaced as errors.
     */
    private fun dispatchCues(cues: List<TagCue>) {
        for (cue in cues) when (cue) {
            is TagCue.Emotion -> {
                // Canonical emotions win (combo defs); other names resolve to the
                // model's actual morph casing (extractor lowercases everything,
                // morph names are case-sensitive — blinkLeft ≠ blinkleft, §7.10).
                val fd = faceDriver
                val canonical = cue.name.lowercase()
                val isCanonical = fd != null && canonical in fd.knownEmotionNames
                val direct = fd?.resolveExpression(cue.name)
                if (fd == null || isCanonical || direct != null) {
                    pendingEmotions += EmotionCue(
                        when {
                            fd == null || isCanonical -> canonical
                            else -> direct!!
                        },
                        cue.intensity,
                    )
                }
            }
            is TagCue.Action -> {
                val entry = actionCatalog.firstOrNull { it.tag == cue.name }
                if (gestureDriver?.play(cue.name) == true) {
                    emit(AvatarEvent.ActionStarted(cue.name, entry?.label ?: cue.name))
                }
            }
            is TagCue.Camera -> dispatchCamera(cue.name)
        }
    }

    private fun dispatchCamera(name: String) {
        if (!options.enableLlmCamera) return
        val shot = CAMERA_SHOTS[name] ?: return
        controller?.setCameraShot(shot)
        emit(AvatarEvent.CameraChanged(shot))
    }

    private fun submitSentence(sentence: String, ttsCfg: TtsConfig) {
        if (sentence.isBlank()) return
        val sequence = pipeline.submit(sentence, ttsCfg)
        // 标签修饰其后的句子：挂到这句的序号上，开播时应用（见 [pendingEmotions]）。
        pendingEmotions.takeIf { it.isNotEmpty() }?.let {
            emotionsBySequence[sequence] = it.toList()
            it.clear()
        }
        emit(AvatarEvent.SentenceQueued(sequence, sentence))
    }

    /** 回合收尾：仍没等到句子的表情 cue（句尾标签）立即应用。 */
    private fun flushTrailingEmotion() {
        if (pendingEmotions.isEmpty()) return
        val cues = ArrayList(pendingEmotions)
        pendingEmotions.clear()
        applyEmotionCues(faceDriver, cues, FaceDriver.DEFAULT_EMOTION_HOLD_MS)
            ?.let { emit(AvatarEvent.EmotionChanged(it)) }
    }

    /**
     * 开播时应用一句话的表情 cue 序列。全部同名（协议教的眨眼/开合模式
     * `<emo:x:1>` 紧跟 `<emo:x:0>`）→ 首个立即应用（hold 覆盖整句），后续
     * 按 [EMOTION_STEP_MS] 步进补发（不重置归零计时）；混有不同名 → 句尾
     * 情绪生效（同一句内互相矛盾的情绪只有最后一个能落地）。返回 t=0 应用
     * 的 cue（供发 EmotionChanged 事件），无 cue/无驱动器返回 null。
     */
    private fun applyEmotionCues(
        faceDriver: FaceDriver?,
        cues: List<EmotionCue>,
        holdMs: Long,
    ): EmotionCue? {
        val fd = faceDriver ?: return null
        if (cues.isEmpty()) return null
        val (first, deferred) = splitEmotionRun(cues)
        fd.applyEmotion(first, holdMs)
        deferred.forEachIndexed { index, cue ->
            scope.launch(Dispatchers.Main.immediate) {
                delay(EMOTION_STEP_MS * (index + 1))
                fd.applyEmotion(cue, -1)
            }
        }
        return first
    }

    /**
     * 每轮请求注入当前镜头视角（需求 2：任何模式的提示词都告知大模型所在
     * 视角，让 <cam:> 建议与描述符合用户实际看到的取景）。headless（无
     * controller）不注入。视角行是请求里唯一逐轮变化的指令，挂在末尾 user
     * 消息上（只改本轮请求的副本，store 始终存干净文本）：离生成位置最近，
     * 且不破坏 [人设][协议][历史] 前缀的逐字节稳定（前缀缓存友好）。
     */
    private fun currentViewLine(): String? {
        val c = controller ?: return null
        val shot = c.getActiveCameraShot()
        return if (shot != null) "【当前镜头视角】${shot.label}（${shot.name}），用户正以这个机位看着你。"
        else "【当前镜头视角】自由视角（FREE），用户正手动控制镜头。"
    }

    /** 一次组好的请求：消息序列 + 本轮携带的完整身份块文本（null=没有身份内容）。 */
    private data class BuiltRequest(val messages: List<ChatMessage>, val identityBlock: String?)

    private fun buildRequestMessages(turnImages: List<String>, contextFirstTurn: Boolean): BuiltRequest {
        val list = mutableListOf<ChatMessage>()
        // system 结构（单条——部分服务商对多条 system 直接 400，A.1 第 34 条）：
        //   完整形态（上下文首轮，或上次没送达/指纹变化后的重发轮）：
        //     [前言：身份/任务/台词纪律+协议遵循][人设全文][协议目录全文]
        //   精简形态（其余轮次）：只有前言。
        // 人设与协议目录只随首轮发送（用户需求：不变的内容不逐轮重发）——之后
        // 历史里保留着模型自己发的标签（自我示范）；目录指纹或人设相对上次
        // **成功送达**的文本有变化时重发。精简形态逐字节稳定，服务商前缀缓存
        // 从第二轮起全程命中；首轮与重发轮和精简轮不共享 system 前缀，属一次
        // 性成本。
        val history = store.messages()
        val persona = systemPrompt.trim()
        val protocol = pinnedProtocolText()
        val identityBlock = listOf(missionText, persona, protocol.orEmpty())
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
            .ifEmpty { null }
        val includeIdentity = identityBlock != null &&
            (contextFirstTurn || identityBlock != identitySentBlock)
        val systemText = when {
            includeIdentity -> identityBlock.orEmpty()
            missionText.isNotEmpty() -> missionText
            else -> ""
        }
        if (systemText.isNotEmpty()) list += ChatMessage(ChatRole.SYSTEM, systemText)
        // 历史裁剪（任务 3）：store 里只有 user/assistant；人设/协议两段
        // 都不进 store，天然不受 recentTurnLimit 影响。
        list += options.recentTurnLimit?.let { history.takeLast(it) } ?: history
        // 视角行 + 技能指令行 + 本轮抓拍（视频模式）：都挂到刚 append 的末尾
        // user 消息上——请求里带，store 里的历史始终只有干净文字（见 send 的
        // 注释）。技能指令与视角行同级：逐轮变化、只改本轮请求副本、不碰
        // system/协议（前缀缓存友好，A.1 第 34 条的"system 恒一条"不受影响）。
        val viewLine = currentViewLine()
        val skillLine = skills.activeDirective()
        val directivePrefix = listOfNotNull(viewLine, skillLine).joinToString("\n\n")
        if ((directivePrefix.isNotEmpty() || turnImages.isNotEmpty()) && list.lastOrNull()?.role == ChatRole.USER) {
            val last = list.last()
            list[list.size - 1] = last.copy(
                content = if (directivePrefix.isNotEmpty()) "$directivePrefix\n\n${last.content}" else last.content,
                images = turnImages,
            )
        }
        // 需求 4：提示词落到专用 tag（LlmPrompt），分段绕开 logcat 单条上限。
        // thinking= 是否显式关闭（关思考的参数各家不同：火山 thinking.type /
        // 硅基流动 enable_thinking，见 AiProviders.llmExtraBody）
        val eb = llmConfig?.extraBody
        val thinkingOff = eb?.containsKey("thinking") == true || eb?.containsKey("enable_thinking") == true
        // sys=full（前言+人设+协议整块）| brief（只带前言）；人设/协议列实际
        // 发送情况——排查"模型不知道自己人设/不会用标签"先看这两个字段
        val sysLog = if (includeIdentity) "full" else "brief"
        val personaLog = if (includeIdentity) "${persona.length}ch" else "omitted"
        val protocolLog = when {
            includeIdentity && protocol != null -> "${protocol.length}ch"
            includeIdentity -> "off"
            else -> "omitted"
        }
        Log.i(
            PROMPT_TAG,
            "${STREAM_PREFIX}=== REQUEST model=${llmConfig?.model} thinking=${if (thinkingOff) "off" else "default"} " +
                "view=${viewLine ?: "n/a"} skill=${skillLine?.length ?: 0}ch " +
                "sys=$sysLog persona=$personaLog protocol=$protocolLog " +
                "history=${history.size} sent=${trimmedSent(history)} images=${turnImages.size} ===",
        )
        list.lastOrNull()
            ?.takeIf { it.role == ChatRole.USER }
            ?.let { Log.i(PROMPT_TAG, "${STREAM_PREFIX}REQUEST user: ${it.content}${if (it.images.isEmpty()) "" else " (+${it.images.size} image)"}") }
        // 完整 system 消息逐字节落日志（分段绕 logcat 单条上限）：full 轮=前言+
        // 人设+协议目录整块，brief 轮=只有前言。配合 REQUEST user 行，每轮请求
        // 模型实际收到什么全部可审计——排查"模型不知道人设/不遵循协议/念旁白"
        // 直接读这两段，不用再拼凑 persona/protocol 各自的分段转储
        if (systemText.isNotEmpty()) logChunked(PROMPT_TAG, "REQUEST system", systemText)
        // 观测点：请求形态与三段可用清单规模（排查"模型不用标签"时先看这行；
        // sys=brief=本上下文非首轮且身份块已成功送达过，模型靠历史里的标签
        // 自我示范；首轮与重发轮必为 full）
        Log.i(
            "AvatarSession",
            "${STREAM_PREFIX}prompt: sys=$sysLog persona=$personaLog protocol=$protocolLog, " +
                "cameras=${currentCameraTags().size}, actions=${currentActionGroups().sumOf { it.second.size }}, " +
                "directExpr=${currentDirectExpressions().size}, history=${history.size} " +
                "sent=${trimmedSent(history)} images=${turnImages.size}",
        )
        return BuiltRequest(list, if (includeIdentity) identityBlock else null)
    }

    /**
     * 协议块（全量表情/动作/镜头目录）文本的钉住层：内容只取决于当前加载的
     * 模型与开关，与轮次无关，逐轮重拼纯属浪费——缓存上次组装的文本，内容
     * 没变就复用同一份；目录变了（重载模型/镜头开关切换）就原地重钉并打日志。
     * **钉住 ≠ 发送**：是否随请求带上由 [buildRequestMessages] 按「上下文首轮
     * 或指纹相对上次发送有变化」决定（用户需求：目录不变就不必每轮都发）。
     * LLM 模型切换的「新开上下文」由集成方轮换上下文 id 实现（demo 见
     * MainActivity）。
     *
     * 返回协议文本；null = 协议关闭或目录为空。
     */
    private fun pinnedProtocolText(): String? {
        if (!options.protocolInstructions) return null
        val block = options.assembler.multimodalProtocolBlock(
            cameras = currentCameraTags(),
            actionGroups = currentActionGroups(),
            directExpressions = currentDirectExpressions(),
        )
        if (block.isBlank()) return null
        if (block != pinnedProtocol) {
            pinnedProtocol = block
            Log.i(
                "AvatarSession",
                "${STREAM_PREFIX}protocol pinned: ${block.length} chars, " +
                    "cameras=${currentCameraTags().size}, " +
                    "actions=${currentActionGroups().sumOf { it.second.size }}, " +
                    "directExpr=${currentDirectExpressions().size}",
            )
        }
        return block
    }

    /** 实际发送的历史条数（观测点用）：null 上限 = 全量。 */
    private fun trimmedSent(history: List<ChatMessage>): Int =
        options.recentTurnLimit?.let { minOf(it, history.size) } ?: history.size

    /** RESPONSE 头行的 token 用量段：`tok: prompt=A cached=B completion=C`，未上报时缺省。 */
    private fun usagePart(usage: LlmUsage?): String = when (usage) {
        null -> ""
        else -> " tok: prompt=${usage.promptTokens} cached=${usage.cachedTokens ?: -1} completion=${usage.completionTokens}"
    }

    /**
     * 多模态协议块全文——上下文第一轮请求里那条 system 消息的协议段（后续
     * 轮次省略不发送，见 [pinnedProtocolText]；发送时与人设拼接为一条
     * system）。设置页的「协议提示词」只读展示走这里，保证 UI 看到的就是
     * 上下文开始时模型收到的。
     */
    fun protocolBlock(): String {
        // Sections of the protocol block that cannot run are omitted so the
        // prompt never advertises a tag the session would drop.
        return options.assembler.multimodalProtocolBlock(
            cameras = currentCameraTags(),
            actionGroups = currentActionGroups(),
            directExpressions = currentDirectExpressions(),
        )
    }

    private fun currentCameraTags(): List<Pair<String, String>> =
        if (options.enableLlmCamera && controller != null) SystemPromptAssembler.DEFAULT_CAMERA_TAGS
        else emptyList()

    private fun currentActionGroups(): List<Pair<String, List<String>>> =
        if (gestureDriver != null) actionCatalog.groupBy { it.category }
            .map { (category, entries) -> category to entries.map { it.tag } }
        else emptyList()

    private fun currentDirectExpressions(): List<String> =
        faceDriver?.availableExpressions?.filter { it.isNotBlank() }?.sorted() ?: emptyList()

    private fun emit(event: AvatarEvent) {
        _events.tryEmit(event)
    }

    /**
     * 长文本按 ~3.2K 字符分段打日志（logcat 单条有上限），换行换成字面 `\n`
     * 让每段保持单行——`adb logcat -s LlmPrompt` 可整段读回。
     */
    private fun logChunked(tag: String, header: String, body: String) {
        if (body.isEmpty()) {
            Log.i(tag, "$STREAM_PREFIX$header <empty>")
            return
        }
        val chunkSize = 3_200
        val parts = (body.length + chunkSize - 1) / chunkSize
        var start = 0
        var part = 1
        while (start < body.length) {
            val end = minOf(start + chunkSize, body.length)
            Log.i(tag, "$STREAM_PREFIX$header [$part/$parts] ${body.substring(start, end).replace("\n", "\\n")}")
            start = end
            part++
        }
    }

    companion object {
        /** 提示词/回复的专用排查日志 tag（需求 4/5）：`adb logcat -s LlmPrompt`。 */
        private const val PROMPT_TAG = "LlmPrompt"

        /** 问答链路日志的统一前缀（用户 grep 用，覆盖提示词/回复/实时播放事件）。 */
        private const val STREAM_PREFIX = "[InfoStreamDectect] "

        /**
         * 每轮请求恒带的身份/任务/表达纪律前言（[Options.identityPreamble]
         * 默认值；用户需求 3/4：模型曾把人设里的旁白/心理描写当台词念出来，
         * 且不清楚自己是虚拟人）。两段都极短——它们每轮都在请求里。
         */
        const val DEFAULT_IDENTITY_PREAMBLE =
            "【身份与任务】你是一个 3D 虚拟人,正在与用户实时交流:你说出的每句话都会被语音合成" +
                "朗读出来,用户看得到你虚拟形象的表情与动作;你的任务就是演好当前这个角色," +
                "自然地陪用户聊天互动。\n" +
                "【台词纪律】只说角色开口要说的话,像面对面聊天一样简短自然;不要念出小说式旁白、" +
                "心理活动或场景描写,也不要用(括号)或*星号*描写动作神态——情绪与动作请用行内标签" +
                "表达,让虚拟形象替你演出来。"

        /**
         * 协议遵循提醒（protocolInstructions=true 时拼在前言末尾；用户需求 5：
         * 目录词表只在首轮发送，之后每轮提醒模型继续遵循、只用历史里出现过的
         * 标签名——防止看不到词表后改用括号动作或胡编标签名）。
         */
        const val PROTOCOL_REMINDER =
            "【协议遵循】完整的多模态标签词表(<emo:/<act:/<cam:>)只在对话开头的系统消息里提供过" +
                "一次,后续请求不再重复;请继续遵循该协议:标签放在语义对应的位置," +
                "只使用你历史回复中出现过的标签名,记不准就不要发标签。"

        /** 句子挂接表情的保持余量（clip 时长之上）：句尾留一段表情余韵再归零。 */
        private const val EMOTION_HOLD_MARGIN_MS = 1_200L

        /** 同名表情序列（眨眼模式）补发步长：闭→开的间隔≈一次眨眼。 */
        private const val EMOTION_STEP_MS = 200L

        /**
         * 纯函数拆分一句话的表情 cue 序列（单测锁定分支）：全部同名（协议教
         * 的眨眼/开合模式）→（首个, 其余按步进补发）；混有不同名或单条 →
         * （句尾情绪, 无补发）。
         */
        internal fun splitEmotionRun(cues: List<EmotionCue>): Pair<EmotionCue, List<EmotionCue>> {
            val firstName = cues.first().name
            return if (cues.size == 1 || cues.any { it.name != firstName }) {
                cues.last() to emptyList()
            } else {
                cues.first() to cues.drop(1)
            }
        }

        /** `<cam:…>` tag → preset shot (tag values are the enum names lowercased). */
    private val CAMERA_SHOTS: Map<String, CameraShot> = mapOf(
        "close_up" to CameraShot.CLOSE_UP,
        "macro" to CameraShot.MACRO,
        "medium_shot" to CameraShot.MEDIUM_SHOT,
        "full_shot" to CameraShot.FULL_SHOT,
        "long_shot" to CameraShot.LONG_SHOT,
        "over_shoulder" to CameraShot.OVER_SHOULDER,
    )
    }
}
