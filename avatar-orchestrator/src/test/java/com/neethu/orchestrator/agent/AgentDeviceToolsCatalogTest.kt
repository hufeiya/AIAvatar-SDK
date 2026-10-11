package com.neethu.orchestrator.agent

import com.neethu.corelib.AmbiencePreset
import com.neethu.corelib.Lang
import com.neethu.orchestrator.i18n.promptTextsOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.5 设备工具（docs/agent-tools-expansion.md §2）：六工厂的参数校验
 * （枚举白名单/缺失必填/isError 文案）、缝失败归因到文案的映射、schema
 * 形态锁（防 hint/enum 漂移）。全部纯 JVM——设备能力是注入的假缝。
 */
class AgentDeviceToolsCatalogTest {

    private val texts = promptTextsOf(Lang.ZH)

    // ── get_device_status ───────────────────────────────────────────────────

    @Test
    fun `device status composes natural sentence from vitals`() = runTest {
        val (_, executor) = AgentToolCatalog.getDeviceStatus(texts) {
            DeviceVitals(batteryPercent = 43, charging = false, temperatureC = 41.5f, audioRoute = "speaker")
        }
        val result = executor.invoke("{}")
        assertFalse(result.isError)
        assertEquals("电量 43%(未在充电),机身温度约 41.5°C,声音从扬声器播出", result.content)
    }

    @Test
    fun `device status degrades unknown temperature without inventing numbers`() = runTest {
        val (_, executor) = AgentToolCatalog.getDeviceStatus(texts) {
            DeviceVitals(batteryPercent = 9, charging = true, temperatureC = null, audioRoute = "bluetooth")
        }
        val content = executor.invoke("{}").content
        assertTrue(content.contains("机身温度未知"))
        assertFalse(content.contains("°C"))
        assertTrue(content.contains("蓝牙耳机"))
    }

    @Test
    fun `device status with null seam feeds back error`() = runTest {
        val (_, executor) = AgentToolCatalog.getDeviceStatus(texts) { null }
        val result = executor.invoke("{}")
        assertTrue(result.isError)
        assertEquals(texts.toolDeviceStatusUnavailable, result.content)
    }

    // ── set_flashlight ──────────────────────────────────────────────────────

    @Test
    fun `flashlight accepts boolean and string forms of enabled`() = runTest {
        val received = mutableListOf<Boolean>()
        val (_, executor) = AgentToolCatalog.setFlashlight(texts) {
            received += it
            null
        }
        assertEquals(texts.toolFlashlightDone(true), executor.invoke("""{"enabled":true}""").content)
        assertEquals(texts.toolFlashlightDone(false), executor.invoke("""{"enabled":"false"}""").content)
        assertEquals(listOf(true, false), received)
    }

    @Test
    fun `flashlight missing enabled feeds back arg error`() = runTest {
        val (_, executor) = AgentToolCatalog.setFlashlight(texts) { null }
        val result = executor.invoke("{}")
        assertTrue(result.isError)
        assertEquals(texts.toolFlashlightArgError, result.content)
    }

    @Test
    fun `flashlight failures map to dedicated texts`() = runTest {
        fun executorWith(failure: TorchFailure) =
            AgentToolCatalog.setFlashlight(texts) { failure }.second

        assertTrue(executorWith(TorchFailure.NO_FLASH).invoke("""{"enabled":true}""").isError)
        assertEquals(
            texts.toolFlashlightNoFlash,
            executorWith(TorchFailure.NO_FLASH).invoke("""{"enabled":true}""").content,
        )
        assertEquals(
            texts.toolFlashlightCameraInUse,
            executorWith(TorchFailure.CAMERA_IN_USE).invoke("""{"enabled":true}""").content,
        )
        assertTrue(
            executorWith(TorchFailure.FAILED).invoke("""{"enabled":true}""").isError,
        )
    }

    // ── send_haptic ─────────────────────────────────────────────────────────

    @Test
    fun `haptic valid pattern reaches seam and done text`() = runTest {
        val received = mutableListOf<String>()
        val (_, executor) = AgentToolCatalog.sendHaptic(texts) {
            received += it
            null
        }
        assertEquals(texts.toolHapticDone("heartbeat"), executor.invoke("""{"pattern":"heartbeat"}""").content)
        assertEquals("heartbeat", received.single())
    }

    @Test
    fun `haptic unknown pattern lists valid values`() = runTest {
        val (_, executor) = AgentToolCatalog.sendHaptic(texts) { null }
        val result = executor.invoke("""{"pattern":"boom"}""")
        assertTrue(result.isError)
        for (p in AgentToolCatalog.HAPTIC_PATTERNS) assertTrue(result.content.contains(p))
    }

    @Test
    fun `haptic missing pattern and no vibrator`() = runTest {
        val (_, executor) = AgentToolCatalog.sendHaptic(texts) { null }
        val missing = executor.invoke("{}")
        assertTrue(missing.isError)
        assertTrue(missing.content.contains(AgentToolCatalog.HAPTIC_PATTERNS.first()))

        val noMotor = AgentToolCatalog.sendHaptic(texts) { HapticFailure.NO_VIBRATOR }.second
        val result = noMotor.invoke("""{"pattern":"high_five"}""")
        assertTrue(result.isError)
        assertEquals(texts.toolHapticNoVibrator, result.content)
    }

    // ── read_clipboard ──────────────────────────────────────────────────────

