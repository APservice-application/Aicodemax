package com.aicodemax.tools.render

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.image.FrameScopes
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
    /** CP-102: cache usage (render temps + generated files). */
    suspend fun cacheStatus(): Outcome<CacheStatus>
    /** CP-102: deletes render temps + old generated files (keeps finals). */
    suspend fun cacheClear(olderThanDays: Int = 7): Outcome<CacheCleared>
    /** CP-103: hardware codec inventory (partial GPU: encode/decode offload). */
    suspend fun hwinfo(): Outcome<GpuReport>
}

/** CP-102: cache usage report. */
data class CacheStatus(
    val renderOutBytes: Long,
    val renderFiles: Int,
    val genBytes: Long,
    val genFiles: Int,
) {
    val totalBytes: Long get() = renderOutBytes + genBytes
    val summary: String get() =
        "เรนเดอร์ ${"%.1f".format(renderOutBytes / 1048576.0)}MB ($renderFiles ไฟล์) + สร้างไว้ ${"%.1f".format(genBytes / 1048576.0)}MB ($genFiles ไฟล์)"
}

/** CP-103: one codec entry. */
data class CodecInfo(
    val name: String,
    val mime: String,
    val encoder: Boolean,
    val hw: Boolean,
) {
    fun short(): String = "$name (${if (hw) "HW" else "SW"})"
}

/** CP-103: hardware codec inventory. */
data class GpuReport(
    val encoders: List<CodecInfo>,
    val decoders: List<CodecInfo>,
) {
    val hwEncoder: String? get() = encoders.firstOrNull { it.hw }?.name
    val summary: String get() = buildString {
        append("เอนโค้ด:")
        append(if (encoders.isEmpty()) " ไม่มี" else " " + encoders.take(4).joinToString(" / ") { it.short() })
        append(" ถอดรหัส:")
        append(if (decoders.isEmpty()) " ไม่มี" else " " + decoders.take(4).joinToString(" / ") { it.short() })
    }
}

/** CP-102: cleanup result. */
data class CacheCleared(val deletedFiles: Int, val freedBytes: Long) {
    val summary: String get() = "ลบ $deletedFiles ไฟล์ คืน ${"%.1f".format(freedBytes / 1048576.0)}MB"
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
        scopes: FrameScopes? = null,
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
                if (scopes != null && scopes.pixels > 0) {
                    val broken = scopes.darkPct >= 95.0 || scopes.brightPct >= 95.0
                    checks += QcCheck("exposure", !broken, scopes.summary())
                }
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

    override suspend fun cacheStatus(): Outcome<CacheStatus> =
        Outcome.Success(CacheStatus(0, 0, 0, 0))

    override suspend fun cacheClear(olderThanDays: Int): Outcome<CacheCleared> =
        Outcome.Success(CacheCleared(0, 0))

    override suspend fun hwinfo(): Outcome<GpuReport> =
        Outcome.Success(
            GpuReport(
                listOf(CodecInfo("fake-avc-enc", "video/avc", true, false)),
                listOf(CodecInfo("fake-avc-dec", "video/avc", false, false)),
            ),
        )

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
