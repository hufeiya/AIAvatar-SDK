package com.neethu.aiadapter.api

/**
 * Text embedding seam for the long-term memory retrieval (docs/memory-feasibility.md
 * §4.2). Chat/TTS/ASR chains are untouched — only the memory feature calls this.
 *
 * Config is passed per call (not held by the adapter) so a key change applies
 * immediately without rebuilding any session; returning null/blank config is
 * the caller's signal to fall back to keyword retrieval.
 */
interface EmbeddingAdapter {
    /**
     * Embeds [texts] in one network round trip. Returns vectors in the same
     * order as the input (providers return per-item `index` fields — the
     * adapter is responsible for reordering).
     *
     * @throws IOException on HTTP/network failure or a non-JSON body (message
     *   carries a response preview, A.1 rule 11). Callers treat any embed
     *   failure as "this item is stored without a vector", never as data loss.
     */
    suspend fun embed(texts: List<String>, config: EmbeddingConfig): List<FloatArray>
}

/**
 * Connection parameters for an embeddings request. [baseUrl] includes the
 * version segment (e.g. `https://api.siliconflow.cn/v1`); the adapter appends
 * `/embeddings`. The demo's default model is `BAAI/bge-m3` (1024-dim,
 * SiliconFlow) — v1 locks the model: switching it invalidates every stored
 * vector (mixed-dimension DB), see memory-feasibility.md §8 risk 8.
 */
data class EmbeddingConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)
