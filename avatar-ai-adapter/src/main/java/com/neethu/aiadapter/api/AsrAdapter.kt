package com.neethu.aiadapter.api

import com.neethu.aiadapter.model.AsrConfig

/**
 * Pluggable speech-to-text backend (task 4: voice input).
 *
 * Contract mirrors [TtsAdapter]: one suspend call, audio bytes in, plain text
 * out. The demo feeds it a MediaRecorder take (AAC in an m4a container) right
 * after the user releases the push-to-talk button; the adapter implementation
 * owns the wire protocol (OpenAI-compatible `/audio/transcriptions` multipart).
 */
interface AsrAdapter {
    /**
     * Transcribes [audio] (encoded bytes) into text.
     *
     * @param audio the complete audio file bytes
     * @param mime  the encoding's MIME type (e.g. `audio/mp4`); implementations
     *   use it to pick a multipart filename/content-type the server accepts
     * @param config model + optional recognition hints
     * @return the recognized text (may be empty for silence)
     * @throws IOException on transport/HTTP errors or unparseable responses
     */
    suspend fun transcribe(audio: ByteArray, mime: String, config: AsrConfig): String
}
