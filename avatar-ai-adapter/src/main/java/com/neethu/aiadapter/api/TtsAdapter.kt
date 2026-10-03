package com.neethu.aiadapter.api

import com.neethu.aiadapter.model.TtsConfig
import com.neethu.aiadapter.model.TtsResult

/**
 * Pluggable text-to-speech backend.
 *
 * V1 contract is sentence-level synthesis: one call returns the complete audio
 * for [text]. Streaming sentence-by-sentence from a single connection is a
 * future extension (AIRI's `bidirectional-ws` transport).
 */
interface TtsAdapter {
    suspend fun synthesize(text: String, config: TtsConfig): TtsResult
}
