package com.aicodemax.tools.audio_runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.fold
import com.aicodemax.tools.audio.AudioPort
import com.aicodemax.tools.audio.InMemoryAudioPort
import com.aicodemax.tools.gateway.ToolCall
import com.aicodemax.tools.gateway.ToolExecutor
import com.aicodemax.tools.gateway.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Gateway executor for audio (actions: info/trim/concat/gain/fade). */
class AudioToolExecutor(private val audio: AudioPort = InMemoryAudioPort()) : ToolExecutor {
    override val toolId: String = "audio"

    override suspend fun execute(call: ToolCall): Outcome<ToolResult> =
        withContext(Dispatchers.IO) {
            when (call.action) {
                "info" -> {
                    val path = call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: path")
                    audio.info(path).fold(
                        onSuccess = { done(true, "เสียง ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "trim" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "cut")
                    val start = call.args["startMs"]?.toLongOrNull()
                    val end = call.args["endMs"]?.toLongOrNull()
                    if (start == null || end == null) {
                        return@withContext done(false, error = "missing args: startMs,endMs (เช่น ตัดเสียง a.wav 0,5000)")
                    }
                    audio.trim(src, dst, start, end).fold(
                        onSuccess = { done(true, "ตัดแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "concat" -> {
                    val srcs = call.args["srcs"]?.split('|')?.map { it.trim() }?.filter { it.isNotEmpty() }
                    if (srcs.isNullOrEmpty()) {
                        return@withContext done(false, error = "missing arg: srcs (เช่น a.wav|b.wav)")
                    }
                    val dst = call.args["dst"] ?: defaultDst(srcs.first(), "joined")
                    audio.concat(srcs, dst).fold(
                        onSuccess = { done(true, "ต่อแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "gain" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "vol")
                    val db = call.args["db"]?.toDoubleOrNull()
                        ?: return@withContext done(false, error = "missing arg: db (เช่น เร่งเสียง a.wav 6)")
                    audio.gain(src, dst, db).fold(
                        onSuccess = { done(true, "ปรับเสียงแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "fade" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "fade")
                    val fadeIn = call.args["inMs"]?.toLongOrNull() ?: 1000L
                    val fadeOut = call.args["outMs"]?.toLongOrNull() ?: 1000L
                    audio.fade(src, dst, fadeIn, fadeOut).fold(
                        onSuccess = { done(true, "เฟดแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                else -> done(false, error = "unknown action '${call.action}' (have: info/trim/concat/gain/fade)")
            }
        }

    private fun defaultDst(src: String, tag: String): String {
        val dot = src.lastIndexOf('.')
        val base = if (dot < 0) src else src.substring(0, dot)
        // Edits always land as WAV (honest: no re-encode to lossy yet).
        return "$base-$tag.wav"
    }

    private fun done(ok: Boolean, output: String = "", error: String = ""): Outcome<ToolResult> =
        Outcome.Success(ToolResult(ok = ok, output = output, error = error))
}
