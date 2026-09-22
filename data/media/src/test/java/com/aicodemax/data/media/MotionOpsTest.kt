package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-80: motion path ops. */
class MotionOpsTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun applyPathSetsPosKeysAndZoom() {
        val px = listOf(KeyPoint(0, 0f), KeyPoint(2000, 64f))
        val py = listOf(KeyPoint(0, 0f), KeyPoint(2000, 36f))
        val t = TimelineOps.applyPath(timeline(), "c1", px, py, 8)
        val keys = t.tracks[0].clips[0].keyframes!!
        assertEquals(2, keys.points("posX").size)
        assertEquals(64f, keys.points("posX")[1].value)
        assertEquals(108, t.tracks[0].clips[0].transform!!.scale)
    }

    @Test
    fun applyPathRejectsBadInput() {
        try {
            TimelineOps.applyPath(timeline(), "c1", emptyList(), emptyList(), 99)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("0..50"))
        }
        val audio = Timeline(listOf(Track("A1", MediaKind.AUDIO, listOf(Clip("a1", "au", 0, 4000, 0)))))
        try {
            TimelineOps.applyPath(audio, "a1", emptyList(), emptyList())
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("เสียง"))
        }
    }
}
