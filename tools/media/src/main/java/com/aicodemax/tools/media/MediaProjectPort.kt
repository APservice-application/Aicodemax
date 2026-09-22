package com.aicodemax.tools.media

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.data.media.CheckpointMeta
import com.aicodemax.data.media.Clip
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
            tracks[idx] = track.copy(clips = track.clips + clip)
        }
        // setTimeline nests inside this transaction (single undo entry).
        setTimeline(projectId, Timeline(tracks), actor)
    }

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
        if (before.project == null) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        return when (val deleted = projects.delete(projectId)) {
            is Outcome.Failure -> deleted
            is Outcome.Success -> {
                trashOf[projectId] = deleted.value
                undoStore.push(projectId, "ลบโปรเจกต์ ${before.project.name}", actor, before)
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
            tracks[idx] = tracks[idx].copy(clips = tracks[idx].clips + clip)
        }
        setTimeline(projectId, Timeline(tracks), actor)
    }

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
