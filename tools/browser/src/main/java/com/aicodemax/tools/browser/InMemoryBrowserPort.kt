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

    override suspend fun openTab(url: String, name: String, note: String): Outcome<BrowserTab> {
        // CP-147: UrlResolver is the single source of truth (spec §6) — the old
        // naive "https://" prefix produced garbage like "https://Qwen3 4B".
        val resolved = if (url.isBlank()) {
            "about:blank"
        } else {
            when (val r = UrlResolver.normalize(url)) {
                is Outcome.Success -> r.value
                is Outcome.Failure -> return Outcome.Failure(r.error)
            }
        }
        counter += 1
        val tab = BrowserTab(id = "tab-$counter", url = resolved, loading = resolved != "about:blank", name = name, note = note)
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
        val resolved = when (val r = UrlResolver.normalize(url)) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> return Outcome.Failure(r.error)
        }
        val updated = current.copy(url = resolved, loading = true)
        _tabs.value = _tabs.value.map { if (it.id == tabId) updated else it }
        return Outcome.Success(updated)
    }

    // CP-115 page automation needs the live Android WebView — honest failures here,
    // real behavior in app/ AndroidBrowserPort (delegating wrapper).
    private fun needsView(): Outcome<Nothing> = Outcome.Failure(
        AppError("BROWSER_NEEDS_WEBVIEW", "เปิดจอ Browser ก่อน — ระบบอัตโนมัติทำงานบนหน้าเว็บที่เห็นจริงเท่านั้น"),
    )

    override suspend fun pageText(tabId: String): Outcome<String> = needsView()
    override suspend fun click(tabId: String, selector: String): Outcome<String> = needsView()
    override suspend fun typeText(tabId: String, selector: String, text: String): Outcome<String> = needsView()
    override suspend fun probeLogin(tabId: String): Outcome<Boolean> = needsView()

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
