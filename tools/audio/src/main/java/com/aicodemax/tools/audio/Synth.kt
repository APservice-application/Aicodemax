package com.aicodemax.tools.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * CP-86 §30: procedural offline music beds + SFX (sine stacks + noise envelopes → WAV).
 * Royalty-free by construction (generated, not sampled).
 */
object Synth {
    const val SR = 22050
    val MUSIC_STYLES = listOf("calm", "bright", "tense", "dark")
    val SFX_KINDS = listOf("impact", "riser", "whoosh", "click", "success")

    /** Chord-pad bed: 4-chord loop with soft attack + tremolo. seconds 1..120. */
    fun musicBed(style: String, seconds: Int): PcmAudio {
        require(style in MUSIC_STYLES) { "สไตล์ต้องเป็น ${MUSIC_STYLES.joinToString("/")} (ได้ $style)" }
        require(seconds in 1..120) { "ความยาวต้องอยู่ 1..120วิ" }
        val roots = when (style) {
            "bright" -> doubleArrayOf(261.63, 329.63, 392.00, 523.25) // C E G C
            "tense" -> doubleArrayOf(110.00, 116.54, 110.00, 103.83) // A Bb A Ab
            "dark" -> doubleArrayOf(82.41, 98.00, 110.00, 87.31) // E G A F
            else -> doubleArrayOf(220.00, 174.61, 146.83, 196.00) // A F D G
        }
        val n = seconds * SR
        val samples = FloatArray(n)
        val chordLen = n / 4
        for (i in 0 until n) {
            val chord = (i / chordLen).coerceIn(0, 3)
            val root = roots[chord]
            val pos = (i % chordLen).toDouble() / chordLen
            val attack = minOf(1.0, pos * 4.0) * minOf(1.0, (1.0 - pos) * 4.0 + 0.25)
            val trem = 0.85 + 0.15 * sin(2 * PI * 4.0 * i / SR)
            val v = (sin(2 * PI * root * i / SR) * 0.30 +
                sin(2 * PI * root * 1.25 * i / SR) * 0.18 +
                sin(2 * PI * root * 1.5 * i / SR) * 0.18 +
                sin(2 * PI * root * 2.0 * i / SR) * 0.10) * attack * trem
            samples[i] = (v * 0.5).toFloat()
        }
        return PcmAudio(SR, 1, samples)
    }

    /** One-shot effect (mono). */
    fun sfx(kind: String): PcmAudio {
        require(kind in SFX_KINDS) { "sfx ต้องเป็น ${SFX_KINDS.joinToString("/")} (ได้ $kind)" }
        val seconds = when (kind) {
            "riser" -> 2.0
            "whoosh" -> 1.2
            "success" -> 1.0
            "impact" -> 0.8
            else -> 0.25
        }
        val n = (seconds * SR).toInt()
        val samples = FloatArray(n)
        var noiseSeed = 12345L
        fun noise(): Double {
            noiseSeed = (noiseSeed * 1103515245 + 12345) and 0x7FFFFFFF
            return (noiseSeed.toDouble() / 0x7FFFFFFF) * 2.0 - 1.0
        }
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val p = i.toDouble() / n
            samples[i] = when (kind) {
                "impact" -> ((sin(2 * PI * 60 * t) * 0.8 + noise() * 0.4) * Math.exp(-p * 6.0) * 0.7).toFloat()
                "riser" -> {
                    val f = 200.0 + 1800.0 * p
                    ((sin(2 * PI * f * t) * 0.4 + noise() * 0.3 * p) * p * 0.6).toFloat()
                }
                "whoosh" -> ((noise() * 0.6) * sin(PI * p) * 0.6).toFloat()
                "click" -> ((sin(2 * PI * 2000 * t) * 0.7) * Math.exp(-p * 12.0) * 0.6).toFloat()
                else -> {
                    // success: C-E-G arpeggio blips
                    val f = doubleArrayOf(523.25, 659.25, 783.99)[(p * 3).toInt().coerceIn(0, 2)]
                    val local = (p * 3) % 1.0
                    ((sin(2 * PI * f * t) * 0.6) * Math.exp(-local * 4.0) * 0.6).toFloat()
                }
            }
        }
        return PcmAudio(SR, 1, samples)
    }
}
