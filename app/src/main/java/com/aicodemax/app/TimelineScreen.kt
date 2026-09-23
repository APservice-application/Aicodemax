package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.aicodemax.core.common.fold
import com.aicodemax.data.media.TimelineShortcut
import com.aicodemax.data.media.TimelineShortcuts
import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.ClipTransform
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import com.aicodemax.data.media.Track
import com.aicodemax.ui.designsystem.LocalSpacing
import kotlinx.coroutines.launch

/** CP-73 clip-list editor: select clip, transform (§12), freeze, split/dup/delete, undo/redo. */
@Composable
fun TimelineScreen(services: ServiceLocator) {
    val spacing = LocalSpacing.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<Project>>(emptyList()) }
    var projectIndex by remember { mutableStateOf(0) }
    var timeline by remember { mutableStateOf<Timeline?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var undoCount by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var lutPath by remember { mutableStateOf("") }
    var slideAssets by remember { mutableStateOf("") }
    var newText by remember { mutableStateOf("") }
    var presetIndex by remember { mutableStateOf(2) }
    var ideaTopic by remember { mutableStateOf("") }
    var ideaKindIndex by remember { mutableStateOf(2) }
    var ideas by remember { mutableStateOf("") }
    var keyPropIndex by remember { mutableStateOf(0) }
    var keyValue by remember { mutableStateOf(100f) }
    var keyEaseIndex by remember { mutableStateOf(0) }
    var mcPaths by remember { mutableStateOf("") }

    suspend fun refresh() {
        val project = projects.getOrNull(projectIndex) ?: return
        services.media.getTimeline(project.id).fold(
            onSuccess = { timeline = it },
            onFailure = { message = it.message },
        )
        services.media.history(project.id).fold(
            onSuccess = { undoCount = it.undoLabels.size },
            onFailure = {},
        )
    }

    LaunchedEffect(Unit) {
        services.media.listProjects().fold(
            onSuccess = {
                projects = it
                refresh()
            },
            onFailure = { message = it.message },
        )
    }
    LaunchedEffect(projectIndex, projects.size) { refresh() }

    fun runCall(block: suspend (String) -> Unit) {
        val project = projects.getOrNull(projectIndex) ?: return
        scope.launch {
            busy = true
            message = null
            try {
                block(project.id)
            } finally {
                refresh()
                busy = false
            }
        }
    }

    fun selectedClip(): Pair<Track, Clip>? =
        timeline?.orderedClips()?.firstOrNull { it.second.id == selectedId }

    fun adjustSpeed(mutate: (com.aicodemax.data.media.ClipSpeed) -> com.aicodemax.data.media.ClipSpeed) {
        val clip = selectedClip()?.second ?: return
        val next = mutate(clip.speed ?: com.aicodemax.data.media.ClipSpeed())
        runCall { projectId ->
            services.media.setClipSpeed(projectId, clip.id, next, "HUMAN").fold(
                onSuccess = {},
                onFailure = { message = it.message },
            )
        }
    }

    fun keyCall(block: suspend (String, String) -> Unit) {
        val clip = selectedClip()?.second ?: return
        runCall { projectId -> block(projectId, clip.id) }
    }

    val ordered = timeline?.orderedClips().orEmpty()

    fun stepSelection(dir: Int) {
        val ids = ordered.map { it.second.id }
        if (ids.isEmpty()) return
        val cur = ids.indexOf(selectedId)
        selectedId = if (cur < 0) ids[0] else ids[(cur + dir + ids.size) % ids.size]
    }

    // CP-106 expert keyboard shortcuts + TalkBack announcements via [message].
    fun handleShortcut(code: TimelineShortcut): Boolean {
        when (code) {
            TimelineShortcut.UNDO -> runCall { projectId ->
                services.media.undo(projectId, "HUMAN").fold(
                    onSuccess = { message = it },
                    onFailure = { message = it.message },
                )
            }
            TimelineShortcut.REDO -> runCall { projectId ->
                services.media.redo(projectId, "HUMAN").fold(
                    onSuccess = { message = it },
                    onFailure = { message = it.message },
                )
            }
            TimelineShortcut.NEXT_CLIP -> stepSelection(1)
            TimelineShortcut.PREV_CLIP -> stepSelection(-1)
            TimelineShortcut.DELETE_CLIP -> keyCall { projectId, clipId ->
                services.media.deleteClip(projectId, clipId, "HUMAN").fold(
                    onSuccess = { selectedId = null },
                    onFailure = { message = it.message },
                )
            }
        }
        return true
    }

    val shortcutFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            shortcutFocus.requestFocus()
        } catch (_: Exception) {
        }
    }

    fun adjustClip(mutate: (ClipTransform) -> ClipTransform) {
        val clip = selectedClip()?.second ?: return
        val next = mutate(clip.transform ?: ClipTransform())
        runCall { projectId ->
            services.media.transformClip(projectId, clip.id, next, "HUMAN").fold(
                onSuccess = {},
                onFailure = { message = it.message },
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(spacing.md)
            .focusRequester(shortcutFocus).focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val code = TimelineShortcuts.resolve(
                        event.key.keyCode.toInt(), event.isCtrlPressed, event.isShiftPressed,
                    ) ?: return@onKeyEvent false
                    handleShortcut(code)
                }
            },
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item {
            Surface(tonalElevation = spacing.xs) {
                Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text("ไทม์ไลน์", style = MaterialTheme.typography.titleMedium)
                    if (projects.isEmpty()) {
                        Text("ยังไม่มีโปรเจกต์ — สร้างในแชทก่อน (เช่น สร้างโปรเจกต์ ทริปทะเล)")
                    } else {
                        TextButton(onClick = { projectIndex = (projectIndex + 1) % projects.size }, enabled = !busy) {
                            Text("โปรเจกต์: ${projects[projectIndex].name} (${projectIndex + 1}/${projects.size})")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(
                                onClick = {
                                    runCall { projectId ->
                                        services.media.undo(projectId, "HUMAN").fold(
                                            onSuccess = { message = it },
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("เลิกทำ Ctrl+Z ($undoCount)") }
                            OutlinedButton(
                                onClick = {
                                    runCall { projectId ->
                                        services.media.redo(projectId, "HUMAN").fold(
                                            onSuccess = { message = it },
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("ทำซ้ำ Ctrl+Y") }
                        }
                    }
                    Text(
                        TimelineShortcuts.help,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    message?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
        item { Text("คลิป (${ordered.size})", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(ordered) { index, (track, clip) ->
            val clipSelected = clip.id == selectedId
            Surface(
                tonalElevation = spacing.xs,
                onClick = { selectedId = clip.id },
                modifier = Modifier.semantics {
                    contentDescription =
                        "คลิปที่ ${index + 1} แทร็ก ${track.id} ${clip.startMs} ถึง ${clip.endMs} มิลลิวินาที" +
                            if (clipSelected) " เลือกแล้ว" else ""
                },
            ) {
                Column(modifier = Modifier.padding(spacing.md).fillMaxWidth()) {
                    Text(
                        "${index + 1}. ${track.id}: ${clip.startMs}..${clip.endMs} @${clip.atMs}" +
                            (clip.transform?.summary()?.ifBlank { null }?.let { " <$it>" } ?: "") +
                            (if (clip.id == selectedId) " ●" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        val presets = listOf("title", "lower", "caption", "hook", "cta")
        item {
            Surface(tonalElevation = spacing.xs) {
                Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text("ข้อความ (${timeline?.texts?.size ?: 0})", style = MaterialTheme.typography.titleMedium)
                    timeline?.texts?.forEachIndexed { i, overlay ->
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Text(
                                "T${i + 1}. ${overlay.summary()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    runCall { projectId ->
                                        services.media.removeText(projectId, overlay.id, "HUMAN").fold(
                                            onSuccess = {},
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("ลบ") }
                        }
                    }
                    TextField(
                        value = newText,
                        onValueChange = { newText = it },
                        label = { Text("ข้อความใหม่") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedButton(
                            onClick = { presetIndex = (presetIndex + 1) % presets.size },
                            enabled = !busy,
                        ) { Text("สไตล์: ${presets[presetIndex]}") }
                        Button(
                            onClick = {
                                runCall { projectId ->
                                    val duration = timeline?.durationMs ?: 0
                                    services.media.addText(
                                        projectId,
                                        com.aicodemax.data.media.OverlayText.preset(presets[presetIndex]).copy(
                                            id = com.aicodemax.core.common.Ids.newId("text"),
                                            text = newText,
                                            startMs = 0,
                                            endMs = if (duration > 0) duration else 3000,
                                        ),
                                        "HUMAN",
                                    ).fold(
                                        onSuccess = { newText = "" },
                                        onFailure = { message = it.message },
                                    )
                                }
                            },
                            enabled = !busy && newText.isNotBlank(),
                        ) { Text("เพิ่ม") }
                    }
                    TextField(
                        value = ideaTopic,
                        onValueChange = { ideaTopic = it },
                        label = { Text("หัวข้อสำหรับไอเดีย") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        val kinds = listOf("title", "hook", "caption", "cta", "description")
                        OutlinedButton(
                            onClick = { ideaKindIndex = (ideaKindIndex + 1) % kinds.size },
                            enabled = !busy,
                        ) { Text("ชนิด: ${kinds[ideaKindIndex]}") }
                        Button(
                            onClick = {
                                ideas = com.aicodemax.tools.media.TextIdeas
                                    .ideas(kinds[ideaKindIndex], ideaTopic)
                                    .mapIndexed { i, idea -> "${i + 1}. $idea" }
                                    .joinToString("\n")
                            },
                            enabled = ideaTopic.isNotBlank(),
                        ) { Text("คิดไอเดีย") }
                    }
                    if (ideas.isNotEmpty()) Text(ideas, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val selection = selectedClip()
        if (selection != null) {
            val (track, clip) = selection
            item {
                Surface(tonalElevation = spacing.xs) {
                    Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        Text("คลิปที่เลือก (${track.id})", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            Button(
                                onClick = {
                                    runCall { projectId ->
                                        services.media.splitClip(
                                            projectId, clip.id,
                                            clip.atMs + clip.durationMs / 2, "HUMAN",
                                        ).fold(
                                            onSuccess = {},
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("แยกกลาง") }
                            OutlinedButton(
                                onClick = {
                                    runCall { projectId ->
                                        services.media.duplicateClip(projectId, clip.id, null, "HUMAN").fold(
                                            onSuccess = {},
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("ซ้ำ") }
                            OutlinedButton(
                                onClick = {
                                    runCall { projectId ->
                                        services.media.deleteClip(projectId, clip.id, "HUMAN").fold(
                                            onSuccess = { selectedId = null },
                                            onFailure = { message = it.message },
                                        )
                                    }
                                },
                                enabled = !busy,
                            ) { Text("ลบ") }
                        }
                        if (track.kind.name == "VIDEO") {
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                Button(
                                    onClick = {
                                        runCall { projectId ->
                                            services.media.freezeFrame(projectId, clip.id, null, 2000, "HUMAN").fold(
                                                onSuccess = {},
                                                onFailure = { message = it.message },
                                            )
                                        }
                                    },
                                    enabled = !busy,
                                ) { Text("ฟรีซ 2 วิ") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(rotation = (it.rotation + 90) % 360) }
                                }, enabled = !busy) { Text("หมุน ${(clip.transform?.rotation ?: 0)}°") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(flipH = !it.flipH) }
                                }, enabled = !busy) { Text(if (clip.transform?.flipH == true) "↔ เปิด" else "↔") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(flipV = !it.flipV) }
                                }, enabled = !busy) { Text(if (clip.transform?.flipV == true) "↕ เปิด" else "↕") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(scale = (it.scale - 25).coerceAtLeast(1)) }
                                }, enabled = !busy) { Text("ซูม−") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(scale = (it.scale + 25).coerceAtMost(400)) }
                                }, enabled = !busy) { Text("ซูม+ ${clip.transform?.scale ?: 100}%") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(opacity = (it.opacity - 10).coerceAtLeast(0)) }
                                }, enabled = !busy) { Text("ทึบ−") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(opacity = (it.opacity + 10).coerceAtMost(100)) }
                                }, enabled = !busy) { Text("ทึบ+ ${clip.transform?.opacity ?: 100}") }
                                OutlinedButton(onClick = {
                                    adjustClip { ClipTransform() }
                                }, enabled = !busy) { Text("รีเซ็ต") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(cropX = 0, cropY = 0, cropW = 100, cropH = 100) }
                                }, enabled = !busy) { Text("ครอปเต็ม") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(cropX = 12, cropY = 12, cropW = 76, cropH = 76) }
                                }, enabled = !busy) { Text("ครอป 75%") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(cropX = 25, cropY = 25, cropW = 50, cropH = 50) }
                                }, enabled = !busy) { Text("ครอป 50%") }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(posX = (it.posX - 40).coerceAtLeast(-4000)) }
                                }, enabled = !busy) { Text("←") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(posX = (it.posX + 40).coerceAtMost(4000)) }
                                }, enabled = !busy) { Text("→") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(posY = (it.posY - 40).coerceAtLeast(-4000)) }
                                }, enabled = !busy) { Text("↑") }
                                OutlinedButton(onClick = {
                                    adjustClip { it.copy(posY = (it.posY + 40).coerceAtMost(4000)) }
                                }, enabled = !busy) { Text("↓") }
                            }
                        }
                        Text(
                            "ความเร็ว: ${(clip.speed?.summary()?.ifBlank { null } ?: "ปกติ")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(onClick = {
                                adjustSpeed { it.copy(rate = 50) }
                            }, enabled = !busy) { Text("0.5x") }
                            OutlinedButton(onClick = {
                                adjustSpeed { com.aicodemax.data.media.ClipSpeed() }
                            }, enabled = !busy) { Text("1x") }
                            OutlinedButton(onClick = {
                                adjustSpeed { it.copy(rate = 200) }
                            }, enabled = !busy) { Text("2x") }
                            OutlinedButton(onClick = {
                                adjustSpeed { it.copy(reverse = !it.reverse, curve = emptyList()) }
                            }, enabled = !busy) { Text(if (clip.speed?.reverse == true) "REV เปิด" else "ย้อนกลับ") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            val curves = listOf("none", "easein", "easeout", "montage", "hero", "bullet")
                            OutlinedButton(onClick = {
                                adjustSpeed { current ->
                                    val names = curves.filter { it != "none" }
                                    val idx = (selectedCurveIndex(clip) + 1) % (names.size + 1)
                                    if (idx == 0) current.copy(curve = emptyList())
                                    else current.copy(
                                        curve = com.aicodemax.data.media.ClipSpeed.preset(names[idx - 1]),
                                        reverse = false,
                                    )
                                }
                            }, enabled = !busy) { Text("ramp: ${selectedCurveName(clip)}") }
                        }
                        // CP-76 keyframes (§14).
                        val keyProps = listOf("scale", "rotation", "opacity", "volume", "posX", "posY")
                        val keyEases = listOf("linear", "easein", "easeout", "easeinout")
                        val keyProp = keyProps[keyPropIndex % keyProps.size]
                        val keyEase = keyEases[keyEaseIndex % keyEases.size]
                        Text(
                            "คีย์เฟรม: ${(clip.keyframes?.summary()?.ifBlank { null } ?: "ไม่มี")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(onClick = {
                                keyPropIndex = (keyPropIndex + 1) % keyProps.size
                                keyValue = keyDefault(keyProps[keyPropIndex % keyProps.size])
                            }, enabled = !busy) { Text(keyProp) }
                            OutlinedButton(onClick = {
                                keyEaseIndex = (keyEaseIndex + 1) % keyEases.size
                            }, enabled = !busy) { Text(keyEase) }
                            OutlinedButton(onClick = {
                                keyValue = (keyValue - keyStep(keyProp)).coerceAtLeast(keyRange(keyProp).first)
                            }, enabled = !busy) { Text("-") }
                            OutlinedButton(onClick = {
                                keyValue = (keyValue + keyStep(keyProp)).coerceAtMost(keyRange(keyProp).second)
                            }, enabled = !busy) { Text("+") }
                            Text("=${keyValue.toInt()}", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            val outLen = clip.outputDurationMs()
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setKeyframe(projectId, clipId, keyProp, 0L, keyValue, keyEase, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("＋ต้น") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setKeyframe(projectId, clipId, keyProp, outLen / 2, keyValue, keyEase, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("＋กลาง") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setKeyframe(projectId, clipId, keyProp, outLen, keyValue, keyEase, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("＋ท้าย") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.clearKeyframes(projectId, clipId, null, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ล้าง") }
                        }
                        clip.keyframes?.let { keys ->
                            for (prop in com.aicodemax.data.media.ClipKeyframes.PROPS) {
                                for (pt in keys.points(prop)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                        Text("$prop @${pt.atMs}ms = ${pt.value} (${pt.ease})", style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = {
                                            keyCall { projectId, clipId ->
                                                services.media.removeKeyframe(projectId, clipId, prop, pt.atMs, "HUMAN").fold(
                                                    onSuccess = {},
                                                    onFailure = { message = it.message },
                                                )
                                            }
                                        }, enabled = !busy) { Text("ลบ") }
                                    }
                                }
                            }
                        }
                        // CP-77 transitions (§21) + fx (§20).
                        val inKinds = listOf("cut", "fade", "dissolve", "wipeleft", "wiperight", "wipeup", "wipedown")
                        val outKinds = listOf("cut", "fade")
                        Text(
                            "ทรานซิชัน: เข้า=${clip.transitionIn?.summary() ?: "cut"} ออก=${clip.transitionOut?.summary() ?: "cut"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(onClick = {
                                val cur = clip.transitionIn?.kind ?: "cut"
                                val next = inKinds[(inKinds.indexOf(cur) + 1) % inKinds.size]
                                keyCall { projectId, clipId ->
                                    services.media.setTransition(projectId, clipId, "in", next, clip.transitionIn?.durationMs ?: 500L, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("เข้า:${clip.transitionIn?.kind ?: "cut"}") }
                            OutlinedButton(onClick = {
                                val cur = clip.transitionOut?.kind ?: "cut"
                                val next = outKinds[(outKinds.indexOf(cur) + 1) % outKinds.size]
                                keyCall { projectId, clipId ->
                                    services.media.setTransition(projectId, clipId, "out", next, clip.transitionOut?.durationMs ?: 500L, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ออก:${clip.transitionOut?.kind ?: "cut"}") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val d = ((clip.transitionIn?.durationMs ?: 500L) - 100).coerceAtLeast(100)
                                    services.media.setTransition(projectId, clipId, "in", clip.transitionIn?.kind ?: "fade", d, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("สั้น") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val d = ((clip.transitionIn?.durationMs ?: 500L) + 100).coerceAtMost(2000)
                                    services.media.setTransition(projectId, clipId, "in", clip.transitionIn?.kind ?: "fade", d, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ยาว") }
                        }
                        Text(
                            "เอฟเฟกต์: ${(clip.fx?.summary()?.ifBlank { null } ?: "ปิด")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            val fx = clip.fx ?: com.aicodemax.data.media.ClipFx()
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipFx(projectId, clipId, fx.copy(blur = (fx.blur + 2) % 12), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("เบลอ:${fx.blur}") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipFx(projectId, clipId, fx.copy(vignette = (fx.vignette + 25) % 125), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("วิกเน็ต:${fx.vignette}") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipFx(projectId, clipId, fx.copy(grain = (fx.grain + 25) % 125), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("เกรน:${fx.grain}") }
                        }
                        // CP-78 color (§42).
                        val cc = clip.color ?: com.aicodemax.data.media.ClipColor()
                        val presets = listOf("none", "cinema", "warm", "cool", "vivid", "bw")
                        Text(
                            "สี: ${(clip.color?.summary()?.ifBlank { null } ?: "ปกติ")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(onClick = {
                                val cur = presets.firstOrNull { com.aicodemax.data.media.ClipColor.preset(it) == cc } ?: "none"
                                val next = presets[(presets.indexOf(cur) + 1) % presets.size]
                                keyCall { projectId, clipId ->
                                    services.media.setClipColor(projectId, clipId, com.aicodemax.data.media.ClipColor.preset(next), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("โทน") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipColor(projectId, clipId, cc.copy(brightness = (cc.brightness - 10).coerceAtLeast(-100)), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("มืด") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipColor(projectId, clipId, cc.copy(brightness = (cc.brightness + 10).coerceAtMost(100)), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("สว่าง") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipColor(projectId, clipId, cc.copy(saturation = (cc.saturation + 15).coerceAtMost(100)), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("สด+") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipColor(projectId, clipId, cc.copy(exposure = (cc.exposure - 10).coerceAtLeast(-100)), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("รับแสง-") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipColor(projectId, clipId, cc.copy(exposure = (cc.exposure + 10).coerceAtMost(100)), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("รับแสง+") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val call = com.aicodemax.tools.gateway.ToolCall(
                                        com.aicodemax.core.common.Ids.newId("ui"), "media", "timeline.colorAuto",
                                        mapOf("projectId" to projectId, "clipId" to clipId), actor = "HUMAN",
                                    )
                                    services.gateway.call(call).fold(
                                        onSuccess = { message = if (it.ok) it.output else it.error },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ออโต้สี") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val call = com.aicodemax.tools.gateway.ToolCall(
                                        com.aicodemax.core.common.Ids.newId("ui"), "media", "timeline.enhance",
                                        mapOf("projectId" to projectId, "clipId" to clipId), actor = "HUMAN",
                                    )
                                    services.gateway.call(call).fold(
                                        onSuccess = { message = if (it.ok) it.output else it.error },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ปรับปรุง") }
                        }
                        // CP-90 wheels (§34 Pro).
                        listOf(
                            Triple("ลิฟต์", cc.lift, { v: Int -> cc.copy(lift = v) }),
                            Triple("แกมมา", cc.gamma, { v: Int -> cc.copy(gamma = v) }),
                            Triple("เกน", cc.gain, { v: Int -> cc.copy(gain = v) }),
                        ).forEach { (label, value, apply) ->
                            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                OutlinedButton(onClick = {
                                    keyCall { projectId, clipId ->
                                        services.media.setClipColor(projectId, clipId, apply((value - 10).coerceAtLeast(-100)), "HUMAN").fold(
                                            onSuccess = {},
                                            onFailure = { message = it.message },
                                        )
                                    }
                                }, enabled = !busy) { Text("-") }
                                Text("$label $value", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = {
                                    keyCall { projectId, clipId ->
                                        services.media.setClipColor(projectId, clipId, apply((value + 10).coerceAtMost(100)), "HUMAN").fold(
                                            onSuccess = {},
                                            onFailure = { message = it.message },
                                        )
                                    }
                                }, enabled = !busy) { Text("+") }
                            }
                        }
                        // CP-81 LUT (§42 Pro).
                        Text(
                            "LUT: ${(clip.lut?.summary() ?: "ไม่มี")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            TextField(
                                value = lutPath,
                                onValueChange = { lutPath = it },
                                label = { Text("ไฟล์ .cube") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(0.55f),
                            )
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val call = com.aicodemax.tools.gateway.ToolCall(
                                        com.aicodemax.core.common.Ids.newId("ui"), "media", "timeline.lut",
                                        mapOf("projectId" to projectId, "clipId" to clipId, "path" to lutPath.trim()), actor = "HUMAN",
                                    )
                                    services.gateway.call(call).fold(
                                        onSuccess = { message = if (it.ok) it.output else it.error },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy && lutPath.isNotBlank()) { Text("ใส่ LUT") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipLut(projectId, clipId, null, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ล้าง") }
                        }
                        // CP-84 Ken Burns + slideshow (§45).
                        val mo = clip.motion ?: com.aicodemax.data.media.ClipMotion()
                        Text(
                            "โมชัน: ${clip.motion?.summary() ?: "นิ่ง"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(onClick = {
                                val dirs = com.aicodemax.data.media.ClipMotion.DIRS
                                val next = dirs[(dirs.indexOf(mo.direction).coerceAtLeast(0) + 1) % dirs.size]
                                keyCall { projectId, clipId ->
                                    services.media.setClipMotion(projectId, clipId, mo.copy(direction = next), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ทิศ:${mo.direction}") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipMotion(projectId, clipId, mo.copy(zoom = (mo.zoom + 10).coerceAtMost(60)), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ซูม+:${mo.zoom}") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipMotion(projectId, clipId, null, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ปิดโมชัน") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            TextField(
                                value = slideAssets,
                                onValueChange = { slideAssets = it },
                                label = { Text("assetIds รูป คั่นจุลภาค") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(0.6f),
                            )
                            OutlinedButton(onClick = {
                                val ids = slideAssets.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                runCall { projectId ->
                                    services.media.slideshow(projectId, ids, 3000, 400, "HUMAN").fold(
                                        onSuccess = { message = "สไลด์โชว์ ${ids.size} รูปแล้ว" },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy && slideAssets.isNotBlank()) { Text("สไลด์โชว์") }
                        }
                        // CP-85 mixer-lite + beats (§31/§102).
                        Text(
                            "เสียง: ${clip.volume}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipVolume(projectId, clipId, (clip.volume - 10).coerceAtLeast(0), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("เบา") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipVolume(projectId, clipId, (clip.volume + 10).coerceAtMost(100), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ดัง") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val call = com.aicodemax.tools.gateway.ToolCall(
                                        com.aicodemax.core.common.Ids.newId("ui"), "media", "timeline.beatsToMarkers",
                                        mapOf("projectId" to projectId, "clipId" to clipId), actor = "HUMAN",
                                    )
                                    services.gateway.call(call).fold(
                                        onSuccess = { message = if (it.ok) it.output else it.error },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("จังหวะ→มาร์ก") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val call = com.aicodemax.tools.gateway.ToolCall(
                                        com.aicodemax.core.common.Ids.newId("ui"), "media", "timeline.autocut",
                                        mapOf("projectId" to projectId, "clipId" to clipId), actor = "HUMAN",
                                    )
                                    services.gateway.call(call).fold(
                                        onSuccess = { message = if (it.ok) it.output else it.error },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ตัดเงียบ") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val call = com.aicodemax.tools.gateway.ToolCall(
                                        com.aicodemax.core.common.Ids.newId("ui"), "media", "timeline.highlights",
                                        mapOf("projectId" to projectId, "clipId" to clipId), actor = "HUMAN",
                                    )
                                    services.gateway.call(call).fold(
                                        onSuccess = { message = if (it.ok) it.output else it.error },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ช็อตเด่น") }
                        }
                        // CP-88 reframe + canvas (§33).
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            listOf("9:16", "16:9", "1:1").forEach { aspect ->
                                OutlinedButton(onClick = {
                                    runCall { projectId ->
                                        val mkCall = { action: String, args: Map<String, String> ->
                                            com.aicodemax.tools.gateway.ToolCall(
                                                com.aicodemax.core.common.Ids.newId("ui"), "media", action,
                                                args, actor = "HUMAN",
                                            )
                                        }
                                        val canvasRes = services.gateway.call(mkCall("timeline.setCanvas", mapOf("projectId" to projectId, "aspect" to aspect)))
                                        val clipId = selectedClip()?.second?.id
                                        val reframeRes = if (clipId == null) null else services.gateway.call(
                                            mkCall("timeline.reframe", mapOf("projectId" to projectId, "clipId" to clipId, "aspect" to aspect)),
                                        )
                                        val cMsg = canvasRes.fold({ if (it.ok) it.output else it.error }, { it.message })
                                        val rMsg = reframeRes?.fold({ if (it.ok) "รีเฟรมแล้ว" else it.error }, { it.message })
                                        message = if (rMsg == null) cMsg else cMsg + " / " + rMsg
                                    }
                                }, enabled = !busy) { Text(aspect) }
                            }
                        }
                        // CP-79 mask + chroma (§17/§18).
                        val mk = clip.mask ?: com.aicodemax.data.media.ClipMask()
                        Text(
                            "มาสก์: ${(clip.mask?.takeUnless { it.isIdentity }?.summary() ?: "ปิด")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(onClick = {
                                val has = clip.mask?.takeUnless { it.isIdentity } != null
                                keyCall { projectId, clipId ->
                                    val next = if (has) com.aicodemax.data.media.ClipMask()
                                    else com.aicodemax.data.media.ClipMask(shape = "ellipse", feather = 20)
                                    services.media.setClipMask(projectId, clipId, next, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text(if (clip.mask?.takeUnless { it.isIdentity } != null) "มาสก์:เปิด" else "มาสก์") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val next = mk.copy(shape = if (mk.shape == "ellipse") "rect" else "ellipse", x = 20, y = 20, w = 60, h = 60)
                                    services.media.setClipMask(projectId, clipId, next, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text(mk.shape) }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val next = (if (mk.isIdentity) mk.copy(x = 20, y = 20, w = 60, h = 60) else mk).copy(feather = (mk.feather + 20) % 120)
                                    services.media.setClipMask(projectId, clipId, next, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ขน:${mk.feather}") }
                        }
                        Text(
                            "chroma: ${(clip.chroma?.summary() ?: "ปิด")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            val ch = clip.chroma ?: com.aicodemax.data.media.ClipChroma()
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val next = if (clip.chroma == null) com.aicodemax.data.media.ClipChroma() else null
                                    services.media.setClipChroma(projectId, clipId, next, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text(if (clip.chroma == null) "กรีนสกรีน" else "กรีน:เปิด") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipChroma(projectId, clipId, ch.copy(hue = if (ch.hue == 120) 240 else 120), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text(if (ch.hue == 240) "ฟ้า" else "เขียว") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.setClipChroma(projectId, clipId, ch.copy(tolerance = (ch.tolerance + 10).coerceAtMost(100)), "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ทน:${ch.tolerance}") }
                        }
                        // CP-79 background (§19).
                        Text(
                            "พื้นหลัง: ${(timeline?.background?.summary() ?: "black")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            val modes = listOf("black", "color", "blur")
                            OutlinedButton(onClick = {
                                val cur = timeline?.background?.mode ?: "black"
                                val next = modes[(modes.indexOf(cur).coerceAtLeast(0) + 1) % modes.size]
                                runCall { projectId ->
                                    val bg = if (next == "black") null
                                    else (timeline?.background ?: com.aicodemax.data.media.ClipBackground()).copy(mode = next, color = "1A2B4A")
                                    services.media.setBackground(projectId, bg, "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("โหมด:${timeline?.background?.mode ?: "black"}") }
                        }
                        // CP-80 tracking + stabilize (§15/§43).
                        Text(
                            "โมชัน: " + if ((clip.keyframes?.points("posX")?.size ?: 0) > 0) "มี path" else "ไม่มี",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val call = com.aicodemax.tools.gateway.ToolCall(
                                        com.aicodemax.core.common.Ids.newId("ui"), "media", "timeline.stabilize",
                                        mapOf("projectId" to projectId, "clipId" to clipId), actor = "HUMAN",
                                    )
                                    services.gateway.call(call).fold(
                                        onSuccess = { message = if (it.ok) it.output else it.error },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("กันสั่น") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    val call = com.aicodemax.tools.gateway.ToolCall(
                                        com.aicodemax.core.common.Ids.newId("ui"), "media", "timeline.track",
                                        mapOf("projectId" to projectId, "clipId" to clipId, "target" to "self"), actor = "HUMAN",
                                    )
                                    services.gateway.call(call).fold(
                                        onSuccess = { message = if (it.ok) it.output else it.error },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("แทร็กกลาง") }
                            OutlinedButton(onClick = {
                                keyCall { projectId, clipId ->
                                    services.media.clearKeyframes(projectId, clipId, "posX", "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                    services.media.clearKeyframes(projectId, clipId, "posY", "HUMAN").fold(
                                        onSuccess = {},
                                        onFailure = { message = it.message },
                                    )
                                }
                            }, enabled = !busy) { Text("ล้างแทร็ก") }
                        }
                    }
                }
            }
        }
        // CP-104 multicam (§30).
        item {
            Surface(tonalElevation = spacing.xs) {
                Column(modifier = Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    Text("มัลติแคม", style = MaterialTheme.typography.titleMedium)
                    TextField(
                        value = mcPaths,
                        onValueChange = { mcPaths = it },
                        label = { Text("ไฟล์แต่ละมุม คั่นด้วย ,") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        OutlinedButton(onClick = {
                            val paths = mcPaths.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            if (paths.size < 2) {
                                message = "ใส่อย่างน้อย 2 มุมครับ"
                            } else {
                                scope.launch {
                                    busy = true
                                    message = null
                                    try {
                                        val call = com.aicodemax.tools.gateway.ToolCall(
                                            com.aicodemax.core.common.Ids.newId("ui"), "video", "multicam.sync",
                                            mapOf("paths" to paths.joinToString(",")), actor = "HUMAN",
                                        )
                                        services.gateway.call(call).fold(
                                            onSuccess = { message = if (it.ok) it.output else it.error },
                                            onFailure = { message = it.message },
                                        )
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        }, enabled = !busy) { Text("ซิงก์มัลติแคม") }
                        OutlinedButton(onClick = {
                            keyCall { projectId, clipId ->
                                val clip = selectedClip()?.second ?: return@keyCall
                                val asset = services.media.assetPath(projectId, clip.assetId).fold(
                                    onSuccess = { it },
                                    onFailure = { null },
                                )
                                if (asset == null) {
                                    message = "หาไฟล์คลิปที่เลือกไม่เจอ"
                                } else {
                                    val mid = (clip.startMs + clip.endMs) / 2
                                    val call = com.aicodemax.tools.gateway.ToolCall(
                                        com.aicodemax.core.common.Ids.newId("ui"), "video", "scopes",
                                        mapOf("path" to asset, "atMs" to mid.toString()), actor = "HUMAN",
                                    )
                                    services.gateway.call(call).fold(
                                        onSuccess = { message = if (it.ok) it.output else it.error },
                                        onFailure = { message = it.message },
                                    )
                                }
                            }
                        }, enabled = !busy) { Text("สโคปคลิปที่เลือก") }
                    }
                    Text("ตัดมุม/รายการตัด สั่งในแชทได้ เช่น ตัดมัลติแคม mc_1 ที่ 5000 มุม 2")
                    message?.let { Text(it) }
                }
            }
        }
    }
}

private fun keyStep(prop: String): Float = when (prop) {
    "posX", "posY" -> 40f
    "rotation" -> 15f
    else -> 10f
}

private fun keyDefault(prop: String): Float = when (prop) {
    "scale", "opacity", "volume" -> 100f
    else -> 0f
}

private fun keyRange(prop: String): Pair<Float, Float> =
    com.aicodemax.data.media.ClipKeyframes.RANGES[prop] ?: (0f to 100f)

/** Matches the clip's curve against known presets (UI label only). */
private fun selectedCurveName(clip: Clip): String {
    val curve = clip.speed?.curve.orEmpty()
    if (curve.isEmpty()) return "none"
    for (name in listOf("easein", "easeout", "montage", "hero", "bullet")) {
        if (com.aicodemax.data.media.ClipSpeed.preset(name) == curve) return name
    }
    return "custom"
}

private fun selectedCurveIndex(clip: Clip): Int {
    val name = selectedCurveName(clip)
    if (name == "none" || name == "custom") return 0
    return listOf("easein", "easeout", "montage", "hero", "bullet").indexOf(name) + 1
}
