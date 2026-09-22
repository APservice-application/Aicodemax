package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-85: volume + bulk markers ops. */
class VolumeMarkerOpsTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun volumeSet() {
        val t = TimelineOps.volume(timeline(), "c1", 55)
        assertEquals(55, t.tracks[0].clips[0].volume)
        try {
            TimelineOps.volume(timeline(), "c1", 150)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("0..100"))
        }
    }

    @Test
    fun addMarkersBulk() {
        val t = TimelineOps.addMarkers(timeline(), listOf(TimelineMarker("m1", 100, "บีต1"), TimelineMarker("m2", 200)))
        assertEquals(2, t.markers.size)
        try {
            TimelineOps.addMarkers(timeline(), emptyList())
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ไม่มีมาร์กเกอร์"))
        }
    }
}
