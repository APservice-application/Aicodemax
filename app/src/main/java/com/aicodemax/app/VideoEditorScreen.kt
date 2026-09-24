package com.aicodemax.app

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal enum class VideoSheet(val title: String) {
    MEDIA("เพิ่มสื่อ"), TRIM("ตัดหัว / ตัดท้าย"), TRANSFORM("ปรับแต่ง (Transform)"),
    SPEED("ความเร็ว"), KEYFRAME("คีย์เฟรม"), TRANSITION("ทรานซิชัน"),
    AUDIO("เสียง (Audio)"), COLOR("ปรับสี (Color)"), FX("เอฟเฟกต์"),
    MASK("มาสก์ / กรีนสกรีน"), MOTION("โมชั่น / กันสั่น"), TEXT("ข้อความ"),
    AI("AI วิดีโอ"), MORE("เครื่องมือเพิ่มเติม"),
}

/** PDF Screens 3–7. Backing operations call the existing MediaProjectPort; no mock commands. */
@Composable
internal fun VideoEditorScreen(
    services: ServiceLocator,
    projectId: String,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onAdvanced: () -> Unit,
    onHandToChat: (String) -> Unit,
    onOpen: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("video_editor", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var project by remember(projectId) { mutableStateOf<Project?>(null) }
    var timeline by remember(projectId) { mutableStateOf<Timeline?>(null) }
    var assets by remember(projectId) { mutableStateOf<List<MediaAsset>>(emptyList()) }
    var paths by remember(projectId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedId by remember(projectId) { mutableStateOf<String?>(null) }
    var playheadMs by remember(projectId) { mutableLongStateOf(prefs.getLong("head_$projectId", 0)) }
    var playing by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<VideoSheet?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(58f) }
    var undoCount by remember { mutableStateOf(0) }
    var redoCount by remember { mutableStateOf(0) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var fullscreenPlaying by remember { mutableStateOf(false) }
    val activity = remember(context) {
        var ctx: Context? = context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        ctx as? Activity
    }
    DisposableEffect(fullscreen) {
        val previous = activity?.requestedOrientation
        if (fullscreen) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (fullscreen && previous != null) activity?.requestedOrientation = previous }
    }

    suspend fun refresh() {
        val nextProject = services.media.openProject(projectId).fold({ it }, { message = it.message; null }) ?: return
        val nextAssets = services.media.listAssets(projectId).fold({ it }, { message = it.message; emptyList() })
        val nextPaths = withContext(Dispatchers.IO) {
            nextAssets.associate { asset ->
                asset.id to services.media.assetPath(projectId, asset.id).fold({ it }, { "" })
            }
        }
        project = nextProject
        timeline = nextProject.timeline
        assets = nextAssets
        paths = nextPaths
        if (selectedId != null && nextProject.timeline.findClip(selectedId!!) == null) selectedId = null
        playheadMs = playheadMs.coerceIn(0, nextProject.timeline.durationMs)
        services.media.history(projectId).fold(
            onSuccess = { undoCount = it.undoLabels.size; redoCount = it.redoCount },
            onFailure = {},
        )
    }
    LaunchedEffect(projectId) { refresh() }
    LaunchedEffect(playheadMs) { delay(500); prefs.edit().putLong("head_$projectId", playheadMs).apply() }
    BackHandler(enabled = sheet != null || deleteConfirm) {
        if (deleteConfirm) deleteConfirm = false else sheet = null
    }

    fun execute(op: suspend () -> Outcome<*>) {
        if (busy) return
        scope.launch {
            busy = true; message = null
            try {
                when (val result = op()) {
                    is Outcome.Failure -> message = result.error.message
                    is Outcome.Success -> {
                        val tool = result.value as? ToolResult
                        if (tool != null && !tool.ok) message = tool.error
                        else if (tool != null && tool.output.isNotBlank()) message = tool.output
                    }
                }
                refresh()
            } catch (e: Exception) { message = e.message ?: "แก้ไขไม่ได้" }
            finally { busy = false }
        }
    }
    fun clipAction(op: suspend (Clip) -> Outcome<*>) {
        val selected = timeline?.findClip(selectedId ?: "")?.second
        if (selected == null) { message = "เลือกคลิปบนไทม์ไลน์ก่อน"; return }
        execute { op(selected) }
    }
    fun gateway(action: String, args: Map<String, String> = emptyMap()) {
        clipAction { clip -> services.gateway.call(
            ToolCall(Ids.newId("video-ui"), "media", action,
                mapOf("projectId" to projectId, "clipId" to clip.id) + args, actor = "HUMAN"),
        ) }
    }

    fun addFromUris(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        scope.launch {
            busy = true; message = null
            val originalCanvas = timeline?.canvas.orEmpty()
            val originalBackground = timeline?.background
            var added = 0
            try {
                for (uri in uris) {
                    val temp = withContext(Dispatchers.IO) { copyUriIntoWorkspace(context, services.workspaceDir, "video-import", uri) }
                    if (temp == null) { message = "เปิดไฟล์ที่เลือกไม่ได้"; continue }
                    try {
                        val asset = services.media.importAsset(projectId, temp, "HUMAN")
                            .fold({ it }, { message = it.message; null }) ?: continue
                        val duration = if (asset.kind == MediaKind.IMAGE) 3000L
                        else asset.facts["durationMs"]?.toLongOrNull()?.takeIf { it > 0 }
                        if (duration == null) { message = "ไม่พบความยาวของ ${asset.originalName}"; continue }
                        val end = services.media.getTimeline(projectId).fold({ it.durationMs }, { 0L })
                        services.media.addClip(projectId, asset.id, 0, duration, end, actor = "HUMAN").fold(
                            onSuccess = { added++ }, onFailure = { message = it.message },
                        )
                    } finally { withContext(Dispatchers.IO) { File(temp).delete() } }
                }
                // addClip currently reconstructs Timeline without canvas/background; restore both.
                if (added > 0) {
                    if (originalCanvas.isNotEmpty()) services.media.setCanvas(projectId, originalCanvas, "HUMAN")
                    if (originalBackground != null) services.media.setBackground(projectId, originalBackground, "HUMAN")
                    if (message == null) message = "เพิ่ม $added คลิปแล้ว"
                }
                refresh()
            } catch (e: Exception) { message = e.message ?: "นำเข้าไม่ได้" }
            finally { busy = false }
        }
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addFromUris(it) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addFromUris(it) }
    val current = timeline
    val selected = current?.findClip(selectedId ?: "")?.second
    val ordered = current?.orderedClips().orEmpty()
    val clipIndex = ordered.indexOfFirst { it.second.id == selectedId }
    val duration = current?.durationMs ?: 0L
    val active = current?.tracks?.filter { it.kind == MediaKind.VIDEO || it.kind == MediaKind.IMAGE }
        ?.flatMap { track -> track.clips.map { track to it } }
        ?.filter { (_, clip) -> playheadMs >= clip.atMs && playheadMs < clip.atMs + clip.outputDurationMs() }
        ?.maxByOrNull { it.second.atMs }
    val activeClip = active?.second
    val activePath = activeClip?.let { paths[it.assetId] }
    val activeKind = active?.first?.kind?.name ?: "VIDEO"

    fun split() {
        clipAction { clip ->
            if (playheadMs <= clip.atMs || playheadMs >= clip.atMs + clip.outputDurationMs()) {
                Outcome.Failure(com.aicodemax.core.common.AppError("EDIT_PLAYHEAD", "เลื่อนเส้นเล่นให้อยู่ภายในคลิปก่อนแยก"))
            } else services.media.splitClip(projectId, clip.id, playheadMs, "HUMAN")
        }
    }
    fun freeze() {
        clipAction { clip ->
            if (playheadMs < clip.atMs || playheadMs >= clip.atMs + clip.outputDurationMs()) {
                Outcome.Failure(com.aicodemax.core.common.AppError("EDIT_PLAYHEAD", "เลื่อนเส้นเล่นเข้าไปในคลิปก่อนฟรีซ"))
            } else services.media.freezeFrame(projectId, clip.id, playheadMs, 2000, "HUMAN")
        }
    }
    fun openTool(tool: String) {
        when (tool) {
            "SPLIT" -> split()
            "DUP" -> clipAction { services.media.duplicateClip(projectId, it.id, null, "HUMAN") }
            "DEL" -> if (selected != null) deleteConfirm = true
            "FREEZE" -> freeze()
            else -> sheet = VideoSheet.valueOf(tool)
        }
    }
    val tools = if (selected == null) listOf(
        Triple("MEDIA", "＋", "สื่อ"), Triple("AUDIO", "♫", "เสียง"),
        Triple("TEXT", "T", "ข้อความ"), Triple("COLOR", "◐", "สี"),
        Triple("FX", "✦", "FX"), Triple("AI", "✧", "AI"), Triple("MORE", "•••", "เพิ่มเติม"),
    ) else listOf(
        Triple("SPLIT", "✂", "แยก"), Triple("DUP", "▣", "ทำซ้ำ"),
        Triple("DEL", "⌫", "ลบ"), Triple("FREEZE", "❄", "ฟรีซ"),
        Triple("TRIM", "◫", "ทริม"), Triple("TRANSFORM", "◇", "ปรับแต่ง"),
        Triple("SPEED", "⏱", "ความเร็ว"), Triple("KEYFRAME", "◆", "คีย์เฟรม"),
        Triple("TRANSITION", "⫷", "ทรานซิชัน"), Triple("AUDIO", "♫", "เสียง"),
        Triple("COLOR", "◐", "สี"), Triple("FX", "✦", "FX"),
        Triple("MASK", "◉", "มาสก์"), Triple("MOTION", "↗", "โมชั่น"),
        Triple("TEXT", "T", "ข้อความ"), Triple("AI", "✧", "AI"),
        Triple("MORE", "•••", "เพิ่มเติม"),
    )

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoIconButton("‹", "กลับโปรเจกต์", {
                playing = false
                prefs.edit().putLong("head_$projectId", playheadMs).apply()
                onBack()
            })
            Column(Modifier.weight(1f)) {
                Text(project?.name ?: "กำลังเปิดโปรเจกต์…", color = VideoInk.text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (busy) "กำลังบันทึก…" else "บันทึกอัตโนมัติ ✓", color = VideoInk.muted, style = MaterialTheme.typography.labelSmall)
            }
            VideoButton("ส่งออก", onClick = {
                playing = false
                prefs.edit().putLong("head_$projectId", playheadMs).apply()
                onExport()
            }, prominent = true, enabled = current != null && ordered.isNotEmpty() && !busy, modifier = Modifier.padding(end = 7.dp))
        }
        Box(Modifier.weight(1.2f).fillMaxWidth().background(VideoInk.background)) {
            VideoPlaybackPreview(
                source = activePath,
                kind = activeKind,
                clip = activeClip,
                aspect = current?.canvas.orEmpty().ifBlank { "16:9" },
                timeMs = playheadMs,
                durationMs = duration,
                playing = playing && !fullscreen,
                texts = current?.texts.orEmpty(),
                onToggle = { playing = !playing },
                onPosition = { playheadMs = it.coerceIn(0, duration) },
                onStop = { playing = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoIconButton(if (playing) "Ⅱ" else "▶", if (playing) "หยุดชั่วคราว" else "เล่นคลิปต้นฉบับ", { playing = !playing }, enabled = ordered.isNotEmpty())
            Text("${videoTime(playheadMs)}  /  ${videoTime(duration)}", color = VideoInk.text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            VideoIconButton("⛶", "แสดงตัวอย่างเต็มจอแนวนอน", { sheet = null; playing = false; fullscreenPlaying = false; fullscreen = true }, tint = VideoInk.muted)
        }
        Row(Modifier.fillMaxWidth().height(42.dp).padding(start = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoIconButton("↶", "เลิกทำ $undoCount ขั้น", { execute { services.media.undo(projectId, "HUMAN") } }, enabled = !busy && undoCount > 0)
            VideoIconButton("↷", "ทำซ้ำ $redoCount ขั้น", { execute { services.media.redo(projectId, "HUMAN") } }, enabled = !busy && redoCount > 0)
            Text(
                if (selected != null) "คลิป ${clipIndex + 1} · ${videoTime(selected.atMs)}–${videoTime(selected.atMs + selected.outputDurationMs())}"
                else "ลากเพื่อเลื่อน · บีบเพื่อซูม · กดค้างคลิปเพื่อย้าย",
                color = if (selected == null) VideoInk.muted else VideoInk.yellow,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            VideoIconButton("−", "ย่อไทม์ไลน์", { zoom = (zoom / 1.4f).coerceAtLeast(20f) }, tint = VideoInk.muted)
            VideoIconButton("+", "ขยายไทม์ไลน์", { zoom = (zoom * 1.4f).coerceAtMost(160f) }, tint = VideoInk.muted)
        }
        Box(Modifier.weight(1f).fillMaxWidth().background(VideoInk.surface)) {
            VideoTimelinePanel(
                timeline = current,
                assets = assets,
                paths = paths,
                playheadMs = playheadMs,
                selectedId = selectedId,
                zoom = zoom,
                playing = playing,
                onSeek = { playheadMs = it.coerceIn(0, duration) },
                onSelect = { selectedId = if (selectedId == it) null else it; sheet = null },
                onZoom = { zoom = it.coerceIn(20f, 160f) },
                onAdd = { mediaPicker.launch(arrayOf("video/*", "image/*")) },
                onMove = { clip, at -> execute { services.media.moveClip(projectId, clip.id, at, null, "HUMAN") } },
                onTrim = { clip, start, end, at -> execute { services.media.trimClip(projectId, clip.id, start, end, at, "HUMAN") } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (message != null) {
            Text(
                message!!,
                color = if (message!!.contains("ไม่") || message!!.contains("ผิด")) VideoInk.danger else VideoInk.green,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().background(VideoInk.raised).semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 14.dp, vertical = 6.dp),
                maxLines = 2,
            )
        }
        Row(
            Modifier.fillMaxWidth().height(85.dp).background(VideoInk.background).horizontalScroll(rememberScrollState()).padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tools.forEach { (tool, glyph, label) ->
                Column(
                    modifier = Modifier.width(69.dp).height(73.dp)
                        .clickable(enabled = !busy, onClickLabel = label) { openTool(tool) }
                        .padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(glyph, color = if (tool == sheet?.name) VideoInk.green else VideoInk.text, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = VideoInk.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
    if (fullscreen) {
        Dialog(onDismissRequest = { fullscreenPlaying = false; fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Column(Modifier.fillMaxSize().background(VideoInk.background)) {
                Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    VideoIconButton("×", "ปิดตัวอย่างเต็มจอ", { fullscreenPlaying = false; fullscreen = false })
                    Text("ตัวอย่างคลิปต้นฉบับ", color = VideoInk.text)
                }
                VideoPlaybackPreview(
                    source = activePath, kind = activeKind, clip = activeClip,
                    aspect = current?.canvas.orEmpty().ifBlank { "16:9" },
                    timeMs = playheadMs, durationMs = duration, playing = fullscreenPlaying,
                    texts = current?.texts.orEmpty(),
                    onToggle = { fullscreenPlaying = !fullscreenPlaying },
                    onPosition = { playheadMs = it.coerceIn(0, duration) },
                    onStop = { fullscreenPlaying = false },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    VideoIconButton(if (fullscreenPlaying) "Ⅱ" else "▶", "เล่นหรือหยุด", { fullscreenPlaying = !fullscreenPlaying })
                    Slider(
                        value = if (duration > 0) playheadMs.toFloat() / duration else 0f,
                        onValueChange = { playheadMs = (it * duration).toLong() },
                        modifier = Modifier.weight(1f),
                    )
                    Text(videoTime(playheadMs), color = VideoInk.text)
                }
            }
        }
    }
    if (sheet != null) {
        val openSheet = sheet!!
        VideoToolSheet(
            title = openSheet.title,
            onClose = { sheet = null },
        ) {
            VideoSheetControls(
                sheet = openSheet,
                services = services,
                projectId = projectId,
                clip = selected,
                timeline = current,
                timeMs = playheadMs,
                busy = busy,
                onRun = ::execute,
                onGateway = ::gateway,
                onImportVideo = { mediaPicker.launch(arrayOf("video/*", "image/*")) },
                onImportAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onAdvanced = { sheet = null; onAdvanced() },
                onOpenRoute = { sheet = null; onOpen(it) },
                onChat = { sheet = null; onHandToChat(it) },
                onClose = { sheet = null },
            )
        }
    }
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("ลบคลิปที่เลือก?") },
            text = { Text("ลบคลิปจากโปรเจกต์โดยไม่ลบไฟล์ต้นฉบับ เลิกทำได้") },
            confirmButton = { TextButton(onClick = {
                deleteConfirm = false
                clipAction { services.media.deleteClip(projectId, it.id, "HUMAN") }
                sheet = null
            }) { Text("ลบคลิป", color = VideoInk.danger) } },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("ยกเลิก") } },
        )
    }
}
