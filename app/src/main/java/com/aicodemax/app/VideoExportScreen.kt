package com.aicodemax.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Project
import com.aicodemax.tools.render.RenderJob
import com.aicodemax.tools.render.RenderStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** PDF Screen 8. Uses the real render queue, QC, owner approval and MediaStore export. */
@Composable
internal fun VideoExportScreen(services: ServiceLocator, projectId: String, onBack: () -> Unit, onQueue: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember(projectId) { mutableStateOf<Project?>(null) }
    var job by remember(projectId) { mutableStateOf<RenderJob?>(null) }
    var previewSource by remember(projectId) { mutableStateOf<String?>(null) }
    var previewKind by remember(projectId) { mutableStateOf("VIDEO") }
    var previewAtMs by remember(projectId) { mutableStateOf(0L) }
    var resolution by remember { mutableStateOf("original") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(projectId) {
        services.media.openProject(projectId).fold(onSuccess = { project = it }, onFailure = { message = it.message })
        job = services.render.list().fold(
            onSuccess = { all -> all.filter { it.projectId == projectId }.maxByOrNull { it.createdAt } },
            onFailure = { null },
        )
        val last = project?.timeline?.orderedClips()?.lastOrNull { it.first.kind != MediaKind.AUDIO }
        if (last != null) {
            previewKind = last.first.kind.name
            previewAtMs = (last.second.endMs - 80).coerceAtLeast(last.second.startMs)
            previewSource = services.media.assetPath(projectId, last.second.assetId).fold({ it }, { null })
        }
    }
    LaunchedEffect(job?.id, job?.status) {
        val currentId = job?.id ?: return@LaunchedEffect
        while (job?.status == RenderStatus.RUNNING || job?.status == RenderStatus.QUEUED) {
            delay(1000)
            services.render.status(currentId).fold(onSuccess = { job = it }, onFailure = { message = it.message })
        }
    }
    fun run(op: suspend () -> Outcome<RenderJob>) {
        if (busy) return
        scope.launch {
            busy = true; message = null
            try {
                op().fold(onSuccess = { job = it }, onFailure = { message = it.message })
            } catch (e: Exception) { message = e.message ?: "ดำเนินการไม่ได้" }
            finally { busy = false }
        }
    }
    val duration = project?.timeline?.durationMs ?: 0L
    val bitrate = when (resolution) { "480p" -> 2_000_000L; "720p" -> 4_000_000L; else -> 8_000_000L }
    val approximateMb = ((bitrate + 128_000) * duration / (8 * 1024 * 1024 * 1000)).coerceAtLeast(1)
    val running = job?.status == RenderStatus.RUNNING || job?.status == RenderStatus.QUEUED
    val success = job?.status == RenderStatus.DONE && job?.qc?.passed == true
    val actualPreview = job?.takeIf { it.status == RenderStatus.DONE }?.previews?.lastOrNull()
    Column(Modifier.fillMaxSize().background(VideoInk.background)) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoIconButton("‹", "กลับไปหน้าตัดต่อ", onBack)
            Text("ส่งออกวิดีโอ", modifier = Modifier.weight(1f), color = VideoInk.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            VideoIconButton("☷", "ดูคิวงานเรนเดอร์", onQueue, tint = VideoInk.muted)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Black, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                VideoFrame(actualPreview ?: previewSource, if (actualPreview != null) "IMAGE" else previewKind,
                    if (actualPreview != null) 0 else previewAtMs,
                    modifier = Modifier.fillMaxSize().padding(5.dp).background(VideoInk.raised, RoundedCornerShape(12.dp)))
                Text(if (actualPreview != null) "ผลเรนเดอร์จริง" else "ภาพอ้างอิงจากคลิปสุดท้าย",
                    modifier = Modifier.align(Alignment.BottomStart).background(VideoInk.background.copy(alpha = 0.82f), RoundedCornerShape(8.dp)).padding(8.dp),
                    color = VideoInk.text, style = MaterialTheme.typography.labelSmall)
            }
            Text(project?.name ?: "กำลังโหลด…", color = VideoInk.text, style = MaterialTheme.typography.titleMedium)
            Text("ระยะเวลา ${videoTime(duration)}", color = VideoInk.muted, style = MaterialTheme.typography.bodySmall)
            Text("ความละเอียด", color = VideoInk.text, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("original" to "1080p", "720p" to "720p", "480p" to "480p").forEach { (preset, label) ->
                    VideoChip(label, resolution == preset, { resolution = preset }, enabled = !running && !busy)
                }
            }
            Column(Modifier.fillMaxWidth().background(VideoInk.surface, RoundedCornerShape(12.dp)).padding(13.dp)) {
                Text("เฟรมเรต", color = VideoInk.text, style = MaterialTheme.typography.bodyMedium)
                Text("ตามไฟล์ต้นฉบับ · เอนจินปัจจุบันยังไม่รองรับการเลือก 30/60fps แยกต่างหาก", color = VideoInk.muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("ขนาดไฟล์ประมาณ $approximateMb MB  (ขึ้นอยู่กับภาพและเสียงจริง)", color = VideoInk.muted, style = MaterialTheme.typography.bodySmall)
            if (running) {
                LinearProgressIndicator(progress = (job?.progress ?: 0) / 100f, modifier = Modifier.fillMaxWidth(), color = VideoInk.green)
                Text("กำลังเรนเดอร์ ${job?.progress ?: 0}% · โปรดเปิดแอปไว้ระหว่างทำงาน", color = VideoInk.text)
            }
            if (job?.status == RenderStatus.FAILED) {
                Text("เรนเดอร์ไม่สำเร็จ: ${job?.error}", color = VideoInk.danger)
            }
            if (success) {
                Column(Modifier.fillMaxWidth().background(VideoInk.greenSoft, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓ เรนเดอร์เสร็จ · QC ผ่าน", color = VideoInk.green, fontWeight = FontWeight.Bold)
                    Text("${job?.qc?.checks?.size ?: 0} จุดตรวจสอบ  ·  ${job?.preset?.name}", color = VideoInk.text, style = MaterialTheme.typography.bodySmall)
                    if (job?.approved == true) Text("✓ อนุมัติแล้ว", color = VideoInk.green)
                }
            }
            if (!running) {
                VideoButton("ส่งออกวิดีโอ", {
                    run {
                        val enqueued = services.render.enqueue(projectId, resolution)
                        if (enqueued is Outcome.Success) {
                            val queued = enqueued.value
                            // App-scoped rendering continues if the user opens the queue screen.
                            services.appScope.launch { services.render.run(queued.id) }
                        }
                        enqueued
                    }
                }, modifier = Modifier.fillMaxWidth(), prominent = true, enabled = duration > 0 && !busy)
            }
            if (success && job?.approved != true) {
                VideoButton("ตรวจแล้ว · อนุมัติวิดีโอ", { val id = job?.id ?: return@VideoButton; run { services.render.approve(id) } }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
            if (success && job?.approved == true && job?.exportedUri.isNullOrBlank()) {
                VideoButton("บันทึกลงเครื่อง", { val id = job?.id ?: return@VideoButton; run { services.render.export(id) } }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
            }
            if (success && !job?.exportedUri.isNullOrBlank()) {
                VideoButton("แชร์วิดีโอ", {
                    val uri = Uri.parse(job?.exportedUri)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try { context.startActivity(Intent.createChooser(intent, "แชร์วิดีโอ")) }
                    catch (e: Exception) { message = e.message ?: "แชร์ไฟล์ไม่ได้" }
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                Text("บันทึกแล้วที่ Download/Aicodemax", color = VideoInk.muted, style = MaterialTheme.typography.bodySmall)
            }
            VideoButton("ดูคิวงาน / ตรวจ QC โดยละเอียด", onQueue, modifier = Modifier.fillMaxWidth())
            if (message != null) Text(message!!, color = VideoInk.danger)
            Spacer(Modifier.height(24.dp))
        }
    }
}
