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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import com.aicodemax.ui.designsystem.LabeledField
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.OutputBlock
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RecTab { AUDIO, PROMPTER, CAMERA, PODCAST, SCREEN }

/**
 * CP-137 recorder (SCR-REC-001..005): tabbed audio record / teleprompter /
 * camera capture / podcast / honest screen-capture note. Recordings import
 * into the active media project.
 */
@Composable
fun RecordScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(RecTab.AUDIO) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var projectIndex by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordDst by remember { mutableStateOf<String?>(null) }
    var script by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf("40") }
    var prompting by remember { mutableStateOf(false) }
    var podVoice by remember { mutableStateOf("") }
    var podBed by remember { mutableStateOf("") }
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

    Column(modifier = Modifier.fillMaxSize()) {
        ProjectPickerRow(projects, projectIndex, { projectIndex = it })
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            for (value in RecTab.values()) {
                Tab(
                    selected = tab == value,
                    onClick = { tab = value },
                    text = {
                        Text(
                            when (value) {
                                RecTab.AUDIO -> "อัดเสียง"
                                RecTab.PROMPTER -> "พรอมป์เตอร์"
                                RecTab.CAMERA -> "กล้อง"
                                RecTab.PODCAST -> "พอดแคสต์"
                                RecTab.SCREEN -> "อัดจอ"
                            },
                        )
                    },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            when (tab) {
                RecTab.AUDIO -> {
                    Text("อัดเสียงแล้วนำเข้าโปรเจกต์อัตโนมัติ", style = MaterialTheme.typography.bodySmall)
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
                RecTab.PROMPTER -> {
                    TextField(
                        value = script, onValueChange = { script = it },
                        label = { Text("บทพูด") }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("ความเร็ว", speed, { speed = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { prompting = !prompting }, enabled = script.isNotBlank()) {
                            Text(if (prompting) "หยุดเลื่อน" else "เริ่มเลื่อน")
                        }
                    }
                    Text(
                        script.ifBlank { "—" },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().height(160.dp).verticalScroll(promptScroll),
                    )
                }
                RecTab.CAMERA -> {
                    Text(
                        "ถ่ายวิดีโอด้วยแอปกล้องของระบบ แล้วนำเข้าโปรเจกต์อัตโนมัติ",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
                        if (intent.resolveActivity(context.packageManager) != null) {
                            captureLauncher.launch(intent)
                        } else {
                            message = "เครื่องนี้ไม่มีแอปกล้อง"
                        }
                    }) { Text("เปิดกล้องถ่ายวิดีโอ") }
                }
                RecTab.PODCAST -> {
                    Text("ตัดเงียบ + นอร์มัลไลซ์ + ผสมดนตรี ในขั้นตอนเดียว", style = MaterialTheme.typography.bodySmall)
                    LabeledField("ไฟล์เสียงพูด", podVoice, { podVoice = it })
                    LabeledField("ไฟล์ดนตรี (ไม่บังคับ)", podBed, { podBed = it })
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            val dst = services.workspaceDir.path + "/podcast-" + System.currentTimeMillis() + ".wav"
                            val args = mutableMapOf("voice" to podVoice.trim(), "dst" to dst)
                            if (podBed.isNotBlank()) args["bed"] = podBed.trim()
                            services.gateway.call(ToolCall(Ids.newId("ui"), "audio", "podcast", args, actor = "HUMAN")).fold(
                                onSuccess = {
                                    message = if (it.ok) it.output else it.error
                                    if (it.ok) {
                                        podVoice = ""
                                        podBed = ""
                                    }
                                },
                                onFailure = { message = it.message },
                            )
                            busy = false
                        }
                    }, enabled = !busy && podVoice.isNotBlank()) { Text("ทำพอดแคสต์") }
                }
                RecTab.SCREEN -> {
                    Text(
                        "อัดหน้าจอในแอปยังไม่รองรับ — ใช้ตัวอัดหน้าจอของระบบ แล้วนำเข้าไฟล์จากแชท (แนบไฟล์) หรือวางใน workspace ได้เลย",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            message?.let { OutputBlock(it.take(1500)) }
        }
    }
}
