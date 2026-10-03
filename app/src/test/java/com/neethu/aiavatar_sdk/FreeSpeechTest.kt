package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自由说话纯逻辑：WAV 头逐字段、VAD 端点判定（起音确认/句尾悬停/短促
 * 丢弃/超长断句/说话期 barge-in 的门限·持续·宽限三重门）。
 */
class FreeSpeechTest {

    // ── WavEncoder ────────────────────────────────────────────────────────

    @Test
    fun `wav header fields are correct`() {
        val pcm = ByteArray(100) { (it % 7).toByte() }
        val wav = WavEncoder.encode(pcm, sampleRateHz = 16_000)
        assertEquals(144, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        // riff size = 36 + data
        assertEquals(136, le32(wav, 4))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(16, le32(wav, 16)) // fmt chunk size
        assertEquals(1, le16(wav, 20)) // PCM
        assertEquals(1, le16(wav, 22)) // mono
        assertEquals(16_000, le32(wav, 24))
        assertEquals(32_000, le32(wav, 28)) // byte rate = 16000*1*2
        assertEquals(2, le16(wav, 32)) // block align
        assertEquals(16, le16(wav, 34)) // bits
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(100, le32(wav, 40))
        // PCM 原样跟在头后
        assertEquals(pcm[0], wav[44])
        assertEquals(pcm[99], wav[143])
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    // ── SpeechVad ─────────────────────────────────────────────────────────

    /** 帧推进器:每次喂一帧 RMS,时间 +20ms。 */
    private class Feeder(val vad: SpeechVad = SpeechVad()) {
        var now = 1_000L
        val events = mutableListOf<SpeechVad.Event>()

        fun feed(rms: Float, frames: Int, avatarSpeaking: Boolean = false) {
            repeat(frames) {
                events += vad.feed(rms, now, avatarSpeaking)
                now += 20
            }
        }

        fun assertSingle(expected: SpeechVad.Event) {
            assertEquals(listOf(expected), events)
        }
    }

    @Test
    fun `silence produces no events`() {
        val f = Feeder()
        f.feed(rms = 0.008f, frames = 200) // 4s 安静
        assertTrue(f.events.isEmpty())
        assertFalse(f.vad.capturing)
    }

    @Test
    fun `speech then hangover yields started and ended`() {
        val f = Feeder()
        f.feed(0.2f, frames = 20) // 400ms 有声(60ms 确认后起音,时长过最短句)
        assertTrue(f.events.firstOrNull() == SpeechVad.Event.SpeechStarted)
        assertTrue(f.vad.capturing)
        f.events.clear()
        f.feed(0.006f, frames = 50) // 1s 静默(悬停 800ms 后判句尾)
        f.assertSingle(SpeechVad.Event.SpeechEnded)
        assertFalse(f.vad.capturing)
    }

    @Test
    fun `short blip is discarded as too short`() {
        val f = Feeder()
        f.feed(0.2f, frames = 5) // 100ms 短促声
        assertEquals(SpeechVad.Event.SpeechStarted, f.events.first())
        f.events.clear()
        f.feed(0.006f, frames = 50) // 静默过悬停
        f.assertSingle(SpeechVad.Event.TooShort)
    }

    @Test
    fun `long speech is force-ended at max utterance`() {
        val f = Feeder()
        f.feed(0.2f, frames = 3)
        f.events.clear()
        f.feed(0.15f, frames = 800) // 持续说 16s
        assertTrue("expected SpeechEnded among ${f.events}", SpeechVad.Event.SpeechEnded in f.events)
    }

    @Test
    fun `barge-in needs loud and sustained and past grace`() {
        val f = Feeder()
        // 虚拟人开始说话:先喂宽限期(600ms)内的中等响度(超过普通起音 0.055,低于 barge 0.14)
        f.feed(0.07f, frames = 30, avatarSpeaking = true)
        assertTrue("no event during grace/moderate", f.events.isEmpty())
        // 继续中等响度 600ms:仍不触发(未过 barge 门限)
        f.feed(0.07f, frames = 30, avatarSpeaking = true)
        assertTrue(f.events.isEmpty())
        // 大声开口持续 350ms(需 ≥18 帧)→ BargeIn 恰好一次
        f.feed(0.25f, frames = 20, avatarSpeaking = true)
        f.assertSingle(SpeechVad.Event.BargeIn)
        assertTrue(f.vad.capturing) // 这句话继续被捕获
        // 持续大声不再重复触发
        f.events.clear()
        f.feed(0.25f, frames = 10, avatarSpeaking = true)
        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `moderate avatar-level sound does not capture or corrupt vad`() {
        val f = Feeder()
        // 说话期 3s 喂 0.1(超过普通起音门限、低于 barge 门限):不得捕获/起音
        f.feed(0.1f, frames = 150, avatarSpeaking = true)
        assertTrue(f.events.isEmpty())
        assertFalse(f.vad.capturing)
        // 说话结束后 VAD 仍然健康:正常说话照常起音
        f.feed(0.2f, frames = 20, avatarSpeaking = false)
        assertEquals(SpeechVad.Event.SpeechStarted, f.events.first())
    }

    @Test
    fun `reset clears capture state`() {
        val f = Feeder()
        f.feed(0.2f, frames = 10)
        assertTrue(f.vad.capturing)
        f.vad.reset()
        assertFalse(f.vad.capturing)
    }
}
