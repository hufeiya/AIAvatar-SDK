package com.neethu.orchestrator.history

import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.ChatRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * [ConversationStore] 的 Room 实现（任务 3）——历史落库，进程重启可恢复。
 *
 * 设计取舍：
 * - [ConversationStore] 接口是同步的（AvatarSession 组装请求时同步读），写库
 *   却是异步的：内存镜像 [mirror] 是读路径的事实来源，追加/清空先动镜像，
 *   再经 [ioScope]（默认单线程串行）后台落库。进程存活期内读写顺序一致；
 *   仅进程被杀时最后一笔写库可能丢失（毫秒级窗口，demo 场景可接受）。
 * - 首次访问用 [runBlocking] 从库里懒加载一次（一次会话只发生一回，几十行
 *   的小查询，阻塞可忽略）；此后读路径不再碰库。
 * - 每次追加顺手 upsert 会话行（updatedAt/characterId），上下文列表由此按
 *   「最近使用」排序。
 *
 * @param ioScope 落库协程域；默认 `Dispatchers.IO` 限并发 1 以保证写入顺序。
 *   测试注入 `Dispatchers.Unconfined` 即可同步断言 DAO 状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConversationStore(
    private val sessionDao: ConversationSessionDao,
    private val messageDao: ConversationMessageDao,
    /** 上下文 id（集成方持久化；demo 存 demo_settings 的 ai_context_id）。 */
    val sessionId: String,
    /** 信息性字段：最近使用该上下文时的卡片标识，随会话行落库。 */
    val characterId: String? = null,
    private val ioScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
) : ConversationStore {

    private val lock = Any()
    private val mirror = ArrayDeque<ChatMessage>()
    private var loaded = false

    override fun messages(): List<ChatMessage> = synchronized(lock) {
        ensureLoaded()
        mirror.toList()
    }

    override fun appendUser(text: String) = append(ChatMessage(ChatRole.USER, text))

    override fun appendAssistant(text: String) = append(ChatMessage(ChatRole.ASSISTANT, text))

    override fun clear() {
        synchronized(lock) {
            ensureLoaded()
            mirror.clear()
        }
        ioScope.launch { messageDao.deleteFor(sessionId) }
    }

    private fun append(message: ChatMessage) {
        synchronized(lock) {
            ensureLoaded()
            mirror.addLast(message)
        }
        val row = MessageEntity(
            sessionId = sessionId,
            role = message.role.name,
            content = message.content,
            createdAt = System.currentTimeMillis(),
        )
        ioScope.launch {
            messageDao.insert(row)
            sessionDao.upsert(
                SessionEntity(
                    id = sessionId,
                    characterId = characterId,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /** 必须在 [lock] 内调用。 */
    private fun ensureLoaded() {
        if (loaded) return
        val rows = runBlocking(Dispatchers.IO) { messageDao.messagesFor(sessionId) }
        for (row in rows) {
            val role = runCatching { ChatRole.valueOf(row.role) }.getOrNull() ?: continue
            mirror.addLast(ChatMessage(role, row.content))
        }
        loaded = true
    }

    companion object {
        /** demo 集成入口。 */
        fun from(
            db: ConversationDatabase,
            sessionId: String,
            characterId: String? = null,
            ioScope: CoroutineScope? = null,
        ): RoomConversationStore =
            RoomConversationStore(
                db.sessionDao(), db.messageDao(), sessionId, characterId,
                ioScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
            )
    }
}
