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
    /** CP-102: low-res editing proxy (long side ≤ [maxDim], video only). */
    suspend fun proxy(src: String, dst: String, maxDim: Int = 640): Outcome<VideoInfo>
    /** CP-104: group angles + align by audio onsets (method clap|manual). */
    suspend fun multicamSync(paths: List<String>, method: String = "clap"): Outcome<MulticamGroup>
    /** CP-104: record an angle cut on the group timeline. */
    suspend fun multicamCut(groupId: String, atMs: Long, angle: Int): Outcome<MulticamGroup>
    /** CP-104: cut list (EDL) for the group. */
    suspend fun multicamEdl(groupId: String): Outcome<String>
}

/**
 * In-memory fake: stores [VideoInfo] by path. Real files on disk are probed
 * ([info] only); edits require the clip to be [put] first.
 */
class InMemoryVideoPort : VideoPort {
    private val store = mutableMapOf<String, VideoInfo>()
    private val groups = mutableMapOf<String, MulticamGroup>()
    private var groupSeq = 0

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

    override suspend fun proxy(src: String, dst: String, maxDim: Int): Outcome<VideoInfo> {
        val clip = store[src]
            ?: return Outcome.Failure(AppError("VIDEO_PROXY", "ไม่พบวิดีโอ $src (fake นี้ต้อง put() ก่อน)"))
        if (maxDim < 160) return Outcome.Failure(AppError("VIDEO_PROXY", "maxDim ต้อง ≥ 160"))
        val longer = maxOf(clip.width, clip.height).coerceAtLeast(1)
        val scale = if (longer <= maxDim) 1.0 else maxDim.toDouble() / longer
        val out = clip.copy(
            path = dst,
            width = (clip.width * scale).toInt().coerceAtLeast(2) and 1.inv(),
            height = (clip.height * scale).toInt().coerceAtLeast(2) and 1.inv(),
            hasAudio = false,
        )
        store[dst] = out
        return Outcome.Success(out)
    }

    override suspend fun multicamSync(paths: List<String>, method: String): Outcome<MulticamGroup> {
        if (paths.size < 2) {
            return Outcome.Failure(AppError("VIDEO_MULTICAM", "มัลติแคมต้องมีอย่างน้อย 2 มุม"))
        }
        for (p in paths) {
            if (store[p] == null) {
                return Outcome.Failure(AppError("VIDEO_MULTICAM", "ไม่พบวิดีโอ $p (fake นี้ต้อง put() ก่อน)"))
            }
        }
        val id = "mc_${++groupSeq}"
        val group = MulticamGroup(id, paths, List(paths.size) { 0L }, method.ifEmpty { "manual" }, 0.0, 0)
        groups[id] = group
        return Outcome.Success(group)
    }

    override suspend fun multicamCut(groupId: String, atMs: Long, angle: Int): Outcome<MulticamGroup> {
        val group = groups[groupId]
            ?: return Outcome.Failure(AppError("VIDEO_MULTICAM", "ไม่พบกลุ่ม $groupId"))
        if (angle < 1 || angle > group.angles.size) {
            return Outcome.Failure(AppError("VIDEO_MULTICAM", "มุม $angle เกินจำนวนมุม (${group.angles.size})"))
        }
        if (atMs < 0) return Outcome.Failure(AppError("VIDEO_MULTICAM", "เวลา $atMs ms ใช้ไม่ได้"))
        val next = group.copy(cuts = (group.cuts + MulticamCut(atMs, angle - 1)).sortedBy { it.atMs })
        groups[groupId] = next
        return Outcome.Success(next)
    }

    override suspend fun multicamEdl(groupId: String): Outcome<String> {
        val group = groups[groupId]
            ?: return Outcome.Failure(AppError("VIDEO_MULTICAM", "ไม่พบกลุ่ม $groupId"))
        return Outcome.Success(group.edl())
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
