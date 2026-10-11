package com.neethu.aiavatar_sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 智能体工具的 app 侧契约（docs/agent-feasibility.md §4.6/§4.11）：
 * 两个开关的默认值与持久化语义、身份签名不含工具开关（工具不改变模型能力面，
 * 换开关不轮换对话上下文）、ai_cmd tools_call 的参数解析。
 */
class AgentPrefsTest {

    @Test
    fun `defaults - agent tools on, camera observe off`() {
        val p = AiChatPrefs()
        assertTrue("总开关默认开（纯本地工具无隐私面）", p.agentTools)
        assertFalse("摄像头观察默认关（隐私面：模型可主动看画面）", p.observeCamera)
    }

    @Test
    fun `identity signature ignores agent toggles`() {
        val base = AiChatPrefs(provider = AiProvider.SILICONFLOW, llmModel = "Qwen/Qwen3.8-27B")
        val toggled = base.copy(agentTools = !base.agentTools, observeCamera = !base.observeCamera)
        // 工具开关不改变模型能力面 → 不轮换上下文（§11.3，与语音/镜头开关同列）
        assertEquals(llmIdentitySignature(base), llmIdentitySignature(toggled))
        // 模型变化仍然轮换（回归锚）
        assertFalse(
            llmIdentitySignature(base) ==
                llmIdentitySignature(base.copy(llmModel = "deepseek-ai/DeepSeek-V3")),
        )
    }

    @Test
    fun `tools_call arg parses name and optional json`() {
        assertEquals("get_time" to "{}", parseToolsCallArg("get_time"))
        assertEquals("get_time" to "{}", parseToolsCallArg("get_time {}"))
        assertEquals("observe_camera" to """{"description":"杯子"}""", parseToolsCallArg("""observe_camera {"description":"杯子"}"""))
        assertEquals("get_time" to "{}", parseToolsCallArg("  get_time   "))
        assertThrows(IllegalArgumentException::class.java) { parseToolsCallArg(null) }
        assertThrows(IllegalArgumentException::class.java) { parseToolsCallArg("   ") }
    }
}
