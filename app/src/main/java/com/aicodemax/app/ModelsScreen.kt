package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.fold
import com.aicodemax.ui.designsystem.LocalSpacing

/** AI screen: model registry + router verdict + live resource snapshot. */
@Composable
fun ModelsScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    var resourceText by remember { mutableStateOf("กำลังอ่าน…") }

    LaunchedEffect(Unit) {
        resourceText = services.resources.snapshot().fold(
            onSuccess = { s ->
                "RAM ว่าง ${s.ramAvailableBytes / 1024 / 1024}MB / " +
                    "${s.ramTotalBytes / 1024 / 1024}MB\n" +
                    "ดิสก์ว่าง ${s.storageAvailableBytes / 1024 / 1024}MB / " +
                    "${s.storageTotalBytes / 1024 / 1024}MB\n" +
                    "แบต ${s.batteryPercent}%${if (s.batteryCharging) " (ชาร์จ)" else ""}\n" +
                    "เน็ต: ${if (!s.networkAvailable) "ออฟไลน์" else if (s.networkUnmetered) "ไม่จำกัด" else "จำกัดปริมาณ"}"
            },
            onFailure = { "อ่านไม่ได้: ${it.message}" },
        )
    }

    val models = remember { services.models.all() }
    val verdict = remember {
        services.router.pick().fold(
            onSuccess = { "router เลือก: ${it.name} (${it.status.name})" },
            onFailure = { it.message },
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("โมเดล", style = MaterialTheme.typography.titleMedium)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(modifier = Modifier.fillMaxWidth().padding(spacing.md)) {
                if (models.isEmpty()) {
                    Text("ยังไม่มีโมเดลที่ลงทะเบียน — bootstrap model จะมาใน Phase ถัดไป")
                } else {
                    for (model in models) {
                        Text("• ${model.name} — ${model.status.name}")
                    }
                }
                Text(
                    verdict,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
        Text("ทรัพยากรเครื่อง", style = MaterialTheme.typography.titleMedium)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(resourceText, modifier = Modifier.fillMaxWidth().padding(spacing.md))
        }
    }
}
