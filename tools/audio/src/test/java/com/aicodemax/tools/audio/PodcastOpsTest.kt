package com.aicodemax.tools.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-95: mix/normalize/autocut. */
class PodcastOpsTest {
    @Test
    fun mixAddsBed() {
        val a = PcmAudio(8000, 1, FloatArray(8000) { 0.5f })
        val b = PcmAudio(8000, 1, FloatArray(4000) { 0.25f })
        val out = AudioOps.mix(a, b, gainB = 1.0)
        assertEquals(8000, out.frames)
        assertEquals(0.75f, out.samples[0], 0.001f)
        assertEquals(0.5f, out.samples[7999], 0.001f)
    }

    @Test
    fun normalizeHitsPeak() {
        val out = AudioOps.normalize(PcmAudio(8000, 1, FloatArray(8000) { 0.25f }), -6.0)
        assertEquals(0.501f, AudioOps.peak(out), 0.01f)
    }

    @Test
    fun autocutKeepsSpeech() {
        val samples = FloatArray(8000 * 3)
        for (i in 0 until 8000) samples[i] = 0.5f
        for (i in 16000 until 24000) samples[i] = 0.5f
        val src = PcmAudio(8000, 1, samples)
        val ranges = Speech.analyze(src, padMs = 0).ranges
        assertEquals(2, ranges.size)
        val out = AudioOps.autocut(src, ranges)
        assertTrue(out.durationMs in 1500..2500)
    }
}
