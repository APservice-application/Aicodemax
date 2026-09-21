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
    override suspend fun openTab(url: String): Outcome<BrowserTab> {
        val tab = BrowserTab(id = "t${tabs.size}", url = url)
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
}
