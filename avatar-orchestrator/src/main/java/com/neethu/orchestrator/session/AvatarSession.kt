package com.neethu.orchestrator.session

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
) {

    data class Options(
        /** Concurrent TTS requests (AIRI default: 4). */
        val ttsMaxConcurrent: Int = 4,
        val chunkerOptions: SentenceChunker.Options = SentenceChunker.Options(),
        val enableFaceDriving: Boolean = true,
        val assembler: SystemPromptAssembler = SystemPromptAssembler(),
        /** Append the multimodal-tag protocol to every system prompt. */
        val protocolInstructions: Boolean = true,
        /** Let `<act:…>` tags play gestures from the action catalog. */
        val enableLlmGestures: Boolean = true,
        /** Let `<cam:…>` tags switch the preset camera framing. */
        val enableLlmCamera: Boolean = true,
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

    private val queue: PlaybackQueue = playbackQueue ?: AudioTrackPlaybackQueue()
    private val pipeline = SpeechPipeline(scope, tts, queue, lipSyncProcessor, options.ttsMaxConcurrent)
    private val extractor: TagExtractor = InlineTagExtractor()
    private val chunker = SentenceChunker(options.chunkerOptions)
    private val store: ConversationStore = InMemoryConversationStore()
    private val replyBuffer = StringBuilder()
    private var turnJob: Job? = null

    init {
        lipSyncProcessor?.let { lsp ->
            if (lsp is WlipsyncLipSyncProcessor) faceDriver?.setPhonemeLayout(lsp.vowelLayout)
        }
        pipeline.listener = object : SpeechPipeline.Listener {
            override fun onSentenceFailed(sequence: Int, text: String, error: Throwable) {
                Log.e("AvatarSession", "sentence #$sequence failed", error)
                emit(AvatarEvent.SentenceFailed(sequence, text, error.message ?: "TTS failed"))
            }
        }
        queue.listener = object : PlaybackQueue.Listener {
            override fun onPlaybackStarted(item: PlaybackItem) {
                queue.active()?.let { faceDriver?.onPlaybackStarted(item, it) }
                scope.launch(Dispatchers.Main.immediate) {
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
                    "clip #${item.sequence} pcm=${"%.2f".format(item.pcm.size.toFloat() / item.sampleRateHz)}s",
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

    /** Fire-and-forget user turn. Emits [AvatarEvent]s on [events]. */
    fun send(text: String) {
        if (turnJob?.isActive == true) interrupt("superseded")
        val llmCfg = llmConfig ?: run {
            emit(AvatarEvent.TurnFailed(IllegalStateException("llmConfig not set"))); return
        }
        val ttsCfg = ttsConfig ?: run {
            emit(AvatarEvent.TurnFailed(IllegalStateException("ttsConfig not set"))); return
        }
        turnJob = scope.launch {
            _phase.value = ConversationPhase.THINKING
            extractor.reset()
            chunker.reset()
            replyBuffer.setLength(0)
            pipeline.beginTurn()
            store.appendUser(text)
            try {
                llm.streamChat(buildRequestMessages(), llmCfg).collect { event ->
                    when (event) {
                        is LlmStreamEvent.TextDelta -> handleDelta(event.text, ttsCfg)
                        is LlmStreamEvent.Finish -> Unit
                        is LlmStreamEvent.Error -> throw event.throwable
                    }
                }
                // Drain the extractor tail, then any chunker remainder.
                val tail = extractor.flush()
                if (tail.cleanText.isNotEmpty()) {
                    chunker.feed(tail.cleanText).forEach { submitSentence(it, ttsCfg) }
                }
                chunker.flush().forEach { submitSentence(it, ttsCfg) }
                pipeline.endTurn()
                pipeline.awaitTurnComplete()
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
    suspend fun sendAndAwait(text: String) {
        send(text)
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
            pipeline.beginTurn()
            try {
                val tagged = extractor.feed(text)
                val tail = extractor.flush()
                dispatchCues(tagged.cues + tail.cues)
                (chunker.feed(tagged.cleanText + tail.cleanText) + chunker.flush())
                    .forEach { submitSentence(it, ttsCfg) }
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
        dispatchCues(result.cues)
        if (result.cleanText.isNotEmpty()) {
            chunker.feed(result.cleanText).forEach { submitSentence(it, ttsCfg) }
        }
    }

    /**
     * Multimodal dispatch (docs/ai-layer-handoff.md §7.4): cues fire the
     * moment the LLM emits them (AIRI semantics — reactions read as fast);
     * unknown names are silently dropped, never surfaced as errors.
     */
    private fun dispatchCues(cues: List<TagCue>) {
        for (cue in cues) when (cue) {
            is TagCue.Emotion -> {
                val emotion = EmotionCue(cue.name, cue.intensity)
                faceDriver?.applyEmotion(emotion)
                emit(AvatarEvent.EmotionChanged(emotion))
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
        emit(AvatarEvent.SentenceQueued(sequence, sentence))
    }

    private fun buildRequestMessages(): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        // Sections of the protocol block that cannot run are omitted so the
        // prompt never advertises a tag the session would drop.
        val cameras =
            if (options.enableLlmCamera && controller != null) SystemPromptAssembler.DEFAULT_CAMERA_TAGS
            else emptyList()
        val actions =
            if (gestureDriver != null) actionCatalog.map { it.tag to it.label }
            else emptyList()
        val system = buildString {
            append(systemPrompt.trim())
            if (options.protocolInstructions) {
                if (isNotEmpty()) append("\n\n")
                append(options.assembler.multimodalProtocolBlock(cameras = cameras, actions = actions))
            }
        }
        if (system.isNotEmpty()) list += ChatMessage(ChatRole.SYSTEM, system)
        list += store.messages()
        return list
    }

    private fun emit(event: AvatarEvent) {
        _events.tryEmit(event)
    }

    companion object {
        /** `<cam:…>` tag → preset shot (tag values are the enum names lowercased). */
        private val CAMERA_SHOTS: Map<String, CameraShot> = mapOf(
            "close_up" to CameraShot.CLOSE_UP,
            "medium_shot" to CameraShot.MEDIUM_SHOT,
            "full_shot" to CameraShot.FULL_SHOT,
            "long_shot" to CameraShot.LONG_SHOT,
            "over_shoulder" to CameraShot.OVER_SHOULDER,
        )
    }
}
