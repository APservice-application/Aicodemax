package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.fold
import com.aicodemax.data.audit.AuditEntry
import com.aicodemax.ui.designsystem.LocalSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** Audit viewer — append-only log of every AI/USER/SYSTEM action (newest first). */
@Composable
fun AuditScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<AuditEntry>>(emptyList()) }
    var count by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            services.audit.query(100).fold(
                onSuccess = { entries = it; count = services.audit.count(); error = null },
                onFailure = { error = it.message },
            )
        }
    }

    LaunchedEffect(Unit) { load() }

    Column(modifier = Modifier.fillMaxSize().padding(spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Audit ($count รายการ)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { load() }) { Text("รีเฟรช") }
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        if (entries.isEmpty() && error == null) {
            Text("ยังไม่มีรายการ — ทุก action ของ AI จะถูกบันทึกที่นี่")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(entries, key = { it.id }) { entry -> AuditCard(entry) }
        }
    }
}

@Composable
private fun AuditCard(entry: AuditEntry) {
    val spacing = LocalSpacing.current
    val time = remember(entry.timestamp) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp))
    }
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${entry.actor} • ${entry.action}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (entry.allowed) "✓" else "✕ blocked",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.allowed) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (entry.toolId.isNotBlank()) {
                Text(
                    text = "tool: ${entry.toolId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (entry.detail.isNotBlank()) {
                Text(text = entry.detail, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
