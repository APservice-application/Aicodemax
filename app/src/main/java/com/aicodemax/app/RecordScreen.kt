package com.aicodemax.app

import android.app.Activity
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Project
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.ui.designsystem.LocalSpacing
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CP-94: in-app audio recording + teleprompter + system-camera video capture.
 * Screen capture is honestly deferred (use the OS recorder, then import below).
 */
@Composable
fun RecordScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var projectIndex by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordDst by remember { mutableStateOf<String?>(null) }
    var script by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf("40") }
    var prompting by remember { mutableStateOf(false) }
    val promptScroll = rememberScrollState()

    val project = projects.getOrNull(projectIndex)

    LaunchedEffect(Unit) {
        scope.launch {
            services.media.listProjects().fold(
                onSuccess = { projects = it },
                onFailure = { message = it.message },
            )
        }
    }

    // Teleprompter auto-scroll.
    LaunchedEffect(prompting) {
        while (prompting) {
            delay(100)
            val step = (speed.toIntOrNull() ?: 40).coerceIn(5, 400) / 10
            if (promptScroll.value + step >= promptScroll.maxValue) {
                prompting = false
            } else {
                promptScroll.scrollTo(promptScroll.value + step)
            }
        }
    }

    fun importFile(file: File) {
        val pid = project?.id
        if (pid == null) {
            message = "ยังไม่มีโปรเจกต์ — สร้างในแชทก่อน"
            return
        }
        scope.launch {
            services.media.importAsset(pid, file.absolutePath, "HUMAN").fold(
                onSuccess = { message = "นำเข้าแล้ว: ${it.originalName}" },
                onFailure = { message = it.message },
            )
        }
    }

    val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri == null) {
                message = "ไม่ได้ไฟล์วิดีโอ"
            } else {
                scope.launch {
                    try {
                        val dst = File(services.workspaceDir, "capture-${System.currentTimeMillis()}.mp4")
                        context.contentResolver.openInputStream(uri)?.use { ins ->
                            dst.outputStream().use { out -> ins.copyTo(out) }
                        }
                        importFile(dst)
                    } catch (e: Exception) {
                        message = "นำเข้าวิดีโอไม่ได้: ${e.message}"
                    }
                }
            }
        }
    }

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
                Text("อัดเสียง", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            val dst = File(services.workspaceDir, "recordings/rec-${System.currentTimeMillis()}.m4a").absolutePath
                            services.gateway.call(
                                ToolCall(Ids.newId("ui"), "audio", "recordStart", mapOf("dst" to dst), actor = "HUMAN"),
                            ).fold(
                                onSuccess = {
                                    message = if (it.ok) it.output else it.error
                                    if (it.ok) {
                                        recording = true
                                        recordDst = dst
                                    }
                                },
                                onFailure = { message = it.message },
                            )
                            busy = false
                        }
                    }, enabled = !busy && !recording) { Text("● เริ่มอัด") }
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            services.gateway.call(
                                ToolCall(Ids.newId("ui"), "audio", "recordStop", emptyMap(), actor = "HUMAN"),
                            ).fold(
                                onSuccess = {
                                    message = if (it.ok) it.output else it.error
                                    if (it.ok) {
                                        recording = false
                                        recordDst?.let { importFile(File(it)) }
                                        recordDst = null
                                    }
                                },
                                onFailure = { message = it.message },
                            )
                            busy = false
                        }
                    }, enabled = !busy && recording) { Text("■ หยุดอัด") }
                }
            }
            item {
                Text("เทเลพรอมป์เตอร์ (อ่านบท + อัดเสียง)", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = script, onValueChange = { script = it }, label = { Text("บทพูด") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        TextField(value = speed, onValueChange = { speed = it }, label = { Text("ความเร็ว") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.3f))
                        OutlinedButton(onClick = { prompting = !prompting }, enabled = script.isNotBlank()) {
                            Text(if (prompting) "หยุดเลื่อน" else "เริ่มเลื่อน")
                        }
                    }
                    Text(
                        script.ifBlank { "—" },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().height(120.dp).verticalScroll(promptScroll),
                    )
                }
            }
            item {
                Text("ถ่ายวิดีโอ (กล้องระบบ)", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = {
                    val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                    if (intent.resolveActivity(context.packageManager) != null) {
                        captureLauncher.launch(intent)
                    } else {
                        message = "เครื่องนี้ไม่มีแอปกล้อง"
                    }
                }) { Text("เปิดกล้องถ่ายวิดีโอ") }
            }
            item {
                Text("อัดหน้าจอ", style = MaterialTheme.typography.titleSmall)
                Text(
                    "อัดหน้าจอในแอปยังไม่รองรับ — ใชตัวอัดหน้าจอของระบบ แล้วนำเข้าไฟล์จากแชท (นำเข้าไฟล์) ได้เลย",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
