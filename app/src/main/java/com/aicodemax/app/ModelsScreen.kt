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
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.ai.models.InstallState
import com.aicodemax.ai.models.ModelDescriptor
import com.aicodemax.ai.models.ModelRequirement
import com.aicodemax.ai.models.ScoringModelRouter
import com.aicodemax.core.common.fold
import com.aicodemax.core.resources.ResourceSnapshot
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

/** Models Center (CP-38): registry + install/download/health + scored recommendation. */
@Composable
fun ModelsScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var resourceText by remember { mutableStateOf("กำลังอ่าน…") }
    var snapshot by remember { mutableStateOf<ResourceSnapshot?>(null) }
    var models by remember { mutableStateOf(services.models.all()) }
    var verdict by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun refreshModels() {
        models = services.models.all()
        verdict = services.router.pick().fold(
            onSuccess = { "router เลือก: ${it.name} (${it.status.name})" },
            onFailure = { it.message },
        )
    }

    LaunchedEffect(Unit) {
        services.resources.snapshot().fold(
            onSuccess = { s ->
                snapshot = s
                resourceText = "RAM ว่าง ${s.ramAvailableBytes / 1024 / 1024}MB / " +
                    "${s.ramTotalBytes / 1024 / 1024}MB\n" +
                    "ดิสก์ว่าง ${s.storageAvailableBytes / 1024 / 1024}MB / " +
                    "${s.storageTotalBytes / 1024 / 1024}MB\n" +
                    "แบต ${s.batteryPercent}%${if (s.batteryCharging) " (ชาร์จ)" else ""}\n" +
                    "เน็ต: ${if (!s.networkAvailable) "ออฟไลน์" else if (s.networkUnmetered) "ไม่จำกัด" else "จำกัดปริมาณ"}"
            },
            onFailure = { resourceText = "อ่านไม่ได้: ${it.message}" },
        )
        refreshModels()
    }

    val scored = remember(models, snapshot) {
        if (models.isEmpty()) {
            "ยังไม่มีโมเดลที่ลงทะเบียน"
        } else {
            val router = ScoringModelRouter(
                services.models,
                resources = { snapshot },
                health = { id ->
                    services.installer.healthCheck(id).fold(
                        onSuccess = { it.healthy },
                        onFailure = { false },
                    )
                },
            )
            router.pick(ModelRequirement("chat")).fold(
                onSuccess = { "แนะนำ: ${it.name} (${it.kind.name})" },
                onFailure = { it.message },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "__head__") {
            Text("โมเดล (${models.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (models.isEmpty()) {
            item(key = "__empty__") {
                Text("ยังไม่มีโมเดล — ลงทะเบียนผ่าน AI setup ในอนาคต / ติดตั้งจาก URL ด้านล่างเมื่อมี model id")
            }
        }
        items(models, key = { it.id }) { model ->
            ModelCard(
                model = model,
                progressLine = progressLine(services, model.id),
                onHealth = {
                    scope.launch {
                        working = true
                        services.installer.healthCheck(model.id).fold(
                            onSuccess = { note = "${model.name}: ${it.detail}" },
                            onFailure = { note = it.message },
                        )
                        refreshModels()
                        working = false
                    }
                },
                onPause = { services.installer.pause(model.id) },
                onResume = { services.installer.resume(model.id) },
                onCancel = { services.installer.cancel(model.id) },
            )
        }
        item(key = "__install__") {
            Text("ติดตั้งจาก URL", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("https://…/model.bin (ต้องมี model id ก่อน)") },
                )
            }
            Text(
                "การติดตั้งต้องเลือกโมเดลที่ลงทะเบียนแล้ว — ฟีเจอร์ลงทะเบียนโมเดลใหม่ตามมาในรอบถัดไป",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (note != null) {
            item(key = "__note__") { Text(note!!, style = MaterialTheme.typography.bodyMedium) }
        }
        item(key = "__verdict__") {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
                    Text(verdict, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        scored,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
        item(key = "__res__") {
            Text("ทรัพยากรเครื่อง", style = MaterialTheme.typography.titleMedium)
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(resourceText, modifier = Modifier.fillMaxWidth().padding(spacing.md))
            }
        }
    }
}

private fun progressLine(services: ServiceLocator, modelId: String): String {
    val progress = services.installer.progress(modelId)
    return when (progress.state) {
        InstallState.NOT_INSTALLED -> "ยังไม่ติดตั้ง"
        InstallState.DOWNLOADING -> "ดาวน์โหลด ${progress.bytesDone}/${progress.bytesTotal} bytes"
        InstallState.PAUSED -> "หยุดชั่วคราว (${progress.bytesDone} bytes)"
        InstallState.READY -> "พร้อมใช้"
        else -> progress.state.name + (if (progress.detail.isNotBlank()) " — ${progress.detail}" else "")
    }
}

@Composable
private fun ModelCard(
    model: ModelDescriptor,
    progressLine: String,
    onHealth: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
            Text("${model.name} — ${model.status.name}", style = MaterialTheme.typography.titleSmall)
            Text(
                "${model.kind.name}" + if (model.sizeBytes > 0) " • ${model.sizeBytes / 1024 / 1024}MB" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(progressLine, style = MaterialTheme.typography.bodyMedium)
            if (model.statusDetail.isNotBlank()) {
                Text(
                    model.statusDetail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextButton(onClick = onHealth) { Text("ตรวจสุขภาพ") }
                TextButton(onClick = onPause) { Text("พัก") }
                TextButton(onClick = onResume) { Text("ต่อ") }
                TextButton(onClick = onCancel) { Text("ยกเลิก") }
            }
        }
    }
}
