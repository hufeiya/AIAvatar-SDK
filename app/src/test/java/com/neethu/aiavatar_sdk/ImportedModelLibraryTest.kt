package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 导入模型库的纯函数层（GLB 校验/文件名净化/防撞名）：导入外部 VRM 落
 * filesDir/vrms 的三条硬约束——不是 GLB 拒收、名字剥完为空拒收、导入模型
 * 绝不遮蔽内置模型（重名加序号）。Android 侧的落盘/扫描逻辑不走 JVM 单测。
 */
class ImportedModelLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** "glTF" magic + 最小长度余量（12 字节 header）。 */
    private val glbBytes = byteArrayOf(0x67, 0x6C, 0x54, 0x46) + ByteArray(20)

    // ── isValidGlb ────────────────────────────────────────────────────────

    @Test
    fun `glb magic accepted`() {
        assertTrue(ImportedModelLibrary.isValidGlb(glbBytes))
    }

    @Test
    fun `non-glb and short files rejected`() {
        assertFalse(ImportedModelLibrary.isValidGlb(ByteArray(32)))
        assertFalse(ImportedModelLibrary.isValidGlb("PNG...........".toByteArray() + ByteArray(20)))
        assertFalse(ImportedModelLibrary.isValidGlb(byteArrayOf(0x67, 0x6C, 0x54))) // 截断的 magic
        assertFalse(ImportedModelLibrary.isValidGlb(ByteArray(0)))
    }

    // ── sanitizeModelName ─────────────────────────────────────────────────

    @Test
    fun `strips path segments and keeps vrm extension`() {
        assertEquals("my model.vrm", ImportedModelLibrary.sanitizeModelName("/sdcard/Download/my model.vrm"))
        assertEquals("char.vrm", ImportedModelLibrary.sanitizeModelName("C:\\models\\char.vrm"))
        assertEquals("m.GLB", ImportedModelLibrary.sanitizeModelName("m.GLB"))
    }

    @Test
    fun `replaces illegal chars and appends missing extension`() {
        assertEquals("a_b.vrm", ImportedModelLibrary.sanitizeModelName("a:b.vrm"))
        assertEquals("模型.vrm", ImportedModelLibrary.sanitizeModelName("模型")) // 无后缀补 .vrm（中文不在白名单则整体替换）
        assertEquals("model.vrm", ImportedModelLibrary.sanitizeModelName("model"))
    }

    @Test
    fun `empty or path-only names rejected`() {
        assertNull(ImportedModelLibrary.sanitizeModelName(null))
        assertNull(ImportedModelLibrary.sanitizeModelName("   "))
        assertNull(ImportedModelLibrary.sanitizeModelName("/sdcard/"))
        assertNull(ImportedModelLibrary.sanitizeModelName(".."))
    }

    // ── resolveUniqueName ─────────────────────────────────────────────────

    @Test
    fun `unique name passes through when free`() {
        val dir = tmp.newFolder("a")
        assertEquals("A.vrm", ImportedModelLibrary.resolveUniqueName(dir, emptySet(), "A.vrm"))
    }

    @Test
    fun `collides against existing files with numbered suffixes`() {
        val dir = tmp.newFolder("b")
        assertTrue(File(dir, "A.vrm").createNewFile())
        assertEquals("A_1.vrm", ImportedModelLibrary.resolveUniqueName(dir, emptySet(), "A.vrm"))
        assertTrue(File(dir, "A_1.vrm").createNewFile())
        assertEquals("A_2.vrm", ImportedModelLibrary.resolveUniqueName(dir, emptySet(), "A.vrm"))
    }

    @Test
    fun `builtin names count as taken so imports never shadow builtins`() {
        val dir = tmp.newFolder("c")
        assertEquals(
            "B_1.vrm",
            ImportedModelLibrary.resolveUniqueName(dir, takenNames = setOf("B.vrm"), desired = "B.vrm"),
        )
    }
}
