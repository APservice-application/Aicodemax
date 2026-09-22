package com.aicodemax.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.aicodemax.core.common.fold
import com.aicodemax.data.skills.SkillMeta
import com.aicodemax.ui.designsystem.LocalSpacing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Skills Center (CP-58): list/import/view/delete skill files (.md/.txt/.zip ≤2MB). */
@Composable
fun SkillsScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var skills by remember { mutableStateOf<List<SkillMeta>>(emptyList()) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            services.skills.list().fold(
                onSuccess = { skills = it; error = null },
                onFailure = { error = it.message },
            )
        }
    }

    fun importUri(uri: Uri) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "skill.md"
                    val tmp = File(context.cacheDir, "skill-import-$name")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: throw IllegalStateException("cannot open file")
                    val installed = services.skills.install(tmp)
                    tmp.delete()
                    installed
                }
            }
            result.fold(
                onSuccess = { outcome ->
                    outcome.fold(
                        onSuccess = { error = null; load() },
                        onFailure = { error = it.message },
                    )
                },
                onFailure = { error = "นำเข้าไม่ได้: ${it.message}" },
            )
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importUri(uri)
    }

    LaunchedEffect(Unit) { load() }

    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("Skills — ความรู้เสริมให้ AI", style = MaterialTheme.typography.titleMedium)
        Text(
            "ไฟล์ .md/.txt/.zip (≤2MB) ฉีดเข้า context เป็น === SKILL: id ===",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        TextButton(onClick = { picker.launch("*/*") }) { Text("＋ นำเข้าไฟล์") }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        if (viewing != null) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(modifier = Modifier.fillMaxWidth().padding(spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(viewing!!.first, style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { viewing = null }) { Text("ปิด") }
                    }
                    Text(
                        viewing!!.second.take(2000),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (skills.isEmpty()) {
                item(key = "__empty__") { Text("ยังไม่มีสกิล — นำเข้าไฟล์ .md/.txt/.zip ได้เลย") }
            }
            items(skills, key = { it.id }) { skill ->
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                skill.id + if (skill.builtin) " • built-in" else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${skill.category} • ${skill.sizeBytes} bytes",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                        TextButton(onClick = {
                            scope.launch {
                                services.skills.get(skill.id).fold(
                                    onSuccess = { viewing = skill.id to it.content },
                                    onFailure = { error = it.message },
                                )
                            }
                        }) { Text("ดู") }
                        if (!skill.builtin) {
                            TextButton(onClick = {
                                scope.launch {
                                    services.skills.remove(skill.id).fold(
                                        onSuccess = { error = null; load() },
                                        onFailure = { error = it.message },
                                    )
                                }
                            }) { Text("ลบ") }
                        }
                    }
                }
            }
        }
    }
}
