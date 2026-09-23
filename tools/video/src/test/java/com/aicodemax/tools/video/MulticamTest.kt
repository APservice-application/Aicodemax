package com.aicodemax.tools.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MulticamTest {
    @Test
    fun syncFindsKnownLag() {
        val grid = (1..10).map { it * 1000L }
        val late = grid.map { it - 1500L }
        val res = MulticamSync.sync(listOf(grid, late), searchMs = 2000, stepMs = 50, tolMs = 120)
        assertEquals(0, res.refAngle)
        assertEquals(listOf(0L, 1500L), res.offsetsMs)
        assertTrue(res.confidence > 0.8)
    }

    @Test
    fun syncNeedsTwoAngles() {
        val res = MulticamSync.sync(listOf(listOf(1000L, 2000L)))
        assertEquals(listOf(0L), res.offsetsMs)
        assertEquals(0.0, res.confidence, 0.0)
    }

    @Test
    fun edlListsCutsAndOffsets() {
        val group = MulticamGroup(
            "mc_1", listOf("a.mp4", "b.mp4"), listOf(0L, 1500L),
            "clap", 0.9, 0, listOf(MulticamCut(5000L, 1)),
        )
        val edl = group.edl()
        assertTrue(edl.contains("TITLE: mc_1"))
        assertTrue(edl.contains("angle 2"))
        assertTrue(edl.contains("A2+1500ms"))
        assertTrue(group.summary().contains("2 มุม"))
    }
}
