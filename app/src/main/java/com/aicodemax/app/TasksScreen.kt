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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.ai.tasks.AiTask
import com.aicodemax.ai.tasks.TaskState
import com.aicodemax.core.common.fold
import com.aicodemax.core.state.AppEvent
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.StatusKind
import com.aicodemax.ui.designsystem.statusColor

/** Live task list — refreshes on every TaskUpdated event from the bus. */
@Composable
fun TasksScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    var tasks by remember { mutableStateOf(services.tasks.list()) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        services.bus.events.collect { event ->
            if (event is AppEvent.TaskUpdated) {
                tasks = services.tasks.list()
            }
        }
    }

    if (tasks.isEmpty()) {
        Column(modifier = Modifier.fillMaxSize().padding(spacing.md)) {
            Text("ยังไม่มีงาน — สั่ง AI ในแชทได้เลย")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (actionError != null) {
                item(key = "__error__") {
                    Text(actionError!!, color = MaterialTheme.colorScheme.error)
                }
            }
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onCancel = {
                        services.tasks.cancel(task.id).fold(
                            onSuccess = { actionError = null },
                            onFailure = { actionError = it.message },
                        )
                    },
                    onRetry = {
                        services.tasks.retry(task.id).fold(
                            onSuccess = { actionError = null },
                            onFailure = { actionError = it.message },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun TaskCard(task: AiTask, onCancel: () -> Unit, onRetry: () -> Unit) {
    val spacing = LocalSpacing.current
    val color = when {
        TaskState.isTerminal(task.state) && task.state == TaskState.COMPLETED ->
            statusColor(StatusKind.SUCCESS)
        task.state == TaskState.FAILED -> statusColor(StatusKind.DANGER)
        TaskState.isTerminal(task.state) -> MaterialTheme.colorScheme.outline
        else -> statusColor(StatusKind.INFO)
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${task.state.name} • try ${task.attempts}/${task.maxAttempts}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
            if (task.lastError.isNotBlank()) {
                Text(
                    task.lastError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (task.resultSummary.isNotBlank()) {
                Text(task.resultSummary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                if (!TaskState.isTerminal(task.state)) {
                    TextButton(onClick = onCancel) { Text("ยกเลิก") }
                }
                if (task.state == TaskState.FAILED) {
                    TextButton(onClick = onRetry) { Text("ลองใหม่") }
                }
            }
        }
    }
}
