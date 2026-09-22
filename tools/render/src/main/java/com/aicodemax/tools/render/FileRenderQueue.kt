package com.aicodemax.tools.render

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * CP-67 file-backed render queue: `<root>/renders/<jobId>.json`, one file
 * per job (§19: queue survives process death; pump with `run`).
 */
class FileRenderQueue(rootDir: File) {
    private val dir = File(rootDir, "renders").also { it.mkdirs() }
    private val json = Json { prettyPrint = true }

    fun enqueue(projectId: String, preset: RenderPreset): RenderJob {
        val job = RenderJob(
            id = "job_${System.currentTimeMillis().toString(36)}_${(0..9999).random().toString().padStart(4, '0')}",
            projectId = projectId,
            preset = preset,
            status = RenderStatus.QUEUED,
        )
        save(job)
        return job
    }

    fun get(id: String): RenderJob? {
        val file = File(dir, "$id.json")
        if (!file.isFile) return null
        return try {
            json.decodeFromString<RenderJob>(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    /** Newest first. */
    fun list(): List<RenderJob> =
        dir.listFiles { f -> f.isFile && f.extension == "json" }
            .orEmpty()
            .mapNotNull { file ->
                try {
                    json.decodeFromString<RenderJob>(file.readText())
                } catch (_: Exception) {
                    null
                }
            }
            .sortedByDescending { it.createdAt }

    fun save(job: RenderJob) {
        File(dir, "${job.id}.json").writeText(json.encodeToString(job))
    }

    /** Re-queue a failed job (RECOVERY: retry with the same id keeps history). */
    fun retry(id: String): RenderJob? {
        val job = get(id) ?: return null
        if (job.status != RenderStatus.FAILED) return job
        val queued = job.copy(
            status = RenderStatus.QUEUED, progress = 0, error = "",
            startedAt = 0, finishedAt = 0, qc = null, previews = emptyList(), outputPath = "",
        )
        save(queued)
        return queued
    }

    /** Latest job matching [predicate] (executor fallbacks: status/retry/approve/export). */
    fun latest(predicate: (RenderJob) -> Boolean = { true }): RenderJob? =
        list().firstOrNull(predicate)
}
