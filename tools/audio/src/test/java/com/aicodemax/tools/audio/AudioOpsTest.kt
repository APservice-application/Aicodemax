package com.aicodemax.tools.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOpsTest {
    /** 1s 8kHz mono constant 0.5. */
    private fun sample(): PcmAudio = PcmAudio(8000, 1, FloatArray(8000) { 0.5f })

    @Test
    fun trimKeepsRange() {
        val out = AudioOps.trim(sample(), 250, 750)
        assertEquals(4000, out.frames)
        assertEquals(500L, out.durationMs)
    }

    @Test
    fun trimRejectsEmptyRange() {
        try {
            AudioOps.trim(sample(), 900, 100)
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("empty trim"))
        }
    }

    @Test
    fun concatJoinsMatchingClips() {
        val out = AudioOps.concat(listOf(sample(), sample()))
        assertEquals(16000, out.frames)
        try {
            AudioOps.concat(listOf(sample(), PcmAudio(44100, 1, FloatArray(100))))
            assertTrue("expected failure", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("matching"))
        }
    }

    @Test
    fun gainAppliesDbAndClips() {
        val up = AudioOps.gain(sample(), 6.0)
        assertEquals(0.5f * 1.9952624f, up.samples[0], 0.001f)
        val over = AudioOps.gain(sample(), 20.0)
        assertEquals(1f, over.samples[0], 0f)
    }

    @Test
    fun fadeRampsBothEnds() {
        val out = AudioOps.fade(sample(), 8, 8)
        // 8ms @8kHz = 64 frames; first frame gain = 1/65.
        assertEquals(0.5f / 65f, out.samples[0], 0.0001f)
        assertTrue(out.samples[4000] == 0.5f)
        assertEquals(0.5f / 65f, out.samples[7999], 0.0001f)
    }

    @Test
    fun resampleChangesRate() {
        val out = AudioOps.resample(sample(), 4000)
        assertEquals(4000, out.sampleRate)
        assertEquals(4000, out.frames)
        assertEquals(0.5f, out.samples[0], 0.0001f)
    }

    @Test
    fun peakFindsMax() {
        assertEquals(0.5f, AudioOps.peak(sample()), 0f)
    }
}
