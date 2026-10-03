package com.neethu.orchestrator.card

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class CharacterCardParserTest {

    @Test
    fun `parses v2 card`() {
        val json = """
            {"spec":"chara_card_v2","spec_version":"2.0","data":{
              "name":"星野","description":"一位温柔的向导","personality":"耐心",
              "scenario":"深夜的图书馆","first_mes":"晚上好。",
              "alternate_greetings":["嗨！"],"mes_example":"<START>\n{{user}}: 你好\n{{char}}: 嗨",
              "system_prompt":"你是星野","post_history_instructions":"保持简短",
              "extensions":{"depth_prompt":{"prompt":".Extension preserved"}}}
            }
        """.trimIndent()
        val card = CharacterCardParser.fromJson(json)
        assertNotNull(card)
        assertEquals("chara_card_v2", card!!.spec)
        assertEquals("星野", card.name)
        assertEquals("一位温柔的向导", card.description)
        assertEquals("晚上好。", card.firstMessage)
        assertEquals(listOf("嗨！"), card.alternateGreetings)
        assertEquals("保持简短", card.postHistoryInstructions)
        assertNotNull(card.extensions)
    }

    @Test
    fun `parses v1 flat card`() {
        val card = CharacterCardParser.fromJson(
            """{"name":"阿达","description":"机器人","personality":"好奇","scenario":"实验室","first_mes":"醒来了？"}"""
        )
        assertNotNull(card)
        assertEquals("v1", card!!.spec)
        assertEquals("阿达", card.name)
        assertEquals("实验室", card.scenario)
    }

    @Test
    fun `returns null on garbage`() {
        assertNull(CharacterCardParser.fromJson("not json at all {"))
    }

    @Test
    fun `extracts card from png tEXt chara key`() {
        val cardJson = buildJsonObject {
            put("name", "PNG娘")
            put("description", "来自图片")
        }
        val png = buildTestPng(
            listOf("chara" to Base64.getEncoder().encodeToString(cardJson.toString().toByteArray()))
        )
        val card = CharacterCardParser.fromPng(png)
        assertNotNull(card)
        assertEquals("PNG娘", card!!.name)
        assertEquals("来自图片", card.description)
    }

    @Test
    fun `prefers ccv3 key over chara`() {
        val v3 = buildJsonObject {
            put("spec", "chara_card_v3")
            put("spec_version", "3.0")
            put("data", buildJsonObject { put("name", "V3角色") })
        }
        val v2 = buildJsonObject { put("name", "V2角色") }
        val png = buildTestPng(
            listOf(
                "chara" to Base64.getEncoder().encodeToString(v2.toString().toByteArray()),
                "ccv3" to Base64.getEncoder().encodeToString(v3.toString().toByteArray()),
            )
        )
        assertEquals("V3角色", CharacterCardParser.fromPng(png)!!.name)
    }

    @Test
    fun `png without metadata returns null`() {
        assertNull(CharacterCardParser.fromPng(buildTestPng(emptyList())))
    }

    @Test
    fun `parse dispatches by signature`() {
        val png = buildTestPng(emptyList())
        assertNull(CharacterCardParser.parse(png))
        val card = CharacterCardParser.parse("""{"name":"JSON卡"}""".toByteArray())
        assertEquals("JSON卡", card!!.name)
    }

    // ── helpers: minimal PNG container (no compression, CRCs zeroed) ──────

    private fun buildTestPng(textChunks: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(chunk("IHDR", ByteArray(13)))
        for ((keyword, value) in textChunks) {
            val keywordBytes = keyword.toByteArray(Charsets.US_ASCII)
            val textBytes = value.toByteArray(Charsets.ISO_8859_1)
            val data = ByteArray(keywordBytes.size + 1 + textBytes.size)
            System.arraycopy(keywordBytes, 0, data, 0, keywordBytes.size)
            System.arraycopy(textBytes, 0, data, keywordBytes.size + 1, textBytes.size)
            out.write(chunk("tEXt", data))
        }
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(byteArrayOf(
            (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
        ))
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        out.write(ByteArray(4)) // CRC — the parser does not verify
        return out.toByteArray()
    }
}
