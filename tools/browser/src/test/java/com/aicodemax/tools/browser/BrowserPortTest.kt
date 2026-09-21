package com.aicodemax.tools.browser

import com.aicodemax.core.common.fold
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPortTest {

    @Test
    fun openNavigateCloseFlow(): Unit = runBlocking {
        val port = InMemoryBrowserPort()

        val tab = port.openTab("example.com").fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("open failed") },
        )
        assertEquals("https://example.com", tab.url)

        port.updateMeta(tab.id, "Example", loading = false)
        val listed = port.listTabs().fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("list failed") },
        )
        assertEquals(1, listed.size)
        assertEquals("Example", listed[0].title)

        val navigated = port.navigate(tab.id, "https://example.org").fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("navigate failed") },
        )
        assertEquals("https://example.org", navigated.url)
        assertTrue(navigated.loading)

        port.closeTab(tab.id)
        val after = port.listTabs().fold(
            onSuccess = { it },
            onFailure = { throw AssertionError("list failed") },
        )
        assertTrue(after.isEmpty())
    }

    @Test
    fun navigateUnknownTabFails(): Unit = runBlocking {
        val port = InMemoryBrowserPort()
        var failed = false
        port.navigate("nope", "https://example.com").fold(
            onSuccess = { throw AssertionError("should fail") },
            onFailure = { failed = true },
        )
        assertTrue(failed)
    }
}
