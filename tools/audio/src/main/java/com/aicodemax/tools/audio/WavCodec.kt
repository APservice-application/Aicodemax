package com.aicodemax.tools.audio

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
