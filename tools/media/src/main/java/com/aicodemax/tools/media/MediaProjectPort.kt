package com.aicodemax.tools.media

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import com.aicodemax.data.media.Clip
import com.aicodemax.data.media.FileMediaStore
import com.aicodemax.data.media.FileProjectStore
import com.aicodemax.data.media.MediaAsset
import com.aicodemax.data.media.MediaKind
import com.aicodemax.data.media.Project
import com.aicodemax.data.media.Timeline
import com.aicodemax.data.media.Track
import java.io.File

/**
 * CP-64 project contract: projects + assets + timeline + versions.
 * File-backed impl ships on JVM and Android (plain file I/O).
 */
interface MediaProjectPort {
    suspend fun createProject(name: String): Outcome<Project>
    suspend fun listProjects(): Outcome<List<Project>>
    suspend fun openProject(projectId: String): Outcome<Project>
    suspend fun importAsset(projectId: String, path: String): Outcome<MediaAsset>
    suspend fun listAssets(projectId: String): Outcome<List<MediaAsset>>
    suspend fun removeAsset(projectId: String, assetId: String): Outcome<Unit>
    suspend fun getTimeline(projectId: String): Outcome<Timeline>
    suspend fun setTimeline(projectId: String, timeline: Timeline): Outcome<Project>
    suspend fun addClip(
        projectId: String,
        assetId: String,
        startMs: Long,
        endMs: Long,
        atMs: Long,
        volume: Int = 100,
    ): Outcome<Project>
    suspend fun saveVersion(projectId: String): Outcome<Int>
    suspend fun listVersions(projectId: String): Outcome<List<Int>>
    suspend fun restoreVersion(projectId: String, version: Int): Outcome<Project>
}

