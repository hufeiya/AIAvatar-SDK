package com.neethu.orchestrator.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弱模型工具调用兜底（2026-10-11 Qwen3.8 关思考真机实测驱动）：悬空衔接句检测
 * 与 Qwen 模板文本形态工具调用解析（docs/agent-provider-matrix.md 可靠性节）。
 */
class TemplateToolCallsTest {

    @Test
    fun `dangling bridge detection`() {
        assertTrue(isDanglingBridgeReply("让我看看……"))
        assertTrue(isDanglingBridgeReply("让我看看呀～"))
        assertTrue(isDanglingBridgeReply("我查一下哈"))
        assertTrue(isDanglingBridgeReply("稍等,我观察一下"))
        // 动作承诺句（2026-10-24 扩展）：坐/躺/走动只能走工具通道，
        // 承诺不调用 = 动作必不发生，同判悬空让 required 重试补救
        assertTrue(isDanglingBridgeReply("好，我这就去床边坐下"))
        assertTrue(isDanglingBridgeReply("这就过去"))
        assertTrue(isDanglingBridgeReply("好呀，我这就躺下"))
        assertTrue(isDanglingBridgeReply("马上到"))
        // 长回复/反问/空串不判悬空
        assertFalse(isDanglingBridgeReply("让我看看你那边——嗯……画面里好像只有你房间的东西"))
        assertFalse(isDanglingBridgeReply("你猜猜我在看什么？"))
        assertFalse(isDanglingBridgeReply("哈哈哈"))
        assertFalse(isDanglingBridgeReply(""))
        // 散文里偶然带「马上/过去」单字不命中（cue 是完整短语）
        assertFalse(isDanglingBridgeReply("马上就好啦，别急嘛"))
        assertFalse(isDanglingBridgeReply("上周我们聊到过去的事"))
    }

    @Test
    fun `pseudo tool call text detection`() {
        val tools = listOf("interact_with", "move_to", "stand_up")
        // DeepSeek 实测形态：自造 XML 整段写进正文
        assertTrue(isPseudoToolCallText("我去窗边看一眼。\n<interact_with>\n<anchor>bed_sit</anchor>\n</interact_with>", tools))
        assertTrue(isPseudoToolCallText("<move_to>carpet</move_to>", tools))
        // 散文裸提工具名（无 < 前缀）不算
        assertFalse(isPseudoToolCallText("我调用 interact_with 帮你坐下", tools))
        assertFalse(isPseudoToolCallText("窗边风很好", tools))
        assertFalse(isPseudoToolCallText("窗边风很好", emptyList()))
    }

    @Test
    fun `template tool call text parses into request`() {
        val text = "<tool_call>\n<function=observe_camera>\n" +
            "<parameter=description>\n看看当前摄像头画面里有什么\n</parameter>\n" +
            "</function>\n</tool_call>"
        val calls = parseTemplateToolCalls(text)
        assertEquals(1, calls.size)
        assertEquals("observe_camera", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("\"description\""))
        assertTrue(calls[0].argumentsJson.contains("看看当前摄像头画面里有什么"))
        assertTrue(calls[0].id.startsWith("textcall_"))
    }

    @Test
    fun `template parsing handles multiple calls quotes and newlines`() {
        val text = "<tool_call>\n<function=get_time>\n<parameter=tz>UTC+8 \"北京\"</parameter>\n</function>\n</tool_call>\n" +
            "中间的正常文字不该影响解析\n" +
            "<tool_call>\n<function=observe_camera>\n<parameter=description>第一行\n第二行</parameter>\n" +
            "<parameter=question>杯子</parameter>\n</function>\n</tool_call>"
        val calls = parseTemplateToolCalls(text)
        assertEquals(2, calls.size)
        assertEquals("get_time", calls[0].name)
        assertTrue(calls[0].argumentsJson.contains("\\\"北京\\\""))
        assertEquals("observe_camera", calls[1].name)
        // 参数值合法 JSON（换行被转义）——执行方的 JSON 解析不能炸
        kotlinx.serialization.json.Json.parseToJsonElement(calls[1].argumentsJson)
    }

    @Test
    fun `plain text without template blocks yields empty`() {
        assertTrue(parseTemplateToolCalls("让我看看……").isEmpty())
        assertTrue(parseTemplateToolCalls("").isEmpty())
        assertTrue(parseTemplateToolCalls("[调用工具 observe_camera]: {}").isEmpty())
    }
}
