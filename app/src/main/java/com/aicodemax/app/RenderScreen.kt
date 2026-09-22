package com.aicodemax.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Project
import com.aicodemax.tools.render.RenderJob
import com.aicodemax.tools.render.RenderPreset
import com.aicodemax.tools.render.RenderStatus
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** CP-67 Render Center: pick project + preset, queue/run, QC, approve, export. */
@Composable
fun RenderScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var jobs by remember { mutableStateOf<List<RenderJob>>(emptyList()) }
    var projectIndex by remember { mutableStateOf(0) }
    var presetIndex by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun refreshJobs() {
        services.render.list().fold(
            onSuccess = { jobs = it },
            onFailure = { message = it.message },
        )
    }

    LaunchedEffect(Unit) {
        services.media.listProjects().fold(
            onSuccess = { projects = it },
            onFailure = { message = it.message },
        )
        refreshJobs()
    }
    // Live progress while anything runs.
    LaunchedEffect(jobs.any { it.status == RenderStatus.RUNNING || it.status == RenderStatus.QUEUED }) {
        while (jobs.any { it.status == RenderStatus.RUNNING || it.status == RenderStatus.QUEUED }) {
            delay(2000)
            refreshJobs()
        }
    }

    fun runCall(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            message = null
            try {
                block()
            } finally {
                refreshJobs()
                busy = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item {
            Surface(tonalElevation = spacing.xs) {
                Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text("เรนเดอร์โปรเจกต์", style = MaterialTheme.typography.titleMedium)
                    if (projects.isEmpty()) {
                        Text("ยังไม่มีโปรเจกต์ — สร้างในแชทก่อน (เช่น สร้างโปรเจกต์ ทริปทะเล)")
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            TextButton(
                                onClick = { projectIndex = (projectIndex + 1) % projects.size },
                                enabled = !busy,
                            ) {
                                Text("โปรเจกต์: ${projects[projectIndex].name} (${projectIndex + 1}/${projects.size})")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            RenderPreset.PRESETS.forEachIndexed { i, preset ->
                                if (i == presetIndex) {
                                    Button(onClick = {}, enabled = !busy) { Text(preset.name) }
                                } else {
                                    OutlinedButton(onClick = { presetIndex = i }, enabled = !busy) {
                                        Text(preset.name)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Button(
                                onClick = {
                                    runCall {
                                        services.render.runNow(
                                            projects[projectIndex].id,
                                            RenderPreset.PRESETS[presetIndex].name,
                                        ).fold(
                                            onSuccess = { message = "เสร็จ: ${it.outputPath}" },
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("เรนเดอร์ทันที") }
                            OutlinedButton(
                                onClick = {
                                    runCall {
                                        services.render.enqueue(
                                            projects[projectIndex].id,
                                            RenderPreset.PRESETS[presetIndex].name,
                                        ).fold(
                                            onSuccess = { message = "เข้าคิวแล้ว: ${it.id}" },
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("เข้าคิว") }
                        }
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { Text("งานเรนเดอร์ (${jobs.size})", style = MaterialTheme.typography.titleMedium) }
        items(jobs) { job ->
            Surface(tonalElevation = spacing.xs) {
                Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text("${job.id} • ${job.projectId} • ${job.preset.name}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "สถานะ ${job.status} ${job.progress}%" +
                            (job.qc?.let { if (it.passed) " • QCผ่าน" else " • QCไม่ผ่าน" } ?: "") +
                            (if (job.approved) " • อนุมัติแล้ว" else "") +
                            (if (job.exportedUri.isNotEmpty()) " • ส่งออกแล้ว" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (job.status == RenderStatus.RUNNING || job.status == RenderStatus.QUEUED) {
                        LinearProgressIndicator(
                            progress = (job.progress.coerceIn(0, 100)) / 100f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (job.outputPath.isNotEmpty()) {
                        Text(job.outputPath, style = MaterialTheme.typography.bodySmall)
                    }
                    if (job.error.isNotEmpty()) {
                        Text("ผิดพลาด: ${job.error}", style = MaterialTheme.typography.bodySmall)
                    }
                    job.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    job.qc?.checks?.forEach { check ->
                        Text(
                            (if (check.ok) "✓ " else "✕ ") + check.name + " " + check.detail,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (job.exportedUri.isNotEmpty()) {
                        Text(job.exportedUri, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        job.previews.take(3).forEach { path ->
                            PreviewThumb(path, Modifier.weight(1f))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        if (job.status == RenderStatus.QUEUED) {
                            Button(onClick = { runCall { services.render.run(job.id) } }, enabled = !busy) {
                                Text("เริ่ม")
                            }
                        }
                        if (job.status == RenderStatus.FAILED) {
                            Button(onClick = { runCall { services.render.retry(job.id) } }, enabled = !busy) {
                                Text("ลองใหม่")
                            }
                        }
                        if (job.status == RenderStatus.DONE && job.qc?.passed == true && !job.approved) {
                            Button(
                                onClick = {
                                    runCall {
                                        services.render.approve(job.id).fold(
                                            onSuccess = { message = "อนุมัติแล้ว" },
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("อนุมัติ") }
                        }
                        if (job.approved && job.exportedUri.isEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    runCall {
                                        services.render.export(job.id).fold(
                                            onSuccess = { message = "ส่งออกแล้ว: ${it.exportedUri}" },
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("เอ็กซ์พอร์ต") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewThumb(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "พรีวิว",
            modifier = modifier.height(96.dp),
            contentScale = ContentScale.Crop,
        )
    }
}
