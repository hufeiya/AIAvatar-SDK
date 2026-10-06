package com.neethu.orchestrator.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「模仿我」P2 表情映射纯函数单测：ARKit 直配模型 / VRM 预设别名降级 /
 * 多源取 max / 死区与 neutral 过滤。
 */
class MimicFaceMapperTest {

    private val arkitModel = listOf(
        "eyeBlinkLeft", "eyeBlinkRight", "jawOpen", "mouthSmileLeft", "browDownLeft",
    )

    private val vrmPresetModel = listOf("aa", "blinkLeft", "blinkRight", "joy", "angry", "fun")

    @Test
    fun `arkit-named model maps directly`() {
        val out = MimicFaceMapper.map(
            mapOf("eyeBlinkLeft" to 0.9f, "jawOpen" to 0.5f, "browDownLeft" to 0.3f),
            arkitModel,
        )
        assertEquals(0.9f, out["eyeBlinkLeft"]!!, 1e-4f)
        assertEquals(0.5f, out["jawOpen"]!!, 1e-4f)
        assertEquals(0.3f, out["browDownLeft"]!!, 1e-4f)
        assertEquals(3, out.size)
    }

    @Test
    fun `vrm-preset model falls back to alias table`() {
        val out = MimicFaceMapper.map(
            mapOf("jawOpen" to 0.8f, "mouthSmileLeft" to 0.6f, "mouthSmileRight" to 0.4f),
            vrmPresetModel,
        )
        // jawOpen→aa；两个 smile 源都映射 joy 且取 max
        assertEquals(0.8f, out["aa"]!!, 1e-4f)
        assertEquals(0.6f, out["joy"]!!, 1e-4f)
        assertFalse(out.containsKey("mouthSmileLeft"))
    }

    @Test
    fun `morph name normalization matches case and underscores`() {
        // 模型 morph 名大小写/下划线变体（blink_L 等）也应被直配命中
        val out = MimicFaceMapper.map(
            mapOf("eyeBlinkLeft" to 1.0f, "eyeBlinkRight" to 0.8f),
            listOf("Blink_L", "Blink_R"),
        )
        assertFalse(out.isEmpty())
        assertEquals(1.0f, out.values.max(), 1e-4f)
    }

    @Test
    fun `deadzone and neutral are filtered`() {
        val out = MimicFaceMapper.map(
            mapOf(
                "neutral" to 1.0f,
                "jawOpen" to 0.02f, // 低于死区
                "eyeBlinkLeft" to 0.7f,
            ),
            arkitModel + listOf("neutral"),
        )
        assertFalse(out.containsKey("neutral"))
        assertFalse(out.containsKey("jawOpen"))
        assertEquals(0.7f, out["eyeBlinkLeft"]!!, 1e-4f)
    }

    @Test
    fun `weights clamp to 0-1`() {
        val out = MimicFaceMapper.map(mapOf("jawOpen" to 1.7f), arkitModel)
        assertEquals(1.0f, out["jawOpen"]!!, 1e-4f)
    }

    @Test
    fun `empty inputs yield empty output`() {
        assertTrue(MimicFaceMapper.map(emptyMap(), arkitModel).isEmpty())
        assertTrue(MimicFaceMapper.map(mapOf("jawOpen" to 0.5f), emptyList()).isEmpty())
    }
}
