package com.aicodemax.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aicodemax.core.state.AutonomyLevel
import com.aicodemax.core.state.ThemeMode
import com.aicodemax.core.state.UiMode
import com.aicodemax.data.settings.SettingsRepository
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(
    repository: SettingsRepository,
    onOpenAbout: () -> Unit,
    permissionLine: String = "",
    denies: List<String> = emptyList(),
    onRevokeAll: () -> Unit = {},
    onOpenAudit: () -> Unit = {},
) {
    val theme by repository.theme.collectAsState(initial = ThemeMode.SYSTEM)
    val autonomy by repository.autonomy.collectAsState(initial = AutonomyLevel.ASK_ALWAYS)
    val model by repository.activeModelId.collectAsState(initial = null)
    val uiMode by repository.uiMode.collectAsState(initial = UiMode.PRO)
    val scope = rememberCoroutineScope()
    var revokedTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    SettingsScreen(
        theme = theme,
        autonomy = autonomy,
        activeModel = model,
        uiMode = uiMode,
        onTheme = { scope.launch { repository.setTheme(it) } },
        onAutonomy = { scope.launch { repository.setAutonomy(it) } },
        onUiMode = { scope.launch { repository.setUiMode(it) } },
        onOpenAbout = onOpenAbout,
        permissionLine = permissionLine,
        denies = denies,
        revokedTick = revokedTick,
        onRevokeAll = { onRevokeAll(); revokedTick += 1 },
        onOpenAudit = onOpenAudit,
    )
}

@Composable
fun SettingsScreen(
    theme: ThemeMode,
    autonomy: AutonomyLevel,
    activeModel: String?,
    uiMode: UiMode = UiMode.PRO,
    onTheme: (ThemeMode) -> Unit,
    onAutonomy: (AutonomyLevel) -> Unit,
    onUiMode: (UiMode) -> Unit = {},
    onOpenAbout: () -> Unit,
    permissionLine: String = "",
    denies: List<String> = emptyList(),
    revokedTick: Int = 0,
    onRevokeAll: () -> Unit = {},
    onOpenAudit: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("รูปลักษณ์", style = MaterialTheme.typography.titleMedium)
        for (option in ThemeMode.values()) {
            RadioRow(
                label = when (option) {
                    ThemeMode.LIGHT -> "สว่าง"
                    ThemeMode.DARK -> "มืด"
                    ThemeMode.SYSTEM -> "ตามระบบ"
                },
                selected = theme == option,
                onClick = { onTheme(option) },
            )
        }
        Text("โหมดหน้าจอ", style = MaterialTheme.typography.titleMedium)
        for (option in UiMode.values()) {
            RadioRow(
                label = when (option) {
                    UiMode.SIMPLE -> "ง่าย (มือใหม่ — เมนูหลักเท่านั้น)"
                    UiMode.PRO -> "โปร (เต็มทุกเมนู)"
                },
                selected = uiMode == option,
                onClick = { onUiMode(option) },
            )
        }
        Text("ความเป็นอิสระของ AI", style = MaterialTheme.typography.titleMedium)
        for (option in AutonomyLevel.values()) {
            RadioRow(
                label = when (option) {
                    AutonomyLevel.ASK_ALWAYS -> "ถามก่อนทุกงานที่ต้องขอสิทธิ์"
                    AutonomyLevel.AUTO_SAFE -> "ทำเองเฉพาะงานปลอดภัย"
                    AutonomyLevel.AUTO_ALL -> "ทำเองทั้งหมด"
                },
                selected = autonomy == option,
                onClick = { onAutonomy(option) },
            )
        }
        Text("โมเดล", style = MaterialTheme.typography.titleMedium)
        Text(
            text = activeModel ?: "ยังไม่มีโมเดลที่พร้อมใช้",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text("ความปลอดภัยและสิทธิ์", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "AI ขอสิทธิ์ก่อนทำทุกงานที่เสี่ยง — งานที่ถูกบล็อกจะบันทึกใน audit เสมอ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        if (permissionLine.isNotBlank()) {
            Text(permissionLine, style = MaterialTheme.typography.bodyMedium)
        }
        if (denies.isNotEmpty()) {
            for (deny in denies.take(10)) {
                Text(
                    "✕ ปฏิเสธถาวร: $deny",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (revokedTick > 0) {
            Text(
                "ล้างสิทธิ์ทั้งหมดแล้ว",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Row {
            TextButton(onClick = onRevokeAll) { Text("ล้างสิทธิ์ทั้งหมด") }
            TextButton(onClick = onOpenAudit) { Text("ดู audit") }
        }
        TextButton(onClick = onOpenAbout) { Text("เกี่ยวกับ / ลิขสิทธิ์") }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Appropriate Legal Notices (AMENDMENT-002): GPLv3 + Termux attribution. */
@Composable
fun AboutScreen() {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("Aicodemax", style = MaterialTheme.typography.titleLarge)
        Text(
            "OWN AI APPLICATION — free software. Version 0.1.0 (foundation).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("ลิขสิทธิ์", style = MaterialTheme.typography.titleMedium)
        Text(
            "This program is free software: you can redistribute it and/or modify it " +
                "under the terms of the GNU General Public License as published by the Free " +
                "Software Foundation, either version 3 of the License, or (at your option) " +
                "any later version. This program comes with ABSOLUTELY NO WARRANTY. " +
                "Full license: LICENSE file in the source repository.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("ซอฟต์แวร์บุคคลที่สาม", style = MaterialTheme.typography.titleMedium)
        Text(
            "• Termux (https://github.com/termux/termux-app) — GPLv3, embedded terminal " +
                "stack for the in-app terminal tool.\n" +
                "• Android-Terminal-Emulator lineage — Apache 2.0.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Source code: https://github.com/APservice-application/Aicodemax",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
