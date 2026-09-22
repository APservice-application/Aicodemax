package com.aicodemax.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.aicodemax.core.common.fold
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
    var newText by remember { mutableStateOf("") }
    var presetIndex by remember { mutableStateOf(2) }
    var ideaTopic by remember { mutableStateOf("") }
    var ideaKindIndex by remember { mutableStateOf(2) }
    var ideas by remember { mutableStateOf("") }
    var keyPropIndex by remember { mutableStateOf(0) }
    var keyValue by remember { mutableStateOf(100f) }
    var keyEaseIndex by remember { mutableStateOf(0) }

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
        modifier = Modifier.fillMaxSize().padding(spacing.md),
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
                            ) { Text("เลิกทำ ($undoCount)") }
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
                            ) { Text("ทำซ้ำ") }
                        }
                    }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        val ordered = timeline?.orderedClips().orEmpty()
        item { Text("คลิป (${ordered.size})", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(ordered) { index, (track, clip) ->
            Surface(
                tonalElevation = spacing.xs,
                onClick = { selectedId = clip.id },
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
                    }
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
