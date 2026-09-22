package com.aicodemax.tools.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** CP-87 §32/§40: one speech segment (source-relative ms). */
data class SpeechRange(val startMs: Long, val endMs: Long)

data class SpeechAnalysis(
    val ranges: List<SpeechRange>,
    /** Mean dBFS per window (for highlight scoring). */
    val energyDb: List<Double>,
    val windowMs: Long,
    val durationMs: Long,
)

/**
 * CP-87: honest no-ML speech/silence segmentation (energy gate + hangover + padding).
 * Pure Kotlin, JVM-tested with synthetic tone/silence.
 */
object Speech {
    fun analyze(
        pcm: PcmAudio,
        thresholdDb: Double = -40.0,
        minSpeechMs: Long = 300,
        minSilenceMs: Long = 500,
        padMs: Long = 150,
        windowMs: Long = 100,
    ): SpeechAnalysis {
        val sr = pcm.sampleRate
        val frames = pcm.samples.size / pcm.channels
        val durMs = (frames.toDouble() / sr * 1000).toLong()
        val winFrames = ((windowMs * sr) / 1000).toInt().coerceAtLeast(1)
        val nWin = (frames + winFrames - 1) / winFrames
        val db = DoubleArray(nWin) { w ->
            val base = w * winFrames
            val end = minOf(base + winFrames, frames)
            var e = 0.0
            for (f in base until end) {
                var s = 0.0
                for (c in 0 until pcm.channels) s += pcm.samples[f * pcm.channels + c]
                s /= pcm.channels
                e += s * s
            }
            val rms = sqrt(e / (end - base).coerceAtLeast(1))
            if (rms <= 0.0) -120.0 else 20 * log10(rms)
        }
        val speech = BooleanArray(nWin) { db[it] >= thresholdDb }
        // Hangover: bridge silences shorter than minSilenceMs.
        val minSilWin = ((minSilenceMs + windowMs - 1) / windowMs).toInt().coerceAtLeast(1)
        var w = 0
        while (w < nWin) {
            if (!speech[w]) {
                var e = w
                while (e < nWin && !speech[e]) e++
                if (e - w < minSilWin && w > 0 && e < nWin) {
                    for (k in w until e) speech[k] = true
                }
                w = e
            } else {
                w++
            }
        }
        // Collect runs, drop short speech, pad + clamp.
        val minSpeechWin = ((minSpeechMs + windowMs - 1) / windowMs).toInt().coerceAtLeast(1)
        val ranges = mutableListOf<SpeechRange>()
        w = 0
        while (w < nWin) {
            if (speech[w]) {
                var e = w
                while (e < nWin && speech[e]) e++
                if (e - w >= minSpeechWin) {
                    val s = (w * windowMs - padMs).coerceAtLeast(0)
                    val en = (e * windowMs + padMs).coerceAtMost(durMs)
                    ranges.add(SpeechRange(s, en))
                }
                w = e
            } else {
                w++
            }
        }
        return SpeechAnalysis(ranges, db.toList(), windowMs, durMs)
    }
}
