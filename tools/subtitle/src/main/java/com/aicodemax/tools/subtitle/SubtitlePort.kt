package com.aicodemax.tools.subtitle

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.video.VideoInfo
import java.io.File

/** Result of a subtitle operation. */
data class SubtitleInfo(
    val path: String,
    val cues: Int,
    val durationMs: Long,
) {
    val summary: String get() = "$cues cues ${durationMs}ms"
}

/**
 * CP-66 subtitle contract: SRT make/parse/shift + burn-in.
 * [make]: transcript + (durationMs | mediaPath to probe) -> .srt file.
 * [burn]: video + .srt -> subtitled .mp4 (transcode; Android only).
 */
interface SubtitlePort {
    suspend fun make(transcript: String, mediaPath: String?, durationMs: Long?, dst: String): Outcome<SubtitleInfo>
    suspend fun parse(path: String): Outcome<SubtitleInfo>
    suspend fun shift(src: String, dst: String, offsetMs: Long): Outcome<SubtitleInfo>
    suspend fun burn(srcVideo: String, srtPath: String, dst: String): Outcome<VideoInfo>
    /** CP-98: offline dictionary translation (th-en/en-th). */
    suspend fun translate(src: String, dst: String, direction: String): Outcome<SubtitleInfo>
}

/** File-backed make/parse/shift (pure JVM — ships on Android too). Burn stays abstract. */
abstract class FileSubtitlePort(
    private val audio: com.aicodemax.tools.audio.AudioPort,
    private val video: com.aicodemax.tools.video.VideoPort,
) : SubtitlePort {
    override suspend fun make(
        transcript: String,
        mediaPath: String?,
        durationMs: Long?,
        dst: String,
    ): Outcome<SubtitleInfo> {
        if (transcript.isBlank()) {
            return Outcome.Failure(AppError("SUB_EMPTY", "ไม่มีข้อความให้ทำซับ"))
        }
        val duration = durationMs ?: mediaPath?.let { probeDuration(it) }
        if (duration == null || duration <= 0) {
            return Outcome.Failure(
                AppError("SUB_NO_DURATION", "ต้องรู้ความยาวเสียง/วิดีโอ (บอกไฟล์ media หรือมิลลิวินาที)"),
            )
        }
        val cues = Srt.distribute(transcript, duration)
        if (cues.isEmpty()) {
            return Outcome.Failure(AppError("SUB_EMPTY", "ข้อความสั้นเกินไปหรือเวลาผิด"))
        }
        return try {
            File(dst).parentFile?.mkdirs()
            File(dst).writeText(Srt.format(cues), Charsets.UTF_8)
            Outcome.Success(SubtitleInfo(dst, cues.size, duration))
        } catch (e: Exception) {
            Outcome.Failure(AppError("SUB_WRITE", "เขียนไฟล์ซับไม่ได้: ${e.message}"))
        }
    }

    override suspend fun translate(src: String, dst: String, direction: String): Outcome<SubtitleInfo> {
        val text = try {
            File(src).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return Outcome.Failure(AppError("SUB_READ", "อ่านไฟล์ซับไม่ได้: ${e.message}"))
        }
        val cues = when (val parsed = Srt.parse(text)) {
            is Outcome.Failure -> return parsed
            is Outcome.Success -> parsed.value
        }
        if (cues.isEmpty()) return Outcome.Failure(AppError("SUB_EMPTY", "ไฟล์ซับไม่มีคิว"))
        val dir = direction.lowercase()
        if (dir != "th-en" && dir != "en-th") {
            return Outcome.Failure(AppError("SUB_LANG", "รองรับแค่ th-en หรือ en-th (พจนานุกรมในตัว)"))
        }
        var hits = 0
        var total = 0
        val out = cues.map { cue ->
            cue.copy(lines = cue.lines.map { line ->
                val tr = Translator.translate(line, dir)
                hits += tr.hits
                total += tr.total
                tr.text
            })
        }
        return try {
            File(dst).parentFile?.mkdirs()
            File(dst).writeText(Srt.format(out), Charsets.UTF_8)
            val last = out.maxOf { it.endMs }
            Outcome.Success(SubtitleInfo(dst, out.size, last))
        } catch (e: Exception) {
            Outcome.Failure(AppError("SUB_WRITE", "เขียนไฟล์ซับไม่ได้: ${e.message}"))
        }
    }

    override suspend fun parse(path: String): Outcome<SubtitleInfo> {
        val file = File(path)
        if (!file.isFile) {
            return Outcome.Failure(AppError("SUB_NO_FILE", "ไม่พบไฟล์ $path"))
        }
        return try {
            when (val cues = Srt.parse(file.readText(Charsets.UTF_8))) {
                is Outcome.Failure -> cues
                is Outcome.Success -> Outcome.Success(
                    SubtitleInfo(path, cues.value.size, cues.value.maxOf { it.endMs }),
                )
            }
        } catch (e: Exception) {
            Outcome.Failure(AppError("SUB_READ", "อ่านไฟล์ซับไม่ได้: ${e.message}"))
        }
    }

    override suspend fun shift(src: String, dst: String, offsetMs: Long): Outcome<SubtitleInfo> {
        return when (val parsed = parse(src)) {
            is Outcome.Failure -> parsed
            is Outcome.Success -> {
                val cues = (Srt.parse(File(src).readText(Charsets.UTF_8)) as Outcome.Success<List<Cue>>).value
                val moved = Srt.shift(cues, offsetMs)
                try {
                    File(dst).parentFile?.mkdirs()
                    File(dst).writeText(Srt.format(moved), Charsets.UTF_8)
                    Outcome.Success(SubtitleInfo(dst, moved.size, moved.maxOf { it.endMs }))
                } catch (e: Exception) {
                    Outcome.Failure(AppError("SUB_WRITE", "เขียนไฟล์ซับไม่ได้: ${e.message}"))
                }
            }
        }
    }

    private suspend fun probeDuration(mediaPath: String): Long? {
        val ext = mediaPath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3", "wav", "m4a", "ogg", "flac" -> when (val r = audio.info(mediaPath)) {
                is Outcome.Failure -> null
                is Outcome.Success -> r.value.durationMs.takeIf { it > 0 }
            }
            else -> when (val r = video.info(mediaPath)) {
                is Outcome.Failure -> null
                is Outcome.Success -> r.value.durationMs.takeIf { it > 0 }
            }
        }
    }
}

