package com.aicodemax.tools.media_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.data.media.ClipSpeed
import com.aicodemax.data.media.ClipKeyframes
import com.aicodemax.data.media.ClipTransform
import com.aicodemax.data.media.SpeedPoint
import com.aicodemax.core.common.Ids
import com.aicodemax.data.media.OverlayText
import com.aicodemax.tools.media.TextIdeas
import com.aicodemax.tools.media.InMemoryMediaProject
import com.aicodemax.tools.media.MediaProjectPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for media projects. */
class MediaToolExecutor(private val media: MediaProjectPort = InMemoryMediaProject()) : ToolExecutor {
    override val toolId: String = "media"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "project.create" -> {
                    val name = call.args["name"] ?: "Untitled"
                    media.createProject(name, call.actor).fold(
                        onSuccess = { done(true, "โปรเจกต์ ${it.name} (${it.id})") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "project.list" -> media.listProjects().fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) done(true, "ยังไม่มีโปรเจกต์")
                        else done(true, list.joinToString("\n") { "${it.id} | ${it.name} | v${it.version} | ${it.timeline.durationMs}ms" })
                    },
                    onFailure = { done(false, error = it.message) },
                )
                "asset.import" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val path = call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: path")
                    media.importAsset(projectId, path, call.actor).fold(
                        onSuccess = { done(true, "import แล้ว ${it.originalName} (${it.kind}, ${it.id})") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "asset.list" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.listAssets(projectId).fold(
                        onSuccess = { list ->
                            if (list.isEmpty()) done(true, "ยังไม่มี asset")
                            else done(true, list.joinToString("\n") { "${it.id} | ${it.kind} | ${it.originalName}" })
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.get" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.getTimeline(projectId).fold(
                        onSuccess = { timeline ->
                            val ordered = timeline.orderedClips()
                            val lines = ordered.mapIndexed { i, (track, clip) ->
                                "  ${i + 1}. ${track.id}: ${clip.assetId} ${clip.startMs}..${clip.endMs} @${clip.atMs} [${clip.id}]" +
                                    (clip.transform?.summary()?.ifBlank { null }?.let { " <$it>" } ?: "") +
                                    (clip.speed?.summary()?.ifBlank { null }?.let { " [$it]" } ?: "") +
                                    (clip.keyframes?.takeUnless { it.isEmpty }?.let { " {KF ${it.summary()}}" } ?: "")
                            }
                            val flags = timeline.tracks.joinToString(" ") { track ->
                                buildString {
                                    append(track.id)
                                    if (track.locked) append("(ล็อก)")
                                    if (track.muted) append("(ปิดเสียง)")
                                    if (track.hidden) append("(ซ่อน)")
                                }
                            }
                            val markers = if (timeline.markers.isEmpty()) "ไม่มีมาร์กเกอร์"
                            else timeline.markers.joinToString(", ") { "${it.label.ifBlank { "มาร์ก" }}@${it.atMs} [${it.id}]" }
                            val texts = if (timeline.texts.isEmpty()) "ไม่มีข้อความ"
                            else timeline.texts.mapIndexed { i, t -> "T${i + 1}. ${t.summary()} [${t.id}]" }.joinToString("\n")
                            done(true, "timeline ${timeline.durationMs}ms, ${lines.size} คลิป\nแทร็ก: $flags\n" + lines.joinToString("\n") + "\n$markers\n$texts")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.addClip" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val assetId = call.args["assetId"]
                        ?: return@withContext done(false, error = "missing arg: assetId")
                    val start = call.args["startMs"]?.toLongOrNull()
                    val end = call.args["endMs"]?.toLongOrNull()
                    val at = call.args["atMs"]?.toLongOrNull()
                    if (start == null || end == null || at == null) {
                        return@withContext done(false, error = "missing args: startMs,endMs,atMs")
                    }
                    val volume = call.args["volume"]?.toIntOrNull() ?: 100
                    media.addClip(projectId, assetId, start, end, at, volume, call.actor).fold(
                        onSuccess = { done(true, "วางคลิปแล้ว timeline ยาว ${it.timeline.durationMs}ms") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "version.save" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.saveVersion(projectId, call.actor).fold(
                        onSuccess = { done(true, "บันทึกเวอร์ชัน $it แล้ว") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "version.list" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.listVersions(projectId).fold(
                        onSuccess = { done(true, if (it.isEmpty()) "ยังไม่มีเวอร์ชัน" else "เวอร์ชัน: " + it.joinToString(",")) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "version.restore" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val version = call.args["version"]?.toIntOrNull()
                        ?: return@withContext done(false, error = "missing arg: version")
                    media.restoreVersion(projectId, version, call.actor).fold(
                        onSuccess = { done(true, "ย้อนไปเวอร์ชัน $version แล้ว (timeline ${it.timeline.durationMs}ms)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "project.rename" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val name = call.args["name"]
                        ?: return@withContext done(false, error = "missing arg: name")
                    media.renameProject(projectId, name, call.actor).fold(
                        onSuccess = { done(true, "เปลี่ยนชื่อเป็น ${it.name} แล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "project.duplicate" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.duplicateProject(projectId, call.actor).fold(
                        onSuccess = { done(true, "สำเนาแล้ว ${it.name} (${it.id})") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "project.delete" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.deleteProject(projectId, call.actor).fold(
                        onSuccess = { done(true, "ลบแล้ว (กู้ได้: project.restore $it หรือ edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "project.trash" -> media.listTrash().fold(
                    onSuccess = { done(true, if (it.isEmpty()) "ถังขยะว่าง" else it.joinToString("\n")) },
                    onFailure = { done(false, error = it.message) },
                )
                "project.restore" -> {
                    val trashId = call.args["trashId"]
                        ?: return@withContext done(false, error = "missing arg: trashId")
                    media.restoreProject(trashId, call.actor).fold(
                        onSuccess = { done(true, "กู้แล้ว ${it.name} (${it.id})") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "project.backup" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.backupProject(projectId, call.actor).fold(
                        onSuccess = { done(true, "แบ็คอัพแล้ว: $it") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "edit.undo" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.undo(projectId, call.actor).fold(
                        onSuccess = { done(true, "เลิกทำแล้ว: $it") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "edit.redo" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.redo(projectId, call.actor).fold(
                        onSuccess = { done(true, "ทำซ้ำแล้ว: $it") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "edit.history" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.history(projectId).fold(
                        onSuccess = { h ->
                            val undo = if (h.undoLabels.isEmpty()) "เลิกทำ: (ว่าง)" else "เลิกทำ:\n" + h.undoLabels.mapIndexed { i, l -> "  ${i + 1}. $l" }.joinToString("\n")
                            val ev = if (h.events.isEmpty()) "เหตุการณ์: (ว่าง)" else "เหตุการณ์:\n" + h.events.takeLast(10).joinToString("\n") { "  ${it.type} (${it.actor})" }
                            done(true, "$undo\nทำซ้ำค้าง: ${h.redoCount}\n$ev")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "checkpoint.save" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.checkpoint(projectId, call.args["reason"] ?: "manual", call.actor).fold(
                        onSuccess = { done(true, "บันทึกเช็คพอยต์แล้ว: $it") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "checkpoint.list" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.listCheckpoints(projectId).fold(
                        onSuccess = { done(true, if (it.isEmpty()) "ยังไม่มีเช็คพอยต์" else it.joinToString("\n") { c -> "${c.id} (${c.reason})" }) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "checkpoint.recover" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.recoverCheckpoint(projectId, call.args["id"], call.actor).fold(
                        onSuccess = { done(true, "กู้เช็คพอยต์แล้ว timeline ${it.timeline.durationMs}ms (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.splitClip" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val at = call.args["atMs"]?.toLongOrNull()
                        ?: return@withContext done(false, error = "missing arg: atMs")
                    media.splitClip(projectId, clipId, at, call.actor).fold(
                        onSuccess = { done(true, "แยกคลิปแล้ว timeline ยาว ${it.timeline.durationMs}ms (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.trimClip" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    media.trimClip(
                        projectId, clipId,
                        call.args["startMs"]?.toLongOrNull(),
                        call.args["endMs"]?.toLongOrNull(),
                        call.args["atMs"]?.toLongOrNull(),
                        call.actor,
                    ).fold(
                        onSuccess = { done(true, "ทริมคลิปแล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.moveClip" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val to = call.args["toAtMs"]?.toLongOrNull()
                        ?: return@withContext done(false, error = "missing arg: toAtMs")
                    media.moveClip(projectId, clipId, to, call.args["toTrack"], call.actor).fold(
                        onSuccess = { done(true, "ย้ายคลิปแล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.deleteClip" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    media.deleteClip(projectId, clipId, call.actor).fold(
                        onSuccess = { done(true, "ลบคลิปแล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.duplicateClip" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    media.duplicateClip(projectId, clipId, call.args["atMs"]?.toLongOrNull(), call.actor).fold(
                        onSuccess = { done(true, "สำเนาคลิปแล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.transformClip" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val base = timeline.findClip(clipId)?.second?.transform ?: ClipTransform()
                    val crop = call.args["crop"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                    val pos = call.args["pos"]?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
                    val next = base.copy(
                        rotation = call.args["rotation"]?.toIntOrNull() ?: base.rotation,
                        flipH = parseFlag(call.args["flipH"]) ?: base.flipH,
                        flipV = parseFlag(call.args["flipV"]) ?: base.flipV,
                        cropX = crop?.getOrNull(0) ?: base.cropX,
                        cropY = crop?.getOrNull(1) ?: base.cropY,
                        cropW = crop?.getOrNull(2) ?: base.cropW,
                        cropH = crop?.getOrNull(3) ?: base.cropH,
                        scale = call.args["scale"]?.toIntOrNull() ?: base.scale,
                        posX = pos?.getOrNull(0) ?: base.posX,
                        posY = pos?.getOrNull(1) ?: base.posY,
                        opacity = call.args["opacity"]?.toIntOrNull() ?: base.opacity,
                    )
                    media.transformClip(projectId, clipId, next, call.actor).fold(
                        onSuccess = { done(true, "เปลี่ยนภาพคลิปแล้ว (${next.summary().ifBlank { "ปกติ" }}) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.freezeFrame" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    media.freezeFrame(
                        projectId, clipId,
                        call.args["frameMs"]?.toLongOrNull(),
                        call.args["holdMs"]?.toLongOrNull() ?: 2000,
                        call.actor,
                    ).fold(
                        onSuccess = { done(true, "ฟรีซเฟรมแล้ว timeline ยาว ${it.timeline.durationMs}ms (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.addText" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val text = call.args["text"]
                        ?: return@withContext done(false, error = "missing arg: text")
                    val base = OverlayText.preset(call.args["preset"] ?: "caption")
                    val duration = ((media.getTimeline(projectId) as? Outcome.Success)?.value?.durationMs ?: 0)
                    val overlay = base.copy(
                        id = Ids.newId("text"),
                        text = text,
                        startMs = call.args["startMs"]?.toLongOrNull() ?: 0,
                        endMs = call.args["endMs"]?.toLongOrNull()
                            ?: if (duration > 0) duration else 3000,
                        xPct = call.args["x"]?.toIntOrNull() ?: base.xPct,
                        yPct = call.args["y"]?.toIntOrNull() ?: base.yPct,
                        sizePct = call.args["size"]?.toIntOrNull() ?: base.sizePct,
                        color = call.args["color"]?.let { parseColor(it) } ?: base.color,
                        align = call.args["align"] ?: base.align,
                        bold = parseFlag(call.args["bold"]) ?: base.bold,
                        opacity = call.args["opacity"]?.toIntOrNull() ?: base.opacity,
                        animIn = call.args["animIn"] ?: base.animIn,
                        animOut = call.args["animOut"] ?: base.animOut,
                    )
                    media.addText(projectId, overlay, call.actor).fold(
                        onSuccess = { done(true, "เพิ่มข้อความแล้ว (${overlay.summary()}) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.updateText" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val id = resolveText(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: textId/textIndex (ดูเลขจาก timeline.get)")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val base = timeline.texts.firstOrNull { it.id == id }
                        ?: return@withContext done(false, error = "ไม่มีข้อความ $id")
                    val next = base.copy(
                        text = call.args["text"] ?: base.text,
                        startMs = call.args["startMs"]?.toLongOrNull() ?: base.startMs,
                        endMs = call.args["endMs"]?.toLongOrNull() ?: base.endMs,
                        xPct = call.args["x"]?.toIntOrNull() ?: base.xPct,
                        yPct = call.args["y"]?.toIntOrNull() ?: base.yPct,
                        sizePct = call.args["size"]?.toIntOrNull() ?: base.sizePct,
                        color = call.args["color"]?.let { parseColor(it) } ?: base.color,
                        align = call.args["align"] ?: base.align,
                        bold = parseFlag(call.args["bold"]) ?: base.bold,
                        opacity = call.args["opacity"]?.toIntOrNull() ?: base.opacity,
                        animIn = call.args["animIn"] ?: base.animIn,
                        animOut = call.args["animOut"] ?: base.animOut,
                    )
                    media.updateText(projectId, id, next, call.actor).fold(
                        onSuccess = { done(true, "แก้ข้อความแล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.removeText" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val id = resolveText(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: textId/textIndex (ดูเลขจาก timeline.get)")
                    media.removeText(projectId, id, call.actor).fold(
                        onSuccess = { done(true, "ลบข้อความแล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "text.ideas" -> {
                    val kind = call.args["kind"] ?: "caption"
                    val topic = call.args["topic"] ?: ""
                    val ideas = TextIdeas.ideas(kind, topic, call.args["platform"])
                    done(true, ideas.mapIndexed { i, idea -> "${i + 1}. $idea" }.joinToString("\n"))
                }
                "timeline.setSpeed" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val base = timeline.findClip(clipId)?.second?.speed ?: ClipSpeed()
                    val curveRaw = call.args["curve"]
                    val curve = when {
                        curveRaw == null -> base.curve
                        curveRaw == "none" || curveRaw == "off" -> emptyList()
                        "," in curveRaw -> curveRaw.split(",").mapNotNull { part ->
                            val kv = part.split(":").map { it.trim().toIntOrNull() ?: return@mapNotNull null }
                            if (kv.size == 2) SpeedPoint(kv[0], kv[1]) else null
                        }
                        else -> ClipSpeed.preset(curveRaw)
                    }
                    val next = base.copy(
                        rate = call.args["rate"]?.toIntOrNull() ?: base.rate,
                        reverse = parseFlag(call.args["reverse"]) ?: base.reverse,
                        curve = curve,
                    )
                    media.setClipSpeed(projectId, clipId, next, call.actor).fold(
                        onSuccess = { done(true, "ตั้งความเร็วแล้ว (${next.summary().ifBlank { "ปกติ" }}) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.setKeyframe" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val prop = call.args["prop"]
                        ?: return@withContext done(false, error = "missing arg: prop (${ClipKeyframes.PROPS.joinToString("/")})")
                    val at = call.args["atMs"]?.toLongOrNull()
                        ?: return@withContext done(false, error = "missing arg: atMs (มิลลิวินาทีจากต้นคลิป)")
                    val value = call.args["value"]?.toFloatOrNull()
                        ?: return@withContext done(false, error = "missing arg: value")
                    val ease = call.args["ease"] ?: "linear"
                    media.setKeyframe(projectId, clipId, prop, at, value, ease, call.actor).fold(
                        onSuccess = { done(true, "ตั้งคีย์เฟรม $prop=$value @${at}ms ($ease) แล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.removeKeyframe" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val prop = call.args["prop"]
                        ?: return@withContext done(false, error = "missing arg: prop")
                    val at = call.args["atMs"]?.toLongOrNull()
                        ?: return@withContext done(false, error = "missing arg: atMs")
                    media.removeKeyframe(projectId, clipId, prop, at, call.actor).fold(
                        onSuccess = { done(true, "ลบคีย์เฟรม $prop ใกล้ ${at}ms แล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.clearKeyframes" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    media.clearKeyframes(projectId, clipId, call.args["prop"], call.actor).fold(
                        onSuccess = { done(true, "ล้างคีย์เฟรมแล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.addMarker" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val at = call.args["atMs"]?.toLongOrNull()
                        ?: return@withContext done(false, error = "missing arg: atMs")
                    media.addMarker(projectId, at, call.args["label"] ?: "", call.actor).fold(
                        onSuccess = { done(true, "เพิ่มมาร์กเกอร์แล้ว ${it.id} @${it.atMs}ms") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.removeMarker" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val markerId = call.args["markerId"]
                        ?: return@withContext done(false, error = "missing arg: markerId")
                    media.removeMarker(projectId, markerId, call.actor).fold(
                        onSuccess = { done(true, "ลบมาร์กเกอร์แล้ว") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.trackFlags" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val trackId = call.args["trackId"]
                        ?: return@withContext done(false, error = "missing arg: trackId (เช่น V1/A1)")
                    media.setTrackFlags(
                        projectId, trackId,
                        parseFlag(call.args["locked"]), parseFlag(call.args["muted"]),
                        parseFlag(call.args["hidden"]), call.args["color"], call.actor,
                    ).fold(
                        onSuccess = { done(true, "แทร็ก ${it.id}: ล็อก=${it.locked} ปิดเสียง=${it.muted} ซ่อน=${it.hidden}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}'")
            }
        }

    /** clipId direct, or 1-based clipIndex from timeline.get order. */
    private suspend fun resolveText(projectId: String, args: Map<String, String>): String? {
        args["textId"]?.let { return it }
        val index = args["textIndex"]?.toIntOrNull() ?: return null
        val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value ?: return null
        return timeline.texts.getOrNull(index - 1)?.id
    }

    /** Accepts #RRGGBB / #AARRGGBB / decimal long. */
    private fun parseColor(raw: String): Long? = try {
        val hex = raw.trim().removePrefix("#")
        when (hex.length) {
            6 -> ("FF$hex").toLong(16)
            8 -> hex.toLong(16)
            else -> raw.trim().toLongOrNull()
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun resolveClip(projectId: String, args: Map<String, String>): String? {
        args["clipId"]?.let { return it }
        val index = args["clipIndex"]?.toIntOrNull() ?: return null
        val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value ?: return null
        return timeline.orderedClips().getOrNull(index - 1)?.second?.id
    }

    private fun parseFlag(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        null -> null
        "true", "1", "เปิด", "on", "yes" -> true
        "false", "0", "ปิด", "off", "no" -> false
        else -> null
    }

    /** Latest project (by update time) for chat flows that omit projectId. */
    private suspend fun latestProject(): String? = when (val list = media.listProjects()) {
        is Outcome.Failure -> null
        is Outcome.Success -> list.value.firstOrNull()?.id
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
