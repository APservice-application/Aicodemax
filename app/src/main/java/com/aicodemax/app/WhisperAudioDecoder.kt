package com.aicodemax.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.aicodemax.core.common.Outcome
import com.aicodemax.core.common.runOutcome
import java.nio.ByteOrder

/**
 * CP-140: decode any audio/video file to 16 kHz mono float PCM for whisper.
 * Pure Android framework (MediaExtractor + MediaCodec) — no extra native deps.
 */
object WhisperAudioDecoder {
    const val TARGET_RATE = 16000
    const val MAX_MINUTES = 30

    data class Decoded(val samples: FloatArray, val durationMs: Long, val mime: String)

    fun decode(path: String): Outcome<Decoded> = runOutcome("STT_DECODE") {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(path)
            } catch (e: Exception) {
                throw IllegalStateException("เปิดไฟล์ไม่ได้: ${e.message}")
            }
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) throw IllegalStateException("ไฟล์นี้ไม่มีแทร็กเสียง")
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = try {
                format.getLong(MediaFormat.KEY_DURATION)
            } catch (_: Exception) {
                0L
            }
            if (durationUs > MAX_MINUTES * 60L * 1_000_000L) {
                throw IllegalStateException("ไฟล์ยาวเกิน $MAX_MINUTES นาที — ตัดให้สั้นก่อน")
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/unknown"
            extractor.selectTrack(trackIndex)
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val pcm16 = decodeAll(extractor, codec)
                codec.stop()
                val mono = downmixToMono(pcm16, channels)
                val resampled = resample(mono, srcRate, TARGET_RATE)
                // Hard cap: 35 min of 16 kHz mono (safety if duration metadata lied).
                val capped = if (resampled.size > 35 * 60 * TARGET_RATE) {
                    resampled.copyOf(35 * 60 * TARGET_RATE)
                } else {
                    resampled
                }
                val floats = FloatArray(capped.size) { capped[it] / 32768f }
                val durationMs = if (durationUs > 0) durationUs / 1000 else (floats.size * 1000L / TARGET_RATE)
                Decoded(floats, durationMs, mime)
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun decodeAll(extractor: MediaExtractor, codec: MediaCodec): ShortArray {
        val out = mutableListOf<Short>()
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        var stalls = 0
        while (!outputEos) {
            if (!inputEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex) ?: throw IllegalStateException("input buffer หาย")
                    val read = extractor.readSampleData(buffer, 0)
                    if (read < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex >= 0 -> {
                    stalls = 0
                    val buffer = codec.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0) {
                        val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val chunk = ShortArray(info.size / 2)
                        shorts.get(chunk)
                        for (s in chunk) out.add(s)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    stalls++
                    if (stalls > 3000) throw IllegalStateException("ถอดรหัสเสียงค้าง — ไฟล์อาจเสีย")
                }
            }
        }
        if (out.isEmpty()) throw IllegalStateException("ถอดเสียงได้ 0 ตัวอย่าง — ไฟล์อาจไม่มีเสียงจริง")
        return out.toShortArray()
    }

    private fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return interleaved
        val frames = interleaved.size / channels
        return ShortArray(frames) { frame ->
            var sum = 0
            for (c in 0 until channels) sum += interleaved[frame * channels + c]
            (sum / channels).toShort()
        }
    }

    private fun resample(mono: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate || mono.isEmpty()) return mono
        val ratio = srcRate.toDouble() / dstRate
        val dstSize = (mono.size / ratio).toInt().coerceAtLeast(1)
        return ShortArray(dstSize) { i ->
            val pos = i * ratio
            val lo = pos.toInt().coerceIn(0, mono.size - 1)
            val hi = (lo + 1).coerceIn(0, mono.size - 1)
            val frac = (pos - lo).toFloat()
            (mono[lo] * (1 - frac) + mono[hi] * frac).toInt().toShort()
        }
    }
}
