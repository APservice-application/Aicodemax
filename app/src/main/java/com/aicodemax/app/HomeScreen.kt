package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.aicodemax.core.common.fold
import com.aicodemax.ui.designsystem.LocalSpacing

/** Home: greeting + live status cards (all values read from real stores). */
@Composable
fun HomeScreen(services: ServiceLocator, onOpen: (String) -> Unit, onNewChat: () -> Unit) {
    val spacing = LocalSpacing.current
    var resourceLine by remember { mutableStateOf("กำลังอ่านทรัพยากร…") }

    LaunchedEffect(Unit) {
        resourceLine = services.resources.snapshot().fold(
            onSuccess = { s ->
                val ramMb = s.ramAvailableBytes / 1024 / 1024
                val diskMb = s.storageAvailableBytes / 1024 / 1024
                "RAM ว่าง ${ramMb}MB • ดิสก์ว่าง ${diskMb}MB • แบต ${s.batteryPercent}%"
            },
            onFailure = { "อ่านทรัพยากรไม่ได้: ${it.message}" },
        )
    }

    val auditCount = remember { services.audit.count() }
    val browserTabs = remember { services.browser.tabs.value.size }
    val tools = remember { services.toolRegistry.all() }
    val runnable = tools.count { it.isRunnable() }
    val tasks = remember { services.tasks.list() }
    val activeTasks = tasks.count { !com.aicodemax.ai.tasks.TaskState.isTerminal(it.state) }
    val modelLine = remember {
        services.router.pick().fold(
            onSuccess = { "พร้อมใช้: ${it.name}" },
            onFailure = { "ยังไม่มีโมเดลที่พร้อม (${services.models.all().size} registered)" },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("สวัสดี 👋", style = MaterialTheme.typography.titleLarge)
        Text("วันนี้ให้ช่วยอะไร?", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onNewChat, modifier = Modifier.fillMaxWidth()) { Text("เริ่มแชทใหม่") }

        StatusCard(title = "AI", line = modelLine, action = "ดู" to { onOpen(Routes.MODELS) })
        StatusCard(
            title = "เครื่องมือ",
            line = "พร้อมใช้ $runnable/${tools.size} ตัว",
            action = "ดู" to { onOpen(Routes.TOOLS) },
        )
        StatusCard(
            title = "งาน",
            line = if (tasks.isEmpty()) "ยังไม่มีงาน" else "กำลังทำ $activeTasks งาน (ทั้งหมด ${tasks.size})",
            action = "ดู" to { onOpen(Routes.TASKS) },
        )
        StatusCard(
            title = "เบราว์เซอร์",
            line = if (browserTabs == 0) "ยังไม่มีแท็บ" else "$browserTabs แท็บ",
            action = "เปิด" to { onOpen(Routes.BROWSER) },
        )
        StatusCard(
            title = "ตรวจสอบ",
            line = "$auditCount รายการ",
            action = "ดู" to { onOpen(Routes.AUDIT) },
        )
        StatusCard(title = "เครื่อง", line = resourceLine, action = null)
    }
}

@Composable
private fun StatusCard(title: String, line: String, action: Pair<String, () -> Unit>?) {
    val spacing = LocalSpacing.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(line, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                TextButton(onClick = action.second) { Text(action.first) }
            }
        }
    }
}
