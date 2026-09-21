package com.aicodemax.app

import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

/**
 * Browser Center — tabs + address bar + WebView.
 * Tab state lives in [com.aicodemax.tools.browser.InMemoryBrowserPort] so the AI
 * can open/navigate tabs through the gateway too. Limitation: per-tab history
 * resets when switching tabs (one WebView shows the active tab).
 */
@Composable
fun BrowserScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val tabs by services.browser.tabs.collectAsState()
    var activeId by remember { mutableStateOf<String?>(null) }
    var address by remember { mutableStateOf("") }

    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()
    LaunchedEffect(active?.id) {
        activeId = active?.id
        if (active != null) address = active.url
    }

    fun go() {
        val url = address.trim()
        if (url.isEmpty()) return
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
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            TextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("พิมพ์ URL…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
            )
            Button(onClick = { go() }) { Text("ไป") }
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
                                    services.browser.updateMeta(active.id, view?.title ?: "", false)
                                }
                            }
                            loadUrl(active.url)
                        }
                    },
                    update = { webView ->
                        if (webView.url != active.url) webView.loadUrl(active.url)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
