package com.aicodemax.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.fold
import com.aicodemax.data.checkpoint.Checkpoint
import com.aicodemax.tools.editor.EditorBuffer
import com.aicodemax.tools.files.ContentMatch
import com.aicodemax.tools.files.FileEntry
import com.aicodemax.tools.project.Project
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Refresh
import com.aicodemax.ui.designsystem.AicodeRadii
import com.aicodemax.ui.designsystem.ConfirmDialog
import com.aicodemax.ui.designsystem.EmptyState
import com.aicodemax.ui.designsystem.ErrorState
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.OutputBlock
import kotlinx.coroutines.launch

private enum class FilesTab { FILES, SEARCH, PROJECTS, AI, RESTORE }

/**
 * CP-137 files workspace (SCR-FILES-001..005): folder drill + search +
 * grid/list + sort + multi-select (copy/move/rename/delete) + details +
 * editor with unsaved guard + AI actions. Jump tabs replaced by AI handoff.
 */
@Composable
fun ProjectsScreen(
    services: ServiceLocator,
    onOpen: (String) -> Unit,
    onHandToChat: (String) -> Unit = {},
) {
    val spacing = LocalSpacing.current
    var tab by remember { mutableStateOf(FilesTab.FILES) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            for (value in FilesTab.values()) {
                Tab(
                    selected = tab == value,
                    onClick = { tab = value },
                    text = {
                        Text(
                            when (value) {
                                FilesTab.FILES -> "ไฟล์"
                                FilesTab.SEARCH -> "ค้นหา"
                                FilesTab.PROJECTS -> "โปรเจกต์"
                                FilesTab.AI -> "AI"
                                FilesTab.RESTORE -> "กู้คืน"
                            },
                        )
                    },
                )
            }
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(spacing.sm))
        }
        when (tab) {
            FilesTab.FILES -> FilesTabContent(services, onHandToChat)
            FilesTab.SEARCH -> SearchTabContent(services)
            FilesTab.PROJECTS -> ProjectsTabContent(services, onError = { error = it })
            FilesTab.AI -> AiTabContent(onHandToChat)
            FilesTab.RESTORE -> RestoreTabContent(services)
        }
    }
}

