package com.aicodemax.tools.browser

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real tab/session state manager. Rendering is done by the UI layer (WebView),
 * which reports page titles back via [updateMeta].
 */
class InMemoryBrowserPort : BrowserPort {
    private val _tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()
    private var counter = 0

    override fun descriptor() = browserDescriptorToday()

    override suspend fun openTab(url: String): Outcome<BrowserTab> {
        counter += 1
        val tab = BrowserTab(id = "tab-$counter", url = normalize(url), loading = true)
        _tabs.value = _tabs.value + tab
        return Outcome.Success(tab)
    }

    override suspend fun closeTab(tabId: String): Outcome<Unit> {
        _tabs.value = _tabs.value.filterNot { it.id == tabId }
        return Outcome.Success(Unit)
    }

    override suspend fun listTabs(): Outcome<List<BrowserTab>> = Outcome.Success(_tabs.value)

    override suspend fun navigate(tabId: String, url: String): Outcome<BrowserTab> {
        val current = _tabs.value.firstOrNull { it.id == tabId }
            ?: return Outcome.Failure(AppError("TAB_UNKNOWN", "tab '$tabId' not found"))
        val updated = current.copy(url = normalize(url), loading = true)
        _tabs.value = _tabs.value.map { if (it.id == tabId) updated else it }
        return Outcome.Success(updated)
    }

    /** Called by the WebView layer when a page starts/finishes loading. */
    fun updateMeta(tabId: String, title: String, loading: Boolean) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) it.copy(title = title, loading = loading) else it
        }
    }

    private fun normalize(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "about:blank"
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
