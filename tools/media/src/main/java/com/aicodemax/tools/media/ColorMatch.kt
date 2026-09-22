package com.aicodemax.tools.media

import com.aicodemax.data.media.ClipColor

/** CP-90 §34: per-frame channel stats for color matching (pure, JVM-tested). */
data class ChannelStats(
    val meanR: Double,
    val meanG: Double,
    val meanB: Double,
    val p2: Int,
    val p50: Int,
    val p98: Int,
)

object ColorMatch {
    fun stats(px: IntArray): ChannelStats {
        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        val hist = IntArray(256)
        for (c in px) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            rSum += r
            gSum += g
            bSum += b
            hist[((0.299 * r + 0.587 * g + 0.114 * b).toInt()).coerceIn(0, 255)]++
        }
        val n = px.size.coerceAtLeast(1).toDouble()
        fun percentile(p: Double): Int {
            var acc = 0L
            val target = (px.size * p).toLong()
            for (i in hist.indices) {
                acc += hist[i]
                if (acc >= target) return i
            }
            return 255
        }
        return ChannelStats(rSum / n, gSum / n, bSum / n, percentile(0.02), percentile(0.50), percentile(0.98))
    }

    /**
     * Grade that pushes [target] toward [ref]: channel-balance → temp/tint,
     * median luma → exposure, endpoints → whites/blacks.
     */
    fun match(target: ChannelStats, ref: ChannelStats): ClipColor {
        val tAvg = (target.meanR + target.meanG + target.meanB) / 3.0
        val rAvg = (ref.meanR + ref.meanG + ref.meanB) / 3.0
        if (tAvg < 1 || rAvg < 1) return ClipColor()
        // Channel ratios relative to own average → how far each cast is off.
        val tRB = (target.meanB - target.meanR) / tAvg
        val rRB = (ref.meanB - ref.meanR) / rAvg
        val tG = (tAvg - target.meanG) / tAvg
        val rG = (rAvg - ref.meanG) / rAvg
        val temperature = ((tRB - rRB) * 120).toInt().coerceIn(-80, 80)
        val tint = ((tG - rG) * 150).toInt().coerceIn(-60, 60)
        val exposure = ((ref.p50 - target.p50) / 4).coerceIn(-40, 40)
        val whites = ((ref.p98 - target.p98) / 2).coerceIn(-60, 60)
        val blacks = (-(ref.p2 - target.p2) / 2).coerceIn(-60, 60)
        return ClipColor(
            temperature = temperature.takeIf { kotlin.math.abs(it) >= 5 } ?: 0,
            tint = tint.takeIf { kotlin.math.abs(it) >= 5 } ?: 0,
            exposure = exposure,
            whites = whites,
            blacks = blacks,
        )
    }
}
