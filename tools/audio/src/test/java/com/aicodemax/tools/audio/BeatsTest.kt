package com.aicodemax.tools.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-85: beat detection on synthetic clicks. */
class BeatsTest {
    private fun clicks(bpm: Double, seconds: Int, sr: Int = 8000): PcmAudio {
        val n = seconds * sr
        val samples = FloatArray(n) { (Math.random() * 0.02 - 0.01).toFloat() }
        val period = (60.0 / bpm * sr).toInt()
        var i = 0
        while (i < n) {
            samples[i] = 1.0f
            if (i + 1 < n) samples[i + 1] = 0.6f
            i += period
        }
        return PcmAudio(sr, 1, samples)
    }

    @Test
    fun detects120bpm() {
        val a = Beats.analyze(clicks(120.0, 4))
        assertTrue("bpm=${a.bpm}", a.bpm in 100.0..140.0)
        assertTrue("beats=${a.beatsMs.size}", a.beatsMs.size in 5..10)
        assertTrue(a.confidence > 0.15)
    }

    @Test
    fun silenceHasNoPulse() {
        val a = Beats.analyze(PcmAudio(8000, 1, FloatArray(8000 * 2)))
        assertTrue(a.bpm == 0.0 && a.beatsMs.isEmpty())
    }
}
