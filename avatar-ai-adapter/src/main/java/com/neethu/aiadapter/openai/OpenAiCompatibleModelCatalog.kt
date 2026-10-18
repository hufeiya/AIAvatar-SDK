package com.neethu.aiadapter.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * `/models` 目录的一个条目：[id] 必有，其余元数据**各家给什么收什么**——
 * 火山 Ark 带 [domain]（LLM/VLM/Embedding/ImageGeneration…）与 [status]
 * （Shutdown/Retiring），OpenRouter 带 [inputModalities]/[outputModalities]
 * （architecture.input_modalities，含 image=真视觉），其余（硅基流动/百炼/
 * DeepSeek）只有 id。
 */
data class ModelCatalogEntry(
    val id: String,
    val domain: String? = null,
    val status: String? = null,
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
)

/**
 * OpenAI 兼容网关的大模型清单（`GET {base}/models`）：设置页模型下拉的
 * 实时获取路径（模型版本实时更新，内存缓存归调用方管理）。**永不抛异常**
 * ——网络/鉴权/解析任何一步失败都返回空列表，调用方回退静态清单（与
 * [SiliconFlowVoiceCatalog] 同一容错语义；实测 404/空目录都是常态失败）。
 */
class OpenAiCompatibleModelCatalog(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun fetch(baseUrl: String, apiKey: String): List<ModelCatalogEntry> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/models")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string() ?: return@withContext emptyList()
                    parse(body)
                }
            } catch (_: IOException) {
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

    /** 防御式解析：结构对不上就空列表，绝不向上抛。 */
    internal fun parse(body: String): List<ModelCatalogEntry> = try {
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: return emptyList()
        data.asSequence()
            .mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ModelCatalogEntry(
                    id = id,
                    domain = obj.stringOf("domain"),
                    status = obj.stringOf("status"),
                    inputModalities = obj.stringListOf("architecture", "input_modalities"),
                    outputModalities = obj.stringListOf("architecture", "output_modalities"),
                )
            }
            .distinctBy { it.id }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun JsonObject.stringOf(key: String): String? {
        // ⚠ JsonNull 也是 JsonPrimitive（显式 "status":null）——先排除再取 content
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (primitive is kotlinx.serialization.json.JsonNull) return null
        return primitive.content.takeIf { it.isNotBlank() }
    }

    /** architecture.input_modalities / output_modalities（嵌套一层）防御式读取。 */
    private fun JsonObject.stringListOf(parent: String, key: String): List<String>? =
        ((this[parent] as? JsonObject)?.get(key) as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.takeIf { it.isNotEmpty() }
}
