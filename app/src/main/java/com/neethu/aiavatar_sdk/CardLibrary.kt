package com.neethu.aiavatar_sdk

import android.content.Context
import android.net.Uri
import com.neethu.orchestrator.card.CharacterCardStore
import org.json.JSONArray
import java.io.File

private const val KEY_AI_CARDS = "ai_cards"
private const val KEY_AI_ACTIVE_CARD = "ai_active_card"

/**
 * Demo 侧的人物卡库：原始 bytes 落在 `filesDir/cards`（[CharacterCardStore]，
 * 永不序列化 data class），导入顺序的文件名索引与当前激活卡片持久化到
 * demo_settings（`ai_cards` / `ai_active_card`）。
 *
 * 索引与磁盘互为校对：启动时逐个重解析索引里的文件，缺失/损坏的条目自动
 * 剔除并回写，这样偏好被清或文件被手动删都不至于卡死 UI。
 */
internal class CardLibrary(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val store = CharacterCardStore(File(context.filesDir, "cards"))

    /** 已导入的卡片，按导入顺序。 */
    var cards: List<CharacterCardStore.Entry> = loadCards()
        private set

    /** 当前激活卡片的文件名；null = 未激活。 */
    var activeFile: String? = prefs.getString(KEY_AI_ACTIVE_CARD, null)
        private set

    /**
     * 从 SAF [Uri] 导入：读 bytes → parse → 落盘 → 追加索引。
     * 返回 null 表示文件不可读或不是可解析的角色卡。
     */
    fun import(context: Context, uri: Uri): CharacterCardStore.Entry? {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return null
        return importBytes(bytes)
    }

    /** [import] 的字节级入口，供 adb 调试命令复用同一条导入路径。 */
    fun importBytes(bytes: ByteArray): CharacterCardStore.Entry? {
        val entry = store.save(bytes) ?: return null
        cards = cards + entry
        saveIndex()
        return entry
    }

    /** 激活/取消激活（传 null）卡片；持久化到 ai_active_card。 */
    fun setActive(fileName: String?) {
        activeFile = fileName
        prefs.edit().putString(KEY_AI_ACTIVE_CARD, fileName).apply()
    }

    /** 删除卡片文件并移出索引；若删的是激活卡则同时取消激活。 */
    fun delete(fileName: String): Boolean {
        val removed = store.delete(fileName)
        cards = cards.filter { it.fileName != fileName }
        saveIndex()
        if (activeFile == fileName) setActive(null)
        return removed
    }

    /** 逐个重解析索引中的文件；与磁盘不一致时回写索引。 */
    private fun loadCards(): List<CharacterCardStore.Entry> {
        val names = mutableListOf<String>()
        try {
            val arr = JSONArray(prefs.getString(KEY_AI_CARDS, "[]") ?: "[]")
            for (i in 0 until arr.length()) names += arr.getString(i)
        } catch (_: Exception) {
        }
        val entries = names.mapNotNull { name ->
            store.read(name)?.let { CharacterCardStore.Entry(name, it) }
        }
        if (entries.map { it.fileName } != names) saveIndex(entries)
        return entries
    }

    private fun saveIndex(entries: List<CharacterCardStore.Entry> = cards) {
        prefs.edit()
            .putString(
                KEY_AI_CARDS,
                JSONArray().apply { entries.forEach { put(it.fileName) } }.toString(),
            )
            .apply()
    }
}