/** File-backed projects with probe facts from the media engines. */
class FileMediaProject(
    root: File,
    private val images: com.aicodemax.tools.image.ImagePort,
    private val audio: com.aicodemax.tools.audio.AudioPort,
    private val video: com.aicodemax.tools.video.VideoPort,
    clock: Clock = SystemClock,
) : MediaProjectPort {
    private val projects = FileProjectStore(root, clock)
    private val assets = FileMediaStore(root, clock)

    override suspend fun createProject(name: String): Outcome<Project> = projects.create(name)
    override suspend fun listProjects(): Outcome<List<Project>> = projects.list()
    override suspend fun openProject(projectId: String): Outcome<Project> = projects.open(projectId)
    override suspend fun listAssets(projectId: String): Outcome<List<MediaAsset>> = assets.list(projectId)
    override suspend fun removeAsset(projectId: String, assetId: String): Outcome<Unit> =
        assets.remove(projectId, assetId)

    override suspend fun importAsset(projectId: String, path: String): Outcome<MediaAsset> {
        if (projects.open(projectId) is Outcome.Failure) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        val kind = assets.kindFor(File(path).name)
            ?: return Outcome.Failure(AppError("MEDIA_KIND", "นามสกุลไฟล์นี้ยังไม่รองรับ (วิดีโอ/เสียง/รูป)"))
        val facts = probeFacts(kind, path)
        return assets.import(projectId, File(path), facts)
    }

    override suspend fun getTimeline(projectId: String): Outcome<Timeline> =
        when (val project = projects.open(projectId)) {
            is Outcome.Failure -> project
            is Outcome.Success -> Outcome.Success(project.value.timeline)
        }

    override suspend fun setTimeline(projectId: String, timeline: Timeline): Outcome<Project> {
        val known = when (val all = assets.list(projectId)) {
            is Outcome.Failure -> return all
            is Outcome.Success -> all.value.map { it.id }.toSet()
        }
        val errors = timeline.validate(known)
        if (errors.isNotEmpty()) {
            return Outcome.Failure(AppError("MEDIA_TIMELINE", errors.joinToString("; ")))
        }
        return projects.saveTimeline(projectId, timeline)
    }

    override suspend fun addClip(
        projectId: String,
        assetId: String,
        startMs: Long,
        endMs: Long,
        atMs: Long,
        volume: Int,
    ): Outcome<Project> {
        val asset = when (val got = assets.get(projectId, assetId)) {
            is Outcome.Failure -> return got
            is Outcome.Success -> got.value
        }
        val timeline = when (val got = getTimeline(projectId)) {
            is Outcome.Failure -> return got
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
        return setTimeline(projectId, Timeline(tracks))
    }

    override suspend fun saveVersion(projectId: String): Outcome<Int> = projects.saveVersion(projectId)
    override suspend fun listVersions(projectId: String): Outcome<List<Int>> = projects.listVersions(projectId)
    override suspend fun restoreVersion(projectId: String, version: Int): Outcome<Project> =
        projects.restoreVersion(projectId, version)

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

/** Pure-memory fake for executor/UI tests. */
class InMemoryMediaProject : MediaProjectPort {
    private val projects = mutableMapOf<String, Project>()
    private val assets = mutableMapOf<String, MutableList<MediaAsset>>()
    private val versions = mutableMapOf<String, MutableList<Timeline>>()

    override suspend fun createProject(name: String): Outcome<Project> {
        val project = Project(Ids.newId("proj"), name.ifBlank { "Untitled" })
        projects[project.id] = project
        assets[project.id] = mutableListOf()
        versions[project.id] = mutableListOf()
        return Outcome.Success(project)
    }

    override suspend fun listProjects(): Outcome<List<Project>> = Outcome.Success(projects.values.toList())

    override suspend fun openProject(projectId: String): Outcome<Project> =
        projects[projectId]?.let { Outcome.Success(it) }
            ?: Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))

    override suspend fun importAsset(projectId: String, path: String): Outcome<MediaAsset> {
        if (projectId !in projects) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        val kind = when (path.substringAfterLast('.', "").lowercase()) {
            "mp4", "mov", "mkv", "webm", "3gp" -> MediaKind.VIDEO
            "mp3", "wav", "m4a", "ogg", "flac" -> MediaKind.AUDIO
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> MediaKind.IMAGE
            else -> return Outcome.Failure(AppError("MEDIA_KIND", "นามสกุลไฟล์นี้ยังไม่รองรับ"))
        }
        val asset = MediaAsset(Ids.newId("asset"), kind, File(path).name, File(path).name, 0)
        assets.getOrPut(projectId) { mutableListOf() }.add(asset)
        return Outcome.Success(asset)
    }

    override suspend fun listAssets(projectId: String): Outcome<List<MediaAsset>> =
        Outcome.Success(assets[projectId]?.toList().orEmpty())

    override suspend fun removeAsset(projectId: String, assetId: String): Outcome<Unit> {
        val removed = assets[projectId]?.removeIf { it.id == assetId } == true
        return if (removed) Outcome.Success(Unit) else Outcome.Failure(AppError("MEDIA_NO_ASSET", "ไม่มี asset $assetId"))
    }

    override suspend fun getTimeline(projectId: String): Outcome<Timeline> =
        when (val project = openProject(projectId)) {
            is Outcome.Failure -> project
            is Outcome.Success -> Outcome.Success(project.value.timeline)
        }

    override suspend fun setTimeline(projectId: String, timeline: Timeline): Outcome<Project> {
        val known = assets[projectId].orEmpty().map { it.id }.toSet()
        val errors = timeline.validate(known)
        if (errors.isNotEmpty()) {
            return Outcome.Failure(AppError("MEDIA_TIMELINE", errors.joinToString("; ")))
        }
        val project = projects[projectId]
            ?: return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        val updated = project.copy(timeline = timeline)
        projects[projectId] = updated
        return Outcome.Success(updated)
    }

    override suspend fun addClip(
        projectId: String,
        assetId: String,
        startMs: Long,
        endMs: Long,
        atMs: Long,
        volume: Int,
    ): Outcome<Project> {
        val asset = assets[projectId]?.firstOrNull { it.id == assetId }
            ?: return Outcome.Failure(AppError("MEDIA_NO_ASSET", "ไม่มี asset $assetId"))
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
        return setTimeline(projectId, Timeline(tracks))
    }

    override suspend fun saveVersion(projectId: String): Outcome<Int> {
        val project = projects[projectId]
            ?: return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        val list = versions.getOrPut(projectId) { mutableListOf() }
        list.add(project.timeline)
        projects[projectId] = project.copy(version = list.size)
        return Outcome.Success(list.size)
    }

    override suspend fun listVersions(projectId: String): Outcome<List<Int>> {
        if (projectId !in projects) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        return Outcome.Success(versions[projectId].orEmpty().indices.map { it + 1 })
    }

    override suspend fun restoreVersion(projectId: String, version: Int): Outcome<Project> {
        val project = projects[projectId]
            ?: return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        val snap = versions[projectId].orEmpty().getOrNull(version - 1)
            ?: return Outcome.Failure(AppError("MEDIA_NO_VERSION", "ไม่มีเวอร์ชัน $version"))
        val updated = project.copy(timeline = snap)
        projects[projectId] = updated
        return Outcome.Success(updated)
    }
}
