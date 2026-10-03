package com.neethu.aiavatar_sdk.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频模式纯逻辑：抓拍环形缓存（取最清晰/容量滚动）、拉普拉斯清晰度
 * （平坦≈0/边缘大）、视觉关键词命中。
 */
class VideoPureTest {

    // ── SnapshotRingBuffer ────────────────────────────────────────────────

    @Test
    fun `ring keeps only the newest N frames`() {
        val ring = SnapshotRingBuffer(capacity = 3)
        fun frame(i: Int, sharp: Float) =
            SnapshotFrame("url$i", i * 100, sharp, atMs = i * 500L)
        ring.push(frame(1, 1f))
        ring.push(frame(2, 2f))
        ring.push(frame(3, 3f))
        ring.push(frame(4, 4f))
        assertEquals(3, ring.size())
        // 最早的 #1（atMs=500）被挤出，最新是 #4（atMs=2000）
        assertEquals(2000L, ring.newest()!!.atMs)
        assertTrue(ring.framesAll().none { it.atMs == 500L })
    }

    @Test
    fun `best returns the sharpest frame in the window`() {
        val ring = SnapshotRingBuffer(capacity = 3)
        ring.push(SnapshotFrame("blur", 10_000, sharpness = 3.1f, atMs = 1))
        ring.push(SnapshotFrame("sharp", 12_000, sharpness = 9.4f, atMs = 2))
        ring.push(SnapshotFrame("mid", 11_000, sharpness = 5.2f, atMs = 3))
        assertSame("sharp", ring.best()!!.dataUrl)
    }

    @Test
    fun `best breaks sharpness ties by recency`() {
        val ring = SnapshotRingBuffer(capacity = 3)
        ring.push(SnapshotFrame("old", 1, sharpness = 5f, atMs = 1))
        ring.push(SnapshotFrame("new", 1, sharpness = 5f, atMs = 2))
        assertEquals("new", ring.best()!!.dataUrl)
    }

    @Test
    fun `empty ring returns nulls`() {
        val ring = SnapshotRingBuffer()
        assertNull(ring.best())
        assertNull(ring.newest())
        assertEquals(0, ring.size())
    }

    // ── FrameQuality ──────────────────────────────────────────────────────

    @Test
    fun `flat image has near-zero sharpness`() {
        val w = 64; val h = 64
        val luma = IntArray(w * h) { 128 }
        assertTrue(FrameQuality.laplacianVariance(luma, w, h) < 1e-6f)
    }

    @Test
    fun `edge-rich image scores higher than a smooth gradient`() {
        val w = 64; val h = 64
        // 棋盘格：边缘丰富
        val checker = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            if ((x / 2 + y / 2) % 2 == 0) 40 else 220
        }
        // 平滑斜坡：无局部边缘
        val gradient = IntArray(w * h) { i -> (i % w) * 4 }
        val sharp = FrameQuality.laplacianVariance(checker, w, h)
        val smooth = FrameQuality.laplacianVariance(gradient, w, h)
        assertTrue("checker=$sharp gradient=$smooth", sharp > smooth * 10)
        assertTrue("gradient should be near-flat: $smooth", smooth < 1f)
    }

    @Test
    fun `degenerate inputs are safe`() {
        assertEquals(0f, FrameQuality.laplacianVariance(IntArray(0), 0, 0))
        assertEquals(0f, FrameQuality.laplacianVariance(IntArray(4), 2, 2))
        assertEquals(0f, FrameQuality.laplacianVariance(IntArray(100), 10, 5)) // size < w*h
    }

    // ── FaceFrameMath ─────────────────────────────────────────────────────

    @Test
    fun `upright size swaps for quarter rotations`() {
        assertEquals(480 to 640, FaceFrameMath.uprightSize(640, 480, 270))
        assertEquals(480 to 640, FaceFrameMath.uprightSize(640, 480, 90))
        assertEquals(640 to 480, FaceFrameMath.uprightSize(640, 480, 0))
        assertEquals(640 to 480, FaceFrameMath.uprightSize(640, 480, 180))
    }

    @Test
    fun `centered face normalizes to zero - regression of the constant bottom-right bias`() {
        // 真机实录（修复前）：缓冲 640×480、直立 480×640，居中的脸
        // centerX=240/centerY=320 被除以缓冲尺寸 → nx=+0.25、ny=+0.33
        // → 注视点恒偏右下。必须按直立系归一化。
        val f = FaceFrameMath.normalize(
            centerX = 240f, centerY = 320f, boxW = 200f, boxH = 250f,
            bufferW = 640, bufferH = 480, rotationDegrees = 270, frontCamera = true,
        )
        assertEquals(0f, f.nx, 1e-5f)
        assertEquals(0f, f.ny, 1e-5f)
    }

    @Test
    fun `front camera flips nx - face on image left looks world-right`() {
        // 前置原始帧未镜像：用户在屏幕右（世界 +X）→ 画面左（直立 x<240）
        val f = FaceFrameMath.normalize(
            centerX = 120f, centerY = 320f, boxW = 100f, boxH = 120f,
            bufferW = 640, bufferH = 480, rotationDegrees = 270, frontCamera = true,
        )
        assertEquals(0.5f, f.nx, 1e-5f)
        assertEquals(0f, f.ny, 1e-5f)
        // 后置不翻转：同位置直接映射（画面左 = 世界左）
        val back = FaceFrameMath.normalize(
            centerX = 120f, centerY = 320f, boxW = 100f, boxH = 120f,
            bufferW = 640, bufferH = 480, rotationDegrees = 270, frontCamera = false,
        )
        assertEquals(-0.5f, back.nx, 1e-5f)
    }

    @Test
    fun `face at image bottom maps to positive ny and area uses upright frame`() {
        val f = FaceFrameMath.normalize(
            centerX = 240f, centerY = 560f, boxW = 240f, boxH = 320f,
            bufferW = 640, bufferH = 480, rotationDegrees = 270, frontCamera = true,
        )
        // 画面下缘（ny>0）= 用户低 → 投影器向下看
        assertEquals(0.75f, f.ny, 1e-5f)
        // 面积分母是直立系 480×640（不是缓冲 640×480）
        assertEquals(0.25f, f.area, 1e-5f)
    }

    @Test
    fun `visual intent keywords hit`() {
        assertTrue(VisionKeywords.matches("你看我身上这件衣服怎么样"))
        assertTrue(VisionKeywords.matches("这个是什么颜色"))
        assertTrue(VisionKeywords.matches("我背后有什么东西"))
        assertFalse(VisionKeywords.matches("你好呀,今天天气不错"))
        assertFalse(VisionKeywords.matches("给我讲个笑话"))
    }

    @Test
    fun `empty keywords never match`() {
        assertFalse(VisionKeywords.matches("看这个", emptyList()))
    }
}
