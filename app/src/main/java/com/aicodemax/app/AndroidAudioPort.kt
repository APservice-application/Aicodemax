package com.aicodemax.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import com.aicodemax.tools.audio.AudioInfo
import com.aicodemax.tools.audio.AudioOps
import com.aicodemax.tools.audio.AudioPort
import com.aicodemax.tools.audio.AudioProbe
import com.aicodemax.tools.audio.PcmAudio
import com.aicodemax.tools.audio.WavCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CP-62 Android audio: pure WAV pipeline ([WavCodec]/[AudioOps], unit-tested)
 * + MediaCodec decode for MP3/M4A/OGG/FLAC. Edits always land as WAV (honest:
 * no lossy re-encode yet).
 */
class AndroidAudioPort : AudioPort {
    override suspend fun info(path: String): Outcome<AudioInfo> =
        withContext(Dispatchers.IO) {
            when (val probed = AudioProbe.probe(File(path))) {
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
        editOne(src, dst, "AUDIO_TRIM") { clip -> AudioOps.trim(clip, startMs, endMs) }

    override suspend fun concat(srcs: List<String>, dst: String): Outcome<AudioInfo> =
        withContext(Dispatchers.IO) {
            if (srcs.isEmpty()) {
                return@withContext Outcome.Failure(AppError("AUDIO_CONCAT", "ไม่ได้บอกไฟล์ต้นฉบับ"))
            }
            val clips = mutableListOf<PcmAudio>()
            for (src in srcs) {
                when (val decoded = decode(src)) {
                    is Outcome.Failure -> return@withContext decoded
                    is Outcome.Success -> clips.add(decoded.value)
                }
            }
            val out = try {
                AudioOps.concat(clips)
            } catch (e: IllegalArgumentException) {
                return@withContext Outcome.Failure(AppError("AUDIO_CONCAT", e.message ?: "bad args"))
            }
            writeOut(dst, out, "AUDIO_CONCAT")
        }

    override suspend fun gain(src: String, dst: String, db: Double): Outcome<AudioInfo> =
        editOne(src, dst, "AUDIO_GAIN") { clip -> AudioOps.gain(clip, db) }

    override suspend fun fade(src: String, dst: String, fadeInMs: Long, fadeOutMs: Long): Outcome<AudioInfo> =
        editOne(src, dst, "AUDIO_FADE") { clip -> AudioOps.fade(clip, fadeInMs, fadeOutMs) }

    override suspend fun voiceFx(src: String, dst: String, semitones: Int, robot: Boolean, echoMs: Long, echoDecay: Int): Outcome<AudioInfo> =
        editOne(src, dst, "AUDIO_VOICEFX") { clip ->
            com.aicodemax.tools.audio.VoiceFx.apply(clip, semitones, robot, echoMs, echoDecay)
        }

    override suspend fun synthMusic(style: String, seconds: Int, dst: String): Outcome<AudioInfo> =
        withContext(Dispatchers.IO) {
            try {
                writeOut(dst, com.aicodemax.tools.audio.Synth.musicBed(style, seconds), "AUDIO_SYNTH")
            } catch (e: IllegalArgumentException) {
                Outcome.Failure(AppError("AUDIO_SYNTH", e.message ?: "bad args"))
            }
        }

    override suspend fun synthSfx(kind: String, dst: String): Outcome<AudioInfo> =
        withContext(Dispatchers.IO) {
            try {
                writeOut(dst, com.aicodemax.tools.audio.Synth.sfx(kind), "AUDIO_SYNTH")
            } catch (e: IllegalArgumentException) {
                Outcome.Failure(AppError("AUDIO_SYNTH", e.message ?: "bad args"))
            }
        }

    override suspend fun speech(path: String, thresholdDb: Double, minSpeechMs: Long, minSilenceMs: Long, padMs: Long): Outcome<com.aicodemax.tools.audio.SpeechAnalysis> =
        withContext(Dispatchers.IO) {
            when (val decoded = decode(path)) {
                is Outcome.Failure -> decoded
                is Outcome.Success -> Outcome.Success(
                    com.aicodemax.tools.audio.Speech.analyze(decoded.value, thresholdDb, minSpeechMs, minSilenceMs, padMs),
                )
            }
        }

    override suspend fun beats(path: String): Outcome<com.aicodemax.tools.audio.BeatAnalysis> =
        withContext(Dispatchers.IO) {
            when (val decoded = decode(path)) {
                is Outcome.Failure -> decoded
                is Outcome.Success -> Outcome.Success(com.aicodemax.tools.audio.Beats.analyze(decoded.value))
            }
        }

    private suspend fun editOne(
        src: String,
        dst: String,
        code: String,
        op: (PcmAudio) -> PcmAudio,
    ): Outcome<AudioInfo> = withContext(Dispatchers.IO) {
        val clip = when (val decoded = decode(src)) {
            is Outcome.Failure -> return@withContext decoded
            is Outcome.Success -> decoded.value
        }
        val out = try {
            op(clip)
        } catch (e: IllegalArgumentException) {
            return@withContext Outcome.Failure(AppError(code, e.message ?: "bad args"))
        }
        writeOut(dst, out, code)
    }

    private fun writeOut(dst: String, audio: PcmAudio, code: String): Outcome<AudioInfo> {
        val target = if (dst.substringAfterLast('.', "").lowercase() == "wav") {
            dst
        } else {
            dst.substringBeforeLast('.') + ".wav"
        }
        return when (val written = WavCodec.write(File(target), audio)) {
            is Outcome.Failure -> Outcome.Failure(AppError(code, written.error.message))
            is Outcome.Success -> Outcome.Success(
                AudioInfo(target, "WAV", audio.durationMs, audio.sampleRate, audio.channels, File(target).length()),
            )
        }
    }

    /** Decodes WAV/MP3/M4A/OGG/FLAC to PCM — also used by the render mixer. */
    fun decodeToPcm(src: String): Outcome<PcmAudio> = decode(src)

    private fun decode(src: String): Outcome<PcmAudio> {
        if (src.substringAfterLast('.', "").lowercase() == "wav") {
            return WavCodec.read(File(src))
        }
        return decodeCompressed(src)
    }

    /**
     * Decodes MP3/M4A/OGG/FLAC via MediaCodec (sync loop, 2-min cap).
     * 16-bit PCM expected; float output is converted when the codec says so.
     */
    private fun decodeCompressed(src: String): Outcome<PcmAudio> {
        val file = File(src)
        if (!file.isFile) {
            return Outcome.Failure(AppError("AUDIO_NO_FILE", "ไม่พบไฟล์ $src"))
        }
        if (file.length() > 200L * 1024 * 1024) {
            return Outcome.Failure(AppError("AUDIO_TOO_BIG", "ไฟล์ใหญ่เกิน 200MB — ตัดด้วยช่วงเวลาสั้นๆ ก่อนครับ"))
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(src)
        } catch (e: Exception) {
            extractor.release()
            return Outcome.Failure(AppError("AUDIO_DECODE", "เปิดไฟล์ไม่ได้: ${e.message}"))
        }
        var track = -1
        var mime: String? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val candidate = try {
                format.getString(MediaFormat.KEY_MIME)
            } catch (_: Exception) {
                null
            }
            if (candidate != null && candidate.startsWith("audio/")) {
                track = i
                mime = candidate
                break
            }
        }
        if (track < 0 || mime == null) {
            extractor.release()
            return Outcome.Failure(AppError("AUDIO_DECODE", "ไฟล์นี้ไม่มีแทร็กเสียง"))
        }
        val decoder = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            extractor.release()
            return Outcome.Failure(AppError("AUDIO_DECODE", "เครื่องนี้ถอด $mime ไม่ได้: ${e.message}"))
        }
        try {
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            decoder.configure(format, null, null, 0)
            decoder.start()
            var sampleRate = try {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } catch (_: Exception) {
                44100
            }
            var channels = try {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } catch (_: Exception) {
                2
            }
            var floatOut = false
            val pcmBytes = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 120_000
            var sawInputEos = false
            var done = false
            while (!done) {
                if (System.currentTimeMillis() > deadline) {
                    return Outcome.Failure(AppError("AUDIO_DECODE", "ถอดเสียงนานเกิน 2 นาที — ลองไฟล์สั้นกว่านี้ครับ"))
                }
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = decoder.getInputBuffer(inIndex)
                        if (buf == null) {
                            sawInputEos = true
                        } else {
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        sampleRate = try {
                            out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        } catch (_: Exception) {
                            sampleRate
                        }
                        channels = try {
                            out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        } catch (_: Exception) {
                            channels
                        }
                        floatOut = try {
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        } catch (_: Exception) {
                            false
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (sawInputEos && info.size == 0) {
                            // Keep looping; EOS arrives via output flags.
                        }
                    }
                    else -> {
                        if (outIndex >= 0) {
                            val buf = decoder.getOutputBuffer(outIndex)
                            if (buf != null && info.size > 0) {
                                val chunk = ByteArray(info.size)
                                buf.get(chunk)
                                pcmBytes.write(chunk)
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                done = true
                            }
                        }
                    }
                }
            }
            val raw = pcmBytes.toByteArray()
            if (raw.isEmpty()) {
                return Outcome.Failure(AppError("AUDIO_DECODE", "ถอดเสียงได้ 0 ไบต์ (ไฟล์อาจเสีย)"))
            }
            if (channels !in 1..2) {
                return Outcome.Failure(AppError("AUDIO_DECODE", "รองรับแค่ mono/stereo (ไฟล์นี้ $channels ch)"))
            }
            val samples = if (floatOut) {
                val count = raw.size / 4
                FloatArray(count) { i ->
                    ByteBuffer.wrap(raw, i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).float
                }
            } else {
                val count = raw.size / 2
                val little = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                FloatArray(count) { i -> little.get(i) / 32768f }
            }
            val usable = (samples.size / channels) * channels
            return Outcome.Success(PcmAudio(sampleRate, channels, samples.copyOf(usable)))
        } catch (e: Exception) {
            return Outcome.Failure(AppError("AUDIO_DECODE", "ถอดเสียงไม่ได้: ${e.message}"))
        } finally {
            try {
                decoder.stop()
            } catch (_: Exception) {
            }
            try {
                decoder.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }
}
