package com.aicodemax.tools.render

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.video.VideoPort
import java.io.File

/**
 * CP-67 render contract (§19–21).
 *
 * Flow: enqueue → run (transcode + QC + previews) → approve (user) → export.
 * Honest v0 limits: no cancellation mid-render, always renders the latest
 * project state, video-clip audio is skipped in the full transcode path
 * (noted on the job).
 */
interface RenderPort {
    suspend fun enqueue(projectId: String, presetName: String?): Outcome<RenderJob>
    suspend fun runNow(projectId: String, presetName: String?): Outcome<RenderJob>
    suspend fun run(jobId: String): Outcome<RenderJob>
    suspend fun status(jobId: String): Outcome<RenderJob>
    suspend fun list(): Outcome<List<RenderJob>>
    suspend fun retry(jobId: String): Outcome<RenderJob>
    suspend fun approve(jobId: String): Outcome<RenderJob>
    suspend fun export(jobId: String): Outcome<RenderJob>
}

/**
 * CP-67 QC gate (§20): the rendered file must match the timeline before
 * the user is asked to approve. Pure orchestration over [VideoPort], so it
 * is unit-testable with fakes.
 */
object Qc {
    suspend fun check(
        durationMs: Long,
        wantAudio: Boolean,
        outputPath: String,
        maxHeight: Int,
        video: VideoPort,
    ): QcReport {
        val checks = mutableListOf<QcCheck>()
        val file = File(outputPath)
        checks += QcCheck("file", file.isFile && file.length() > 0, "${file.length()} bytes")
        if (!file.isFile) return QcReport(false, checks)
        when (val info = video.info(outputPath)) {
            is Outcome.Failure -> {
                checks += QcCheck("probe", false, info.error.message)
                return QcReport(false, checks)
            }
            is Outcome.Success -> {
                val v = info.value
                checks += QcCheck("probe", true, "${v.width}x${v.height} ${v.durationMs}ms")
                val durationOk = if (durationMs <= 0) {
                    v.durationMs >= 500
                } else {
                    kotlin.math.abs(v.durationMs - durationMs) <= durationMs * 15 / 100
                }
                checks += QcCheck("duration", durationOk, "ได้ ${v.durationMs}ms คาด ~${durationMs}ms")
                checks += QcCheck("height", v.height <= maxHeight + 2, "${v.height} ≤ $maxHeight")
                checks += QcCheck(
                    "audio",
                    if (wantAudio) v.hasAudio else true,
                    if (wantAudio) "ต้องมีเสียง: ${v.hasAudio}" else "ไม่มีคลิปเสียง",
                )
            }
        }
        return QcReport(checks.all { it.ok }, checks)
    }
}

/** JVM fake: instant renders with a passing QC, for tests and previews. */
class InMemoryRender : RenderPort {
    private val jobs = LinkedHashMap<String, RenderJob>()
    private var counter = 0

    override suspend fun enqueue(projectId: String, presetName: String?): Outcome<RenderJob> {
        counter += 1
        val job = RenderJob(
            id = "job_$counter",
            projectId = projectId,
            preset = RenderPreset.byName(presetName),
            status = RenderStatus.QUEUED,
        )
        jobs[job.id] = job
        return Outcome.Success(job)
    }

    override suspend fun runNow(projectId: String, presetName: String?): Outcome<RenderJob> {
        val enqueued = enqueue(projectId, presetName)
        if (enqueued is Outcome.Failure) return enqueued
        return run((enqueued as Outcome.Success).value.id)
    }

    override suspend fun run(jobId: String): Outcome<RenderJob> {
        val job = jobs[jobId] ?: return Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))
        val done = job.copy(
            status = RenderStatus.DONE, progress = 100,
            startedAt = 1, finishedAt = 2,
            outputPath = "/tmp/${job.id}.mp4",
            qc = QcReport.single("fake", true, "simulated"),
        )
        jobs[jobId] = done
        return Outcome.Success(done)
    }

    override suspend fun status(jobId: String): Outcome<RenderJob> =
        jobs[jobId]?.let { Outcome.Success(it) }
            ?: Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))

    override suspend fun list(): Outcome<List<RenderJob>> =
        Outcome.Success(jobs.values.sortedByDescending { it.createdAt })

    override suspend fun retry(jobId: String): Outcome<RenderJob> {
        val job = jobs[jobId] ?: return Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))
        val queued = job.copy(status = RenderStatus.QUEUED, progress = 0, error = "")
        jobs[jobId] = queued
        return Outcome.Success(queued)
    }

    override suspend fun approve(jobId: String): Outcome<RenderJob> {
        val job = jobs[jobId] ?: return Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))
        if (job.qc?.passed != true) {
            return Outcome.Failure(AppError("RENDER_QC", "QC ยังไม่ผ่าน อนุมัติไม่ได้"))
        }
        val approved = job.copy(approved = true)
        jobs[jobId] = approved
        return Outcome.Success(approved)
    }

    override suspend fun export(jobId: String): Outcome<RenderJob> {
        val job = jobs[jobId] ?: return Outcome.Failure(AppError("RENDER_NO_JOB", "ไม่มีงาน $jobId"))
        if (!job.approved) {
            return Outcome.Failure(AppError("RENDER_APPROVAL", "ต้องอนุมัติก่อนเอ็กซ์พอร์ต"))
        }
        val exported = job.copy(exportedUri = "file://${job.outputPath}")
        jobs[jobId] = exported
        return Outcome.Success(exported)
    }
}
