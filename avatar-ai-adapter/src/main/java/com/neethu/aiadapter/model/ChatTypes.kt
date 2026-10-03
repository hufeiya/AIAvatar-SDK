package com.neethu.aiadapter.model

import kotlinx.serialization.json.JsonObject

/** Conversation message roles following the OpenAI chat-completions convention. */
enum class ChatRole { SYSTEM, USER, ASSISTANT }

/** A single conversation message sent to / received from an LLM. */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    /**
     * Images attached to this message as data URLs (`data:image/jpeg;base64,…`),
     * sent via the OpenAI multimodal `content` array (`image_url` parts).
     * Only meaningful on USER messages for vision-capable models; keep empty
     * for text-only models (they reject array-form content with 400).
     * Wire format only — history stores text alone, so images sent on one
     * turn are deliberately dropped from later requests.
     */
    val images: List<String> = emptyList(),
)

/**
 * Connection + sampling parameters for a chat-completions request.
 *
 * [baseUrl] should include the version segment, e.g. `https://api.openai.com/v1`
 * or `https://api.groq.com/openai/v1`. The adapter appends `/chat/completions`.
 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Float = 0.8f,
    val topP: Float = 1.0f,
    val maxTokens: Int = 1024,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Merged verbatim into the JSON request body (e.g. provider-specific knobs). */
    val extraBody: JsonObject? = null,
)

/** Streaming events emitted by [com.neethu.aiadapter.api.LlmAdapter]. */
sealed interface LlmStreamEvent {
    /** An incremental piece of the assistant reply text. */
    data class TextDelta(val text: String) : LlmStreamEvent

    /** The stream finished. [reason] is the provider finish reason, e.g. `stop`. */
    data class Finish(val reason: String?) : LlmStreamEvent

    /** The stream failed. Delivered as an event (the Flow also completes normally). */
    data class Error(val throwable: Throwable) : LlmStreamEvent
}

/** Audio container/encoding returned by a TTS engine. */
enum class TtsAudioFormat {
    /** Headerless 16-bit little-endian mono PCM (OpenAI `pcm` response format). */
    RAW_PCM_16LE,
    WAV,
    MP3,
    OGG,
    UNKNOWN,
}

/** Voice/engine parameters for a speech synthesis request. */
data class TtsConfig(
    val model: String,
    val voice: String,
    val speed: Float = 1.0f,
    /**
     * `wav` (widely supported), `mp3`, `pcm` (OpenAI-only raw 16-bit LE).
     * The [com.neethu.aiadapter.api.TtsAdapter] echoes this back in [TtsResult.format].
     */
    val responseFormat: String = "wav",
    /**
     * Requested output sample rate in Hz (OpenAI-compatible extension, e.g.
     * SiliconFlow `sample_rate`); `null` = provider default. Set this to the
     * wLipSync target rate (16 kHz) when the lip-sync pipeline expects
     * calibration-matched input — the fractional-resample path degrades MFCC
     * matching on non-integer ratios (measured, see docs/ai-layer-handoff.md).
     */
    val sampleRate: Int? = null,
    /** Sample rate of [TtsAudioFormat.RAW_PCM_16LE] results (OpenAI pcm is 24 kHz). */
    val rawPcmSampleRate: Int = 24_000,
    val extraParams: Map<String, String> = emptyMap(),
)

/** Synthesized audio bytes plus enough metadata to decode them. */
class TtsResult(
    val audio: ByteArray,
    val format: TtsAudioFormat,
    val rawPcmSampleRate: Int = 24_000,
)

/** Recognition parameters for a speech-to-text request. */
data class AsrConfig(
    /** Provider model id, e.g. `whisper-1` or SiliconFlow `Qwen/Qwen3-ASR-1.7B`. */
    val model: String,
    /**
     * ISO-639-1 language hint (whisper-style `language` form field, e.g. "zh");
     * `null` = let the provider auto-detect. Providers that don't take the
     * field simply ignore it, so leave it null unless the model needs it.
     */
    val language: String? = null,
    /** Optional vocabulary/terminology hint (whisper-style `prompt`); `null` = none. */
    val prompt: String? = null,
)
