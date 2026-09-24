package com.aicodemax.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Project
import com.aicodemax.ui.designsystem.LabeledField
import com.aicodemax.ui.designsystem.LocalSpacing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CP-136 audio workspace (SCR-AUDIO-001..005): pick → info → trim/concat/fade/
 * gain/mix/normalize/autocut/beats/speech/voicefx/synth/record via audio.*
 * tools, chainable, importable to a media project. (Honest: edits land as WAV.)
 */
@Composable
fun AudioEditorScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var src by remember { mutableStateOf("") }
    var lastDst by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var projectIndex by remember { mutableStateOf(0) }
    // Tool params.
    var startMs by remember { mutableStateOf("0") }
    var endMs by remember { mutableStateOf("5000") }
    var joinList by remember { mutableStateOf<List<String>>(emptyList()) }
    var gainDb by remember { mutableStateOf("6") }
    var fadeIn by remember { mutableStateOf("1000") }
    var fadeOut by remember { mutableStateOf("1000") }
    var peakDb by remember { mutableStateOf("-3") }
    var cutThreshold by remember { mutableStateOf("-40") }
    var cutPad by remember { mutableStateOf("150") }
    var mixB by remember { mutableStateOf("") }
    var mixGainB by remember { mutableStateOf("1.0") }
    var mixOffset by remember { mutableStateOf("0") }
    var fxSemi by remember { mutableStateOf("5") }
    var fxRobot by remember { mutableStateOf(false) }
    var synthStyle by remember { mutableStateOf("calm") }
    var synthSecs by remember { mutableStateOf("10") }
    var sfxKind by remember { mutableStateOf("impact") }

    fun dstFor(tag: String): String {
        val base = src.substringAfterLast('/').substringBeforeLast('.').ifBlank { "audio" }
        return File(
            File(services.workspaceDir, "audio").apply { mkdirs() },
            "$base-$tag-${System.currentTimeMillis()}.wav",
        ).absolutePath
    }

    fun runAudio(action: String, args: Map<String, String>, tag: String? = null, explicitSrc: String? = null) {
        val useSrc = explicitSrc ?: src
        if (useSrc.isBlank() && explicitSrc == null) {
            message = "เลือกไฟล์เสียงก่อนครับ"
            return
        }
        val dst = tag?.let { dstFor(it) }
        val full = args.toMutableMap()
        if (useSrc.isNotBlank()) full["src"] = useSrc
        if (dst != null) full["dst"] = dst
        scope.runTool(
            services, "audio", action, full,
            onBusy = { busy = it },
            onMessage = {
                message = it
                if (dst != null && File(dst).exists()) lastDst = dst
            },
        )
    }

    fun loadInfo(path: String) {
        scope.runTool(
            services, "audio", "info", mapOf("path" to path),
            onBusy = { busy = it },
            onMessage = { info = it },
        )
    }

    LaunchedEffect(Unit) {
        scope.launch {
            services.media.listProjects().fold(
                onSuccess = { projects = it },
                onFailure = { message = it.message },
            )
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val copied = copyUriIntoWorkspace(context, services.workspaceDir, "audio", uri)
            withContext(Dispatchers.Main) {
                if (copied == null) {
                    message = "นำเข้าไฟล์เสียงไม่ได้"
                } else {
                    src = copied
                    lastDst = null
                    loadInfo(copied)
                }
            }
        }
    }
    val pickJoinLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val copied = uris.mapNotNull { copyUriIntoWorkspace(context, services.workspaceDir, "audio", it) }
            withContext(Dispatchers.Main) { joinList = joinList + copied }
        }
    }
    val pickMixLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val copied = copyUriIntoWorkspace(context, services.workspaceDir, "audio", uri)
            withContext(Dispatchers.Main) {
                if (copied == null) message = "นำเข้าไฟล์ไม่ได้" else mixB = copied
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "__src__") {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                OutlinedButton(
                    onClick = { pickLauncher.launch("audio/*") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (src.isBlank()) "🎚️ เลือกไฟล์เสียง" else "🎚️ เปลี่ยนไฟล์") }
                if (src.isNotBlank()) {
                    Text(src, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (info.isNotBlank()) {
                    Text(info, style = MaterialTheme.typography.bodySmall)
                }
                val dst = lastDst
                if (dst != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ผลลัพธ์: ${dst.substringAfterLast('/')}", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            src = dst
                            lastDst = null
                            loadInfo(dst)
                        }) { Text("ใช้เป็นต้นฉบับ") }
                    }
                }
                WorkspaceMessage(message)
            }
        }
        if (src.isNotBlank()) {
            item(key = "__cut__") {
                WorkspaceSection("✂️ ตัด/ต่อ") {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("เริ่ม ms", startMs, { startMs = it }, modifier = Modifier.weight(1f))
                        LabeledField("จบ ms", endMs, { endMs = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                runAudio(
                                    "trim",
                                    mapOf("startMs" to startMs.trim(), "endMs" to endMs.trim()),
                                    tag = "cut",
                                )
                            },
                            enabled = !busy,
                        ) { Text("ตัด") }
                    }
                    OutlinedButton(
                        onClick = { pickJoinLauncher.launch("audio/*") },
                        enabled = !busy,
                    ) { Text("＋ เพิ่มไฟล์ต่อท้าย (${joinList.size})") }
                    for (path in joinList) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(onClick = { joinList = joinList - path }) { Text("ออก") }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            runAudio(
                                "concat",
                                mapOf("srcs" to ([src] + joinList).joinToString("|")),
                                tag = "joined",
                            )
                        },
                        enabled = !busy && joinList.isNotEmpty(),
                    ) { Text("ต่อไฟล์") }
                }
            }
            item(key = "__level__") {
                WorkspaceSection("🔊 ระดับเสียง/เฟด/นอร์มัลไลซ์") {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("เกน dB", gainDb, { gainDb = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { runAudio("gain", mapOf("db" to gainDb.trim()), tag = "vol") },
                            enabled = !busy,
                        ) { Text("เร่ง/เบา") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("เฟดเข้า ms", fadeIn, { fadeIn = it }, modifier = Modifier.weight(1f))
                        LabeledField("เฟดออก ms", fadeOut, { fadeOut = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                runAudio(
                                    "fade",
                                    mapOf("inMs" to fadeIn.trim(), "outMs" to fadeOut.trim()),
                                    tag = "fade",
                                )
                            },
                            enabled = !busy,
                        ) { Text("เฟด") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("พีค dB", peakDb, { peakDb = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { runAudio("normalize", mapOf("peakDb" to peakDb.trim()), tag = "norm") },
                            enabled = !busy,
                        ) { Text("นอร์มัลไลซ์") }
                    }
                }
            }
            item(key = "__smart__") {
                WorkspaceSection("🧠 ตัดเงียบ/ผสม/วิเคราะห์") {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("เงียบ dB", cutThreshold, { cutThreshold = it }, modifier = Modifier.weight(1f))
                        LabeledField("เผื่อ ms", cutPad, { cutPad = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                runAudio(
                                    "autocut",
                                    mapOf("thresholdDb" to cutThreshold.trim(), "padMs" to cutPad.trim()),
                                    tag = "autocut",
                                )
                            },
                            enabled = !busy,
                        ) { Text("ตัดเงียบ") }
                    }
                    OutlinedButton(
                        onClick = { pickMixLauncher.launch("audio/*") },
                        enabled = !busy,
                    ) { Text(if (mixB.isBlank()) "เลือกไฟล์ B สำหรับผสม" else "B: ${mixB.substringAfterLast('/')}") }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("เกน B", mixGainB, { mixGainB = it }, modifier = Modifier.weight(1f))
                        LabeledField("เหลื่อม ms", mixOffset, { mixOffset = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                runAudio(
                                    "mix",
                                    mapOf(
                                        "srcA" to src,
                                        "srcB" to mixB.trim(),
                                        "gainB" to mixGainB.trim(),
                                        "offsetMs" to mixOffset.trim(),
                                    ),
                                    tag = "mix",
                                )
                            },
                            enabled = !busy && mixB.isNotBlank(),
                        ) { Text("ผสม") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedButton(
                            onClick = { runAudio("beats", emptyMap()) },
                            enabled = !busy,
                        ) { Text("จับจังหวะ") }
                        OutlinedButton(
                            onClick = { runAudio("speech", emptyMap()) },
                            enabled = !busy,
                        ) { Text("หาช่วงพูด") }
                    }
                }
            }
            item(key = "__fx__") {
                WorkspaceSection("🎭 เปลี่ยนเสียง/ดนตรี/SFX") {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("semi -12..12", fxSemi, { fxSemi = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { fxRobot = !fxRobot }) {
                            Text(if (fxRobot) "●หุ่นยนต์" else "หุ่นยนต์")
                        }
                        OutlinedButton(
                            onClick = {
                                val args = mutableMapOf("semitones" to fxSemi.trim())
                                if (fxRobot) args["robot"] = "true"
                                runAudio("voicefx", args, tag = "fx")
                            },
                            enabled = !busy,
                        ) { Text("เปลี่ยนเสียง") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("สไตล์เพลง", synthStyle, { synthStyle = it }, modifier = Modifier.weight(1f))
                        LabeledField("วินาที", synthSecs, { synthSecs = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                val dst = dstFor("bed")
                                scope.runTool(
                                    services, "audio", "synthmusic",
                                    mapOf("style" to synthStyle.trim(), "seconds" to synthSecs.trim(), "dst" to dst),
                                    onBusy = { busy = it },
                                    onMessage = {
                                        message = it
                                        if (File(dst).exists()) lastDst = dst
                                    },
                                )
                            },
                            enabled = !busy,
                        ) { Text("ทำเพลง") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("kind", sfxKind, { sfxKind = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                val dst = dstFor("sfx")
                                scope.runTool(
                                    services, "audio", "synthsfx",
                                    mapOf("kind" to sfxKind.trim(), "dst" to dst),
                                    onBusy = { busy = it },
                                    onMessage = {
                                        message = it
                                        if (File(dst).exists()) lastDst = dst
                                    },
                                )
                            },
                            enabled = !busy,
                        ) { Text("ทำ SFX") }
                    }
                }
            }
        }
        item(key = "__record__") {
            WorkspaceSection("🎙️ อัดเสียง") {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedButton(
                        onClick = {
                            val dst = File(
                                File(services.workspaceDir, "audio").apply { mkdirs() },
                                "rec-${System.currentTimeMillis()}.m4a",
                            ).absolutePath
                            scope.runTool(
                                services, "audio", "recordStart", mapOf("dst" to dst),
                                onBusy = { busy = it },
                                onMessage = {
                                    message = it
                                    if (it.startsWith("เริ่มอัด")) {
                                        recording = true
                                        lastDst = dst
                                    }
                                },
                            )
                        },
                        enabled = !busy && !recording,
                    ) { Text("● เริ่มอัด") }
                    OutlinedButton(
                        onClick = {
                            scope.runTool(
                                services, "audio", "recordStop", emptyMap(),
                                onBusy = { busy = it },
                                onMessage = {
                                    message = it
                                    recording = false
                                    val dst = lastDst
                                    if (dst != null && File(dst).exists()) {
                                        src = dst
                                        lastDst = null
                                        loadInfo(dst)
                                    }
                                },
                            )
                        },
                        enabled = !busy && recording,
                    ) { Text("■ หยุดอัด") }
                }
            }
        }
        if (src.isNotBlank()) {
            item(key = "__import__") {
                WorkspaceSection("📁 นำเข้าโปรเจกต์") {
                    ProjectPickerRow(projects, projectIndex, { projectIndex = it })
                    OutlinedButton(
                        onClick = {
                            val pid = projects.getOrNull(projectIndex)?.id
                            val target = lastDst ?: src
                            if (pid == null) {
                                message = "ยังไม่มีโปรเจกต์มีเดีย — สร้างในหน้าวิดีโอก่อน"
                                return@OutlinedButton
                            }
                            scope.launch {
                                services.media.importAsset(pid, target, "HUMAN").fold(
                                    onSuccess = { message = "นำเข้าแล้ว: ${it.originalName}" },
                                    onFailure = { message = it.message },
                                )
                            }
                        },
                        enabled = !busy,
                    ) { Text("นำเข้า${if (lastDst != null) "ผลลัพธ์" else "ต้นฉบับ"}") }
                }
            }
        }
    }
}
