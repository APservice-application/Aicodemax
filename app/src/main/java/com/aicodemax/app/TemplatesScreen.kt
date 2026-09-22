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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.LibraryItem
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.ProjectTemplate
import com.aicodemax.data.media.TemplateCategories
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

/** Templates + asset library (§51–53): save/apply project-graph templates, searchable library. */
@Composable
fun TemplatesScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var projectIndex by remember { mutableStateOf(0) }
    var templates by remember { mutableStateOf<List<ProjectTemplate>>(emptyList()) }
    var assets by remember { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var tplName by remember { mutableStateOf("") }
    var tplCategory by remember { mutableStateOf("custom") }
    var tplSlots by remember { mutableStateOf("") }
    var applyInputs by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var libQuery by remember { mutableStateOf("") }
    var libName by remember { mutableStateOf("") }
    var libKind by remember { mutableStateOf("effect") }
    var libRef by remember { mutableStateOf("") }

    val project = projects.getOrNull(projectIndex)

    fun load() {
        scope.launch {
            services.media.listProjects().fold(
                onSuccess = { projects = it },
                onFailure = { message = it.message },
            )
            services.media.listTemplates(null).fold(
                onSuccess = { templates = it },
                onFailure = { message = it.message },
            )
            services.media.libraryList(null).fold(
                onSuccess = { items = it },
                onFailure = { message = it.message },
            )
        }
    }

    fun loadAssets(projectId: String) {
        scope.launch {
            services.media.listAssets(projectId).fold(
                onSuccess = { assets = it },
                onFailure = { message = it.message },
            )
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(project?.id) { project?.id?.let { loadAssets(it) } }

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text("โปรเจกต์: ${project?.name ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                    if (projects.size > 1) {
                        OutlinedButton(onClick = { projectIndex = (projectIndex + 1) % projects.size }) {
                            Text("สลับ")
                        }
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                Text("บันทึกเทมเพลตจากโปรเจกต์นี้", style = MaterialTheme.typography.titleSmall)
                TextField(value = tplName, onValueChange = { tplName = it }, label = { Text("ชื่อ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("หมวด: $tplCategory (${TemplateCategories.ALL.joinToString("/")})", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TemplateCategories.ALL.take(7).forEach { c ->
                        OutlinedButton(onClick = { tplCategory = c }) { Text(c.take(4)) }
                    }
                }
                TextField(value = tplSlots, onValueChange = { tplSlots = it }, label = { Text("ช่อง main:1,รอง:2 (ว่าง=ไม่มีช่อง)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    val pid = project?.id ?: return@OutlinedButton
                    scope.launch {
                        val slots = tplSlots.split(",").mapNotNull {
                            val kv = it.split(":", limit = 2)
                            if (kv.size < 2 || kv[0].isBlank()) null else kv[0].trim() to kv[1].trim()
                        }.toMap()
                        // Resolve 1-based clip numbers to ids via timeline.get is done server-side by index too.
                        services.media.saveTemplate(tplName.trim(), tplCategory, pid, slots).fold(
                            onSuccess = { message = "บันทึกแล้ว: ${it.summary()}"; tplName = ""; load() },
                            onFailure = { message = it.message },
                        )
                    }
                }) { Text("บันทึกเทมเพลต") }
            }
            item {
                Text("เทมเพลต (${templates.size})", style = MaterialTheme.typography.titleSmall)
                Text("asset ในโปรเจกต์: ${if (assets.isEmpty()) "—" else assets.joinToString { "${it.id}=${it.originalName}" }}", style = MaterialTheme.typography.bodySmall)
            }
            items(templates, key = { it.id }) { tpl ->
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text("- ${tpl.summary()}", style = MaterialTheme.typography.bodySmall)
                    if (tpl.slots.isNotEmpty()) {
                        Text("ช่อง: ${tpl.slots.joinToString { "${it.id}(${it.kind})" }}", style = MaterialTheme.typography.bodySmall)
                        TextField(
                            value = applyInputs[tpl.id] ?: "",
                            onValueChange = { applyInputs = applyInputs + (tpl.id to it) },
                            label = { Text("แทนที่ main:assetId,...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedButton(onClick = {
                            val pid = project?.id ?: return@OutlinedButton
                            scope.launch {
                                val reps = (applyInputs[tpl.id] ?: "").split(",").mapNotNull {
                                    val kv = it.split(":", limit = 2)
                                    if (kv.size < 2 || kv[0].isBlank()) null else kv[0].trim() to kv[1].trim()
                                }.toMap()
                                services.media.applyTemplate(pid, tpl.id, reps, "HUMAN").fold(
                                    onSuccess = { message = "ใช้ ${tpl.name} แล้ว (เลิกทำได้ที่ไทม์ไลน์)" },
                                    onFailure = { message = it.message },
                                )
                            }
                        }) { Text("ใช้") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                services.media.deleteTemplate(tpl.id).fold(
                                    onSuccess = { message = "ลบแล้ว"; load() },
                                    onFailure = { message = it.message },
                                )
                            }
                        }) { Text("ลบ") }
                    }
                }
            }
            item {
                Text("คลัง asset (${items.size})", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = libQuery, onValueChange = { libQuery = it }, label = { Text("ค้นหา") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.6f))
                    OutlinedButton(onClick = {
                        scope.launch {
                            services.media.librarySearch(libQuery).fold(
                                onSuccess = { items = it; message = "เจอ ${it.size} รายการ" },
                                onFailure = { message = it.message },
                            )
                        }
                    }) { Text("ค้น") }
                }
                TextField(value = libName, onValueChange = { libName = it }, label = { Text("ชื่อ asset ใหม่") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = libKind, onValueChange = { libKind = it }, label = { Text("kind") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.4f))
                    TextField(value = libRef, onValueChange = { libRef = it }, label = { Text("ref/พาธ") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.55f))
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        services.media.libraryAdd(libKind.trim(), libName.trim(), emptyList(), libRef.trim()).fold(
                            onSuccess = { message = "เพิ่มแล้ว"; libName = ""; libRef = ""; load() },
                            onFailure = { message = it.message },
                        )
                    }
                }) { Text("เพิ่มเข้าคลัง") }
            }
            items(items, key = { it.id }) { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text("- ${item.summary()}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        scope.launch {
                            services.media.libraryRemove(item.id).fold(
                                onSuccess = { load() },
                                onFailure = { message = it.message },
                            )
                        }
                    }) { Text("ลบ") }
                }
            }
        }
    }
}
