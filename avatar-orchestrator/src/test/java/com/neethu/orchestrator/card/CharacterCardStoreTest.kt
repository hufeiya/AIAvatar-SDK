package com.neethu.orchestrator.card

import com.neethu.aiadapter.api.LlmAdapter
import com.neethu.aiadapter.model.ChatMessage
import com.neethu.aiadapter.model.LlmConfig
import com.neethu.aiadapter.model.LlmStreamEvent
import com.neethu.orchestrator.session.AvatarSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

class CharacterCardStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val v2CardJson = """
        {"spec":"chara_card_v2","spec_version":"2.0","data":{
          "name":"星野","description":"一位温柔的向导","personality":"耐心",
          "scenario":"深夜的图书馆","first_mes":"晚上好，{{user}}。我是{{char}}。"
        }}
    """.trimIndent()

    // ── 持久化往返 ─────────────────────────────────────────────────────────

    @Test
    fun `save writes original bytes and list re-parses them`() {
        val dir = tmp.newFolder("cards")
        val store = CharacterCardStore(dir)

        val entry = store.save(v2CardJson.toByteArray())!!

        // 原始字节原样落盘（不序列化 data class）
        val saved = File(dir, entry.fileName)
        assertTrue(saved.isFile)
        assertEquals(v2CardJson, saved.readText())
        assertTrue("json bytes → .json file", entry.fileName.endsWith(".json"))
        // 文件名可读：卡名清洗 + 短随机后缀
        assertTrue(entry.fileName.startsWith("星野_"))

        val listed = store.list()
        assertEquals(1, listed.size)
        assertEquals(entry.fileName, listed[0].fileName)
        assertEquals("星野", listed[0].card.name)
        assertEquals("一位温柔的向导", listed[0].card.description)
        assertEquals("深夜的图书馆", listed[0].card.scenario)
        assertEquals("chara_card_v2", listed[0].card.spec)
    }

    @Test
    fun `read parse save round trip then setCharacterCard builds prompt with description`() {
        val store = CharacterCardStore(tmp.newFolder("cards"))
        val entry = store.save(v2CardJson.toByteArray())!!

        val card = store.read(entry.fileName)
        assertNotNull(card)

        val session = AvatarSession(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            llm = NeverCalledLlm(),
            tts = UnusedTts,
            controller = null,
            playbackQueue = NoopQueue,
            lipSyncProcessor = null,
        )
        session.setCharacterCard(card!!)
        assertTrue(session.systemPrompt.contains("一位温柔的向导"))
        assertTrue(session.systemPrompt.contains("深夜的图书馆"))
        // 协议块自任务 8 起在 send 时统一追加（§7.4），systemPrompt 只含人设
        assertFalse(session.systemPrompt.contains("Multimodal protocol"))

        session.clearCharacterCard()
        assertEquals("", session.systemPrompt)
    }

    @Test
    fun `png bytes are stored with png extension and survive reload`() {
        val store = CharacterCardStore(tmp.newFolder("cards"))
        val png = pngWithChara("""{"name":"图卡","description":"来自图片"}""")

        val entry = store.save(png)!!
        assertTrue(entry.fileName.endsWith(".png"))
        assertTrue(entry.fileName.startsWith("图卡_"))

        val reloaded = store.list()
        assertEquals(1, reloaded.size)
        assertEquals("图卡", reloaded[0].card.name)
    }

    @Test
    fun `save rejects unparseable bytes without writing anything`() {
        val dir = tmp.newFolder("cards")
        val store = CharacterCardStore(dir)

        assertNull(store.save("not a card at all".toByteArray()))
        assertNull(store.save(ByteArray(0)))
        assertTrue(dir.listFiles().isNullOrEmpty())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `delete removes the file and drops it from list`() {
        val store = CharacterCardStore(tmp.newFolder("cards"))
        val a = store.save(v2CardJson.toByteArray())!!
        val b = store.save("""{"name":"第二张"}""".toByteArray())!!

        assertTrue(store.delete(a.fileName))
        assertEquals(listOf(b.fileName), store.list().map { it.fileName })
        assertNull(store.read(a.fileName))
        assertNotNull(store.read(b.fileName))
    }

    @Test
    fun `read and delete reject path traversal`() {
        val store = CharacterCardStore(tmp.newFolder("cards"))
        assertNull(store.read("../escape.json"))
        assertNull(store.read("sub/dir.json"))
        assertFalse(store.delete("../escape.json"))
        assertFalse(store.delete(""))
    }

    // ── spokenGreeting 宏 ─────────────────────────────────────────────────

    @Test
    fun `spokenGreeting resolves char and user macros`() {
        val card = CharacterCardParser.fromJson(v2CardJson)!!
        assertEquals("晚上好，你。我是星野。", card.spokenGreeting())
        // 可自定义 user 别名；无宏时原样返回
        assertEquals("晚上好，旅行者。我是星野。", card.spokenGreeting("旅行者"))
    }

    @Test
    fun `spokenGreeting is case insensitive on macros`() {
        val card = CharacterCardParser.fromJson(
            """{"name":"Nova","first_mes":"{{CHAR}} waves at {{User}}!"}"""
        )!!
        assertEquals("Nova waves at 你!", card.spokenGreeting())
    }

    // ── test doubles ──────────────────────────────────────────────────────

    private class NeverCalledLlm : LlmAdapter {
        override fun streamChat(messages: List<ChatMessage>, config: LlmConfig): Flow<LlmStreamEvent> {
            error("speak/setCharacterCard must not touch the LLM")
        }
    }

    private object UnusedTts : com.neethu.aiadapter.api.TtsAdapter {
        override suspend fun synthesize(text: String, config: com.neethu.aiadapter.model.TtsConfig) =
            error("not used in this test")
    }

    private object NoopQueue : com.neethu.orchestrator.audio.PlaybackQueue {
        override var listener: com.neethu.orchestrator.audio.PlaybackQueue.Listener? = null
        override fun active() = null
        override fun enqueue(item: com.neethu.orchestrator.audio.PlaybackItem) = Unit
        override fun stopAll(reason: String) = Unit
        override fun release() = Unit
    }

    // ── helpers: minimal PNG container with a tEXt chara chunk ────────────

    private fun pngWithChara(cardJson: String): ByteArray {
        val payload = Base64.getEncoder().encodeToString(cardJson.toByteArray())
        val keyword = "chara".toByteArray(Charsets.US_ASCII)
        val text = payload.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(keyword.size + 1 + text.size)
        System.arraycopy(keyword, 0, data, 0, keyword.size)
        System.arraycopy(text, 0, data, keyword.size + 1, text.size)

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(chunk("tEXt", data))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val len = data.size
        out.write(byteArrayOf(
            (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
        ))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(ByteArray(4)) // CRC — the parser does not verify
        return out.toByteArray()
    }
}
