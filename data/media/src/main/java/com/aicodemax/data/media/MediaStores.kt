package com.aicodemax.data.media

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Clock
import com.aicodemax.core.common.Ids
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.SystemClock
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * CP-64 file stores. Layout under [root]:
 * ```
 * <projectId>/project.json
 * <projectId>/assets.json
 * <projectId>/assets/<file>
 * <projectId>/versions/<n>.json   (timeline snapshots, §23)
 * ```
 */
class FileMediaStore(
    private val root: File,
    private val clock: Clock = SystemClock,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    companion object {
        const val MAX_IMPORT_BYTES = 500L * 1024 * 1024
    }

    fun kindFor(fileName: String): MediaKind? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp4", "mov", "mkv", "webm", "3gp" -> MediaKind.VIDEO
        "mp3", "wav", "m4a", "ogg", "flac" -> MediaKind.AUDIO
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> MediaKind.IMAGE
        else -> null
    }

    fun list(projectId: String): Outcome<List<MediaAsset>> = try {
        Outcome.Success(readAssets(projectId))
    } catch (e: Exception) {
        Outcome.Failure(AppError("MEDIA_LIST", "list asset ไม่ได้: ${e.message}"))
    }

    fun get(projectId: String, assetId: String): Outcome<MediaAsset> {
        return when (val all = list(projectId)) {
            is Outcome.Failure -> all
            is Outcome.Success -> all.value.firstOrNull { it.id == assetId }?.let { Outcome.Success(it) }
                ?: Outcome.Failure(AppError("MEDIA_NO_ASSET", "ไม่มี asset $assetId"))
        }
    }

    /**
     * Copies [src] into the project and registers it with [facts] from the probe.
     * File name is sanitized; collisions get a numeric suffix.
     */
    fun import(
        projectId: String,
        src: File,
        facts: Map<String, String> = emptyMap(),
    ): Outcome<MediaAsset> {
        if (!src.isFile) {
            return Outcome.Failure(AppError("MEDIA_NO_FILE", "ไม่พบไฟล์ ${src.path}"))
        }
        if (src.length() > MAX_IMPORT_BYTES) {
            return Outcome.Failure(AppError("MEDIA_TOO_BIG", "ไฟล์ใหญ่เกิน 500MB"))
        }
        val kind = kindFor(src.name)
            ?: return Outcome.Failure(AppError("MEDIA_KIND", "นามสกุล .${src.extension} ยังไม่รองรับ (วิดีโอ/เสียง/รูป)"))
        return try {
            val dir = File(root, "$projectId/assets")
            dir.mkdirs()
            val safe = src.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(80).ifBlank { "file" }
            var target = File(dir, safe)
            var n = 1
            while (target.exists()) {
                n += 1
                target = File(dir, "${safe.substringBeforeLast('.')}-$n.${safe.substringAfterLast('.', safe)}")
            }
            src.copyTo(target)
            val asset = MediaAsset(
                id = Ids.newId("asset"),
                kind = kind,
                fileName = target.name,
                originalName = src.name,
                sizeBytes = target.length(),
                facts = facts,
                addedAt = clock.nowMillis(),
            )
            writeAssets(projectId, readAssets(projectId) + asset)
            Outcome.Success(asset)
        } catch (e: Exception) {
            Outcome.Failure(AppError("MEDIA_IMPORT", "import ไม่ได้: ${e.message}"))
        }
    }

    fun remove(projectId: String, assetId: String): Outcome<Unit> = try {
        val all = readAssets(projectId)
        val asset = all.firstOrNull { it.id == assetId }
            ?: return Outcome.Failure(AppError("MEDIA_NO_ASSET", "ไม่มี asset $assetId"))
        File(File(root, "$projectId/assets"), asset.fileName).delete()
        writeAssets(projectId, all.filter { it.id != assetId })
        Outcome.Success(Unit)
    } catch (e: Exception) {
        Outcome.Failure(AppError("MEDIA_REMOVE", "ลบ asset ไม่ได้: ${e.message}"))
    }

    fun assetFile(projectId: String, asset: MediaAsset): File =
        File(File(root, "$projectId/assets"), asset.fileName)

    private fun assetsFile(projectId: String): File = File(root, "$projectId/assets.json")

    private fun readAssets(projectId: String): List<MediaAsset> {
        val file = assetsFile(projectId)
        if (!file.isFile) return emptyList()
        return json.decodeFromString<List<MediaAsset>>(file.readText())
    }

    private fun writeAssets(projectId: String, assets: List<MediaAsset>) {
        val file = assetsFile(projectId)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(assets))
    }
}

