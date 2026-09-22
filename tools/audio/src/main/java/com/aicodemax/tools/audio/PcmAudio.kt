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
