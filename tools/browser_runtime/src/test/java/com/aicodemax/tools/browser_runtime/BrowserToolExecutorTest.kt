package com.aicodemax.tools.browser_runtime

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.browser.BrowserPort
import com.aicodemax.tools.browser.BrowserTab
import com.aicodemax.tools.browser.browserDescriptorToday
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.registry.ToolDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeBrowserPort : BrowserPort {
    val tabs = mutableListOf<BrowserTab>()
    override fun descriptor(): ToolDescriptor = browserDescriptorToday()
    override suspend fun openTab(url: String, name: String, note: String): Outcome<BrowserTab> {
        val tab = BrowserTab(id = "t${tabs.size}", url = url, name = name, note = note)
        tabs.add(tab)
        return Outcome.Success(tab)
    }
    override suspend fun closeTab(tabId: String): Outcome<Unit> {
        tabs.removeIf { it.id == tabId }
        return Outcome.Success(Unit)
    }
    override suspend fun listTabs(): Outcome<List<BrowserTab>> = Outcome.Success(tabs.toList())
    override suspend fun navigate(tabId: String, url: String): Outcome<BrowserTab> {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return Outcome.Failure(AppError("TAB_UNKNOWN", "no tab"))
        val updated = tabs[index].copy(url = url)
        tabs[index] = updated
        return Outcome.Success(updated)
    }
    override suspend fun pageText(tabId: String): Outcome<String> = Outcome.Success("fake page text")
    override suspend fun click(tabId: String, selector: String): Outcome<String> =
        Outcome.Success("CLICKED:$selector")
    override suspend fun typeText(tabId: String, selector: String, text: String): Outcome<String> =
        Outcome.Success("TYPED:$selector=$text")
    override suspend fun probeLogin(tabId: String): Outcome<Boolean> = Outcome.Success(false)
}

class BrowserToolExecutorTest {
    private fun run(call: ToolCall, port: FakeBrowserPort = FakeBrowserPort()): ToolResult {
        val executor = BrowserToolExecutor(port)
        return runBlocking { executor.execute(call) as Outcome.Success<ToolResult> }.value
    }

    @Test
    fun openListNavigateCloseRoundtrip() {
        val port = FakeBrowserPort()
        val opened = run(ToolCall("c1", "browser", "open", mapOf("url" to "https://example.com")), port)
        assertTrue(opened.ok)
        val listed = run(ToolCall("c2", "browser", "list"), port)
        assertTrue(listed.ok)
        assertTrue(listed.output.contains("example.com"))
        val navigated = run(
            ToolCall("c3", "browser", "navigate", mapOf("tabId" to "t0", "url" to "https://example.org")),
            port,
        )
        assertTrue(navigated.ok)
        val closed = run(ToolCall("c4", "browser", "close", mapOf("tabId" to "t0")), port)
        assertTrue(closed.ok)
        val empty = run(ToolCall("c5", "browser", "list"), port)
        assertTrue(empty.output.contains("(no tabs)"))
    }

    @Test
    fun missingArgsFailHonestly() {
        val noUrl = run(ToolCall("c1", "browser", "open"))
        assertFalse(noUrl.ok)
        assertTrue(noUrl.error.contains("url"))

        val unknown = run(ToolCall("c2", "browser", "back", mapOf("tabId" to "t0")))
        assertFalse(unknown.ok)
        assertTrue(unknown.error.contains("unknown action"))
    }

    @Test
    fun pageAutomationUsesLatestTabByDefault() {
        // CP-115: read/click/type/probe default to the latest tab.
        val port = FakeBrowserPort()
        run(ToolCall("c0", "browser", "open", mapOf("url" to "https://example.com")), port)
        val read = run(ToolCall("c1", "browser", "read"), port)
        assertTrue(read.ok)
        assertTrue(read.output.contains("fake page text"))
        val click = run(ToolCall("c2", "browser", "click", mapOf("selector" to "#btn")), port)
        assertTrue(click.ok)
        val type = run(ToolCall("c3", "browser", "type", mapOf("selector" to "input", "text" to "hi")), port)
        assertTrue(type.ok)
        val probe = run(ToolCall("c4", "browser", "probe"), port)
        assertTrue(probe.ok)
        assertTrue(probe.output.contains("UNKNOWN"))
    }

    @Test
    fun pageAutomationWithoutTabsFailsHonestly() {
        val read = run(ToolCall("c1", "browser", "read"))
        assertFalse(read.ok)
        assertTrue(read.error.contains("ยังไม่มีแท็บ"))
        val noSelector = run(
            ToolCall("c2", "browser", "click"),
            FakeBrowserPort().also { runBlocking { it.openTab("https://x.com") } },
        )
        assertFalse(noSelector.ok)
        assertTrue(noSelector.error.contains("selector"))
    }

    @Test
    fun openStoresNameAndNote() {
        // CP-147 (spec §11): name/note travel with the tab.
        val port = FakeBrowserPort()
        val opened = run(
            ToolCall("c1", "browser", "open", mapOf("url" to "https://fb.com", "name" to "Facebook ร้านค้า", "note" to "โพสต์งาน")),
            port,
        )
        assertTrue(opened.ok)
        assertTrue(port.tabs.single().name == "Facebook ร้านค้า")
        assertTrue(port.tabs.single().note == "โพสต์งาน")
    }

    @Test
    fun navigateWithoutTabIdUsesCurrentOrOpens() {
        // CP-147 (browser.open_url): no tabId -> current tab; no tabs -> new tab.
        val port = FakeBrowserPort()
        val opened = run(ToolCall("c1", "browser", "navigate", mapOf("url" to "https://a.com")), port)
        assertTrue(opened.ok)
        assertTrue(opened.output.startsWith("opened"))
        val moved = run(ToolCall("c2", "browser", "navigate", mapOf("url" to "https://b.com")), port)
        assertTrue(moved.ok)
        assertTrue(moved.output.startsWith("navigated"))
        assertTrue(port.tabs.size == 1)
    }

    @Test
    fun searchOpensEngineUrl() {
        // CP-147 (browser.search): query -> Google URL.
        val port = FakeBrowserPort()
        val r = run(ToolCall("c1", "browser", "search", mapOf("query" to "Qwen3 4B")), port)
        assertTrue(r.ok)
        assertTrue(r.output, r.output.contains("google.com/search?q=Qwen3+4B"))
        val empty = run(ToolCall("c2", "browser", "search", mapOf("query" to "  ")), port)
        assertFalse(empty.ok)
    }

    @Test
    fun infoAndCurrentReportTabFacts() {
        // CP-147 (browser.get_page_info / get_current_tab).
        val port = FakeBrowserPort()
        run(ToolCall("c0", "browser", "open", mapOf("url" to "https://a.com", "name" to "A")), port)
        val info = run(ToolCall("c1", "browser", "info"), port)
        assertTrue(info.ok)
        assertTrue(info.output, info.output.contains("url=https://a.com"))
        assertTrue(info.output.contains("login="))
        val current = run(ToolCall("c2", "browser", "current"), port)
        assertTrue(current.ok)
        assertTrue(current.output.contains("name=A"))
        val none = run(ToolCall("c3", "browser", "current"), FakeBrowserPort())
        assertFalse(none.ok)
        assertTrue(none.error.contains("NO_TABS"))
    }
}
