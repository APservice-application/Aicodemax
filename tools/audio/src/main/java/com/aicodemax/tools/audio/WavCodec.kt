package com.aicodemax.tools.audio

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.RandomAccessFile

/** Pure-JVM WAV PCM read/write (8/16/24/32-bit int + 32-bit float). Runs on Android too. */
object WavCodec {
    fun read(file: File): Outcome<PcmAudio> {
        if (!file.isFile) {
            return Outcome.Failure(AppError("AUDIO_NO_FILE", "ไม่พบไฟล์ ${file.path}"))
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            return Outcome.Failure(AppError("AUDIO_READ", "อ่านไฟล์ไม่ได้: ${e.message}"))
        }
        return decode(bytes)
    }

    /**
     * Read only [startMs, endMs) from a PCM/float WAV. Unlike [read], this
     * does not load the entire asset when rendering a short timeline slice.
     * An empty window beyond EOF returns zero frames (the mixer supplies silence).
     */
    fun readRange(file: File, startMs: Long, endMs: Long): Outcome<PcmAudio> {
        if (!file.isFile) return Outcome.Failure(AppError("AUDIO_NO_FILE", "ไม่พบไฟล์ ${file.path}"))
        if (startMs < 0 || endMs <= startMs || endMs - startMs > 75_000) {
            return Outcome.Failure(AppError("AUDIO_RANGE", "ช่วงเสียงต้องยาวกว่า 0 และไม่เกิน 75 วินาที"))
        }
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 44) return Outcome.Failure(AppError("AUDIO_NO_WAV", "ไม่ใช่ไฟล์ WAV"))
                val head = ByteArray(12)
                raf.readFully(head)
                if (ascii(head, 0, 4) != "RIFF" || ascii(head, 8, 4) != "WAVE") {
                    return Outcome.Failure(AppError("AUDIO_NO_WAV", "ไม่ใช่ไฟล์ WAV"))
                }
                var fmt: ByteArray? = null
                var dataOff = -1L
                var dataBytes = 0L
                while (raf.filePointer + 8 <= raf.length()) {
                    val id = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
                    val size = ByteArray(4).also { raf.readFully(it) }.let {
                        ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
                    }
                    val start = raf.filePointer
                    if (id == "fmt " && size >= 16 && start + 16 <= raf.length()) {
                        fmt = ByteArray(16).also { raf.readFully(it) }
                    } else if (id == "data") {
                        dataOff = start
                        dataBytes = size.coerceAtMost(raf.length() - start)
                    }
                    val next = start + size + (size and 1L)
                    if (fmt != null && dataOff >= 0) break
                    if (next <= start || next > raf.length()) break
                    raf.seek(next)
                }
                val formatBytes = fmt ?: return Outcome.Failure(AppError("AUDIO_WAV_FMT", "WAV นี้ไม่มี fmt/data"))
                if (dataOff < 0) return Outcome.Failure(AppError("AUDIO_WAV_FMT", "WAV นี้ไม่มี fmt/data"))
                val header = ByteBuffer.wrap(formatBytes).order(ByteOrder.LITTLE_ENDIAN)
                val kind = header.short.toInt() and 0xFFFF
                val channels = header.short.toInt() and 0xFFFF
                val rate = header.int
                header.int // average byte rate (recomputed from the actual PCM layout below)
                header.short // block alignment
                val bits = header.short.toInt() and 0xFFFF
                if (kind !in listOf(1, 3) || channels !in 1..2 || rate !in 8_000..192_000 || bits !in listOf(8, 16, 24, 32) ||
                    (kind == 3 && bits != 32)
                ) return Outcome.Failure(AppError("AUDIO_WAV_FMT", "WAV นี้ไม่ใช่ PCM/float mono/stereo"))
                val stride = channels * (bits / 8)
                val totalFrames = dataBytes / stride
                val first = (startMs * rate / 1000).coerceIn(0L, totalFrames)
                val last = (endMs * rate / 1000).coerceIn(first, totalFrames)
                val length = (last - first).toInt() * stride
                if (length == 0) return Outcome.Success(PcmAudio(rate, channels, FloatArray(0)))
                val raw = ByteArray(length)
                raf.seek(dataOff + first * stride)
                raf.readFully(raw)
                val wrapped = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN).apply {
                    put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + length)
                    put("WAVE".toByteArray(Charsets.US_ASCII))
                    put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16); put(formatBytes)
                    put("data".toByteArray(Charsets.US_ASCII)); putInt(length); put(raw)
                }.array()
                decode(wrapped)
            }
        } catch (e: Exception) {
            Outcome.Failure(AppError("AUDIO_READ", "อ่านช่วง WAV ไม่ได้: ${e.message}"))
        }
    }

    fun decode(bytes: ByteArray): Outcome<PcmAudio> {
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") {
            return Outcome.Failure(AppError("AUDIO_NO_WAV", "ไม่ใช่ไฟล์ WAV"))
        }
        var audioFormat = -1
        var channels = 0
        var sampleRate = 0
        var bits = 0
        var dataOff = -1
        var dataLen = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = ascii(bytes, pos, 4)
            val len = u32le(bytes, pos + 4)
            if (id == "fmt " && len >= 16 && pos + 8 + 16 <= bytes.size) {
                audioFormat = u16le(bytes, pos + 8)
                channels = u16le(bytes, pos + 10)
                sampleRate = u32le(bytes, pos + 12)
                bits = u16le(bytes, pos + 22)
            }
            if (id == "data") {
                dataOff = pos + 8
                dataLen = minOf(len, bytes.size - dataOff)
            }
            pos += 8 + len + (len and 1)
        }
        if (audioFormat != 1 && audioFormat != 3) {
            return Outcome.Failure(AppError("AUDIO_WAV_FMT", "WAV นี้บีบอัดแบบอื่น (format=$audioFormat) — รองรับ PCM/float"))
        }
        if (channels !in 1..2 || sampleRate <= 0 || (bits != 8 && bits != 16 && bits != 24 && bits != 32)) {
            return Outcome.Failure(AppError("AUDIO_WAV_FMT", "WAV นี้แปลก (ch=$channels rate=$sampleRate bits=$bits)"))
        }
        if (audioFormat == 3 && bits != 32) {
            return Outcome.Failure(AppError("AUDIO_WAV_FMT", "float WAV ต้อง 32-bit"))
        }
        if (dataOff < 0) {
            return Outcome.Failure(AppError("AUDIO_WAV_FMT", "WAV นี้ไม่มีข้อมูลเสียง"))
        }
        val bytesPerSample = bits / 8
        val frames = dataLen / (bytesPerSample * channels)
        if (frames <= 0) {
            return Outcome.Failure(AppError("AUDIO_WAV_FMT", "WAV นี้ไม่มีข้อมูลเสียง"))
        }
        val samples = FloatArray(frames * channels)
        val buf = ByteBuffer.wrap(bytes, dataOff, frames * bytesPerSample * channels).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            samples[i] = when (bits) {
                8 -> ((buf.get().toInt() and 0xFF) - 128) / 128f
                16 -> buf.short / 32768f
                24 -> {
                    val b0 = buf.get().toInt() and 0xFF
                    val b1 = buf.get().toInt() and 0xFF
                    val b2 = buf.get().toInt()
                    val v = (b2 shl 16) or (b1 shl 8) or b0
                    v / 8388608f
                }
                else -> if (audioFormat == 3) buf.float else buf.int / 2147483648f
            }
        }
        return Outcome.Success(PcmAudio(sampleRate, channels, samples))
    }

    /** Encodes 16-bit PCM WAV. */
    fun encode(audio: PcmAudio): ByteArray {
        val dataLen = audio.samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1) // PCM
        buf.putShort(audio.channels.toShort())
        buf.putInt(audio.sampleRate)
        buf.putInt(audio.sampleRate * audio.channels * 2)
        buf.putShort((audio.channels * 2).toShort())
        buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataLen)
        for (sample in audio.samples) {
            buf.putShort((sample.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        }
        return buf.array()
    }

    fun write(file: File, audio: PcmAudio): Outcome<Unit> = try {
        file.parentFile?.mkdirs()
        file.writeBytes(encode(audio))
        Outcome.Success(Unit)
    } catch (e: Exception) {
        Outcome.Failure(AppError("AUDIO_WRITE", "เขียนไฟล์ไม่ได้: ${e.message}"))
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String {
        if (off + len > b.size) return ""
        return String(b, off, len, Charsets.US_ASCII)
    }

    private fun u16le(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
