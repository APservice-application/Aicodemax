package com.aicodemax.tools.media

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.data.media.CheckpointMeta
import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.ClipTransform
import com.aicodemax.data.media.FileCheckpointStore
import com.aicodemax.data.media.FileEventLog
import com.aicodemax.data.media.FileMediaStore
import com.aicodemax.data.media.FileProjectStore
import com.aicodemax.data.media.FileUndoStore
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.ProjectEvent
import com.aicodemax.data.media.ProjectEventTypes
import com.aicodemax.data.media.ProjectSnapshot
import com.aicodemax.data.media.Timeline
import com.aicodemax.data.media.TimelineMarker
import com.aicodemax.data.media.ClipSpeed
import com.aicodemax.data.media.ClipFx
import com.aicodemax.data.media.ClipColor
import com.aicodemax.data.media.ClipMask
import com.aicodemax.data.media.ClipLut
import com.aicodemax.data.media.ClipMotion
import com.aicodemax.data.media.ClipChroma
import com.aicodemax.data.media.ClipBackground
import com.aicodemax.data.media.LibraryItem
import com.aicodemax.data.media.ProjectTemplate
import com.aicodemax.data.media.TemplateSlot
import com.aicodemax.data.media.KeyPoint
import com.aicodemax.data.media.OverlayText
import com.aicodemax.data.media.TimelineOps
import com.aicodemax.data.media.Track
import com.aicodemax.data.media.UndoEntry
import java.io.File

/**
 * CP-64 project contract + CP-71 transactions/undo/management.
 * Every mutating op is a transaction: snapshot → mutate → (push undo +
 * event) or rollback (§37/§87/§110/§111). File-backed impl ships on JVM
 * and Android (plain file I/O).
 */
