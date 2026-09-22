package com.aicodemax.tools.subtitle

/**
 * CP-66: ARGB → YUV420 (BT.601) for the burn-in encoder. Pure + tested;
 * the Android port feeds the bytes to MediaCodec ByteBuffer input.
 */
object Yuv {
    /** Planar I420 (Y + U + V). Dimensions must be even. */
    fun toI420(argb: IntArray, width: Int, height: Int): ByteArray {
        require(width % 2 == 0 && height % 2 == 0) { "even dims required, got ${width}x$height" }
        require(argb.size == width * height) { "pixel count mismatch" }
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                out[y * width + x] = yOf(r, g, b).toByte()
            }
        }
        var uPos = ySize
        var vPos = ySize + ySize / 4
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val p = argb[(y + dy) * width + (x + dx)]
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                out[uPos++] = uOf(r / 4, g / 4, b / 4).toByte()
                out[vPos++] = vOf(r / 4, g / 4, b / 4).toByte()
            }
        }
        return out
    }

    /** Semi-planar NV12 (Y + interleaved UV). Dimensions must be even. */
    fun toNV12(argb: IntArray, width: Int, height: Int): ByteArray {
        require(width % 2 == 0 && height % 2 == 0) { "even dims required, got ${width}x$height" }
        require(argb.size == width * height) { "pixel count mismatch" }
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = argb[y * width + x]
                val r = (p ushr 16) and 0xFF
                val g = (p ushr 8) and 0xFF
                val b = p and 0xFF
                out[y * width + x] = yOf(r, g, b).toByte()
            }
        }
        var pos = ySize
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val p = argb[(y + dy) * width + (x + dx)]
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                out[pos++] = uOf(r / 4, g / 4, b / 4).toByte()
                out[pos++] = vOf(r / 4, g / 4, b / 4).toByte()
            }
        }
        return out
    }

    internal fun yOf(r: Int, g: Int, b: Int): Int =
        ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16

    internal fun uOf(r: Int, g: Int, b: Int): Int =
        ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128

    internal fun vOf(r: Int, g: Int, b: Int): Int =
        ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
}
