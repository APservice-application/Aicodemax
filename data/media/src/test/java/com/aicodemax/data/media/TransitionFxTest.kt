package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-77: transitions + basic fx ops. */
class TransitionFxTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun transitionSetAndClear() {
        var t = TimelineOps.transition(timeline(), "c1", "in", "dissolve", 800)
        t = TimelineOps.transition(t, "c1", "out", "fade", 300)
        val clip = t.tracks[0].clips[0]
        assertEquals("dissolve800", clip.transitionIn!!.summary())
        assertEquals("fade300", clip.transitionOut!!.summary())
        t = TimelineOps.clearTransition(t, "c1", "in")
        assertEquals(null, t.tracks[0].clips[0].transitionIn)
        t = TimelineOps.clearTransition(t, "c1")
        assertEquals(null, t.tracks[0].clips[0].transitionOut)
    }

    @Test
    fun transitionRejectsBadInput() {
        try {
            TimelineOps.transition(timeline(), "c1", "out", "dissolve")
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ขาout"))
        }
        try {
            TimelineOps.transition(timeline(), "c1", "in", "fade", 50)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("100..2000"))
        }
        val short = Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(Clip("c9", "a1", 0, 1000, 0)))))
        try {
            TimelineOps.transition(short, "c9", "in", "fade", 1500)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ยาวกว่าคลิป"))
        }
        val audio = Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(Clip("a1", "au", 0, 4000, 0)))))
        try {
            TimelineOps.transition(audio, "a1", "in", "wipeleft")
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fade/cut"))
        }
        val fok = TimelineOps.transition(audio, "a1", "in", "fade", 200)
        assertEquals("fade200", fok.tracks[0].clips[0].transitionIn!!.summary())
    }

    @Test
    fun fxSetAndAudioGuard() {
        val t = TimelineOps.fx(timeline(), "c1", ClipFx(blur = 4, vignette = 50, grain = 20))
        assertEquals("b4/v50/g20", t.tracks[0].clips[0].fx!!.summary())
        val cleared = TimelineOps.fx(t, "c1", ClipFx())
        assertEquals(null, cleared.tracks[0].clips[0].fx)
        try {
            TimelineOps.fx(timeline(), "c1", ClipFx(blur = 99))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("0..10"))
        }
        val audio = Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(Clip("a1", "au", 0, 4000, 0)))))
        try {
            TimelineOps.fx(audio, "a1", ClipFx(grain = 10))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("เสียง"))
        }
    }

    @Test
    fun splitClearsCutEdges() {
        var t = TimelineOps.transition(timeline(), "c1", "in", "fade", 500)
        t = TimelineOps.transition(t, "c1", "out", "fade", 500)
        t = TimelineOps.fx(t, "c1", ClipFx(grain = 30))
        val split = TimelineOps.split(t, "c1", 3000, "c2")
        val clips = split.tracks[0].clips.sortedBy { it.atMs }
        assertEquals("fade500", clips[0].transitionIn!!.summary())
        assertEquals(null, clips[0].transitionOut)
        assertEquals(null, clips[1].transitionIn)
        assertEquals("fade500", clips[1].transitionOut!!.summary())
        assertEquals(30, clips[0].fx!!.grain)
        assertEquals(30, clips[1].fx!!.grain)
    }
}
