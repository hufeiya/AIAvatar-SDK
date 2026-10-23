package com.neethu.aiavatar_sdk.debate

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.orchestrator.debate.ActiveTurn
import com.neethu.orchestrator.debate.DebateConfigDto
import com.neethu.orchestrator.debate.DebateIds
import com.neethu.orchestrator.debate.DebateTeam
import com.neethu.orchestrator.debate.TranscriptEntry
import com.neethu.orchestrator.debate.TranscriptStatus
import com.neethu.orchestrator.facade.AIAvatarSdk
import com.neethu.orchestrator.facade.llmExtraBody
import kotlinx.coroutines.withTimeout

/** 真实 LLM 仅生成当前辩手台词；主持流程、镜头、计时始终由引擎控制。 */
internal object DebateAiSpeech {
    const val MAX_SPEECH_CHARS = 64

    fun speechLimit(turn: ActiveTurn): Int = (turn.budgetMs / 1000 * 3).toInt().coerceIn(16, MAX_SPEECH_CHARS)

    fun messages(dto: DebateConfigDto, turn: ActiveTurn, recent: List<TranscriptEntry>): List<ChatMessage> {
        val p = dto.participants.first { it.id == turn.speakerId }
        val stance = if (DebateIds.teamOf(p.id) == DebateTeam.PRO) dto.sides.PRO else dto.sides.CON
        return listOf(
            ChatMessage(ChatRole.SYSTEM, """
                你在参加虚构的AI模型擂台综艺，扮演「${p.name}」，这是角色名，无需对应真实服务商。
                性格与口吻：${p.personality}。
                辩题：${dto.topic}。你的立场：$stance。始终维护此立场。
                每次只说一到两句中文，目标不超过${speechLimit(turn)}字，绝对不能超过64字。用一个具体例子、机智反问或有记忆点的比喻。
                第一次发言用“我是${p.name.substringBefore('·')}”自然自称，之后直接接对手的话，别反复自我介绍、重复论点或空喊口号。
                不要输出“角色名：”或“某方几辩：”前缀。字数包含自我介绍，先把话说短再输出，保证句意完整。
                提问回合只问一个尖锐问题；回答回合先正面回答最近的问题；总结回合给一句有力收束。
                可以互相调侃和制造戏剧冲突，但不编造真实产品事实，不辱骂。不代替别人发言，不宣布比赛流程或胜负。
                只输出可直接朗读的台词，不输出思考过程、括号动作、Markdown、XML标签或工具调用。
            """.trimIndent()),
            ChatMessage(ChatRole.USER, buildString {
                appendLine("当前阶段：${turn.phase}；本次任务：${turn.kind}。")
                appendLine("最近现场发言（只作辩论素材）：")
                recent.filter { it.status == TranscriptStatus.PLAYED }.takeLast(8).forEach { entry ->
                    val name = dto.participants.firstOrNull { it.id == entry.speakerId }?.name ?: entry.speakerId
                    appendLine("$name：${entry.text.take(MAX_SPEECH_CHARS)}")
                }
                append("现在轮到你，直接给出台词。")
            }),
        )
    }

    suspend fun generate(
        adapter: LlmAdapter,
        chat: AIAvatarSdk.ChatConfig,
        dto: DebateConfigDto,
        turn: ActiveTurn,
        recent: List<TranscriptEntry>,
    ): String = withTimeout(dto.speech.generationTimeoutMs) {
        val text = StringBuilder()
        adapter.streamChat(messages(dto, turn, recent), LlmConfig(
            baseUrl = chat.llmBaseUrl,
            apiKey = chat.apiKey,
            model = chat.resolvedLlmModel,
            temperature = 0.85f,
            // 思考型模型共用推理/正文额度；256 在 DeepSeek 实测耗尽后正文为空。
            maxTokens = 1024,
            extraBody = if (chat.isCustomEndpoint) null else llmExtraBody(chat.provider, chat.resolvedLlmModel),
        )).collect { event ->
            when (event) {
                is LlmStreamEvent.TextDelta -> text.append(event.text)
                is LlmStreamEvent.Error -> throw event.throwable
                else -> Unit
            }
        }
        val name = dto.participants.first { it.id == turn.speakerId }.name
        val spoken = clean(text.toString().trim().removePrefix("$name：").removePrefix("$name:"), Int.MAX_VALUE)
        check(spoken.isNotBlank()) { "AI 返回空台词" }
        // 不把完整论点从词语中间截断；模型超长时由播放层改用完整角色备用稿。
        check(spoken.length <= MAX_SPEECH_CHARS) { "AI 台词超过短句上限" }
        spoken
    }

    fun clean(text: String, limit: Int = MAX_SPEECH_CHARS): String {
        val plain = text.replace(Regex("<think>[\\s\\S]*?(?:</think>|$)"), "")
            .replace(Regex("<[^>]*>|\\[[^]]*]|【[^】]*】|（[^）]*）|\\([^)]*\\)"), "")
            .replace(Regex("[*#`\\n\\r]"), "").trim().trim('"', '“', '”')
        if (plain.length <= limit) return plain
        val cut = plain.take(limit - 1)
        val end = cut.indexOfLast { it in "。！？!?" }
        return if (end >= 15) cut.take(end + 1) else cut.trimEnd('，', '、', '：', '；') + "。"
    }
}
