package com.aicodemax.tools.media_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.data.media.ClipSpeed
import com.aicodemax.data.media.ClipKeyframes
import com.aicodemax.data.media.ClipFx
import com.aicodemax.data.media.ClipColor
import com.aicodemax.data.media.ClipMask
import com.aicodemax.data.media.ClipChroma
import com.aicodemax.data.media.ClipBackground
import com.aicodemax.data.media.ClipLut
import com.aicodemax.data.media.ClipMotion
import com.aicodemax.data.media.ClipTransform
import com.aicodemax.data.media.SpeedPoint
import com.aicodemax.core.common.Ids
import com.aicodemax.data.media.OverlayText
import com.aicodemax.data.media.KeyPoint
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.ProjectEventTypes
import com.aicodemax.data.media.TrackPath
import com.aicodemax.data.media.TrackPoint
import com.aicodemax.tools.media.TextIdeas
import com.aicodemax.tools.media.ColorPort
import com.aicodemax.tools.media.ColorRequest
import com.aicodemax.tools.media.GenKinds
import com.aicodemax.tools.media.GenPort
import com.aicodemax.tools.media.GenRequest
import com.aicodemax.tools.media.InMemoryColorPort
import com.aicodemax.tools.media.InMemoryGenPort
import com.aicodemax.tools.media.InMemoryMediaProject
import com.aicodemax.tools.media.InMemoryTrackingPort
import com.aicodemax.tools.media.MediaProjectPort
import com.aicodemax.tools.media.StabRequest
import com.aicodemax.tools.media.TrackRequest
import com.aicodemax.tools.media.TrackingPort
import com.aicodemax.tools.audio.AudioPort
import com.aicodemax.tools.audio.InMemoryAudioPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for media projects. */
class MediaToolExecutor(
    private val media: MediaProjectPort = InMemoryMediaProject(),
    private val tracking: TrackingPort = InMemoryTrackingPort(),
    private val color: ColorPort = InMemoryColorPort(),
    private val gen: GenPort = InMemoryGenPort(),
    private val audio: AudioPort = InMemoryAudioPort(),
) : ToolExecutor {
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
                                    (clip.keyframes?.takeUnless { it.isEmpty }?.let { " {KF ${it.summary()}}" } ?: "") +
                                    (clip.transitionIn?.let { " {IN:${it.summary()}}" } ?: "") +
                                    (clip.transitionOut?.let { " {OUT:${it.summary()}}" } ?: "") +
                                    (clip.fx?.takeUnless { it.isIdentity }?.let { " {FX:${it.summary()}}" } ?: "") +
                                    (clip.color?.takeUnless { it.isIdentity }?.let { " {C:${it.summary()}}" } ?: "") +
                                    (clip.lut?.let { " {L:${it.summary()}}" } ?: "") +
                                    (clip.motion?.takeUnless { it.isIdentity }?.let { " {M:${it.summary()}}" } ?: "") +
                                    (clip.mask?.takeUnless { it.isIdentity }?.let { " {M:${it.summary()}}" } ?: "") +
                                    (clip.chroma?.let { " {CH:${it.summary()}}" } ?: "")
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
                            val bgLine = timeline.background?.takeUnless { it.isIdentity }?.let { "พื้นหลัง: ${it.summary()}\n" } ?: ""
                            done(true, "timeline ${timeline.durationMs}ms, ${lines.size} คลิป\nแทร็ก: $flags\n" + bgLine + lines.joinToString("\n") + "\n$markers\n$texts")
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
                "timeline.setTransition" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val edge = call.args["edge"] ?: "in"
                    val kind = call.args["kind"] ?: "fade"
                    val dur = call.args["durationMs"]?.toLongOrNull() ?: 500L
                    media.setTransition(projectId, clipId, edge, kind, dur, call.actor).fold(
                        onSuccess = { done(true, "ตั้งทรานซิชัน$edge $kind ${dur}ms แล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.clearTransition" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    media.clearTransition(projectId, clipId, call.args["edge"], call.actor).fold(
                        onSuccess = { done(true, "ล้างทรานซิชันแล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.setFx" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val base = timeline.findClip(clipId)?.second?.fx ?: ClipFx()
                    val next = base.copy(
                        blur = call.args["blur"]?.toIntOrNull() ?: base.blur,
                        vignette = call.args["vignette"]?.toIntOrNull() ?: base.vignette,
                        grain = call.args["grain"]?.toIntOrNull() ?: base.grain,
                    )
                    media.setClipFx(projectId, clipId, next, call.actor).fold(
                        onSuccess = { done(true, "ตั้งเอฟเฟกต์แล้ว (${next.summary().ifBlank { "ปิด" }}) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.setColor" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val presetRaw = call.args["preset"]
                    if (presetRaw != null && presetRaw !in ClipColor.PRESETS) {
                        return@withContext done(false, error = "preset ไม่รู้จัก ($presetRaw) ใช้ ${ClipColor.PRESETS.joinToString("/")}")
                    }
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val fromPreset = presetRaw?.let { ClipColor.preset(it) }
                    val base = fromPreset ?: timeline.findClip(clipId)?.second?.color ?: ClipColor()
                    val next = base.copy(
                        brightness = call.args["brightness"]?.toIntOrNull() ?: base.brightness,
                        contrast = call.args["contrast"]?.toIntOrNull() ?: base.contrast,
                        saturation = call.args["saturation"]?.toIntOrNull() ?: base.saturation,
                        temperature = call.args["temperature"]?.toIntOrNull() ?: base.temperature,
                        tint = call.args["tint"]?.toIntOrNull() ?: base.tint,
                        highlights = call.args["highlights"]?.toIntOrNull() ?: base.highlights,
                        shadows = call.args["shadows"]?.toIntOrNull() ?: base.shadows,
                        hueShift = call.args["hueShift"]?.toIntOrNull() ?: base.hueShift,
                        lightness = call.args["lightness"]?.toIntOrNull() ?: base.lightness,
                        exposure = call.args["exposure"]?.toIntOrNull() ?: base.exposure,
                        whites = call.args["whites"]?.toIntOrNull() ?: base.whites,
                        blacks = call.args["blacks"]?.toIntOrNull() ?: base.blacks,
                    )
                    media.setClipColor(projectId, clipId, next, call.actor).fold(
                        onSuccess = { done(true, "แก้สีแล้ว (${next.summary().ifBlank { "ปกติ" }}) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.lut" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val path = call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: path (ไฟล์ .cube)")
                    if (!path.endsWith(".cube", ignoreCase = true)) {
                        return@withContext done(false, error = "รองรับเฉพาะไฟล์ .cube (3D LUT)")
                    }
                    val strength = call.args["strength"]?.toIntOrNull() ?: 100
                    media.setClipLut(projectId, clipId, ClipLut(path, strength), call.actor).fold(
                        onSuccess = {
                            done(true, "ใส่ LUT แล้ว (${path.substringAfterLast('/')}@$strength%) (เลิกทำได้: edit.undo)")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.lutClear" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    media.setClipLut(projectId, clipId, null, call.actor).fold(
                        onSuccess = { done(true, "ล้าง LUT แล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.colorAuto" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val found = timeline.findClip(clipId)
                        ?: return@withContext done(false, error = "ไม่มีคลิป $clipId")
                    val clip = found.second
                    val assetPath = (media.assetPath(projectId, clip.assetId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "หาไฟล์ต้นฉบับของคลิปไม่เจอ")
                    val mid = clip.startMs + clip.durationMs / 2
                    val outcome = color.analyze(ColorRequest(assetPath, mid))
                    if (outcome is Outcome.Failure) {
                        return@withContext done(false, error = outcome.error.message)
                    }
                    val analysis = (outcome as Outcome.Success).value
                    val base = clip.color ?: ClipColor()
                    val sg = analysis.suggest
                    val next = base.copy(
                        brightness = sg.brightness.takeUnless { it == 0 } ?: base.brightness,
                        contrast = sg.contrast.takeUnless { it == 0 } ?: base.contrast,
                        saturation = sg.saturation.takeUnless { it == 0 } ?: base.saturation,
                        temperature = sg.temperature.takeUnless { it == 0 } ?: base.temperature,
                        tint = sg.tint.takeUnless { it == 0 } ?: base.tint,
                        highlights = sg.highlights.takeUnless { it == 0 } ?: base.highlights,
                        shadows = sg.shadows.takeUnless { it == 0 } ?: base.shadows,
                        exposure = sg.exposure.takeUnless { it == 0 } ?: base.exposure,
                        whites = sg.whites.takeUnless { it == 0 } ?: base.whites,
                        blacks = sg.blacks.takeUnless { it == 0 } ?: base.blacks,
                    )
                    media.setClipColor(projectId, clipId, next, call.actor).fold(
                        onSuccess = {
                            done(true, "ออโต้สีแล้ว (${sg.summary().ifBlank { "ภาพดีอยู่แล้ว" }}): ${analysis.notes} (เลิกทำได้: edit.undo)")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "template.save" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val name = call.args["name"]?.trim()
                        ?: return@withContext done(false, error = "missing arg: name (ชื่อเทมเพลต)")
                    val category = call.args["category"] ?: "custom"
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val allClips = timeline.tracks.flatMap { it.clips }
                    val slots = parsePairs(call.args["slots"]).mapNotNull { (slot, ref) ->
                        val clipId = allClips.find { it.id == ref }?.id
                            ?: ref.toIntOrNull()?.let { n -> allClips.getOrNull(n - 1)?.id }
                        if (clipId == null) null else slot to clipId
                    }.toMap()
                    if (parsePairs(call.args["slots"]).isNotEmpty() && slots.size < parsePairs(call.args["slots"]).size) {
                        return@withContext done(false, error = "slot อ้างคลิปที่ไม่มี (ใช้ clipId หรือเลขคลิปจาก timeline.get)")
                    }
                    media.saveTemplate(name, category, projectId, slots, call.args["description"] ?: "").fold(
                        onSuccess = { done(true, "บันทึกเทมเพลตแล้ว (${it.summary()})") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "template.list" -> {
                    media.listTemplates(call.args["category"]).fold(
                        onSuccess = { list ->
                            if (list.isEmpty()) done(true, "ยังไม่มีเทมเพลต")
                            else done(true, list.joinToString("\n") { "- ${it.id}: ${it.summary()}" })
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "template.apply" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val ref = call.args["templateId"] ?: call.args["name"]
                        ?: return@withContext done(false, error = "missing arg: templateId (ดูจาก template.list)")
                    val templates = (media.listTemplates(null) as? Outcome.Success)?.value.orEmpty()
                    val tpl = templates.find { it.id == ref } ?: templates.find { it.name == ref }
                        ?: return@withContext done(false, error = "ไม่มีเทมเพลต $ref")
                    val replacements = parsePairs(call.args["replacements"])
                    media.applyTemplate(projectId, tpl.id, replacements, call.actor).fold(
                        onSuccess = { done(true, "ใช้เทมเพลต ${tpl.name} แล้ว (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "template.delete" -> {
                    val ref = call.args["templateId"] ?: call.args["name"]
                        ?: return@withContext done(false, error = "missing arg: templateId (ดูจาก template.list)")
                    val templates = (media.listTemplates(null) as? Outcome.Success)?.value.orEmpty()
                    val id = templates.find { it.id == ref }?.id ?: templates.find { it.name == ref }?.id ?: ref
                    media.deleteTemplate(id).fold(
                        onSuccess = { done(true, "ลบเทมเพลตแล้ว") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "library.add" -> {
                    val kind = call.args["kind"]
                        ?: return@withContext done(false, error = "missing arg: kind (effect/filter/transition/...)")
                    val name = call.args["name"]
                        ?: return@withContext done(false, error = "missing arg: name")
                    val tags = call.args["tags"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    media.libraryAdd(kind, name, tags, call.args["ref"] ?: "").fold(
                        onSuccess = { done(true, "เพิ่มเข้าคลังแล้ว (${it.summary()})") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "library.list" -> {
                    media.libraryList(call.args["kind"]).fold(
                        onSuccess = { list ->
                            if (list.isEmpty()) done(true, "คลังยังว่าง")
                            else done(true, list.joinToString("\n") { "- ${it.id}: ${it.summary()}" })
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "library.search" -> {
                    val q = call.args["query"] ?: call.args["q"]
                        ?: return@withContext done(false, error = "missing arg: query")
                    media.librarySearch(q).fold(
                        onSuccess = { list ->
                            if (list.isEmpty()) done(true, "ไม่เจอ \"$q\" ในคลัง")
                            else done(true, list.joinToString("\n") { "- ${it.id}: ${it.summary()}" })
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "library.remove" -> {
                    val id = call.args["itemId"]
                        ?: return@withContext done(false, error = "missing arg: itemId")
                    media.libraryRemove(id).fold(
                        onSuccess = { done(true, "ลบออกจากคลังแล้ว") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "gen.list" -> {
                    val caps = gen.list()
                    done(true, caps.joinToString("\n") { c ->
                        "- ${c.kind}: ${c.title}" + if (c.available) "" else " (ยังใช้ไม่ได้: ${c.note})"
                    })
                }
                "gen.make" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val kind = call.args["kind"]?.lowercase()
                        ?: return@withContext done(false, error = "missing arg: kind (poster/background/stylize/tts)")
                    if (kind !in GenKinds.ALL) {
                        return@withContext done(false, error = "kind ไม่รู้จัก ($kind) ใช้ ${GenKinds.ALL.joinToString("/")}")
                    }
                    val req = GenRequest(
                        kind = kind,
                        prompt = call.args["prompt"] ?: "",
                        inputPath = call.args["path"] ?: "",
                        width = call.args["w"]?.toIntOrNull() ?: 1280,
                        height = call.args["h"]?.toIntOrNull() ?: 720,
                        style = call.args["style"] ?: "",
                        lang = call.args["lang"] ?: "th-TH",
                    )
                    val outcome = gen.generate(req)
                    if (outcome is Outcome.Failure) {
                        return@withContext done(false, error = outcome.error.message)
                    }
                    val result = (outcome as Outcome.Success).value
                    media.importAsset(projectId, result.path, call.actor).fold(
                        onSuccess = { asset ->
                            done(true, "สร้างแล้ว: ${asset.id} (${result.note}) — เพิ่มลงไทม์ไลน์ด้วย timeline.addClip assetId=${asset.id}")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.motion" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    if (call.args["off"] == "true") {
                        return@withContext media.setClipMotion(projectId, clipId, null, call.actor).fold(
                            onSuccess = { done(true, "ปิดโมชันแล้ว (เลิกทำได้: edit.undo)") },
                            onFailure = { done(false, error = it.message) },
                        )
                    }
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val base = timeline.findClip(clipId)?.second?.motion ?: ClipMotion()
                    val next = base.copy(
                        direction = call.args["dir"] ?: base.direction,
                        zoom = call.args["zoom"]?.toIntOrNull() ?: base.zoom,
                    )
                    media.setClipMotion(projectId, clipId, next, call.actor).fold(
                        onSuccess = { done(true, "ตั้งโมชันแล้ว (${next.summary()}) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.slideshow" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val raw = call.args["assetIds"] ?: call.args["assets"]
                        ?: return@withContext done(false, error = "missing arg: assetIds (คั่นด้วยจุลภาค)")
                    val refs = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val assets = ((media.listAssets(projectId) as? Outcome.Success)?.value.orEmpty())
                    val ids = refs.mapNotNull { ref ->
                        assets.find { it.id == ref }?.id
                            ?: assets.find { it.originalName == ref || it.originalName.endsWith("/$ref") }?.id
                    }
                    if (ids.size < refs.size) {
                        val known = assets.map { it.originalName }
                        return@withContext done(false, error = "หารูปไม่เจอ (มีในโปรเจกต์: ${known.joinToString(", ").ifBlank { "—" }})")
                    }
                    val stillMs = call.args["stillMs"]?.toLongOrNull() ?: 3000L
                    val fadeMs = call.args["fadeMs"]?.toLongOrNull() ?: 400L
                    media.slideshow(projectId, ids, stillMs, fadeMs, call.actor).fold(
                        onSuccess = { done(true, "ทำสไลด์โชว์แล้ว (${ids.size} รูป ต่อรูป ${stillMs}ms) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.volume" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val volume = call.args["volume"]?.toIntOrNull()
                        ?: return@withContext done(false, error = "missing arg: volume (0..100)")
                    media.setClipVolume(projectId, clipId, volume, call.actor).fold(
                        onSuccess = { done(true, "ตั้งเสียงแล้ว ($volume) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.beatsToMarkers" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val clip = timeline.findClip(clipId)?.second
                        ?: return@withContext done(false, error = "ไม่มีคลิป $clipId")
                    val assetPath = (media.assetPath(projectId, clip.assetId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "หาไฟล์ต้นฉบับของคลิปไม่เจอ")
                    val beats = audio.beats(assetPath)
                    if (beats is Outcome.Failure) {
                        return@withContext done(false, error = beats.error.message)
                    }
                    val analysis = (beats as Outcome.Success).value
                    if (analysis.bpm <= 0 || analysis.beatsMs.isEmpty()) {
                        return@withContext done(true, "จับจังหวะไม่ได้ (สัญญาณไม่มีพัลส์ชัด)")
                    }
                    val markers = analysis.beatsMs.filter { it in clip.startMs until clip.endMs }.take(200).mapIndexed { i, ms ->
                        com.aicodemax.data.media.TimelineMarker(
                            Ids.newId("mark"), clip.atMs + clip.sourceToOutput(ms - clip.startMs), "บีต${i + 1}",
                        )
                    }
                    if (markers.isEmpty()) {
                        return@withContext done(true, "%.0f BPM แต่บีตอยู่นอกช่วงคลิป".format(analysis.bpm))
                    }
                    media.addMarkers(projectId, markers, call.actor).fold(
                        onSuccess = { done(true, "%.0f BPM วางมาร์กเกอร์แล้ว ${markers.size} จุด (เลิกทำได้: edit.undo)".format(analysis.bpm)) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.setMask" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val base = timeline.findClip(clipId)?.second?.mask ?: ClipMask()
                    val rectOnly = call.args["x"] == null && call.args["y"] == null &&
                        call.args["w"] == null && call.args["h"] == null
                    val seed = if (rectOnly && base.isIdentity && (call.args["shape"] ?: base.shape) == "rect") {
                        base.copy(x = 20, y = 20, w = 60, h = 60)
                    } else {
                        base
                    }
                    val next = seed.copy(
                        shape = call.args["shape"] ?: seed.shape,
                        x = call.args["x"]?.toIntOrNull() ?: seed.x,
                        y = call.args["y"]?.toIntOrNull() ?: seed.y,
                        w = call.args["w"]?.toIntOrNull() ?: seed.w,
                        h = call.args["h"]?.toIntOrNull() ?: seed.h,
                        feather = call.args["feather"]?.toIntOrNull() ?: seed.feather,
                        invert = parseFlag(call.args["invert"]) ?: seed.invert,
                    )
                    media.setClipMask(projectId, clipId, next, call.actor).fold(
                        onSuccess = { done(true, "ตั้งมาสก์แล้ว (${next.summary()}) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.setChroma" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    if (parseFlag(call.args["off"]) == true) {
                        return@withContext media.setClipChroma(projectId, clipId, null, call.actor).fold(
                            onSuccess = { done(true, "ปิด chroma แล้ว (เลิกทำได้: edit.undo)") },
                            onFailure = { done(false, error = it.message) },
                        )
                    }
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val base = timeline.findClip(clipId)?.second?.chroma ?: ClipChroma()
                    val next = base.copy(
                        hue = call.args["hue"]?.toIntOrNull() ?: base.hue,
                        tolerance = call.args["tolerance"]?.toIntOrNull() ?: base.tolerance,
                        softness = call.args["softness"]?.toIntOrNull() ?: base.softness,
                        despill = call.args["despill"]?.toIntOrNull() ?: base.despill,
                    )
                    media.setClipChroma(projectId, clipId, next, call.actor).fold(
                        onSuccess = { done(true, "ตั้ง chroma แล้ว (${next.summary()}) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.setBackground" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val mode = call.args["mode"] ?: "black"
                    val base = timeline.background ?: ClipBackground()
                    val next = if (mode == "black" || mode == "off") {
                        null
                    } else {
                        base.copy(
                            mode = mode,
                            color = call.args["color"] ?: base.color,
                            blur = call.args["blur"]?.toIntOrNull() ?: base.blur,
                            assetId = call.args["assetId"] ?: base.assetId,
                        )
                    }
                    media.setBackground(projectId, next, call.actor).fold(
                        onSuccess = { done(true, "ตั้งพื้นหลังแล้ว (${next?.summary() ?: "black"}) (เลิกทำได้: edit.undo)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "timeline.track" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val (track, clip) = timeline.findClip(clipId)
                        ?: return@withContext done(false, error = "ไม่พบคลิป $clipId")
                    if (track.kind != MediaKind.VIDEO) {
                        return@withContext done(false, error = "แทร็กได้เฉพาะคลิปวิดีโอ")
                    }
                    val asset = ((media.listAssets(projectId) as? Outcome.Success)?.value?.firstOrNull { it.id == clip.assetId })
                        ?: return@withContext done(false, error = "ไม่พบ asset ของคลิป")
                    if (asset.kind != MediaKind.VIDEO) {
                        return@withContext done(false, error = "cl asset ไม่ใช่วิดีโอ")
                    }
                    val path = (media.assetPath(projectId, asset.id) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไฟล์ asset ไม่ได้")
                    val srcStart = call.args["startMs"]?.toLongOrNull()?.coerceIn(clip.startMs, clip.endMs) ?: clip.startMs
                    val srcEnd = call.args["endMs"]?.toLongOrNull()?.coerceIn(srcStart, clip.endMs) ?: clip.endMs
                    if (srcEnd - srcStart < 300) {
                        return@withContext done(false, error = "ช่วงแทร็กสั้นไป (อย่างน้อย 300ms)")
                    }
                    val req = TrackRequest(
                        assetPath = path,
                        x = call.args["x"]?.toIntOrNull() ?: 40,
                        y = call.args["y"]?.toIntOrNull() ?: 40,
                        w = call.args["w"]?.toIntOrNull() ?: 20,
                        h = call.args["h"]?.toIntOrNull() ?: 20,
                        startMs = srcStart,
                        endMs = srcEnd,
                        stepMs = call.args["stepMs"]?.toLongOrNull() ?: 250,
                    )
                    if (req.x !in 0..100 || req.y !in 0..100 || req.w !in 1..100 || req.h !in 1..100) {
                        return@withContext done(false, error = "กรอบแทร็กต้อง x/y 0..100, w/h 1..100")
                    }
                    val trackOutcome = tracking.analyzeTrack(req)
                    val analysis = (trackOutcome as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = (trackOutcome as Outcome.Failure).error.message)
                    val target = call.args["target"] ?: "self"
                    val shift = (clip.speed?.sourceToOutput(srcStart - clip.startMs, clip.endMs - clip.startMs) ?: (srcStart - clip.startMs))
                    val result = when {
                        target == "self" -> {
                            val (kx, ky, note) = trackToKeys(analysis.path, shift, clip.outputDurationMs())
                            media.applyTrackPath(projectId, clipId, kx, ky, 0, ProjectEventTypes.TRACK_APPLIED, "แทร็ก", call.actor).fold(
                                onSuccess = { "ขยับตามวัตถุ (คีย์ ${kx.size} จุด)$note" },
                                onFailure = { return@withContext done(false, error = it.message) },
                            )
                        }
                        target.startsWith("clip:") -> {
                            val n = target.removePrefix("clip:").toIntOrNull()
                                ?: return@withContext done(false, error = "target ต้องเป็น self/clip:N/text:N")
                            val timeline2 = (media.getTimeline(projectId) as? Outcome.Success)?.value
                                ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                            val dest = timeline2.orderedClips().getOrNull(n - 1)?.second
                                ?: return@withContext done(false, error = "ไม่มีคลิปที่ $n")
                            val (kx, ky, note) = trackToKeys(analysis.path, 0, dest.outputDurationMs())
                            media.applyTrackPath(projectId, dest.id, kx, ky, 0, ProjectEventTypes.TRACK_APPLIED, "แทร็ก", call.actor).fold(
                                onSuccess = { "ใช้กับคลิปที่ $n (คีย์ ${kx.size} จุด)$note" },
                                onFailure = { return@withContext done(false, error = it.message) },
                            )
                        }
                        target.startsWith("text:") -> {
                            val n = target.removePrefix("text:").toIntOrNull()
                                ?: return@withContext done(false, error = "target ต้องเป็น self/clip:N/text:N")
                            val timeline2 = (media.getTimeline(projectId) as? Outcome.Success)?.value
                                ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                            val text = timeline2.texts.getOrNull(n - 1)
                                ?: return@withContext done(false, error = "ไม่มีข้อความที่ $n")
                            val abs = TrackPath(analysis.path.points.map { it.copy(atMs = it.atMs + clip.atMs + shift) })
                            val problems = abs.validate()
                            if (problems.isNotEmpty()) return@withContext done(false, error = problems.joinToString("; "))
                            media.updateText(projectId, text.id, text.copy(follow = abs), call.actor).fold(
                                onSuccess = { "ข้อความที่ $n ตามวัตถุแล้ว" },
                                onFailure = { return@withContext done(false, error = it.message) },
                            )
                        }
                        else -> return@withContext done(false, error = "target ต้องเป็น self/clip:N/text:N")
                    }
                    done(true, "แทร็กแล้ว: ${analysis.path.summary()} (${analysis.samples} ตัวอย่าง, หลุด ${analysis.lost}) → $result (เลิกทำได้: edit.undo)")
                }
                "timeline.stabilize" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    val clipId = resolveClip(projectId, call.args)
                        ?: return@withContext done(false, error = "missing arg: clipId/clipIndex (ดูเลขคลิปจาก timeline.get)")
                    val timeline = (media.getTimeline(projectId) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไทม์ไลน์ไม่ได้")
                    val (track, clip) = timeline.findClip(clipId)
                        ?: return@withContext done(false, error = "ไม่พบคลิป $clipId")
                    if (track.kind != MediaKind.VIDEO) {
                        return@withContext done(false, error = "กันสั่นได้เฉพาะคลิปวิดีโอ")
                    }
                    val asset = ((media.listAssets(projectId) as? Outcome.Success)?.value?.firstOrNull { it.id == clip.assetId })
                        ?: return@withContext done(false, error = "ไม่พบ asset ของคลิป")
                    if (asset.kind != MediaKind.VIDEO) {
                        return@withContext done(false, error = "asset ไม่ใช่วิดีโอ")
                    }
                    val path = (media.assetPath(projectId, asset.id) as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = "อ่านไฟล์ asset ไม่ได้")
                    val srcStart = call.args["startMs"]?.toLongOrNull()?.coerceIn(clip.startMs, clip.endMs) ?: clip.startMs
                    val srcEnd = call.args["endMs"]?.toLongOrNull()?.coerceIn(srcStart, clip.endMs) ?: clip.endMs
                    if (srcEnd - srcStart < 500) {
                        return@withContext done(false, error = "ช่วงกันสั่นสั้นไป (อย่างน้อย 500ms)")
                    }
                    val req = StabRequest(
                        assetPath = path,
                        startMs = srcStart,
                        endMs = srcEnd,
                        stepMs = call.args["stepMs"]?.toLongOrNull() ?: 200,
                        smoothMs = call.args["smoothMs"]?.toLongOrNull() ?: 600,
                        zoom = call.args["zoom"]?.toIntOrNull(),
                    )
                    val stabOutcome = tracking.analyzeStab(req)
                    val analysis = (stabOutcome as? Outcome.Success)?.value
                        ?: return@withContext done(false, error = (stabOutcome as Outcome.Failure).error.message)
                    val shift = (clip.speed?.sourceToOutput(srcStart - clip.startMs, clip.endMs - clip.startMs) ?: (srcStart - clip.startMs))
                    val (kx, ky, note) = trackToKeys(analysis.path, shift, clip.outputDurationMs())
                    media.applyTrackPath(projectId, clipId, kx, ky, analysis.zoom, ProjectEventTypes.STAB_APPLIED, "กันสั่น", call.actor).fold(
                        onSuccess = { done(true, "กันสั่นแล้ว: สั่น %.1f%% ซูม ${analysis.zoom}%% (คีย์ ${kx.size} จุด)$note (เลิกทำได้: edit.undo)".format(analysis.shakePct)) },
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

    /**
     * CP-80: % path → posX/posY keyframes (720p reference: 1280x720).
     * Times shifted by [shiftMs], points past [clipLenMs] dropped (noted).
     */
    private fun trackToKeys(path: TrackPath, shiftMs: Long, clipLenMs: Long): Triple<List<KeyPoint>, List<KeyPoint>, String> {
        var dropped = 0
        var clamped = false
        val kx = mutableListOf<KeyPoint>()
        val ky = mutableListOf<KeyPoint>()
        for (p in path.points.sortedBy { it.atMs }) {
            val at = p.atMs + shiftMs
            if (at < 0 || at > clipLenMs) {
                dropped += 1
                continue
            }
            val x = (p.dx * 1280f / 100f).toInt()
            val y = (p.dy * 720f / 100f).toInt()
            if (x !in -4000..4000 || y !in -4000..4000) clamped = true
            kx += KeyPoint(at, x.coerceIn(-4000, 4000).toFloat())
            ky += KeyPoint(at, y.coerceIn(-4000, 4000).toFloat())
        }
        var note = ""
        if (dropped > 0) note += " (ตัด $dropped จุดที่เกินคลิป)"
        if (clamped) note += " (clamp ±4000px)"
        return Triple(kx, ky, note)
    }

    private fun parseFlag(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
        null -> null
        "true", "1", "เปิด", "on", "yes" -> true
        "false", "0", "ปิด", "off", "no" -> false
        else -> null
    }

    /** Latest project (by update time) for chat flows that omit projectId. */
    private fun parsePairs(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { part ->
            val kv = part.split(Regex("[=:]"), limit = 2)
            if (kv.size < 2 || kv[0].isBlank() || kv[1].isBlank()) null else kv[0].trim() to kv[1].trim()
        }.toMap()
    }

    private suspend fun latestProject(): String? = when (val list = media.listProjects()) {
        is Outcome.Failure -> null
        is Outcome.Success -> list.value.firstOrNull()?.id
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