/** Pure-memory fake for executor tests (burn is simulated, no transcode). */
class InMemorySubtitlePort : SubtitlePort {
    private val store = mutableMapOf<String, List<Cue>>()

    fun put(path: String, cues: List<Cue>) {
        store[path] = cues
    }

    override suspend fun make(
        transcript: String,
        mediaPath: String?,
        durationMs: Long?,
        dst: String,
    ): Outcome<SubtitleInfo> {
        if (transcript.isBlank()) {
            return Outcome.Failure(AppError("SUB_EMPTY", "ไม่มีข้อความให้ทำซับ"))
        }
        val duration = durationMs ?: 10_000L
        val cues = Srt.distribute(transcript, duration)
        store[dst] = cues
        return Outcome.Success(SubtitleInfo(dst, cues.size, duration))
    }

    override suspend fun parse(path: String): Outcome<SubtitleInfo> {
        val cues = store[path]
            ?: return Outcome.Failure(AppError("SUB_NO_FILE", "ไม่พบไฟล์ $path (fake นี้ต้อง put() ก่อน)"))
        return Outcome.Success(SubtitleInfo(path, cues.size, cues.maxOfOrNull { it.endMs } ?: 0))
    }

    override suspend fun shift(src: String, dst: String, offsetMs: Long): Outcome<SubtitleInfo> {
        val cues = store[src]
            ?: return Outcome.Failure(AppError("SUB_NO_FILE", "ไม่พบไฟล์ $src (fake นี้ต้อง put() ก่อน)"))
        val moved = Srt.shift(cues, offsetMs)
        store[dst] = moved
        return Outcome.Success(SubtitleInfo(dst, moved.size, moved.maxOf { it.endMs }))
    }

    override suspend fun burn(srcVideo: String, srtPath: String, dst: String): Outcome<VideoInfo> {
        if (srtPath !in store) {
            return Outcome.Failure(AppError("SUB_NO_FILE", "ไม่พบไฟล์ซับ $srtPath (fake นี้ต้อง put() ก่อน)"))
        }
        return Outcome.Success(VideoInfo(dst, "MP4", 10_000, 640, 480, hasAudio = true))
    }

    override suspend fun translate(src: String, dst: String, direction: String): Outcome<SubtitleInfo> {
        val cues = store[src]
            ?: return Outcome.Failure(AppError("SUB_NO_FILE", "ไม่พบไฟล์ $src (fake นี้ต้อง put() ก่อน)"))
        val dir = direction.lowercase()
        if (dir != "th-en" && dir != "en-th") {
            return Outcome.Failure(AppError("SUB_LANG", "รองรับแค่ th-en หรือ en-th (พจนานุกรมในตัว)"))
        }
        val out = cues.map { cue -> cue.copy(lines = cue.lines.map { Translator.translate(it, dir).text }) }
        store[dst] = out
        return Outcome.Success(SubtitleInfo(dst, out.size, out.maxOfOrNull { it.endMs } ?: 0))
    }

    fun get(path: String): List<Cue>? = store[path]
}
