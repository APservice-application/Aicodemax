package com.aicodemax.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** CP-87: autocut op. */
class AutocutOpsTest {
    @Test
    fun autocutKeepsRanges() {
        val clip = Clip("c1", "a1", 0, 10000, 500)
        val t = Timeline(listOf(Track("V1", MediaKind.VIDEO, listOf(clip))))
        val out = TimelineOps.autocut(t, "c1", listOf(0L to 2000L, 5000L to 7000L), listOf("s1", "s2"))
        val clips = out.tracks[0].clips
        assertEquals(2, clips.size)
        assertEquals(500L, clips[0].atMs)
        assertEquals(2500L, clips[1].atMs)
        assertEquals(5000L, clips[1].startMs)
        try {
            TimelineOps.autocut(t, "c1", listOf(0L to 20000L), listOf("x"))
            fail("expected IAE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("นอกคลิป"))
        }
    }
}
