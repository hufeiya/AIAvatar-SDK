package com.neethu.orchestrator.face

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 口型通道混合（FaceDriver.blendMouth 纯函数）：说话时口型与表情嘴部按
 * morph 取 max，而不是口型独占——独占时表情的嘴部 garnish（happy→aa、
 * surprised→oh、sad→mouthFrown…）在说话期间全部被抹掉，脸只剩口型。
 */
class FaceDriverBlendMouthTest {

    @Test
    fun `viseme wins when it is the stronger source`() {
        val out = FaceDriver.blendMouth(
            viseme = mapOf("aa" to 0.49f, "oh" to 0f, "ee" to 0f),
            emotion = mapOf("aa" to 0.12f, "oh" to 0.25f),
        )
        assertEquals(0.49f, out["aa"]!!, 1e-6f)
    }

    @Test
    fun `emotion garnish shows through where the viseme is silent`() {
        // 说话间隙/弱口型处，表情的嘴部动作可见（旧语义此处恒 0）
        val out = FaceDriver.blendMouth(
            viseme = mapOf("aa" to 0f, "oh" to 0.1f),
            emotion = mapOf("aa" to 0.12f, "oh" to 0.25f),
        )
        assertEquals(0.12f, out["aa"]!!, 1e-6f)
        assertEquals(0.25f, out["oh"]!!, 1e-6f)
    }

    @Test
    fun `without emotion the mouth is the raw viseme`() {
        val viseme = mapOf("aa" to 0.4f, "ih" to 0.2f, "ou" to 0f, "ee" to 0f, "oh" to 0.1f)
        assertEquals(viseme, FaceDriver.blendMouth(viseme, emptyMap()))
    }

    @Test
    fun `output keeps viseme names only — emotion mouth morphs ride their own channel`() {
        // mouthFrown 等 ARKit 嘴部 morph 不在口型五元组里，由表情通道直发；
        // blendMouth 只负责 aa/ih/ou/ee/oh 五个键。
        val out = FaceDriver.blendMouth(
            viseme = mapOf("aa" to 0f),
            emotion = mapOf("aa" to 0.1f, "mouthFrownLeft" to 0.35f),
        )
        assertEquals(setOf("aa"), out.keys)
        assertEquals(0.1f, out["aa"]!!, 1e-6f)
    }
}
