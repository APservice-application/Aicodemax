package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimelineOpsTest {
    private fun timeline(): Timeline {
        val clip = Clip("c1", "a1", 0, 4000, 1000)
        return Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
    }

    @Test
    fun splitDividesSourceRange() {
        val next = TimelineOps.split(timeline(), "c1", 3000, "c2")
        val clips = next.tracks[0].clips.sortedBy { it.atMs }
        assertEquals(2, clips.size)
        assertEquals(Clip("c1", "a1", 0, 2000, 1000), clips[0])
        assertEquals(Clip("c2", "a1", 2000, 4000, 3000), clips[1])
    }

    @Test
    fun splitOutsideRangeFails() {
        try {
            TimelineOps.split(timeline(), "c1", 900, "c2")
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("จุดแยก"))
        }
        try {
            TimelineOps.split(timeline(), "missing", 2000, "c2")
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ไม่มีคลิป"))
        }
    }

    @Test
    fun trimMoveDeleteDuplicate() {
        var t = TimelineOps.trim(timeline(), "c1", 500, 3500, null)
        assertEquals(500, t.tracks[0].clips[0].startMs)
        try {
            TimelineOps.trim(t, "c1", null, 99999, null, maxEndMs = 4000)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("4000"))
        }
        t = TimelineOps.move(t, "c1", 8000, null, MediaKind.VIDEO)
        assertEquals(8000, t.tracks[0].clips[0].atMs)
        t = TimelineOps.duplicate(t, "c1", null, "c9")
        assertEquals(2, t.tracks[0].clips.size)
        assertEquals(11000, t.tracks[0].clips.first { it.id == "c9" }.atMs)
        t = TimelineOps.delete(t, "c1")
        assertEquals(listOf("c9"), t.tracks[0].clips.map { it.id })
    }

    @Test
    fun moveAcrossKindFails() {
        val t = timeline().copy(tracks = timeline().tracks + Track("A1", MediaKind.AUDIO))
        try {
            TimelineOps.move(t, "c1", 0, "A1", MediaKind.VIDEO)
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ข้ามชนิด"))
        }
    }

    @Test
    fun markersAndFlags() {
        var t = TimelineOps.addMarker(timeline(), TimelineMarker("m1", 2000, "hi"))
        assertEquals(1, t.markers.size)
        t = TimelineOps.removeMarker(t, "m1")
        assertTrue(t.markers.isEmpty())
        t = TimelineOps.trackFlags(t, "V1", locked = true, muted = null, hidden = true, color = "red")
        assertTrue(t.tracks[0].locked && t.tracks[0].hidden)
        try {
            TimelineOps.delete(t, "c1")
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ล็อก"))
        }
    }
}
