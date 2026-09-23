package com.aicodemax.tools.image

import kotlin.math.sqrt

/**
 * CP-105 video scopes (§101): waveform + vectorscope + RGB parade.
 * Pure Kotlin, JVM-tested with synthetic gradients.
 */
data class Waveform(
    val cols: Int,
    val rows: Int,
    val cells: IntArray,
) {
    fun clippedCols(): Int {
        var n = 0
        for (c in 0 until cols) if (cells[c] > 0) n++
        return n
    }

    fun crushedCols(): Int {
        var n = 0
        for (c in 0 until cols) if (cells[(rows - 1) * cols + c] > 0) n++
        return n
    }

    fun ascii(): String {
        val ramp = " .:-=+*#%@"
        val peak = cells.maxOrNull()?.coerceAtLeast(1) ?: 1
        return buildString {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val v = cells[r * cols + c]
                    append(ramp[((v * (ramp.length - 1)) / peak).coerceIn(0, ramp.length - 1)])
                }
                if (r + 1 < rows) append('\n')
            }
        }
    }

    fun summary(): String =
        "เวฟฟอร์ม: คลิป ${clippedCols()}/$cols คอลัมน์ จม ${crushedCols()}/$cols คอลัมน์"
}

data class Vectorscope(
    val size: Int,
    val cells: IntArray,
    val meanSat: Double,
) {
    fun ascii(): String {
        val ramp = " .:+#@"
        val peak = cells.maxOrNull()?.coerceAtLeast(1) ?: 1
        return buildString {
            for (r in 0 until size) {
                for (c in 0 until size) {
                    if (r == size / 2 && c == size / 2) {
                        append('+')
                    } else {
                        val v = cells[r * size + c]
                        append(ramp[((v * (ramp.length - 1)) / peak).coerceIn(0, ramp.length - 1)])
                    }
                }
                if (r + 1 < size) append('\n')
            }
        }
    }

    fun summary(): String = "เวกเตอร์สโคป: ความอิ่มเฉลี่ย ${(meanSat * 100).toInt()}%"
}

data class Parade(
    val cols: Int,
    val rows: Int,
    /** Mean level 0..rows per channel per column: [ch * cols + col]. */
    val levels: IntArray,
) {
    private fun meanLevel(ch: Int): Double {
        var sum = 0L
        for (c in 0 until cols) sum += levels[ch * cols + c]
        return sum.toDouble() / cols.coerceAtLeast(1)
    }

    fun ascii(): String {
        val names = charArrayOf('R', 'G', 'B')
        return buildString {
            for (ch in 0 until 3) {
                for (r in rows - 1 downTo 0) {
                    if (r == rows - 1) append(names[ch]) else append(' ')
                    for (c in 0 until cols) append(if (levels[ch * cols + c] > r) '#' else '.')
                    append('\n')
                }
            }
        }.trimEnd('\n')
    }

    fun summary(): String {
        val r = meanLevel(0)
        val g = meanLevel(1)
        val b = meanLevel(2)
        val cast = when {
            r > g + 1.5 && r > b + 1.5 -> " (แดงเด่น)"
            b > r + 1.5 && b > g + 1.5 -> " (ฟ้าเด่น)"
            g > r + 1.5 && g > b + 1.5 -> " (เขียวเด่น)"
            else -> ""
        }
        return "พาเหรด R/G/B: %d/%d/%d%s".format(r.toInt(), g.toInt(), b.toInt(), cast)
    }
}

data class ScopesReport(
    val frame: FrameScopes,
    val wave: Waveform,
    val vector: Vectorscope,
    val parade: Parade,
) {
    fun fullText(): String = buildString {
        appendLine("ฮิสโตแกรม: ${frame.summary()}")
        appendLine(wave.summary())
        appendLine(wave.ascii())
        appendLine(vector.summary())
        appendLine(vector.ascii())
        append(parade.summary())
        append('\n')
        append(parade.ascii())
    }
}

object VideoScopes {
    fun waveform(pixels: IntArray, w: Int, h: Int, cols: Int = 40, rows: Int = 10): Waveform {
        val cells = IntArray(cols * rows)
        if (w <= 0 || h <= 0 || pixels.isEmpty()) return Waveform(cols, rows, cells)
        val stride = (pixels.size / 50_000).coerceAtLeast(1)
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val x = (i % w).coerceIn(0, w - 1)
            val luma = 0.299 * ((p ushr 16) and 0xFF) + 0.587 * ((p ushr 8) and 0xFF) + 0.114 * (p and 0xFF)
            val c = (x * cols / w).coerceIn(0, cols - 1)
            val r = ((255.0 - luma) * rows / 256.0).toInt().coerceIn(0, rows - 1)
            cells[r * cols + c] += 1
            i += stride
        }
        return Waveform(cols, rows, cells)
    }

    fun vectorscope(pixels: IntArray, size: Int = 15): Vectorscope {
        val cells = IntArray(size * size)
        if (pixels.isEmpty()) return Vectorscope(size, cells, 0.0)
        val stride = (pixels.size / 50_000).coerceAtLeast(1)
        var satSum = 0.0
        var n = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val r = ((p ushr 16) and 0xFF).toDouble()
            val g = ((p ushr 8) and 0xFF).toDouble()
            val b = (p and 0xFF).toDouble()
            val cb = -0.168736 * r - 0.331264 * g + 0.5 * b
            val cr = 0.5 * r - 0.418688 * g - 0.081312 * b
            val gx = (cb * size / 256.0 + size / 2.0).toInt().coerceIn(0, size - 1)
            val gy = (cr * size / 256.0 + size / 2.0).toInt().coerceIn(0, size - 1)
            cells[gy * size + gx] += 1
            satSum += sqrt(cb * cb + cr * cr) / 128.0
            n += 1
            i += stride
        }
        return Vectorscope(size, cells, (satSum / n.coerceAtLeast(1)).coerceIn(0.0, 1.0))
    }

    fun parade(pixels: IntArray, w: Int, h: Int, cols: Int = 40, rows: Int = 6): Parade {
        val levels = IntArray(3 * cols)
        if (w <= 0 || h <= 0 || pixels.isEmpty()) return Parade(cols, rows, levels)
        val sums = LongArray(3 * cols)
        val counts = LongArray(cols)
        val stride = (pixels.size / 50_000).coerceAtLeast(1)
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val x = (i % w).coerceIn(0, w - 1)
            val c = (x * cols / w).coerceIn(0, cols - 1)
            sums[c] += ((p ushr 16) and 0xFF)
            sums[cols + c] += ((p ushr 8) and 0xFF)
            sums[2 * cols + c] += (p and 0xFF)
            counts[c] += 1
            i += stride
        }
        for (ch in 0 until 3) {
            for (c in 0 until cols) {
                val n = counts[c].coerceAtLeast(1)
                levels[ch * cols + c] = ((sums[ch * cols + c] / n) * rows / 256).toInt().coerceIn(0, rows)
            }
        }
        return Parade(cols, rows, levels)
    }

    fun report(pixels: IntArray, w: Int, h: Int): ScopesReport =
        ScopesReport(
            ColorScopes.analyze(pixels),
            waveform(pixels, w, h),
            vectorscope(pixels),
            parade(pixels, w, h),
        )

}
