package com.neethu.orchestrator.memory

import android.content.Context

/**
 * 关系状态的 Room 落地（酒馆 P3-1）：DAO 全 suspend 形态（Room 自带查询
 * 执行器调度，主线程调用安全——2026-10-25 真机 main-thread 崩溃的修正）。
 * 单行主键读写毫秒级，无需再开异步写池。存不用 Room 跑 JVM 单测
 * （Fake DAO 直灌，见 RoomRelationshipStoreTest）。
 */
class RoomRelationshipStore(
    private val dao: RelationshipStateDao,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : RelationshipStore {

    override suspend fun load(avatarId: String): RelationshipRecord? =
        dao.getFor(avatarId)?.toRecord()

    override suspend fun save(record: RelationshipRecord) {
        dao.upsert(
            RelationshipStateEntity(
                avatarId = record.avatarId,
                favorability = record.favorability.coerceIn(0, 100),
                anniversaryEpochDay = record.anniversaryEpochDay,
                nickname = record.nickname?.takeIf { it.isNotBlank() },
                updatedAt = nowMs(),
            ),
        )
    }

    override suspend fun clear(avatarId: String): Boolean = dao.clearFor(avatarId) > 0

    private fun RelationshipStateEntity.toRecord() = RelationshipRecord(
        avatarId = avatarId,
        favorability = favorability,
        anniversaryEpochDay = anniversaryEpochDay,
        nickname = nickname,
        updatedAt = updatedAt,
    )

    companion object {
        /** app/companion 一行接入：与记忆同库（MemoryDatabase v3）。 */
        fun from(context: Context): RoomRelationshipStore =
            RoomRelationshipStore(MemoryDatabase.getInstance(context).relationshipDao())
    }
}
