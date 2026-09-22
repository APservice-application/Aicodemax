package com.aicodemax.tools.media

import com.aicodemax.data.media.TrackPath
import com.aicodemax.data.media.TrackPoint
import kotlin.math.sqrt

/** CP-80 grayscale frame for template matching (row-major 0..255). */
data class GrayFrame(val width: Int, val height: Int, val pixels: ByteArray) {
    fun at(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF
}

/** CP-80 normalized cross-correlation template tracking (§15, no ML). Pure/JVM. */
object TemplateMatch {
    data class Match(val dx: Int, val dy: Int, val score: Double)

    /**
     * NCC search for [templ] inside [frame], centered on ([cx],[cy]) ± [radius].
     * [cx],[cy] = expected template top-left. Returns best offset + score 0..1.
     */
    fun match(
        frame: GrayFrame,
        templ: GrayFrame,
        cx: Int,
        cy: Int,
        radius: Int,
    ): Match {
        val tw = templ.width
        val th = templ.height
        val n = tw * th
        var tSum = 0L
        var tSq = 0L
        for (v in templ.pixels) {
            val p = v.toInt() and 0xFF
            tSum += p
            tSq += p * p
        }
        val tMean = tSum.toDouble() / n
        val tVar = tSq - tSum.toDouble() * tSum / n
        if (tVar <= 0.0) return Match(0, 0, 0.0)
        val x0 = (cx - radius).coerceIn(0, frame.width - tw)
        val x1 = (cx + radius).coerceIn(0, frame.width - tw)
        val y0 = (cy - radius).coerceIn(0, frame.height - th)
        val y1 = (cy + radius).coerceIn(0, frame.height - th)
        var best = Match(0, 0, -1.0)
        for (y in y0..y1) {
            for (x in x0..x1) {
                var fSum = 0L
                var fSq = 0L
                var cross = 0L
                for (ty in 0 until th) {
                    val fRow = (y + ty) * frame.width + x
                    val tRow = ty * tw
                    for (tx in 0 until tw) {
                        val f = frame.pixels[fRow + tx].toInt() and 0xFF
                        val t = templ.pixels[tRow + tx].toInt() and 0xFF
                        fSum += f
                        fSq += f * f
                        cross += f * t
                    }
                }
                val fVar = fSq - fSum.toDouble() * fSum / n
                val score = if (fVar <= 0.0) {
                    0.0
                } else {
                    (cross - fSum.toDouble() * tSum / n) / sqrt(fVar * tVar)
                }
                if (score > best.score) best = Match(x - cx, y - cy, score)
            }
        }
        return best
    }

    fun crop(frame: GrayFrame, x: Int, y: Int, w: Int, h: Int): GrayFrame {
        val x0 = x.coerceIn(0, frame.width - 1)
        val y0 = y.coerceIn(0, frame.height - 1)
        val w2 = w.coerceIn(1, frame.width - x0)
        val h2 = h.coerceIn(1, frame.height - y0)
        val out = ByteArray(w2 * h2)
        for (row in 0 until h2) {
            frame.pixels.copyInto(out, row * w2, (y0 + row) * frame.width + x0, (y0 + row) * frame.width + x0 + w2)
        }
        return GrayFrame(w2, h2, out)
    }

    /**
     * Absolute tracking: fixed template from frame 0, matched in every frame.
     * Returns offsets (px, from start) + per-frame scores.
     */
    fun trackAbsolute(
        frames: List<GrayFrame>,
        rectX: Int,
        rectY: Int,
        rectW: Int,
        rectH: Int,
        radius: Int,
    ): List<Match> {
        if (frames.isEmpty()) return emptyList()
        val first = frames[0]
        val templ = crop(first, rectX, rectY, rectW, rectH)
        val x0 = rectX.coerceIn(0, first.width - templ.width)
        val y0 = rectY.coerceIn(0, first.height - templ.height)
        return frames.map { match(it, templ, x0, y0, radius) }
    }

    /**
     * Differential tracking: each frame matched against the previous frame's
     * patch (camera-motion estimation for stabilization). Returns per-step shifts.
     */
    fun trackDifferential(
        frames: List<GrayFrame>,
        rectX: Int,
        rectY: Int,
        rectW: Int,
        rectH: Int,
        radius: Int,
    ): List<Match> {
        if (frames.size < 2) return emptyList()
        val out = mutableListOf<Match>()
        var px = rectX
        var py = rectY
        for (i in 1 until frames.size) {
            val templ = crop(frames[i - 1], px, py, rectW, rectH)
            val x0 = px.coerceIn(0, frames[i].width - templ.width)
            val y0 = py.coerceIn(0, frames[i].height - templ.height)
            val m = match(frames[i], templ, x0, y0, radius)
            out += m
            px = (x0 + m.dx).coerceIn(0, frames[i].width - templ.width)
            py = (y0 + m.dy).coerceIn(0, frames[i].height - templ.height)
        }
        return out
    }

    /** Box-smooth a trajectory (odd window ≥ 1). */
    fun smooth(traj: List<Pair<Float, Float>>, window: Int): List<Pair<Float, Float>> {
        val w = window.coerceAtLeast(1)
        if (w <= 1 || traj.isEmpty()) return traj
        val half = w / 2
        return traj.indices.map { i ->
            val lo = (i - half).coerceAtLeast(0)
            val hi = (i + half).coerceAtMost(traj.size - 1)
            var sx = 0f
            var sy = 0f
            for (k in lo..hi) {
                sx += traj[k].first
                sy += traj[k].second
            }
            val n = (hi - lo + 1).toFloat()
            sx / n to sy / n
        }
    }

    /** Builds a % path from px offsets ([frameW]/[frameH] = analyzed size). */
    fun toPath(
        atMs: List<Long>,
        dxPx: List<Float>,
        dyPx: List<Float>,
        frameW: Int,
        frameH: Int,
    ): TrackPath {
        val pts = atMs.indices.map { i ->
            TrackPoint(atMs[i], dxPx[i] * 100f / frameW, dyPx[i] * 100f / frameH)
        }
        return TrackPath(pts)
    }
}
