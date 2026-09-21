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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.ToolDescriptor
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.StatusKind
import com.aicodemax.ui.designsystem.statusColor
import kotlinx.coroutines.launch

/**
 * Tool grid driven ONLY by real registry capabilities.
 * Runnable tools get Open + Self-test; the rest show honest reasons (no fake buttons).
 */
@Composable
fun ToolsScreen(
    tools: List<ToolDescriptor>,
    onOpenTool: (String) -> Unit,
    onSelfTest: suspend (toolId: String) -> String,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        items(tools, key = { it.toolId }) { tool ->
            ToolCard(tool = tool, onOpen = { onOpenTool(tool.toolId) }, onSelfTest = onSelfTest)
        }
    }
}

@Composable
private fun ToolCard(
    tool: ToolDescriptor,
    onOpen: () -> Unit,
    onSelfTest: suspend (toolId: String) -> String,
) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "ซ่อนชั้น" else "ดูชั้น")
                }
                if (runnable) {
                    TextButton(
                        onClick = {
                            testing = true
                            scope.launch {
                                verdict = onSelfTest(tool.toolId)
                                testing = false
                            }
                        },
                        enabled = !testing,
                    ) { Text(if (testing) "กำลังทดสอบ…" else "ทดสอบ") }
                }
            }
            if (verdict != null) {
                Text(
                    verdict!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (expanded) {
                for (layer in tool.layers) {
                    val dot = when (layer.status) {
                        CapabilityStatus.AVAILABLE -> "●"
                        CapabilityStatus.PARTIAL -> "◐"
                        CapabilityStatus.MISSING -> "○"
                    }
                    Text(
                        "$dot ${layer.layer.name}" + if (layer.reason.isNotBlank()) " — ${layer.reason}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
