package com.aicodemax.app

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.Project
import com.aicodemax.tools.image.ImageLayer
import com.aicodemax.tools.image.LayerStack
import com.aicodemax.ui.designsystem.LabeledField
import com.aicodemax.ui.designsystem.LocalSpacing
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CP-136 image workspace (SCR-IMAGE-001..006) + CP-142 layers (§47):
 * every picked/result image becomes a layer; tools run on the selected
 * layer. Layer panel is a bottom sheet in portrait, a side panel in
 * landscape. Tap selects; long-press renames/duplicates/deletes/merges.
 */
@Composable
fun ImageEditorScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var stack by remember { mutableStateOf(LayerStack()) }
    var showSheet by remember { mutableStateOf(false) }
    var menuLayerId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
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

    val selected = stack.selected
    val src = selected?.path.orEmpty()

    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun layerName(): String =
        if (stack.layers.isEmpty()) "พื้นหลัง" else "เลเยอร์ ${stack.layers.size + 1}"

    fun mutateLayer(op: () -> LayerStack) {
        try {
            stack = op()
        } catch (e: IllegalArgumentException) {
            message = e.message
        }
    }

    fun dstFor(tag: String, basePath: String = src): String {
        val base = basePath.substringAfterLast('/').substringBeforeLast('.').ifBlank { "image" }
        val ext = basePath.substringAfterLast('.', "png").take(4)
        return File(File(services.workspaceDir, "images").apply { mkdirs() }, "$base-$tag-${System.currentTimeMillis()}.$ext").absolutePath
    }

    fun runImage(action: String, args: Map<String, String>, tag: String? = null) {
        val layer = stack.selected
        if (layer == null) {
            message = "เพิ่มเลเยอร์ก่อนครับ"
            return
        }
        if (layer.locked) {
            message = "เลเยอร์ \"${layer.name}\" ถูกล็อก — ปลดล็อกก่อนแต่ง"
            return
        }
        val dst = tag?.let { dstFor(it) }
        val full = args.toMutableMap()
        full["src"] = layer.path
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

    fun runFlatten(paths: List<String>, dst: String, onDone: (String) -> Unit) {
        scope.runTool(
            services, "image", "flatten",
            mapOf("srcs" to paths.joinToString("\n"), "dst" to dst),
            onBusy = { busy = it },
            onMessage = {
                message = it
                if (File(dst).exists()) onDone(dst)
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

    fun duplicateLayer(layer: ImageLayer) {
        if (layer.locked) {
            message = "เลเยอร์ \"${layer.name}\" ถูกล็อก — ปลดล็อกก่อนทำสำเนา"
            return
        }
        val dst = dstFor("copy", layer.path)
        busy = true
        scope.launch(Dispatchers.IO) {
            try {
                File(layer.path).copyTo(File(dst), overwrite = true)
                withContext(Dispatchers.Main) {
                    mutateLayer { stack.duplicate(layer.id, newId(), dst) }
                    message = "ทำสำเนาเลเยอร์แล้ว"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "ทำสำเนาไม่ได้: ${e.message}" }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun mergeLayer(layer: ImageLayer) {
        val at = stack.layers.indexOfFirst { it.id == layer.id }
        if (at <= 0) {
            message = "เลเยอร์ล่างสุดไม่มีอะไรให้รวมด้วย"
            return
        }
        val below = stack.layers[at - 1]
        if (layer.locked || below.locked) {
            message = "เลเยอร์ถูกล็อก — ปลดล็อกทั้งคู่ก่อนรวม"
            return
        }
        val dst = dstFor("merged", layer.path)
        runFlatten(listOf(below.path, layer.path), dst) { flat ->
            mutateLayer { stack.mergeDown(layer.id, newId(), flat) }
        }
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
                    mutateLayer { stack.add(ImageLayer(newId(), layerName(), copied)) }
                    lastDst = null
                    loadInfo(copied)
                }
            }
        }
    }

    @Composable
    fun MainList(modifier: Modifier) {
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        LazyColumn(
            modifier = modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "__src__") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    OutlinedButton(
                        onClick = { pickLauncher.launch("image/*") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("🖼️ เพิ่มรูปเป็นเลเยอร์") }
                    if (!isLandscape) {
                        OutlinedButton(
                            onClick = { showSheet = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("🧅 เลเยอร์ (${stack.layers.size})") }
                    }
                    if (selected != null) {
                        Text(
                            "เลือกอยู่: ${selected.name}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Text(src, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (info.isNotBlank()) {
                        Text(info, style = MaterialTheme.typography.bodySmall)
                    }
                    WorkspaceMessage(message)
                }
            }
            if (selected != null) {
                item(key = "__preview__") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("เลเยอร์ที่เลือก", style = MaterialTheme.typography.labelSmall)
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
                                    val layer = stack.selected
                                    if (layer == null) {
                                        message = "เพิ่มเลเยอร์ก่อนครับ"
                                    } else {
                                        mutateLayer { stack.updatePath(layer.id, dst) }
                                        lastDst = null
                                        loadInfo(dst)
                                    }
                                }) { Text("⬆ อัปเดตเลเยอร์") }
                                OutlinedButton(onClick = {
                                    mutateLayer { stack.add(ImageLayer(newId(), layerName(), dst)) }
                                    lastDst = null
                                }) { Text("＋ เป็นเลเยอร์ใหม่") }
                            }
                        }
                    }
                }
                item(key = "__composite__") {
                    WorkspaceSection("🧅 รวมเลเยอร์") {
                        OutlinedButton(
                            onClick = {
                                val visible = stack.visibleLayers()
                                if (visible.isEmpty()) {
                                    message = "ไม่มีเลเยอร์ที่เปิดตาไว้ — เปิด 👁 อย่างน้อย 1 เลเยอร์"
                                    return@OutlinedButton
                                }
                                runFlatten(visible.map { it.path }, dstFor("flat")) { lastDst = it }
                            },
                            enabled = !busy,
                        ) { Text("รวมเลเยอร์ที่เห็น (${stack.visibleLayers().size})") }
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
                                    val layer = stack.selected
                                    if (layer == null) {
                                        message = "เพิ่มเลเยอร์ก่อนครับ"
                                    } else {
                                        scope.runTool(
                                            services, "image", "scopes", mapOf("path" to layer.path),
                                            onBusy = { busy = it },
                                            onMessage = { message = it },
                                        )
                                    }
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
                        ) { Text("นำเข้า${if (lastDst != null) "ผลลัพธ์" else "เลเยอร์ที่เลือก"}") }
                    }
                }
            }
        }
    }

    @Composable
    fun Panel(modifier: Modifier) {
        LayerPanel(
            stack = stack,
            busy = busy,
            onSelect = { mutateLayer { stack.select(it) } },
            onToggleVisible = { mutateLayer { stack.toggleVisible(it) } },
            onToggleLock = { mutateLayer { stack.toggleLock(it) } },
            onMenu = { menuLayerId = it },
            modifier = modifier,
        )
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize()) {
            Panel(modifier = Modifier.width(300.dp).fillMaxHeight())
            MainList(modifier = Modifier.weight(1f))
        }
    } else {
        MainList(modifier = Modifier.fillMaxSize())
        if (showSheet) {
            ModalBottomSheet(onDismissRequest = { showSheet = false }) {
                Panel(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    val menuLayer = stack.layers.find { it.id == menuLayerId }
    if (menuLayer != null) {
        AlertDialog(
            onDismissRequest = { menuLayerId = null; renaming = false },
            title = { Text("🧅 ${menuLayer.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (renaming) {
                        LabeledField("ชื่อใหม่", renameText, { renameText = it })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                mutateLayer { stack.rename(menuLayer.id, renameText) }
                                menuLayerId = null
                                renaming = false
                            }) { Text("บันทึก") }
                            TextButton(onClick = { renaming = false }) { Text("ยกเลิก") }
                        }
                    } else {
                        TextButton(onClick = {
                            renameText = menuLayer.name
                            renaming = true
                        }) { Text("✏️ เปลี่ยนชื่อ") }
                        TextButton(onClick = {
                            duplicateLayer(menuLayer)
                            menuLayerId = null
                        }) { Text("📋 ทำสำเนา") }
                        TextButton(onClick = {
                            mutateLayer { stack.remove(menuLayer.id) }
                            menuLayerId = null
                        }) { Text("🗑️ ลบเลเยอร์") }
                        TextButton(onClick = {
                            mergeLayer(menuLayer)
                            menuLayerId = null
                        }) { Text("🔗 รวมกับเลเยอร์ล่าง") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuLayerId = null; renaming = false }) { Text("ปิด") }
            },
        )
    }
}

