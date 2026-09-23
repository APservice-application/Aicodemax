package com.aicodemax.tools.video_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.video.InMemoryVideoPort
import com.aicodemax.tools.video.VideoPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for video (actions: info/thumbnail/trim/extractAudio). */
class VideoToolExecutor(private val video: VideoPort = InMemoryVideoPort()) : ToolExecutor {
    override val toolId: String = "video"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "info" -> {
                    val path = call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: path")
                    video.info(path).fold(
                        onSuccess = { done(true, "วิดีโอ ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "thumbnail" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: replaceExt(src, "png", "thumb")
                    val timeMs = call.args["timeMs"]?.toLongOrNull() ?: 1000L
                    video.thumbnail(src, dst, timeMs).fold(
                        onSuccess = { done(true, "ภาพปกแล้ว ${it.path}: ${it.width}x${it.height}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "trim" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: replaceExt(src, "mp4", "cut")
                    val start = call.args["startMs"]?.toLongOrNull()
                    val end = call.args["endMs"]?.toLongOrNull()
                    if (start == null || end == null) {
                        return@withContext done(false, error = "missing args: startMs,endMs (เช่น ตัดวิดีโอ a.mp4 0,10000)")
                    }
                    video.trim(src, dst, start, end).fold(
                        onSuccess = { done(true, "ตัดแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "extractAudio" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: replaceExt(src, "m4a", "audio")
                    video.extractAudio(src, dst).fold(
                        onSuccess = { done(true, "ดึงเสียงแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "proxy" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: replaceExt(src, "mp4", "proxy")
                    val maxDim = call.args["maxDim"]?.toIntOrNull() ?: 640
                    video.proxy(src, dst, maxDim).fold(
                        onSuccess = { done(true, "พร็อกซีแล้ว ${it.path}: ${it.summary} (ภาพอย่างเดียว ไม่มีเสียง)") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: info/thumbnail/trim/extractAudio/proxy)")
            }
        }

    private fun replaceExt(src: String, ext: String, tag: String): String {
        val dot = src.lastIndexOf('.')
        val base = if (dot < 0) src else src.substring(0, dot)
        return "$base-$tag.$ext"
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
