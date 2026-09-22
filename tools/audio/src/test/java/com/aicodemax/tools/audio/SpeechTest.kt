package com.aicodemax.tools.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** CP-87: speech/silence segmentation. */
class SpeechTest {
    @Test
    fun findsTwoSpeechRuns() {
        val sr = 8000
        val n = sr * 3
        val samples = FloatArray(n)
        for (i in 0 until sr) samples[i] = sin(2 * PI * 440 * i / sr).toFloat() * 0.5f
        for (i in sr * 2 until n) samples[i] = sin(2 * PI * 440 * i / sr).toFloat() * 0.5f
        val a = Speech.analyze(PcmAudio(sr, 1, samples), padMs = 0)
        assertEquals(2, a.ranges.size)
        assertTrue(a.ranges[0].startMs < 200 && a.ranges[0].endMs in 800..1200)
        assertTrue(a.ranges[1].startMs in 1800..2200 && a.ranges[1].endMs > 2800)
    }

    @Test
    fun silenceYieldsNothing() {
        val a = Speech.analyze(PcmAudio(8000, 1, FloatArray(8000)))
        assertTrue(a.ranges.isEmpty())
    }
}