/**
 * CP-142 layer panel (§47): top layer first; each row shows thumbnail,
 * name, visibility and lock. Tap selects, long-press opens the menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LayerPanel(
    stack: LayerStack,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onToggleVisible: (String) -> Unit,
    onToggleLock: (String) -> Unit,
    onMenu: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier.padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text("🧅 เลเยอร์ (${stack.layers.size})", style = MaterialTheme.typography.titleSmall)
        if (stack.layers.isEmpty()) {
            Text("(ยังไม่มีเลเยอร์ — เพิ่มรูปก่อน)", style = MaterialTheme.typography.bodySmall)
        }
        stack.layers.asReversed().forEach { layer ->
            val isSelected = layer.id == stack.selected?.id
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                modifier = Modifier.fillMaxWidth()
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    )
                    .combinedClickable(
                        onClick = { onSelect(layer.id) },
                        onLongClick = { onMenu(layer.id) },
                    )
                    .padding(spacing.sm),
            ) {
                WorkspaceImage(path = layer.path, modifier = Modifier.size(48.dp))
                Text(
                    layer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { onToggleVisible(layer.id) }, enabled = !busy) {
                    Text(if (layer.visible) "👁" else "🚫")
                }
                OutlinedButton(onClick = { onToggleLock(layer.id) }, enabled = !busy) {
                    Text(if (layer.locked) "🔒" else "🔓")
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
