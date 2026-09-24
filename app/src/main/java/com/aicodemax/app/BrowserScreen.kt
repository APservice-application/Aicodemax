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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.aicodemax.core.common.fold
import com.aicodemax.tools.browser.Bookmark
import com.aicodemax.tools.browser.BrowserHandoff
import com.aicodemax.tools.browser.FileDownloader
import com.aicodemax.tools.browser.HandoffPayload
import com.aicodemax.tools.browser.HistoryEntry
import com.aicodemax.ui.designsystem.AicodeRadii
import com.aicodemax.ui.designsystem.AicodeSearchField
import com.aicodemax.ui.designsystem.EmptyState
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.OutputBlock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class BrowserMode { TABS, OVERVIEW, HISTORY, BOOKMARKS, DOWNLOADS }

/**
 * Browser workspace (CP-136 re-skin of the CP-39 engine): tab strip + omnibox
 * + overview grid + history + bookmarks + downloads + AI panel + hand-to-chat.
 * Tab state lives in InMemoryBrowserPort so the AI can open/navigate tabs too.
 * Limitation: per-tab history resets when switching tabs (one WebView shows the active tab).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var historyQuery by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var aiOpen by remember { mutableStateOf(false) }
    var aiQuestion by remember { mutableStateOf("") }
    var downloads by remember { mutableStateOf<List<File>>(emptyList()) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }

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

    fun reloadDownloads() {
        val dir = File(services.workspaceDir, "downloads")
        downloads = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    LaunchedEffect(Unit) {
        reloadLibrary()
        reloadDownloads()
    }

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

    fun handToChat(question: String) {
        val url = active?.url ?: address.trim()
        if (url.isBlank()) return
        onHandToChat(BrowserHandoff.toPrompt(HandoffPayload(url, active?.title ?: url), question))
    }

    fun addBookmark() {
        val url = active?.url ?: address.trim()
        if (url.isNotBlank()) {
            services.library.addBookmark(url, active?.title ?: url)
            reloadLibrary()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OfflineNotice(services)
        // SCR-BROWSER-001: tab strip + omnibox + ⋮ menu.
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(tabs, key = { it.id }) { tab ->
                Surface(
                    shape = RoundedCornerShape(AicodeRadii.M),
                    color = if (tab.id == active?.id) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { activeId = tab.id; mode = BrowserMode.TABS }) {
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
                                onSuccess = { activeId = it.id; mode = BrowserMode.TABS },
                                onFailure = { },
                            )
                        }
                    },
                ) { Text("＋") }
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
                placeholder = { Text("พิมพ์ URL หรือคำค้น…") },
                shape = RoundedCornerShape(AicodeRadii.XXL),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(address) }),
            )
            Button(onClick = { go(address) }) { Text("ไป") }
            TextButton(onClick = { menuOpen = true }) { Text("⋮") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("🗂️ ภาพรวมแท็บ") }, onClick = { menuOpen = false; mode = BrowserMode.OVERVIEW })
                DropdownMenuItem(text = { Text("🕘 ประวัติ") }, onClick = { menuOpen = false; mode = BrowserMode.HISTORY; reloadLibrary() })
                DropdownMenuItem(text = { Text("☆ ที่คั่น") }, onClick = { menuOpen = false; mode = BrowserMode.BOOKMARKS; reloadLibrary() })
                DropdownMenuItem(text = { Text("⬇️ ดาวน์โหลด") }, onClick = { menuOpen = false; mode = BrowserMode.DOWNLOADS; reloadDownloads() })
                DropdownMenuItem(text = { Text("🤖 ถาม AI เรื่องหน้านี้") }, onClick = { menuOpen = false; aiQuestion = ""; aiOpen = true })
                DropdownMenuItem(text = { Text("＋ เพิ่มที่คั่น") }, onClick = { menuOpen = false; addBookmark() })
                DropdownMenuItem(text = { Text("💬 ส่งหน้านี้ให้แชท") }, onClick = { menuOpen = false; handToChat("") })
            }
        }

        when (mode) {
            BrowserMode.HISTORY -> HistoryPanel(
                history = history,
                query = historyQuery,
                onQuery = { historyQuery = it },
                onOpen = ::go,
                onClear = {
                    services.library.clearHistory()
                    reloadLibrary()
                },
            )
            BrowserMode.BOOKMARKS -> BookmarksPanel(
                bookmarks = bookmarks,
                onOpen = ::go,
                onRemove = {
                    services.library.removeBookmark(it)
                    reloadLibrary()
                },
            )
            BrowserMode.OVERVIEW -> OverviewPanel(
                tabs = tabs,
                activeId = activeId,
                onOpenTab = { activeId = it; mode = BrowserMode.TABS },
                onCloseTab = { id -> scope.launch { services.browser.closeTab(id) } },
                onNewTab = {
                    scope.launch {
                        services.browser.openTab("duckduckgo.com").fold(
                            onSuccess = { activeId = it.id; mode = BrowserMode.TABS },
                            onFailure = { },
                        )
                    }
                },
            )
            BrowserMode.DOWNLOADS -> DownloadsPanel(
                downloads = downloads,
                message = downloadMessage,
                downloading = downloading,
                currentUrl = active?.url ?: address.trim(),
                onDownloadCurrent = { url ->
                    downloading = true
                    downloadMessage = null
                    scope.launch(Dispatchers.IO) {
                        val name = url.substringAfterLast('/').substringBefore('?')
                            .ifBlank { "download-${System.currentTimeMillis()}" }
                            .replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
                        val dest = File(File(services.workspaceDir, "downloads").apply { mkdirs() }, name)
                        FileDownloader().download(url, dest).fold(
                            onSuccess = { downloadMessage = "ดาวน์โหลดแล้ว: ${dest.name} ($it bytes)" },
                            onFailure = { downloadMessage = "ดาวน์โหลดไม่ได้: ${it.message}" },
                        )
                        withContext(Dispatchers.Main) {
                            reloadDownloads()
                            downloading = false
                        }
                    }
                },
                onDelete = {
                    it.delete()
                    reloadDownloads()
                },
            )
            BrowserMode.TABS -> {
                if (active == null) {
                    EmptyState(
                        icon = Icons.Outlined.Refresh,
                        title = "ยังไม่มีแท็บ",
                        description = "กด ＋ หรือพิมพ์ URL แล้วกด ไป",
                        modifier = Modifier.fillMaxSize(),
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
                                            val pageUrl = view?.url ?: active.url
                                            services.library.visit(pageUrl, title)
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
    }

    if (aiOpen) {
        AiPanelSheet(
            pageTitle = active?.title ?: "",
            pageUrl = active?.url ?: address.trim(),
            question = aiQuestion,
            onQuestion = { aiQuestion = it },
            onDismiss = { aiOpen = false },
            onAsk = { q ->
                aiOpen = false
                handToChat(q)
            },
        )
    }
}

@Composable
private fun HistoryPanel(
    history: List<HistoryEntry>,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val shown = if (query.isBlank()) history
    else history.filter {
        it.url.contains(query, ignoreCase = true) || it.title.contains(query, ignoreCase = true)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        item(key = "__search__") {
            AicodeSearchField(
                value = query,
                onValueChange = onQuery,
                onSearch = {},
                placeholder = "ค้นหาประวัติ…",
            )
        }
        item(key = "__clear__") {
            TextButton(onClick = onClear) { Text("ล้างประวัติ") }
        }
        items(shown, key = { it.id }) { entry ->
            Surface(
                shape = RoundedCornerShape(AicodeRadii.S),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(entry.url) },
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
}

@Composable
private fun BookmarksPanel(
    bookmarks: List<Bookmark>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        if (bookmarks.isEmpty()) {
            item(key = "__empty__") { Text("(ยังไม่มีที่คั่น — กด ⋮ > ＋ เพิ่มที่คั่น)") }
        }
        items(bookmarks, key = { it.id }) { bookmark ->
            Surface(
                shape = RoundedCornerShape(AicodeRadii.S),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onOpen(bookmark.url) },
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
                    TextButton(onClick = { onRemove(bookmark.id) }) { Text("ลบ") }
                }
            }
        }
    }
}

/** SCR-BROWSER-002: tab overview grid. */
@Composable
private fun OverviewPanel(
    tabs: List<com.aicodemax.tools.browser.BrowserTab>,
    activeId: String?,
    onOpenTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.fillMaxSize().padding(spacing.sm)) {
        OutlinedButton(onClick = onNewTab, modifier = Modifier.fillMaxWidth()) {
            Text("＋ แท็บใหม่")
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(tabs, key = { it.id }) { tab ->
                Surface(
                    shape = RoundedCornerShape(AicodeRadii.M),
                    color = if (tab.id == activeId) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { onOpenTab(tab.id) },
                ) {
                    Column(modifier = Modifier.padding(spacing.sm)) {
                        Text(
                            tab.title.ifBlank { tab.url },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = spacing.xs),
                        )
                        Text(
                            tab.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { onCloseTab(tab.id) }) { Text("ปิดแท็บ") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsPanel(
    downloads: List<File>,
    message: String?,
    downloading: Boolean,
    currentUrl: String,
    onDownloadCurrent: (String) -> Unit,
    onDelete: (File) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        item(key = "__dl__") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text("ดาวน์โหลด URL ของแท็บปัจจุบัน", style = MaterialTheme.typography.titleSmall)
                Text(
                    currentUrl.ifBlank { "(ยังไม่มี URL)" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(
                    onClick = { onDownloadCurrent(currentUrl) },
                    enabled = !downloading && currentUrl.startsWith("http"),
                ) { Text(if (downloading) "กำลังดาวน์โหลด…" else "⬇️ ดาวน์โหลด") }
                if (message != null) OutputBlock(message)
            }
        }
        item(key = "__head__") {
            Text("ไฟล์ที่ดาวน์โหลด (${downloads.size})", style = MaterialTheme.typography.titleSmall)
        }
        items(downloads, key = { it.absolutePath }) { file ->
            Surface(
                shape = RoundedCornerShape(AicodeRadii.S),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${file.length()} bytes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    TextButton(onClick = { onDelete(file) }) { Text("ลบ") }
                }
            }
        }
    }
}

/** SCR-BROWSER-007: AI panel — ask about this page, answered in chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiPanelSheet(
    pageTitle: String,
    pageUrl: String,
    question: String,
    onQuestion: (String) -> Unit,
    onDismiss: () -> Unit,
    onAsk: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text("🤖 ถาม AI เรื่องหน้านี้", style = MaterialTheme.typography.titleMedium)
            Text(
                pageTitle.ifBlank { pageUrl },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                pageUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                OutlinedButton(onClick = { onAsk("สรุปเนื้อหาสำคัญของหน้านี้ให้หน่อย") }) {
                    Text("สรุปหน้านี้")
                }
                OutlinedButton(onClick = { onAsk("สกัดประเด็น/ลิงก์สำคัญจากหน้านี้") }) {
                    Text("สกัดประเด็น")
                }
            }
            TextField(
                value = question,
                onValueChange = onQuestion,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("ถามอะไรก็ได้เกี่ยวกับหน้านี้…") },
                singleLine = false,
                maxLines = 3,
            )
            Button(
                onClick = { onAsk(question.trim()) },
                enabled = question.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(bottom = spacing.md),
            ) { Text("ถามในแชท 💬") }
        }
    }
}
