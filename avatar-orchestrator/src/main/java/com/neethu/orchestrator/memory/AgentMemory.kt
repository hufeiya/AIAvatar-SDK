package com.neethu.orchestrator.memory

/**
 * 一条长期记忆 = 一句第三人称原子事实（docs/memory-feasibility.md §4.1）。
 * 纯 JVM 数据类，Room 实体与它互相独立（[RoomMemoryStore] 负责转换）。
 *
 * @property content 一句原子事实，≤[MemoryLimits.CONTENT_MAX_CHARS] 字（落库前截断）。
 * @property category 封闭分类，取值见 [MemoryCategories]；未知值一律归 `other`。
 * @property importance 1-10：提炼缺省 5、remember 工具缺省 8（显式「记住」的事
 *   排常驻槽候选）；≥8 的身份/关系事实进检索的常驻槽。
 * @property source "tool"(remember 工具) | "auto"(自动提炼) | "manual"(管理面板)。
 * @property embedding bge-m3 1024 维向量；null = 未嵌入（写入时 embed 失败或
 *   集成方没配 embeddings 服务）——检索走关键词路，内容不丢。
 * @property avatarId 所属虚拟人分片（一屏多虚拟人 P1，[MemoryStore.scoped]
 *   写入时盖章）；"" = 全局共享（单虚拟人时代的历史数据/无分片写入）。
 */
data class AgentMemory(
    val id: String,
    val content: String,
    val category: String,
    val importance: Int,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 1,
    val embedding: FloatArray? = null,
    val avatarId: String = "",
) {
    /** embedding 是数组，data class 默认相等语义会退化成引用比较——覆盖修正。 */
    override fun equals(other: Any?): Boolean =
        other is AgentMemory &&
            id == other.id &&
            content == other.content &&
            category == other.category &&
            importance == other.importance &&
            source == other.source &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            lastAccessedAt == other.lastAccessedAt &&
            accessCount == other.accessCount &&
            avatarId == other.avatarId &&
            embedding.contentEquals(other.embedding)

    override fun hashCode(): Int = id.hashCode() * 31 + content.hashCode()

    override fun toString(): String =
        "AgentMemory($id [$category] imp=$importance src=$source \"${content.take(40)}\")"
}

/** 记忆的封闭分类与全场统一约束（docs/memory-feasibility.md §2.2 分类表的单点正本）。 */
object MemoryCategories {
    const val PROFILE = "profile"
    const val RELATIONSHIP = "relationship"
    const val PREFERENCE = "preference"
    const val EVENT = "event"
    const val COMMITMENT = "commitment"
    const val OTHER = "other"

    /** 合法全集（schema enum、提炼 parser 白名单、remember 工具校验共用）。 */
    val ALL = listOf(PROFILE, RELATIONSHIP, PREFERENCE, EVENT, COMMITMENT, OTHER)

    /** 时间衰减豁免集：身份/关系/偏好类恒定新鲜度（§4.5 修 airi 公式的坑）。 */
    val RECENCY_EXEMPT = setOf(PROFILE, RELATIONSHIP, PREFERENCE)

    /** 常驻槽资格：身份/关系类且 importance≥8 恒注入（§4.5）。 */
    val PINNED = setOf(PROFILE, RELATIONSHIP)

    fun normalize(raw: String?): String =
        raw?.trim()?.lowercase()?.let { ALL.firstOrNull { c -> c == it } } ?: OTHER
}

/** 记忆条目的来源标签（source 字段封闭集）。 */
object MemorySources {
    const val TOOL = "tool"
    const val AUTO = "auto"
    const val MANUAL = "manual"
}

/** 全场统一的内容长度约束（提炼 parser / remember 工具 / 管理面板共用）。 */
object MemoryLimits {
    const val CONTENT_MAX_CHARS = 120
    const val IMPORTANCE_MIN = 1
    const val IMPORTANCE_MAX = 10
    const val IMPORTANCE_AUTO_DEFAULT = 5
    const val IMPORTANCE_TOOL_DEFAULT = 8

    fun clampImportance(raw: Int?): Int =
        (raw ?: IMPORTANCE_AUTO_DEFAULT).coerceIn(IMPORTANCE_MIN, IMPORTANCE_MAX)

    fun clampContent(raw: String?): String =
        raw?.trim()?.take(CONTENT_MAX_CHARS).orEmpty()
}

/** 一次待提炼回合（用户原文 + 助手全量干净文本）。 */
data class MemoryTurn(val user: String, val assistant: String)
