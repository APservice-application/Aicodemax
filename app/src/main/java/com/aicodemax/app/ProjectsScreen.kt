package com.aicodemax.app

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ScrollableTabRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.fold
import com.aicodemax.data.checkpoint.Checkpoint
import com.aicodemax.tools.editor.EditorBuffer
import com.aicodemax.tools.files.ContentMatch
import com.aicodemax.tools.files.FileEntry
import com.aicodemax.tools.project.Project
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

private enum class WorkspaceTab { OVERVIEW, FILES, SEARCH, CHAT, TASKS, BUILD, TEST, GIT, RESTORE }

/**
 * Project Workspace (CP-36): Overview/Files/Search/Chat/Tasks/Build/Test/Git/Restore.
 * Files tab has search/sort/grid + real editor (CP-42/43).
 */
@Composable
fun ProjectsScreen(services: ServiceLocator, onOpen: (String) -> Unit) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(WorkspaceTab.OVERVIEW) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var active by remember { mutableStateOf<Project?>(null) }
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadProjects() {
        services.projects.list().fold(
            onSuccess = { projects = it },
            onFailure = { error = it.message },
        )
        services.projects.getActive().fold(
            onSuccess = { active = it },
            onFailure = { active = null },
        )
    }

    LaunchedEffect(Unit) { loadProjects() }

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            for (value in WorkspaceTab.values()) {
                Tab(
                    selected = tab == value,
                    onClick = { tab = value },
                    text = {
                        Text(
                            when (value) {
                                WorkspaceTab.OVERVIEW -> "ภาพรวม"
                                WorkspaceTab.FILES -> "ไฟล์"
                                WorkspaceTab.SEARCH -> "ค้นหา"
                                WorkspaceTab.CHAT -> "แชท"
                                WorkspaceTab.TASKS -> "งาน"
                                WorkspaceTab.BUILD -> "Build"
                                WorkspaceTab.TEST -> "Test"
                                WorkspaceTab.GIT -> "Git"
                                WorkspaceTab.RESTORE -> "กู้คืน"
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
            WorkspaceTab.OVERVIEW -> OverviewTab(
                projects = projects,
                active = active,
                newName = newName,
                onName = { newName = it },
                onCreate = {
                    scope.launch {
                        services.projects.create(newName).fold(
                            onSuccess = { newName = ""; loadProjects() },
                            onFailure = { error = it.message },
                        )
                    }
                },
                onSelect = {
                    services.projects.setActive(it).fold(
                        onSuccess = { services.workingSet.setProject(it.id); loadProjects() },
                        onFailure = { error = it.message },
                    )
                },
            )
            WorkspaceTab.FILES -> FilesTab(services)
            WorkspaceTab.SEARCH -> SearchTab(services)
            WorkspaceTab.CHAT -> JumpTab(label = "คุยกับ AI เรื่องโปรเจกต์นี้", button = "เปิดแชท") { onOpen(Routes.CHAT) }
            WorkspaceTab.TASKS -> JumpTab(label = "งานของ AI ทั้งหมด", button = "เปิดหน้างาน") { onOpen(Routes.TASKS) }
            WorkspaceTab.BUILD -> JumpTab(label = "รัน build pipeline", button = "เปิด Build") { onOpen(Routes.BUILD) }
            WorkspaceTab.TEST -> JumpTab(label = "รัน test suites", button = "เปิด Test") { onOpen(Routes.BUILD) }
            WorkspaceTab.GIT -> JumpTab(label = "สถานะ git ของ workspace", button = "เปิด Git") { onOpen(Routes.GIT) }
            WorkspaceTab.RESTORE -> RestoreTab(services)
        }
    }
}

@Composable
private fun OverviewTab(
    projects: List<Project>,
    active: Project?,
    newName: String,
    onName: (String) -> Unit,
    onCreate: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        item(key = "__active__") {
            Text(
                if (active == null) "ยังไม่เลือกโปรเจกต์" else "โปรเจกต์ปัจจุบัน: ${active.name}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item(key = "__new__") {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                TextField(
                    value = newName,
                    onValueChange = onName,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("ชื่อโปรเจกต์ใหม่…") },
                )
                TextButton(onClick = onCreate, enabled = newName.isNotBlank()) { Text("สร้าง") }
            }
        }
        items(projects, key = { it.id }) { project ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (project.id == active?.id) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.fillMaxWidth().clickable { onSelect(project.id) },
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

private enum class SortMode { NAME, SIZE, TIME }

@Composable
private fun FilesTab(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(SortMode.NAME) }
    var grid by remember { mutableStateOf(false) }

    fun load(dir: String) {
        scope.launch {
            services.files.list(dir).fold(
                onSuccess = { entries = it; path = dir; error = null },
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

    Column(modifier = Modifier.fillMaxSize().padding(spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            if (path.isNotEmpty()) {
                TextButton(onClick = { load(path.substringBeforeLast("/", "")) }) { Text("← ขึ้นบน") }
            }
            Text(
                if (path.isEmpty()) "workspace/" else "workspace/$path",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            TextButton(onClick = { sort = SortMode.values()[(sort.ordinal + 1) % 3] }) {
                Text("เรียง: ${sort.name}")
            }
            TextButton(onClick = { grid = !grid }) { Text(if (grid) "มุมมอง: ตาราง" else "มุมมอง: รายการ") }
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }
        if (sorted.isEmpty() && error == null) {
            Text("ว่างเปล่า — สั่ง AI ในแชทได้เลย เช่น “สร้างไฟล์ notes.txt: สวัสดี”")
        }
        if (grid) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                items(sorted, key = { it.path }) { entry ->
                    FileCell(entry = entry, onClick = {
                        if (entry.isDirectory) load(entry.path) else editing = entry.path
                    })
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                items(sorted, key = { it.path }) { entry ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (entry.isDirectory) load(entry.path) else editing = entry.path
                        },
                    ) {
                        Column(modifier = Modifier.padding(spacing.sm)) {
                            Text((if (entry.isDirectory) "📁 " else "📄 ") + entry.name)
                            if (!entry.isDirectory) {
                                Text(
                                    "${entry.sizeBytes} bytes",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
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
        )
    }
}

@Composable
private fun FileCell(entry: FileEntry, onClick: () -> Unit) {
    val spacing = LocalSpacing.current
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(spacing.sm)) {
            Text(if (entry.isDirectory) "📁" else "📄")
            Text(entry.name, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}

@Composable
private fun SearchTab(services: ServiceLocator) {
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
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
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
private fun JumpTab(label: String, button: String, onJump: () -> Unit) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(label)
        TextButton(onClick = onJump) { Text(button) }
    }
}

@Composable
private fun RestoreTab(services: ServiceLocator) {
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
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
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

/** Real file editor dialog (CP-43): open → edit → save over EditorPort. */
@Composable
private fun EditorDialog(services: ServiceLocator, path: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var buffer by remember { mutableStateOf<EditorBuffer?>(null) }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedTick by remember { mutableStateOf(0) }

    LaunchedEffect(path) {
        services.editor.open(path).fold(
            onSuccess = { buffer = it; text = it.content },
            onFailure = { error = it.message },
        )
    }

    AlertDialog(
        onDismissRequest = {
            scope.launch { services.editor.close(path) }
            onClose()
        },
        title = { Text(path + if (buffer?.dirty == true && text != buffer?.content) " •" else "") },
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
                enabled = text != null && text != buffer?.content,
            ) { Text(if (savedTick > 0) "บันทึกแล้ว ✓" else "บันทึก") }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch { services.editor.close(path) }
                onClose()
            }) { Text("ปิด") }
        },
    )
}
