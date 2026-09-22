package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-84: Ken Burns ops + slideshow builder. */
class MotionSlideOpsTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun motionSetAndClear() {
        val t = TimelineOps.motion(timeline(), "c1", ClipMotion("left", 30))
        assertEquals("KB:left+30", t.tracks[0].clips[0].motion!!.summary())
        val cleared = TimelineOps.motion(t, "c1", null)
        assertEquals(null, cleared.tracks[0].clips[0].motion)
        val identity = TimelineOps.motion(t, "c1", ClipMotion("in", 0))
        assertEquals(null, identity.tracks[0].clips[0].motion)
    }

    @Test
    fun motionRejectsBadInput() {
        try {
            TimelineOps.motion(timeline(), "c1", ClipMotion("diagonal", 20))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ทิศ"))
        }
        val audio = Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(Clip("a1", "au", 0, 4000, 0)))))
        try {
            TimelineOps.motion(audio, "a1", ClipMotion())
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("เสียง"))
        }
    }

    @Test
    fun slideshowAppends() {
        val t = TimelineOps.slideshow(timeline(), listOf("p1", "p2", "p3"), 2000, 300, listOf("s1", "s2", "s3"))
        val clips = t.tracks[0].clips
        assertEquals(4, clips.size)
        assertEquals(5000L, clips[1].atMs)
        assertEquals(7000L, clips[2].atMs)
        assertEquals("in", clips[1].motion!!.direction)
        assertEquals("out", clips[2].motion!!.direction)
        assertEquals(300L, clips[1].transitionIn!!.durationMs)
        try {
            TimelineOps.slideshow(timeline(), emptyList(), 2000, 0, emptyList())
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("อย่างน้อย"))
        }
    }
}
