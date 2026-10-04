package com.neethu.aiavatar_sdk

/**
 * 对话字幕面板的行与纯逻辑（用户消息 + 虚拟人流式回复同列展示，可滚动回看，
 * 自动贴底）。抽成纯函数便于 JVM 单测——面板本身在 MainActivity.AiChatBar。
 */
enum class ChatRole { USER, AVATAR }

data class ChatLine(val role: ChatRole, val text: String)

object ChatTranscript {
    /** 面板最多保留的行数，更早的行从顶部挤出。 */
    const val MAX_LINES = 60

    /**
     * 追加一段文本。[role] 为 AVATAR 且最后一行也是 AVATAR 时并入最后一行
     * （虚拟人逐句开播，句文本流式拼成整段回复）；USER 永远新起一行——
     * 连续两条用户消息是两次独立发言，不能拼成一条。
     */
    fun append(lines: List<ChatLine>, role: ChatRole, text: String): List<ChatLine> {
        if (text.isEmpty()) return lines
        val last = lines.lastOrNull()
        val merge = last != null && last.role == role && role == ChatRole.AVATAR
        return if (merge) {
            lines.dropLast(1) + last.copy(text = last.text + text)
        } else {
            lines + ChatLine(role, text)
        }
    }

    /** 超出 [MAX_LINES] 的旧行从头裁掉。 */
    fun cap(lines: List<ChatLine>): List<ChatLine> =
        if (lines.size <= MAX_LINES) lines else lines.drop(lines.size - MAX_LINES)
}
