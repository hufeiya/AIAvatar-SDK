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
) {

    data class Options(
        /** Concurrent TTS requests (AIRI default: 4). */
        val ttsMaxConcurrent: Int = 4,
        val chunkerOptions: SentenceChunker.Options = SentenceChunker.Options(),
        val enableFaceDriving: Boolean = true,
        val assembler: SystemPromptAssembler = SystemPromptAssembler(),
        /**
         * 把多模态协议块（全量表情/动作/镜头目录）注入请求。目录只与当前加载
         * 的模型/目录有关、与轮次无关，因此每个上下文只组装一次并逐轮复用
         * 同一文本（[pinnedProtocol]），不逐轮重拼；发送时与人设 system 合并为
         * **一条** system 消息（部分服务商拒绝多条 system，A.1 第 34 条），
         * 请求前缀 [人设+协议一条 system][历史] 逐字节稳定，服务商的
         * 前缀缓存可以全程命中。
         */
        val protocolInstructions: Boolean = true,
        /** Let `<act:…>` tags play gestures from the action catalog. */
        val enableLlmGestures: Boolean = true,
        /** Let `<cam:…>` tags switch the preset camera framing. */
        val enableLlmCamera: Boolean = true,
        /**
         * LLM 请求只带最近 N 条 user/assistant 历史（人设 system 与协议
         * system 消息恒置顶、不受裁剪影响）；null = 全量发送。持久化存储下
         * 上下文会无限增长，长会话建议设置。
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

    private val queue: PlaybackQueue = playbackQueue ?: AudioTrackPlaybackQueue()
    private val pipeline = SpeechPipeline(scope, tts, queue, lipSyncProcessor, options.ttsMaxConcurrent)
    private val extractor: TagExtractor = InlineTagExtractor()
    private val chunker = SentenceChunker(options.chunkerOptions)
    private val store: ConversationStore = store
    private val replyBuffer = StringBuilder()
    /** 标签剥离后的正文（与 [replyBuffer] 同步累积，LlmPrompt 日志的"解析后"一路）。 */
    private val cleanBuffer = StringBuilder()
    /**
     * 当前上下文已注入的协议块文本（每上下文只组装一次）；null = 尚未注入。
     * 目录变化（重载模型、镜头开关）时原地重钉，历史保留。
     */
    private var pinnedProtocol: String? = null
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
                llm.streamChat(buildRequestMessages(images), llmCfg).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> {
                            if (ttfbMs < 0) ttfbMs = SystemClock.elapsedRealtime() - turnStartMs
                            handleDelta(event.text, ttsCfg)
                        }
                        is LlmStreamEvent.Finish -> usage = event.usage
                        is LlmStreamEvent.Error -> throw event.throwable
                    }
                }
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
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                pipeline.cancelTurn("turn-error")
                _phase.value = ConversationPhase.IDLE
                emit(AvatarEvent.TurnFailed(t))
            }
        }
    }

    /** Send and suspend until the whole turn (LLM + TTS + playback) settles. */
    suspend fun sendAndAwait(text: String, images: List<String> = emptyList()) {
        send(text, images)
        turnJob?.join()
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

    private fun buildRequestMessages(turnImages: List<String>): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        // 人设+协议合并为**一条** system：部分服务商对多条 system 直接 400
        // （实测硅基流动 Qwen3.8 "System message must be at the beginning"，
        // 即使两条都在开头——A.1 第 34 条），单条 system 所有 OpenAI 兼容端点
        // 都收。两段文本仍各自钉住缓存（见 [pinnedProtocolText]），只在发送前
        // 拼接；换卡改人设时历史已清，前缀缓存自然失效，可接受。
        val persona = systemPrompt.trim()
        val protocol = pinnedProtocolText().orEmpty()
        val systemText = listOf(persona, protocol).filter { it.isNotEmpty() }.joinToString("\n\n")
        if (systemText.isNotEmpty()) list += ChatMessage(ChatRole.SYSTEM, systemText)
        // 历史裁剪（任务 3）：store 里只有 user/assistant；人设/协议两条
        // system 都不进 store，天然不受 recentTurnLimit 影响。
        val history = store.messages()
        list += options.recentTurnLimit?.let { history.takeLast(it) } ?: history
        // 视角行 + 本轮抓拍（视频模式）：都挂到刚 append 的末尾 user 消息上——
        // 请求里带，store 里的历史始终只有干净文字（见 send 的注释）。
        val viewLine = currentViewLine()
        if ((viewLine != null || turnImages.isNotEmpty()) && list.lastOrNull()?.role == ChatRole.USER) {
            val last = list.last()
            list[list.size - 1] = last.copy(
                content = if (viewLine != null) "$viewLine\n\n${last.content}" else last.content,
                images = turnImages,
            )
        }
        // 需求 4：提示词落到专用 tag（LlmPrompt），分段绕开 logcat 单条上限。
        // thinking= 是否显式关闭（关思考的参数各家不同：火山 thinking.type /
        // 硅基流动 enable_thinking，见 AiProviders.llmExtraBody）
        val eb = llmConfig?.extraBody
        val thinkingOff = eb?.containsKey("thinking") == true || eb?.containsKey("enable_thinking") == true
        Log.i(
            PROMPT_TAG,
            "${STREAM_PREFIX}=== REQUEST model=${llmConfig?.model} thinking=${if (thinkingOff) "off" else "default"} " +
                "view=${viewLine ?: "n/a"} " +
                "persona=${persona.length}ch protocol=${pinnedProtocol?.length ?: 0}ch " +
                "history=${history.size} sent=${trimmedSent(history)} images=${turnImages.size} ===",
        )
        list.lastOrNull()
            ?.takeIf { it.role == ChatRole.USER }
            ?.let { Log.i(PROMPT_TAG, "${STREAM_PREFIX}REQUEST user: ${it.content}${if (it.images.isEmpty()) "" else " (+${it.images.size} image)"}") }
        if (persona.isNotEmpty()) logChunked(PROMPT_TAG, "REQUEST persona", persona)
        pinnedProtocol?.let { logChunked(PROMPT_TAG, "REQUEST protocol", it) }
        // 观测点：协议块规模与三段可用清单是否注入（排查"模型不用标签"时先看这行）
        Log.i(
            "AvatarSession",
            "${STREAM_PREFIX}prompt: persona=${persona.length}ch protocol=${pinnedProtocol?.length ?: 0}ch, " +
                "cameras=${currentCameraTags().size}, actions=${currentActionGroups().sumOf { it.second.size }}, " +
                "directExpr=${currentDirectExpressions().size}, history=${history.size} " +
                "sent=${trimmedSent(history)} images=${turnImages.size}",
        )
        return list
    }

    /**
     * 协议块（全量表情/动作/镜头目录）每上下文只组装一次：文本只取决于当前
     * 加载的模型与开关，与轮次无关，逐轮重拼纯属浪费。这里缓存上次注入的
     * 文本，内容没变就逐轮复用同一份（请求前缀稳定，服务商前缀缓存全程
     * 命中）；目录变了（重载模型/镜头开关切换）就原地重钉并打日志——历史
     * 保留，旧回复里的旧标签由门控静默丢弃，无需清上下文。LLM 模型切换的
     * "新开上下文"由集成方轮换上下文 id 实现（demo 见 MainActivity）。
     *
     * 返回协议文本（拼进人设那条 system 用）；null = 协议关闭或目录为空。
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
     * 多模态协议块全文——即请求里那条 system 消息的协议段（每上下文钉一次，
     * 见 [pinnedProtocolText]；发送时与人设拼接为一条 system）。设置页的
     * 「协议提示词」只读展示走这里，保证 UI 看到的就是模型收到的。
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
