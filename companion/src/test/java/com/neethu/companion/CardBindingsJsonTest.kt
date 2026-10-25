package com.neethu.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 卡级绑定 JSON 往返(companion 分叉):sceneFile 字段进出 + demo 旧 JSON
 * (无 sceneFile 键)的向后兼容。
 */
class CardBindingsJsonTest {

    @Test
    fun `scene file round-trips`() {
        val bindings = mapOf(
            "a.png" to CardBinding(voice = "Yunxi", modelFile = "10.vrm", greetingIndex = 1, sceneFile = "dream_bedroom.glb"),
            "b.json" to CardBinding(sceneFile = "home.glb"),
        )
        val json = CardBindingsJson.toJson(bindings)
        val back = CardBindingsJson.fromJson(json)
        assertEquals("dream_bedroom.glb", back["a.png"]?.sceneFile)
        assertEquals("Yunxi", back["a.png"]?.voice)
        assertEquals(1, back["a.png"]?.greetingIndex)
        assertEquals("home.glb", back["b.json"]?.sceneFile)
        assertNull(back["b.json"]?.voice)
    }

    @Test
    fun `demo-era json without scene key still parses`() {
        val legacy = """{"a.png":{"voice":"anna","modelFile":"10.vrm","greetingIndex":0}}"""
        val back = CardBindingsJson.fromJson(legacy)
        assertEquals("anna", back["a.png"]?.voice)
        assertNull(back["a.png"]?.sceneFile)
    }

    @Test
    fun `blank binding drops out`() {
        val json = CardBindingsJson.toJson(mapOf("a.png" to CardBinding()))
        assertEquals("{}", json)
    }

    @Test
    fun `field merge keeps old scene`() {
        // update() 的字段级合并语义:只写 voice 不应丢 sceneFile
        val old = CardBinding(sceneFile = "dream_bedroom.glb")
        val merged = CardBinding(
            voice = "v",
            modelFile = old.modelFile,
            greetingIndex = old.greetingIndex,
            sceneFile = old.sceneFile,
        )
        assertEquals("dream_bedroom.glb", merged.sceneFile)
        assertEquals("v", merged.voice)
    }
}
