package com.aicodemax.tools.audio

import kotlin.math.abs

/**
 * CP-85 §31: honest no-ML beat detection (energy-flux onsets + autocorr BPM).
 * Pure Kotlin, JVM-tested with synthetic clicks.
 */
data class BeatAnalysis(
    /** Estimated tempo, 0 if no clear pulse. */
    val bpm: Double,
    /** Beat grid positions in ms (snapped to onsets). */
    val beatsMs: List<Long>,
    /** 0..1 pulse clarity. */
    val confidence: Double,
)

object Beats {
    private const val FRAME = 1024
    private const val HOP = 512
    private const val MAX_BEATS = 512

    fun analyze(pcm: PcmAudio): BeatAnalysis {
        val onsets = onsets(pcm)
        if (onsets.size < 4) return BeatAnalysis(0.0, emptyList(), 0.0)
        val sr = pcm.sampleRate
        val frames = pcm.samples.size / pcm.channels
        val nWin = (frames - FRAME) / HOP
        val flux = fluxOf(pcm)
        if (flux.isEmpty() || nWin < 8) return BeatAnalysis(0.0, emptyList(), 0.0)
        val mono = FloatArray(frames) { i ->
            var sum = 0f
            for (c in 0 until pcm.channels) sum += pcm.samples[i * pcm.channels + c]
            sum / pcm.channels
        }
        val mean = flux.average()
        val std = kotlin.math.sqrt(flux.map { (it - mean) * (it - mean) }.average())
        val threshold = mean + 0.5 * std
        val onsetsD = onsets.map { it.toDouble() }
        // BPM via autocorrelation of flux over 60..200bpm.
        val hopSec = HOP.toDouble() / sr
        fun lagFor(bpm: Double): Int = (60.0 / bpm / hopSec).toInt().coerceAtLeast(1)
        var bestBpm = 0.0
        var bestScore = 0.0
        var b = 60.0
        while (b <= 200.0) {
            val lag = lagFor(b)
            var num = 0.0
            var den = 0.0
            for (w in 0 until nWin - lag) {
                num += flux[w] * flux[w + lag]
                den += flux[w] * flux[w]
            }
            val score = if (den > 0) num / den else 0.0
            if (score > bestScore) {
                bestScore = score
                bestBpm = b
            }
            b += 1.0
        }
        // Prefer the metrically stable octave (avoid half/double when close).
        val confidence = bestScore.coerceIn(0.0, 1.0)
        if (confidence < 0.15) return BeatAnalysis(0.0, emptyList(), confidence)
        val periodMs = 60_000.0 / bestBpm
        val start = onsetsD.first()
        val durMs = frames.toDouble() / sr * 1000.0
        val beats = mutableListOf<Long>()
        var t = start
        while (t <= durMs && beats.size < MAX_BEATS) {
            val snapped = onsetsD.minByOrNull { abs(it - t) }?.takeIf { abs(it - t) < periodMs * 0.35 } ?: t
            beats.add(snapped.toLong())
            t += periodMs
        }
        return BeatAnalysis(bestBpm, beats, confidence)
    }

    private fun fluxOf(pcm: PcmAudio): DoubleArray {
        val sr = pcm.sampleRate
        val frames = pcm.samples.size / pcm.channels
        if (frames < sr) return DoubleArray(0)
        val mono = FloatArray(frames) { i ->
            var sum = 0f
            for (c in 0 until pcm.channels) sum += pcm.samples[i * pcm.channels + c]
            sum / pcm.channels
        }
        val nWin = (frames - FRAME) / HOP
        if (nWin < 8) return DoubleArray(0)
        return DoubleArray(nWin) { w ->
            if (w == 0) {
                0.0
            } else {
                var ePrev = 0.0
                var eCur = 0.0
                for (i in 0 until FRAME) {
                    val a = mono[(w - 1) * HOP + i].toDouble()
                    val c = mono[w * HOP + i].toDouble()
                    ePrev += a * a
                    eCur += c * c
                }
                maxOf(0.0, (eCur - ePrev) / FRAME)
            }
        }
    }

    /** CP-104: peak-picked onset positions in ms (also feeds multicam sync). */
    fun onsets(pcm: PcmAudio): List<Long> {
        val flux = fluxOf(pcm)
        if (flux.size < 8) return emptyList()
        val mean = flux.average()
        val std = kotlin.math.sqrt(flux.map { (it - mean) * (it - mean) }.average())
        val threshold = mean + 0.5 * std
        val sr = pcm.sampleRate
        val out = mutableListOf<Long>()
        for (w in 3 until flux.size - 3) {
            if (flux[w] < threshold) continue
            var isMax = true
            for (k in 1..3) {
                if (flux[w - k] >= flux[w] || flux[w + k] > flux[w]) {
                    isMax = false
                    break
                }
            }
            if (isMax) out.add(((w * HOP + FRAME / 2).toDouble() / sr * 1000.0).toLong())
        }
        return out
    }
}
