package com.aicodemax.tools.render_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.media.MediaProjectPort
import com.aicodemax.tools.render.RenderJob
import com.aicodemax.tools.render.RenderPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-67 gateway executor. Chat-friendly fallbacks: missing projectId →
 * latest project; missing jobId → latest matching job (status/retry/
 * approve/export each pick the sensible candidate).
 */
class RenderToolExecutor(
    private val port: RenderPort,
    private val media: MediaProjectPort,
) : ToolExecutor {
    override val toolId: String = "render"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "enqueue" -> {
                    val projectId = call.args["projectId"] ?: latestProjectId()
                    if (projectId == null) {
                        return@withContext done(false, error = "ยังไม่มีโปรเจกต์ให้เรนเดอร์")
                    }
                    port.enqueue(projectId, call.args["preset"]).fold(
                        onSuccess = { done(true, describe(it)) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "runNow" -> {
                    val projectId = call.args["projectId"] ?: latestProjectId()
                    if (projectId == null) {
                        return@withContext done(false, error = "ยังไม่มีโปรเจกต์ให้เรนเดอร์")
                    }
                    port.runNow(projectId, call.args["preset"]).fold(
                        onSuccess = { done(true, describe(it)) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "run" -> runOn(call, call.args["jobId"] ?: latestId({ true })) { port.run(it) }
                "status" -> runOn(call, call.args["jobId"] ?: latestId({ true })) { port.status(it) }
                "list" -> port.list().fold(
                    onSuccess = { jobs ->
                        val out = if (jobs.isEmpty()) "ยังไม่มีงานเรนเดอร์"
                        else jobs.take(10).joinToString("\n") { describeShort(it) }
                        done(true, out)
                    },
                    onFailure = { done(false, error = it.message) },
                )
                "retry" -> runOn(
                    call,
                    call.args["jobId"] ?: latestId({ it.status.name == "FAILED" }),
                    empty = "ไม่มีงานที่ล้มเหลวให้ลองใหม่",
                ) { port.retry(it) }
                "approve" -> runOn(
                    call,
                    call.args["jobId"] ?: latestId({ it.qc?.passed == true && !it.approved }),
                    empty = "ไม่มีงานที่ผ่าน QC รออนุมัติ",
                ) { port.approve(it) }
                "export" -> runOn(
                    call,
                    call.args["jobId"] ?: latestId({ it.approved && it.exportedUri.isEmpty() }),
                    empty = "ไม่มีงานที่อนุมัติแล้วรอเอ็กซ์พอร์ต",
                ) { port.export(it) }
                "batch" -> {
                    val ids = if (call.args["all"] == "true") {
                        media.listProjects().fold(
                            onSuccess = { ps -> ps.map { it.id } },
                            onFailure = { return@withContext done(false, error = it.message) },
                        )
                    } else {
                        (call.args["projectIds"] ?: call.args["projectId"] ?: "")
                            .split(",", " ", ";").map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    if (ids.isEmpty()) {
                        return@withContext done(false, error = "missing arg: projectIds (คั่นด้วยจุลภาค) หรือ all=true")
                    }
                    if (ids.size > 20) {
                        return@withContext done(false, error = "มากสุด 20 โปรเจกต์ต่อรอบ (ได้ ${ids.size})")
                    }
                    val preset = call.args["preset"]
                    val lines = mutableListOf<String>()
                    for (id in ids) {
                        port.runNow(id, preset).fold(
                            onSuccess = { lines.add("$id: ${describeShort(it)}") },
                            onFailure = { lines.add("$id: ล้มเหลว ${it.message}") },
                        )
                    }
                    done(true, "เรนเดอร์ทีละโปรเจกต์ ${ids.size} งาน:\n" + lines.joinToString("\n"))
                }
                "cache.status" -> {
                    port.cacheStatus().fold(
                        onSuccess = { done(true, "แคช: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "cache.clear" -> {
                    val days = call.args["days"]?.toIntOrNull() ?: 7
                    port.cacheClear(days).fold(
                        onSuccess = { done(true, "ล้างแคชแล้ว: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "hwinfo" -> {
                    port.hwinfo().fold(
                        onSuccess = { done(true, "ตัวเร่งฮาร์ดแวร์: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "director" -> {
                    val projectId = call.args["projectId"] ?: latestProjectId()
                    if (projectId == null) {
                        return@withContext done(false, error = "ยังไม่มีโปรเจกต์ให้ตรวจ")
                    }
                    media.getTimeline(projectId).fold(
                        onSuccess = { done(true, com.aicodemax.tools.render.DirectorReview.review(it).verdictText()) },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: enqueue/runNow/run/status/list/retry/approve/export/batch/cache.status/cache.clear/hwinfo/director)")
            }
        }

    private suspend fun runOn(
        call: ToolCall,
        jobId: String?,
        empty: String = "ยังไม่มีงานเรนเดอร์",
        block: suspend (String) -> Outcome<RenderJob>,
    ): Outcome<ToolResult> {
        if (jobId == null) return done(false, error = empty)
        return block(jobId).fold(
            onSuccess = { done(true, describe(it)) },
            onFailure = { done(false, error = it.message) },
        )
    }

    private suspend fun latestProjectId(): String? =
        media.listProjects().fold(
            onSuccess = { projects -> projects.maxByOrNull { maxOf(it.updatedAt, it.createdAt) }?.id },
            onFailure = { null },
        )

    private suspend fun latestId(predicate: (RenderJob) -> Boolean): String? =
        port.list().fold(
            onSuccess = { jobs -> jobs.firstOrNull(predicate)?.id },
            onFailure = { null },
        )

    private fun describe(job: RenderJob): String = buildString {
        append("${job.id} [${job.status}] ${job.progress}% ${job.preset.name}")
        if (job.outputPath.isNotEmpty()) append(" → ${job.outputPath}")
        job.qc?.let { append(if (it.passed) " QCผ่าน" else " QCไม่ผ่าน") }
        if (job.approved) append(" อนุมัติแล้ว")
        if (job.exportedUri.isNotEmpty()) append(" ส่งออก: ${job.exportedUri}")
        if (job.error.isNotEmpty()) append(" ผิดพลาด: ${job.error}")
    }

    private fun describeShort(job: RenderJob): String =
        "${job.id} [${job.status}] ${job.projectId} ${job.preset.name}"

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
