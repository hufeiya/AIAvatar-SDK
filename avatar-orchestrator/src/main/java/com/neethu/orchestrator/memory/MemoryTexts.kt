package com.neethu.orchestrator.memory

import com.neethu.corelib.Lang

/**
 * 长期记忆全部提示词/工具文案的中英双语目录（docs/memory-feasibility.md §4.8）。
 * 照 PromptTexts 惯例：文案只允许出现在本文件，每个成员中英两版按相同顺序
 * 对齐，改一处必须同步另一语言（MemoryTextsI18nTest 断言 EN 版无中文字符）。
 */
interface MemoryTexts {
    val lang: Lang

    /** 虚拟人名缺省（无人物卡时提炼对话里助手行的前缀）。 */
    val defaultCharName: String

    // ── 自动提炼（通道 B）──────────────────────────────────────────────
    /** 提炼请求的 system：只输出一行 JSON ops,反讨好条款是防「没东西硬挤」的关键。 */
    val extractSystem: String

    /**
     * 提炼请求的 user：现有记忆清单（去重靠「清单进提炼 prompt」）+ 待提炼对话。
     */
    fun extractUser(memoryList: String, dialog: String): String

    /** 记忆清单行：`m_ab12cd34 [偏好] 用户不喜欢香菜`（updatedAt 倒序，上限 60 条）。 */
    fun listLine(id: String, categoryLabel: String, content: String): String

    /** 清单为空时的占位行。 */
    val listEmpty: String

    // ── [记忆参考] 注入块（末尾 user 前缀槽）─────────────────────────────
    val recallBlockHeader: String

    fun recallBlockLine(categoryLabel: String, content: String): String

    // ── 分类显示名（注入块与工具结果共用）────────────────────────────────
    val categoryProfile: String
    val categoryRelationship: String
    val categoryPreference: String
    val categoryEvent: String
    val categoryCommitment: String
    val categoryOther: String

    fun categoryLabel(category: String): String

    // ── remember 工具（通道 A）─────────────────────────────────────────
    val toolRememberDescription: String
    val toolRememberHint: String

    /** 记住成功的确认文案（模型第一人称转告）。 */
    fun toolRememberDone(content: String): String

    /** 缺少必填 content 参数的错误文案。 */
    val toolRememberArgError: String

    // ── recall 工具 ────────────────────────────────────────────────────
    val toolRecallDescription: String
    val toolRecallHint: String

    /** 空结果是正常结果（非 isError）：模型自然说「我还不了解这个」。 */
    val toolRecallEmpty: String

    /** 命中行：`m_ab12 [偏好] 用户不喜欢香菜`。 */
    fun toolRecallLine(id: String, categoryLabel: String, content: String): String

    // ── forget 工具 ────────────────────────────────────────────────────
    val toolForgetDescription: String
    val toolForgetHint: String

    fun toolForgetDone(id: String): String

    /** id 未命中：isError,提示先用 recall 查 id（防小模型瞎编 id 误删）。 */
    val toolForgetMiss: String

    val toolForgetArgError: String

    companion object {
        fun of(lang: Lang): MemoryTexts = if (lang == Lang.EN) MemoryTextsEn else MemoryTextsZh
    }
}

fun memoryTextsOf(lang: Lang): MemoryTexts = MemoryTexts.of(lang)

// ─────────────────────────────────────────────────────────────────────────────
// 简体中文（默认）
// ─────────────────────────────────────────────────────────────────────────────

object MemoryTextsZh : MemoryTexts {
    override val lang: Lang = Lang.ZH

    override val defaultCharName: String = "虚拟人"

