package com.aicodemax.tools.browser

import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.core.common.runOutcome
import java.io.File
import java.io.InputStream
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** CP-26 remainder: history + bookmarks + downloads + AI handoff (file-backed, offline-first). */
@Serializable
data class HistoryEntry(
    val id: String,
    val url: String,
    val title: String,
    val visitedAt: Long,
)

@Serializable
data class Bookmark(
    val id: String,
    val url: String,
    val title: String,
    val createdAt: Long,
)

class BrowserLibrary(
    rootDir: File,
    private val clock: Clock = SystemClock,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val historySer = ListSerializer(HistoryEntry.serializer())
    private val bookmarkSer = ListSerializer(Bookmark.serializer())
    private val base: File = rootDir.apply { mkdirs() }
    private val historyFile: File = File(base, "history.json")
    private val bookmarkFile: File = File(base, "bookmarks.json")

    private fun readHistory(): MutableList<HistoryEntry> {
        if (!historyFile.exists()) return mutableListOf()
        val text = historyFile.readText()
        if (text.isBlank()) return mutableListOf()
        return json.decodeFromString(historySer, text).toMutableList()
    }

    @Synchronized
    fun visit(url: String, title: String, maxEntries: Int = 500): Outcome<HistoryEntry> =
        runOutcome("BROWSER_HISTORY") {
            check(url.isNotBlank()) { "url is blank" }
            val all = readHistory()
            val entry = HistoryEntry(Ids.newId("hist"), url.trim(), title.trim(), clock.nowMillis())
            all.add(entry)
            val kept = all.sortedByDescending { it.visitedAt }.take(maxEntries.coerceAtLeast(1))
            historyFile.writeText(json.encodeToString(historySer, kept))
            entry
        }

    @Synchronized
    fun recent(limit: Int = 50): Outcome<List<HistoryEntry>> = runOutcome("BROWSER_HISTORY") {
        readHistory().sortedByDescending { it.visitedAt }.take(limit.coerceAtLeast(0))
    }

    @Synchronized
    fun searchHistory(query: String, limit: Int = 50): Outcome<List<HistoryEntry>> =
        runOutcome("BROWSER_HISTORY") {
            readHistory()
                .filter { it.url.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true) }
                .sortedByDescending { it.visitedAt }
                .take(limit.coerceAtLeast(0))
        }

    @Synchronized
    fun clearHistory(): Outcome<Int> = runOutcome("BROWSER_HISTORY") {
        val count = readHistory().size
        historyFile.writeText("[]")
        count
    }

    private fun readBookmarks(): MutableList<Bookmark> {
        if (!bookmarkFile.exists()) return mutableListOf()
        val text = bookmarkFile.readText()
        if (text.isBlank()) return mutableListOf()
        return json.decodeFromString(bookmarkSer, text).toMutableList()
    }

    @Synchronized
    fun addBookmark(url: String, title: String): Outcome<Bookmark> =
        runOutcome("BROWSER_BOOKMARK") {
            check(url.isNotBlank()) { "url is blank" }
            val all = readBookmarks()
            val existing = all.firstOrNull { it.url == url.trim() }
            if (existing != null) return@runOutcome existing.copy(title = title.trim()).also {
                all[all.indexOf(existing)] = it
                bookmarkFile.writeText(json.encodeToString(bookmarkSer, all))
            }
            val bookmark = Bookmark(Ids.newId("bm"), url.trim(), title.trim(), clock.nowMillis())
            all.add(bookmark)
            bookmarkFile.writeText(json.encodeToString(bookmarkSer, all))
            bookmark
        }

    @Synchronized
    fun bookmarks(): Outcome<List<Bookmark>> = runOutcome("BROWSER_BOOKMARK") {
        readBookmarks().sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun removeBookmark(id: String): Boolean {
        val all = readBookmarks()
        val removed = all.removeIf { it.id == id }
        if (removed) bookmarkFile.writeText(json.encodeToString(bookmarkSer, all))
        return removed
    }
}

/** Download engine with an injectable fetcher (real HTTP in production, fake bytes in tests). */
class FileDownloader(
    private val fetch: (String) -> InputStream = { URL(it).openStream() },
    private val maxBytes: Long = 100L * 1024 * 1024,
) {
    fun download(url: String, destFile: File): Outcome<Long> = runOutcome("BROWSER_DOWNLOAD") {
        check(url.startsWith("http://") || url.startsWith("https://")) { "only http(s) downloads: '$url'" }
        destFile.parentFile?.mkdirs()
        var total = 0L
        fetch(url).buffered().use { input ->
            destFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= maxBytes) { "download exceeds ${maxBytes} bytes" }
                    output.write(buffer, 0, read)
                }
            }
        }
        total
    }
}

/** Hands a page to the AI chat as prompt context. */
data class HandoffPayload(
    val pageUrl: String,
    val title: String,
    val selection: String = "",
)

object BrowserHandoff {
    fun toPrompt(payload: HandoffPayload, question: String): String = buildString {
        appendLine("Page: ${payload.title} <${payload.pageUrl}>")
        if (payload.selection.isNotBlank()) {
            appendLine("Selected text:")
            appendLine(payload.selection.take(4000))
        }
        append("Question: ${question.trim().ifBlank { "summarize this page" }}")
    }.trim()
}
