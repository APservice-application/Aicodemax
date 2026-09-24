package com.aicodemax.app

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.app.Activity
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.text.DateFormat
import java.util.Date

private enum class VideoPage { HOME, NEW, EDIT, EXPORT, ADVANCED }
private enum class VideoSort { RECENT, NAME }

/** PDF Screens 1–8. The editor stays a separate workspace, no horizontal tab wall. */
@Composable
fun VideoWorkspace(
    services: ServiceLocator,
    onOpen: (String) -> Unit,
    onHandToChat: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(VideoPage.HOME) }
    var projectId by remember { mutableStateOf<String?>(null) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(VideoSort.RECENT) }
    var manage by remember { mutableStateOf<Project?>(null) }
    var manageAction by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    var defaultAspect by remember {
        mutableStateOf(context.getSharedPreferences("video_editor", Context.MODE_PRIVATE).getString("default_aspect", "9:16") ?: "9:16")
    }

    suspend fun reload() {
        services.media.listProjects().fold(onSuccess = { projects = it }, onFailure = { error = it.message })
    }
    LaunchedEffect(page) { if (page == VideoPage.HOME) reload() }
    BackHandler {
        page = when (page) {
            VideoPage.HOME -> { onOpen(Routes.CHAT); VideoPage.HOME }
            VideoPage.NEW, VideoPage.EDIT -> VideoPage.HOME
            VideoPage.EXPORT, VideoPage.ADVANCED -> VideoPage.EDIT
        }
    }
    val palette = darkColorScheme(
        primary = VideoInk.green, onPrimary = VideoInk.text,
        background = VideoInk.background, onBackground = VideoInk.text,
        surface = VideoInk.surface, onSurface = VideoInk.text,
        surfaceVariant = VideoInk.raised, onSurfaceVariant = VideoInk.muted,
        outline = VideoInk.border, error = VideoInk.danger,
    )
    MaterialTheme(colorScheme = palette) {
        Box(Modifier.fillMaxSize().background(VideoInk.background)) {
            when (page) {
                VideoPage.HOME -> VideoHome(
                    projects = if (sort == VideoSort.RECENT) projects.sortedByDescending { it.updatedAt }
                               else projects.sortedBy { it.name.lowercase() },
                    services = services,
                    onBack = { onOpen(Routes.CHAT) },
                    onNew = { page = VideoPage.NEW },
                    onEdit = { projectId = it; page = VideoPage.EDIT },
                    onManage = { project, action ->
                        manage = project; manageAction = action; renameText = project.name
                    },
                    onSort = { sort = if (sort == VideoSort.RECENT) VideoSort.NAME else VideoSort.RECENT },
                    onDefaults = { manageAction = "defaults" },
                    onImport = { page = VideoPage.NEW },
                    sort = sort,
                )
                VideoPage.NEW -> VideoNewProject(services, defaultAspect, onBack = { page = VideoPage.HOME }) {
                    projectId = it
                    page = VideoPage.EDIT
                }
                VideoPage.EDIT -> projectId?.let { id ->
                    VideoEditorScreen(
                        services = services,
                        projectId = id,
                        onBack = { page = VideoPage.HOME },
                        onExport = { page = VideoPage.EXPORT },
                        onAdvanced = { page = VideoPage.ADVANCED },
                        onHandToChat = onHandToChat,
                        onOpen = onOpen,
                    )
                }
                VideoPage.EXPORT -> projectId?.let { id ->
                    VideoExportScreen(services, id, onBack = { page = VideoPage.EDIT }, onQueue = { onOpen(Routes.RENDER) })
                }
                VideoPage.ADVANCED -> projectId?.let { id ->
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            VideoIconButton("‹", "กลับไปหน้าตัดต่อ", { page = VideoPage.EDIT })
                            Text("เครื่องมือขั้นสูง", color = VideoInk.text, style = MaterialTheme.typography.titleMedium)
                        }
                        // Preserve the existing pro-level tools until each is migrated into a dedicated sheet.
                        Box(Modifier.weight(1f)) { TimelineScreen(services, initialProjectId = id) }
                    }
                }
            }
            if (error != null) {
                Text(error!!, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(VideoInk.surface).padding(16.dp), color = VideoInk.danger)
            }
        }

        if (manageAction == "defaults") {
            AlertDialog(
                onDismissRequest = { manageAction = "" },
                title = { Text("สัดส่วนเริ่มต้น") },
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("9:16", "16:9", "1:1").forEach { aspect ->
                            VideoChip(aspect, defaultAspect == aspect, {
                                defaultAspect = aspect
                                context.getSharedPreferences("video_editor", Context.MODE_PRIVATE).edit().putString("default_aspect", aspect).apply()
                            })
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { manageAction = "" }) { Text("เสร็จสิ้น") } },
            )
        }
        if (manage != null && manageAction in listOf("rename", "duplicate", "delete")) {
            val project = manage!!
            AlertDialog(
                onDismissRequest = { manage = null; manageAction = "" },
                title = { Text(when (manageAction) { "rename" -> "เปลี่ยนชื่อโปรเจกต์"; "duplicate" -> "ทำสำเนาโปรเจกต์"; else -> "ลบโปรเจกต์?" }) },
                text = {
                    if (manageAction == "rename") {
                        OutlinedTextField(renameText, onValueChange = { renameText = it }, singleLine = true, label = { Text("ชื่อใหม่") })
                    } else Text(
                        if (manageAction == "delete") "${project.name} จะถูกย้ายไปถังขยะ และกู้คืนได้จากเครื่องมือโปรเจกต์"
                        else "สร้างสำเนาของ ${project.name} โดยไม่เปลี่ยนต้นฉบับ",
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = manageAction != "rename" || renameText.isNotBlank(),
                        onClick = {
                            val action = manageAction
                            manage = null; manageAction = ""
                            scope.launch {
                                val result = when (action) {
                                    "rename" -> services.media.renameProject(project.id, renameText.trim(), "HUMAN")
                                    "duplicate" -> services.media.duplicateProject(project.id, "HUMAN")
                                    else -> services.media.deleteProject(project.id, "HUMAN")
                                }
                                result.fold(onSuccess = { reload() }, onFailure = { error = it.message })
                            }
                        },
                    ) { Text(if (manageAction == "delete") "ลบ" else "ยืนยัน", color = if (manageAction == "delete") VideoInk.danger else VideoInk.green) }
                },
                dismissButton = { TextButton(onClick = { manage = null; manageAction = "" }) { Text("ยกเลิก") } },
            )
        }
    }
}

