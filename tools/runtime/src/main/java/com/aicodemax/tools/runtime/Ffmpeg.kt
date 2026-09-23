package com.aicodemax.tools.runtime

import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.io.File

/**
 * CP-119: real media operations through the embedded ffmpeg/ffprobe binaries
 * (CP-118 toolchain). Pure JVM — the [Runner] is injected (ProcessRunner on
 * Android, fakes in tests). Missing binary/input → honest Outcome.Failure,
 * never fake output.
 */
object Ffmpeg {
    const val PROBE_TIMEOUT_MS = 30_000L
    const val EXPORT_TIMEOUT_MS = 600_000L

    data class RunnerResult(
        val exitCode: Int,
        val stdout: String = "",
        val stderr: String = "",
        val timedOut: Boolean = false,
    )

    fun interface Runner {
        fun run(exe: String, args: List<String>, timeoutMs: Long): RunnerResult
    }

    data class MediaProbe(
        val durationMs: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
    ) {
        fun summary(): String = buildList {
            durationMs?.let { add("ยาว ${it}ms") }
            if (width != null && height != null) add("ภาพ ${width}x${height}")
            videoCodec?.let { add("วิดีโอ $it") }
            audioCodec?.let { add("เสียง $it") }
        }.ifEmpty { listOf("อ่านข้อมูลไม่ได้") }.joinToString(" • ")
    }

    data class ExportOpts(
        val startMs: Long? = null,
        val durationMs: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val videoCodec: String = "mpeg4",
        val audioCodec: String = "aac",
        val videoBitrateK: Int? = null,
    )

    /** Flat key=value output — no JSON dependency needed. */
    fun probeArgs(path: String): List<String> = listOf(
        "-v", "error",
        "-show_entries", "format=duration:stream=width,height,codec_name,codec_type",
        "-of", "default=noprint_wrappers=1",
        path,
    )

    fun parseProbe(text: String): MediaProbe {
        var durationMs: Long? = null
        var width: Int? = null
        var height: Int? = null
        var videoCodec: String? = null
        var audioCodec: String? = null
        var section = ""
        // Per-[STREAM] buffer: key order inside a section is not guaranteed,
        // so codec_name is assigned only after codec_type is known.
        var streamCodec: String? = null
        var streamType: String? = null
        fun flushStream() {
            if (streamType == "video" && videoCodec == null) videoCodec = streamCodec
            if (streamType == "audio" && audioCodec == null) audioCodec = streamCodec
            streamCodec = null
            streamType = null
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                if (section == "[STREAM]") flushStream()
                section = if (line.startsWith("[/")) "" else line
                continue
            }
            val key = line.substringBefore("=").trim()
            val value = line.substringAfter("=", "").trim().removeSurrounding("\"")
            if (value.isEmpty() || value == "N/A") continue
            when {
                section == "[FORMAT]" && key == "duration" ->
                    durationMs = value.toDoubleOrNull()?.let { (it * 1000).toLong() }
                section == "[STREAM]" && key == "width" -> {
                    val w = value.toIntOrNull()
                    if (w != null && w > 0 && width == null) width = w
                }
                section == "[STREAM]" && key == "height" -> {
                    val h = value.toIntOrNull()
                    if (h != null && h > 0 && height == null) height = h
                }
                section == "[STREAM]" && key == "codec_name" -> streamCodec = value
                section == "[STREAM]" && key == "codec_type" -> streamType = value
            }
        }
        if (section == "[STREAM]") flushStream()
        return MediaProbe(durationMs, width, height, videoCodec, audioCodec)
    }

    fun probeFile(
        ffprobeExe: String?,
        path: String,
        runner: Runner,
    ): Outcome<MediaProbe> = runOutcome("FFPROBE") {
        if (ffprobeExe.isNullOrBlank() || !File(ffprobeExe).isFile) {
            throw IllegalStateException("native ffprobe ยังไม่ฝังในเครื่องนี้ (ต้อง build ที่ฝัง toolchain)")
        }
        if (!File(path).isFile) throw IllegalArgumentException("ไม่พบไฟล์: $path")
        val res = runner.run(ffprobeExe, probeArgs(path), PROBE_TIMEOUT_MS)
        if (res.timedOut) throw IllegalStateException("ffprobe หมดเวลา (>30วิ)")
        if (res.exitCode != 0) {
            throw IllegalStateException("ffprobe ล้มเหลว (exit ${res.exitCode}): ${res.stderr.take(200)}")
        }
        parseProbe(res.stdout + "\n" + res.stderr)
    }

    fun exportArgs(input: String, output: String, opts: ExportOpts): List<String> = buildList {
        add("-y")
        opts.startMs?.let { add("-ss"); add("%.3f".format(it / 1000.0)) }
        opts.durationMs?.let { add("-t"); add("%.3f".format(it / 1000.0)) }
        add("-i"); add(input)
        if (opts.width != null || opts.height != null) {
            add("-vf"); add("scale=${opts.width ?: -1}:${opts.height ?: -1}")
        }
        add("-c:v"); add(opts.videoCodec)
        opts.videoBitrateK?.let { add("-b:v"); add("${it}k") }
        add("-c:a"); add(opts.audioCodec)
        add(output)
    }

    data class ExportResult(val outputPath: String, val bytes: Long)

    fun exportFile(
        ffmpegExe: String?,
        input: String,
        output: String,
        opts: ExportOpts,
        runner: Runner,
    ): Outcome<ExportResult> = runOutcome("FFMPEG_EXPORT") {
        if (ffmpegExe.isNullOrBlank() || !File(ffmpegExe).isFile) {
            throw IllegalStateException("native ffmpeg ยังไม่ฝังในเครื่องนี้ (ต้อง build ที่ฝัง toolchain)")
        }
        if (!File(input).isFile) throw IllegalArgumentException("ไม่พบไฟล์ต้นฉบับ: $input")
        val outFile = File(output)
        outFile.parentFile?.mkdirs()
        val res = runner.run(ffmpegExe, exportArgs(input, output, opts), EXPORT_TIMEOUT_MS)
        if (res.timedOut) throw IllegalStateException("ffmpeg หมดเวลา (>10นาที)")
        if (res.exitCode != 0) {
            throw IllegalStateException("ffmpeg ล้มเหลว (exit ${res.exitCode}): ${res.stderr.take(300)}")
        }
        if (!outFile.isFile || outFile.length() == 0L) {
            throw IllegalStateException("ffmpeg จบแต่ไม่มีไฟล์ผลลัพธ์: $output")
        }
        ExportResult(output, outFile.length())
    }
}
