package com.aicodemax.tools.image

import com.aicodemax.core.common.AppError
import com.aicodemax.core.common.Outcome
import java.io.File

/** Format + dimensions read from file headers (no decode needed). */
data class ImageProbeInfo(
    val format: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

/**
 * CP-61: reads PNG/JPEG/GIF/BMP/WebP dimensions from headers.
 * Pure JVM (no Bitmap) — runs in unit tests and on device.
 */
object ImageProbe {
    fun probe(file: File): Outcome<ImageProbeInfo> {
        if (!file.isFile) {
            return Outcome.Failure(AppError("IMAGE_NO_FILE", "ไม่พบไฟล์ ${file.path}"))
        }
        val bytes = try {
            file.inputStream().use { input ->
                val buf = ByteArray(128)
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                buf.copyOf(read)
            }
        } catch (e: Exception) {
            return Outcome.Failure(AppError("IMAGE_READ", "อ่านไฟล์ไม่ได้: ${e.message}"))
        }
        val dims = parse(bytes)
            ?: return Outcome.Failure(AppError("IMAGE_UNKNOWN", "ไม่รู้จักฟอร์แมตรูป (รองรับ PNG/JPEG/GIF/BMP/WebP)"))
        return Outcome.Success(dims.copy(sizeBytes = file.length()))
    }

    /** Parses headers only; null when the format is unknown. Exposed for tests. */
    fun parse(bytes: ByteArray): ImageProbeInfo? {
        if (bytes.size < 16) return null
        if (isPng(bytes)) {
            // IHDR must follow the 8-byte signature: len(4)=13 + "IHDR" + w32be + h32be.
            if (bytes.size >= 24 && ascii(bytes, 12, 4) == "IHDR") {
                return ImageProbeInfo("PNG", u32be(bytes, 16), u32be(bytes, 20), 0)
            }
            return ImageProbeInfo("PNG", -1, -1, 0)
        }
        if (isJpeg(bytes)) return jpegDims(bytes)
        if (isGif(bytes)) {
            return ImageProbeInfo("GIF", u16le(bytes, 6), u16le(bytes, 8), 0)
        }
        if (isBmp(bytes)) {
            val dib = u32le(bytes, 14)
            if (dib >= 40 && bytes.size >= 26) {
                return ImageProbeInfo("BMP", i32le(bytes, 18), kotlin.math.abs(i32le(bytes, 22)), 0)
            }
            return ImageProbeInfo("BMP", -1, -1, 0)
        }
        if (isRiffWebp(bytes)) return webpDims(bytes)
        return null
    }

    private fun jpegDims(bytes: ByteArray): ImageProbeInfo? {
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) {
                i += 1
                continue
            }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0xD9 || (marker in 0xD0..0xD7) || marker == 0x01) {
                i += 2
                continue
            }
            if (i + 3 >= bytes.size) break
            val len = u16be(bytes, i + 2)
            if (len < 2) return null
            val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (sof) {
                if (i + 9 >= bytes.size) return null
                return ImageProbeInfo("JPEG", u16be(bytes, i + 7), u16be(bytes, i + 5), 0)
            }
            i += 2 + len
        }
        return ImageProbeInfo("JPEG", -1, -1, 0)
    }

    private fun webpDims(bytes: ByteArray): ImageProbeInfo? {
        if (bytes.size < 16) return null
        return when (ascii(bytes, 12, 4)) {
            "VP8 " -> {
                if (bytes.size < 30) return ImageProbeInfo("WebP", -1, -1, 0)
                val w = (u16le(bytes, 26) and 0x3FFF)
                val h = (u16le(bytes, 28) and 0x3FFF)
                ImageProbeInfo("WebP", w, h, 0)
            }
            "VP8L" -> {
                // bytes[21] = 0x2F signature; 14-bit (w-1) + 14-bit (h-1), LE bit order.
                if (bytes.size < 26) return ImageProbeInfo("WebP", -1, -1, 0)
                if (bytes[21] != 0x2F.toByte()) return ImageProbeInfo("WebP", -1, -1, 0)
                val b1 = bytes[22].toInt() and 0xFF
                val b2 = bytes[23].toInt() and 0xFF
                val b3 = bytes[24].toInt() and 0xFF
                val b4 = bytes[25].toInt() and 0xFF
                val w = 1 + (b1 or ((b2 and 0x3F) shl 8))
                val h = 1 + ((b2 ushr 6) or (b3 shl 2) or ((b4 and 0x0F) shl 10))
                ImageProbeInfo("WebP", w, h, 0)
            }
            "VP8X" -> {
                if (bytes.size < 30) return ImageProbeInfo("WebP", -1, -1, 0)
                val w = 1 + (u24le(bytes, 24))
                val h = 1 + (u24le(bytes, 27))
                ImageProbeInfo("WebP", w, h, 0)
            }
            else -> ImageProbeInfo("WebP", -1, -1, 0)
        }
    }

    private fun isPng(b: ByteArray): Boolean =
        b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() &&
            b[3] == 0x47.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() &&
            b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()

    private fun isJpeg(b: ByteArray): Boolean =
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun isGif(b: ByteArray): Boolean =
        (ascii(b, 0, 6) == "GIF87a" || ascii(b, 0, 6) == "GIF89a") && b.size >= 10

    private fun isBmp(b: ByteArray): Boolean =
        b[0] == 0x42.toByte() && b[1] == 0x4D.toByte() && b.size >= 26

    private fun isRiffWebp(b: ByteArray): Boolean =
        ascii(b, 0, 4) == "RIFF" && b.size >= 16 && ascii(b, 8, 4) == "WEBP"

    private fun ascii(b: ByteArray, off: Int, len: Int): String {
        if (off + len > b.size) return ""
        return String(b, off, len, Charsets.US_ASCII)
    }

    private fun u16be(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun u16le(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u24le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)

    private fun u32be(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun u32le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun i32le(b: ByteArray, o: Int): Int = u32le(b, o)
}
