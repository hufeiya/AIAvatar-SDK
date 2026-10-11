package com.neethu.corelib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.5 氛围预设（docs/agent-tools-expansion.md §2.5）：五预设的字段映射锁
 * （尤其 dim/dreamy 的 lux 值——模型按预设名演「变暗/朦胧」，数值漂了体验就
 * 对不上）；applyTo 只动灯光三字段、用户调过的其余字段原样保留。
 */
class AmbiencePresetTest {

    /** 用户调过一堆字段的基准设置（验证 copy 不打回默认）。 */
    private val tuned = AvatarRenderSettings(
        iblIntensity = 9_000f,
        iblRotationDegrees = 45f,
        lightingRig = LightingRig.STUDIO,
        shadowMapSize = 4096,
        softShadows = true,
        contactShadows = true,
        ambientOcclusion = AmbientOcclusionQuality.STANDARD,
        toneMapping = ToneMappingMode.FILMIC,
        bloomEnabled = true,
        bloomStrength = 0.4f,
        antiAliasing = AntiAliasingMode.TAA,
        depthOfFieldEnabled = true,
        enhanceMaterials = true,
        renderMode = RenderMode.MTOON,
        showFps = false,
    )

    @Test
    fun `preset field mapping locked`() {
        assertEquals(13_000f, AmbiencePreset.STUDIO.applyTo(tuned).iblIntensity, 0.01f)
        assertEquals(LightingRig.STUDIO, AmbiencePreset.STUDIO.applyTo(tuned).lightingRig)

        assertEquals(22_000f, AmbiencePreset.BRIGHT.applyTo(tuned).iblIntensity, 0.01f)
        assertEquals(LightingRig.STUDIO, AmbiencePreset.BRIGHT.applyTo(tuned).lightingRig)

        // 昏暗夜话：KEY_FILL + 3500 lux
        assertEquals(3_500f, AmbiencePreset.DIM.applyTo(tuned).iblIntensity, 0.01f)
        assertEquals(LightingRig.KEY_FILL, AmbiencePreset.DIM.applyTo(tuned).lightingRig)

        // 单灯聚光舞台：KEY_ONLY + 5000 lux
        assertEquals(5_000f, AmbiencePreset.SPOTLIGHT.applyTo(tuned).iblIntensity, 0.01f)
        assertEquals(LightingRig.KEY_ONLY, AmbiencePreset.SPOTLIGHT.applyTo(tuned).lightingRig)

        // 暗夜朦胧：KEY_ONLY + 2500 lux + bloom 0.25
        assertEquals(2_500f, AmbiencePreset.DREAMY.applyTo(tuned).iblIntensity, 0.01f)
        assertEquals(LightingRig.KEY_ONLY, AmbiencePreset.DREAMY.applyTo(tuned).lightingRig)
        assertTrue(AmbiencePreset.DREAMY.applyTo(tuned).bloomEnabled)
        assertEquals(0.25f, AmbiencePreset.DREAMY.applyTo(tuned).bloomStrength, 0.001f)
    }

    @Test
    fun `bloom off presets keep strength untouched`() {
        // 关 bloom 不动 strength（bloomStrength 只在 DREAMY 显式给值）
        val applied = AmbiencePreset.STUDIO.applyTo(tuned)
        assertFalse(applied.bloomEnabled)
        assertEquals(0.4f, applied.bloomStrength, 0.001f)
    }

    @Test
    fun `applyTo only touches the three ambience fields`() {
        for (preset in AmbiencePreset.entries) {
            val applied = preset.applyTo(tuned)
            assertEquals("iblRotationDegrees 被预设动了", tuned.iblRotationDegrees, applied.iblRotationDegrees)
            assertEquals("shadowMapSize 被预设动了（PCSS/阴影永不进预设）", tuned.shadowMapSize, applied.shadowMapSize)
            assertEquals("softShadows 被预设动了", tuned.softShadows, applied.softShadows)
            assertEquals("contactShadows 被预设动了", tuned.contactShadows, applied.contactShadows)
            assertEquals("ambientOcclusion 被预设动了", tuned.ambientOcclusion, applied.ambientOcclusion)
            assertEquals("toneMapping 被预设动了", tuned.toneMapping, applied.toneMapping)
            assertEquals("antiAliasing 被预设动了", tuned.antiAliasing, applied.antiAliasing)
            assertEquals("depthOfFieldEnabled 被预设动了", tuned.depthOfFieldEnabled, applied.depthOfFieldEnabled)
            assertEquals("enhanceMaterials 被预设动了", tuned.enhanceMaterials, applied.enhanceMaterials)
            assertEquals("renderMode 被预设动了", tuned.renderMode, applied.renderMode)
            assertEquals("showFps 被预设动了", tuned.showFps, applied.showFps)
        }
    }

    @Test
    fun `controller tracks last pushed settings`() {
        val controller = AvatarController()
        assertNull("未推送过 = null", controller.currentRenderSettings)
        val settings = AmbiencePreset.DIM.applyTo(AvatarRenderSettings())
        controller.updateRenderSettings(settings)
        assertEquals(settings, controller.currentRenderSettings)
    }
}