    override val extractSystem: String =
        "你在维护虚拟人关于用户的长期记忆库。阅读下面的对话片段(自上次整理以来的若干轮)," +
            "输出要做的记忆变更,只输出一行 JSON,不要输出任何其他内容:\n" +
            "{\"ops\":[{\"action\":\"add\",\"content\":\"第三人称原子事实\",\"category\":\"...\",\"importance\":5}]}\n" +
            "category 取值:profile(身份)|relationship(重要的人/宠物)|preference(偏好/习惯)|" +
            "event(发生的事/计划)|commitment(答应用户的事)\n" +
            "规则:\n" +
            "- 只记与用户相关的、之后对话用得上的稳定事实:身份、偏好、生活事件与计划、" +
            "约定、用户提到的重要的人和宠物。\n" +
            "- 大多数片段只有 0~2 条值得记;输出空 ops {\"ops\":[]} 是完全正常且常见的结局," +
            "不要为了产出而编造或把琐碎内容拔高成记忆。\n" +
            "- 一条一个原子事实,写具体细节(名字/数字/日期),一次最多 5 条,宁缺毋滥。\n" +
            "- 这些不要记:寒暄与客套、你(虚拟人)自己说的话、用户一次性的情绪表达、" +
            "记忆清单里已有的内容、用户没有明确确认的猜测、与画面/照片相关的临时内容。\n" +
            "- 需要更正或删除已有记忆时:{\"action\":\"update\",\"id\":\"...\",\"content\":\"...\"} " +
            "或 {\"action\":\"delete\",\"id\":\"...\"}(id 见记忆清单;不确定就别删)。"

    override fun extractUser(memoryList: String, dialog: String): String =
        "[现有记忆清单]\n$memoryList\n\n[待提炼对话]\n$dialog"

    override fun listLine(id: String, categoryLabel: String, content: String): String =
        "$id [$categoryLabel] $content"

    override val listEmpty: String = "(还没有任何记忆)"

    override val recallBlockHeader: String = "[记忆参考]"

    override fun recallBlockLine(categoryLabel: String, content: String): String =
        "- ($categoryLabel) $content"

    override val categoryProfile: String = "身份"
    override val categoryRelationship: String = "关系"
    override val categoryPreference: String = "偏好"
    override val categoryEvent: String = "事件"
    override val categoryCommitment: String = "约定"
    override val categoryOther: String = "其他"

    override fun categoryLabel(category: String): String = when (category) {
        MemoryCategories.PROFILE -> categoryProfile
        MemoryCategories.RELATIONSHIP -> categoryRelationship
        MemoryCategories.PREFERENCE -> categoryPreference
        MemoryCategories.EVENT -> categoryEvent
        MemoryCategories.COMMITMENT -> categoryCommitment
        else -> categoryOther
    }

    override val toolRememberDescription: String =
        "把用户明确要求记住的事写进长期记忆(一次一条)。"

    override val toolRememberHint: String =
        "用户明确请你记住什么、或你们约定了要做什么事时调用;把要点写成一条具体的事实" +
            "(含名字/日期等细节),不要把闲聊逐句存进来。"

    override fun toolRememberDone(content: String): String = "已记住:$content"

    override val toolRememberArgError: String =
        "缺少必填参数 content(要记住的事,一句话)。请带上参数重试。"

    override val toolRecallDescription: String =
        "按关键词搜索关于用户的长期记忆,返回命中的条目(id+内容)。"

    override val toolRecallHint: String =
        "想回忆和用户的过往、或不确定用户是否提过某事时,先调用本工具再回答,不要编造;" +
            "忘掉某事前也先用它查到对应条目的 id。"

    override val toolRecallEmpty: String =
        "没有找到相关记忆。(这不是错误:如实告诉用户你还不了解这件事即可)"

    override fun toolRecallLine(id: String, categoryLabel: String, content: String): String =
        "$id [$categoryLabel] $content"

    override val toolForgetDescription: String =
        "从长期记忆里删除一条(id 精确匹配,来自 recall 的结果)。"

    override val toolForgetHint: String =
        "用户请你忘掉某事时调用;先用 recall 找到对应记忆的 id 再删,不确定对应哪条就" +
            "如实告诉用户可以在设置的记忆管理里手动删除。"

    override fun toolForgetDone(id: String): String = "已删除记忆 $id。"

    override val toolForgetMiss: String =
        "没有找到这个 id 对应的记忆。先用 recall 查到确切的 id 再删;找不到就告诉用户" +
            "可以在设置的记忆管理里手动删除。"

    override val toolForgetArgError: String =
        "缺少必填参数 id(要删除的记忆 id,来自 recall 的结果)。请带上参数重试。"
}

// ─────────────────────────────────────────────────────────────────────────────
// English
// ─────────────────────────────────────────────────────────────────────────────

object MemoryTextsEn : MemoryTexts {
    override val lang: Lang = Lang.EN

    override val defaultCharName: String = "the avatar"

