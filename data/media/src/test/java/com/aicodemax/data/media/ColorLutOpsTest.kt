package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-81: LUT ops + extended ClipColor validation. */
class ColorLutOpsTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun lutSetAndClear() {
        val t = TimelineOps.lut(timeline(), "c1", ClipLut("/tmp/warm.cube", 80))
        assertEquals("LUT:warm.cube@80%", t.tracks[0].clips[0].lut!!.summary())
        val cleared = TimelineOps.lut(t, "c1", null)
        assertEquals(null, cleared.tracks[0].clips[0].lut)
    }

    @Test
    fun lutRejectsBadInput() {
        try {
            TimelineOps.lut(timeline(), "c1", ClipLut("/tmp/w.cube", 150))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("0..100"))
        }
        val audio = Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(Clip("a1", "au", 0, 4000, 0)))))
        try {
            TimelineOps.lut(audio, "a1", ClipLut("/tmp/w.cube"))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("เสียง"))
        }
    }

    @Test
    fun clipColorNewFieldsValidate() {
        assertTrue(ClipColor(exposure = 10, whites = 5, blacks = -5).validate().isEmpty())
        assertTrue(ClipColor(exposure = 101).validate().isNotEmpty())
        assertEquals("ex10 wh5 bl-5", ClipColor(exposure = 10, whites = 5, blacks = -5).summary())
    }
}