@Composable
private fun VideoHome(
    projects: List<Project>,
    services: ServiceLocator,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
    onManage: (Project, String) -> Unit,
    onSort: () -> Unit,
    onDefaults: () -> Unit,
    onImport: () -> Unit,
    sort: VideoSort,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoIconButton("‹", "กลับไปแชท", onBack)
            Text("โปรเจกต์วิดีโอ", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = VideoInk.text, fontWeight = FontWeight.SemiBold)
            Box {
                VideoIconButton("⋮", "เมนูโปรเจกต์", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(VideoInk.surface)) {
                    DropdownMenuItem(text = { Text(if (sort == VideoSort.RECENT) "เรียงตามชื่อ" else "เรียงตามล่าสุด") }, onClick = { menu = false; onSort() })
                    DropdownMenuItem(text = { Text("นำเข้าจากไฟล์") }, onClick = { menu = false; onImport() })
                    DropdownMenuItem(text = { Text("สัดส่วนเริ่มต้น") }, onClick = { menu = false; onDefaults() })
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().height(118.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(VideoInk.green, VideoInk.greenDark)))
                        .clickable(onClickLabel = "สร้างโปรเจกต์ใหม่", onClick = onNew)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("＋", style = MaterialTheme.typography.headlineMedium, color = VideoInk.text)
                    Text("สร้างโปรเจกต์ใหม่", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = VideoInk.text)
                    Text("เริ่มจากรูปภาพหรือวิดีโอของคุณ", style = MaterialTheme.typography.bodySmall, color = VideoInk.text.copy(alpha = 0.84f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("โปรเจกต์ล่าสุด", modifier = Modifier.weight(1f), color = VideoInk.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${projects.size} โปรเจกต์", color = VideoInk.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (projects.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(16.dp)).background(VideoInk.surface), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("▧", color = VideoInk.green, style = MaterialTheme.typography.headlineMedium)
                            Text("ยังไม่มีโปรเจกต์", color = VideoInk.text)
                            Text("แตะการ์ดด้านบนเพื่อเริ่มตัดต่อ", color = VideoInk.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(projects, key = { it.id }) { project ->
                var cardMenu by remember { mutableStateOf(false) }
                val first = project.timeline.orderedClips().firstOrNull()
                var thumb by remember(project.id, project.updatedAt) { mutableStateOf<String?>(null) }
                LaunchedEffect(project.id, project.updatedAt) {
                    thumb = first?.second?.assetId?.let { id -> services.media.assetPath(project.id, id).fold({ it }, { null }) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(15.dp))
                        .background(VideoInk.surface).clickable(onClickLabel = "เปิด ${project.name}") { onEdit(project.id) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    VideoFrame(thumb, first?.first?.kind?.name ?: "VIDEO", modifier = Modifier.size(width = 84.dp, height = 68.dp).clip(RoundedCornerShape(9.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(project.name, color = VideoInk.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text("${videoTime(project.timeline.durationMs)} · ${if (project.updatedAt > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(project.updatedAt)) else "เพิ่งสร้าง"}", color = VideoInk.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    Box {
                        VideoIconButton("⋮", "จัดการ ${project.name}", { cardMenu = true })
                        DropdownMenu(expanded = cardMenu, onDismissRequest = { cardMenu = false }, modifier = Modifier.background(VideoInk.surface)) {
                            listOf("duplicate" to "ทำสำเนา", "rename" to "เปลี่ยนชื่อ", "delete" to "ลบโปรเจกต์").forEach { (action, label) ->
                                DropdownMenuItem(text = { Text(label, color = if (action == "delete") VideoInk.danger else VideoInk.text) }, onClick = { cardMenu = false; onManage(project, action) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun pickedName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "ไฟล์สื่อ"

private fun pickedKind(context: Context, uri: Uri): MediaKind? {
    val mime = context.contentResolver.getType(uri).orEmpty()
    val name = pickedName(context, uri).substringAfterLast('.', "").lowercase()
    // Do not offer HEIC/HEVC images as importable when MediaProjectPort cannot ingest them.
    return when {
        name in listOf("mp4", "mov", "mkv", "webm", "3gp") -> MediaKind.VIDEO
        name in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> MediaKind.IMAGE
        name in listOf("mp3", "m4a", "wav", "ogg", "flac") -> MediaKind.AUDIO
        '.' !in pickedName(context, uri) && mime.startsWith("video/") -> MediaKind.VIDEO
        '.' !in pickedName(context, uri) && mime.startsWith("image/") && mime != "image/heic" -> MediaKind.IMAGE
        '.' !in pickedName(context, uri) && mime.startsWith("audio/") -> MediaKind.AUDIO
        else -> null
    }
}

/** One temporary directory per picked asset preserves its human filename in the project. */
internal fun copyVideoPickedUri(context: Context, workspaceDir: File, uri: Uri): String? {
    val directory = File(workspaceDir, "video-import/${UUID.randomUUID()}")
    return try {
        directory.mkdirs()
        val name = pickedName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val suffix = if ('.' in name) "" else "." + (MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "mp4")
        val safe = (name + suffix).replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(96)
        val file = File(directory, safe)
        val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("เปิดสื่อไม่ได้")
        input.use { file.outputStream().use { dst -> it.copyTo(dst) } }
        file.absolutePath
    } catch (_: Exception) {
        directory.deleteRecursively()
        null
    }
}

internal fun clearVideoPickedCopy(path: String) {
    val file = File(path)
    file.delete()
    file.parentFile?.takeIf { it.parentFile?.name == "video-import" }?.delete()
}

@Composable
private fun VideoNewProject(services: ServiceLocator, defaultAspect: String, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var aspect by remember { mutableStateOf(defaultAspect) }
    var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var capturedUri by remember { mutableStateOf<Uri?>(null) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    val finalCapture by rememberUpdatedState(capturedFile)
    DisposableEffect(Unit) { onDispose { finalCapture?.delete() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { incoming ->
        selected = (selected + incoming).distinct()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && (capturedFile?.length() ?: 0L) > 0L) {
            capturedUri?.let { selected = (selected + it).distinct() }
        } else error = "กล้องไม่ได้บันทึกวิดีโอ กรุณาลองใหม่"
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoIconButton("×", "ยกเลิก", onBack, enabled = !busy)
            Text("โปรเจกต์ใหม่", modifier = Modifier.weight(1f), color = VideoInk.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(
                onClick = {
                    if (busy || selected.isEmpty()) return@TextButton
                    busy = true; error = null
                    scope.launch {
                        var createdId: String? = null
                        try {
                            val project = services.media.createProject("วิดีโอ ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())}", "HUMAN")
                                .fold(onSuccess = { it }, onFailure = { error = it.message; null })
                            if (project != null) {
                                createdId = project.id
                                var cursorMs = 0L
                                var imported = 0
                                for (uri in selected) {
                                    if (pickedKind(context, uri) == null) { error = "ชนิดไฟล์ไม่รองรับ: ${pickedName(context, uri)}"; continue }
                                    val temp = withContext(Dispatchers.IO) { copyVideoPickedUri(context, services.workspaceDir, uri) }
                                    if (temp == null) { error = "อ่านไฟล์ไม่ได้: ${pickedName(context, uri)}"; continue }
                                    try {
                                        val asset = services.media.importAsset(project.id, temp, "HUMAN").fold({ it }, { error = it.message; null })
                                        if (asset != null) {
                                            val duration = when (asset.kind) {
                                                MediaKind.IMAGE -> 3000L
                                                else -> asset.facts["durationMs"]?.toLongOrNull()?.takeIf { it > 0 }
                                            }
                                            if (duration == null) { error = "อ่านความยาว ${asset.originalName} ไม่ได้"; continue }
                                            services.media.addClip(project.id, asset.id, 0, duration, cursorMs, actor = "HUMAN").fold(
                                                onSuccess = { cursorMs += duration; imported++ },
                                                onFailure = { error = it.message },
                                            )
                                        }
                                    } finally { withContext(Dispatchers.IO) { clearVideoPickedCopy(temp) } }
                                }
                                if (imported > 0) {
                                    services.media.setCanvas(project.id, aspect, "HUMAN").fold(
                                        onSuccess = { onCreated(project.id) },
                                        onFailure = { error = it.message },
                                    )
                                } else if (error == null) error = "ไม่สามารถนำเข้าสื่อได้"
                            }
                        } catch (e: Exception) {
                            error = e.message ?: "สร้างโปรเจกต์ไม่ได้"
                        } finally { busy = false }
                    }
                },
                enabled = selected.isNotEmpty() && !busy,
            ) { Text(if (busy) "กำลังนำเข้า…" else "ถัดไป", color = if (selected.isNotEmpty() && !busy) VideoInk.green else VideoInk.muted, fontWeight = FontWeight.Bold) }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("1  เลือกสัดส่วนวิดีโอ", color = VideoInk.text, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("9:16", "16:9", "1:1").forEach { a ->
                    VideoChip(a, aspect == a, { aspect = a }, Modifier.weight(1f))
                }
            }
            Text("2  นำเข้าสื่อ (วิดีโอ/รูปภาพ)", color = VideoInk.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
            Text("เลือกหลายไฟล์ได้ · จัดลำดับตามที่เลือก", color = VideoInk.muted, style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 22.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(13.dp)).background(VideoInk.surface)
                        .clickable(enabled = !busy, onClickLabel = "เลือกวิดีโอหรือรูปภาพ") { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("＋", color = VideoInk.green, style = MaterialTheme.typography.headlineMedium)
                    Text("เลือกจากเครื่อง", color = VideoInk.text, style = MaterialTheme.typography.labelSmall)
                }
            }
            item {
                Column(
                    modifier = Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(13.dp)).background(VideoInk.surface)
                        .clickable(enabled = !busy, onClickLabel = "เปิดกล้องถ่ายวิดีโอ") {
                            try {
                                val dir = File(context.cacheDir, "video-capture").apply { mkdirs() }
                                val file = File.createTempFile("clip-", ".mp4", dir)
                                capturedFile = file
                                capturedUri = FileProvider.getUriForFile(context, "${context.packageName}.video-provider", file)
                                camera.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                                    putExtra(MediaStore.EXTRA_OUTPUT, capturedUri)
                                    clipData = ClipData.newUri(context.contentResolver, "Video capture", capturedUri!!)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                })
                            } catch (e: Exception) { error = e.message ?: "เปิดกล้องไม่ได้" }
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("◎", color = VideoInk.green, style = MaterialTheme.typography.headlineMedium)
                    Text("ถ่ายวิดีโอ", color = VideoInk.text, style = MaterialTheme.typography.labelSmall)
                }
            }
            itemsIndexed(selected, key = { _, uri -> uri.toString() }) { index, uri ->
                Box(Modifier.aspectRatio(0.85f).clip(RoundedCornerShape(13.dp)).border(1.dp, VideoInk.green, RoundedCornerShape(13.dp))) {
                    VideoFrame(uri.toString(), pickedKind(context, uri)?.name ?: "VIDEO", modifier = Modifier.fillMaxSize())
                    Text("${index + 1}", modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).background(VideoInk.green, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp), color = VideoInk.text, style = MaterialTheme.typography.labelSmall)
                    Text(pickedName(context, uri), modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(VideoInk.background.copy(alpha = 0.85f)).padding(5.dp), color = VideoInk.text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    VideoIconButton("×", "เอาไฟล์ที่ ${index + 1} ออก", { selected = selected.filterNot { it == uri } }, modifier = Modifier.align(Alignment.TopStart).size(40.dp), enabled = !busy)
                }
            }
        }
        if (error != null) Text(error!!, color = VideoInk.danger, modifier = Modifier.fillMaxWidth().padding(16.dp))
        if (busy) Text("กำลังคัดลอกไฟล์และวางบนไทม์ไลน์…", color = VideoInk.green, modifier = Modifier.padding(16.dp))
    }
}
