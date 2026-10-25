package com.neethu.companion.debug

import android.util.Log
import com.neethu.companion.AiDebugCommand
import com.neethu.companion.AI_LOG_TAG
import com.neethu.orchestrator.session.AvatarSession

/**
 * ai_cmd 调试钩子（demo AiChatDebugHooks 的产品精简版）：真机验收通道。
 * `adb shell am start -n com.neethu.companion/.CompanionActivity --es ai_cmd
 * <命令> --es ai_arg <参数>` → logcat `-s AIDebug` 读回。
 */
internal class CompanionDebugHooks(
    val sessionProvider: () -> AvatarSession?,
    val stateLine: () -> String,
    val autonomyStateLine: () -> String,
    val autonomyToggle: (String?) -> String,
    val forceGreet: () -> String,
    val forceThink: () -> String,
    val relationshipLine: suspend () -> String?,
    val memoryList: suspend () -> String,
    val memoryForget: suspend (String) -> String,
    val memoryClear: suspend () -> String,
    val activateCard: (String) -> String,
    val clearCard: () -> String,
    val cardsSnapshot: () -> String,
    val sendText: (String) -> Unit,
    val sendGreeting: (String) -> Unit,
)

/** 执行一条 ai_cmd,返回结果行(logcat 落地);未知命令返回 help。 */
internal suspend fun executeAiCommand(cmd: AiDebugCommand, h: CompanionDebugHooks): String {
    val arg = cmd.arg
    return try {
        when (cmd.name) {
            "help" -> "commands: state snapshot send speak interrupt activate_card clear_card " +
                "cards greet think autonomy relationship tools_list tools_call memory_list memory_forget memory_clear"

            "state" -> h.stateLine()

            "snapshot" -> buildString {
                appendLine(h.stateLine())
                appendLine(h.autonomyStateLine())
                h.sessionProvider()?.agentSnapshot()?.let { appendLine(it.take(2000)) }
            }.trim()

            "send" -> {
                val text = arg ?: return "send: missing ai_arg"
                h.sendText(text)
                "sent: $text"
            }

            "speak" -> {
                val text = arg ?: return "speak: missing ai_arg"
                h.sendGreeting(text)
                "speaking: $text"
            }

            "interrupt" -> {
                h.sessionProvider()?.interrupt()
                "interrupted"
            }

            "activate_card" -> h.activateCard(arg ?: "")
            "clear_card" -> h.clearCard()
            "cards" -> h.cardsSnapshot()

            "greet" -> h.forceGreet()
            "think" -> h.forceThink()
            "autonomy" -> h.autonomyToggle(arg)

            "relationship" -> h.relationshipLine() ?: "no relationship snapshot (session not ready or tracking off)"

            "tools_list" -> h.sessionProvider()?.tools?.list()?.joinToString(",") { it.name } ?: "no session"

            "tools_call" -> {
                val s = h.sessionProvider() ?: return "no session"
                val name = arg ?: return "tools_call: missing ai_arg(name)"
                val result = s.tools.invoke(name, "{}")
                "isError=${result.isError} ${result.content.take(600)}"
            }

            "memory_list" -> h.memoryList()
            "memory_forget" -> h.memoryForget(arg ?: "")
            "memory_clear" -> h.memoryClear()

            else -> "unknown cmd '${cmd.name}'; try 'help'"
        }
    } catch (t: Throwable) {
        Log.w(AI_LOG_TAG, "ai_cmd ${cmd.name} failed", t)
        "error: ${t.message}"
    }
}
