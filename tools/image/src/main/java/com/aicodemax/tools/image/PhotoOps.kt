package com.aicodemax.tools.image

import kotlin.math.roundToInt

/**
 * CP-89 §37: photo edit/upscale/restore. Pure pixel math (JVM-tested);
 * platform ports only convert Bitmap <-> [PixelImage].
 *
 * Honest scope: classic DSP only (no AI super-resolution — that needs an
 * on-device model; see §37 roadmap).
 */
object PhotoOps {
    /**
     * brightness/contrast/saturation: -100..100. sharpness: 0..100 (unsharp mask).
     */
    fun adjust(
        src: PixelImage,
        brightness: Int = 0,
        contrast: Int = 0,
        saturation: Int = 0,
        sharpness: Int = 0,
    ): PixelImage {
        require(brightness in -100..100) { "brightness ต้องอยู่ -100..100" }
        require(contrast in -100..100) { "contrast ต้องอยู่ -100..100" }
        require(saturation in -100..100) { "saturation ต้องอยู่ -100..100" }
        require(sharpness in 0..100) { "sharpness ต้องอยู่ 0..100" }
        val bShift = brightness * 2.55
        val cGain = 1.0 + contrast / 100.0
        val sGain = 1.0 + saturation / 100.0
        val out = IntArray(src.pixels.size)
        for (i in src.pixels.indices) {
            val p = src.pixels[i]
            var r = redOf(p).toDouble()
            var g = greenOf(p).toDouble()
            var bl = blueOf(p).toDouble()
            val luma = 0.299 * r + 0.587 * g + 0.114 * bl
            r = luma + (r - luma) * sGain
            g = luma + (g - luma) * sGain
            bl = luma + (bl - luma) * sGain
            r = (r - 128) * cGain + 128 + bShift
            g = (g - 128) * cGain + 128 + bShift
            bl = (bl - 128) * cGain + 128 + bShift
            out[i] = argb(alphaOf(p), r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), bl.roundToInt().coerceIn(0, 255))
        }
        if (sharpness == 0) return PixelImage(src.width, src.height, out)
        // Unsharp mask (3x3 box blur).
        val amount = sharpness / 100.0 * 1.5
        val sharp = IntArray(out.size)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val xx = (x + dx).coerceIn(0, src.width - 1)
                        val yy = (y + dy).coerceIn(0, src.height - 1)
                        val q = out[yy * src.width + xx]
                        r += redOf(q)
                        g += greenOf(q)
                        b += blueOf(q)
                        n++
                    }
                }
                val c = out[y * src.width + x]
                sharp[y * src.width + x] = argb(
                    alphaOf(c),
                    (redOf(c) + (redOf(c) - r.toDouble() / n) * amount).roundToInt().coerceIn(0, 255),
                    (greenOf(c) + (greenOf(c) - g.toDouble() / n) * amount).roundToInt().coerceIn(0, 255),
                    (blueOf(c) + (blueOf(c) - b.toDouble() / n) * amount).roundToInt().coerceIn(0, 255),
                )
            }
        }
        return PixelImage(src.width, src.height, sharp)
    }

    /** Bicubic upscale by [scale] (2 or 4). Output dims capped at 8192px. */
    fun upscale(src: PixelImage, scale: Int): PixelImage {
        require(scale == 2 || scale == 4) { "ขยายได้ 2x หรือ 4x เท่านั้น" }
        val w = src.width * scale
        val h = src.height * scale
        require(w <= 8192 && h <= 8192) { "ภาพใหญ่เกิน 8192px" }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y + 0.5) / scale - 0.5
            for (x in 0 until w) {
                out[y * w + x] = bicubic(src, (x + 0.5) / scale - 0.5, sy)
            }
        }
        return PixelImage(w, h, out)
    }

    /**
     * Old-photo lite restoration: median denoise + auto-contrast de-fade
     * + gray-world white balance. Each stage optional.
     */
    fun restore(src: PixelImage, denoise: Boolean = true, deFade: Boolean = true, whiteBalance: Boolean = true): PixelImage {
        var img = src
        if (denoise) img = median3(img)
        if (deFade) img = autoContrast(img, 0.5)
        if (whiteBalance) img = grayWorld(img)
        return img
    }

    private fun median3(src: PixelImage): PixelImage {
        val out = IntArray(src.pixels.size)
        val win = IntArray(9)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                for (ch in 0..2) {
                    var n = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val q = src.pixels[(y + dy).coerceIn(0, src.height - 1) * src.width + (x + dx).coerceIn(0, src.width - 1)]
                            win[n++] = when (ch) {
                                0 -> redOf(q)
                                1 -> greenOf(q)
                                else -> blueOf(q)
                            }
                        }
                    }
                    win.sort()
                    val med = win[4]
                    val idx = y * src.width + x
                    out[idx] = when (ch) {
                        0 -> argb(alphaOf(src.pixels[idx]), med, greenOf(out[idx]), blueOf(out[idx]))
                        1 -> argb(alphaOf(src.pixels[idx]), redOf(out[idx]), med, blueOf(out[idx]))
                        else -> argb(alphaOf(src.pixels[idx]), redOf(out[idx]), greenOf(out[idx]), med)
                    }
                }
            }
        }
        return PixelImage(src.width, src.height, out)
    }

    private fun autoContrast(src: PixelImage, cutPct: Double): PixelImage {
        fun channel(get: (Int) -> Int): Pair<Int, Int> {
            val hist = IntArray(256)
            for (p in src.pixels) hist[get(p)]++
            val total = src.pixels.size.toDouble()
            var lo = 0
            var acc = 0.0
            while (lo < 255 && acc / total * 100 < cutPct) {
                acc += hist[lo]
                lo++
            }
            var hi = 255
            acc = 0.0
            while (hi > 0 && acc / total * 100 < cutPct) {
                acc += hist[hi]
                hi--
            }
            return lo to maxOf(hi, lo + 1)
        }
        val (rLo, rHi) = channel(::redOf)
        val (gLo, gHi) = channel(::greenOf)
        val (bLo, bHi) = channel(::blueOf)
        val out = IntArray(src.pixels.size)
        for (i in src.pixels.indices) {
            val p = src.pixels[i]
            out[i] = argb(
                alphaOf(p),
                ((redOf(p) - rLo) * 255.0 / (rHi - rLo)).roundToInt().coerceIn(0, 255),
                ((greenOf(p) - gLo) * 255.0 / (gHi - gLo)).roundToInt().coerceIn(0, 255),
                ((blueOf(p) - bLo) * 255.0 / (bHi - bLo)).roundToInt().coerceIn(0, 255),
            )
        }
        return PixelImage(src.width, src.height, out)
    }

    private fun grayWorld(src: PixelImage): PixelImage {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        for (p in src.pixels) {
            rSum += redOf(p)
            gSum += greenOf(p)
            bSum += blueOf(p)
        }
        val n = src.pixels.size.toDouble()
        val avg = (rSum + gSum + bSum) / 3.0 / n
        val rG = if (rSum == 0L) 1.0 else avg / (rSum / n)
        val gG = if (gSum == 0L) 1.0 else avg / (gSum / n)
        val bG = if (bSum == 0L) 1.0 else avg / (bSum / n)
        val out = IntArray(src.pixels.size)
        for (i in src.pixels.indices) {
            val p = src.pixels[i]
            out[i] = argb(
                alphaOf(p),
                (redOf(p) * rG).roundToInt().coerceIn(0, 255),
                (greenOf(p) * gG).roundToInt().coerceIn(0, 255),
                (blueOf(p) * bG).roundToInt().coerceIn(0, 255),
            )
        }
        return PixelImage(src.width, src.height, out)
    }

    private fun cubic(t: Double): Double {
        val a = -0.5
        val t2 = t * t
        val t3 = t2 * t
        return when {
            t <= 1 -> (a + 2) * t3 - (a + 3) * t2 + 1
            t <= 2 -> a * t3 - 5 * a * t2 + 8 * a * t - 4 * a
            else -> 0.0
        }
    }

    private fun bicubic(src: PixelImage, sx: Double, sy: Double): Int {
        val x0 = sx.toInt()
        val y0 = sy.toInt()
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var wSum = 0.0
        for (m in -1..2) {
            for (n in -1..2) {
                val w = cubic(kotlin.math.abs(sx - (x0 + n))) * cubic(kotlin.math.abs(sy - (y0 + m)))
                val p = src.pixels[y0.plus(m).coerceIn(0, src.height - 1) * src.width + x0.plus(n).coerceIn(0, src.width - 1)]
                r += redOf(p) * w
                g += greenOf(p) * w
                b += blueOf(p) * w
                wSum += w
            }
        }
        if (wSum == 0.0) return src.pixels[y0.coerceIn(0, src.height - 1) * src.width + x0.coerceIn(0, src.width - 1)]
        val c = src.pixels[y0.coerceIn(0, src.height - 1) * src.width + x0.coerceIn(0, src.width - 1)]
        return argb(alphaOf(c), (r / wSum).roundToInt().coerceIn(0, 255), (g / wSum).roundToInt().coerceIn(0, 255), (b / wSum).roundToInt().coerceIn(0, 255))
    }
}
