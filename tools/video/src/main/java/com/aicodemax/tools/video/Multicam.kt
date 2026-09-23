package com.aicodemax.tools.video

/**
 * CP-104 §30: multicam group model + honest no-ML onset sync.
 * Pure Kotlin, JVM-tested with synthetic onset grids.
 *
 * Convention: `groupTime = angleTime + offsetsMs[angle]` — a positive offset
 * means that angle started later than the reference angle and must be delayed
 * by that much to line up.
 */
data class MulticamCut(
    val atMs: Long,
    val angle: Int,
)

data class MulticamGroup(
    val id: String,
    val angles: List<String>,
    val offsetsMs: List<Long>,
    val method: String,
    val confidence: Double,
    val refAngle: Int,
    val cuts: List<MulticamCut> = emptyList(),
) {
    fun summary(): String = buildString {
        append("มัลติแคม $id: ${angles.size} มุม (อ้างอิงมุม ${refAngle + 1}, วิธี $method")
        append(", มั่นใจ ${(confidence * 100).toInt()}%)")
        offsetsMs.forEachIndexed { i, off ->
            append(" มุม${i + 1}${if (off >= 0) "+" else ""}${off}ms")
        }
        if (cuts.isNotEmpty()) append(" ตัด ${cuts.size} จุด")
    }

    /** CMX3600-flavoured cut list (group timeline = reference angle). */
    fun edl(): String = buildString {
        appendLine("TITLE: $id  (ref angle ${refAngle + 1}, method $method)")
        appendLine("FCM: NON-DROP FRAME")
        val bounds = listOf(0L) + cuts.sortedBy { it.atMs }.map { it.atMs }
        val order = cuts.sortedBy { it.atMs }.map { it.angle }
        val timeline = (listOf(refAngle) + order).take(bounds.size)
        for (i in bounds.indices) {
            val start = bounds[i]
            val end = if (i + 1 < bounds.size) bounds[i + 1] else start
            val angle = timeline[i]
            appendLine(
                "${(i + 1).toString().padStart(3, '0')}  AX       V     C        " +
                    "${fmtTc(start)} ${fmtTc(end)} ${fmtTc(start)} ${fmtTc(end)}  (angle ${angle + 1})",
            )
        }
        appendLine("* OFFSETS: " + offsetsMs.mapIndexed { idx, off -> "A${idx + 1}${if (off >= 0) "+" else ""}${off}ms" }.joinToString(" "))
    }

    private fun fmtTc(ms: Long): String {
        val totalSec = (ms.coerceAtLeast(0) / 1000).toInt()
        val ff = ((ms.coerceAtLeast(0) % 1000) * 30 / 1000).toInt()
        val hh = totalSec / 3600
        val mm = (totalSec % 3600) / 60
        val ss = totalSec % 60
        return "%02d:%02d:%02d:%02d".format(hh, mm, ss, ff)
    }
}

object MulticamSync {
    data class Result(
        val offsetsMs: List<Long>,
        val confidence: Double,
        val refAngle: Int,
    )

    /**
     * Aligns onset sequences by lag search: for each non-reference angle the
     * lag in [-searchMs, +searchMs] with the most onset coincidences (within
     * ±[tolMs]) wins. Reference = angle with the most onsets.
     */
    fun sync(
        onsetLists: List<List<Long>>,
        searchMs: Long = 30_000,
        stepMs: Long = 50,
        tolMs: Long = 120,
    ): Result {
        if (onsetLists.size < 2) return Result(List(onsetLists.size) { 0L }, 0.0, 0)
        val ref = onsetLists.indices.maxByOrNull { onsetLists[it].size } ?: 0
        val refSeq = onsetLists[ref].sorted()
        val offsets = MutableList(onsetLists.size) { 0L }
        if (refSeq.size < 2) return Result(offsets, 0.0, ref)
        var confSum = 0.0
        var confN = 0
        for (i in onsetLists.indices) {
            if (i == ref) continue
            val seq = onsetLists[i].sorted()
            if (seq.size < 2) continue
            var bestLag = 0L
            var bestHits = -1
            var lag = -searchMs
            while (lag <= searchMs) {
                var hits = 0
                for (o in seq) {
                    if (nearestWithin(refSeq, o + lag, tolMs)) hits++
                }
                if (hits > bestHits) {
                    bestHits = hits
                    bestLag = lag
                }
                lag += stepMs
            }
            // Fine pass: shift by the mean residual of matched pairs so the
            // reported lag sits at the plateau centre, not its first edge.
            val residuals = seq.mapNotNull { o ->
                nearestValue(refSeq, o + bestLag, tolMs)?.let { it - (o + bestLag) }
            }
            if (residuals.isNotEmpty()) bestLag += (residuals.average()).let { kotlin.math.round(it).toLong() }
            offsets[i] = bestLag
            confSum += bestHits.toDouble() / maxOf(seq.size, refSeq.size).coerceAtLeast(1)
            confN++
        }
        val conf = if (confN == 0) 0.0 else (confSum / confN).coerceIn(0.0, 1.0)
        return Result(offsets, conf, ref)
    }

    private fun nearestValue(sorted: List<Long>, t: Long, tol: Long): Long? {
        var lo = 0
        var hi = sorted.size - 1
        var best: Long? = null
        var bestDist = tol + 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sorted[mid]
            val d = kotlin.math.abs(v - t)
            if (d < bestDist) {
                bestDist = d
                best = v
            }
            if (v < t) lo = mid + 1 else hi = mid - 1
        }
        return if (bestDist <= tol) best else null
    }

    private fun nearestWithin(sorted: List<Long>, t: Long, tol: Long): Boolean {
        var lo = 0
        var hi = sorted.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sorted[mid]
            if (v < t - tol) lo = mid + 1 else if (v > t + tol) hi = mid - 1 else return true
        }
        return false
    }
}
