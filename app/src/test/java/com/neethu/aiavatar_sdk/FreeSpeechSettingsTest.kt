package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自由说话灵敏度设置纯逻辑：脏值钳制回合法范围、默认值落在滑条范围内。
 * （prefs load/save 走 Android 框架，不在 JVM 单测面内。）
 */
class FreeSpeechSettingsTest {

    @Test
    fun `clamp pulls wild values back into range`() {
        val c = FreeSpeechSettings.clamp(
            FreeSpeechSettings(startAbsolute = 1f, bargeAbsolute = -1f, hangoverMs = 99_999f)
        )
        assertEquals(0.080f, c.startAbsolute)
        assertEquals(0.05f, c.bargeAbsolute)
        assertEquals(1500f, c.hangoverMs)
    }

    @Test
    fun `clamp keeps in-range values untouched`() {
        val s = FreeSpeechSettings(startAbsolute = 0.030f, bargeAbsolute = 0.12f, hangoverMs = 900f)
        assertEquals(s, FreeSpeechSettings.clamp(s))
    }

    @Test
    fun `defaults are inside slider ranges`() {
        val d = FreeSpeechSettings()
        assertTrue(d.startAbsolute in FreeSpeechSettings.START_ABSOLUTE_RANGE)
        assertTrue(d.bargeAbsolute in FreeSpeechSettings.BARGE_ABSOLUTE_RANGE)
        assertTrue(d.hangoverMs in FreeSpeechSettings.HANGOVER_MS_RANGE)
    }
}
