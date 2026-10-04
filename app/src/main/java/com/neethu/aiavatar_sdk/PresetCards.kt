package com.neethu.aiavatar_sdk

import android.content.Context
import com.neethu.orchestrator.card.CharacterCard
import com.neethu.orchestrator.card.CharacterCardParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 预置人物卡（APK 内 `assets/cards`）：asset 路径 + 解析出的 [CharacterCard]。 */
internal data class PresetCard(
    val assetPath: String,
    val card: CharacterCard,
)

/**
 * 预置人物卡目录读取：`assets/cards` 下的 PNG 卡（官方 SillyTavern 卡 + 按
 * 官方 V3 规范制作的原创卡）。逐个解析、坏卡跳过；解析不出元数据的文件不进
 * grid，也不会被选中。
 */
internal class PresetCardLibrary(private val context: Context) {

    /** 预置卡列表，按文件名排序；全部不可解析时为空。 */
    fun load(): List<PresetCard> {
        val assets = context.assets
        val names = try {
            assets.list(PRESET_DIR)?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        return names.filter { it.endsWith(".png") }.sorted().mapNotNull { name ->
            val path = "$PRESET_DIR/$name"
            try {
                assets.open(path).use { stream ->
                    val card = CharacterCardParser.parse(stream.readBytes())
                    card?.takeIf { it.name.isNotBlank() }?.let { PresetCard(path, it) }
                }
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 读取一张预置卡的原始 bytes（导入落盘用）；不可读返回 null。 */
    fun readBytes(assetPath: String): ByteArray? = try {
        context.assets.open(assetPath).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val PRESET_DIR = "cards"
    }
}

/**
 * 预置卡导入去重（纯 JSON 逻辑，单测覆盖）：点选预置卡把它导入 [CardLibrary]
 * 落盘，映射 assetPath→fileName 持久化。映射指向的文件被用户删掉后自动失效，
 * 下次点选重新导入。
 */
internal object PresetCardImport {

    /**
     * 决定一次点选是「复用已导入文件」还是「需要重新导入」。
     * @param mapJson 持久化的映射（`ai_preset_cards`），空/损坏视为空映射。
     * @param assetPath 被点选的预置卡 asset 路径。
     * @param existingFileNames 当前卡库里的文件名集合（磁盘为准）。
     * @return (已导入的文件名或 null=需要导入, 更新后的映射 JSON)。
     */
    fun resolve(
        mapJson: String?,
        assetPath: String,
        existingFileNames: Set<String>,
    ): Pair<String?, String> {
        val map = parse(mapJson).toMutableMap()
        val mapped = map[assetPath]
        if (mapped != null && mapped in existingFileNames) return mapped to rebuild(map)
        if (mapped != null && mapped !in existingFileNames) map.remove(assetPath)
        return null to rebuild(map)
    }

    /** 导入成功后记录映射；[fileName] 必须已真实存在于卡库。 */
    fun record(mapJson: String?, assetPath: String, fileName: String): String {
        val map = parse(mapJson).toMutableMap()
        map[assetPath] = fileName
        return rebuild(map)
    }

    /** 落盘文件名反查 asset 路径；null = 无此映射（手工导入的卡）。 */
    fun reverseGet(mapJson: String?, fileName: String?): String? {
        if (fileName == null) return null
        return parse(mapJson).entries.firstOrNull { it.value == fileName }?.key
    }

    private fun parse(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            buildMap {
                for ((key, value) in obj) {
                    runCatching { put(key, value.jsonPrimitive.content) }
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun rebuild(map: Map<String, String>): String =
        buildJsonObject { map.forEach { (k, v) -> put(k, v) } }.toString()
}

/**
 * 人物卡提示词的人工编辑覆盖（纯 JSON 逻辑，单测覆盖）：设置页把卡片人设
 * 改成自定义文本后按卡片文件名持久化；未编辑过的卡走卡片默认人设。删除
 * 对应键 = 还原默认。
 */
internal object CardPromptOverrides {

    /** [fileName] 卡的覆盖文本；null = 无覆盖（用卡片默认人设）。 */
    fun get(json: String?, fileName: String?): String? {
        if (json.isNullOrBlank() || fileName == null) return null
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            obj[fileName]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** 写入/清除（[text] 为空串或 null 即清除）覆盖，返回新的映射 JSON。 */
    fun set(json: String?, fileName: String, text: String?): String {
        val obj = try {
            if (json.isNullOrBlank()) JsonObject(emptyMap())
            else Json.parseToJsonElement(json).jsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
        val entries = obj.toMutableMap()
        if (text.isNullOrBlank()) entries.remove(fileName)
        else entries[fileName] = kotlinx.serialization.json.JsonPrimitive(text)
        return buildJsonObject { entries.forEach { (k, v) -> put(k, v) } }.toString()
    }
}
