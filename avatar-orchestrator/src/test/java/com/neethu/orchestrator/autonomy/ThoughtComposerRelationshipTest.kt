package com.neethu.orchestrator.autonomy

import com.neethu.corelib.Lang
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问候文本的关系扩展（酒馆 P3-1）：纪念日优先、专属称呼槽、占位符替换
 * 干净（无残留 `{n}`/`{y}`）。
 */
class ThoughtComposerRelationshipTest {

    private val composer = ThoughtComposer(Lang.ZH, kotlin.random.Random(42))

    @Test
    fun `anniversary pool wins over everything and substitutes year`() {
        val text = take(composer.greetingText(hour = 9, memoryFact = "你养了只猫", anniversaryYears = 2))
        assertTrue("周年数字必须替换: $text", text.contains("2"))
        assertTrue("必须来自纪念池: $text", text.contains("纪念") || text.contains("相识") || text.contains("年前"))
        assertTrue("占位符残留: $text", !text.contains("{y}") && !text.contains("{n}") && !text.contains("{m}"))
    }

    @Test
    fun `nickname slot substitutes and never leaks placeholder`() {
        repeat(20) {
            val text = take(composer.greetingText(hour = 20, memoryFact = null, nickname = "阿岚"))
            assertTrue("占位符残留: $text", !text.contains("{n}"))
        }
    }

    @Test
    fun `memory fact still preferred over nickname`() {
        val text = take(composer.greetingText(hour = 20, memoryFact = "你下周要考试", nickname = "阿岚"))
        assertTrue("记忆槽应优先: $text", text.contains("考试"))
    }

    @Test
    fun `anniversary thought mentions anniversary`() {
        val thought = composer.greetingThought(anniversaryYears = 1)
        assertTrue(thought.contains("纪念") || thought.contains("1年"))
        assertTrue(!thought.contains("{y}"))
    }

    @Test
    fun `plain greeting without any relationship input unchanged`() {
        val text = composer.greetingText(hour = 9, memoryFact = null)
        assertTrue(text == null || text.isNotEmpty())
    }
}

/** JUnit4 断言不回传值，测试里取非空副本用。 */
private fun take(text: String?): String = text ?: error("问候文本为 null")