private enum class SortMode { NAME, SIZE, TIME }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilesTabContent(services: ServiceLocator, onHandToChat: (String) -> Unit) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var grid by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detailsOf by remember { mutableStateOf<String?>(null) }
    var detailsText by remember { mutableStateOf("") }
    var opMessage by remember { mutableStateOf<String?>(null) }
    var opBusy by remember { mutableStateOf(false) }
    var mkdirName by remember { mutableStateOf("") }
    var mkdirOpen by remember { mutableStateOf(false) }
    var destOpen by remember { mutableStateOf(false) }
    var destMode by remember { mutableStateOf("copy") }
    var destDir by remember { mutableStateOf("") }
    var renameOpen by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    var deleteConfirm by remember { mutableStateOf(false) }

    fun load(dir: String) {
        scope.launch {
            services.files.list(dir).fold(
                onSuccess = { entries = it; path = dir; error = null; selected = emptySet() },
                onFailure = { error = it.message },
            )
        }
    }

    LaunchedEffect(Unit) { load("") }

    val sorted = remember(entries, sort) {
        when (sort) {
            SortMode.NAME -> entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            SortMode.SIZE -> entries.sortedByDescending { it.sizeBytes }
            SortMode.TIME -> entries.sortedByDescending { it.modifiedAt }
        }
    }

    fun runOp(label: String, block: suspend () -> Unit) {
        scope.launch {
            opBusy = true
            opMessage = null
            try {
                block()
            } catch (e: Exception) {
                opMessage = "${label}ไม่ได้: ${e.message}"
            }
            load(path)
            selected = emptySet()
            opBusy = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(spacing.md)) {
        // SCR-FILES-001: breadcrumb + view controls.
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path.isNotEmpty()) {
                TextButton(onClick = { load(path.substringBeforeLast("/", "")) }) { Text("← ขึ้นบน") }
            }
            Text(
                if (path.isEmpty()) "workspace/" else "workspace/$path",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TextButton(onClick = { sort = SortMode.values()[(sort.ordinal + 1) % 3] }) {
                Text("เรียง: ${sort.name}")
            }
            TextButton(onClick = { grid = !grid }) { Text(if (grid) "ตาราง" else "รายการ") }
            TextButton(onClick = { mkdirOpen = true }) { Text("＋ โฟลเดอร์") }
            TextButton(onClick = { load(path) }) { Text("รีเฟรช") }
        }
        // SCR-FILES-002: selection toolbar.
        if (selected.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(AicodeRadii.M),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(spacing.sm)) {
                    Text(
                        "เลือก ${selected.size} รายการ",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                        TextButton(onClick = { destMode = "copy"; destDir = path; destOpen = true }, enabled = !opBusy) {
                            Text("คัดลอก")
                        }
                        TextButton(onClick = { destMode = "move"; destDir = path; destOpen = true }, enabled = !opBusy) {
                            Text("ย้าย")
                        }
                        if (selected.size == 1) {
                            TextButton(onClick = {
                                renameValue = selected.first().substringAfterLast('/')
                                renameOpen = true
                            }, enabled = !opBusy) { Text("เปลี่ยนชื่อ") }
                        }
                        TextButton(onClick = { deleteConfirm = true }, enabled = !opBusy) { Text("ลบ") }
                        TextButton(onClick = { selected = emptySet() }) { Text("ยกเลิก") }
                    }
                }
            }
        }
        if (error != null) {
            ErrorState(
                icon = Icons.Outlined.Refresh,
                title = "โหลดไฟล์ไม่ได้",
                explanation = "แตะลองใหม่ หรือตรวจสิทธิ์ไฟล์",
                onRetry = { load(path) },
                details = error,
            )
        }
        if (sorted.isEmpty() && error == null) {
            EmptyState(
                icon = Icons.Outlined.List,
                title = "โฟลเดอร์ว่าง",
                description = "สั่ง AI ในแชทได้เลย เช่น “สร้างไฟล์ notes.txt: สวัสดี”",
                primaryLabel = "โฟลเดอร์ใหม่",
                onPrimary = { mkdirOpen = true },
                secondaryLabel = "รีเฟรช",
                onSecondary = { load(path) },
            )
        }
        opMessage?.let { OutputBlock(it.take(600)) }
        if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                items(sorted, key = { it.path }) { entry ->
                    FileCell(
                        entry = entry,
                        checked = entry.path in selected,
                        onToggle = {
                            selected = if (entry.path in selected) selected - entry.path else selected + entry.path
                        },
                        onOpen = {
                            if (entry.isDirectory) load(entry.path) else editing = entry.path
                        },
                        onDetails = {
                            detailsOf = entry.path
                            scope.launch {
                                services.files.metadata(entry.path).fold(
                                    onSuccess = { detailsText = it.toString() },
                                    onFailure = { detailsText = it.message ?: "อ่านไม่ได้" },
                                )
                            }
                        },
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                items(sorted, key = { it.path }) { entry ->
                    Surface(
                        shape = RoundedCornerShape(AicodeRadii.S),
                        color = if (entry.path in selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty()) {
                                            selected = if (entry.path in selected) selected - entry.path
                                            else selected + entry.path
                                        } else if (entry.isDirectory) load(entry.path)
                                        else editing = entry.path
                                    },
                                    onLongClick = {
                                        selected = if (entry.path in selected) selected - entry.path
                                        else selected + entry.path
                                    },
                                )
                                .padding(spacing.sm),
                        ) {
                            Checkbox(
                                checked = entry.path in selected,
                                onCheckedChange = {
                                    selected = if (entry.path in selected) selected - entry.path
                                    else selected + entry.path
                                },
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text((if (entry.isDirectory) "📁 " else "📄 ") + entry.name)
                                if (!entry.isDirectory) {
                                    Text(
                                        "${entry.sizeBytes} bytes",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                            TextButton(onClick = {
                                detailsOf = entry.path
                                scope.launch {
                                    services.files.metadata(entry.path).fold(
                                        onSuccess = { detailsText = it.toString() },
                                        onFailure = { detailsText = it.message ?: "อ่านไม่ได้" },
                                    )
                                }
                            }) { Text("รายละเอียด") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        EditorDialog(
            services = services,
            path = target,
            onClose = { editing = null; load(path) },
            onHandToChat = onHandToChat,
        )
    }

    if (mkdirOpen) {
        AlertDialog(
            onDismissRequest = { mkdirOpen = false },
            title = { Text("โฟลเดอร์ใหม่") },
            text = {
                TextField(
                    value = mkdirName,
                    onValueChange = { mkdirName = it },
                    singleLine = true,
                    placeholder = { Text("ชื่อโฟลเดอร์…") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = mkdirName.trim()
                        mkdirOpen = false
                        mkdirName = ""
                        if (name.isNotEmpty()) {
                            runOp("สร้างโฟลเดอร์") {
                                val dest = if (path.isEmpty()) name else "$path/$name"
                                services.files.mkdir(dest).fold(
                                    onSuccess = { opMessage = "สร้างแล้ว: $dest" },
                                    onFailure = { opMessage = it.message },
                                )
                            }
                        }
                    },
                ) { Text("สร้าง") }
            },
            dismissButton = {
                TextButton(onClick = { mkdirOpen = false }) { Text("ยกเลิก") }
            },
        )
    }

    if (destOpen) {
        AlertDialog(
            onDismissRequest = { destOpen = false },
            title = { Text(if (destMode == "copy") "คัดลอกไปที่" else "ย้ายไปที่") },
            text = {
                TextField(
                    value = destDir,
                    onValueChange = { destDir = it },
                    singleLine = true,
                    placeholder = { Text("โฟลเดอร์ปลายทาง (ว่าง = ราก)…") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val dir = destDir.trim().trim('/')
                        val items = selected.toList()
                        destOpen = false
                        runOp(if (destMode == "copy") "คัดลอก" else "ย้าย") {
                            var ok = 0
                            var fail = 0
                            for (item in items) {
                                val dest = if (dir.isEmpty()) item.substringAfterLast('/')
                                else "$dir/${item.substringAfterLast('/')}"
                                val result = if (destMode == "copy") services.files.copy(item, dest)
                                else services.files.move(item, dest)
                                result.fold(onSuccess = { ok += 1 }, onFailure = { fail += 1 })
                            }
                            opMessage = "เสร็จ $ok รายการ" + if (fail > 0) " (พลาด $fail)" else ""
                        }
                    },
                ) { Text("ตกลง") }
            },
            dismissButton = {
                TextButton(onClick = { destOpen = false }) { Text("ยกเลิก") }
            },
        )
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("เปลี่ยนชื่อ") },
            text = {
                TextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val item = selected.firstOrNull()
                        val name = renameValue.trim()
                        renameOpen = false
                        if (item != null && name.isNotEmpty()) {
                            runOp("เปลี่ยนชื่อ") {
                                val dir = item.substringBeforeLast('/', "")
                                val dest = if (dir.isEmpty()) name else "$dir/$name"
                                services.files.move(item, dest).fold(
                                    onSuccess = { opMessage = "เปลี่ยนแล้ว: $dest" },
                                    onFailure = { opMessage = it.message },
                                )
                            }
                        }
                    },
                ) { Text("บันทึก") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("ยกเลิก") }
            },
        )
    }

    if (deleteConfirm) {
        ConfirmDialog(
            title = "ลบ ${selected.size} รายการ?",
            explanation = "ไฟล์/โฟลเดอร์ที่เลือกจะถูกลบถาวร กู้คืนไม่ได้",
            confirmLabel = "ลบ",
            onConfirm = {
                deleteConfirm = false
                runOp("ลบ") {
                    var ok = 0
                    var fail = 0
                    for (item in selected.toList()) {
                        services.files.delete(item).fold(
                            onSuccess = { ok += 1 },
                            onFailure = { fail += 1 },
                        )
                    }
                    opMessage = "ลบแล้ว $ok รายการ" + if (fail > 0) " (พลาด $fail)" else ""
                }
            },
            onDismiss = { deleteConfirm = false },
            destructive = true,
        )
    }

    detailsOf?.let { target ->
        AlertDialog(
            onDismissRequest = { detailsOf = null },
            title = { Text("รายละเอียด") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
                    Text(target, style = MaterialTheme.typography.bodySmall)
                    OutputBlock(detailsText.ifBlank { "กำลังโหลด…" })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    detailsOf = null
                    if (!target.endsWith('/')) editing = target
                }) { Text("เปิด") }
            },
            dismissButton = {
                TextButton(onClick = { detailsOf = null }) { Text("ปิด") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileCell(
    entry: FileEntry,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDetails: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Surface(
        shape = RoundedCornerShape(AicodeRadii.S),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onToggle),
    ) {
        Column(modifier = Modifier.padding(spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (entry.isDirectory) "📁" else "📄", modifier = Modifier.weight(1f))
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
            }
            Text(entry.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onDetails) { Text("รายละเอียด") }
        }
    }
}

@Composable
private fun SearchTabContent(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<ContentMatch>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }

    fun search() {
        scope.launch {
            services.files.search("", query).fold(
                onSuccess = { matches = it; searched = true },
                onFailure = { matches = emptyList(); searched = true },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("ค้นหาในไฟล์…") },
            )
            TextButton(onClick = { search() }, enabled = query.isNotBlank()) { Text("ค้น") }
        }
        if (searched && matches.isEmpty()) {
            Text("(ไม่พบ)")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            items(matches, key = { "${it.path}:${it.lineNumber}" }) { match ->
                Surface(
                    shape = RoundedCornerShape(AicodeRadii.S),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(spacing.sm)) {
                        Text(
                            "${match.path}:${match.lineNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(match.line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectsTabContent(services: ServiceLocator, onError: (String?) -> Unit) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var active by remember { mutableStateOf<Project?>(null) }
    var newName by remember { mutableStateOf("") }

    fun loadProjects() {
        services.projects.list().fold(
            onSuccess = { projects = it },
            onFailure = { onError(it.message) },
        )
        services.projects.getActive().fold(
            onSuccess = { active = it },
            onFailure = { active = null },
        )
    }

    LaunchedEffect(Unit) { loadProjects() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        item(key = "__active__") {
            val current = active
            Text(
                if (current == null) "ยังไม่เลือกโปรเจกต์" else "โปรเจกต์ปัจจุบัน: ${current.name}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item(key = "__new__") {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("ชื่อโปรเจกต์ใหม่…") },
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            services.projects.create(newName).fold(
                                onSuccess = { newName = ""; loadProjects() },
                                onFailure = { onError(it.message) },
                            )
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("สร้าง") }
            }
        }
        items(projects, key = { it.id }) { project ->
            Surface(
                shape = RoundedCornerShape(AicodeRadii.S),
                color = if (project.id == active?.id) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.fillMaxWidth().clickable {
                    services.projects.setActive(project.id).fold(
                        onSuccess = { services.workingSet.setProject(it.id); loadProjects() },
                        onFailure = { onError(it.message) },
                    )
                },
            ) {
                Column(modifier = Modifier.padding(spacing.sm)) {
                    Text(project.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        project.rootPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiTabContent(onHandToChat: (String) -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("สั่ง AI เรื่องไฟล์/โปรเจกต์", style = MaterialTheme.typography.titleMedium)
        Text(
            "คำสั่งจะถูกส่งไปให้ AI ในแชทพร้อมทำงานทันที",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        for ((label, prompt) in listOf(
            "📄 สรุปไฟล์ในโฟลเดอร์นี้" to "สรุปไฟล์ทั้งหมดใน workspace ว่ามีอะไรบ้าง",
            "🔍 หาไฟล์ที่ชื่อมีคำว่า report" to "หาไฟล์ที่ชื่อมีคำว่า report ใน workspace",
            "🧹 หาไฟล์ที่น่าจะลบได้" to "สำรวจ workspace แล้วบอกไฟล์ที่น่าจะลบได้อย่างปลอดภัย (ห้ามลบเอง)",
            "📦 บีบอัด workspace เป็น zip" to "บีบอัด workspace ทั้งหมดเป็นไฟล์ zip",
        )) {
            OutlinedButton(onClick = { onHandToChat(prompt) }, modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
        }
    }
}

@Composable
private fun RestoreTabContent(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var taskId by remember { mutableStateOf("") }
    var checkpoint by remember { mutableStateOf<Checkpoint?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val tasks = remember { services.tasks.list().filter { it.checkpointId.isNotBlank() } }

    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("งานที่มี checkpoint: ${tasks.size} งาน", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TextField(
                value = taskId,
                onValueChange = { taskId = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("task id…") },
            )
            TextButton(
                onClick = {
                    scope.launch {
                        services.checkpoints.loadLatest(taskId.trim()).fold(
                            onSuccess = { checkpoint = it; error = null },
                            onFailure = { checkpoint = null; error = it.message },
                        )
                    }
                },
                enabled = taskId.isNotBlank(),
            ) { Text("ดู") }
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        checkpoint?.let { cp ->
            Surface(
                shape = RoundedCornerShape(AicodeRadii.S),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(spacing.sm)) {
                    Text("“${cp.label}” • ${cp.files.size} ไฟล์", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        cp.stateJson.take(400),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        "ส่ง task id นี้ให้ AI ในแชทเพื่อกู้คืน (AI rehydrate จาก checkpoint นี้)",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * Real file editor dialog (CP-43): open → edit → save over EditorPort.
 * CP-137: unsaved-changes guard + AI actions (explain/fix via chat handoff).
 */
@Composable
private fun EditorDialog(
    services: ServiceLocator,
    path: String,
    onClose: () -> Unit,
    onHandToChat: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var buffer by remember { mutableStateOf<EditorBuffer?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedTick by remember { mutableStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        services.editor.open(path).fold(
            onSuccess = { buffer = it; text = it.content },
            onFailure = { error = it.message },
        )
    }

    fun dirty(): Boolean = text != null && text != buffer?.content

    fun closeNow() {
        scope.launch { services.editor.close(path) }
        onClose()
    }

    AlertDialog(
        onDismissRequest = { if (dirty()) confirmDiscard = true else closeNow() },
        title = { Text(path + if (dirty()) " •" else "") },
        text = {
            Column {
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                } else if (text == null) {
                    Text("กำลังโหลด…")
                } else {
                    TextField(
                        value = text!!,
                        onValueChange = { text = it },
                        maxLines = 12,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)) {
                    TextButton(onClick = { onHandToChat("อธิบายไฟล์ $path ว่าทำอะไร") }) { Text("🤖 อธิบาย") }
                    TextButton(onClick = { onHandToChat("ตรวจและแก้ไฟล์ $path ถ้ามีปัญหา") }) { Text("🔧 แก้ไข") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val next = text ?: return@TextButton
                    scope.launch {
                        services.editor.setContent(path, next).fold(
                            onSuccess = {
                                services.editor.save(path).fold(
                                    onSuccess = { buffer = EditorBuffer(path, next, false); savedTick += 1 },
                                    onFailure = { error = it.message },
                                )
                            },
                            onFailure = { error = it.message },
                        )
                    }
                },
                enabled = dirty(),
            ) { Text(if (savedTick > 0 && !dirty()) "บันทึกแล้ว ✓" else "บันทึก") }
        },
        dismissButton = {
            TextButton(onClick = { if (dirty()) confirmDiscard = true else closeNow() }) { Text("ปิด") }
        },
    )

    if (confirmDiscard) {
        ConfirmDialog(
            title = "ทิ้งการแก้ไข?",
            explanation = "ไฟล์ $path มีการแก้ไขที่ยังไม่บันทึก",
            confirmLabel = "ทิ้ง",
            onConfirm = { confirmDiscard = false; closeNow() },
            onDismiss = { confirmDiscard = false },
            destructive = true,
        )
    }
}
