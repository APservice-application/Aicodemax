package com.aicodemax.tools.subtitle_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import com.aicodemax.tools.subtitle.InMemorySubtitlePort
import com.aicodemax.tools.subtitle.SubtitlePort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for subtitles (actions: make/parse/shift/burn). */
class SubtitleToolExecutor(
    private val subs: SubtitlePort = InMemorySubtitlePort(),
    private val llm: (suspend (String) -> Outcome<String>)? = null,
) : ToolExecutor {
    override val toolId: String = "subtitle"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "make" -> {
                    val transcript = call.args["transcript"]
                        ?: return@withContext done(false, error = "missing arg: transcript")
                    val mediaPath = call.args["mediaPath"] ?: call.args["path"]
                    val durationMs = call.args["durationMs"]?.toLongOrNull()
                    val dst = call.args["dst"] ?: defaultDst(mediaPath, "srt")
                    subs.make(transcript, mediaPath, durationMs, dst).fold(
                        onSuccess = { done(true, "ทำซับแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "parse" -> {
                    val path = call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: path")
                    subs.parse(path).fold(
                        onSuccess = { done(true, "ซับ ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "shift" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "srt")
                    val offset = call.args["offsetMs"]?.toLongOrNull()
                        ?: return@withContext done(false, error = "missing arg: offsetMs (เช่น เลื่อนซับ a.srt 500)")
                    subs.shift(src, dst, offset).fold(
                        onSuccess = { done(true, "เลื่อนซับแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "burn" -> {
                    val src = call.args["src"]
                        ?: return@withContext done(false, error = "missing arg: src (วิดีโอ)")
                    val srt = call.args["srt"]
                        ?: return@withContext done(false, error = "missing arg: srt")
                    val dst = call.args["dst"] ?: defaultDst(src, "mp4")
                    subs.burn(src, srt, dst).fold(
                        onSuccess = { done(true, "ฝังซับแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "translate" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "srt")
                    val direction = call.args["direction"] ?: call.args["to"]?.let {
                        if (it.lowercase().startsWith("en")) "th-en" else "en-th"
                    } ?: "th-en"
                    val engine = call.args["engine"] ?: "dict"
                    subs.translate(src, dst, direction, engine, llm).fold(
                        onSuccess = {
                            val tag = if (engine == "llm") "(แปลด้วย LLM)" else "(พจนานุกรมในตัว ไทย↔อังกฤษ)"
                            done(true, "แปลซับแล้ว ${it.path}: ${it.summary} $tag")
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: make/parse/shift/burn/translate)")
            }
        }

    private fun defaultDst(src: String?, ext: String): String {
        if (src.isNullOrBlank()) return "out.$ext"
        val dot = src.lastIndexOf('.')
        val base = if (dot < 0) src else src.substring(0, dot)
        return "$base-sub.$ext"
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
