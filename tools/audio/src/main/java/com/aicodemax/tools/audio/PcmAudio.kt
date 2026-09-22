package com.aicodemax.tools.audio

/**
 * CP-62: platform-free PCM (mono/stereo float samples, -1..1, interleaved).
 * All audio math lives here so it is unit-testable; Android only decodes
 * compressed formats into PCM and encodes WAV back out.
 */
data class PcmAudio(
    val sampleRate: Int,
    val channels: Int,
    val samples: FloatArray,
) {
    init {
        require(sampleRate > 0) { "bad sample rate $sampleRate" }
        require(channels in 1..2) { "only mono/stereo, got $channels" }
        require(samples.size % channels == 0) { "samples ${samples.size} not a whole number of frames" }
    }

    val frames: Int get() = samples.size / channels
    val durationMs: Long get() = frames * 1000L / sampleRate

    fun frame(i: Int): FloatArray = FloatArray(channels) { c -> samples[i * channels + c] }

    override fun equals(other: Any?): Boolean =
        other is PcmAudio && sampleRate == other.sampleRate && channels == other.channels &&
            samples.contentEquals(other.samples)

    override fun hashCode(): Int = 31 * (31 * sampleRate + channels) + samples.contentHashCode()
}

/** Pure PCM operations — no codecs, tested on JVM. */
object AudioOps {
    /** Keeps [startMs, endMs) (clamped). Empty range fails honestly. */
    fun trim(src: PcmAudio, startMs: Long, endMs: Long): PcmAudio {
        val start = ((startMs * src.sampleRate) / 1000).coerceIn(0L, src.frames.toLong()).toInt()
        val end = ((endMs * src.sampleRate) / 1000).coerceIn(0L, src.frames.toLong()).toInt()
        require(end > start) { "empty trim range ($startMs..$endMs ms)" }
        return PcmAudio(src.sampleRate, src.channels, src.samples.copyOfRange(start * src.channels, end * src.channels))
    }

    /** Joins clips (must share sample rate + channels). */
    fun concat(parts: List<PcmAudio>): PcmAudio {
        require(parts.isNotEmpty()) { "nothing to concat" }
        val first = parts.first()
        require(parts.all { it.sampleRate == first.sampleRate && it.channels == first.channels }) {
            "concat needs matching sample rate + channels"
        }
        val total = parts.sumOf { it.samples.size }
        val out = FloatArray(total)
        var pos = 0
        for (part in parts) {
            part.samples.copyInto(out, pos)
            pos += part.samples.size
        }
        return PcmAudio(first.sampleRate, first.channels, out)
    }

    /** Applies [db] gain (clipped to -1..1). */
    fun gain(src: PcmAudio, db: Double): PcmAudio {
        val factor = Math.pow(10.0, db / 20.0).toFloat()
        return PcmAudio(src.sampleRate, src.channels, FloatArray(src.samples.size) { i ->
            (src.samples[i] * factor).coerceIn(-1f, 1f)
        })
    }

    /** Linear fade-in over [ms], fade-out over the tail. */
    fun fade(src: PcmAudio, fadeInMs: Long, fadeOutMs: Long): PcmAudio {
        val inFrames = ((fadeInMs * src.sampleRate) / 1000).coerceIn(0L, src.frames.toLong()).toInt()
        val outFrames = ((fadeOutMs * src.sampleRate) / 1000).coerceIn(0L, src.frames.toLong()).toInt()
        val out = src.samples.copyOf()
        for (i in 0 until inFrames) {
            val g = (i + 1).toFloat() / (inFrames + 1)
            for (c in 0 until src.channels) out[i * src.channels + c] *= g
        }
        for (i in 0 until outFrames) {
            val g = (i + 1).toFloat() / (outFrames + 1)
            val frame = src.frames - 1 - i
            for (c in 0 until src.channels) out[frame * src.channels + c] *= g
        }
        return PcmAudio(src.sampleRate, src.channels, out)
    }

    /** Down/upsamples to [targetRate] (linear interpolation). */
    fun resample(src: PcmAudio, targetRate: Int): PcmAudio {
        require(targetRate > 0) { "bad target rate $targetRate" }
        if (targetRate == src.sampleRate) return src
        val ratio = src.sampleRate.toDouble() / targetRate
        val frames = maxOf(1, (src.frames / ratio).toInt())
        val out = FloatArray(frames * src.channels)
        for (i in 0 until frames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, src.frames - 1)
            val i1 = (i0 + 1).coerceIn(0, src.frames - 1)
            val t = (pos - pos.toInt()).toFloat()
            for (c in 0 until src.channels) {
                out[i * src.channels + c] =
                    src.samples[i0 * src.channels + c] * (1 - t) + src.samples[i1 * src.channels + c] * t
            }
        }
        return PcmAudio(targetRate, src.channels, out)
    }

    /**
     * CP-95: mixes [b] under [a] at [offsetMs] with linear gain [gainB].
     * [b] is auto-resampled to [a]'s rate; mono is upmixed to stereo.
     */
    fun mix(a: PcmAudio, b: PcmAudio, gainB: Double = 1.0, offsetMs: Long = 0): PcmAudio {
        require(gainB in 0.0..4.0) { "gainB ต้องอยู่ 0..4" }
        var bb = resample(b, a.sampleRate)
        if (bb.channels == 1 && a.channels == 2) {
            bb = PcmAudio(bb.sampleRate, 2, FloatArray(bb.frames * 2) { i -> bb.samples[i / 2] })
        }
        require(bb.channels == a.channels) { "mix needs matching channels" }
        val off = ((offsetMs * a.sampleRate) / 1000).coerceAtLeast(0).toInt()
        val total = maxOf(a.frames, off + bb.frames)
        val out = FloatArray(total * a.channels)
        a.samples.copyInto(out, 0, 0, a.samples.size)
        val g = gainB.toFloat()
        for (f in 0 until bb.frames) {
            for (c in 0 until a.channels) {
                val idx = (off + f) * a.channels + c
                out[idx] = (out[idx] + bb.samples[f * bb.channels + c] * g).coerceIn(-1f, 1f)
            }
        }
        return PcmAudio(a.sampleRate, a.channels, out)
    }

    /** CP-95: scales so the peak hits [peakDb] (default -3). Silence passes through. */
    fun normalize(src: PcmAudio, peakDb: Double = -3.0): PcmAudio {
        require(peakDb in -24.0..0.0) { "peakDb ต้องอยู่ -24..0" }
        val peak = peak(src)
        if (peak <= 0f) return src
        val target = Math.pow(10.0, peakDb / 20.0).toFloat()
        val k = target / peak
        return PcmAudio(src.sampleRate, src.channels, FloatArray(src.samples.size) { (src.samples[it] * k).coerceIn(-1f, 1f) })
    }

    /** CP-95: keeps only [ranges] (source ms), concatenated. Empty → honest error. */
    fun autocut(src: PcmAudio, ranges: List<SpeechRange>): PcmAudio {
        val kept = ranges.mapNotNull { r ->
            val s = r.startMs.coerceAtLeast(0)
            val e = r.endMs.coerceAtMost(src.durationMs)
            if (e - s >= 50) trim(src, s, e) else null
        }
        require(kept.isNotEmpty()) { "ไม่มีช่วงเสียงให้เก็บ" }
        return concat(kept)
    }

    /** Peak level 0..1 (analysis helper). */
    fun peak(src: PcmAudio): Float {
        var max = 0f
        for (sample in src.samples) {
            val abs = kotlin.math.abs(sample)
            if (abs > max) max = abs
        }
        return max
    }
}
