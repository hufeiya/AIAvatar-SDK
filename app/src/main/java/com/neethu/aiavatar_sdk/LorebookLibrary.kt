package com.neethu.aiavatar_sdk

import android.content.Context
import android.content.SharedPreferences
import com.neethu.orchestrator.card.Lorebook
import com.neethu.orchestrator.card.LorebookParser
import com.neethu.orchestrator.session.AvatarSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val KEY_LOREBOOK_INDEX = "lorebook_index"
private const val KEY_LOREBOOK_BINDINGS = "lorebook_bindings"
private const val KEY_LOREBOOK_GLOBAL = "lorebook_global"
private const val KEY_LOREBOOK_ENABLED = "lorebook_enabled"

/**
 * demo 侧世界书库（酒馆 P1-1，docs/tavern-companion-feasibility.md）：书 JSON
 * 落盘 `filesDir/lorebooks/`，索引/卡绑定/全局书/开关持久化到 demo_settings。
 *
 * 生效优先级（[activeBookFor]）：**当前卡绑定 > 全局书**。卡导入时自动提取
 * 内嵌 `character_book`（OMate 同款行为，[importFromCard]）并落库绑定该卡；
 * 独立 ST World Info JSON 经 [import] 落库，可设全局或绑定当前卡。
 *
 * 索引与磁盘互为校对（与 CardLibrary 同语义）：启动时逐个重解析，缺失/损坏
 * 条目自动剔除并回写。
 */
internal class LorebookLibrary(context: Context) {

    /** 世界书库的一个条目：落盘文件名 + 解析产物 + 来源。 */
    data class Entry(
        val fileName: String,
        val book: Lorebook,
        /** 来源：`embedded`（卡导入自动提取）或 `imported`（用户 SAF 导入）。 */
        val source: String,
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "lorebooks")

    // ⚠ 声明顺序即初始化顺序：loadEntries() 反推书来源时要读 bindings，
    // 必须先于 entries 初始化（真机踩过：顺序颠倒=启动 NPE 闪退）。
    /** 当前卡文件名 → 书文件名。 */
    private val bindings: MutableMap<String, String> = loadBindings()

    var entries: List<Entry> = loadEntries()
        private set

    /** 全局书文件名；null = 未设。 */
    var globalFile: String? = prefs.getString(KEY_LOREBOOK_GLOBAL, null)
        private set

    /** 世界书总开关（默认开）。 */
    var enabled: Boolean = prefs.getBoolean(KEY_LOREBOOK_ENABLED, true)
        private set

