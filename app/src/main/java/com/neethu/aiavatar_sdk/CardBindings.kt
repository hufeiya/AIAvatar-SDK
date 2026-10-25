package com.neethu.aiavatar_sdk

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject

/**
 * 卡级绑定（酒馆 P2-3，docs/tavern-companion-feasibility.md §5 P2-3）：卡片
 * 关联「音色 + VRM 模型 + 开场白选择」，切卡即整体换人。数据挂库索引
 * （demo_settings 的 `ai_card_bindings`），**不入卡本体**——卡文件保持与
 * SillyTavern 逐字节一致（CharacterCardStore 的铁律）。
 *
 * 绑定语义 = 隐式记忆：卡激活期间用户改的音色/模型/开场白自动写回绑定，
 * 重新激活该卡时整体恢复（OMate 的「换卡=换人」手感）。单字段允许为空
 * （用户没动过的维度不绑定，激活时保持全局设置）。
 */
data class CardBinding(
    /** 存储值口径同 AiChatPrefs.voice（完整引用或服务商短名；Edge 音色为目录 id）。 */
    val voice: String? = null,
    /** VRM/GLB 文件名（内置或导入同名空间）。 */
    val modelFile: String? = null,
    /** 开场白选择：0=默认 first_mes，1..n=alternateGreetings 下标；null=未选过。 */
    val greetingIndex: Int? = null,
) {
    val isBlank: Boolean get() = voice == null && modelFile == null && greetingIndex == null
}

/** [CardBinding] 与 prefs JSON 的互转（纯函数，JVM 可测；kotlinx.serialization
 *  与卡域先例 PresetCardImport 同款——org.json 在 JVM 单测不可用）。 */
object CardBindingsJson {

    private const val KEY_VOICE = "voice"
    private const val KEY_MODEL = "modelFile"
    private const val KEY_GREETING = "greetingIndex"

    fun toJson(bindings: Map<String, CardBinding>): String = buildJsonObject {
        for ((file, b) in bindings) {
            if (b.isBlank) continue
            put(file, buildJsonObject {
                b.voice?.let { put(KEY_VOICE, it) }
                b.modelFile?.let { put(KEY_MODEL, it) }
                b.greetingIndex?.let { put(KEY_GREETING, it) }
            })
        }
    }.toString()

    fun fromJson(raw: String?): Map<String, CardBinding> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val root = Json.parseToJsonElement(raw).jsonObject
            val out = LinkedHashMap<String, CardBinding>()
            for ((file, value) in root) {
                val o = value.jsonObject
                fun str(key: String): String? =
                    (o[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                out[file] = CardBinding(
                    voice = str(KEY_VOICE),
                    modelFile = str(KEY_MODEL),
                    greetingIndex = o[KEY_GREETING]?.jsonPrimitive?.content?.toIntOrNull(),
                )
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

/** 绑定表的可变门面（DemoUiState 持有；写穿 prefs）。 */
class CardBindings(private val prefs: SharedPreferences) {

    private var map: MutableMap<String, CardBinding> =
        CardBindingsJson.fromJson(prefs.getString(PREFS_KEY, null)).toMutableMap()

    fun forCard(fileName: String?): CardBinding? = fileName?.let { map[it] }

    /** 写回单卡绑定（字段级合并：null 字段保留原值）；[binding] 全空 = 清除。 */
    fun update(fileName: String, binding: CardBinding) {
        val merged = map[fileName].orEmpty().let { old ->
            CardBinding(
                voice = binding.voice ?: old.voice,
                modelFile = binding.modelFile ?: old.modelFile,
                greetingIndex = binding.greetingIndex ?: old.greetingIndex,
            )
        }
        if (merged.isBlank) {
            map.remove(fileName)
        } else {
            map[fileName] = merged
        }
        persist()
    }

    fun remove(fileName: String) {
        if (map.remove(fileName) != null) persist()
    }

    private fun persist() {
        prefs.edit().putString(PREFS_KEY, CardBindingsJson.toJson(map)).apply()
    }

    private fun CardBinding?.orEmpty(): CardBinding = this ?: CardBinding()

    companion object {
        private const val PREFS_KEY = "ai_card_bindings"
    }
}
