package com.aicodemax.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.fold
import com.aicodemax.tools.files.FileEntry
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

/**
 * File browser — the tool's own UI controller path (UI → FilePort → Runtime).
 * The AI path always goes through the ToolGateway instead.
 */
@Composable
fun ProjectsScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun load(dir: String) {
        scope.launch {
            services.files.list(dir).fold(
                onSuccess = { entries = it; path = dir; error = null },
                onFailure = { error = it.message },
            )
        }
    }

    LaunchedEffect(Unit) { load("") }

    Column(modifier = Modifier.fillMaxSize().padding(spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            if (path.isNotEmpty()) {
                TextButton(
                    onClick = {
                        val parent = path.substringBeforeLast("/", "")
                        load(parent)
                    },
                ) { Text("← ขึ้นบน") }
            }
            Text(
                text = if (path.isEmpty()) "workspace/" else "workspace/$path",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        if (entries.isEmpty() && error == null) {
            Text("ว่างเปล่า — สั่ง AI ในแชทได้เลย เช่น “สร้างไฟล์ notes.txt: สวัสดี”")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(entries, key = { it.path }) { entry ->
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (entry.isDirectory) {
                            load(entry.path)
                        } else {
                            scope.launch {
                                services.files.read(entry.path).fold(
                                    onSuccess = { viewing = entry.path to it },
                                    onFailure = { error = it.message },
                                )
                            }
                        }
                    },
                ) {
                    Column(modifier = Modifier.padding(spacing.sm)) {
                        Text((if (entry.isDirectory) "📁 " else "📄 ") + entry.name)
                        if (!entry.isDirectory) {
                            Text(
                                "${entry.sizeBytes} bytes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }

    viewing?.let { (name, content) ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(name) },
            text = {
                Text(
                    text = content.ifBlank { "(ไฟล์ว่าง)" },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("ปิด") } },
        )
    }
}
