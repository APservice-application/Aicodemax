package com.aicodemax.tools.video

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/** Result of a video operation. */
data class VideoInfo(
    val path: String,
    val format: String,
    val durationMs: Long,
    val width: Int = -1,
    val height: Int = -1,
    val hasAudio: Boolean = false,
    val sizeBytes: Long = -1,
) {
    val summary: String get() = buildString {
        append("$format ${durationMs}ms")
        if (width > 0) append(" ${width}x$height")
        if (hasAudio) append(" +audio")
    }
}

/**
 * CP-63 video contract: probe + thumbnail + trim + audio extraction.
 * Trim/extract are stream-copy (no re-encode); real transcode is future work.
 */
interface VideoPort {
    suspend fun info(path: String): Outcome<VideoInfo>
    suspend fun thumbnail(src: String, dst: String, timeMs: Long): Outcome<VideoInfo>
    suspend fun trim(src: String, dst: String, startMs: Long, endMs: Long): Outcome<VideoInfo>
    suspend fun extractAudio(src: String, dst: String): Outcome<VideoInfo>
}

/**
 * In-memory fake: stores [VideoInfo] by path. Real files on disk are probed
 * ([info] only); edits require the clip to be [put] first.
 */
class InMemoryVideoPort : VideoPort {
    private val store = mutableMapOf<String, VideoInfo>()

    fun put(path: String, info: VideoInfo) {
        store[path] = info
    }

    fun get(path: String): VideoInfo? = store[path]

    override suspend fun info(path: String): Outcome<VideoInfo> {
        store[path]?.let { return Outcome.Success(it) }
        return when (val probed = Mp4Probe.probe(java.io.File(path))) {
            is Outcome.Failure -> probed
            is Outcome.Success -> Outcome.Success(
                VideoInfo(
                    path, probed.value.format, probed.value.durationMs,
                    probed.value.width, probed.value.height, probed.value.hasAudio, probed.value.sizeBytes,
                ),
            )
        }
    }

    override suspend fun thumbnail(src: String, dst: String, timeMs: Long): Outcome<VideoInfo> {
        val clip = store[src]
            ?: return Outcome.Failure(AppError("VIDEO_THUMB", "ไม่พบวิดีโอ $src (fake นี้ต้อง put() ก่อน)"))
        if (timeMs < 0 || (clip.durationMs >= 0 && timeMs > clip.durationMs)) {
            return Outcome.Failure(AppError("VIDEO_THUMB", "เวลา $timeMs ms เกินความยาวคลิป"))
        }
        val out = VideoInfo(dst, "PNG", 0, clip.width, clip.height)
        store[dst] = out
        return Outcome.Success(out)
    }

    override suspend fun trim(src: String, dst: String, startMs: Long, endMs: Long): Outcome<VideoInfo> {
        val clip = store[src]
            ?: return Outcome.Failure(AppError("VIDEO_TRIM", "ไม่พบวิดีโอ $src (fake นี้ต้อง put() ก่อน)"))
        if (endMs <= startMs || startMs < 0) {
            return Outcome.Failure(AppError("VIDEO_TRIM", "ช่วงเวลาไม่ถูกต้อง ($startMs..$endMs ms)"))
        }
        val out = clip.copy(path = dst, durationMs = endMs - startMs)
        store[dst] = out
        return Outcome.Success(out)
    }

    override suspend fun extractAudio(src: String, dst: String): Outcome<VideoInfo> {
        val clip = store[src]
            ?: return Outcome.Failure(AppError("VIDEO_AUDIO", "ไม่พบวิดีโอ $src (fake นี้ต้อง put() ก่อน)"))
        if (!clip.hasAudio) {
            return Outcome.Failure(AppError("VIDEO_NO_AUDIO", "คลิปนี้ไม่มีแทร็กเสียง"))
        }
        val out = VideoInfo(dst, "M4A", clip.durationMs, hasAudio = true)
        store[dst] = out
        return Outcome.Success(out)
    }
}
