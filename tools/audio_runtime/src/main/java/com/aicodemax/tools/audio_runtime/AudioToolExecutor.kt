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
                "beats" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    audio.beats(src).fold(
                        onSuccess = {
                            if (it.bpm <= 0) done(true, "จับจังหวะไม่ได้ (สัญญาณไม่มีพัลส์ชัด)")
                            else done(true, "จังหวะ %.0f BPM มั่นใจ %d%% บีต %d จุด".format(it.bpm, (it.confidence * 100).toInt(), it.beatsMs.size))
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "voicefx" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "fx")
                    audio.voiceFx(
                        src, dst,
                        call.args["semitones"]?.toIntOrNull() ?: 0,
                        call.args["robot"] == "true",
                        call.args["echoMs"]?.toLongOrNull() ?: 0L,
                        call.args["echoDecay"]?.toIntOrNull() ?: 0,
                    ).fold(
                        onSuccess = { done(true, "เปลี่ยนเสียงแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "synthmusic" -> {
                    val style = call.args["style"] ?: "calm"
                    val seconds = call.args["seconds"]?.toIntOrNull() ?: 10
                    val dst = call.args["dst"] ?: defaultDst("bed.wav", style)
                    audio.synthMusic(style, seconds, dst).fold(
                        onSuccess = { done(true, "ทำเพลงแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "synthsfx" -> {
                    val kind = call.args["kind"] ?: "impact"
                    val dst = call.args["dst"] ?: defaultDst("sfx.wav", kind)
                    audio.synthSfx(kind, dst).fold(
                        onSuccess = { done(true, "ทำ SFX แล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "speech" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    audio.speech(
                        src,
                        call.args["thresholdDb"]?.toDoubleOrNull() ?: -40.0,
                        call.args["minSpeechMs"]?.toLongOrNull() ?: 300L,
                        call.args["minSilenceMs"]?.toLongOrNull() ?: 500L,
                        call.args["padMs"]?.toLongOrNull() ?: 150L,
                    ).fold(
                        onSuccess = {
                            if (it.ranges.isEmpty()) done(true, "ไม่เจอช่วงเสียงพูด")
                            else done(true, "เจอเสียงพูด ${it.ranges.size} ช่วง: " + it.ranges.take(10).joinToString { r -> "${r.startMs}-${r.endMs}" })
                        },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "recordStart" -> {
                    val dst = call.args["dst"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: dst")
                    audio.recordStart(dst).fold(
                        onSuccess = { done(true, "เริ่มอัดเสียงแล้ว → $dst") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "recordStop" -> {
                    audio.recordStop().fold(
                        onSuccess = { done(true, "หยุดอัดแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "mix" -> {
                    val a = call.args["srcA"] ?: call.args["src"]
                        ?: return@withContext done(false, error = "missing arg: srcA")
                    val b = call.args["srcB"] ?: call.args["bed"]
                        ?: return@withContext done(false, error = "missing arg: srcB")
                    val dst = call.args["dst"] ?: defaultDst(a, "mix")
                    audio.mix(a, b, dst, call.args["gainB"]?.toDoubleOrNull() ?: 1.0, call.args["offsetMs"]?.toLongOrNull() ?: 0L).fold(
                        onSuccess = { done(true, "ผสมเสียงแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "normalize" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "norm")
                    audio.normalize(src, dst, call.args["peakDb"]?.toDoubleOrNull() ?: -3.0).fold(
                        onSuccess = { done(true, "นอร์มัลไลซ์แล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "autocut" -> {
                    val src = call.args["src"] ?: call.args["path"]
                        ?: return@withContext done(false, error = "missing arg: src")
                    val dst = call.args["dst"] ?: defaultDst(src, "cut")
                    audio.autocut(
                        src, dst,
                        call.args["thresholdDb"]?.toDoubleOrNull() ?: -40.0,
                        call.args["minSpeechMs"]?.toLongOrNull() ?: 300L,
                        call.args["minSilenceMs"]?.toLongOrNull() ?: 500L,
                        call.args["padMs"]?.toLongOrNull() ?: 150L,
                    ).fold(
                        onSuccess = { done(true, "ตัดเงียบเสียงแล้ว ${it.path}: ${it.summary}") },
                        onFailure = { done(false, error = it.message) },
                    )
                }
                "podcast" -> {
                    val voice = call.args["voice"] ?: call.args["src"]
                        ?: return@withContext done(false, error = "missing arg: voice")
                    val dst = call.args["dst"] ?: defaultDst(voice, "podcast")
                    val bed = call.args["bed"]
                    val bedGain = call.args["bedGain"]?.toDoubleOrNull() ?: 0.15
                    val tmpCut = dst + ".cut.wav"
                    val tmpNorm = dst + ".norm.wav"
                    val cut = audio.autocut(voice, tmpCut)
                    if (cut is Outcome.Failure) {
                        return@withContext done(false, error = cut.error.message)
                    }
                    val norm = audio.normalize((cut as Outcome.Success).value.path, tmpNorm)
                    if (norm is Outcome.Failure) {
                        return@withContext done(false, error = norm.error.message)
                    }
                    val normPath = (norm as Outcome.Success).value.path
                    if (bed == null) {
                        audio.gain(normPath, dst, 0.0).fold(
                            onSuccess = { done(true, "พอดแคสต์พร้อมแล้ว $dst (ตัดเงียบ+นอร์มัลไลซ์): ${it.summary}") },
                            onFailure = { done(false, error = it.message) },
                        )
                    } else {
                        audio.mix(normPath, bed, dst, bedGain).fold(
                            onSuccess = { done(true, "พอดแคสต์พร้อมแล้ว $dst (ตัดเงียบ+นอร์มัลไลซ์+ดนตรี): ${it.summary}") },
                            onFailure = { done(false, error = it.message) },
                        )
                    }
                }
                else -> done(false, error = "unknown action '${call.action}' (have: info/trim/concat/gain/fade/beats/voicefx/synthmusic/synthsfx/speech/recordStart/recordStop/mix/normalize/autocut/podcast)")
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
