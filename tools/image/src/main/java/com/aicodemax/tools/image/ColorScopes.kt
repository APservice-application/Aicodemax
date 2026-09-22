package com.aicodemax.tools.image

/**
 * CP-78 scopes summary (§101 histogram). Pure/JVM — computed over decoded
 * pixels on Android, over test pixels in unit tests.
 */
data class FrameScopes(
    val pixels: Long,
    val meanLuma: Double,
    val darkPct: Double,
    val brightPct: Double,
    val meanR: Double,
    val meanG: Double,
    val meanB: Double,
    /** 16 merged luma bins (0..15 → counts) for the sparkline. */
    val bins16: List<Long>,
) {
    /** Thai verdict: exposure + white-balance hint. */
    val verdict: String get() {
        if (pixels <= 0) return "ไม่มีข้อมูล"
        if (darkPct >= 95.0) return "มืดสนิท — ภาพมีปัญหา"
        if (brightPct >= 95.0) return "สว่างจ้า — ภาพมีปัญหา"
        val cast = when {
            meanR > meanG + 18 && meanR > meanB + 18 -> " (ติดแดง)"
            meanB > meanR + 18 && meanB > meanG + 18 -> " (ติดฟ้า)"
            meanG > meanR + 18 && meanG > meanB + 18 -> " (ติดเขียว)"
            else -> ""
        }
        return when {
            darkPct > 40.0 -> "มืดไป$cast"
            brightPct > 25.0 -> "สว่างไป$cast"
            else -> "ปกติ$cast"
        }
    }

    /** 16-block luma histogram sparkline. */
    fun sparkline(): String {
        if (bins16.isEmpty()) return ""
        val peak = bins16.maxOrNull()?.coerceAtLeast(1) ?: 1
        val bars = "▁▂▃▄▅▆▇█"
        return bins16.joinToString("") { bars[((it * 7) / peak).toInt().coerceIn(0, 7)].toString() }
    }

    fun summary(): String =
        "mean %.0f มืด %.1f%% สว่าง %.1f%% → %s %s".format(meanLuma, darkPct, brightPct, verdict, sparkline())
}

/** Pure luma-histogram analysis (§101). */
object ColorScopes {
    fun analyze(image: PixelImage): FrameScopes = analyze(image.pixels)

    fun analyze(pixels: IntArray): FrameScopes {
        val bins = LongArray(256)
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            val rv = (p ushr 16) and 0xFF
            val gv = (p ushr 8) and 0xFF
            val bv = p and 0xFF
            r += rv
            g += gv
            b += bv
            bins[((0.299 * rv + 0.587 * gv + 0.114 * bv).toInt()).coerceIn(0, 255)] += 1
        }
        return fromBins(bins, r, g, b, pixels.size.toLong())
    }

    internal fun fromBins(bins: LongArray, r: Long, g: Long, b: Long, n: Long): FrameScopes {
        val total = n.coerceAtLeast(1)
        var sum = 0L
        var dark = 0L
        var bright = 0L
        for (i in bins.indices) {
            sum += i * bins[i]
            if (i < 16) dark += bins[i]
            if (i > 235) bright += bins[i]
        }
        val merged = List(16) { k -> (k * 16 until (k + 1) * 16).sumOf { bins[it] } }
        return FrameScopes(
            pixels = n,
            meanLuma = sum.toDouble() / total,
            darkPct = dark * 100.0 / total,
            brightPct = bright * 100.0 / total,
            meanR = r.toDouble() / total,
            meanG = g.toDouble() / total,
            meanB = b.toDouble() / total,
            bins16 = merged,
        )
    }
}

/** Accumulates sampled frames during a render (one instance per job). */
class RenderScopes {
    private val bins = LongArray(256)
    private var r = 0L
    private var g = 0L
    private var b = 0L
    private var n = 0L
    var frames: Int = 0
        private set

    /** Adds one ARGB frame, sampling every 16th pixel (fast, representative). */
    fun add(pixels: IntArray) {
        frames += 1
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            val rv = (p ushr 16) and 0xFF
            val gv = (p ushr 8) and 0xFF
            val bv = p and 0xFF
            r += rv
            g += gv
            b += bv
            bins[((0.299 * rv + 0.587 * gv + 0.114 * bv).toInt()).coerceIn(0, 255)] += 1
            n += 1
            i += 16
        }
    }

    fun isEmpty(): Boolean = n <= 0

    fun report(): FrameScopes = ColorScopes.fromBins(bins, r, g, b, n)
}
