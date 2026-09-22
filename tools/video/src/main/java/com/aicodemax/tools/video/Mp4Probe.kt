package com.aicodemax.tools.video

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.io.File

/** Container facts. -1 = unknown. */
data class VideoProbeInfo(
    val format: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val sizeBytes: Long,
    val note: String = "",
)

/**
 * CP-63: pure-JVM MP4/M4A/MOV/3GP probe (ftyp + moov: mvhd duration,
 * per-track tkhd dims + mdhd duration + hdlr kind). Runs in unit tests
 * and on device; other containers fall back to MediaMetadataRetriever
 * in the Android port.
 */
object Mp4Probe {
    /** How much of the head we scan for moov (moov can sit behind a big mdat). */
    const val SCAN_LIMIT = 8 * 1024 * 1024

    fun probe(file: File): Outcome<VideoProbeInfo> {
        if (!file.isFile) {
            return Outcome.Failure(AppError("VIDEO_NO_FILE", "ไม่พบไฟล์ ${file.path}"))
        }
        val head = try {
            file.inputStream().use { input ->
                val buf = ByteArray(minOf(file.length(), SCAN_LIMIT.toLong()).toInt())
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
        } catch (e: Exception) {
            return Outcome.Failure(AppError("VIDEO_READ", "อ่านไฟล์ไม่ได้: ${e.message}"))
        }
        val info = parse(head, file.length())
            ?: return Outcome.Failure(AppError("VIDEO_UNKNOWN", "ไม่รู้จักไฟล์วิดีโอนี้ (รองรับ MP4/M4A/MOV/3GP ผ่าน probe)"))
        return Outcome.Success(info)
    }

    /** Box parse; null when this is not an ISO-BMFF file. Exposed for tests. */
    fun parse(head: ByteArray, sizeBytes: Long = head.size.toLong()): VideoProbeInfo? {
        if (head.size < 16) return null
        val firstName = ascii(head, 4, 4)
        if (firstName != "ftyp" && firstName != "moov" && firstName != "mdat") return null
        val brand = if (firstName == "ftyp" && head.size >= 12) ascii(head, 8, 4) else ""
        var durationMs = -1L
        var width = -1
        var height = -1
        var hasVideo = false
        var hasAudio = false
        var foundMoov = false
        var pos = 0
        while (pos + 8 <= head.size) {
            val len = boxLen(head, pos) ?: break
            if (len < 8) break
            val name = ascii(head, pos + 4, 4)
            if (name == "moov") {
                foundMoov = true
                val end = minOf(head.size, pos + len)
                var inner = pos + 8
                while (inner + 8 <= end) {
                    val ilen = boxLen(head, inner) ?: break
                    if (ilen < 8) break
                    val iname = ascii(head, inner + 4, 4)
                    if (iname == "mvhd") {
                        durationMs = mvhdDuration(head, inner, minOf(end, inner + ilen))
                    } else if (iname == "trak") {
                        val track = parseTrack(head, inner + 8, minOf(end, inner + ilen))
                        if (track != null) {
                            if (track.kind == "vide") {
                                hasVideo = true
                                if (track.width > 0) {
                                    width = track.width
                                    height = track.height
                                }
                                if (durationMs < 0 && track.durationMs >= 0) durationMs = track.durationMs
                            }
                            if (track.kind == "soun") {
                                hasAudio = true
                                if (durationMs < 0 && track.durationMs >= 0) durationMs = track.durationMs
                            }
                        }
                    }
                    if (ilen <= 0 || inner + ilen <= inner) break
                    inner += ilen
                }
                break
            }
            if (len <= 0 || pos + len <= pos) break
            // Skip giants (mdat) without loading them.
            if (name == "mdat" && pos + len > head.size) break
            pos += len
        }
        if (!foundMoov) {
            return VideoProbeInfo("MP4", -1, -1, -1, false, false, sizeBytes, "หา moov ไม่เจอ (ไฟล์อาจเสีย)")
        }
        val format = when {
            brand.startsWith("3g") -> "3GP"
            brand == "qt  " -> "MOV"
            else -> "MP4"
        }
        return VideoProbeInfo(format, durationMs, width, height, hasVideo, hasAudio, sizeBytes)
    }

    private data class Track(val kind: String, val width: Int, val height: Int, val durationMs: Long)

    private fun parseTrack(head: ByteArray, start: Int, end: Int): Track? {
        var kind = ""
        var width = -1
        var height = -1
        var durationMs = -1L
        var pos = start
        while (pos + 8 <= end && pos + 8 <= head.size) {
            val len = boxLen(head, pos) ?: break
            if (len < 8) break
            val boxEnd = minOf(end, pos + len)
            when (ascii(head, pos + 4, 4)) {
                "tkhd" -> {
                    // tkhd v0: width/height u32 16.16 at content+76/+80 (= box+84/+88).
                    if (pos + 92 <= boxEnd) {
                        width = u32be(head, pos + 84) shr 16
                        height = u32be(head, pos + 88) shr 16
                    }
                }
                "mdia" -> {
                    var inner = pos + 8
                    while (inner + 8 <= boxEnd) {
                        val ilen = boxLen(head, inner) ?: break
                        if (ilen < 8) break
                        val iend = minOf(boxEnd, inner + ilen)
                        when (ascii(head, inner + 4, 4)) {
                            "mdhd" -> {
                                if (inner + 24 <= iend && head[inner + 8].toInt() == 0) {
                                    val timescale = u32be(head, inner + 20).toLong() and 0xFFFFFFFFL
                                    val duration = u32be(head, inner + 24).toLong() and 0xFFFFFFFFL
                                    if (timescale > 0) durationMs = duration * 1000 / timescale
                                }
                            }
                            "hdlr" -> {
                                if (inner + 16 <= iend) kind = ascii(head, inner + 16, 4)
                            }
                        }
                        if (ilen <= 0 || inner + ilen <= inner) break
                        inner += ilen
                    }
                }
            }
            if (len <= 0 || pos + len <= pos) break
            pos += len
        }
        if (kind.isEmpty()) return null
        return Track(kind, width, height, durationMs)
    }

    private fun mvhdDuration(head: ByteArray, pos: Int, end: Int): Long {
        if (pos + 28 > end) return -1
        val version = head[pos + 8].toInt() and 0xFF
        if (version != 0) return -1
        val timescale = u32be(head, pos + 20).toLong() and 0xFFFFFFFFL
        val duration = u32be(head, pos + 24).toLong() and 0xFFFFFFFFL
        if (timescale <= 0) return -1
        return duration * 1000 / timescale
    }

    /** Box length; supports 32-bit size (64-bit largesize returns null → stop). */
    private fun boxLen(head: ByteArray, pos: Int): Int? {
        val size = u32be(head, pos).toLong() and 0xFFFFFFFFL
        if (size == 1L) return null // largesize — not scanned
        if (size == 0L) return head.size - pos // to end of file
        if (size > Int.MAX_VALUE) return null
        return size.toInt()
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String {
        if (off < 0 || off + len > b.size) return ""
        return String(b, off, len, Charsets.US_ASCII)
    }

    private fun u32be(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 4 > b.size) return 0
        return ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    }
}
