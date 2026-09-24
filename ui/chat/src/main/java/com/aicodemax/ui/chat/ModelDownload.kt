package com.aicodemax.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * CP-144: built-in AI status card (replaces the CP-139 download banner).
 * The core AI ships with the app — this card only reports provisioning
 * progress or errors. It NEVER asks the user to download the core model.
 * [progress] is 0f..1f while the bundled asset is copied (first launch),
 * null otherwise.
 */
data class BuiltinAiUi(
    val title: String,
    val detail: String,
    val progress: Float? = null,
    val showModelsLink: Boolean = false,
)

@Composable
fun BuiltinAiCard(
    ui: BuiltinAiUi,
    onOpenModels: () -> Unit,
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
            val current = ui.progress
            if (current != null) {
                LinearProgressIndicator(progress = { current }, modifier = Modifier.fillMaxWidth())
                Text("${(current * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            if (ui.showModelsLink) {
                TextButton(onClick = onOpenModels) { Text("ดูโมเดล") }
            }
        }
    }
}
