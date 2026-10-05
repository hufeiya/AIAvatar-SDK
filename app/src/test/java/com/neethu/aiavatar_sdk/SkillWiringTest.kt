package com.neethu.aiavatar_sdk

import com.neethu.aiavatar_sdk.skills.isSkillGestureAsset
import com.neethu.aiavatar_sdk.skills.rpsHandAssets
import com.neethu.orchestrator.skill.RpsSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 技能 P0 的 app 侧纯逻辑：WAV 时长（VAD 快路径的出拳信号判据）与技能手势
 * 目录的扫描排除（`<act:>` 协议块不收技能 tag）。
 */
class SkillWiringTest {

    @Test
    fun `wav duration from byte count`() {
        // 44 字节头 + 16000*2 字节/秒
        val wav = WavEncoder.encode(ByteArray(32_000), sampleRateHz = 16_000)
        assertEquals(1_000L, wavDurationMs(wav))
        val short = WavEncoder.encode(ByteArray(16_000), sampleRateHz = 16_000)
        assertEquals(500L, wavDurationMs(short))
        // 出拳判据的边界：2.5s 短句
        val threshold = WavEncoder.encode(ByteArray(2 * 16_000 * 5 / 2), sampleRateHz = 16_000)
        assertEquals(2_500L, wavDurationMs(threshold))
        assertTrue(wavDurationMs(threshold) <= RpsSkill.THROW_MAX_WAV_MS)
    }

    @Test
    fun `degenerate wav never crashes`() {
        assertEquals(0L, wavDurationMs(ByteArray(10)))
        assertEquals(0L, wavDurationMs(ByteArray(44)))
    }

    @Test
    fun `skill gesture assets are excluded from llm catalog paths`() {
        for (path in rpsHandAssets.values) {
            assertTrue(isSkillGestureAsset(path))
        }
        assertTrue(isSkillGestureAsset("/sdcard/Android/data/pkg/files/11_技能_猜拳/gesture_rock.vrma"))
        assertFalse(isSkillGestureAsset("animations/04_交流手势/Nodding Head Yes.vrma"))
        assertFalse(isSkillGestureAsset("animations/11_手势_上半身/Some Gesture.vrma"))
    }

    @Test
    fun `rps hand assets cover every hand`() {
        assertEquals(RpsSkill.Hand.entries.toSet(), rpsHandAssets.keys)
        for (path in rpsHandAssets.values) {
            assertTrue("path must point into assets/animations: $path", path.startsWith("animations/"))
            assertTrue(path.endsWith(".vrma"))
        }
    }
}
