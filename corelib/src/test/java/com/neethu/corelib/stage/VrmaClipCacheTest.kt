package com.neethu.corelib.stage

import com.neethu.corelib.internal.VrmaParser.VrmaAnimation
import com.neethu.corelib.stage.internal.VrmaClipCache
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VRMA 只读缓存的纯 JVM 锁（P0 单测项：多 runtime 状态隔离）——
 * 同源共享同一实例（省 N-1 份解析与内存）、异源互不串台、
 * 解析失败负缓存、clear 后可重试。
 *
 * 缓存是纯记忆化器（parse 由调用方注入），测试用假解析函数直接验证
 * 缓存语义，不触真实 GLB 解析。
 */
class VrmaClipCacheTest {

    private fun animation(duration: Float) = VrmaAnimation(
        duration = duration,
        humanoidTracks = emptyList(),
        expressionTracks = emptyList(),
        restHipsPosition = floatArrayOf(0f, 1f, 0f),
    )

    @Test
    fun `same source shares one parsed instance`() {
        val cache = VrmaClipCache()
        var parseCalls = 0
        val fake: (String) -> VrmaAnimation? = { _ ->
            parseCalls++
            animation(2.5f)
        }
        val a = cache.getOrParse("animations/Idle.vrma", fake)
        val b = cache.getOrParse("animations/Idle.vrma", fake)
        assertSame(a, b)            // 同实例：多 runtime 共享的依据
        assertTrue(parseCalls == 1) // 第二次零解析
        assertTrue(cache.size == 1)
    }

    @Test
    fun `different sources stay isolated`() {
        val cache = VrmaClipCache()
        val a = cache.getOrParse("a.vrma") { animation(1f) }
        val b = cache.getOrParse("b.vrma") { animation(2f) }
        assertNotSame(a, b)
        assertTrue(a!!.duration == 1f)
        assertTrue(b!!.duration == 2f)
        assertTrue(cache.size == 2)
    }

    @Test
    fun `parse failure is negatively cached until clear`() {
        val cache = VrmaClipCache()
        var fail = true
        val flaky: (String) -> VrmaAnimation? = { _ ->
            if (fail) null else animation(3f)
        }
        assertNull(cache.getOrParse("bad.vrma", flaky))
        // 失败不自动重试（负缓存：坏文件不反复 parse）
        fail = false
        assertNull(cache.getOrParse("bad.vrma", flaky))
        // clear 后可重试恢复
        cache.clear()
        val recovered = cache.getOrParse("bad.vrma", flaky)
        assertTrue(recovered != null && recovered.duration == 3f)
    }

    @Test
    fun `throwing parse surfaces as null and does not crash the cache`() {
        val cache = VrmaClipCache()
        assertNull(
            cache.getOrParse("missing.vrma") {
                throw java.io.FileNotFoundException("missing.vrma")
            }
        )
        assertNull(
            cache.getOrParse("missing.vrma") { throw IllegalStateException("no read") }
        )
        assertFalse(cache.size == 0) // 负缓存占位
    }
}
