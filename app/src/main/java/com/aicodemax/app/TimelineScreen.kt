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
                    }
                }
            }
        }
    }
}
