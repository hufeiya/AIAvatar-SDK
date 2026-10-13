package com.neethu.aiadapter.openai

import com.neethu.aiadapter.api.EmbeddingAdapter
import com.neethu.aiadapter.api.EmbeddingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * OpenAI-compatible `POST {baseUrl}/embeddings` adapter (docs/memory-feasibility.md
 * §4.2): `{"model": …, "input": [ … ]}` in, `{"data": [{"index": 0, "embedding":
 * […]}]}` out. Works with SiliconFlow (`BAAI/bge-m3`, 1024-dim) and any
 * OpenAI-shaped gateway. Volcano Ark / OpenRouter don't expose this endpoint —
 * integrators without an embeddings provider simply don't wire an adapter and
 * the memory layer falls back to keyword retrieval.
 */
class OpenAiCompatibleEmbeddingAdapter(
    private val client: OkHttpClient = OkHttpClient(),
) : EmbeddingAdapter {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun embed(texts: List<String>, config: EmbeddingConfig): List<FloatArray> =
        withContext(Dispatchers.IO) {
            require(texts.isNotEmpty()) { "embed() needs at least one text" }
            val endpoint = config.baseUrl.trimEnd('/') + "/embeddings"
            val body = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildJsonObject {
                    put("model", JsonPrimitive(config.model))
                    put("input", buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } })
                },
            )
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // 网关失败也可能是 200 的 HTML 页（A.1 第 11 条同源教训）：
                    // 非 2xx 一律带响应预览报错，真实原因直接进堆栈
                    throw IOException("Embedding HTTP ${response.code}: ${text.take(500)}")
                }
                val parsed = runCatching { json.parseToJsonElement(text).jsonObject }
                    .getOrElse {
                        throw IOException(
                            "Embedding returned non-JSON body (HTTP ${response.code}, " +
                                "content-type=${response.header("Content-Type").orEmpty()}): " +
                                text.take(120)
                        )
                    }
                val data = parsed["data"]?.jsonArray
                    ?: throw IOException("Embedding response has no 'data' array: ${text.take(200)}")
                // 按 index 归位（服务端不保证顺序），index 缺失按数组序兜底
                val vectors = arrayOfNulls<FloatArray>(data.size)
                data.forEachIndexed { fallbackIndex, element ->
                    val obj = element.jsonObject
                    val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: fallbackIndex
                    val vec = obj["embedding"]?.jsonArray
                        ?: throw IOException("Embedding item #$index has no 'embedding' array: ${text.take(200)}")
                    vectors[index] = FloatArray(vec.size) { vec[it].jsonPrimitive.float }
                }
                vectors.map { it ?: throw IOException("Embedding response left a null slot (bad index?)") }
            }
        }
}
