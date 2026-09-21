package com.aicodemax.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.StatusKind
import com.aicodemax.ui.designsystem.statusColor

/**
 * Tool grid driven ONLY by real registry capabilities.
 * Runnable tools get an Open button; the rest show honest reasons (no fake buttons).
 */
@Composable
fun ToolsScreen(
    tools: List<ToolDescriptor>,
    onOpenTool: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items(tools, key = { it.toolId }) { tool ->
            ToolCard(tool = tool, onOpen = { onOpenTool(tool.toolId) })
        }
    }
}

@Composable
private fun ToolCard(tool: ToolDescriptor, onOpen: () -> Unit) {
    val spacing = LocalSpacing.current
    val runnable = tool.isRunnable()
    val badge = if (runnable) statusColor(StatusKind.SUCCESS) else MaterialTheme.colorScheme.outline
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tool.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (runnable) "พร้อมใช้ • v${tool.version}" else "ยังไม่พร้อม • v${tool.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = badge,
                    )
                }
                if (runnable) {
                    Button(onClick = onOpen) { Text("เปิด") }
                }
            }
            if (!runnable) {
                for (reason in tool.missingReasons().take(3)) {
                    Text(
                        text = "• $reason",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
