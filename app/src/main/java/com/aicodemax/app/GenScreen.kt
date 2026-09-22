package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Project
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

/** Generative media (§46): offline poster/background/stylize/tts → imported as assets. */
@Composable
fun GenScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var projectIndex by remember { mutableStateOf(0) }
    var kind by remember { mutableStateOf("poster") }
    var prompt by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fxPath by remember { mutableStateOf("") }
    var fxSemi by remember { mutableStateOf("5") }
    var fxRobot by remember { mutableStateOf(false) }
    var synthStyle by remember { mutableStateOf("calm") }
    var synthSecs by remember { mutableStateOf("10") }
    var sfxKind by remember { mutableStateOf("impact") }
    var lastDst by remember { mutableStateOf<String?>(null) }
    var photoPath by remember { mutableStateOf("") }
    var photoScale by remember { mutableStateOf("2") }

    val project = projects.getOrNull(projectIndex)

    LaunchedEffect(Unit) {
        scope.launch {
            services.media.listProjects().fold(
                onSuccess = { projects = it },
                onFailure = { message = it.message },
            )
            services.gateway.call(
                ToolCall(Ids.newId("ui"), "media", "gen.list", emptyMap(), actor = "HUMAN"),
            ).fold(
                onSuccess = { providers = if (it.ok) it.output else it.error },
                onFailure = { message = it.message },
            )
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
                Text("ตัวสร้างที่ใช้ได้", style = MaterialTheme.typography.titleSmall)
                Text(providers.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
            }
            item {
                Text("สร้างมีเดียใหม่", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf("poster", "background", "stylize", "tts").forEach { k ->
                            OutlinedButton(onClick = { kind = k }, enabled = !busy) {
                                Text(if (k == kind) "●$k" else k)
                            }
                        }
                    }
                    if (kind == "stylize") {
                        TextField(value = path, onValueChange = { path = it }, label = { Text("พาธรูปต้นฉบับ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextField(value = style, onValueChange = { style = it }, label = { Text("สไตล์ vivid/warm/cool/cinema/bw") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else if (kind == "background") {
                        TextField(value = style, onValueChange = { style = it }, label = { Text("สไตล์ dusk/sea/rose/forest/bw/solid:RRGGBB") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    } else {
                        TextField(value = prompt, onValueChange = { prompt = it }, label = { Text(if (kind == "tts") "ข้อความให้พูด" else "ข้อความโปสเตอร์") }, modifier = Modifier.fillMaxWidth())
                        if (kind == "poster") {
                            TextField(value = style, onValueChange = { style = it }, label = { Text("ธีม indigo/warm/sea/rose/forest/bw") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    OutlinedButton(onClick = {
                        val pid = project?.id ?: return@OutlinedButton
                        busy = true
                        scope.launch {
                            val args = mutableMapOf("projectId" to pid, "kind" to kind)
                            if (prompt.isNotBlank()) args["prompt"] = prompt
                            if (path.isNotBlank()) args["path"] = path.trim()
                            if (style.isNotBlank()) args["style"] = style.trim()
                            services.gateway.call(
                                ToolCall(Ids.newId("ui"), "media", "gen.make", args, actor = "HUMAN"),
                            ).fold(
                                onSuccess = {
                                    message = if (it.ok) it.output else it.error
                                    if (it.ok) {
                                        prompt = ""
                                    }
                                },
                                onFailure = { message = it.message },
                            )
                            busy = false
                        }
                    }, enabled = !busy && project != null) { Text(if (busy) "กำลังสร้าง…" else "สร้าง") }
                }
            }
            item {
                Text("เสียง: เปลี่ยนเสียง / ดนตรี / SFX", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = fxPath, onValueChange = { fxPath = it }, label = { Text("ไฟล์เสียงต้นฉบับ (เปลี่ยนเสียง)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        TextField(value = fxSemi, onValueChange = { fxSemi = it }, label = { Text("semi -12..12") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.4f))
                        OutlinedButton(onClick = { fxRobot = !fxRobot }) { Text(if (fxRobot) "●หุ่นยนต์" else "หุ่นยนต์") }
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                val dst = services.workspaceDir.path + "/gen-audio/voicefx-${System.currentTimeMillis()}.wav"
                                val args = mutableMapOf("src" to fxPath.trim(), "dst" to dst, "semitones" to fxSemi.trim())
                                if (fxRobot) args["robot"] = "true"
                                services.gateway.call(ToolCall(Ids.newId("ui"), "audio", "voicefx", args, actor = "HUMAN")).fold(
                                    onSuccess = {
                                        message = if (it.ok) it.output else it.error
                                        if (it.ok) lastDst = dst
                                    },
                                    onFailure = { message = it.message },
                                )
                                busy = false
                            }
                        }, enabled = !busy && fxPath.isNotBlank()) { Text("เปลี่ยนเสียง") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        TextField(value = synthStyle, onValueChange = { synthStyle = it }, label = { Text("สไตล์เพลง") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.35f))
                        TextField(value = synthSecs, onValueChange = { synthSecs = it }, label = { Text("วินาที") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.25f))
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                val dst = services.workspaceDir.path + "/gen-audio/bed-${System.currentTimeMillis()}.wav"
                                services.gateway.call(ToolCall(Ids.newId("ui"), "audio", "synthmusic", mapOf("style" to synthStyle.trim(), "seconds" to synthSecs.trim(), "dst" to dst), actor = "HUMAN")).fold(
                                    onSuccess = {
                                        message = if (it.ok) it.output else it.error
                                        if (it.ok) lastDst = dst
                                    },
                                    onFailure = { message = it.message },
                                )
                                busy = false
                            }
                        }, enabled = !busy) { Text("ทำเพลง") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        TextField(value = sfxKind, onValueChange = { sfxKind = it }, label = { Text("kind impact/riser/whoosh/click/success") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.55f))
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                val dst = services.workspaceDir.path + "/gen-audio/sfx-${System.currentTimeMillis()}.wav"
                                services.gateway.call(ToolCall(Ids.newId("ui"), "audio", "synthsfx", mapOf("kind" to sfxKind.trim(), "dst" to dst), actor = "HUMAN")).fold(
                                    onSuccess = {
                                        message = if (it.ok) it.output else it.error
                                        if (it.ok) lastDst = dst
                                    },
                                    onFailure = { message = it.message },
                                )
                                busy = false
                            }
                        }, enabled = !busy) { Text("ทำ SFX") }
                    }
                    lastDst?.let { dst ->
                        OutlinedButton(onClick = {
                            val pid = project?.id ?: return@OutlinedButton
                            scope.launch {
                                services.media.importAsset(pid, dst, "HUMAN").fold(
                                    onSuccess = { message = "นำเข้าแล้ว: ${it.id} (${it.originalName})" },
                                    onFailure = { message = it.message },
                                )
                            }
                        }, enabled = !busy) { Text("นำเข้าโปรเจกต์") }
                    }
                }
            }
            item {
                Text("ภาพถ่าย: แต่ง / ขยาย / ฟื้นฟู", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    TextField(value = photoPath, onValueChange = { photoPath = it }, label = { Text("พาธรูปต้นฉบับ") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                val dst = services.workspaceDir.path + "/gen-photo/adj-" + System.currentTimeMillis() + ".png"
                                services.gateway.call(ToolCall(Ids.newId("ui"), "image", "adjust", mapOf("src" to photoPath.trim(), "dst" to dst, "brightness" to "10", "contrast" to "10", "saturation" to "10", "sharpness" to "20"), actor = "HUMAN")).fold(
                                    onSuccess = { message = if (it.ok) it.output else it.error },
                                    onFailure = { message = it.message },
                                )
                                busy = false
                            }
                        }, enabled = !busy && photoPath.isNotBlank()) { Text("แต่งภาพ") }
                        TextField(value = photoScale, onValueChange = { photoScale = it }, label = { Text("x") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.2f))
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                val dst = services.workspaceDir.path + "/gen-photo/big-" + System.currentTimeMillis() + ".png"
                                services.gateway.call(ToolCall(Ids.newId("ui"), "image", "upscale", mapOf("src" to photoPath.trim(), "dst" to dst, "scale" to photoScale.trim()), actor = "HUMAN")).fold(
                                    onSuccess = { message = if (it.ok) it.output else it.error },
                                    onFailure = { message = it.message },
                                )
                                busy = false
                            }
                        }, enabled = !busy && photoPath.isNotBlank()) { Text("ขยายภาพ") }
                        OutlinedButton(onClick = {
                            busy = true
                            scope.launch {
                                val dst = services.workspaceDir.path + "/gen-photo/fixed-" + System.currentTimeMillis() + ".png"
                                services.gateway.call(ToolCall(Ids.newId("ui"), "image", "restore", mapOf("src" to photoPath.trim(), "dst" to dst), actor = "HUMAN")).fold(
                                    onSuccess = { message = if (it.ok) it.output else it.error },
                                    onFailure = { message = it.message },
                                )
                                busy = false
                            }
                        }, enabled = !busy && photoPath.isNotBlank()) { Text("ฟื้นฟู") }
                    }
                }
            }
        }
    }
}
