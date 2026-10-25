package com.neethu.orchestrator.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世界书匹配与注入（酒馆 P1-1）：关键词/正则/大小写/全半角/selective/
 * constant 语义 + 注入排序与字符预算。验收对齐
 * docs/tavern-companion-feasibility.md P1-1（①④在会话级测）。
 */
class LorebookInjectorTest {

    private fun entry(
        keys: List<String> = emptyList(),
        content: String = "c",
        order: Int = 100,
        constant: Boolean = false,
        selective: Boolean = false,
        secondary: List<String> = emptyList(),
        caseSensitive: Boolean = false,
        enabled: Boolean = true,
    ) = LorebookEntry(
        keys = keys, content = content, insertionOrder = order, constant = constant,
        selective = selective, secondaryKeys = secondary, caseSensitive = caseSensitive,
        enabled = enabled,
    )

    private fun inject(book: Lorebook, vararg texts: String, budget: Int = 1500): String? =
        LorebookInjector.inject(book, texts.toList(), LorebookInjector.Config(budgetChars = budget))

    // ── 匹配语义 ─────────────────────────────────────────────────────────

    @Test
    fun `keyword hit injects content`() {
        val book = Lorebook(entries = listOf(entry(keys = listOf("王冠"), content = "王冠是至宝")))
        val out = inject(book, "他把王冠戴在头上")
        assertTrue(out!!.contains("王冠是至宝"))
        assertTrue(out.startsWith("【世界设定】"))
    }

    @Test
    fun `no hit returns null`() {
        val book = Lorebook(entries = listOf(entry(keys = listOf("王冠"))))
        assertNull(inject(book, "今天天气不错"))
    }

    @Test
    fun `case insensitive by default - sensitive when flagged`() {
        val book = Lorebook(entries = listOf(entry(keys = listOf("Crown"), content = "c1")))
        assertNotNull(inject(book, "the crown shines"))
        val strict = Lorebook(entries = listOf(entry(keys = listOf("Crown"), content = "c1", caseSensitive = true)))
        assertNull(inject(strict, "the crown shines"))
        assertNotNull(inject(strict, "the Crown shines"))
    }

    @Test
    fun `fullwidth keys match halfwidth text`() {
        val book = Lorebook(entries = listOf(entry(keys = listOf("魔法学院"), content = "x")))
        // 全角感叹号/字母归一：文本里全角「ＭＡＧＩＣ」与键「magic」互认
        val fw = Lorebook(entries = listOf(entry(keys = listOf("ＭＡＧＩＣ"), content = "x")))
        assertNotNull(inject(fw, "he said MAGIC loudly"))
    }

    @Test
    fun `regex key syntax supported`() {
        val book = Lorebook(entries = listOf(entry(keys = listOf("/dra?gon/i"), content = "d")))
        assertNotNull(inject(book, "A DRAGON appeared"))
        val miss = Lorebook(entries = listOf(entry(keys = listOf("/dragonly/"), content = "d")))
        assertNull(inject(miss, "A DRAGON appeared"))
    }

    @Test
    fun `selective requires secondary key hit`() {
        val book = Lorebook(
            entries = listOf(
                entry(keys = listOf("魔法"), selective = true, secondary = listOf("禁咒"), content = "s"),
            ),
        )
        assertNull(inject(book, "他在学魔法")) // 只中主键
        assertNotNull(inject(book, "禁咒也是魔法的一种")) // 主+次
    }

    @Test
    fun `disabled entries never match - constant entries always in`() {
        val book = Lorebook(
            entries = listOf(
                entry(keys = listOf("王冠"), enabled = false),
                entry(constant = true, content = "常驻设定"),
            ),
        )
        val out = inject(book, "王冠在这里") // 关键词条目被禁用
        assertTrue(out!!.contains("常驻设定"))
        assertFalse(out.contains("王冠是"))
    }

    @Test
    fun `scan window only sees the last N messages`() {
        val book = Lorebook(entries = listOf(entry(keys = listOf("王冠"))))
        // scanDepth=2：只有最近两条参与扫描
        assertNull(inject(book, "王冠", "天气", "晚饭"))
        assertNotNull(inject(book, "天气", "王冠"))
    }

    // ── 注入形态 ─────────────────────────────────────────────────────────

    @Test
    fun `entries sorted by insertion_order`() {
        val book = Lorebook(
            entries = listOf(
                entry(keys = listOf("a"), content = "AAA", order = 200),
                entry(keys = listOf("b"), content = "BBB", order = 50),
            ),
        )
        val out = inject(book, "a b")!!
        val aIdx = out.indexOf("AAA")
        val bIdx = out.indexOf("BBB")
        assertTrue(bIdx in 0 until aIdx) // order 小者在前
    }

    @Test
    fun `budget trims matched entries whole - constants exempt`() {
        val long = "x".repeat(1200)
        val book = Lorebook(
            entries = listOf(
                entry(keys = listOf("a"), content = long, order = 10),
                entry(keys = listOf("b"), content = "BBBB", order = 20),
                entry(keys = listOf("z"), content = "ZZZ", order = 30, constant = true),
            ),
        )
        // 预算 1205：常驻 ZZZ(3) + 长条(1200) 装下后剩 2，BBBB(4) 整条跳过
        val out = inject(book, "a b z", budget = 1205)!!
        assertTrue(out.contains("ZZZ")) // 常驻恒注入
        assertTrue(out.contains(long)) // 预算内第一条装入
        assertFalse(out.contains("BBBB")) // 超预算整条跳过
    }

    @Test
    fun `null book or empty window returns null`() {
        assertNull(LorebookInjector.inject(null, listOf("text")))
        assertNull(LorebookInjector.inject(Lorebook(entries = emptyList()), listOf("text")))
        assertNull(LorebookInjector.inject(Lorebook(entries = listOf(entry(constant = true))), emptyList()))
    }
}