    override val extractSystem: String =
        "You maintain the virtual companion's long-term memory about the user. Read the " +
            "conversation fragment below (a few turns since the last consolidation) and output " +
            "the memory changes as a single line of JSON — nothing else:\n" +
            "{\"ops\":[{\"action\":\"add\",\"content\":\"a third-person atomic fact\",\"category\":\"...\",\"importance\":5}]}\n" +
            "category is one of: profile (identity) | relationship (important people/pets) | " +
            "preference (tastes/habits) | event (things happening/plans) | commitment (things promised to the user)\n" +
            "Rules:\n" +
            "- Record only stable, user-related facts useful in later conversation: identity, " +
            "preferences, life events and plans, promises, important people and pets the user mentions.\n" +
            "- Most fragments contain 0-2 items worth remembering; an empty ops {\"ops\":[]} is a " +
            "perfectly normal and common outcome — never invent memories or inflate trivia to have output.\n" +
            "- One atomic fact per item, with concrete details (names/numbers/dates); at most 5 " +
            "items per call; prefer fewer.\n" +
            "- Do NOT record: small talk and pleasantries, your own (the avatar's) lines, the " +
            "user's one-off emotional expressions, anything already in the memory list, unconfirmed " +
            "guesses, or anything tied to a transient photo/screenshot.\n" +
            "- To correct or remove an existing memory: {\"action\":\"update\",\"id\":\"...\",\"content\":\"...\"} " +
            "or {\"action\":\"delete\",\"id\":\"...\"} (ids are in the memory list; when unsure, don't delete)."

    override fun extractUser(memoryList: String, dialog: String): String =
        "[Existing memory list]\n$memoryList\n\n[Conversation to extract from]\n$dialog"

    override fun listLine(id: String, categoryLabel: String, content: String): String =
        "$id [$categoryLabel] $content"

    override val listEmpty: String = "(no memories yet)"

    override val recallBlockHeader: String = "[Memory references]"

    override fun recallBlockLine(categoryLabel: String, content: String): String =
        "- ($categoryLabel) $content"

    override val categoryProfile: String = "identity"
    override val categoryRelationship: String = "relationship"
    override val categoryPreference: String = "preference"
    override val categoryEvent: String = "event"
    override val categoryCommitment: String = "commitment"
    override val categoryOther: String = "other"

    override fun categoryLabel(category: String): String = when (category) {
        MemoryCategories.PROFILE -> categoryProfile
        MemoryCategories.RELATIONSHIP -> categoryRelationship
        MemoryCategories.PREFERENCE -> categoryPreference
        MemoryCategories.EVENT -> categoryEvent
        MemoryCategories.COMMITMENT -> categoryCommitment
        else -> categoryOther
    }

    override val toolRememberDescription: String =
        "Write something the user explicitly asked you to remember into long-term memory (one item per call)."

    override val toolRememberHint: String =
        "Call when the user explicitly asks you to remember something or when you two make a plan; " +
            "phrase it as one concrete fact (with names/dates), never store small talk verbatim."

    override fun toolRememberDone(content: String): String = "Remembered: $content"

    override val toolRememberArgError: String =
        "Missing required argument 'content' (the thing to remember, one sentence). Retry with it."

    override val toolRecallDescription: String =
        "Search long-term memories about the user by keywords; returns matching entries (id + content)."

    override val toolRecallHint: String =
        "Call before answering anything about your shared past or when unsure whether the user " +
            "mentioned something — never make it up; also call it first to find an id before forgetting."

    override val toolRecallEmpty: String =
        "No matching memories found. (Not an error: honestly tell the user you don't know yet.)"

    override fun toolRecallLine(id: String, categoryLabel: String, content: String): String =
        "$id [$categoryLabel] $content"

    override val toolForgetDescription: String =
        "Delete one long-term memory by its exact id (from recall results)."

    override val toolForgetHint: String =
        "Call when the user asks you to forget something; find the exact id with recall first. " +
            "If unsure which one, tell the user they can remove it in the memory management panel."

    override fun toolForgetDone(id: String): String = "Deleted memory $id."

    override val toolForgetMiss: String =
        "No memory matches that id. Use recall to find the exact id first; if there's no match, " +
            "tell the user they can remove it in the memory management panel."

    override val toolForgetArgError: String =
        "Missing required argument 'id' (the memory id to delete, from recall). Retry with it."
}