interface MediaProjectPort {
    suspend fun createProject(name: String, actor: String = "AI"): Outcome<Project>
    suspend fun listProjects(): Outcome<List<Project>>
    suspend fun openProject(projectId: String): Outcome<Project>
    suspend fun importAsset(projectId: String, path: String, actor: String = "AI"): Outcome<MediaAsset>
    suspend fun listAssets(projectId: String): Outcome<List<MediaAsset>>
    // CP-80 absolute asset file path (for frame analysis).
    suspend fun assetPath(projectId: String, assetId: String): Outcome<String>
    suspend fun removeAsset(projectId: String, assetId: String, actor: String = "AI"): Outcome<Unit>
    suspend fun getTimeline(projectId: String): Outcome<Timeline>
    suspend fun setTimeline(projectId: String, timeline: Timeline, actor: String = "AI"): Outcome<Project>
    suspend fun addClip(
        projectId: String,
        assetId: String,
        startMs: Long,
        endMs: Long,
        atMs: Long,
        volume: Int = 100,
        actor: String = "AI",
    ): Outcome<Project>
    // CP-72 clip + marker + track-flag ops.
    suspend fun splitClip(projectId: String, clipId: String, atMs: Long, actor: String = "AI"): Outcome<Project>
    suspend fun trimClip(
        projectId: String, clipId: String, startMs: Long?, endMs: Long?, atMs: Long?,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun moveClip(
        projectId: String, clipId: String, toAtMs: Long, toTrack: String? = null,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun deleteClip(projectId: String, clipId: String, actor: String = "AI"): Outcome<Project>
    suspend fun duplicateClip(
        projectId: String, clipId: String, atMs: Long? = null, actor: String = "AI",
    ): Outcome<Project>
    // CP-73 basic video ops (§12).
    suspend fun transformClip(
        projectId: String,
        clipId: String,
        transform: ClipTransform,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun freezeFrame(
        projectId: String,
        clipId: String,
        frameMs: Long? = null,
        holdMs: Long = 2000,
        actor: String = "AI",
    ): Outcome<Project>
    // CP-74 text overlays (§22).
    suspend fun addText(projectId: String, overlay: OverlayText, actor: String = "AI"): Outcome<Project>
    suspend fun updateText(projectId: String, id: String, overlay: OverlayText, actor: String = "AI"): Outcome<Project>
    suspend fun removeText(projectId: String, id: String, actor: String = "AI"): Outcome<Project>
    // CP-75 clip speed (§13).
    suspend fun setClipSpeed(
        projectId: String,
        clipId: String,
        speed: ClipSpeed,
        actor: String = "AI",
    ): Outcome<Project>
    // CP-76 keyframes (§14).
    suspend fun setKeyframe(
        projectId: String,
        clipId: String,
        prop: String,
        atMs: Long,
        value: Float,
        ease: String = "linear",
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun removeKeyframe(
        projectId: String,
        clipId: String,
        prop: String,
        atMs: Long,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun clearKeyframes(
        projectId: String,
        clipId: String,
        prop: String? = null,
        actor: String = "AI",
    ): Outcome<Project>
    // CP-77 transitions + basic fx (§21/§20).
    suspend fun setTransition(
        projectId: String,
        clipId: String,
        edge: String,
        kind: String,
        durationMs: Long = 500,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun clearTransition(
        projectId: String,
        clipId: String,
        edge: String? = null,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun setClipFx(
        projectId: String,
        clipId: String,
        fx: ClipFx,
        actor: String = "AI",
    ): Outcome<Project>
    // CP-80 motion path (§15/§43).
    suspend fun applyTrackPath(
        projectId: String,
        clipId: String,
        posX: List<KeyPoint>,
        posY: List<KeyPoint>,
        zoomBump: Int = 0,
        event: String = ProjectEventTypes.TRACK_APPLIED,
        label: String = "แทร็ก",
        actor: String = "AI",
    ): Outcome<Project>
    // CP-79 mask + chroma + background (§17/§18/§19).
    suspend fun setClipMask(
        projectId: String,
        clipId: String,
        mask: ClipMask,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun setClipChroma(
        projectId: String,
        clipId: String,
        chroma: ClipChroma?,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun setBackground(
        projectId: String,
        background: ClipBackground?,
        actor: String = "AI",
    ): Outcome<Project>
    // CP-81 LUT (§42 Pro).
    suspend fun setClipLut(
        projectId: String,
        clipId: String,
        lut: ClipLut?,
        actor: String = "AI",
    ): Outcome<Project>
    // CP-82 templates (§51/§52) + asset library (§53).
    suspend fun saveTemplate(
        name: String,
        category: String,
        projectId: String,
        slots: Map<String, String>,
        description: String = "",
    ): Outcome<ProjectTemplate>
    suspend fun listTemplates(category: String? = null): Outcome<List<ProjectTemplate>>
    suspend fun getTemplate(templateId: String): Outcome<ProjectTemplate>
    suspend fun deleteTemplate(templateId: String): Outcome<Unit>
    suspend fun applyTemplate(
        projectId: String,
        templateId: String,
        replacements: Map<String, String>,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun libraryAdd(kind: String, name: String, tags: List<String>, ref: String): Outcome<LibraryItem>
    suspend fun libraryList(kind: String? = null): Outcome<List<LibraryItem>>
    suspend fun librarySearch(query: String): Outcome<List<LibraryItem>>
    suspend fun libraryRemove(itemId: String): Outcome<Unit>
    // CP-84 Ken Burns + slideshow (§45).
    suspend fun setClipMotion(
        projectId: String,
        clipId: String,
        motion: ClipMotion?,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun slideshow(
        projectId: String,
        assetIds: List<String>,
        stillMs: Long = 3000,
        fadeMs: Long = 400,
        actor: String = "AI",
    ): Outcome<Project>
    // CP-85 mixer + beat markers (§31/§102).
    suspend fun setClipVolume(projectId: String, clipId: String, volume: Int, actor: String = "AI"): Outcome<Project>
    suspend fun setCanvas(projectId: String, canvas: String, actor: String = "AI"): Outcome<Project>
    suspend fun addMarkers(projectId: String, markers: List<TimelineMarker>, actor: String = "AI"): Outcome<Project>
    // CP-87 autocut (§32).
    suspend fun autocutClip(projectId: String, clipId: String, keep: List<Pair<Long, Long>>, actor: String = "AI"): Outcome<Project>
    // CP-78 color correction (§42).
    suspend fun setClipColor(
        projectId: String,
        clipId: String,
        color: ClipColor,
        actor: String = "AI",
    ): Outcome<Project>
    suspend fun addMarker(projectId: String, atMs: Long, label: String = "", actor: String = "AI"): Outcome<TimelineMarker>
    suspend fun removeMarker(projectId: String, markerId: String, actor: String = "AI"): Outcome<Unit>
    suspend fun setTrackFlags(
        projectId: String, trackId: String, locked: Boolean?, muted: Boolean?, hidden: Boolean?,
        color: String? = null, actor: String = "AI",
    ): Outcome<Track>
    suspend fun saveVersion(projectId: String, actor: String = "AI"): Outcome<Int>
    suspend fun listVersions(projectId: String): Outcome<List<Int>>
    suspend fun restoreVersion(projectId: String, version: Int, actor: String = "AI"): Outcome<Project>

    // CP-71 management.
    suspend fun renameProject(projectId: String, name: String, actor: String = "AI"): Outcome<Project>
    suspend fun duplicateProject(projectId: String, actor: String = "AI"): Outcome<Project>
    suspend fun deleteProject(projectId: String, actor: String = "AI"): Outcome<String>
    suspend fun listTrash(): Outcome<List<String>>
    suspend fun restoreProject(trashId: String, actor: String = "AI"): Outcome<Project>
    suspend fun backupProject(projectId: String, actor: String = "AI"): Outcome<String>

    // CP-71 undo/redo + history + checkpoints.
    suspend fun undo(projectId: String, actor: String = "AI"): Outcome<String>
    suspend fun redo(projectId: String, actor: String = "AI"): Outcome<String>
    suspend fun history(projectId: String): Outcome<ProjectHistory>
    suspend fun checkpoint(projectId: String, reason: String, actor: String = "AI"): Outcome<String>
    suspend fun listCheckpoints(projectId: String): Outcome<List<CheckpointMeta>>
    suspend fun recoverCheckpoint(projectId: String, id: String? = null, actor: String = "AI"): Outcome<Project>

    /**
     * Runs [block] as ONE undoable transaction (§37): nested mutating calls
     * share the outer snapshot; failure rolls everything back.
     */
    suspend fun <T> runTransaction(
        projectId: String,
        label: String,
        actor: String,
        block: suspend MediaProjectPort.() -> Outcome<T>,
    ): Outcome<T>
}

/** Undo stack labels + recent events for UI/chat history views. */
data class ProjectHistory(
    val undoLabels: List<String>,
    val redoCount: Int,
    val events: List<ProjectEvent>,
)

/** File-backed projects with probe facts from the media engines. */
/** CP-82: builds a portable template from a project timeline (slot clips → slot: markers). */
private fun buildTemplate(
    name: String,
    category: String,
    timeline: Timeline,
    slots: Map<String, String>,
    description: String,
): ProjectTemplate? {
    val byClip = timeline.tracks.flatMap { track -> track.clips.map { it.id to track.kind } }.toMap()
    for (clipId in slots.values) {
        if (clipId !in byClip) return null
    }
    val slotByClip = slots.entries.associate { (slot, clip) -> clip to slot }
    val rewritten = timeline.copy(
        tracks = timeline.tracks.map { track ->
            track.copy(
                clips = track.clips.map { clip ->
                    val slot = slotByClip[clip.id]
                    if (slot != null) clip.copy(assetId = ProjectTemplate.placeholderAsset(slot)) else clip
                },
            )
        },
    )
    val slotObjs = slots.map { (slot, clip) ->
        TemplateSlot(slot, clip, byClip.getValue(clip), slot)
    }
    return ProjectTemplate("", name.trim().ifBlank { "Untitled" }, category, description, rewritten, slotObjs)
}

class FileMediaProject(
    root: File,
    private val images: com.aicodemax.tools.image.ImagePort,
    private val audio: com.aicodemax.tools.audio.AudioPort,
    private val video: com.aicodemax.tools.video.VideoPort,
    private val clock: Clock = SystemClock,
) : MediaProjectPort {
    private val rootDir = root
    private val projects = FileProjectStore(root, clock)
    private val assets = FileMediaStore(root, clock)
    private val events = FileEventLog(root)
    private val undoStore = FileUndoStore(root)
    private val checkpoints = FileCheckpointStore(root)
    private val templates = FileTemplateStore(root, clock)
    private val library = FileLibraryStore(root, clock)
    private val txDepth = mutableMapOf<String, Int>()

    override suspend fun createProject(name: String, actor: String): Outcome<Project> {
        return when (val created = projects.create(name)) {
            is Outcome.Failure -> created
            is Outcome.Success -> {
                val project = created.value
                undoStore.push(project.id, "สร้างโปรเจกต์ ${project.name}", actor, ProjectSnapshot(null))
                events.append(
                    ProjectEvent(
                        Ids.newId("ev"), ProjectEventTypes.PROJECT_CREATED, project.id,
                        clock.nowMillis(), actor, mapOf("name" to project.name),
                    ),
                )
                created
            }
        }
    }

    override suspend fun listProjects(): Outcome<List<Project>> = projects.list()
    override suspend fun openProject(projectId: String): Outcome<Project> = projects.open(projectId)
    override suspend fun listAssets(projectId: String): Outcome<List<MediaAsset>> = assets.list(projectId)

    override suspend fun assetPath(projectId: String, assetId: String): Outcome<String> =
        when (val list = assets.list(projectId)) {
            is Outcome.Failure -> list
            is Outcome.Success -> {
                val asset = list.value.firstOrNull { it.id == assetId }
                    ?: return Outcome.Failure(AppError("MEDIA_NO_ASSET", "ไม่มี asset $assetId"))
                val file = File(File(rootDir, "$projectId/assets"), asset.fileName)
                if (!file.isFile) {
                    Outcome.Failure(AppError("MEDIA_NO_FILE", "ไฟล์ asset หาย (${asset.fileName})"))
                } else {
                    Outcome.Success(file.path)
                }
            }
        }

    override suspend fun removeAsset(projectId: String, assetId: String, actor: String): Outcome<Unit> =
        mutate(
            projectId, "ลบ asset $assetId", ProjectEventTypes.ASSET_REMOVED, actor,
            mapOf("assetId" to assetId),
        ) {
            assets.remove(projectId, assetId)
        }

    override suspend fun importAsset(projectId: String, path: String, actor: String): Outcome<MediaAsset> {
        if (projects.open(projectId) is Outcome.Failure) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        val kind = assets.kindFor(File(path).name)
            ?: return Outcome.Failure(AppError("MEDIA_KIND", "นามสกุลไฟล์นี้ยังไม่รองรับ (วิดีโอ/เสียง/รูป)"))
        val facts = probeFacts(kind, path)
        return mutate(
            projectId, "import ${File(path).name}", ProjectEventTypes.ASSET_IMPORTED, actor,
            mapOf("file" to File(path).name, "kind" to kind.name),
        ) {
            assets.import(projectId, File(path), facts)
        }
    }

    override suspend fun getTimeline(projectId: String): Outcome<Timeline> =
        when (val project = projects.open(projectId)) {
            is Outcome.Failure -> project
            is Outcome.Success -> Outcome.Success(project.value.timeline)
        }

    override suspend fun setTimeline(projectId: String, timeline: Timeline, actor: String): Outcome<Project> {
        val known = when (val all = assets.list(projectId)) {
            is Outcome.Failure -> return all
            is Outcome.Success -> all.value.map { it.id }.toSet()
        }
        val errors = timeline.validate(known)
        if (errors.isNotEmpty()) {
            return Outcome.Failure(AppError("MEDIA_TIMELINE", errors.joinToString("; ")))
        }
        // Validation failures must NOT roll back: nothing was mutated yet, and
        // rolling back here would wipe the outer transaction's earlier work.
        return mutateRaw(projectId, "แก้ timeline", ProjectEventTypes.TIMELINE_SET, actor, emptyMap()) {
            projects.saveTimeline(projectId, timeline)
        }
    }

    override suspend fun addClip(
        projectId: String,
        assetId: String,
        startMs: Long,
        endMs: Long,
        atMs: Long,
        volume: Int,
        actor: String,
    ): Outcome<Project> = mutate(
        projectId, "วางคลิป $assetId", ProjectEventTypes.CLIP_ADDED, actor,
        mapOf("assetId" to assetId),
    ) {
        val asset = when (val got = assets.get(projectId, assetId)) {
            is Outcome.Failure -> return@mutate got
            is Outcome.Success -> got.value
        }
        val timeline = when (val got = getTimeline(projectId)) {
            is Outcome.Failure -> return@mutate got
            is Outcome.Success -> got.value
        }
        val clip = Clip(Ids.newId("clip"), assetId, startMs, endMs, atMs, volume)
        val trackId = asset.kind.name.first() + "1"
        val tracks = timeline.tracks.toMutableList()
        val idx = tracks.indexOfFirst { it.id == trackId }
        if (idx < 0) {
            tracks.add(Track(trackId, asset.kind, listOf(clip)))
        } else {
            val track = tracks[idx]
            if (track.locked) {
                return@mutate Outcome.Failure(AppError("MEDIA_CLIP", "แทร็ก $trackId ล็อกอยู่"))
            }
            tracks[idx] = track.copy(clips = track.clips + clip)
        }
        // setTimeline nests inside this transaction (single undo entry).
        setTimeline(projectId, Timeline(tracks, timeline.markers, timeline.texts), actor)
    }

    override suspend fun splitClip(projectId: String, clipId: String, atMs: Long, actor: String): Outcome<Project> =
        editTimeline(projectId, "แยกคลิป $clipId", ProjectEventTypes.CLIP_SPLIT, actor) { timeline ->
            TimelineOps.split(timeline, clipId, atMs, Ids.newId("clip"))
        }

    override suspend fun trimClip(
        projectId: String, clipId: String, startMs: Long?, endMs: Long?, atMs: Long?, actor: String,
    ): Outcome<Project> = editTimeline(projectId, "ทริมคลิป $clipId", ProjectEventTypes.CLIP_TRIMMED, actor) { timeline ->
        val (_, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        val asset = (assets.get(projectId, clip.assetId) as? Outcome.Success)?.value
        val maxEnd = asset?.facts?.get("durationMs")?.toLongOrNull()
        TimelineOps.trim(timeline, clipId, startMs, endMs, atMs, maxEnd)
    }

    override suspend fun moveClip(
        projectId: String, clipId: String, toAtMs: Long, toTrack: String?, actor: String,
    ): Outcome<Project> = editTimeline(projectId, "ย้ายคลิป $clipId", ProjectEventTypes.CLIP_MOVED, actor) { timeline ->
        val (_, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        val asset = (assets.get(projectId, clip.assetId) as? Outcome.Success)?.value
            ?: throw IllegalArgumentException("ไม่มี asset ${clip.assetId}")
        TimelineOps.move(timeline, clipId, toAtMs, toTrack, asset.kind)
    }

    override suspend fun deleteClip(projectId: String, clipId: String, actor: String): Outcome<Project> =
        editTimeline(projectId, "ลบคลิป $clipId", ProjectEventTypes.CLIP_DELETED, actor) { timeline ->
            TimelineOps.delete(timeline, clipId)
        }

    override suspend fun duplicateClip(
        projectId: String, clipId: String, atMs: Long?, actor: String,
    ): Outcome<Project> =
        editTimeline(projectId, "สำเนาคลิป $clipId", ProjectEventTypes.CLIP_DUPLICATED, actor) { timeline ->
            TimelineOps.duplicate(timeline, clipId, atMs, Ids.newId("clip"))
        }

    override suspend fun addMarker(projectId: String, atMs: Long, label: String, actor: String): Outcome<TimelineMarker> =
        mutate(projectId, "เพิ่มมาร์กเกอร์", ProjectEventTypes.MARKER_ADDED, actor, mapOf("atMs" to atMs.toString())) {
            val timeline = timelineOrFail(projectId)
            val marker = TimelineMarker(Ids.newId("mark"), atMs, label)
            setTimeline(projectId, TimelineOps.addMarker(timeline, marker), actor)
            Outcome.Success(marker)
        }

    override suspend fun removeMarker(projectId: String, markerId: String, actor: String): Outcome<Unit> =
        mutate(projectId, "ลบมาร์กเกอร์", ProjectEventTypes.MARKER_REMOVED, actor, emptyMap()) {
            val timeline = timelineOrFail(projectId)
            setTimeline(projectId, TimelineOps.removeMarker(timeline, markerId), actor)
            Outcome.Success(Unit)
        }

    override suspend fun setTrackFlags(
        projectId: String, trackId: String, locked: Boolean?, muted: Boolean?, hidden: Boolean?,
        color: String?, actor: String,
    ): Outcome<Track> =
        mutate(projectId, "ตั้งค่าแทร็ก $trackId", ProjectEventTypes.TRACK_FLAGS, actor, emptyMap()) {
            val timeline = timelineOrFail(projectId)
            val next = TimelineOps.trackFlags(timeline, trackId, locked, muted, hidden, color)
            setTimeline(projectId, next, actor)
            Outcome.Success(next.tracks.first { it.id == trackId })
        }

    private suspend fun timelineOrFail(projectId: String): Timeline =
        when (val got = getTimeline(projectId)) {
            is Outcome.Failure -> throw IllegalArgumentException(got.error.message)
            is Outcome.Success -> got.value
        }

    /** Runs a pure [TimelineOps] edit inside this transaction (IAE → honest failure + rollback). */
    private suspend fun editTimeline(
        projectId: String,
        label: String,
        eventType: String,
        actor: String,
        edit: (Timeline) -> Timeline,
    ): Outcome<Project> = mutate(projectId, label, eventType, actor, emptyMap()) {
        try {
            setTimeline(projectId, edit(timelineOrFail(projectId)), actor)
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(AppError("MEDIA_CLIP", e.message ?: "แก้คลิปไม่ได้"))
        }
    }

    override suspend fun transformClip(
        projectId: String,
        clipId: String,
        transform: ClipTransform,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "transform $clipId", ProjectEventTypes.CLIP_TRANSFORMED, actor,
    ) { TimelineOps.transform(it, clipId, transform) }

    override suspend fun freezeFrame(
        projectId: String,
        clipId: String,
        frameMs: Long?,
        holdMs: Long,
        actor: String,
    ): Outcome<Project> = runTransaction(projectId, "ฟรีซเฟรม", actor) {
        if (holdMs < 100 || holdMs > 30_000) {
            return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", "ฟรีซได้ครั้งละ 100..30000ms"))
        }
        val timeline = try {
            timelineOrFail(projectId)
        } catch (e: IllegalArgumentException) {
            return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", e.message ?: "อ่านไทม์ไลน์ไม่ได้"))
        }
        val (track, clip) = timeline.findClip(clipId)
            ?: return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", "ไม่มีคลิป $clipId"))
        if (track.kind != MediaKind.VIDEO) {
            return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", "ฟรีซได้เฉพาะคลิปวิดีโอ"))
        }
        if (track.locked) {
            return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", "แทร็ก ${track.id} ล็อกอยู่"))
        }
        val asset = when (val got = assets.get(projectId, clip.assetId)) {
            is Outcome.Failure -> return@runTransaction got
            is Outcome.Success -> got.value
        }
        val at = (frameMs ?: (clip.startMs + clip.durationMs / 2))
            .coerceIn(clip.startMs, (clip.endMs - 1).coerceAtLeast(clip.startMs))
        val atTimeline = clip.atMs + (at - clip.startMs)
        val tmp = File.createTempFile("freeze-", ".jpg")
        try {
            when (val thumb = video.thumbnail(assets.assetFile(projectId, asset).path, tmp.path, at)) {
                is Outcome.Failure -> return@runTransaction Outcome.Failure(
                    AppError("MEDIA_CLIP", "ดึงเฟรมไม่ได้: ${thumb.error.message}"),
                )
                is Outcome.Success -> Unit
            }
            val image = when (val got = importAsset(projectId, tmp.path, actor)) {
                is Outcome.Failure -> return@runTransaction got
                is Outcome.Success -> got.value
            }
            val still = Clip(Ids.newId("clip"), image.id, 0, holdMs, atTimeline)
            try {
                setTimeline(projectId, TimelineOps.insertHold(timeline, clipId, atTimeline, holdMs, still), actor)
            } catch (e: IllegalArgumentException) {
                Outcome.Failure(AppError("MEDIA_CLIP", e.message ?: "ฟรีซไม่ได้"))
            }
        } finally {
            tmp.delete()
        }
    }

    override suspend fun addText(projectId: String, overlay: OverlayText, actor: String): Outcome<Project> =
        editTimeline(projectId, "เพิ่มข้อความ", ProjectEventTypes.TEXT_ADDED, actor) {
            TimelineOps.addText(it, overlay)
        }

    override suspend fun updateText(projectId: String, id: String, overlay: OverlayText, actor: String): Outcome<Project> =
        editTimeline(projectId, "แก้ข้อความ", ProjectEventTypes.TEXT_UPDATED, actor) {
            TimelineOps.updateText(it, id) { overlay }
        }

    override suspend fun removeText(projectId: String, id: String, actor: String): Outcome<Project> =
        editTimeline(projectId, "ลบข้อความ", ProjectEventTypes.TEXT_REMOVED, actor) {
            TimelineOps.removeText(it, id)
        }

    override suspend fun setClipSpeed(
        projectId: String,
        clipId: String,
        speed: ClipSpeed,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ความเร็วคลิป $clipId", ProjectEventTypes.CLIP_SPEED, actor,
    ) { TimelineOps.speed(it, clipId, speed) }

    override suspend fun setKeyframe(
        projectId: String,
        clipId: String,
        prop: String,
        atMs: Long,
        value: Float,
        ease: String,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "คีย์เฟรม $prop $clipId", ProjectEventTypes.KEYFRAME_SET, actor,
    ) { TimelineOps.setKeyframe(it, clipId, prop, atMs, value, ease) }

    override suspend fun removeKeyframe(
        projectId: String,
        clipId: String,
        prop: String,
        atMs: Long,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ลบคีย์เฟรม $prop $clipId", ProjectEventTypes.KEYFRAME_REMOVED, actor,
    ) { TimelineOps.removeKeyframe(it, clipId, prop, atMs) }

    override suspend fun clearKeyframes(
        projectId: String,
        clipId: String,
        prop: String?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ล้างคีย์เฟรม $clipId", ProjectEventTypes.KEYFRAMES_CLEARED, actor,
    ) { TimelineOps.clearKeyframes(it, clipId, prop) }

    override suspend fun setTransition(
        projectId: String,
        clipId: String,
        edge: String,
        kind: String,
        durationMs: Long,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ทรานซิชัน$edge $clipId", ProjectEventTypes.TRANSITION_SET, actor,
    ) { TimelineOps.transition(it, clipId, edge, kind, durationMs) }

    override suspend fun clearTransition(
        projectId: String,
        clipId: String,
        edge: String?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ล้างทรานซิชัน $clipId", ProjectEventTypes.TRANSITION_CLEARED, actor,
    ) { TimelineOps.clearTransition(it, clipId, edge) }

    override suspend fun setClipFx(
        projectId: String,
        clipId: String,
        fx: ClipFx,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "เอฟเฟกต์คลิป $clipId", ProjectEventTypes.CLIP_FX, actor,
    ) { TimelineOps.fx(it, clipId, fx) }

    override suspend fun saveTemplate(
        name: String,
        category: String,
        projectId: String,
        slots: Map<String, String>,
        description: String,
    ): Outcome<ProjectTemplate> {
        val timeline = (getTimeline(projectId) as? Outcome.Success)?.value
            ?: return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        return buildTemplate(name, category, timeline, slots, description)?.let { templates.save(it) }
            ?: Outcome.Failure(AppError("TEMPLATE_SLOTS", "slot อ้างคลิปที่ไม่มี"))
    }

    override suspend fun listTemplates(category: String?): Outcome<List<ProjectTemplate>> =
        when (val all = templates.list()) {
            is Outcome.Failure -> all
            is Outcome.Success -> Outcome.Success(if (category == null) all.value else all.value.filter { it.category == category })
        }

    override suspend fun getTemplate(templateId: String): Outcome<ProjectTemplate> = templates.get(templateId)

    override suspend fun deleteTemplate(templateId: String): Outcome<Unit> = templates.delete(templateId)

    override suspend fun applyTemplate(
        projectId: String,
        templateId: String,
        replacements: Map<String, String>,
        actor: String,
    ): Outcome<Project> {
        val tpl = (templates.get(templateId) as? Outcome.Success)?.value
            ?: return Outcome.Failure(AppError("TEMPLATE_MISSING", "ไม่มีเทมเพลต $templateId"))
        val assetIds = ((listAssets(projectId) as? Outcome.Success)?.value.orEmpty().map { it.id }.toSet())
        val dangling = replacements.values.filter { it !in assetIds }
        if (dangling.isNotEmpty()) {
            return Outcome.Failure(AppError("TEMPLATE_ASSETS", "asset ไม่มีในโปรเจกต์: ${dangling.joinToString(", ")}"))
        }
        return editTimeline(
            projectId, "ใช้เทมเพลต ${tpl.name}", ProjectEventTypes.TEMPLATE_APPLIED, actor,
        ) { TimelineOps.fromTemplate(tpl, replacements) }
    }

    override suspend fun libraryAdd(kind: String, name: String, tags: List<String>, ref: String): Outcome<LibraryItem> =
        library.add(LibraryItem("", kind, name, tags, ref))

    override suspend fun libraryList(kind: String?): Outcome<List<LibraryItem>> =
        when (val all = library.list()) {
            is Outcome.Failure -> all
            is Outcome.Success -> Outcome.Success(if (kind == null) all.value else all.value.filter { it.kind == kind })
        }

    override suspend fun librarySearch(query: String): Outcome<List<LibraryItem>> =
        when (val all = library.list()) {
            is Outcome.Failure -> all
            is Outcome.Success -> Outcome.Success(all.value.filter { it.matches(query) })
        }

    override suspend fun libraryRemove(itemId: String): Outcome<Unit> = library.remove(itemId)
    override suspend fun setClipMotion(
        projectId: String,
        clipId: String,
        motion: ClipMotion?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "โมชันคลิป $clipId", ProjectEventTypes.CLIP_MOTION, actor,
    ) { TimelineOps.motion(it, clipId, motion) }

    override suspend fun slideshow(
        projectId: String,
        assetIds: List<String>,
        stillMs: Long,
        fadeMs: Long,
        actor: String,
    ): Outcome<Project> = mutate(
        projectId, "สไลด์โชว์ ${assetIds.size} รูป", ProjectEventTypes.SLIDESHOW_MADE, actor, emptyMap(),
    ) {
        for (id in assetIds) {
            val asset = when (val got = assets.get(projectId, id)) {
                is Outcome.Failure -> return@mutate got
                is Outcome.Success -> got.value
            }
            if (asset.kind != MediaKind.IMAGE) {
                return@mutate Outcome.Failure(AppError("MEDIA_KIND", "สไลด์โชว์ใช้ได้เฉพาะรูป ($id เป็น ${asset.kind})"))
            }
        }
        val timeline = when (val got = getTimeline(projectId)) {
            is Outcome.Failure -> return@mutate got
            is Outcome.Success -> got.value
        }
        try {
            val ids = assetIds.map { Ids.newId("clip") }
            setTimeline(projectId, TimelineOps.slideshow(timeline, assetIds, stillMs, fadeMs, ids), actor)
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(AppError("MEDIA_CLIP", e.message ?: "ทำสไลด์โชว์ไม่ได้"))
        }
    }
    override suspend fun setClipVolume(projectId: String, clipId: String, volume: Int, actor: String): Outcome<Project> =
        editTimeline(projectId, "เสียงคลิป $clipId", ProjectEventTypes.CLIP_VOLUME, actor) { TimelineOps.volume(it, clipId, volume) }

    override suspend fun addMarkers(projectId: String, markers: List<TimelineMarker>, actor: String): Outcome<Project> =
        editTimeline(projectId, "มาร์กเกอร์ ${markers.size} จุด", ProjectEventTypes.MARKER_ADDED, actor) { TimelineOps.addMarkers(it, markers) }
    override suspend fun autocutClip(projectId: String, clipId: String, keep: List<Pair<Long, Long>>, actor: String): Outcome<Project> =
        editTimeline(projectId, "ตัดเงียบ $clipId", ProjectEventTypes.CLIP_AUTOCUT, actor) {
            TimelineOps.autocut(it, clipId, keep, keep.map { Ids.newId("clip") })
        }
    override suspend fun setCanvas(projectId: String, canvas: String, actor: String): Outcome<Project> =
        editTimeline(projectId, "ตั้งแคนวาส $canvas", ProjectEventTypes.TIMELINE_CANVAS, actor) {
            TimelineOps.setCanvas(it, canvas)
        }
    override suspend fun setClipLut(
        projectId: String,
        clipId: String,
        lut: ClipLut?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "LUT คลิป $clipId", ProjectEventTypes.CLIP_LUT, actor,
    ) { TimelineOps.lut(it, clipId, lut) }

    override suspend fun setClipColor(
        projectId: String,
        clipId: String,
        color: ClipColor,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "แก้สีคลิป $clipId", ProjectEventTypes.CLIP_COLOR, actor,
    ) { TimelineOps.color(it, clipId, color) }

    override suspend fun setClipMask(
        projectId: String,
        clipId: String,
        mask: ClipMask,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "มาสก์คลิป $clipId", ProjectEventTypes.CLIP_MASK, actor,
    ) { TimelineOps.mask(it, clipId, mask) }

    override suspend fun setClipChroma(
        projectId: String,
        clipId: String,
        chroma: ClipChroma?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "chroma คลิป $clipId", ProjectEventTypes.CLIP_CHROMA, actor,
    ) { TimelineOps.chroma(it, clipId, chroma) }

    override suspend fun setBackground(
        projectId: String,
        background: ClipBackground?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "พื้นหลังไทม์ไลน์", ProjectEventTypes.TIMELINE_BG, actor,
    ) { TimelineOps.background(it, background) }

    override suspend fun applyTrackPath(
        projectId: String,
        clipId: String,
        posX: List<KeyPoint>,
        posY: List<KeyPoint>,
        zoomBump: Int,
        event: String,
        label: String,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "$label $clipId", event, actor,
    ) { TimelineOps.applyPath(it, clipId, posX, posY, zoomBump) }

    override suspend fun saveVersion(projectId: String, actor: String): Outcome<Int> =
        mutate(
            projectId, "บันทึกเวอร์ชัน", ProjectEventTypes.VERSION_SAVED, actor, emptyMap(),
        ) {
            projects.saveVersion(projectId)
        }

    override suspend fun listVersions(projectId: String): Outcome<List<Int>> = projects.listVersions(projectId)

    override suspend fun restoreVersion(projectId: String, version: Int, actor: String): Outcome<Project> =
        mutate(
            projectId, "ย้อนเวอร์ชัน $version", ProjectEventTypes.VERSION_RESTORED, actor,
            mapOf("version" to version.toString()),
        ) {
            projects.restoreVersion(projectId, version)
        }

    override suspend fun renameProject(projectId: String, name: String, actor: String): Outcome<Project> =
        mutate(
            projectId, "เปลี่ยนชื่อเป็น $name", ProjectEventTypes.PROJECT_RENAMED, actor,
            mapOf("name" to name),
        ) {
            projects.rename(projectId, name)
        }

    override suspend fun duplicateProject(projectId: String, actor: String): Outcome<Project> {
        return when (
            val copied = mutate(
                projectId, "สำเนาโปรเจกต์", ProjectEventTypes.PROJECT_DUPLICATED, actor, emptyMap(),
            ) {
                projects.duplicate(projectId)
            }
        ) {
            is Outcome.Failure -> copied
            is Outcome.Success -> {
                // The copy starts its own undo history (did-not-exist before).
                undoStore.push(copied.value.id, "สำเนาจาก $projectId", actor, ProjectSnapshot(null))
                events.append(
                    ProjectEvent(
                        Ids.newId("ev"), ProjectEventTypes.PROJECT_CREATED, copied.value.id,
                        clock.nowMillis(), actor, mapOf("from" to projectId),
                    ),
                )
                copied
            }
        }
    }

    override suspend fun deleteProject(projectId: String, actor: String): Outcome<String> {
        // Delete moves the whole dir (incl. undo history) to trash, so the
        // undo entry is pushed AFTERWARDS into a fresh stack (single entry).
        val before = snapshot(projectId)
        val beforeProject = before.project
        if (beforeProject == null) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        return when (val deleted = projects.delete(projectId)) {
            is Outcome.Failure -> deleted
            is Outcome.Success -> {
                trashOf[projectId] = deleted.value
                undoStore.push(projectId, "ลบโปรเจกต์ ${beforeProject.name}", actor, before)
                events.append(
                    ProjectEvent(
                        Ids.newId("ev"), ProjectEventTypes.PROJECT_DELETED, projectId,
                        clock.nowMillis(), actor, mapOf("trashId" to deleted.value),
                    ),
                )
                deleted
            }
        }
    }

    override suspend fun listTrash(): Outcome<List<String>> = Outcome.Success(projects.listTrash())

    override suspend fun restoreProject(trashId: String, actor: String): Outcome<Project> {
        return when (val restored = projects.restoreTrash(trashId)) {
            is Outcome.Failure -> restored
            is Outcome.Success -> {
                undoStore.push(restored.value.id, "กู้จากถังขยะ", actor, ProjectSnapshot(null))
                events.append(
                    ProjectEvent(
                        Ids.newId("ev"), ProjectEventTypes.PROJECT_RESTORED, restored.value.id,
                        clock.nowMillis(), actor, mapOf("trashId" to trashId),
                    ),
                )
                restored
            }
        }
    }

    override suspend fun backupProject(projectId: String, actor: String): Outcome<String> =
        mutate(projectId, "แบ็คอัพ", ProjectEventTypes.PROJECT_BACKED_UP, actor, emptyMap()) {
            projects.backup(projectId)
        }

    override suspend fun undo(projectId: String, actor: String): Outcome<String> {
        val current = snapshot(projectId)
        return when (val popped = undoStore.undo(projectId, current)) {
            is Outcome.Failure -> popped
            is Outcome.Success -> {
                restoreSnapshot(projectId, popped.value.snapshot)
                events.append(
                    ProjectEvent(
                        Ids.newId("ev"), ProjectEventTypes.UNDO, projectId,
                        clock.nowMillis(), actor, mapOf("label" to popped.value.label),
                    ),
                )
                Outcome.Success(popped.value.label)
            }
        }
    }

    override suspend fun redo(projectId: String, actor: String): Outcome<String> {
        val current = snapshot(projectId)
        return when (val popped = undoStore.redo(projectId, current)) {
            is Outcome.Failure -> popped
            is Outcome.Success -> {
                restoreSnapshot(projectId, popped.value.snapshot)
                events.append(
                    ProjectEvent(
                        Ids.newId("ev"), ProjectEventTypes.REDO, projectId,
                        clock.nowMillis(), actor, mapOf("label" to popped.value.label),
                    ),
                )
                Outcome.Success(popped.value.label)
            }
        }
    }

    override suspend fun history(projectId: String): Outcome<ProjectHistory> {
        if (!projects.exists(projectId)) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        val undoLabels = undoStore.history(projectId).map { "${it.label} (${it.actor})" }
        val redoCount = File(rootDir, "$projectId/redo").listFiles { f -> f.isFile }?.size ?: 0
        return Outcome.Success(ProjectHistory(undoLabels, redoCount, events.list(projectId)))
    }

    override suspend fun checkpoint(projectId: String, reason: String, actor: String): Outcome<String> {
        val snap = snapshot(projectId)
        if (snap.project == null) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        val clean = reason.replace(Regex("[^A-Za-z0-9_-]+"), "-").take(40).ifBlank { "manual" }
        val meta = checkpoints.save(projectId, clean, actor, snap)
        events.append(
            ProjectEvent(
                Ids.newId("ev"), ProjectEventTypes.CHECKPOINT_SAVED, projectId,
                clock.nowMillis(), actor, mapOf("id" to meta.id, "reason" to clean),
            ),
        )
        return Outcome.Success(meta.id)
    }

    override suspend fun listCheckpoints(projectId: String): Outcome<List<CheckpointMeta>> {
        if (!projects.exists(projectId)) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        return Outcome.Success(checkpoints.list(projectId))
    }

    override suspend fun recoverCheckpoint(projectId: String, id: String?, actor: String): Outcome<Project> {
        return when (val snap = checkpoints.recover(projectId, id)) {
            is Outcome.Failure -> snap
            is Outcome.Success -> mutate(
                projectId, "กู้เช็คพอยต์ ${id ?: "ล่าสุด"}",
                ProjectEventTypes.CHECKPOINT_RECOVERED, actor, mapOf("id" to (id ?: "")),
            ) {
                restoreSnapshot(projectId, snap.value)
                projects.open(projectId)
            }
        }
    }

    override suspend fun <T> runTransaction(
        projectId: String,
        label: String,
        actor: String,
        block: suspend MediaProjectPort.() -> Outcome<T>,
    ): Outcome<T> = mutateRaw(projectId, label, ProjectEventTypes.TRANSACTION, actor, emptyMap()) {
        block()
    }

    // ---- transaction core ----

    private val trashOf = mutableMapOf<String, String>()

    /**
     * Outermost call snapshots first; on success pushes ONE undo entry +
     * event, on failure/exception rolls back to the snapshot (§111).
     * Nested calls (inside [runTransaction]) just run the block.
     */
    private suspend fun <T> mutate(
        projectId: String,
        label: String,
        eventType: String,
        actor: String,
        detail: Map<String, String>,
        block: suspend () -> Outcome<T>,
    ): Outcome<T> = mutateRaw(projectId, label, eventType, actor, detail, block)

    private suspend fun <T> mutateRaw(
        projectId: String,
        label: String,
        eventType: String,
        actor: String,
        detail: Map<String, String>,
        block: suspend () -> Outcome<T>,
    ): Outcome<T> {
        val nested = (txDepth[projectId] ?: 0) > 0
        val before = if (nested) null else snapshot(projectId)
        txDepth[projectId] = (txDepth[projectId] ?: 0) + 1
        try {
            val result = try {
                block()
            } catch (e: Exception) {
                Outcome.Failure(AppError("MEDIA_TX", e.message ?: "transaction ล้มเหลว"))
            }
            if (!nested) {
                if (result is Outcome.Success) {
                    undoStore.push(projectId, label, actor, before!!)
                    events.append(
                        ProjectEvent(
                            Ids.newId("ev"), eventType, projectId,
                            clock.nowMillis(), actor, detail,
                        ),
                    )
                } else if (before!!.project != null || projects.exists(projectId)) {
                    restoreSnapshot(projectId, before)
                }
            }
            return result
        } finally {
            txDepth[projectId] = (txDepth[projectId] ?: 1) - 1
        }
    }

    private fun snapshot(projectId: String): ProjectSnapshot {
        val project = when (val opened = projects.open(projectId)) {
            is Outcome.Failure -> null
            is Outcome.Success -> opened.value
        }
        val allAssets = when (val listed = assets.list(projectId)) {
            is Outcome.Failure -> emptyList()
            is Outcome.Success -> listed.value
        }
        return ProjectSnapshot(project, allAssets)
    }

    private fun restoreSnapshot(projectId: String, snap: ProjectSnapshot) {
        val snapProject = snap.project
        if (snapProject == null) {
            // Undo of create/duplicate/restore: project did not exist before.
            if (projects.exists(projectId)) {
                projects.delete(projectId)
            }
            return
        }
        if (!projects.exists(projectId)) {
            // Undo of delete: copy bytes back from trash, then restore state.
            trashOf[projectId]?.let { trashId ->
                val trashDir = File(rootDir, "trash/$trashId")
                if (trashDir.isDirectory) {
                    trashDir.copyRecursively(File(rootDir, projectId), overwrite = true)
                    trashDir.deleteRecursively()
                    trashOf.remove(projectId)
                }
            }
        }
        if (!projects.exists(projectId)) {
            File(rootDir, projectId).mkdirs()
        }
        projects.writeProject(snapProject.copy(id = projectId))
        assets.writeAll(projectId, snap.assets)
        snap.assets.forEach { assets.restoreFile(projectId, it.fileName) }
    }

    private suspend fun probeFacts(kind: MediaKind, path: String): Map<String, String> = when (kind) {
        MediaKind.IMAGE -> when (val r = images.info(path)) {
            is Outcome.Failure -> mapOf("error" to r.error.message)
            is Outcome.Success -> mapOf(
                "format" to r.value.format,
                "width" to r.value.width.toString(),
                "height" to r.value.height.toString(),
            )
        }
        MediaKind.AUDIO -> when (val r = audio.info(path)) {
            is Outcome.Failure -> mapOf("error" to r.error.message)
            is Outcome.Success -> mapOf(
                "format" to r.value.format,
                "durationMs" to r.value.durationMs.toString(),
            )
        }
        MediaKind.VIDEO -> when (val r = video.info(path)) {
            is Outcome.Failure -> mapOf("error" to r.error.message)
            is Outcome.Success -> mapOf(
                "format" to r.value.format,
                "durationMs" to r.value.durationMs.toString(),
                "width" to r.value.width.toString(),
                "height" to r.value.height.toString(),
            )
        }
    }
}

/** Pure-memory fake for executor/UI tests (mirrors undo/event semantics). */
class InMemoryMediaProject : MediaProjectPort {
    private val projects = mutableMapOf<String, Project>()
    private val assets = mutableMapOf<String, MutableList<MediaAsset>>()
    private val versions = mutableMapOf<String, MutableList<Timeline>>()
    private val undoStacks = mutableMapOf<String, MutableList<UndoEntry>>()
    private val redoStacks = mutableMapOf<String, MutableList<UndoEntry>>()
    private val eventLog = mutableMapOf<String, MutableList<ProjectEvent>>()
    private val checkpointLog = mutableMapOf<String, MutableList<Pair<CheckpointMeta, ProjectSnapshot>>>()
    private val trashBin = mutableMapOf<String, Pair<String, ProjectSnapshot>>()
    private val memTemplates = BuiltinTemplates.list().associateBy { it.id }.toMutableMap()
    private val memLibrary = mutableMapOf<String, LibraryItem>()
    private fun nowMs(): Long = System.currentTimeMillis()
    private var txDepth = 0

    override suspend fun createProject(name: String, actor: String): Outcome<Project> {
        val project = Project(Ids.newId("proj"), name.ifBlank { "Untitled" })
        projects[project.id] = project
        assets[project.id] = mutableListOf()
        versions[project.id] = mutableListOf()
        pushUndo(project.id, "สร้างโปรเจกต์ ${project.name}", actor, ProjectSnapshot(null))
        event(project.id, ProjectEventTypes.PROJECT_CREATED, actor)
        return Outcome.Success(project)
    }

    override suspend fun listProjects(): Outcome<List<Project>> = Outcome.Success(projects.values.toList())

    override suspend fun openProject(projectId: String): Outcome<Project> =
        projects[projectId]?.let { Outcome.Success(it) }
            ?: Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))

    override suspend fun importAsset(projectId: String, path: String, actor: String): Outcome<MediaAsset> {
        if (projectId !in projects) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        val kind = when (path.substringAfterLast('.', "").lowercase()) {
            "mp4", "mov", "mkv", "webm", "3gp" -> MediaKind.VIDEO
            "mp3", "wav", "m4a", "ogg", "flac" -> MediaKind.AUDIO
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> MediaKind.IMAGE
            else -> return Outcome.Failure(AppError("MEDIA_KIND", "นามสกุลไฟล์นี้ยังไม่รองรับ"))
        }
        return mutate(projectId, "import ${File(path).name}", ProjectEventTypes.ASSET_IMPORTED, actor) {
            val asset = MediaAsset(Ids.newId("asset"), kind, File(path).name, File(path).name, 0)
            assets.getOrPut(projectId) { mutableListOf() }.add(asset)
            Outcome.Success(asset)
        }
    }

    override suspend fun listAssets(projectId: String): Outcome<List<MediaAsset>> =
        Outcome.Success(assets[projectId]?.toList().orEmpty())

    override suspend fun assetPath(projectId: String, assetId: String): Outcome<String> {
        val asset = assets[projectId]?.firstOrNull { it.id == assetId }
            ?: return Outcome.Failure(AppError("MEDIA_NO_ASSET", "ไม่มี asset $assetId"))
        return Outcome.Success("mem://${asset.fileName}")
    }

    override suspend fun removeAsset(projectId: String, assetId: String, actor: String): Outcome<Unit> =
        mutate(projectId, "ลบ asset $assetId", ProjectEventTypes.ASSET_REMOVED, actor) {
            val removed = assets[projectId]?.removeIf { it.id == assetId } == true
            if (removed) Outcome.Success(Unit) else Outcome.Failure(AppError("MEDIA_NO_ASSET", "ไม่มี asset $assetId"))
        }

    override suspend fun getTimeline(projectId: String): Outcome<Timeline> =
        when (val project = openProject(projectId)) {
            is Outcome.Failure -> project
            is Outcome.Success -> Outcome.Success(project.value.timeline)
        }

    override suspend fun setTimeline(projectId: String, timeline: Timeline, actor: String): Outcome<Project> {
        val known = assets[projectId].orEmpty().map { it.id }.toSet()
        val errors = timeline.validate(known)
        if (errors.isNotEmpty()) {
            return Outcome.Failure(AppError("MEDIA_TIMELINE", errors.joinToString("; ")))
        }
        return mutate(projectId, "แก้ timeline", ProjectEventTypes.TIMELINE_SET, actor) {
            val project = projects[projectId]
                ?: return@mutate Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
            val updated = project.copy(timeline = timeline)
            projects[projectId] = updated
            Outcome.Success(updated)
        }
    }

    override suspend fun addClip(
        projectId: String,
        assetId: String,
        startMs: Long,
        endMs: Long,
        atMs: Long,
        volume: Int,
        actor: String,
    ): Outcome<Project> = mutate(projectId, "วางคลิป $assetId", ProjectEventTypes.CLIP_ADDED, actor) {
        val asset = assets[projectId]?.firstOrNull { it.id == assetId }
            ?: return@mutate Outcome.Failure(AppError("MEDIA_NO_ASSET", "ไม่มี asset $assetId"))
        val timeline = (getTimeline(projectId) as? Outcome.Success)?.value ?: Timeline()
        val clip = Clip(Ids.newId("clip"), assetId, startMs, endMs, atMs, volume)
        val trackId = asset.kind.name.first() + "1"
        val tracks = timeline.tracks.toMutableList()
        val idx = tracks.indexOfFirst { it.id == trackId }
        if (idx < 0) {
            tracks.add(Track(trackId, asset.kind, listOf(clip)))
        } else {
            if (tracks[idx].locked) {
                return@mutate Outcome.Failure(AppError("MEDIA_CLIP", "แทร็ก $trackId ล็อกอยู่"))
            }
            tracks[idx] = tracks[idx].copy(clips = tracks[idx].clips + clip)
        }
        setTimeline(projectId, Timeline(tracks, timeline.markers, timeline.texts), actor)
    }

    override suspend fun splitClip(projectId: String, clipId: String, atMs: Long, actor: String): Outcome<Project> =
        editTimeline(projectId, "แยกคลิป $clipId", ProjectEventTypes.CLIP_SPLIT, actor) { timeline ->
            TimelineOps.split(timeline, clipId, atMs, Ids.newId("clip"))
        }

    override suspend fun trimClip(
        projectId: String, clipId: String, startMs: Long?, endMs: Long?, atMs: Long?, actor: String,
    ): Outcome<Project> = editTimeline(projectId, "ทริมคลิป $clipId", ProjectEventTypes.CLIP_TRIMMED, actor) { timeline ->
        TimelineOps.trim(timeline, clipId, startMs, endMs, atMs, null)
    }

    override suspend fun moveClip(
        projectId: String, clipId: String, toAtMs: Long, toTrack: String?, actor: String,
    ): Outcome<Project> = editTimeline(projectId, "ย้ายคลิป $clipId", ProjectEventTypes.CLIP_MOVED, actor) { timeline ->
        val (_, clip) = timeline.findClip(clipId)
            ?: throw IllegalArgumentException("ไม่มีคลิป $clipId")
        val kind = assets[projectId]?.firstOrNull { it.id == clip.assetId }?.kind
            ?: throw IllegalArgumentException("ไม่มี asset ${clip.assetId}")
        TimelineOps.move(timeline, clipId, toAtMs, toTrack, kind)
    }

    override suspend fun deleteClip(projectId: String, clipId: String, actor: String): Outcome<Project> =
        editTimeline(projectId, "ลบคลิป $clipId", ProjectEventTypes.CLIP_DELETED, actor) { timeline ->
            TimelineOps.delete(timeline, clipId)
        }

    override suspend fun duplicateClip(
        projectId: String, clipId: String, atMs: Long?, actor: String,
    ): Outcome<Project> =
        editTimeline(projectId, "สำเนาคลิป $clipId", ProjectEventTypes.CLIP_DUPLICATED, actor) { timeline ->
            TimelineOps.duplicate(timeline, clipId, atMs, Ids.newId("clip"))
        }

    override suspend fun addMarker(projectId: String, atMs: Long, label: String, actor: String): Outcome<TimelineMarker> =
        mutate(projectId, "เพิ่มมาร์กเกอร์", ProjectEventTypes.MARKER_ADDED, actor) {
            val timeline = timelineOrFail(projectId)
            val marker = TimelineMarker(Ids.newId("mark"), atMs, label)
            setTimeline(projectId, TimelineOps.addMarker(timeline, marker), actor)
            Outcome.Success(marker)
        }

    override suspend fun removeMarker(projectId: String, markerId: String, actor: String): Outcome<Unit> =
        mutate(projectId, "ลบมาร์กเกอร์", ProjectEventTypes.MARKER_REMOVED, actor) {
            val timeline = timelineOrFail(projectId)
            setTimeline(projectId, TimelineOps.removeMarker(timeline, markerId), actor)
            Outcome.Success(Unit)
        }

    override suspend fun setTrackFlags(
        projectId: String, trackId: String, locked: Boolean?, muted: Boolean?, hidden: Boolean?,
        color: String?, actor: String,
    ): Outcome<Track> =
        mutate(projectId, "ตั้งค่าแทร็ก $trackId", ProjectEventTypes.TRACK_FLAGS, actor) {
            val timeline = timelineOrFail(projectId)
            val next = TimelineOps.trackFlags(timeline, trackId, locked, muted, hidden, color)
            setTimeline(projectId, next, actor)
            Outcome.Success(next.tracks.first { it.id == trackId })
        }

    private suspend fun timelineOrFail(projectId: String): Timeline =
        when (val got = getTimeline(projectId)) {
            is Outcome.Failure -> throw IllegalArgumentException(got.error.message)
            is Outcome.Success -> got.value
        }

    private suspend fun editTimeline(
        projectId: String,
        label: String,
        eventType: String,
        actor: String,
        edit: (Timeline) -> Timeline,
    ): Outcome<Project> = mutate(projectId, label, eventType, actor) {
        try {
            setTimeline(projectId, edit(timelineOrFail(projectId)), actor)
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(AppError("MEDIA_CLIP", e.message ?: "แก้คลิปไม่ได้"))
        }
    }

    override suspend fun transformClip(
        projectId: String,
        clipId: String,
        transform: ClipTransform,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "transform $clipId", ProjectEventTypes.CLIP_TRANSFORMED, actor,
    ) { TimelineOps.transform(it, clipId, transform) }

    override suspend fun freezeFrame(
        projectId: String,
        clipId: String,
        frameMs: Long?,
        holdMs: Long,
        actor: String,
    ): Outcome<Project> = runTransaction(projectId, "ฟรีซเฟรม", actor) {
        if (holdMs < 100 || holdMs > 30_000) {
            return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", "ฟรีซได้ครั้งละ 100..30000ms"))
        }
        val timeline = try {
            timelineOrFail(projectId)
        } catch (e: IllegalArgumentException) {
            return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", e.message ?: "อ่านไทม์ไลน์ไม่ได้"))
        }
        val (track, clip) = timeline.findClip(clipId)
            ?: return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", "ไม่มีคลิป $clipId"))
        if (track.kind != MediaKind.VIDEO) {
            return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", "ฟรีซได้เฉพาะคลิปวิดีโอ"))
        }
        if (track.locked) {
            return@runTransaction Outcome.Failure(AppError("MEDIA_CLIP", "แทร็ก ${track.id} ล็อกอยู่"))
        }
        val at = (frameMs ?: (clip.startMs + clip.durationMs / 2))
            .coerceIn(clip.startMs, (clip.endMs - 1).coerceAtLeast(clip.startMs))
        val atTimeline = clip.atMs + (at - clip.startMs)
        val image = MediaAsset(Ids.newId("asset"), MediaKind.IMAGE, "freeze.jpg", "freeze.jpg", 0)
        assets.getOrPut(projectId) { mutableListOf() }.add(image)
        val still = Clip(Ids.newId("clip"), image.id, 0, holdMs, atTimeline)
        try {
            setTimeline(projectId, TimelineOps.insertHold(timeline, clipId, atTimeline, holdMs, still), actor)
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(AppError("MEDIA_CLIP", e.message ?: "ฟรีซไม่ได้"))
        }
    }

    override suspend fun addText(projectId: String, overlay: OverlayText, actor: String): Outcome<Project> =
        editTimeline(projectId, "เพิ่มข้อความ", ProjectEventTypes.TEXT_ADDED, actor) {
            TimelineOps.addText(it, overlay)
        }

    override suspend fun updateText(projectId: String, id: String, overlay: OverlayText, actor: String): Outcome<Project> =
        editTimeline(projectId, "แก้ข้อความ", ProjectEventTypes.TEXT_UPDATED, actor) {
            TimelineOps.updateText(it, id) { overlay }
        }

    override suspend fun removeText(projectId: String, id: String, actor: String): Outcome<Project> =
        editTimeline(projectId, "ลบข้อความ", ProjectEventTypes.TEXT_REMOVED, actor) {
            TimelineOps.removeText(it, id)
        }

    override suspend fun setClipSpeed(
        projectId: String,
        clipId: String,
        speed: ClipSpeed,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ความเร็วคลิป $clipId", ProjectEventTypes.CLIP_SPEED, actor,
    ) { TimelineOps.speed(it, clipId, speed) }

    override suspend fun setKeyframe(
        projectId: String,
        clipId: String,
        prop: String,
        atMs: Long,
        value: Float,
        ease: String,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "คีย์เฟรม $prop $clipId", ProjectEventTypes.KEYFRAME_SET, actor,
    ) { TimelineOps.setKeyframe(it, clipId, prop, atMs, value, ease) }

    override suspend fun removeKeyframe(
        projectId: String,
        clipId: String,
        prop: String,
        atMs: Long,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ลบคีย์เฟรม $prop $clipId", ProjectEventTypes.KEYFRAME_REMOVED, actor,
    ) { TimelineOps.removeKeyframe(it, clipId, prop, atMs) }

    override suspend fun clearKeyframes(
        projectId: String,
        clipId: String,
        prop: String?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ล้างคีย์เฟรม $clipId", ProjectEventTypes.KEYFRAMES_CLEARED, actor,
    ) { TimelineOps.clearKeyframes(it, clipId, prop) }

    override suspend fun setTransition(
        projectId: String,
        clipId: String,
        edge: String,
        kind: String,
        durationMs: Long,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ทรานซิชัน$edge $clipId", ProjectEventTypes.TRANSITION_SET, actor,
    ) { TimelineOps.transition(it, clipId, edge, kind, durationMs) }

    override suspend fun clearTransition(
        projectId: String,
        clipId: String,
        edge: String?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "ล้างทรานซิชัน $clipId", ProjectEventTypes.TRANSITION_CLEARED, actor,
    ) { TimelineOps.clearTransition(it, clipId, edge) }

    override suspend fun setClipFx(
        projectId: String,
        clipId: String,
        fx: ClipFx,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "เอฟเฟกต์คลิป $clipId", ProjectEventTypes.CLIP_FX, actor,
    ) { TimelineOps.fx(it, clipId, fx) }

    override suspend fun saveTemplate(
        name: String,
        category: String,
        projectId: String,
        slots: Map<String, String>,
        description: String,
    ): Outcome<ProjectTemplate> {
        val timeline = (getTimeline(projectId) as? Outcome.Success)?.value
            ?: return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        return buildTemplate(name, category, timeline, slots, description)?.let { run { val saved = it.copy(id = it.id.ifBlank { Ids.newId("tpl") }, createdAt = nowMs(), updatedAt = nowMs()); memTemplates[saved.id] = saved; Outcome.Success(saved) } }
            ?: Outcome.Failure(AppError("TEMPLATE_SLOTS", "slot อ้างคลิปที่ไม่มี"))
    }

    override suspend fun listTemplates(category: String?): Outcome<List<ProjectTemplate>> =
        Outcome.Success(
            memTemplates.values.sortedByDescending { it.updatedAt }
                .let { if (category == null) it else it.filter { t -> t.category == category } },
        )

    override suspend fun getTemplate(templateId: String): Outcome<ProjectTemplate> =
        memTemplates[templateId]?.let { Outcome.Success(it) }
            ?: Outcome.Failure(AppError("TEMPLATE_MISSING", "ไม่มีเทมเพลต $templateId"))

    override suspend fun deleteTemplate(templateId: String): Outcome<Unit> {
        if (templateId.startsWith("builtin-")) {
            return Outcome.Failure(AppError("TEMPLATE_BUILTIN", "เทมเพลตตั้งต้นลบไม่ได้"))
        }
        return if (memTemplates.remove(templateId) != null) Outcome.Success(Unit)
        else Outcome.Failure(AppError("TEMPLATE_MISSING", "ไม่มีเทมเพลต $templateId"))
    }

    override suspend fun applyTemplate(
        projectId: String,
        templateId: String,
        replacements: Map<String, String>,
        actor: String,
    ): Outcome<Project> {
        val tpl = memTemplates[templateId]
            ?: return Outcome.Failure(AppError("TEMPLATE_MISSING", "ไม่มีเทมเพลต $templateId"))
        val assetIds = ((listAssets(projectId) as? Outcome.Success)?.value.orEmpty().map { it.id }.toSet())
        val dangling = replacements.values.filter { it !in assetIds }
        if (dangling.isNotEmpty()) {
            return Outcome.Failure(AppError("TEMPLATE_ASSETS", "asset ไม่มีในโปรเจกต์: ${dangling.joinToString(", ")}"))
        }
        return editTimeline(
            projectId, "ใช้เทมเพลต ${tpl.name}", ProjectEventTypes.TEMPLATE_APPLIED, actor,
        ) { TimelineOps.fromTemplate(tpl, replacements) }
    }

    override suspend fun libraryAdd(kind: String, name: String, tags: List<String>, ref: String): Outcome<LibraryItem> =
        LibraryItem("", kind, name, tags, ref).let {
            val problems = it.validate()
            if (problems.isNotEmpty()) return Outcome.Failure(AppError("LIB_INVALID", problems.joinToString("; ")))
            val stamped = it.copy(id = Ids.newId("lib"), createdAt = nowMs())
            memLibrary[stamped.id] = stamped
            Outcome.Success(stamped)
        }

    override suspend fun libraryList(kind: String?): Outcome<List<LibraryItem>> =
        Outcome.Success(
            memLibrary.values.sortedByDescending { it.createdAt }
                .let { if (kind == null) it else it.filter { item -> item.kind == kind } },
        )

    override suspend fun librarySearch(query: String): Outcome<List<LibraryItem>> =
        Outcome.Success(memLibrary.values.filter { it.matches(query) }.sortedByDescending { it.createdAt })

    override suspend fun libraryRemove(itemId: String): Outcome<Unit> =
        if (memLibrary.remove(itemId) != null) Outcome.Success(Unit)
        else Outcome.Failure(AppError("LIB_MISSING", "ไม่มี asset $itemId"))
    override suspend fun setClipMotion(
        projectId: String,
        clipId: String,
        motion: ClipMotion?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "โมชันคลิป $clipId", ProjectEventTypes.CLIP_MOTION, actor,
    ) { TimelineOps.motion(it, clipId, motion) }

    override suspend fun slideshow(
        projectId: String,
        assetIds: List<String>,
        stillMs: Long,
        fadeMs: Long,
        actor: String,
    ): Outcome<Project> = mutate(
        projectId, "สไลด์โชว์ ${assetIds.size} รูป", ProjectEventTypes.SLIDESHOW_MADE, actor,
    ) {
        for (id in assetIds) {
            val asset = assets[projectId]?.firstOrNull { it.id == id }
                ?: return@mutate Outcome.Failure(AppError("MEDIA_NO_ASSET", "ไม่มี asset $id"))
            if (asset.kind != MediaKind.IMAGE) {
                return@mutate Outcome.Failure(AppError("MEDIA_KIND", "สไลด์โชว์ใช้ได้เฉพาะรูป ($id เป็น ${asset.kind})"))
            }
        }
        val timeline = (getTimeline(projectId) as? Outcome.Success)?.value ?: Timeline()
        try {
            val ids = assetIds.map { Ids.newId("clip") }
            setTimeline(projectId, TimelineOps.slideshow(timeline, assetIds, stillMs, fadeMs, ids), actor)
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(AppError("MEDIA_CLIP", e.message ?: "ทำสไลด์โชว์ไม่ได้"))
        }
    }
    override suspend fun setClipVolume(projectId: String, clipId: String, volume: Int, actor: String): Outcome<Project> =
        editTimeline(projectId, "เสียงคลิป $clipId", ProjectEventTypes.CLIP_VOLUME, actor) { TimelineOps.volume(it, clipId, volume) }

    override suspend fun addMarkers(projectId: String, markers: List<TimelineMarker>, actor: String): Outcome<Project> =
        editTimeline(projectId, "มาร์กเกอร์ ${markers.size} จุด", ProjectEventTypes.MARKER_ADDED, actor) { TimelineOps.addMarkers(it, markers) }
    override suspend fun autocutClip(projectId: String, clipId: String, keep: List<Pair<Long, Long>>, actor: String): Outcome<Project> =
        editTimeline(projectId, "ตัดเงียบ $clipId", ProjectEventTypes.CLIP_AUTOCUT, actor) {
            TimelineOps.autocut(it, clipId, keep, keep.map { Ids.newId("clip") })
        }
    override suspend fun setCanvas(projectId: String, canvas: String, actor: String): Outcome<Project> =
        editTimeline(projectId, "ตั้งแคนวาส $canvas", ProjectEventTypes.TIMELINE_CANVAS, actor) {
            TimelineOps.setCanvas(it, canvas)
        }
    override suspend fun setClipLut(
        projectId: String,
        clipId: String,
        lut: ClipLut?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "LUT คลิป $clipId", ProjectEventTypes.CLIP_LUT, actor,
    ) { TimelineOps.lut(it, clipId, lut) }
    override suspend fun setClipColor(
        projectId: String,
        clipId: String,
        color: ClipColor,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "แก้สีคลิป $clipId", ProjectEventTypes.CLIP_COLOR, actor,
    ) { TimelineOps.color(it, clipId, color) }

    override suspend fun setClipMask(
        projectId: String,
        clipId: String,
        mask: ClipMask,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "มาสก์คลิป $clipId", ProjectEventTypes.CLIP_MASK, actor,
    ) { TimelineOps.mask(it, clipId, mask) }

    override suspend fun setClipChroma(
        projectId: String,
        clipId: String,
        chroma: ClipChroma?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "chroma คลิป $clipId", ProjectEventTypes.CLIP_CHROMA, actor,
    ) { TimelineOps.chroma(it, clipId, chroma) }

    override suspend fun setBackground(
        projectId: String,
        background: ClipBackground?,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "พื้นหลังไทม์ไลน์", ProjectEventTypes.TIMELINE_BG, actor,
    ) { TimelineOps.background(it, background) }

    override suspend fun applyTrackPath(
        projectId: String,
        clipId: String,
        posX: List<KeyPoint>,
        posY: List<KeyPoint>,
        zoomBump: Int,
        event: String,
        label: String,
        actor: String,
    ): Outcome<Project> = editTimeline(
        projectId, "$label $clipId", event, actor,
    ) { TimelineOps.applyPath(it, clipId, posX, posY, zoomBump) }

    override suspend fun saveVersion(projectId: String, actor: String): Outcome<Int> =
        mutate(projectId, "บันทึกเวอร์ชัน", ProjectEventTypes.VERSION_SAVED, actor) {
            val project = projects[projectId]
                ?: return@mutate Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
            val list = versions.getOrPut(projectId) { mutableListOf() }
            list.add(project.timeline)
            projects[projectId] = project.copy(version = list.size)
            Outcome.Success(list.size)
        }

    override suspend fun listVersions(projectId: String): Outcome<List<Int>> {
        if (projectId !in projects) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        return Outcome.Success(versions[projectId].orEmpty().indices.map { it + 1 })
    }

    override suspend fun restoreVersion(projectId: String, version: Int, actor: String): Outcome<Project> =
        mutate(projectId, "ย้อนเวอร์ชัน $version", ProjectEventTypes.VERSION_RESTORED, actor) {
            val project = projects[projectId]
                ?: return@mutate Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
            val snap = versions[projectId].orEmpty().getOrNull(version - 1)
                ?: return@mutate Outcome.Failure(AppError("MEDIA_NO_VERSION", "ไม่มีเวอร์ชัน $version"))
            val updated = project.copy(timeline = snap)
            projects[projectId] = updated
            Outcome.Success(updated)
        }

    override suspend fun renameProject(projectId: String, name: String, actor: String): Outcome<Project> =
        mutate(projectId, "เปลี่ยนชื่อเป็น $name", ProjectEventTypes.PROJECT_RENAMED, actor) {
            val project = projects[projectId]
                ?: return@mutate Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
            if (name.isBlank()) {
                return@mutate Outcome.Failure(AppError("MEDIA_NAME", "ชื่อว่างไม่ได้"))
            }
            val updated = project.copy(name = name.trim().take(60))
            projects[projectId] = updated
            Outcome.Success(updated)
        }

    override suspend fun duplicateProject(projectId: String, actor: String): Outcome<Project> {
        val project = projects[projectId]
            ?: return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        return mutate(projectId, "สำเนาโปรเจกต์", ProjectEventTypes.PROJECT_DUPLICATED, actor) {
            val copy = project.copy(id = Ids.newId("proj"), name = "${project.name} copy")
            projects[copy.id] = copy
            assets[copy.id] = assets[projectId].orEmpty().toMutableList()
            versions[copy.id] = mutableListOf()
            pushUndo(copy.id, "สำเนาจาก $projectId", actor, ProjectSnapshot(null))
            Outcome.Success(copy)
        }
    }

    override suspend fun deleteProject(projectId: String, actor: String): Outcome<String> =
        mutate(projectId, "ลบโปรเจกต์", ProjectEventTypes.PROJECT_DELETED, actor) {
            val snap = snapshot(projectId)
            if (snap.project == null) {
                return@mutate Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
            }
            val trashId = "$projectId@${System.currentTimeMillis()}"
            trashBin[trashId] = trashId to snap
            projects.remove(projectId)
            assets.remove(projectId)
            lastTrash[projectId] = trashId
            Outcome.Success(trashId)
        }

    override suspend fun listTrash(): Outcome<List<String>> =
        Outcome.Success(trashBin.keys.sortedDescending())

    override suspend fun restoreProject(trashId: String, actor: String): Outcome<Project> {
        val (_, snap) = trashBin[trashId]
            ?: return Outcome.Failure(AppError("MEDIA_NO_TRASH", "ไม่มีโปรเจกต์ที่ลบไว้นี้"))
        val restored = (snap.project ?: return Outcome.Failure(AppError("MEDIA_RESTORE", "ข้อมูลเสีย"))).copy(
            id = Ids.newId("proj"),
        )
        projects[restored.id] = restored
        assets[restored.id] = snap.assets.toMutableList()
        versions[restored.id] = mutableListOf()
        trashBin.remove(trashId)
        pushUndo(restored.id, "กู้จากถังขยะ", actor, ProjectSnapshot(null))
        event(restored.id, ProjectEventTypes.PROJECT_RESTORED, actor)
        return Outcome.Success(restored)
    }

    override suspend fun backupProject(projectId: String, actor: String): Outcome<String> =
        mutate(projectId, "แบ็คอัพ", ProjectEventTypes.PROJECT_BACKED_UP, actor) {
            if (projectId !in projects) {
                return@mutate Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
            }
            Outcome.Success("$projectId@${System.currentTimeMillis()}")
        }

    override suspend fun undo(projectId: String, actor: String): Outcome<String> {
        val stack = undoStacks[projectId]
        val entry = stack?.removeLastOrNull()
            ?: return Outcome.Failure(AppError("MEDIA_UNDO_EMPTY", "ไม่มีอะไรให้เลิกทำ"))
        redoStacks.getOrPut(projectId) { mutableListOf() }.add(
            UndoEntry(entry.label, actor, System.currentTimeMillis(), snapshot(projectId)),
        )
        restoreSnapshot(projectId, entry.snapshot)
        event(projectId, ProjectEventTypes.UNDO, actor, mapOf("label" to entry.label))
        return Outcome.Success(entry.label)
    }

    override suspend fun redo(projectId: String, actor: String): Outcome<String> {
        val stack = redoStacks[projectId]
        val entry = stack?.removeLastOrNull()
            ?: return Outcome.Failure(AppError("MEDIA_REDO_EMPTY", "ไม่มีอะไรให้ทำซ้ำ"))
        undoStacks.getOrPut(projectId) { mutableListOf() }.add(
            UndoEntry(entry.label, actor, System.currentTimeMillis(), snapshot(projectId)),
        )
        restoreSnapshot(projectId, entry.snapshot)
        event(projectId, ProjectEventTypes.REDO, actor, mapOf("label" to entry.label))
        return Outcome.Success(entry.label)
    }

    override suspend fun history(projectId: String): Outcome<ProjectHistory> {
        if (projectId !in projects) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        val labels = undoStacks[projectId].orEmpty().map { "${it.label} (${it.actor})" }
        return Outcome.Success(
            ProjectHistory(labels, redoStacks[projectId].orEmpty().size, eventLog[projectId].orEmpty().takeLast(50)),
        )
    }

    override suspend fun checkpoint(projectId: String, reason: String, actor: String): Outcome<String> {
        val snap = snapshot(projectId)
        if (snap.project == null) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        val clean = reason.replace(Regex("[^A-Za-z0-9_-]+"), "-").take(40).ifBlank { "manual" }
        val meta = CheckpointMeta("${System.currentTimeMillis()}-$clean", clean, actor, System.currentTimeMillis())
        val list = checkpointLog.getOrPut(projectId) { mutableListOf() }
        list.add(meta to snap)
        while (list.size > 10) list.removeFirst()
        event(projectId, ProjectEventTypes.CHECKPOINT_SAVED, actor, mapOf("id" to meta.id))
        return Outcome.Success(meta.id)
    }

    override suspend fun listCheckpoints(projectId: String): Outcome<List<CheckpointMeta>> {
        if (projectId !in projects) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        return Outcome.Success(checkpointLog[projectId].orEmpty().map { it.first }.reversed())
    }

    override suspend fun recoverCheckpoint(projectId: String, id: String?, actor: String): Outcome<Project> =
        mutate(projectId, "กู้เช็คพอยต์ ${id ?: "ล่าสุด"}", ProjectEventTypes.CHECKPOINT_RECOVERED, actor) {
            val list = checkpointLog[projectId].orEmpty()
            val found = (if (id == null) list.lastOrNull() else list.firstOrNull { it.first.id == id })
                ?: return@mutate Outcome.Failure(AppError("MEDIA_NO_CHECKPOINT", "ไม่มีเช็คพอยต์ให้กู้"))
            restoreSnapshot(projectId, found.second)
            openProject(projectId)
        }

    override suspend fun <T> runTransaction(
        projectId: String,
        label: String,
        actor: String,
        block: suspend MediaProjectPort.() -> Outcome<T>,
    ): Outcome<T> {
        val nested = txDepth > 0
        val before = if (nested) null else snapshot(projectId)
        txDepth += 1
        try {
            val result = try {
                block()
            } catch (e: Exception) {
                Outcome.Failure(AppError("MEDIA_TX", e.message ?: "transaction ล้มเหลว"))
            }
            if (!nested) {
                if (result is Outcome.Success) {
                    pushUndo(projectId, label, actor, before!!)
                    event(projectId, ProjectEventTypes.TRANSACTION, actor)
                } else {
                    restoreSnapshot(projectId, before!!)
                }
            }
            return result
        } finally {
            txDepth -= 1
        }
    }

    private val lastTrash = mutableMapOf<String, String>()

    private suspend fun <T> mutate(
        projectId: String,
        label: String,
        eventType: String,
        actor: String,
        block: suspend () -> Outcome<T>,
    ): Outcome<T> {
        val nested = txDepth > 0
        val before = if (nested) null else snapshot(projectId)
        txDepth += 1
        try {
            val result = try {
                block()
            } catch (e: Exception) {
                Outcome.Failure(AppError("MEDIA_TX", e.message ?: "transaction ล้มเหลว"))
            }
            if (!nested) {
                if (result is Outcome.Success) {
                    pushUndo(projectId, label, actor, before!!)
                    event(projectId, eventType, actor)
                } else {
                    restoreSnapshot(projectId, before!!)
                }
            }
            return result
        } finally {
            txDepth -= 1
        }
    }

    private fun pushUndo(projectId: String, label: String, actor: String, snap: ProjectSnapshot) {
        val stack = undoStacks.getOrPut(projectId) { mutableListOf() }
        stack.add(UndoEntry(label, actor, System.currentTimeMillis(), snap))
        while (stack.size > 30) stack.removeFirst()
        redoStacks[projectId]?.clear()
    }

    private fun event(projectId: String, type: String, actor: String, detail: Map<String, String> = emptyMap()) {
        eventLog.getOrPut(projectId) { mutableListOf() }.add(
            ProjectEvent(Ids.newId("ev"), type, projectId, System.currentTimeMillis(), actor, detail),
        )
    }

    private fun snapshot(projectId: String): ProjectSnapshot =
        ProjectSnapshot(projects[projectId], assets[projectId]?.toList().orEmpty())

    private fun restoreSnapshot(projectId: String, snap: ProjectSnapshot) {
        val snapProject = snap.project
        if (snapProject == null) {
            // Undo of create/duplicate/restore: drop the project (bytes kept in trash for file impl).
            val current = snapshot(projectId)
            if (current.project != null) {
                val trashId = "$projectId@${System.currentTimeMillis()}"
                trashBin[trashId] = trashId to current
            }
            projects.remove(projectId)
            assets.remove(projectId)
            return
        }
        if (projectId !in projects) {
            lastTrash[projectId]?.let { trashBin.remove(it) }
            lastTrash.remove(projectId)
        }
        projects[projectId] = snapProject.copy(id = projectId)
        assets[projectId] = snap.assets.toMutableList()
    }
}
