package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡级绑定（P2-3）的 JSON 序列化锁：单字段缺省、脏数据容错、全空清除。
 */
class CardBindingsJsonTest {

    @Test
    fun `roundtrip keeps partial fields`() {
        val src = mapOf(
            "a.png" to CardBinding(voice = "ann", modelFile = "A.vrm", greetingIndex = 2),
            "b.json" to CardBinding(greetingIndex = 0),
            "c.png" to CardBinding(voice = "x"),
        )
        val round = CardBindingsJson.fromJson(CardBindingsJson.toJson(src))
        assertEquals(src, round)
    }

    @Test
    fun `blank bindings are dropped`() {
        val src = mapOf(
            "a.png" to CardBinding(),
            "b.png" to CardBinding(voice = "v"),
        )
        val round = CardBindingsJson.fromJson(CardBindingsJson.toJson(src))
        assertEquals(setOf("b.png"), round.keys)
    }

    @Test
    fun `corrupt json yields empty map`() {
        assertTrue(CardBindingsJson.fromJson(null).isEmpty())
        assertTrue(CardBindingsJson.fromJson("").isEmpty())
        assertTrue(CardBindingsJson.fromJson("{not json").isEmpty())
        assertTrue(CardBindingsJson.fromJson("[1,2,3]").isEmpty())
    }

    @Test
    fun `missing fields read as null`() {
        val raw = """{"a.png": {"voice": "ann"}}"""
        val map = CardBindingsJson.fromJson(raw)
        val b = map.getValue("a.png")
        assertEquals("ann", b.voice)
        assertNull(b.modelFile)
        assertNull(b.greetingIndex)
    }
}
