package com.aicodemax.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * CP-139: one-tap default-model download card.
 * [progress] is 0f..1f while downloading, null when idle.
 */
data class ModelDownloadUi(
    val title: String,
    val detail: String,
    val progress: Float? = null,
    val busy: Boolean = false,
    val buttonLabel: String = "ดาวน์โหลดและติดตั้ง",
    val downloadingLabel: String = "กำลังดาวน์โหลด… ห้ามปิดแอป",
)

@Composable
fun ModelDownloadBanner(
    ui: ModelDownloadUi,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🧠 ${ui.title}", style = MaterialTheme.typography.titleMedium)
            Text(
                ui.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            if (ui.busy) {
                Text(ui.downloadingLabel, style = MaterialTheme.typography.bodySmall)
                val current = ui.progress
                if (current != null) {
                    LinearProgressIndicator(progress = { current }, modifier = Modifier.fillMaxWidth())
                    Text("${(current * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else {
                Button(onClick = onDownload) { Text(ui.buttonLabel) }
            }
        }
    }
}