    fun setEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean(KEY_LOREBOOK_ENABLED, value).apply()
    }

    /**
     * 导入一份独立世界书 JSON（ST World Info 导出格式）。
     * @return 条目；null = 不可读/不是可解析的世界书。
     */
    fun import(bytes: ByteArray, suggestedName: String?): Entry? {
        val book = LorebookParser.parse(String(bytes, Charsets.UTF_8)) ?: return null
        return save(book, source = "imported", suggestedName)
    }

    /**
     * 卡导入自动提取内嵌书（OMate 同款行为）：书落库并绑定该卡。
     * @return 条目；null = 卡不带书。
     */
    fun importFromCard(cardFile: String, book: Lorebook): Entry? {
        if (book.entries.isEmpty()) return null
        val entry = save(book, source = "embedded", suggestedName = cardFile) ?: return null
        bindCard(cardFile, entry.fileName)
        return entry
    }

    /** 当前卡绑定书（null = 未绑定）。 */
    fun boundFile(cardFile: String?): String? = cardFile?.let { bindings[it] }

    /** 设置当前卡的书绑定；null = 解绑。 */
    fun bindCard(cardFile: String, bookFile: String?) {
        if (bookFile == null) bindings.remove(cardFile) else bindings[cardFile] = bookFile
        saveBindings()
    }

    /** 设全局书（null = 清除）。 */
    fun setGlobal(bookFile: String?) {
        globalFile = bookFile
        prefs.edit().putString(KEY_LOREBOOK_GLOBAL, bookFile).apply()
    }

    /** 生效书：当前卡绑定 > 全局书；[cardFile] 传 null 时只看全局。 */
    fun activeBookFor(cardFile: String?): Lorebook? {
        val bound = boundFile(cardFile)?.let { f -> entries.firstOrNull { it.fileName == f }?.book }
        if (bound != null) return bound
        val global = globalFile?.let { f -> entries.firstOrNull { it.fileName == f }?.book }
        return global
    }

    /** 把生效书应用到会话（激活卡/取消激活/会话重建后调用）。 */
    fun applyTo(session: AvatarSession, cardFile: String?) {
        session.lorebook = activeBookFor(cardFile)
        session.lorebookEnabled = enabled
    }

    /** 删除书文件并移出索引/绑定/全局位。 */
    fun delete(bookFile: String): Boolean {
        val removed = File(dir, bookFile).delete()
        entries = entries.filter { it.fileName != bookFile }
        bindings.values.removeAll { it == bookFile }
        saveBindings()
        if (globalFile == bookFile) setGlobal(null)
        saveIndex()
        return removed
    }

    // ── 落盘与索引 ────────────────────────────────────────────────────────

    private fun save(book: Lorebook, source: String, suggestedName: String?): Entry? {
        dir.mkdirs()
        val base = (suggestedName?.substringAfterLast('/')?.removeSuffix(".json")
            ?.ifBlank { null } ?: "lorebook")
            .replace(Regex("[^A-Za-z0-9_\\-\\u4e00-\\u9fa5]+"), "_")
        var file = File(dir, "$base.json")
        var n = 1
        while (file.exists()) file = File(dir, "${base}_${n++}.json")
        // 落盘原始可读 JSON（条目数组形式），损坏时用户可直接查看/手修
        val ok = runCatching {
            file.writeText(bookToJson(book))
        }.isSuccess
        if (!ok) return null
        val entry = Entry(file.name, book, source)
        entries = entries + entry
        saveIndex()
        return entry
    }

    private fun bookToJson(book: Lorebook): String {
        val arr = JSONArray()
        for (e in book.entries) {
            arr.put(
                JSONObject()
                    .put("keys", JSONArray(e.keys))
                    .put("secondary_keys", JSONArray(e.secondaryKeys))
                    .put("content", e.content)
                    .put("insertion_order", e.insertionOrder)
                    .put("constant", e.constant)
                    .put("selective", e.selective)
                    .put("case_sensitive", e.caseSensitive)
                    .put("enabled", e.enabled)
                    .put("position", e.position)
                    .put("comment", e.name),
            )
        }
        return JSONObject().put("name", book.name).put("entries", arr).toString(2)
    }

    private fun loadEntries(): List<Entry> {
        // 索引为 [{"file":..,"source":..}] 对象数组；旧版纯文件名数组兼容读取
        //（source 按绑定关系反推）。embedded/imported 是 UI 徽标，不参与注入。
        val items = mutableListOf<Pair<String, String>>() // (fileName, source)
        try {
            val arr = JSONArray(prefs.getString(KEY_LOREBOOK_INDEX, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val item = arr.get(i)
                when (item) {
                    is JSONObject -> items += item.getString("file") to item.optString("source", "imported")
                    is String -> items += item to "imported"
                }
            }
        } catch (_: Exception) {
        }
        val loaded = items.mapNotNull { (name, source) ->
            val book = runCatching {
                LorebookParser.parse(File(dir, name).readText())
            }.getOrNull() ?: return@mapNotNull null
            Entry(name, book, source)
        }
        if (loaded.map { it.fileName } != items.map { it.first }) saveIndex(loaded)
        return loaded
    }

    private fun saveIndex(list: List<Entry> = entries) {
        prefs.edit()
            .putString(
                KEY_LOREBOOK_INDEX,
                JSONArray().apply {
                    list.forEach { put(JSONObject().put("file", it.fileName).put("source", it.source)) }
                }.toString(),
            )
            .apply()
    }

    private fun loadBindings(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val obj = JSONObject(prefs.getString(KEY_LOREBOOK_BINDINGS, "{}") ?: "{}")
            for (key in obj.keys()) map[key] = obj.getString(key)
        } catch (_: Exception) {
        }
        return map
    }

    private fun saveBindings() {
        prefs.edit()
            .putString(KEY_LOREBOOK_BINDINGS, JSONObject(bindings).toString())
            .apply()
    }
}
