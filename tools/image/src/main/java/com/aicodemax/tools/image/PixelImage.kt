package com.aicodemax.tools.image

/**
 * CP-61: platform-free image (ARGB pixels, row-major). All pixel math lives here
 * so it is unit-testable on JVM; Android only converts Bitmap <-> PixelImage.
 */
data class PixelImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "bad dims ${width}x${height}" }
        require(pixels.size == width * height) { "pixel count ${pixels.size} != ${width}x$height" }
    }

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

    override fun equals(other: Any?): Boolean =
        other is PixelImage && width == other.width && height == other.height &&
            pixels.contentEquals(other.pixels)

    override fun hashCode(): Int = 31 * (31 * width + height) + pixels.contentHashCode()
}

fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF
fun redOf(argb: Int): Int = (argb ushr 16) and 0xFF
fun greenOf(argb: Int): Int = (argb ushr 8) and 0xFF
fun blueOf(argb: Int): Int = argb and 0xFF
fun argb(a: Int, r: Int, g: Int, b: Int): Int =
    ((a and 0xFF) shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

/** Pure pixel operations — no Bitmap/ImageIO, tested on JVM. */
object ImageOps {
    /** Scales so the longer side becomes [maxDim] (bilinear). No-op when already smaller. */
    fun resize(src: PixelImage, maxDim: Int): PixelImage {
        require(maxDim > 0) { "maxDim must be > 0" }
        val longer = maxOf(src.width, src.height)
        if (longer <= maxDim) return src
        val scale = maxDim.toDouble() / longer
        val w = maxOf(1, (src.width * scale).toInt())
        val h = maxOf(1, (src.height * scale).toInt())
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y + 0.5) / scale - 0.5
            for (x in 0 until w) {
                val sx = (x + 0.5) / scale - 0.5
                out[y * w + x] = bilinear(src, sx, sy)
            }
        }
        return PixelImage(w, h, out)
    }

    /** Crops rect (clamped into bounds). Fails honestly when the rect misses the image. */
    fun crop(src: PixelImage, x: Int, y: Int, w: Int, h: Int): PixelImage {
        require(w > 0 && h > 0) { "crop size must be > 0" }
        val x0 = x.coerceIn(0, src.width - 1)
        val y0 = y.coerceIn(0, src.height - 1)
        val x1 = (x + w).coerceIn(1, src.width)
        val y1 = (y + h).coerceIn(1, src.height)
        require(x1 > x0 && y1 > y0) { "crop rect is outside the image" }
        val cw = x1 - x0
        val ch = y1 - y0
        val out = IntArray(cw * ch)
        for (dy in 0 until ch) {
            src.pixels.copyInto(out, dy * cw, (y0 + dy) * src.width + x0, (y0 + dy) * src.width + x0 + cw)
        }
        return PixelImage(cw, ch, out)
    }

    /** Exact rotation for 90/180/270 degrees clockwise. */
    fun rotate(src: PixelImage, degrees: Int): PixelImage {
        val norm = ((degrees % 360) + 360) % 360
        require(norm == 90 || norm == 180 || norm == 270) { "only 90/180/270 supported, got $degrees" }
        return when (norm) {
            180 -> PixelImage(src.width, src.height, src.pixels.reversedArray())
            90 -> {
                val out = IntArray(src.pixels.size)
                for (y in 0 until src.height) {
                    for (x in 0 until src.width) {
                        out[x * src.height + (src.height - 1 - y)] = src.pixel(x, y)
                    }
                }
                PixelImage(src.height, src.width, out)
            }
            else -> { // 270
                val out = IntArray(src.pixels.size)
                for (y in 0 until src.height) {
                    for (x in 0 until src.width) {
                        out[(src.width - 1 - x) * src.height + y] = src.pixel(x, y)
                    }
                }
                PixelImage(src.height, src.width, out)
            }
        }
    }

    /** Luminance grayscale (Rec. 601), alpha preserved. */
    fun grayscale(src: PixelImage): PixelImage {
        val out = IntArray(src.pixels.size) { i ->
            val p = src.pixels[i]
            val lum = (0.299 * redOf(p) + 0.587 * greenOf(p) + 0.114 * blueOf(p)).toInt().coerceIn(0, 255)
            argb(alphaOf(p), lum, lum, lum)
        }
        return PixelImage(src.width, src.height, out)
    }

    private fun bilinear(src: PixelImage, fx: Double, fy: Double): Int {
        val x0 = fx.toInt().coerceIn(0, src.width - 1)
        val y0 = fy.toInt().coerceIn(0, src.height - 1)
        val x1 = (x0 + 1).coerceIn(0, src.width - 1)
        val y1 = (y0 + 1).coerceIn(0, src.height - 1)
        val tx = (fx - fx.toInt()).coerceIn(0.0, 1.0)
        val ty = (fy - fy.toInt()).coerceIn(0.0, 1.0)
        val c00 = src.pixel(x0, y0)
        val c10 = src.pixel(x1, y0)
        val c01 = src.pixel(x0, y1)
        val c11 = src.pixel(x1, y1)
        fun mix(c0: Int, c1: Int, t: Double): Double = c0 + (c1 - c0) * t
        fun mixD(c0: Double, c1: Double, t: Double): Double = c0 + (c1 - c0) * t
        val a = mixD(mix(alphaOf(c00), alphaOf(c10), tx), mix(alphaOf(c01), alphaOf(c11), tx), ty)
        val r = mixD(mix(redOf(c00), redOf(c10), tx), mix(redOf(c01), redOf(c11), tx), ty)
        val g = mixD(mix(greenOf(c00), greenOf(c10), tx), mix(greenOf(c01), greenOf(c11), tx), ty)
        val b = mixD(mix(blueOf(c00), blueOf(c10), tx), mix(blueOf(c01), blueOf(c11), tx), ty)
        return argb(a.toInt(), r.toInt(), g.toInt(), b.toInt())
    }
}
