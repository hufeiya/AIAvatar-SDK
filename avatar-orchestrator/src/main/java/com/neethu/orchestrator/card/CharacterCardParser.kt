package com.neethu.orchestrator.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.InflaterInputStream

/**
 * SillyTavern Character Card parser.
 *
 * Supported inputs:
 *  - JSON text: V3 (`spec: "chara_card_v3"`), V2 (`spec: "chara_card_v2"`)
 *    and V1 (flat fields)
 *  - PNG images with embedded card metadata: SillyTavern's `chara` tEXt key
 *    (base64 JSON) as well as AIRI's `ccv3` key; zTXt (zlib) is supported too
 *
 * Unknown fields are preserved in [CharacterCard.extensions] where applicable
 * (forward compatibility, mirroring AIRI's `objectWithRest` behavior).
 */
object CharacterCardParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Parse a card from raw bytes: PNG (by signature) or UTF-8 JSON. */
    fun parse(bytes: ByteArray): CharacterCard? =
        if (startsWithPngSignature(bytes)) fromPng(bytes) else fromJson(String(bytes, Charsets.UTF_8))

    // ── JSON ──────────────────────────────────────────────────────────────

    fun fromJson(text: String): CharacterCard? {
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            when (root["spec"]?.jsonPrimitive?.content) {
                "chara_card_v3", "chara_card_v2" -> fromDataObject(
                    root["data"]?.jsonObject ?: return null,
                    spec = root["spec"]!!.jsonPrimitive.content,
                )
                else -> {
                    if (root["data"]?.jsonObjectOrNull() != null) {
                        fromDataObject(root["data"]!!.jsonObject, spec = "v2-ish")
                    } else {
                        fromFlatObject(root)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fromDataObject(data: JsonObject, spec: String): CharacterCard = CharacterCard(
        name = data.str("name") ?: "",
        description = data.str("description") ?: "",
        personality = data.str("personality") ?: "",
        scenario = data.str("scenario") ?: "",
        firstMessage = data.str("first_mes") ?: data.str("firstMes") ?: "",
        alternateGreetings = data["alternate_greetings"]?.jsonArrayOrNull()
            ?.mapNotNull { it.jsonPrimitiveOrNull()?.content } ?: emptyList(),
        messageExample = data.str("mes_example") ?: "",
        systemPrompt = data.str("system_prompt") ?: "",
        postHistoryInstructions = data.str("post_history_instructions") ?: "",
        creator = data.str("creator") ?: "",
        creatorNotes = data.str("creator_notes") ?: "",
        characterVersion = data.str("character_version") ?: "",
        tags = data["tags"]?.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitiveOrNull()?.content } ?: emptyList(),
        spec = spec,
        extensions = data["extensions"]?.jsonObjectOrNull(),
    )

    /** Character Card V1: flat fields, no `data` wrapper. */
    private fun fromFlatObject(root: JsonObject): CharacterCard? {
        val name = root.str("name") ?: return null
        return CharacterCard(
            name = name,
            description = root.str("description") ?: "",
            personality = root.str("personality") ?: "",
            scenario = root.str("scenario") ?: "",
            firstMessage = root.str("first_mes") ?: "",
            messageExample = root.str("mes_example") ?: "",
            creatorNotes = root.str("creatorcomment") ?: "",
            characterVersion = root.str("character_version") ?: "",
            tags = root["tags"]?.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitiveOrNull()?.content } ?: emptyList(),
            spec = "v1",
            extensions = root["extensions"]?.jsonObjectOrNull(),
        )
    }

    // ── PNG ───────────────────────────────────────────────────────────────

    /**
     * Extract card metadata from a PNG's tEXt/zTXt chunks.
     * Checks AIRI's `ccv3` key first, then SillyTavern's `chara`.
     * Returns null when the PNG carries no readable card.
     */
    fun fromPng(bytes: ByteArray): CharacterCard? {
        if (!startsWithPngSignature(bytes)) return null
        val texts = readPngTextChunks(bytes)
        for (key in listOf("ccv3", "chara")) {
            val payload = texts[key] ?: continue
            val decoded = try {
                String(Base64.getDecoder().decode(payload.trim()), Charsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                continue
            }
            fromJson(decoded)?.let { return it }
        }
        return null
    }

    /** All tEXt/zTXt keyword→value pairs in a PNG, in file order. */
    fun readPngTextChunks(png: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var pos = 8 // skip signature
        while (pos + 8 <= png.size) {
            val length = readIntBE(png, pos)
            val type = String(png, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            if (dataStart + length + 4 > png.size) break
            when (type) {
                "tEXt" -> {
                    val nul = png.indexOf(0, dataStart, dataStart + length)
                    if (nul > dataStart) {
                        val keyword = String(png, dataStart, nul - dataStart, Charsets.US_ASCII)
                        val text = String(png, nul + 1, dataStart + length - (nul + 1), Charsets.ISO_8859_1)
                        out.putIfAbsent(keyword, text)
                    }
                }
                "zTXt" -> {
                    val nul = png.indexOf(0, dataStart, dataStart + length)
                    if (nul > dataStart && dataStart + length - nul > 2) {
                        val keyword = String(png, dataStart, nul - dataStart, Charsets.US_ASCII)
                        try {
                            // compression method byte (0 = zlib) then the deflate stream
                            val inflated = InflaterInputStream(ByteArrayInputStream(png, nul + 2, dataStart + length - (nul + 2)))
                                .use { it.readBytes() }
                            out.putIfAbsent(keyword, String(inflated, Charsets.ISO_8859_1))
                        } catch (_: Exception) {
                        }
                    }
                }
                "IEND" -> break
            }
            pos = dataStart + length + 4 // skip data + CRC
        }
        return out
    }

    private fun startsWithPngSignature(bytes: ByteArray): Boolean =
        bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    private fun readIntBE(bytes: ByteArray, pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)

    private fun ByteArray.indexOf(value: Byte, from: Int, to: Int): Int {
        for (i in from until to) if (this[i] == value) return i
        return -1
    }

    // ── tolerant JSON helpers ─────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun kotlinx.serialization.json.JsonElement?.jsonObjectOrNull(): JsonObject? =
        this as? JsonObject

    private fun kotlinx.serialization.json.JsonElement?.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? =
        this as? kotlinx.serialization.json.JsonArray

    private fun kotlinx.serialization.json.JsonElement?.jsonPrimitiveOrNull(): kotlinx.serialization.json.JsonPrimitive? =
        this as? kotlinx.serialization.json.JsonPrimitive
}
