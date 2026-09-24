package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import com.aicodemax.ui.designsystem.AicodeRadii
import com.aicodemax.ui.designsystem.EmptyState
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.StatusKind
import com.aicodemax.ui.designsystem.statusColor

private enum class TaskFilter { ALL, ACTIVE, WAITING, FAILED, DONE }

/**
 * CP-137 tasks (§63): live list + status filter chips + pause/resume/
 * approve/details/take-control. Updates stream from the event bus.
 */
@Composable
fun TasksScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    var tasks by remember { mutableStateOf(services.tasks.list()) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(TaskFilter.ALL) }

    LaunchedEffect(Unit) {
        services.bus.events.collect { event ->
            if (event is AppEvent.TaskUpdated) {
                tasks = services.tasks.list()
            }
        }
    }

    fun act(call: () -> com.aicodemax.core.common.Outcome<AiTask>) {
        call().fold(
            onSuccess = { actionError = null },
            onFailure = { actionError = it.message },
        )
    }

    val shown = remember(tasks, filter) {
        tasks.filter { task ->
            when (filter) {
                TaskFilter.ALL -> true
                TaskFilter.ACTIVE -> !TaskState.isTerminal(task.state) &&
                    task.state != TaskState.WAITING_PERMISSION &&
                    task.state != TaskState.WAITING_USER &&
                    task.state != TaskState.FAILED
                TaskFilter.WAITING -> task.state == TaskState.WAITING_PERMISSION ||
                    task.state == TaskState.WAITING_USER ||
                    task.state == TaskState.WAITING ||
                    task.state == TaskState.PAUSED
                TaskFilter.FAILED -> task.state == TaskState.FAILED ||
                    task.state == TaskState.BLOCKED
                TaskFilter.DONE -> task.state == TaskState.COMPLETED
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(spacing.md)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            for (value in TaskFilter.values()) {
                item(key = value.name) {
                    FilterChip(
                        selected = filter == value,
                        onClick = { filter = value },
                        label = {
                            Text(
                                when (value) {
                                    TaskFilter.ALL -> "ทั้งหมด (${tasks.size})"
                                    TaskFilter.ACTIVE -> "กำลังทำ"
                                    TaskFilter.WAITING -> "รอ"
                                    TaskFilter.FAILED -> "ล้มเหลว"
                                    TaskFilter.DONE -> "เสร็จ"
                                },
                            )
                        },
                    )
                }
            }
        }
        if (tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.CheckCircle,
                title = "ยังไม่มีงาน",
                description = "สั่ง AI ในแชทได้เลย เช่น “สร้างไฟล์ notes.txt: สวัสดี”",
            )
        } else if (shown.isEmpty()) {
            Text(
                "ไม่มีงานในกลุ่มนี้",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                if (actionError != null) {
                    item(key = "__error__") {
                        Text(actionError!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                items(shown, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onCancel = { act { services.tasks.cancel(task.id, "cancelled by user") } },
                        onRetry = { act { services.tasks.retry(task.id) } },
                        onPause = { act { services.tasks.pause(task.id) } },
                        onResume = { act { services.tasks.resume(task.id) } },
                        onApprove = {
                            act { services.tasks.transition(task.id, TaskState.RUNNING, "approved by user") }
                        },
                        onTakeControl = { act { services.tasks.cancel(task.id, "user took control") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: AiTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onApprove: () -> Unit,
    onTakeControl: () -> Unit,
) {
    val spacing = LocalSpacing.current
    var expanded by remember { mutableStateOf(false) }
    val color = when {
        task.state == TaskState.COMPLETED -> statusColor(StatusKind.SUCCESS)
        task.state == TaskState.FAILED -> statusColor(StatusKind.DANGER)
        task.state == TaskState.WAITING_PERMISSION || task.state == TaskState.WAITING_USER ->
            statusColor(StatusKind.WARNING)
        TaskState.isTerminal(task.state) -> MaterialTheme.colorScheme.outline
        else -> statusColor(StatusKind.INFO)
    }
    Surface(shape = RoundedCornerShape(AicodeRadii.M), color = MaterialTheme.colorScheme.surfaceVariant) {
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
            if (expanded) {
                TaskDetails(task)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "ซ่อน" else "รายละเอียด")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                if (task.state == TaskState.RUNNING) {
                    TextButton(onClick = onPause) { Text("พัก") }
                }
                if (task.state == TaskState.PAUSED) {
                    TextButton(onClick = onResume) { Text("ทำต่อ") }
                }
                if (task.state == TaskState.WAITING_PERMISSION || task.state == TaskState.WAITING_USER) {
                    TextButton(onClick = onApprove) { Text("อนุมัติ") }
                    TextButton(onClick = onTakeControl) { Text("รับช่วงเอง") }
                }
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

@Composable
private fun TaskDetails(task: AiTask) {
    val spacing = LocalSpacing.current
    Column(modifier = Modifier.padding(vertical = spacing.xs)) {
        for ((label, value) in taskDetailRows(task)) {
            Text(
                "$label: $value",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

private fun taskDetailRows(task: AiTask): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    rows.add("id" to task.id)
    if (task.goal.isNotBlank()) rows.add("เป้าหมาย" to task.goal)
    if (task.currentStep.isNotBlank()) rows.add("ขั้นปัจจุบัน" to task.currentStep)
    if (task.agentId.isNotBlank()) rows.add("agent" to task.agentId)
    if (task.modelId.isNotBlank()) rows.add("model" to task.modelId)
    if (task.capabilityId.isNotBlank()) rows.add("capability" to task.capabilityId)
    if (task.verificationNote.isNotBlank()) rows.add("ตรวจ" to task.verificationNote)
    if (task.checkpointId.isNotBlank()) rows.add("checkpoint" to task.checkpointId)
    return rows
}
