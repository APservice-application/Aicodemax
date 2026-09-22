package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-78: color correction ops + presets. */
class ColorOpsTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun colorSetAndIdentityClears() {
        val t = TimelineOps.color(timeline(), "c1", ClipColor(brightness = 20, saturation = -100))
        assertEquals("br20 st-100", t.tracks[0].clips[0].color!!.summary())
        val cleared = TimelineOps.color(t, "c1", ClipColor())
        assertEquals(null, cleared.tracks[0].clips[0].color)
        assertEquals(-100, ClipColor.preset("bw").saturation)
        assertEquals(30, ClipColor.preset("warm").temperature)
        assertTrue(ClipColor.preset("none").isIdentity)
    }

    @Test
    fun colorRejectsBadInput() {
        try {
            TimelineOps.color(timeline(), "c1", ClipColor(brightness = 500))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("-100..100"))
        }
        val audio = Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(Clip("a1", "au", 0, 4000, 0)))))
        try {
            TimelineOps.color(audio, "a1", ClipColor(brightness = 10))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("เสียง"))
        }
    }
}
