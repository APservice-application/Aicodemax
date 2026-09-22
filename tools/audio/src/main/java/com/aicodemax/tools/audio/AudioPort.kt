package com.aicodemax.tools.audio

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome

/** Result of an audio operation. */
data class AudioInfo(
    val path: String,
    val format: String,
    val durationMs: Long,
    val sampleRate: Int = -1,
    val channels: Int = -1,
    val sizeBytes: Long = -1,
) {
    val summary: String get() = "$format ${durationMs}ms" +
        (if (sampleRate > 0) " ${sampleRate}Hz" else "") +
        (if (channels > 0) " ${channels}ch" else "")
}

/**
 * CP-62 audio contract: probe + edit (trim/concat/gain/fade, WAV out).
 * Pure-JVM WAV pipeline runs everywhere incl. Android; compressed decode
 * (MP3/M4A/OGG/FLAC) is provided by the Android port via MediaCodec.
 */
interface AudioPort {
    suspend fun info(path: String): Outcome<AudioInfo>
    suspend fun trim(src: String, dst: String, startMs: Long, endMs: Long): Outcome<AudioInfo>
    suspend fun concat(srcs: List<String>, dst: String): Outcome<AudioInfo>
    suspend fun gain(src: String, dst: String, db: Double): Outcome<AudioInfo>
    suspend fun fade(src: String, dst: String, fadeInMs: Long, fadeOutMs: Long): Outcome<AudioInfo>

    /** CP-85 §31: detect tempo + beat grid (offline, no ML). */
    suspend fun beats(path: String): Outcome<BeatAnalysis>
}

/**
 * In-memory fake: stores [PcmAudio] by path. Real files on disk are probed
 * for headers ([info] only); edits require the clip to be [put] first.
 */
class InMemoryAudioPort : AudioPort {
    private val store = mutableMapOf<String, PcmAudio>()

    fun put(path: String, audio: PcmAudio) {
        store[path] = audio
    }

    fun get(path: String): PcmAudio? = store[path]

    override suspend fun info(path: String): Outcome<AudioInfo> {
        store[path]?.let {
            return Outcome.Success(AudioInfo(path, "MEM", it.durationMs, it.sampleRate, it.channels))
        }
        return when (val probed = AudioProbe.probe(java.io.File(path))) {
            is Outcome.Failure -> probed
            is Outcome.Success -> Outcome.Success(
                AudioInfo(
                    path, probed.value.format, probed.value.durationMs,
                    probed.value.sampleRate, probed.value.channels, probed.value.sizeBytes,
                ),
            )
        }
    }

    override suspend fun trim(src: String, dst: String, startMs: Long, endMs: Long): Outcome<AudioInfo> =
        edit(listOf(src), dst, "AUDIO_TRIM") { AudioOps.trim(it.first(), startMs, endMs) }

    override suspend fun concat(srcs: List<String>, dst: String): Outcome<AudioInfo> =
        edit(srcs, dst, "AUDIO_CONCAT") { AudioOps.concat(it) }

    override suspend fun gain(src: String, dst: String, db: Double): Outcome<AudioInfo> =
        edit(listOf(src), dst, "AUDIO_GAIN") { AudioOps.gain(it.first(), db) }

    override suspend fun fade(src: String, dst: String, fadeInMs: Long, fadeOutMs: Long): Outcome<AudioInfo> =
        edit(listOf(src), dst, "AUDIO_FADE") { AudioOps.fade(it.first(), fadeInMs, fadeOutMs) }

    private inline fun edit(
        srcs: List<String>,
        dst: String,
        code: String,
        op: (List<PcmAudio>) -> PcmAudio,
    ): Outcome<AudioInfo> {
        if (srcs.isEmpty()) {
            return Outcome.Failure(AppError(code, "ไม่ได้บอกไฟล์ต้นฉบับ"))
        }
        val clips = mutableListOf<PcmAudio>()
        for (src in srcs) {
            clips.add(store[src] ?: return Outcome.Failure(AppError(code, "ไม่พบเสียง $src (fake นี้ต้อง put() ก่อน)")))
        }
        return try {
            val out = op(clips)
            store[dst] = out
            Outcome.Success(AudioInfo(dst, "MEM", out.durationMs, out.sampleRate, out.channels))
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(AppError(code, e.message ?: "bad args"))
        }
    }

    override suspend fun beats(path: String): Outcome<BeatAnalysis> {
        val clip = store[path]
            ?: return Outcome.Failure(com.aicodemax.core.common.AppError("AUDIO_MISSING", "ไม่มีเสียง $path (put ก่อน)"))
        return Outcome.Success(Beats.analyze(clip))
    }
}
