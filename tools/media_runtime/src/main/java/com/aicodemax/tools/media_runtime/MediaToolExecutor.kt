package com.aicodemax.tools.media_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
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
                    media.createProject(name).fold(
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
                    media.importAsset(projectId, path).fold(
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
                            val lines = timeline.tracks.flatMap { track ->
                                track.clips.map { "  ${track.id}: ${it.assetId} ${it.startMs}..${it.endMs} @${it.atMs}" }
                            }
                            done(true, "timeline ${timeline.durationMs}ms, ${lines.size} คลิป\n" + lines.joinToString("\n"))
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
                    media.addClip(projectId, assetId, start, end, at, volume).fold(
                        onSuccess = { done(true, "วางคลิปแล้ว timeline ยาว ${it.timeline.durationMs}ms") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "version.save" -> {
                    val projectId = call.args["projectId"] ?: latestProject()
                        ?: return@withContext done(false, error = "ยังไม่มีโปรเจกต์ — สร้างโปรเจกต์ใหม่ก่อนครับ")
                    media.saveVersion(projectId).fold(
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
                    media.restoreVersion(projectId, version).fold(
                        onSuccess = { done(true, "ย้อนไปเวอร์ชัน $version แล้ว (timeline ${it.timeline.durationMs}ms)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}'")
            }
        }

    /** Latest project (by update time) for chat flows that omit projectId. */
    private suspend fun latestProject(): String? = when (val list = media.listProjects()) {
        is Outcome.Failure -> null
        is Outcome.Success -> list.value.firstOrNull()?.id
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
