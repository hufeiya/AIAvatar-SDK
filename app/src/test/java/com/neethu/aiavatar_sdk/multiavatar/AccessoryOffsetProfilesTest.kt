package com.neethu.aiavatar_sdk.multiavatar

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校准偏移模板层（P1 道具系统 §6.4）单测：模板表、五层合并链
 * （条目默认 → 模板出厂 → 模板级 preset → 条目级 preset）、
 * 既有无模板路径逐位兼容、模板级 preset 文件 schema 往返。
 */
class AccessoryOffsetProfilesTest {

    private fun entry(
        bone: String = "rightHand",
        pos: FloatArray = floatArrayOf(0f, 0.02f, 0.03f),
        rot: FloatArray = floatArrayOf(0f, 0f, 0f),
        scale: Float = 1f,
    ) = AccessoryOffsetProfiles.Mount(bone, pos, rot, scale)

    private fun preset(
        bone: String? = null,
        pos: List<Float>? = null,
        rot: List<Float>? = null,
        scale: Float? = null,
    ) = AccessoryCalibrationStore.Preset(bone, pos, rot, scale)

    // ── 模板表 ───────────────────────────────────────────────────────────

    @Test
    fun `8 templates registered with mount-point defaults`() {
        assertEquals(
            setOf(
                "holdCup", "holdBottle", "holdBook", "holdWand",
                "holdObject", "wearHead", "holdBig", "worldProp",
            ),
            AccessoryOffsetProfiles.byName.keys,
        )
        // 挂点即模板身份的一部分：杯/瓶/棒/物在右手，书在左手，头戴在头
        assertEquals("rightHand", AccessoryOffsetProfiles.holdCup.bone)
        assertEquals("leftHand", AccessoryOffsetProfiles.holdBook.bone)
        assertEquals("head", AccessoryOffsetProfiles.wearHead.bone)
        AccessoryOffsetProfiles.byName.values.forEach {
            assertTrue(it.scale > 0f)
            assertEquals(3, it.position.size)
            assertEquals(3, it.rotationEulerYXZ.size)
        }
    }

    @Test
    fun `unknown profile name yields null lookup`() {
        assertNull(AccessoryOffsetProfiles.find("holdTaco"))
        assertNull(AccessoryOffsetProfiles.find(null))
        assertNotNull(AccessoryOffsetProfiles.find("holdCup"))
    }

    // ── 合并链 ───────────────────────────────────────────────────────────

    @Test
    fun `no profile = legacy behavior, entry default then accessory preset overlays`() {
        val m = AccessoryOffsetProfiles.resolve(
            entryDefault = entry(pos = floatArrayOf(0.1f, 0f, 0f)),
            profileName = null,
            profilePreset = null,
            accessoryPreset = preset(bone = "leftHand", scale = 1.5f),
        )
        assertEquals("leftHand", m.bone)
        assertArrayEquals(floatArrayOf(0.1f, 0f, 0f), m.position, 0f)
        assertEquals(1.5f, m.scale, 0f)
    }

    @Test
    fun `profile takes over the entry default entirely`() {
        val m = AccessoryOffsetProfiles.resolve(
            entryDefault = entry(bone = "head", pos = floatArrayOf(9f, 9f, 9f), scale = 7f),
            profileName = "holdCup",
            profilePreset = null,
            accessoryPreset = null,
        )
        assertEquals(AccessoryOffsetProfiles.holdCup.bone, m.bone)
        assertArrayEquals(AccessoryOffsetProfiles.holdCup.position, m.position, 0f)
        assertArrayEquals(AccessoryOffsetProfiles.holdCup.rotationEulerYXZ, m.rotationEulerYXZ, 0f)
        assertEquals(AccessoryOffsetProfiles.holdCup.scale, m.scale, 0f)
    }

    @Test
    fun `profile preset overlays only calibrated dimensions`() {
        val m = AccessoryOffsetProfiles.resolve(
            entryDefault = entry(),
            profileName = "holdCup",
            profilePreset = preset(
                pos = listOf(0f, 0.06f, 0.06f),
                rot = listOf(0f, 180f, 0f),
            ),
            accessoryPreset = null,
        )
        // 未标定维度沿用模板出厂值（4705 右手系 180° Y 校准场景：只动 pos/rot）
        assertEquals(AccessoryOffsetProfiles.holdCup.bone, m.bone)
        assertArrayEquals(floatArrayOf(0f, 0.06f, 0.06f), m.position, 0f)
        assertArrayEquals(floatArrayOf(0f, 180f, 0f), m.rotationEulerYXZ, 0f)
        assertEquals(AccessoryOffsetProfiles.holdCup.scale, m.scale, 0f)
    }

    @Test
    fun `accessory preset wins over profile preset (fine-tune beats template)`() {
        val m = AccessoryOffsetProfiles.resolve(
            entryDefault = entry(),
            profileName = "holdBottle",
            profilePreset = preset(pos = listOf(0f, 0.1f, 0.1f), scale = 2f),
            accessoryPreset = preset(scale = 1.2f),
        )
        // 条目级微调只覆盖它标定的维度，模板级其余维度继续生效
        assertArrayEquals(floatArrayOf(0f, 0.1f, 0.1f), m.position, 0f)
        assertEquals(1.2f, m.scale, 0f)
    }

    @Test
    fun `unknown sidecar profile name falls back to entry default defensively`() {
        val m = AccessoryOffsetProfiles.resolve(
            entryDefault = entry(bone = "leftHand", pos = floatArrayOf(0f, 0.08f, 0.06f)),
            profileName = "holdTaco",
            profilePreset = null,
            accessoryPreset = null,
        )
        assertEquals("leftHand", m.bone)
        assertArrayEquals(floatArrayOf(0f, 0.08f, 0.06f), m.position, 0f)
    }

    // ── 模板级 preset 文件 schema ────────────────────────────────────────

    @Test
    fun `profile presets json roundtrip keeps partial fields per template`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val src = AccessoryCalibrationStore.ProfilePresets(
            mapOf(
                "holdCup" to preset(pos = listOf(0f, 0.05f, 0.05f), rot = listOf(0f, 180f, 0f)),
                "wearHead" to preset(bone = "head", scale = 1.1f),
            )
        )
        val text = json.encodeToString(
            AccessoryCalibrationStore.ProfilePresets.serializer(), src
        )
        val back = json.decodeFromString(AccessoryCalibrationStore.ProfilePresets.serializer(), text)
        assertEquals(src.profiles.keys, back.profiles.keys)
        assertEquals(listOf(0f, 180f, 0f), back.profiles.getValue("holdCup").rotationEulerYXZ)
        assertEquals("head", back.profiles.getValue("wearHead").bone)
        assertNull(back.profiles.getValue("wearHead").position)
    }
}
