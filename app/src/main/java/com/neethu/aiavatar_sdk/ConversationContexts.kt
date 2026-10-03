package com.neethu.aiavatar_sdk

import com.neethu.orchestrator.history.ConversationDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「上下文」类别的展示条目（任务 3）。[title] 是会话行的
 * characterId（激活卡片时的文件名），demo 层负责把它翻译成卡片名展示。
 */
data class ConversationContextSummary(
    val id: String,
    val characterId: String?,
    val updatedAt: Long,
    val messageCount: Int,
) {
    /** "05-14 09:32" 样式的最近使用时间。 */
    val updatedAtText: String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))
}

/** 读全部上下文（按最近使用排序 + 各自消息数）；调用方需在 IO 线程。 */
fun loadContextSummaries(db: ConversationDatabase): List<ConversationContextSummary> =
    db.sessionDao().sessions().map { s ->
        ConversationContextSummary(
            id = s.id,
            characterId = s.characterId,
            updatedAt = s.updatedAt,
            messageCount = db.messageDao().countFor(s.id),
        )
    }
