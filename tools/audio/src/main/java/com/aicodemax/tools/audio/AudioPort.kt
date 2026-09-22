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

    /** CP-86 §29: voice changer (pitch/robot/echo). Edits land as WAV. */
    suspend fun voiceFx(src: String, dst: String, semitones: Int, robot: Boolean, echoMs: Long, echoDecay: Int): Outcome<AudioInfo>

    /** CP-86 §30: procedural music bed → WAV. */
    suspend fun synthMusic(style: String, seconds: Int, dst: String): Outcome<AudioInfo>

    /** CP-86 §30: procedural one-shot SFX → WAV. */
    suspend fun synthSfx(kind: String, dst: String): Outcome<AudioInfo>

    /** CP-95: mix bed under voice. */
    suspend fun mix(srcA: String, srcB: String, dst: String, gainB: Double = 1.0, offsetMs: Long = 0): Outcome<AudioInfo>

    /** CP-95: peak normalize. */
    suspend fun normalize(src: String, dst: String, peakDb: Double = -3.0): Outcome<AudioInfo>

    /** CP-95: drop silent ranges (offline speech gate). */
    suspend fun autocut(src: String, dst: String, thresholdDb: Double = -40.0, minSpeechMs: Long = 300, minSilenceMs: Long = 500, padMs: Long = 150): Outcome<AudioInfo>

    /** CP-94: starts in-app audio recording to [dst] (MediaRecorder on Android). */
    suspend fun recordStart(dst: String): Outcome<Unit>

    /** CP-94: stops recording started by [recordStart]; result is a decodable file. */
    suspend fun recordStop(): Outcome<AudioInfo>

    /** CP-87 §32/§40: speech ranges + energy curve (offline, no ML). */
    suspend fun speech(
        path: String,
        thresholdDb: Double = -40.0,
        minSpeechMs: Long = 300,
        minSilenceMs: Long = 500,
        padMs: Long = 150,
    ): Outcome<SpeechAnalysis>
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

    override suspend fun voiceFx(src: String, dst: String, semitones: Int, robot: Boolean, echoMs: Long, echoDecay: Int): Outcome<AudioInfo> =
        edit(listOf(src), dst, "AUDIO_VOICEFX") { VoiceFx.apply(it.first(), semitones, robot, echoMs, echoDecay) }

    override suspend fun synthMusic(style: String, seconds: Int, dst: String): Outcome<AudioInfo> =
        synthTo(dst, "AUDIO_SYNTH") { Synth.musicBed(style, seconds) }

    override suspend fun synthSfx(kind: String, dst: String): Outcome<AudioInfo> =
        synthTo(dst, "AUDIO_SYNTH") { Synth.sfx(kind) }

    override suspend fun mix(srcA: String, srcB: String, dst: String, gainB: Double, offsetMs: Long): Outcome<AudioInfo> =
        edit(listOf(srcA, srcB), dst, "AUDIO_MIX") { AudioOps.mix(it[0], it[1], gainB, offsetMs) }

    override suspend fun normalize(src: String, dst: String, peakDb: Double): Outcome<AudioInfo> =
        edit(listOf(src), dst, "AUDIO_NORMALIZE") { AudioOps.normalize(it.first(), peakDb) }

    override suspend fun autocut(src: String, dst: String, thresholdDb: Double, minSpeechMs: Long, minSilenceMs: Long, padMs: Long): Outcome<AudioInfo> =
        edit(listOf(src), dst, "AUDIO_AUTOCUT") {
            AudioOps.autocut(it.first(), Speech.analyze(it.first(), thresholdDb, minSpeechMs, minSilenceMs, padMs).ranges)
        }

    private var pendingRecord: String? = null

    override suspend fun recordStart(dst: String): Outcome<Unit> {
        if (pendingRecord != null) return Outcome.Failure(AppError("AUDIO_RECORDING", "กำลังอัดอยู่แล้ว"))
        pendingRecord = dst
        return Outcome.Success(Unit)
    }

    override suspend fun recordStop(): Outcome<AudioInfo> {
        val dst = pendingRecord
            ?: return Outcome.Failure(AppError("AUDIO_NOT_RECORDING", "ยังไม่ได้เริ่มอัด (recordStart ก่อน)"))
        pendingRecord = null
        val tone = Synth.musicBed("calm", 2)
        store[dst] = tone
        return Outcome.Success(AudioInfo(dst, "WAV", 2000L, tone.sampleRate, tone.channels))
    }

    override suspend fun speech(path: String, thresholdDb: Double, minSpeechMs: Long, minSilenceMs: Long, padMs: Long): Outcome<SpeechAnalysis> {
        val clip = store[path]
            ?: return Outcome.Failure(AppError("AUDIO_MISSING", "ไม่มีเสียง $path (put ก่อน)"))
        return Outcome.Success(Speech.analyze(clip, thresholdDb, minSpeechMs, minSilenceMs, padMs))
    }

    private inline fun synthTo(dst: String, code: String, op: () -> PcmAudio): Outcome<AudioInfo> {
        return try {
            val out = op()
            store[dst] = out
            Outcome.Success(AudioInfo(dst, "MEM", out.durationMs, out.sampleRate, out.channels))
        } catch (e: IllegalArgumentException) {
            Outcome.Failure(AppError(code, e.message ?: "bad args"))
        }
    }
}
