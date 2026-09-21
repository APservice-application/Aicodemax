package com.aicodemax.tools.browser

import com.aicodemax.core.common.Outcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun historyVisitSearchClear() {
        val library = BrowserLibrary(tmp.root)
        library.visit("https://example.com/a", "A page")
        library.visit("https://example.com/b", "B page")
        library.visit("https://other.dev/", "Other")

        val recent = (library.recent(2) as Outcome.Success<List<HistoryEntry>>).value
        assertEquals(2, recent.size)
        val found = (library.searchHistory("example") as Outcome.Success<List<HistoryEntry>>).value
        assertEquals(2, found.size)
        assertEquals(3, (library.clearHistory() as Outcome.Success<Int>).value)
        assertTrue((library.recent() as Outcome.Success<List<HistoryEntry>>).value.isEmpty())
        assertTrue(library.visit("  ", "x") is Outcome.Failure)
    }

    @Test
    fun bookmarksAddListRemove() {
        val library = BrowserLibrary(tmp.root)
        val first = (library.addBookmark("https://example.com/", "Ex") as Outcome.Success<Bookmark>).value
        val updated = (library.addBookmark("https://example.com/", "Ex2") as Outcome.Success<Bookmark>).value
        assertEquals(first.id, updated.id)
        assertEquals("Ex2", updated.title)
        assertEquals(1, (library.bookmarks() as Outcome.Success<List<Bookmark>>).value.size)
        assertTrue(library.removeBookmark(first.id))
        assertTrue(!library.removeBookmark(first.id))
    }

    @Test
    fun downloaderWritesBytesHonestly() {
        val downloader = FileDownloader(fetch = { "hello-bytes".byteInputStream() })
        val dest = File(tmp.root, "f.bin")
        assertEquals(11L, (downloader.download("https://example.com/f", dest) as Outcome.Success<Long>).value)
        assertEquals("hello-bytes", dest.readText())
        assertTrue(downloader.download("ftp://x/f", dest) is Outcome.Failure)
        val capped = FileDownloader(fetch = { "x".repeat(100).byteInputStream() }, maxBytes = 10)
        assertTrue(capped.download("https://example.com/f", File(tmp.root, "g.bin")) is Outcome.Failure)
    }

    @Test
    fun handoffBuildsPrompt() {
        val prompt = BrowserHandoff.toPrompt(HandoffPayload("https://e.com/", "E", "key line"), "explain?")
        assertTrue(prompt.contains("https://e.com/"))
        assertTrue(prompt.contains("key line"))
        assertTrue(prompt.contains("explain?"))
        assertTrue(BrowserHandoff.toPrompt(HandoffPayload("https://e.com/", "E"), "  ").contains("summarize"))
    }
}
