package com.aicodemax.tools.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * CP-86 §29: honest no-ML voice changer (resample pitch + ring-mod robot + echo).
 * Documented limit: resample pitch also changes speed (no formant preservation).
 */
object VoiceFx {
    /**
     * @param semitones -12..+12 pitch shift (resample; speed changes too).
     * @param robot ring-modulator robot voice.
     * @param echoMs 0..1000 echo delay (0 = off).
     * @param echoDecay 0..90 echo feedback percent.
     */
    fun apply(pcm: PcmAudio, semitones: Int, robot: Boolean, echoMs: Long, echoDecay: Int): PcmAudio {
        require(semitones in -12..12) { "semitones ต้องอยู่ -12..12" }
        require(echoMs in 0..1000) { "echo ต้องอยู่ 0..1000ms" }
        require(echoDecay in 0..90) { "echoDecay ต้องอยู่ 0..90" }
        var samples = pcm.samples.copyOf()
        val channels = pcm.channels
        if (semitones != 0) {
            val ratio = Math.pow(2.0, semitones / 12.0)
            val frames = samples.size / channels
            val outFrames = (frames / ratio).toInt().coerceAtLeast(1)
            val out = FloatArray(outFrames * channels)
            for (f in 0 until outFrames) {
                val srcPos = f * ratio
                val i0 = srcPos.toInt().coerceIn(0, frames - 1)
                val i1 = (i0 + 1).coerceIn(0, frames - 1)
                val frac = (srcPos - i0).toFloat()
                for (c in 0 until channels) {
                    out[f * channels + c] = samples[i0 * channels + c] * (1 - frac) + samples[i1 * channels + c] * frac
                }
            }
            samples = out
        }
        if (robot) {
            val frames = samples.size / channels
            val modHz = 30.0
            for (f in 0 until frames) {
                val m = (0.5 + 0.5 * sin(2 * PI * modHz * f / pcm.sampleRate)).toFloat()
                for (c in 0 until channels) samples[f * channels + c] *= m
            }
        }
        if (echoMs > 0 && echoDecay > 0) {
            val frames = samples.size / channels
            val delay = ((echoMs * pcm.sampleRate) / 1000).toInt().coerceAtLeast(1)
            val decay = echoDecay / 100f
            val out = samples.copyOf()
            for (f in delay until frames) {
                for (c in 0 until channels) {
                    out[f * channels + c] = (samples[f * channels + c] + out[(f - delay) * channels + c] * decay)
                        .coerceIn(-1f, 1f)
                }
            }
            samples = out
        }
        return PcmAudio(pcm.sampleRate, channels, samples)
    }
}
