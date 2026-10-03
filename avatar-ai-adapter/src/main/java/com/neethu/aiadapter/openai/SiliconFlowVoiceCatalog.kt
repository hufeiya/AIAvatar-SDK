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

/** 下拉框用的一个音色选项：[value] 为请求/存储用完整引用，[label] 为显示名。 */
data class TtsVoiceOption(val value: String, val label: String)

/**
 * OpenAI 兼容网关的音色清单（硅基流动扩展端点 `GET {base}/audio/voice/list`，
 * 返回 `{"result":[…]}`，条目为 `{"uri":"<model>:<名>","name":"<名>",…}` 或字符串）。
 *
 * 这是「最好从接口获取音色」的尽力而为路径：**永不抛异常**——网络/鉴权/解析
 * 任何一步失败都返回空列表，调用方（设置页）合并静态兜底音色即可。实测空账号
 * 返回 `{"result":[]}`，故空列表是常态而非异常。
 */
class SiliconFlowVoiceCatalog(private val client: OkHttpClient = OkHttpClient()) {

    suspend fun fetch(baseUrl: String, apiKey: String): List<TtsVoiceOption> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + "/audio/voice/list")
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
    internal fun parse(body: String): List<TtsVoiceOption> = try {
        val result = Json.parseToJsonElement(body).jsonObject["result"]?.jsonArray ?: return emptyList()
        result.asSequence()
            .mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { TtsVoiceOption(it, it) }
                    is JsonObject -> {
                        val value = element.stringOf("uri") ?: element.stringOf("voice")
                            ?: element.stringOf("id") ?: return@mapNotNull null
                        TtsVoiceOption(value, element.stringOf("name") ?: value)
                    }
                    else -> null
                }
            }
            .distinctBy { it.value }
            .filter { it.value.isNotBlank() }
            .toList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}