/** Project CRUD + timeline + numbered version snapshots (§23). */
class FileProjectStore(
    private val root: File,
    private val clock: Clock = SystemClock,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun list(): Outcome<List<Project>> = try {
        val projects = root.listFiles()
            ?.filter { File(it, "project.json").isFile }
            ?.mapNotNull {
                try {
                    json.decodeFromString<Project>(File(it, "project.json").readText())
                } catch (_: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()
        Outcome.Success(projects)
    } catch (e: Exception) {
        Outcome.Failure(AppError("MEDIA_LIST", "list โปรเจกต์ไม่ได้: ${e.message}"))
    }

    fun create(name: String): Outcome<Project> = try {
        val clean = name.trim().ifBlank { "Untitled" }.take(60)
        val project = Project(
            id = Ids.newId("proj"),
            name = clean,
            createdAt = clock.nowMillis(),
            updatedAt = clock.nowMillis(),
        )
        writeProject(project)
        Outcome.Success(project)
    } catch (e: Exception) {
        Outcome.Failure(AppError("MEDIA_CREATE", "สร้างโปรเจกต์ไม่ได้: ${e.message}"))
    }

    fun open(projectId: String): Outcome<Project> {
        val file = projectFile(projectId)
        if (!file.isFile) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        return try {
            Outcome.Success(json.decodeFromString<Project>(file.readText()))
        } catch (e: Exception) {
            Outcome.Failure(AppError("MEDIA_OPEN", "เปิดโปรเจกต์ไม่ได้: ${e.message}"))
        }
    }

    fun saveTimeline(projectId: String, timeline: Timeline): Outcome<Project> {
        return when (val current = open(projectId)) {
            is Outcome.Failure -> current
            is Outcome.Success -> try {
                val updated = current.value.copy(timeline = timeline, updatedAt = clock.nowMillis())
                writeProject(updated)
                Outcome.Success(updated)
            } catch (e: Exception) {
                Outcome.Failure(AppError("MEDIA_SAVE", "บันทึก timeline ไม่ได้: ${e.message}"))
            }
        }
    }

    /** Snapshots the current timeline as the next version number; returns it. */
    fun saveVersion(projectId: String): Outcome<Int> {
        return when (val current = open(projectId)) {
            is Outcome.Failure -> current
            is Outcome.Success -> try {
                val versionsDir = File(root, "$projectId/versions")
                versionsDir.mkdirs()
                val next = (versionsDir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0) + 1
                File(versionsDir, "$next.json").writeText(json.encodeToString(current.value.timeline))
                writeProject(current.value.copy(version = next, updatedAt = clock.nowMillis()))
                Outcome.Success(next)
            } catch (e: Exception) {
                Outcome.Failure(AppError("MEDIA_VERSION", "บันทึกเวอร์ชันไม่ได้: ${e.message}"))
            }
        }
    }

    fun listVersions(projectId: String): Outcome<List<Int>> {
        if (!projectFile(projectId).isFile) {
            return Outcome.Failure(AppError("MEDIA_NO_PROJECT", "ไม่มีโปรเจกต์ $projectId"))
        }
        return try {
            val nums = File(root, "$projectId/versions").listFiles()
                ?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
                ?.sorted()
                .orEmpty()
            Outcome.Success(nums)
        } catch (e: Exception) {
            Outcome.Failure(AppError("MEDIA_VERSION", "list เวอร์ชันไม่ได้: ${e.message}"))
        }
    }

    fun restoreVersion(projectId: String, version: Int): Outcome<Project> {
        return when (val current = open(projectId)) {
            is Outcome.Failure -> current
            is Outcome.Success -> try {
                val snap = File(root, "$projectId/versions/$version.json")
                if (!snap.isFile) {
                    return Outcome.Failure(AppError("MEDIA_NO_VERSION", "ไม่มีเวอร์ชัน $version"))
                }
                val timeline = json.decodeFromString<Timeline>(snap.readText())
                val updated = current.value.copy(timeline = timeline, updatedAt = clock.nowMillis())
                writeProject(updated)
                Outcome.Success(updated)
            } catch (e: Exception) {
                Outcome.Failure(AppError("MEDIA_RESTORE", "ย้อนเวอร์ชันไม่ได้: ${e.message}"))
            }
        }
    }

    private fun projectFile(projectId: String): File = File(root, "$projectId/project.json")

    private fun writeProject(project: Project) {
        val file = projectFile(project.id)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(project))
    }
}
