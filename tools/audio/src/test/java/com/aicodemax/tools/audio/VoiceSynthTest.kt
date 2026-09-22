package com.aicodemax.tools.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** CP-86: voice FX + procedural synth. */
class VoiceSynthTest {
    private fun sine(hz: Double, seconds: Double, sr: Int = 22050): PcmAudio {
        val n = (seconds * sr).toInt()
        return PcmAudio(sr, 1, FloatArray(n) { sin(2 * PI * hz * it / sr).toFloat() })
    }

    private fun zeroCrossings(p: PcmAudio): Int {
        var z = 0
        for (i in 1 until p.samples.size) {
            if ((p.samples[i - 1] < 0) != (p.samples[i] < 0)) z++
        }
        return z
    }

    @Test
    fun pitchOctaveUpDoublesFrequency() {
        val src = sine(440.0, 1.0)
        val shifted = VoiceFx.apply(src, 12, false, 0, 0)
        // Same cycles, half the samples → frequency doubled.
        assertTrue(shifted.samples.size < src.samples.size * 0.55)
        val baseF = zeroCrossings(src).toDouble() / src.samples.size
        val upF = zeroCrossings(shifted).toDouble() / shifted.samples.size
        assertTrue("baseF=$baseF upF=$upF", upF > baseF * 1.8 && upF < baseF * 2.2)
    }

    @Test
    fun robotAndEchoChangeSignal() {
        val src = sine(440.0, 0.5)
        val robot = VoiceFx.apply(src, 0, true, 0, 0)
        assertTrue(robot.samples.zip(src.samples).any { (a, b) -> kotlin.math.abs(a - b) > 0.01f })
        val echo = VoiceFx.apply(src, 0, false, 100, 50)
        assertTrue(echo.samples.size == src.samples.size)
        try {
            VoiceFx.apply(src, 99, false, 0, 0)
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("semitones"))
        }
    }

    @Test
    fun musicBedAndSfx() {
        val bed = Synth.musicBed("calm", 3)
        assertEquals(22050, bed.sampleRate)
        assertEquals(22050 * 3, bed.samples.size)
        assertTrue(bed.samples.any { kotlin.math.abs(it) > 0.05f })
        for (kind in Synth.SFX_KINDS) {
            val s = Synth.sfx(kind)
            assertTrue("$kind empty", s.samples.any { it != 0f })
        }
        try {
            Synth.musicBed("nope", 3)
            assertTrue(false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("สไตล์"))
        }
    }
}
