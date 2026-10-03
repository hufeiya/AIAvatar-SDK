package com.neethu.orchestrator.card

import java.io.File
import java.util.UUID

/**
 * File-backed card library: keeps the **original card bytes** (PNG or JSON —
 * never a re-serialization, `extensions` is a JsonObject that must not be
 * round-tripped through the data class) under [rootDir] and re-parses them on
 * demand, so any parser improvement applies to already-imported cards too.
 *
 * Pure `java.io.File`, no Android types — the save/read/parse round trip is
 * unit-testable on the JVM.
 */
class CharacterCardStore(private val rootDir: File) {

    /** One library entry: the stored file plus its freshly parsed card. */
    data class Entry(
        val fileName: String,
        val card: CharacterCard,
    )

    /** All entries, ordered by file name; files that no longer parse are skipped. */
    fun list(): List<Entry> =
        rootDir.listFiles { f -> f.isFile }
            ?.mapNotNull { file ->
                parseQuietly(file)?.let { Entry(file.name, it) }
            }
            ?.sortedBy { it.fileName }
            ?: emptyList()

    /**
     * Parse [bytes] and persist them verbatim. Returns null (and writes
     * nothing) when the bytes are not a readable card.
     */
    fun save(bytes: ByteArray): Entry? {
        val card = CharacterCardParser.parse(bytes) ?: return null
        rootDir.mkdirs()
        val ext = if (isPng(bytes)) "png" else "json"
        var fileName: String
        do {
            fileName = "${sanitize(card.name)}_${UUID.randomUUID().toString().take(8)}.$ext"
        } while (File(rootDir, fileName).exists())
        File(rootDir, fileName).writeBytes(bytes)
        return Entry(fileName, card)
    }

    /** Re-parse one stored file; null when it is missing or corrupt. */
    fun read(fileName: String): CharacterCard? =
        safeFile(fileName)?.let { parseQuietly(it) }

    /** Delete one stored file; true when something was removed. */
    fun delete(fileName: String): Boolean = safeFile(fileName)?.delete() ?: false

    /** [fileName] must be a bare name inside [rootDir] — no traversal. */
    private fun safeFile(fileName: String): File? {
        if (fileName.isEmpty() || fileName.contains('/') || fileName.contains('\\') ||
            fileName.contains("..")
        ) return null
        return File(rootDir, fileName).takeIf { it.isFile }
    }

    private fun parseQuietly(file: File): CharacterCard? =
        runCatching { CharacterCardParser.parse(file.readBytes()) }.getOrNull()

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

    /** Keep the card name usable as a file name; CJK counts as letters. */
    private fun sanitize(name: String): String =
        name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .trim()
            .take(32)
            .ifEmpty { "card" }
}
