package com.neethu.orchestrator.agent

import com.neethu.aiadapter.model.ToolCallRequest

/**
 * 弱模型工具调用的两条兜底（2026-10-11 真机 Qwen3.8-27B + enable_thinking:false 实测，
 * docs/agent-provider-matrix.md「可靠性」节）：
 *
 * 1. [isDanglingBridgeReply]——「悬空衔接句」检测：模型说了「让我看看……」却没发起
 *    原生调用就收流（关思考下 Qwen3.8 的主要失败形态，实测 ~50%）。命中后会话层用
 *    `tool_choice:"required"` 重试一轮（文字静默，见 AvatarSession）。
 * 2. [parseTemplateToolCalls]——Qwen 对话模板的文本形态工具调用解析：
 *    `required` 重试时关思考的服务端会把 `<tool_call><function=name><parameter=k>v`
 *    **当文字吐出来**而不进原生 tool_calls 字段（服务层不解析，实测 2/2）；这里把
 *    模板文本解析回 ToolCallRequest，模型的真实意图不丢。原生调用优先，文本解析只在
 *    原生为空时兜底。
 *
 * 纯函数，JVM 单测直测。
 */

/** 悬空衔接句动词白名单（模型的「我要去看/查」意图信号）。 */
private val BRIDGE_VERBS = listOf("看看", "瞧瞧", "瞅瞅", "查看", "查查", "观察", "找找", "查一下")

/**
 * 回合可见回复是否是「悬空衔接句」：很短、含看/查类动词、没有向用户提问——
 * 典型如「让我看看……」。长回复或带问号（已在反问互动）不判悬空。
 * 「让我看看你那边——嗯……画面里好像只有你房间的」这类**长错误回答**与
 * 「[调用工具 xx]」**文本式自造格式**不在此列（后者长度超限，二者都接不住，
 * 属已知残余，见矩阵文档）。
 */
fun isDanglingBridgeReply(cleanText: String): Boolean {
    val t = cleanText.trim()
    if (t.isEmpty() || t.length > 24) return false
    if ('？' in t || '?' in t) return false
    return BRIDGE_VERBS.any { it in t }
}

/** 合成的调用 id（模板文本没有服务端 id；assistant/tool 消息对内自洽即可）。 */
private const val TEMPLATE_CALL_ID_PREFIX = "textcall_"

/** Qwen 模板的单个工具调用块：<function=name> <parameter=k>v </parameter>… </function>。 */
private val FUNCTION_BLOCK = Regex(
    // ⚠ \s* 放在每个 <parameter> **前面**：<function=name> 与首个参数之间隔着换行
    // （放后面时循环组在锚点处匹配不上，整个块解析失败——单测踩过）
    pattern = "<function=([\\w.\\-]+)\\s*>((?:\\s*<parameter=[^>]*>.*?</parameter>)*)\\s*</function>",
    options = setOf(RegexOption.DOT_MATCHES_ALL),
)

private val PARAMETER = Regex(
    pattern = "<parameter=([^>]*)>(.*?)</parameter>",
    options = setOf(RegexOption.DOT_MATCHES_ALL),
)

/**
 * 从回复文本解析模板形态的工具调用；无 `<tool_call>`/`<function=` 块时返回空表。
 * 参数值按字面字符串收集（模板里非 JSON，组装成 JSON object 字符串）。
 */
fun parseTemplateToolCalls(text: String): List<ToolCallRequest> {
    if (!text.contains("<function=")) return emptyList()
    val calls = ArrayList<ToolCallRequest>()
    for (block in FUNCTION_BLOCK.findAll(text)) {
        val name = block.groupValues[1].trim()
        if (name.isEmpty()) continue
        val params = LinkedHashMap<String, String>()
        for (p in PARAMETER.findAll(block.groupValues[2])) {
            params[p.groupValues[1].trim()] = p.groupValues[2].trim()
        }
        val argsJson = buildString {
            append('{')
            params.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append(jsonString(k)).append(':').append(jsonString(v))
            }
            append('}')
        }
        calls += ToolCallRequest(
            id = "$TEMPLATE_CALL_ID_PREFIX${calls.size + 1}",
            name = name,
            argumentsJson = argsJson,
        )
    }
    return calls
}

/** 最小 JSON 字符串转义（模板参数值来自模型文本，引号/反斜杠/换行必须转义）。 */
private fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(c)
    }
    append('"')
}