    @Test
    fun `clipboard text passes through as normal result`() = runTest {
        val (_, executor) = AgentToolCatalog.readClipboard(texts) { "SF0012345678" }
        val result = executor.invoke("{}")
        assertFalse(result.isError)
        assertEquals("SF0012345678", result.content)
    }

    @Test
    fun `clipboard empty is a normal result not an error`() = runTest {
        val (_, executor) = AgentToolCatalog.readClipboard(texts) { null }
        val result = executor.invoke("{}")
        assertFalse(result.isError)
        assertEquals(texts.toolClipboardEmpty, result.content)
    }

    // ── set_ambience ────────────────────────────────────────────────────────

    @Test
    fun `ambience passes validated preset enum to seam`() = runTest {
        val received = mutableListOf<AmbiencePreset>()
        val (_, executor) = AgentToolCatalog.setAmbience(texts) {
            received += it
            true
        }
        // 模型可能发小写；缝收到的必须是枚举
        assertTrue(executor.invoke("""{"preset":"dim"}""").content.contains("昏暗夜话"))
        assertEquals(AmbiencePreset.DIM, received.single())
    }

    @Test
    fun `ambience unknown preset lists valid values`() = runTest {
        val (_, executor) = AgentToolCatalog.setAmbience(texts) { true }
        val result = executor.invoke("""{"preset":"cyberpunk"}""")
        assertTrue(result.isError)
        for (p in AgentToolCatalog.AMBIENCE_PRESET_NAMES) assertTrue(result.content.contains(p))
    }

    @Test
    fun `ambience seam failure feeds back error`() = runTest {
        val (_, executor) = AgentToolCatalog.setAmbience(texts) { false }
        val result = executor.invoke("""{"preset":"studio"}""")
        assertTrue(result.isError)
        assertEquals(texts.toolAmbienceFailed, result.content)
    }

    // ── take_photo ──────────────────────────────────────────────────────────

    @Test
    fun `photo saved name lands in result text`() = runTest {
        val (_, executor) = AgentToolCatalog.takePhoto(texts) { "avatar_20261011_213400.png" }
        val result = executor.invoke("{}")
        assertFalse(result.isError)
        assertTrue(result.content.contains("Pictures/AIAvatar/avatar_20261011_213400.png"))
    }

    @Test
    fun `photo capture failure feeds back error`() = runTest {
        val (_, executor) = AgentToolCatalog.takePhoto(texts) { null }
        val result = executor.invoke("{}")
        assertTrue(result.isError)
        assertEquals(texts.toolTakePhotoFailed, result.content)
    }

    // ── schema 形态锁（防 hint/enum 漂移）────────────────────────────────────

    @Test
    fun `schemas lock names and enums`() {
        val pairs = listOf(
            AgentToolCatalog.getDeviceStatus(texts) { null }.first,
            AgentToolCatalog.setFlashlight(texts) { null }.first,
            AgentToolCatalog.sendHaptic(texts) { null }.first,
            AgentToolCatalog.readClipboard(texts) { null }.first,
            AgentToolCatalog.setAmbience(texts) { false }.first,
            AgentToolCatalog.takePhoto(texts) { null }.first,
        )
        assertEquals(
            listOf(
                "get_device_status", "set_flashlight", "send_haptic",
                "read_clipboard", "set_ambience", "take_photo",
            ),
            pairs.map { it.name },
        )
        // 无参工具的 schema 不带 properties
        assertTrue(pairs[0].parametersSchema.toString().contains("\"required\":[]"))
        // 布尔必填 / 枚举白名单进 schema
        assertTrue(pairs[1].parametersSchema.toString().contains("\"enabled\""))
        assertTrue(pairs[1].parametersSchema.toString().contains("\"required\":[\"enabled\"]"))
        for (p in AgentToolCatalog.HAPTIC_PATTERNS) {
            assertTrue(pairs[2].parametersSchema.toString().contains(p))
        }
        for (p in AgentToolCatalog.AMBIENCE_PRESET_NAMES) {
            assertTrue(pairs[4].parametersSchema.toString().contains(p))
        }
        // hint 全部非空（聚合进钉住协议块的注意力预算）
        assertTrue(pairs.all { !it.systemHint.isNullOrBlank() })
    }

    // ── 枚举校验文案与 toolsetBlock 公共尾部 ─────────────────────────────────

    @Test
    fun `enum arg invalid distinguishes missing from wrong value`() {
        val missing = texts.toolEnumArgInvalid("set_ambience", "", AgentToolCatalog.AMBIENCE_PRESET_NAMES)
        assertTrue(missing.contains("缺少"))
        val wrong = texts.toolEnumArgInvalid("set_ambience", "cozy_night", AgentToolCatalog.AMBIENCE_PRESET_NAMES)
        assertTrue(wrong.contains("cozy_night"))
        assertTrue(wrong.contains("spotlight"))
    }

    @Test
    fun `toolset block carries embodiment tail in both languages`() {
        val hints = listOf("get_device_status" to "hint")
        val zh = promptTextsOf(Lang.ZH).toolsetBlock(hints, maxRounds = 3)
        assertTrue(zh.contains("身体感受"))
        assertTrue(zh.contains("不要播报数值"))
        val en = promptTextsOf(Lang.EN).toolsetBlock(hints, maxRounds = 3)
        assertTrue(en.contains("bodily sensations"))
        assertTrue(en.contains("never read out raw numbers"))
    }
}
