package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Project
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.ui.designsystem.LabeledField
import com.aicodemax.ui.designsystem.LocalSpacing
import com.aicodemax.ui.designsystem.OutputBlock
import kotlinx.coroutines.launch

private enum class GenTab { MAKE, SCRIPT, PLAN, AUDIO, PHOTO }

/**
 * CP-137 media generator (SCR-GEN-001..007): tabbed poster/background/
 * stylize/tts/thumbnail, script→video, AI plans, voice/music/SFX, photo
 * fix-ups — all offline engines, imported as project assets.
 */
@Composable
fun GenScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(GenTab.MAKE) }
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
    var scriptText by remember { mutableStateOf("") }
    var aiMode by remember { mutableStateOf("story") }
    var aiTopic by remember { mutableStateOf("") }
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

    Column(modifier = Modifier.fillMaxSize()) {
        ProjectPickerRow(projects, projectIndex, { projectIndex = it })
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 0.dp) {
            for (value in GenTab.values()) {
                Tab(
                    selected = tab == value,
                    onClick = { tab = value },
                    text = {
                        Text(
                            when (value) {
                                GenTab.MAKE -> "สร้าง"
                                GenTab.SCRIPT -> "บท→วิดีโอ"
                                GenTab.PLAN -> "AI วางแผน"
                                GenTab.AUDIO -> "เสียง"
                                GenTab.PHOTO -> "ภาพ"
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
                GenTab.MAKE -> {
                    Text("ตัวสร้างที่ใช้ได้: ${providers.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf("poster", "background", "stylize", "tts", "thumbnail").forEach { k ->
                            OutlinedButton(onClick = { kind = k }, enabled = !busy) {
                                Text(if (k == kind) "●$k" else k)
                            }
                        }
                    }
                    if (kind == "stylize") {
                        LabeledField("พาธรูปต้นฉบับ", path, { path = it })
                        LabeledField("สไตล์ vivid/warm/cool/cinema/bw", style, { style = it })
                    } else if (kind == "thumbnail") {
                        LabeledField("พาธวิดีโอต้นฉบับ", path, { path = it })
                        TextField(value = prompt, onValueChange = { prompt = it }, label = { Text("ชื่อปก") }, modifier = Modifier.fillMaxWidth())
                        LabeledField("สไตล์ vivid/warm/cool/cinema/bw", style, { style = it })
                    } else if (kind == "background") {
                        LabeledField("สไตล์ dusk/sea/rose/forest/bw/solid:RRGGBB", style, { style = it })
                    } else {
                        TextField(
                            value = prompt, onValueChange = { prompt = it },
                            label = { Text(if (kind == "tts") "ข้อความให้พูด" else "ข้อความโปสเตอร์") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (kind == "poster") {
                            LabeledField("ธีม indigo/warm/sea/rose/forest/bw", style, { style = it })
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
                GenTab.SCRIPT -> {
                    TextField(
                        value = scriptText, onValueChange = { scriptText = it },
                        label = { Text("บท (เว้นบรรทัดว่างคั่นแต่ละช่วง)") },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                    OutlinedButton(onClick = {
                        val pid = project?.id ?: return@OutlinedButton
                        busy = true
                        scope.launch {
                            services.gateway.call(ToolCall(Ids.newId("ui"), "media", "script.video", mapOf("projectId" to pid, "script" to scriptText.trim()), actor = "HUMAN")).fold(
                                onSuccess = {
                                    message = if (it.ok) it.output else it.error
                                    if (it.ok) scriptText = ""
                                },
                                onFailure = { message = it.message },
                            )
                            busy = false
                        }
                    }, enabled = !busy && project != null && scriptText.isNotBlank()) { Text("สร้างวิดีโอจากบท") }
                }
                GenTab.PLAN -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf("commercial", "story", "vlog", "tutorial", "review").forEach { m ->
                            OutlinedButton(onClick = { aiMode = m }, enabled = !busy) {
                                Text(if (m == aiMode) "●$m" else m)
                            }
                        }
                    }
                    LabeledField("หัวข้อ", aiTopic, { aiTopic = it })
                    OutlinedButton(onClick = {
                        busy = true
                        scope.launch {
                            services.gateway.call(ToolCall(Ids.newId("ui"), "media", "text.aiplan", mapOf("mode" to aiMode, "topic" to aiTopic.trim()), actor = "HUMAN")).fold(
                                onSuccess = { message = if (it.ok) it.output else it.error },
                                onFailure = { message = it.message },
                            )
                            busy = false
                        }
                    }, enabled = !busy) { Text("วางแผน") }
                }
                GenTab.AUDIO -> {
                    LabeledField("ไฟล์เสียงต้นฉบับ (เปลี่ยนเสียง)", fxPath, { fxPath = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("semi -12..12", fxSemi, { fxSemi = it }, modifier = Modifier.weight(1f))
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
                        LabeledField("สไตล์เพลง", synthStyle, { synthStyle = it }, modifier = Modifier.weight(1f))
                        LabeledField("วินาที", synthSecs, { synthSecs = it }, modifier = Modifier.weight(1f))
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
                        LabeledField("kind impact/riser/whoosh/click/success", sfxKind, { sfxKind = it }, modifier = Modifier.weight(1f))
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
                GenTab.PHOTO -> {
                    LabeledField("พาธรูปต้นฉบับ", photoPath, { photoPath = it })
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
                        LabeledField("ขยาย x", photoScale, { photoScale = it }, modifier = Modifier.weight(1f))
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
                    Text(
                        "อยากแต่งแบบละเอียด เปิดแท็บ แต่งรูป ใน launcher",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            message?.let { OutputBlock(it.take(2000)) }
        }
    }
}
