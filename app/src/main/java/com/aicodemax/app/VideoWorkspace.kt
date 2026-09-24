package com.aicodemax.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Project
import com.aicodemax.ui.designsystem.AicodeRadii
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

private enum class VideoTab { PROJECTS, EDITOR, EXPORT }

/**
 * CP-136 video workspace shell (SCR-VIDEO-001/002/008/009): projects + the
 * full Timeline engine + export/render-queue entry. Engine untouched (§81).
 */
@Composable
fun VideoWorkspace(
    services: ServiceLocator,
    onOpen: (String) -> Unit,
    onHandToChat: (String) -> Unit = {},
) {
    var tab by remember { mutableStateOf(VideoTab.EDITOR) }
    var projectId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            Tab(selected = tab == VideoTab.PROJECTS, onClick = { tab = VideoTab.PROJECTS }, text = { Text("โปรเจกต์") })
            Tab(selected = tab == VideoTab.EDITOR, onClick = { tab = VideoTab.EDITOR }, text = { Text("ตัดต่อ") })
            Tab(selected = tab == VideoTab.EXPORT, onClick = { tab = VideoTab.EXPORT }, text = { Text("ส่งออก") })
        }
        when (tab) {
            VideoTab.PROJECTS -> VideoProjectsPanel(
                services = services,
                onEdit = {
                    projectId = it
                    tab = VideoTab.EDITOR
                },
            )
            VideoTab.EDITOR -> key(projectId) {
                TimelineScreen(services, initialProjectId = projectId)
            }
            VideoTab.EXPORT -> VideoExportPanel(
                onOpenRender = { onOpen(Routes.RENDER) },
                onAskAi = { onHandToChat(it) },
            )
        }
    }
}

@Composable
private fun VideoProjectsPanel(services: ServiceLocator, onEdit: (String) -> Unit) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    fun load() {
        scope.launch {
            services.media.listProjects().fold(
                onSuccess = { projects = it },
                onFailure = { message = it.message },
            )
        }
    }

    LaunchedEffect(Unit) { load() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "__new__") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("ชื่อโปรเจกต์วิดีโอใหม่…") },
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            services.media.createProject(newName.trim(), "HUMAN").fold(
                                onSuccess = {
                                    newName = ""
                                    message = null
                                    load()
                                },
                                onFailure = { message = it.message },
                            )
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("สร้าง") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (projects.isEmpty()) {
            item(key = "__empty__") {
                Text("ยังไม่มีโปรเจกต์ — สร้างใหม่ด้านบน หรือสั่ง AI ในแชท")
            }
        }
        items(projects, key = { it.id }) { project ->
            Surface(
                shape = RoundedCornerShape(AicodeRadii.M),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onEdit(project.id) },
            ) {
                Row(
                    modifier = Modifier.padding(spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(project.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${project.timeline.tracks.size} แทร็ก",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    TextButton(onClick = { onEdit(project.id) }) { Text("ตัดต่อ") }
                }
            }
        }
    }
}

@Composable
private fun VideoExportPanel(onOpenRender: () -> Unit, onAskAi: (String) -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("ส่งออกวิดีโอ", style = MaterialTheme.typography.titleMedium)
        Text(
            "คิวเรนเดอร์ / QC / อนุมัติ / เอ็กซ์พอร์ตไฟล์ อยู่ที่หน้าคิวเรนเดอร์",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        OutlinedButton(onClick = onOpenRender, modifier = Modifier.fillMaxWidth()) {
            Text("เปิดคิวเรนเดอร์")
        }
        Text("หรือสั่ง AI ในแชท", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedButton(onClick = { onAskAi("เอ็กซ์พอร์ตวิดีโอโปรเจกต์ปัจจุบันเป็นไฟล์ MP4") }) {
                Text("เอ็กซ์พอร์ต MP4")
            }
            OutlinedButton(onClick = { onAskAi("ตัดช่วงเงียบออกจากวิดีโอโปรเจกต์ปัจจุบัน") }) {
                Text("ตัดช่วงเงียบ")
            }
        }
    }
}
