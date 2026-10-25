package com.neethu.orchestrator.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书解析（酒馆 P1-1）：CCv2/v3 内嵌数组形态 + ST World Info 独立映射
 * 形态的双格式归一，以及容错语义（坏条目跳过、单字符串 keys、disable）。
 */
class LorebookParserTest {

    @Test
    fun `parses CCv2 embedded character_book array form`() {
        val json = """
        {
          "name": "设定集",
          "entries": [
            {
              "keys": ["王冠", "crown"],
              "content": "王冠是王国的至宝。",
              "insertion_order": 10,
              "enabled": true,
              "position": "before_char"
            },
            {
              "keys": ["魔法"],
              "secondary_keys": ["禁咒"],
              "selective": true,
              "content": "禁咒魔法被封印。",
              "insertion_order": 20,
              "constant": false
            }
          ]
        }
        """.trimIndent()
        val book = LorebookParser.parse(json)
        assertNotNull(book)
        assertEquals("设定集", book!!.name)
        assertEquals(2, book.entries.size)
        val first = book.entries[0]
        assertEquals(listOf("王冠", "crown"), first.keys)
        assertEquals("王冠是王国的至宝。", first.content)
        assertEquals(10, first.insertionOrder)
        assertTrue(first.enabled)
        assertEquals("before_char", first.position)
        val second = book.entries[1]
        assertTrue(second.selective)
        assertEquals(listOf("禁咒"), second.secondaryKeys)
    }

    @Test
    fun `parses ST World Info standalone map form`() {
        val json = """
        {
          "entries": {
            "0": {
              "uid": 0,
              "key": ["dragon", "龙"],
              "keysecondary": [],
              "comment": "Dragons",
              "content": "Dragons hoard gold.",
              "order": 100,
              "position": 0,
              "disable": false,
              "constant": false
            },
            "1": {
              "uid": 1,
              "key": ["always"],
              "content": "Constant entry.",
              "constant": true,
              "disable": false
            },
            "2": {
              "uid": 2,
              "key": ["off"],
              "content": "Disabled entry.",
              "disable": true
            }
          }
        }
        """.trimIndent()
        val book = LorebookParser.parse(json)
        assertNotNull(book)
        assertEquals(3, book!!.entries.size)
        val dragon = book.entries.first { it.content.contains("Dragons") }
        assertEquals(listOf("dragon", "龙"), dragon.keys)
        assertEquals(100, dragon.insertionOrder)
        assertEquals("before_char", dragon.position) // position:0 归一
        val constant = book.entries.first { it.constant }
        assertTrue(constant.enabled)
        val disabled = book.entries.first { it.content.contains("Disabled") }
        assertFalse(disabled.enabled)
    }

    @Test
    fun `position 1 maps to after_char`() {
        val json = """{"entries":{"0":{"key":["x"],"content":"c","position":1}}}"""
        val book = LorebookParser.parse(json)!!
        assertEquals("after_char", book.entries.single().position)
    }

    @Test
    fun `single-string keys tolerated`() {
        val json = """{"entries":[{"keys":"王冠","content":"c"}]}"""
        val book = LorebookParser.parse(json)!!
        assertEquals(listOf("王冠"), book.entries.single().keys)
    }

    @Test
    fun `broken entries skipped without killing the book`() {
        val json = """
        {"entries":[
          {"keys":42,"content":"bad keys type"},
          {"keys":["ok"],"content":"good"},
          {"content":"no keys at all"}
        ]}
        """.trimIndent()
        val book = LorebookParser.parse(json)!!
        assertEquals(1, book.entries.size)
        assertEquals("good", book.entries.single().content)
    }

    @Test
    fun `non-lorebook json returns null`() {
        assertNull(LorebookParser.parse("""{"name":"not a book"}"""))
        assertNull(LorebookParser.parse("totally not json"))
    }

    @Test
    fun `parser wires character_book onto the card`() {
        val cardJson = """
        {
          "spec": "chara_card_v2",
          "data": {
            "name": "Aria",
            "description": "mage",
            "character_book": {
              "entries": [
                {"keys": ["staff"], "content": "Her staff glows blue.", "constant": false}
              ]
            }
          }
        }
        """.trimIndent()
        val card = CharacterCardParser.fromJson(cardJson)!!
        assertNotNull(card.characterBook)
        assertEquals(1, card.characterBook!!.entries.size)
        assertEquals("Her staff glows blue.", card.characterBook!!.entries.single().content)
    }
}
