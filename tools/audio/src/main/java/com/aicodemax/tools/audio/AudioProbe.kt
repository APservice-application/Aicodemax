package com.aicodemax.tools.audio

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.io.File

/** Format + stream facts. -1 = unknown; [exact] tells estimate from fact. */
data class AudioProbeInfo(
    val format: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val sizeBytes: Long,
    val exact: Boolean,
    val note: String = "",
)

/**
 * CP-62: header probe for WAV/FLAC/MP4-M4A/MP3/OGG.
 * WAV+FLAC+MP4 durations are exact; MP3 is an honest CBR estimate; OGG is format-only.
 */
object AudioProbe {
    fun probe(file: File): Outcome<AudioProbeInfo> {
        if (!file.isFile) {
            return Outcome.Failure(AppError("AUDIO_NO_FILE", "ไม่พบไฟล์ ${file.path}"))
        }
        val head = try {
            file.inputStream().use { input ->
                val buf = ByteArray(4096)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
        } catch (e: Exception) {
            return Outcome.Failure(AppError("AUDIO_READ", "อ่านไฟล์ไม่ได้: ${e.message}"))
        }
        val info = parse(head, file.length())
            ?: return Outcome.Failure(AppError("AUDIO_UNKNOWN", "ไม่รู้จักฟอร์แมตเสียง (รองรับ WAV/FLAC/M4A/MP3/OGG)"))
        return Outcome.Success(info)
    }

    /** Header-only parse; null when unknown. Exposed for tests. */
    fun parse(head: ByteArray, sizeBytes: Long = head.size.toLong()): AudioProbeInfo? {
        if (head.size < 16) return null
        if (ascii(head, 0, 4) == "RIFF" && ascii(head, 8, 4) == "WAVE") return wav(head, sizeBytes)
        if (ascii(head, 0, 4) == "fLaC") return flac(head, sizeBytes)
        if (head.size >= 12 && ascii(head, 4, 4) == "ftyp") return mp4(head, sizeBytes)
        if (ascii(head, 0, 4) == "OggS") {
            return AudioProbeInfo("OGG", -1, -1, -1, sizeBytes, false, "ต้อง decode เต็มถึงจะรู้ความยาว")
        }
        val mp3 = mp3(head, sizeBytes)
        if (mp3 != null) return mp3
        return null
    }

    private fun wav(head: ByteArray, sizeBytes: Long): AudioProbeInfo? {
        var pos = 12
        var channels = -1
        var rate = -1
        var bits = -1
        var dataLen = -1
        while (pos + 8 <= head.size) {
            val id = ascii(head, pos, 4)
            val len = u32le(head, pos + 4)
            if (id == "fmt " && len >= 16 && pos + 8 + 16 <= head.size) {
                channels = u16le(head, pos + 10)
                rate = u32le(head, pos + 12)
                bits = u16le(head, pos + 22)
            }
            if (id == "data") dataLen = len
            if (len < 0 || pos + 8 + len < pos + 8) break
            pos += 8 + len + (len and 1)
            if (pos > head.size) break
        }
        if (channels <= 0 || rate <= 0 || bits <= 0) {
            return AudioProbeInfo("WAV", -1, -1, -1, sizeBytes, false, "fmt แปลก")
        }
        val bytesPerFrame = channels * (bits / 8)
        val duration = if (dataLen > 0 && bytesPerFrame > 0) dataLen * 1000L / (rate * bytesPerFrame) else -1
        return AudioProbeInfo("WAV", duration, rate, channels, sizeBytes, dataLen > 0)
    }

    private fun flac(head: ByteArray, sizeBytes: Long): AudioProbeInfo? {
        // fLaC + STREAMINFO (type 0): 3-byte len, then 10-byte info + 8-byte packed.
        if (head.size < 42) return AudioProbeInfo("FLAC", -1, -1, -1, sizeBytes, false)
        if ((head[4].toInt() and 0x7F) != 0) return AudioProbeInfo("FLAC", -1, -1, -1, sizeBytes, false)
        val info = head.copyOfRange(8, head.size)
        if (info.size < 34) return AudioProbeInfo("FLAC", -1, -1, -1, sizeBytes, false)
        val rate = ((info[10].toInt() and 0xFF) shl 12) or ((info[11].toInt() and 0xFF) shl 4) or
            ((info[12].toInt() and 0xFF) ushr 4)
        val channels = (((info[12].toInt() and 0xFF) ushr 1) and 0x07) + 1
        // total = low nibble of [13] + [14..17].
        var total = ((info[13].toInt() and 0x0F).toLong() shl 32)
        total = total or ((info[14].toInt() and 0xFF).toLong() shl 24)
        total = total or ((info[15].toInt() and 0xFF).toLong() shl 16)
        total = total or ((info[16].toInt() and 0xFF).toLong() shl 8)
        total = total or (info[17].toInt() and 0xFF).toLong()
        if (rate <= 0) return AudioProbeInfo("FLAC", -1, -1, channels, sizeBytes, false)
        return AudioProbeInfo("FLAC", total * 1000 / rate, rate, channels, sizeBytes, true)
    }

    private fun mp4(head: ByteArray, sizeBytes: Long): AudioProbeInfo? {
        // Walk top-level boxes for moov/mvhd; mvhd v0: timescale@12, duration@16 (u32).
        var pos = 0
        while (pos + 8 <= head.size) {
            val len = u32be(head, pos).toLong() and 0xFFFFFFFFL
            val name = ascii(head, pos + 4, 4)
            if (len < 8) break
            if (name == "moov") {
                val end = minOf(head.size, (pos + len).toInt())
                var inner = pos + 8
                while (inner + 8 <= end) {
                    val ilen = u32be(head, inner).toLong() and 0xFFFFFFFFL
                    if (ilen < 8) break
                    if (ascii(head, inner + 4, 4) == "mvhd" && inner + 24 <= end) {
                        val version = head[inner + 8].toInt() and 0xFF
                        return if (version == 0) {
                            val timescale = u32be(head, inner + 20).toLong() and 0xFFFFFFFFL
                            val duration = u32be(head, inner + 24).toLong() and 0xFFFFFFFFL
                            if (timescale <= 0) {
                                AudioProbeInfo("M4A", -1, -1, -1, sizeBytes, false)
                            } else {
                                AudioProbeInfo("M4A", duration * 1000 / timescale, -1, -1, sizeBytes, true)
                            }
                        } else {
                            AudioProbeInfo("M4A", -1, -1, -1, sizeBytes, false, "mvhd v$version")
                        }
                    }
                    inner += ilen.toInt()
                }
                break
            }
            if (len > head.size) break
            pos += len.toInt()
        }
        return AudioProbeInfo("M4A", -1, -1, -1, sizeBytes, false, "ยังหา mvhd ไม่เจอ")
    }

    private val MP3_BITRATES = intArrayOf(
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0,
    )
    private val MP3_RATES = intArrayOf(44100, 48000, 32000, 0)

    private fun mp3(head: ByteArray, sizeBytes: Long): AudioProbeInfo? {
        var start = 0
        // Skip ID3v2 tag.
        if (head.size > 10 && ascii(head, 0, 3) == "ID3") {
            val tagLen = ((head[6].toInt() and 0x7F) shl 21) or ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or (head[9].toInt() and 0x7F)
            start = 10 + tagLen
        }
        var i = start
        while (i + 4 < head.size) {
            if (head[i] == 0xFF.toByte() && (head[i + 1].toInt() and 0xE0) == 0xE0) {
                val b1 = head[i + 1].toInt() and 0xFF
                val b2 = head[i + 2].toInt() and 0xFF
                val version = (b1 ushr 3) and 0x03
                val layer = (b1 ushr 1) and 0x03
                val bitrateIdx = (b2 ushr 4) and 0x0F
                val rateIdx = (b2 ushr 2) and 0x03
                // MPEG1 Layer III only for the estimate; anything else = format-only.
                if (version == 0x03 && layer == 0x01 && bitrateIdx != 0 && bitrateIdx != 0x0F && rateIdx != 0x03) {
                    val bitrate = MP3_BITRATES[bitrateIdx] * 1000
                    val rate = MP3_RATES[rateIdx]
                    val duration = if (bitrate > 0) (sizeBytes - start) * 8000 / bitrate else -1
                    return AudioProbeInfo("MP3", duration, rate, -1, sizeBytes, false, "ประมาณจาก bitrate (CBR)")
                }
                return AudioProbeInfo("MP3", -1, -1, -1, sizeBytes, false, "layer/version นี้ต้อง decode เต็ม")
            }
            i += 1
        }
        return null
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String {
        if (off + len > b.size) return ""
        return String(b, off, len, Charsets.US_ASCII)
    }

    private fun u16le(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun u32be(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
