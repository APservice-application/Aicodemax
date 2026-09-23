package com.aicodemax.app

import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.viewinterop.AndroidView
import com.aicodemax.core.common.fold
import com.aicodemax.tools.browser.Bookmark
import com.aicodemax.tools.browser.BrowserHandoff
import com.aicodemax.tools.browser.HandoffPayload
import com.aicodemax.tools.browser.HistoryEntry
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

private enum class BrowserMode { TABS, HISTORY, BOOKMARKS }

/**
 * Browser Center — tabs + address bar + WebView + history + bookmarks.
 * Tab state lives in InMemoryBrowserPort so the AI can open/navigate tabs too.
 * Limitation: per-tab history resets when switching tabs (one WebView shows the active tab).
 */
@Composable
fun BrowserScreen(services: ServiceLocator, onHandToChat: (String) -> Unit = {}) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val tabs by services.browser.tabs.collectAsState()
    var mode by remember { mutableStateOf(BrowserMode.TABS) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }

    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()
    LaunchedEffect(active?.id) {
        activeId = active?.id
        if (active != null) address = active.url
    }

    fun reloadLibrary() {
        services.library.recent(100).fold(
            onSuccess = { history = it },
            onFailure = { },
        )
        services.library.bookmarks().fold(
            onSuccess = { bookmarks = it },
            onFailure = { },
        )
    }

    LaunchedEffect(Unit) { reloadLibrary() }

    fun go(to: String) {
        val url = to.trim()
        if (url.isEmpty()) return
        mode = BrowserMode.TABS
        scope.launch {
            if (active == null) {
                services.browser.openTab(url).fold(
                    onSuccess = { activeId = it.id },
                    onFailure = { },
                )
            } else {
                services.browser.navigate(active.id, url)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            for (value in BrowserMode.values()) {
                TextButton(onClick = { mode = value; reloadLibrary() }) {
                    Text(
                        when (value) {
                            BrowserMode.TABS -> "แท็บ"
                            BrowserMode.HISTORY -> "ประวัติ"
                            BrowserMode.BOOKMARKS -> "ที่คั่น"
                        },
                        color = if (mode == value) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        },
                    )
                }
            }
        }

        if (mode == BrowserMode.HISTORY) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                item(key = "__clear__") {
                    TextButton(onClick = {
                        services.library.clearHistory()
                        reloadLibrary()
                    }) { Text("ล้างประวัติ") }
                }
                items(history, key = { it.id }) { entry ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { go(entry.url) },
                    ) {
                        Column(modifier = Modifier.padding(spacing.sm)) {
                            Text(entry.title.ifBlank { entry.url }, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                entry.url,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
            return
        }

        if (mode == BrowserMode.BOOKMARKS) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                if (bookmarks.isEmpty()) {
                    item(key = "__empty__") { Text("(ยังไม่มีที่คั่น — กด ☆ ในแถบที่อยู่เพื่อเพิ่ม)") }
                }
                items(bookmarks, key = { it.id }) { bookmark ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { go(bookmark.url) },
                    ) {
                        Row(
                            modifier = Modifier.padding(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(bookmark.title.ifBlank { bookmark.url }, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    bookmark.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            TextButton(onClick = {
                                services.library.removeBookmark(bookmark.id)
                                reloadLibrary()
                            }) { Text("ลบ") }
                        }
                    }
                }
            }
            return
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(tabs, key = { it.id }) { tab ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (tab.id == active?.id) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { activeId = tab.id }) {
                            Text((tab.title.ifBlank { tab.url }).take(18))
                        }
                        TextButton(
                            onClick = {
                                scope.launch { services.browser.closeTab(tab.id) }
                            },
                        ) { Text("✕") }
                    }
                }
            }
            item(key = "__new__") {
                TextButton(
                    onClick = {
                        scope.launch {
                            services.browser.openTab("duckduckgo.com").fold(
                                onSuccess = { activeId = it.id },
                                onFailure = { },
                            )
                        }
                    },
                ) { Text("＋ แท็บใหม่") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            TextButton(onClick = { webView?.goBack() }, enabled = canBack) { Text("‹") }
            TextButton(onClick = { webView?.goForward() }, enabled = canForward) { Text("›") }
            TextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("พิมพ์ URL…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(address) }),
            )
            TextButton(onClick = {
                val url = active?.url ?: address.trim()
                if (url.isNotBlank()) {
                    services.library.addBookmark(url, active?.title ?: url)
                    reloadLibrary()
                }
            }) { Text("☆") }
            TextButton(onClick = {
                val url = active?.url ?: address.trim()
                if (url.isNotBlank()) {
                    onHandToChat(BrowserHandoff.toPrompt(HandoffPayload(url, active?.title ?: url), ""))
                }
            }) { Text("🤖") }
            Button(onClick = { go(address) }) { Text("ไป") }
        }

        if (active == null) {
            Text(
                text = "ยังไม่มีแท็บ — กด “＋ แท็บใหม่” หรือพิมพ์ URL แล้วกด ไป",
                modifier = Modifier.padding(spacing.md),
            )
        } else {
            key(active.id) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    services.browser.updateMeta(active.id, view?.title ?: "", true)
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val title = view?.title ?: ""
                                    services.browser.updateMeta(active.id, title, false)
                                    val url = view?.url ?: active.url
                                    services.library.visit(url, title)
                                    canBack = view?.canGoBack() == true
                                    canForward = view?.canGoForward() == true
                                }
                            }
                            loadUrl(active.url)
                            webView = this
                            services.activeWebView = this
                        }
                    },
                    update = { view ->
                        webView = view
                        services.activeWebView = view
                        if (view.url != active.url) view.loadUrl(active.url)
                        canBack = view.canGoBack()
                        canForward = view.canGoForward()
                    },
                    onRelease = { services.activeWebView = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
