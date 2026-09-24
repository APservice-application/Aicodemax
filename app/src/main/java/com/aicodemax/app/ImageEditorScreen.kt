package com.aicodemax.app

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Project
import com.aicodemax.ui.designsystem.LabeledField
import com.aicodemax.ui.designsystem.LocalSpacing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CP-136 image workspace (SCR-IMAGE-001..006): pick → preview → crop/adjust/
 * resize/rotate/grayscale/restore/upscale/scopes via image.* tools, chainable,
 * importable to a media project. No layers yet (honest: single-image edits).
 */
@Composable
fun ImageEditorScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var src by remember { mutableStateOf("") }
    var lastDst by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var projectIndex by remember { mutableStateOf(0) }
    // Tool params.
    var cropX by remember { mutableStateOf("0") }
    var cropY by remember { mutableStateOf("0") }
    var cropW by remember { mutableStateOf("100") }
    var cropH by remember { mutableStateOf("100") }
    var brightness by remember { mutableStateOf(0f) }
    var contrast by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(0f) }
    var sharpness by remember { mutableStateOf(0f) }
    var maxDim by remember { mutableStateOf("1024") }
    var scale by remember { mutableStateOf("2") }
    var denoise by remember { mutableStateOf(true) }
    var deFade by remember { mutableStateOf(true) }
    var whiteBalance by remember { mutableStateOf(true) }

    fun dstFor(tag: String): String {
        val base = src.substringAfterLast('/').substringBeforeLast('.').ifBlank { "image" }
        val ext = src.substringAfterLast('.', "png").take(4)
        return File(File(services.workspaceDir, "images").apply { mkdirs() }, "$base-$tag-${System.currentTimeMillis()}.$ext").absolutePath
    }

    fun runImage(action: String, args: Map<String, String>, tag: String? = null) {
        if (src.isBlank()) {
            message = "เลือกรูปก่อนครับ"
            return
        }
        val dst = tag?.let { dstFor(it) }
        val full = args.toMutableMap()
        full["src"] = src
        if (dst != null) full["dst"] = dst
        scope.runTool(
            services, "image", action, full,
            onBusy = { busy = it },
            onMessage = {
                message = it
                if (dst != null && File(dst).exists()) lastDst = dst
            },
        )
    }

    fun loadInfo(path: String) {
        scope.runTool(
            services, "image", "info", mapOf("path" to path),
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
            val copied = copyUriIntoWorkspace(context, services.workspaceDir, "images", uri)
            withContext(Dispatchers.Main) {
                if (copied == null) {
                    message = "นำเข้ารูปไม่ได้"
                } else {
                    src = copied
                    lastDst = null
                    loadInfo(copied)
                }
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
                    onClick = { pickLauncher.launch("image/*") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (src.isBlank()) "🖼️ เลือกรูป" else "🖼️ เปลี่ยนรูป") }
                if (src.isNotBlank()) {
                    Text(src, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (info.isNotBlank()) {
                    Text(info, style = MaterialTheme.typography.bodySmall)
                }
                WorkspaceMessage(message)
            }
        }
        if (src.isNotBlank()) {
            item(key = "__preview__") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ต้นฉบับ", style = MaterialTheme.typography.labelSmall)
                        WorkspaceImage(path = src, modifier = Modifier.fillMaxWidth().height(160.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ผลลัพธ์", style = MaterialTheme.typography.labelSmall)
                        val dst = lastDst
                        if (dst == null) {
                            Text("(ยังไม่มี)", style = MaterialTheme.typography.bodySmall)
                        } else {
                            WorkspaceImage(path = dst, modifier = Modifier.fillMaxWidth().height(160.dp))
                            OutlinedButton(onClick = {
                                src = dst
                                lastDst = null
                                loadInfo(dst)
                            }) { Text("ใช้เป็นต้นฉบับ") }
                        }
                    }
                }
            }
            item(key = "__crop__") {
                WorkspaceSection("✂️ ครอป") {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        LabeledField("x", cropX, { cropX = it }, modifier = Modifier.weight(1f))
                        LabeledField("y", cropY, { cropY = it }, modifier = Modifier.weight(1f))
                        LabeledField("w", cropW, { cropW = it }, modifier = Modifier.weight(1f))
                        LabeledField("h", cropH, { cropH = it }, modifier = Modifier.weight(1f))
                    }
                    OutlinedButton(
                        onClick = {
                            runImage(
                                "crop",
                                mapOf("x" to cropX.trim(), "y" to cropY.trim(), "w" to cropW.trim(), "h" to cropH.trim()),
                                tag = "crop",
                            )
                        },
                        enabled = !busy,
                    ) { Text("ครอป") }
                }
            }
            item(key = "__adjust__") {
                WorkspaceSection("🎚️ ปรับแสงสี") {
                    AdjustSlider("สว่าง", brightness, { brightness = it })
                    AdjustSlider("คอนทราสต์", contrast, { contrast = it })
                    AdjustSlider("ความสด", saturation, { saturation = it })
                    AdjustSlider("ความคม", sharpness, { sharpness = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedButton(
                            onClick = {
                                runImage(
                                    "adjust",
                                    mapOf(
                                        "brightness" to brightness.toInt().toString(),
                                        "contrast" to contrast.toInt().toString(),
                                        "saturation" to saturation.toInt().toString(),
                                        "sharpness" to sharpness.toInt().toString(),
                                    ),
                                    tag = "adj",
                                )
                            },
                            enabled = !busy,
                        ) { Text("ปรับภาพ") }
                        OutlinedButton(onClick = {
                            brightness = 0f; contrast = 0f; saturation = 0f; sharpness = 0f
                        }) { Text("รีเซ็ต") }
                    }
                }
            }
            item(key = "__size__") {
                WorkspaceSection("📐 ขนาด/หมุน/ขาวดำ") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LabeledField("ด้านยาวสุด px", maxDim, { maxDim = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { runImage("resize", mapOf("maxDim" to maxDim.trim()), tag = "small") },
                            enabled = !busy,
                        ) { Text("ย่อ") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        listOf("90", "180", "270").forEach { deg ->
                            OutlinedButton(
                                onClick = { runImage("rotate", mapOf("degrees" to deg), tag = "rot") },
                                enabled = !busy,
                            ) { Text("หมุน $deg°") }
                        }
                        OutlinedButton(
                            onClick = { runImage("grayscale", emptyMap(), tag = "gray") },
                            enabled = !busy,
                        ) { Text("ขาวดำ") }
                    }
                }
            }
            item(key = "__restore__") {
                WorkspaceSection("✨ ฟื้นฟู/ขยาย/สโคป") {
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedButton(onClick = { denoise = !denoise }) {
                            Text(if (denoise) "●ลดนอยส์" else "ลดนอยส์")
                        }
                        OutlinedButton(onClick = { deFade = !deFade }) {
                            Text(if (deFade) "●แก้ซีด" else "แก้ซีด")
                        }
                        OutlinedButton(onClick = { whiteBalance = !whiteBalance }) {
                            Text(if (whiteBalance) "●ไวต์บาลานซ์" else "ไวต์บาลานซ์")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedButton(
                            onClick = {
                                runImage(
                                    "restore",
                                    mapOf(
                                        "denoise" to denoise.toString(),
                                        "deFade" to deFade.toString(),
                                        "whiteBalance" to whiteBalance.toString(),
                                    ),
                                    tag = "fixed",
                                )
                            },
                            enabled = !busy,
                        ) { Text("ฟื้นฟู") }
                        LabeledField("ขยาย x", scale, { scale = it }, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = { runImage("upscale", mapOf("scale" to scale.trim()), tag = "big") },
                            enabled = !busy,
                        ) { Text("ขยาย") }
                        OutlinedButton(
                            onClick = {
                                scope.runTool(
                                    services, "image", "scopes", mapOf("path" to src),
                                    onBusy = { busy = it },
                                    onMessage = { message = it },
                                )
                            },
                            enabled = !busy,
                        ) { Text("สโคป") }
                    }
                }
            }
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

@Composable
private fun AdjustSlider(label: String, value: Float, onValue: (Float) -> Unit) {
    Column {
        Text("$label: ${value.toInt()}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onValue, valueRange = -100f..100f)
    }
}

/** Downsampled preview; shows a placeholder line when the file can't decode. */
@Composable
private fun WorkspaceImage(path: String, modifier: Modifier = Modifier) {
    val bitmap = remember(path) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        } catch (_: Exception) {
            null
        }
    }
    if (bitmap == null) {
        Text("(แสดงรูปไม่ได้)", style = MaterialTheme.typography.bodySmall)
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}
